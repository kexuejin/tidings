// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import com.kapp.tidings.agent.ChainDiff
import com.kapp.tidings.agent.ChainProgress
import com.kapp.tidings.agent.ChainStage
import com.kapp.tidings.agent.ControlPlane
import com.kapp.tidings.agent.SandboxPatchRef
import com.kapp.tidings.agent.StageChain
import com.kapp.tidings.core.protocol.ConvId
import com.kapp.tidings.domain.RelayController
import com.kapp.tidings.domain.RelayUiState
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonObject

/**
 * Host-side worker and ticket orchestrator implementing DSH core principles:
 * 1. Minimal human attention: single worker handles end-to-end assignment without manual role splitting.
 * 2. Thread follow-up direct routing: messages in active thread resume or abort current ticket.
 * 3. Hand-back on out-of-scope work: polite rejection with concrete reason when repo is missing or invalid.
 * 4. In-place card refresh: single card slot per ticket with monotonic rev increments.
 */
internal class DesktopWorkerOrchestrator(
    private val planeProvider: () -> ControlPlane?,
    private val controller: RelayController,
    private val scope: CoroutineScope,
) {
    /**
     * 工单的**可等待终局**：invoke 执行桥（真机 `@agent` 派单）await 它拿到请求-响应
     * 语义的结果，而群里的可见面仍由工单卡承担。
     *
     * `cardJson` 是终态工单卡的 JSON 文本 —— 等待方把它随结果带回域层（有卡就不补
     * 「没干完」横幅）；`patchPath` 是沙箱产物 patch.diff 的本机路径，供执行桥投群。
     */
    data class TicketOutcome(
        val state: String,
        val summary: String?,
        val error: String?,
        val cardJson: String?,
        val patchPath: String? = null,
    )

    data class ActiveTicket(
        val ticketId: String,
        val convId: String,
        val groupId: String?,
        val job: Job,
        val title: String,
        val repoPath: String,
        @Volatile var stage: String,
        val rev: AtomicInteger = AtomicInteger(0),
        val role: String = "Worker",
        /**
         * 这张卡的**驱动键**（codex/claude/antigravity…），广播与授权共用它。
         * 曾经写死 `tidings-worker`：卡面看不出是谁干的还在其次 —— 用户点卡上
         * 的 Approve，动作帧按卡片的 agentId 过 `AgentExecAuthorizer`，假名
         * 过不了授权，「已提交，等待宿主确认」永远等不来执行（真机实锤）。
         */
        val agentKey: String = "codex",
        @Volatile var onFollowUp: (suspend (String) -> Unit)? = null,
        /**
         * 这张工单**当初要做什么**（用户原话，未经 containment 前导包装）。
         *
         * 只为一件事存在：受阻卡上的 [Retry] 要能把它重跑一遍。没有它，
         * `retry` 这个 actionId 在宿主没有分支可落 —— 真机实测两种错法：
         * 开发卡上被当成「转发给插件」丢掉（`card action retry ... has no local plugin`），
         * 原型卡上落进 `onFollowUp` 被当成一句**微调**（点 Retry 出来的是
         * 「Refined prototype ready」，什么都没重试）。
         */
        @Volatile var devPrompt: String? = null,
        /** 终局一次性完成（成功/受阻/退单/中止/原型待选）；后续再 complete 是无操作。 */
        val done: CompletableDeferred<TicketOutcome> = CompletableDeferred(),
    )

    private val activeTickets = ConcurrentHashMap<String, ActiveTicket>()

    /**
     * 「卡片 → 沙箱补丁」登记簿：合并按钮只带 `cardId`，宿主必须能凭它找回这次沙箱的
     * （工作区, patch 路径）。
     *
     * 为什么不能只靠 `ControlPlane.latestPatchRefForCard`：那条路是**会话台账**反查 ——
     * 先找「事件里有一条 type=card 且 body 含 cardId」的会话，再取该会话的 patch 产物。
     * 但工单卡是 `controller.broadcastChainCard` 直接广播出去的，从不写任何会话台账，
     * 于是对工单卡恒为 null。真机实测：点「合并此补丁」得到
     * `no sandbox patch recorded for card chain-tmvyqfi84`，宿主仓一个字节都没变，
     * `merged` 终态因此从未在设备上出现过。
     *
     * 谁发布卡片谁登记补丁：编排器在发 review_ready 卡的那一刻就知道 patch 路径，
     * 这一跳不该再靠别处反推。
     */
    private val patchByCard = ConcurrentHashMap<String, SandboxPatchRef>()

    /**
     * 卡片 → 群：合并成功后要把**同一张卡**翻成「已合并」，得知道往哪个群发。
     *
     * 从前是靠扫 `controller.state.messages` 反推（找 cardId 相同的行，取 peerId 解群号）。
     * 扫不到时 `appliedGroupId` 为 null，`publishCardNow` 首行就 `return` —— 合并成功、
     * 宿主仓已改、手机上却停在「待合并」，而且**一行日志都没有**。真机实测：
     * `patch merged into workspace by …` 之后什么都没有，卡面还是待合并。
     * 群号在派单时就是已知的，不该事后反推。
     */
    private val groupByCard = ConcurrentHashMap<String, String>()

    /** 合并分支的第一判据：编排器自己记的账（见 [patchByCard]）。 */
    fun patchRefForCard(cardId: String): SandboxPatchRef? = patchByCard[cardId]

    /** 合并成功后翻面卡该发到哪个群（见 [groupByCard]）。 */
    fun groupForCard(cardId: String): String? = groupByCard[cardId]

    fun activeTicketsForConv(convId: String): List<ActiveTicket> =
        activeTickets.values.filter { it.convId == convId }

    fun activeTicketForConv(convId: String): ActiveTicket? =
        activeTickets.values.filter { it.convId == convId }.maxByOrNull { it.ticketId }

    fun isAbortSignal(text: String): Boolean = AbortSignals.matches(text)

    /** 整条就是打断指令（`stop` / `停`…），不是「以它开头的正常话」。 */
    fun isExactAbortSignal(text: String): Boolean = AbortSignals.isExact(text)

    fun isPrototypeIntent(prompt: String): Boolean {
        val lower = prompt.lowercase()
        return lower.contains("\u539f\u578b") || lower.contains("prototype") || lower.contains("mockup")
    }

    /**
     * 去掉行首的 `@提及`，只留下「用户到底说了什么」。
     *
     * 为什么需要：`@codex-e2e 停` 与裸 `停` 是同一个意图，但路由判据看的是原文 ——
     * 前者会因为「有 @」被当成 prompt 走派单，后者压根不是 prompt。打断判据不该
     * 因为用户顺手 @了一下就失效。
     */
    internal fun stripMentions(text: String): String = AbortSignals.stripMentions(text)

    /**
     * 入站文本的**打断前移判定**（测试方案 §6.1）。
     *
     * 返回 `true` = 这条消息已被当作打断消费掉，调用方不要再派单。
     * 没有活跃工单时返回 `false`：那时「停」就是一句普通的话，不该被吞。
     *
     * **取消同步发出**：`job.cancel()` 只置协程状态，真正的 SIGTERM 由沙箱在 ≤100ms
     * 的轮询周期里发出（`WorktreeSandbox.POLL_MS`），所以从入站到进程被摘是一条
     * 不经过网络的路径 —— 卡片的发布才走网络，排在取消之后。
     */
    internal fun abortIfRequested(convId: String, text: String): Boolean {
        if (!isAbortSignal(text)) return false
        val targets = activeTicketsForConv(convId)
        if (targets.isEmpty()) return false
        for (active in targets) {
            active.stage = "aborted"
            activeTickets.remove(active.ticketId)
            pendingPrototypeInputs.remove(active.ticketId)
            val abortedJson = ticketCardJson(
                ticketId = active.ticketId,
                rev = active.rev.incrementAndGet(),
                state = "aborted",
                title = active.title,
                banner = "Aborted by user request",
                percent = 0,
                roleName = active.role,
            )
            active.done.complete(TicketOutcome("aborted", null, "Aborted by user request", abortedJson))
            active.job.cancel(CancellationException("aborted by user: " + text.trim()))
            scope.launch { publishCardJson(active.groupId, abortedJson, active.agentKey) }
        }
        return true
    }

    /**
     * 群内活跃工单的追问与微调唤醒（测试方案阶段 A 原型确认与追问唤醒）。
     *
     * **方向判据**（缺陷 B 的产品裁决）：两种等待态的语义不同 ——
     * - `blocked`：工单挂起等输入，任何无 @ 文本都按唤醒直通（现状保留）；
     * - `prototype`：待选卡已交付，这时来的无 @ 文本可能是微调（"make it dark"）
     *   也可能是全新任务（"run npm test"）—— 文本特征上无法区分（真机两者都
     *   是短祈使句），猜错的代价是一次 5 分钟的白跑加一个被顶掉的工单。
     *   所以不猜：发**方向确认卡**，把决定权还给用户，见 [askPrototypeDirection]。
     */
    internal fun followUpIfActive(convId: String, text: String): Boolean {
        val active = activeTicketsForConv(convId).firstOrNull {
            it.stage in setOf("blocked", "prototype", "proposed")
        } ?: return false
        val trimmed = stripMentions(text)
        if (trimmed.isBlank()) return false
        // 点将期（还没开工）来的正文：不算追问也不算微调 —— 工单一件事都没做，
        // 没有「调整」的语境。按新派单处理：撤掉这张未开工的工单，正文走完整
        // 派单路由（返回 false 让调用方继续分发）。不撤销就派新单的话，3 秒倒计时
        // 到点旧工单照样开跑，群里会出现两张并行抢沙箱的卡。
        if (active.stage == "proposed") {
            cancelByCard("chain-" + active.ticketId, "Superseded by a new message")
            return false
        }
        val handler = active.onFollowUp ?: return false
        scope.launch {
            if (active.stage == "prototype") {
                askPrototypeDirection(active, trimmed)
            } else {
                active.stage = "working"
                publishTicketCard(
                    groupId = active.groupId,
                    ticketId = active.ticketId,
                    rev = active.rev.incrementAndGet(),
                    state = "working",
                    title = active.title,
                    banner = "Resumed: processing follow-up",
                    percent = 50,
                    roleName = active.role,
                    agentId = active.agentKey,
                )
                handler.invoke(trimmed)
            }
        }
        return true
    }

    /** 待用户裁决去向的 prototype 输入（键 = ticketId）。见 [followUpIfActive] 的判据。 */
    private data class PendingPrototypeInput(val text: String)

    private val pendingPrototypeInputs = ConcurrentHashMap<String, PendingPrototypeInput>()

    /**
     * 发「这是调整原型，还是新任务？」的确认卡。
     *
     * 独立 cardId（`chain-<ticketId>-ask`），不顶掉屏上的待选卡；文本暂存在
     * [pendingPrototypeInputs]，action 帧只有 actionId 装不下长文本，点确认时
     * 再取回执行（[confirmPrototypeInput]）。
     */
    private suspend fun askPrototypeDirection(active: ActiveTicket, text: String) {
        pendingPrototypeInputs[active.ticketId] = PendingPrototypeInput(text)
        val excerpt = text.take(80) + if (text.length > 80) "…" else ""
        // ChainProgress 没有动作字段（卡上的 actions 一直是手工构建的，如 diff-review
        // 冲突卡），方向确认卡同款：独立 cardId（`chain-<ticketId>-ask`），不顶掉屏上的
        // 待选卡。两个动作各接一个明确去向 —— options 是「回给 agent 的预设文本」，
        // 装不下「去向」语义，所以这里必须走 action 帧。
        val askJson = buildJsonObject {
            put("cardId", JsonPrimitive("chain-" + active.ticketId + "-ask"))
            put("rev", JsonPrimitive(1))
            put("type", JsonPrimitive("direction-ask"))
            put("state", JsonPrimitive("blocked"))
            put("title", JsonPrimitive(active.title))
            put("progress", JsonPrimitive(60))
            putJsonObject("banner") {
                put("text", JsonPrimitive("「" + excerpt + "」— 是调整这个原型，还是一个新任务？"))
                put("tone", JsonPrimitive("info"))
            }
            put("actions", buildJsonArray {
                add(buildJsonObject {
                    put("actionId", JsonPrimitive("refine-pending"))
                    put("label", JsonPrimitive("作为调整发送"))
                })
                add(buildJsonObject {
                    put("actionId", JsonPrimitive("dispatch-pending"))
                    put("label", JsonPrimitive("派为新任务"))
                })
            })
        }
        publishCardJson(active.groupId, askJson.toString(), active.agentKey)
    }

    /**
     * 方向确认卡上的按钮落地。`cardId` 形如 `chain-<ticketId>-ask`。
     *
     * - 派为新任务：旧原型工单就地收尾（等选布局的等待已无意义），文本走完整派单
     *   路由（是否原型意图由 [isPrototypeIntent] 重新判，不继承旧工单）；
     * - 作为调整：回到原 refine 路径（working 卡 + onFollowUp）。
     */
    internal fun confirmPrototypeInput(cardId: String, asNewTask: Boolean): Boolean {
        val tid = cardId.removePrefix("chain-").removeSuffix("-ask")
        val pending = pendingPrototypeInputs.remove(tid) ?: return false
        val active = activeTickets[tid] ?: return false
        if (asNewTask) {
            active.stage = "superseded"
            activeTickets.remove(tid)
            active.done.complete(
                TicketOutcome("aborted", null, "superseded: input was dispatched as a new task", null),
            )
            dispatchSingleWorker(active.convId, active.groupId, pending.text, active.agentKey)
        } else {
            active.stage = "working"
            scope.launch {
                publishTicketCard(
                    groupId = active.groupId,
                    ticketId = active.ticketId,
                    rev = active.rev.incrementAndGet(),
                    state = "working",
                    title = active.title,
                    banner = "Resumed: processing follow-up",
                    percent = 50,
                    roleName = active.role,
                    agentId = active.agentKey,
                )
                active.onFollowUp?.invoke(pending.text)
            }
        }
        return true
    }

    /**
     * 移动端点击「Approve & Develop」原型确认按钮。
     */
    internal fun approvePrototype(convId: String): Boolean {
        val active = activeTicketsForConv(convId).firstOrNull { it.stage == "prototype" } ?: return false
        val handler = active.onFollowUp ?: return false
        scope.launch {
            active.stage = "working"
            publishTicketCard(
                groupId = active.groupId,
                ticketId = active.ticketId,
                rev = active.rev.incrementAndGet(),
                state = "working",
                title = active.title,
                banner = "Prototype approved: initializing implementation & tests...",
                percent = 30,
                roleName = active.role,
                agentId = active.agentKey,
            )
            handler.invoke("APPROVE_AND_DEVELOP")
        }
        return true
    }

    internal fun approvePrototypeByCard(cardId: String): Boolean {
        val tid = cardId.removePrefix("chain-")
        val active = activeTickets[tid] ?: activeTickets.values.firstOrNull { it.ticketId == tid } ?: return false
        if (active.stage != "prototype") return false
        val handler = active.onFollowUp ?: return false
        scope.launch {
            active.stage = "working"
            publishTicketCard(
                groupId = active.groupId,
                ticketId = active.ticketId,
                rev = active.rev.incrementAndGet(),
                state = "working",
                title = active.title,
                banner = "Prototype approved: initializing implementation & tests...",
                percent = 30,
                roleName = active.role,
                agentId = active.agentKey,
            )
            handler.invoke("APPROVE_AND_DEVELOP")
        }
        return true
    }

    /**
     * 受阻卡上的 [Retry]：在**同一张卡**上把开发/自愈循环重跑一遍。
     *
     * 为什么要专门一条：`retry` 是 `StageChain` 自己写进卡里的 actionId，客户端照发，
     * 但宿主此前没有任何分支接它 —— 真机实测两种错法，都不是「重试」：
     *  · 开发卡：落进通用插件转发，`card action retry ... has no local plugin (dropped)`，
     *    点了毫无反应；
     *  · 原型卡：落进 `followUpByCard`，`retry` 被当成一句**微调**喂给原型闭包，
     *    卡面回的是「Refined prototype ready」。
     * 卡上画了这个按钮，就必须有一条真能重跑的路径。
     */
    internal fun retryByCard(cardId: String): Boolean {
        val tid = cardId.removePrefix("chain-")
        val active = activeTickets[tid] ?: activeTickets.values.firstOrNull { it.ticketId == tid } ?: return false
        if (active.stage != "blocked") return false
        val prompt = active.devPrompt ?: return false
        active.stage = "working"
        scope.launch {
            val outcome = try {
                runDevelopmentAndHealLoop(
                    ticket = active,
                    convId = active.convId,
                    groupId = active.groupId,
                    ticketId = active.ticketId,
                    title = active.title,
                    repoPath = active.repoPath,
                    revCounter = active.rev,
                    defaultAgent = active.agentKey,
                    devPrompt = prompt,
                )
            } catch (e: CancellationException) {
                active.done.complete(TicketOutcome("aborted", null, "aborted", null))
                throw e
            } catch (t: Throwable) {
                TicketOutcome("error", null, t.message ?: (t::class.simpleName ?: "retry failed"), null)
            }
            active.done.complete(outcome)
        }
        return true
    }

    internal fun followUpByCard(cardId: String, text: String): Boolean {
        val tid = cardId.removePrefix("chain-")
        val active = activeTickets[tid] ?: activeTickets.values.firstOrNull { it.ticketId == tid } ?: return false
        if (active.stage !in setOf("blocked", "prototype")) return false
        val handler = active.onFollowUp ?: return false
        val trimmed = stripMentions(text)
        if (trimmed.isBlank()) return false
        scope.launch {
            active.stage = "working"
            publishTicketCard(
                groupId = active.groupId,
                ticketId = active.ticketId,
                rev = active.rev.incrementAndGet(),
                state = "working",
                title = active.title,
                banner = "Resumed: processing follow-up",
                percent = 50,
                roleName = active.role,
                agentId = active.agentKey,
            )
            handler.invoke(trimmed)
        }
        return true
    }

    suspend fun handlePrompt(
        convId: String,
        senderPeerId: String,
        prompt: String,
        replyToId: String?,
    ) {        val groupId = ConvId.groupIdOf(convId)
        val active = activeTicketForConv(convId)

        // 1. Direct Abort signal（第二道防线：`@codex 停` 这类「既是 prompt 又是打断」
        //    的消息由入站前移判定拦下；万一有别的入口直接调到这里，这里再拦一次）。
        if (active != null && isAbortSignal(prompt)) {
            abortIfRequested(convId, prompt)
            return
        }

        // 2. Thread Follow-up waking blocked ticket
        if (active != null && active.stage == "blocked") {
            val handler = active.onFollowUp
            if (handler != null) {
                active.stage = "working"
                publishTicketCard(
                    groupId = groupId,
                    ticketId = active.ticketId,
                    rev = active.rev.incrementAndGet(),
                    state = "working",
                    title = active.title,
                    banner = "Resumed: processing follow-up",
                    percent = 50,
                    roleName = active.role,
                    agentId = active.agentKey,
                )
                handler.invoke(prompt)
                return
            }
        }

        // 3. New intake: check if user explicitly requested multi-role chain
        val stages = StageChain.parseStages(prompt)
        if (stages.size >= 2) {
            dispatchMultiRoleChain(convId, groupId, prompt, stages)
        } else {
            // DSH core philosophy: single worker end-to-end closed loop
            dispatchSingleWorker(convId, groupId, prompt)
        }
    }

    /**
     * invoke 执行桥的**可等待入口**：真机 `@agent` 派单帧带 `agentIntent`，域层走
     * `invokeExecutor.execute`（请求-响应），不走 `onAgentPrompt` 的文本路由 ——
     * 这曾经是阶段 A 原型 / 追问唤醒 / 自愈退单在真机上「单测全绿、现场不存在」的
     * 根因：能力都写在编排器里，而真机那条路直调 `executeCliTask` 绕过了它。
     *
     * 现在执行桥交给这里。三个分支与 [handlePrompt] 同判据、同顺序：
     *
     * 1. **打断**：`abortIfRequested` 已同步把终局（带卡）写进 `done`，await 即得；
     * 2. **受阻工单追问**：唤醒后续跑，等待方拿到「Resumed」回执 —— 真正的结论
     *    由后续工单卡承担（这时 invoke 的语义只是「你的话送到工单了」）；
     * 3. **新派单**：建工单并 await 终局（成功/受阻/退单/中止/原型待选）。
     *
     * `agentKey` 来自派单帧的 `agentId`（codex/claude/antigravity…），不再写死 codex。
     */
    suspend fun handlePromptAwait(
        convId: String,
        senderPeerId: String,
        prompt: String,
        replyToId: String?,
        agentKey: String?,
    ): TicketOutcome {
        val groupId = ConvId.groupIdOf(convId)
        val active = activeTicketForConv(convId)

        if (active != null && isAbortSignal(prompt)) {
            abortIfRequested(convId, prompt)
            return active.done.await()
        }

        if (active != null && active.stage == "blocked") {
            val handler = active.onFollowUp
            if (handler != null) {
                active.stage = "working"
                val json = ticketCardJson(
                    ticketId = active.ticketId,
                    rev = active.rev.incrementAndGet(),
                    state = "working",
                    title = active.title,
                    banner = "Resumed: processing follow-up",
                    percent = 50,
                    roleName = active.role,
                )
                scope.launch { publishCardJson(active.groupId, json, active.agentKey) }
                scope.launch { handler.invoke(prompt) }
                return TicketOutcome("working", "Resumed: processing follow-up", null, json)
            }
        }

        // 点将期的正文 = 新派单（与 followUpIfActive 同判据）：先撤销倒计时中的工单，
        // 否则 3 秒到点旧单照跑，群里出现两张并行抢沙箱的卡。
        if (active != null && active.stage == "proposed") {
            cancelByCard("chain-" + active.ticketId, "Superseded by a new message")
        }

        val stages = StageChain.parseStages(prompt)
        if (stages.size >= 2) {
            // 多角色链有自己的卡与生命周期（ChainDispatcher），这里只回受理。
            dispatchMultiRoleChain(convId, groupId, prompt, stages)
            return TicketOutcome("accepted", prompt.lineSequence().firstOrNull()?.trim()?.take(60), null, null)
        }
        return dispatchSingleWorker(convId, groupId, prompt, agentKey).await()
    }

    private fun dispatchMultiRoleChain(
        convId: String,
        groupId: String?,
        prompt: String,
        stages: List<ChainStage>,
    ) {
        val host = planeProvider() ?: return
        val repo = resolveWorkspacePath(groupId) ?: return
        val chainDispatcher = host.chainDispatcher ?: return
        scope.launch {
            chainDispatcher.start(
                groupId = groupId,
                repoPath = repo,
                title = prompt.take(60),
                stages = stages,
            )
        }
    }

    private fun dispatchSingleWorker(
        convId: String,
        groupId: String?,
        prompt: String,
        agentKey: String? = null,
    ): CompletableDeferred<TicketOutcome> {
        val host = planeProvider()
            ?: return CompletableDeferred(TicketOutcome("error", null, "control plane unavailable", null))
        val repoPath = resolveWorkspacePath(groupId)

        val ticketId = "t" + System.currentTimeMillis().toString(36)
        val title = prompt.lineSequence().firstOrNull()?.trim()?.take(60) ?: "Task $ticketId"
        val done = CompletableDeferred<TicketOutcome>()

        // 派单帧带 agentId 时随帧走（codex/claude/antigravity…）；文本路由没带 → codex。
        // 提前到 handback 之前：退单卡也要挂真名（广播身份与授权都认这个键）。
        val defaultAgent = agentKey?.takeIf { it.isNotBlank() } ?: "codex"

        // Hand-back if repo is invalid or missing
        if (repoPath == null || !isGitRepo(repoPath)) {
            val handedBack = TicketOutcome(
                state = "blocked",
                summary = null,
                error = "Handed back: no git workspace bound to group",
                cardJson = ticketCardJson(
                    ticketId = ticketId,
                    rev = 1,
                    state = "blocked",
                    title = title,
                    banner = "Handed back: no git workspace bound to group",
                    percent = 0,
                    roleName = roleNameOf(defaultAgent),
                ),
            )
            scope.launch { publishCardJson(groupId, handedBack.cardJson, defaultAgent) }
            return CompletableDeferred(handedBack)
        }

        val revCounter = AtomicInteger(1)
        val isProto = isPrototypeIntent(prompt)

        lateinit var ticket: ActiveTicket

        // **必须 LAZY 起跑**：协程体里读的是下面才赋值的 `ticket`。用默认的
        // `CoroutineStart.DEFAULT` 时，`scope.launch` 会把任务交给调度器立刻跑 ——
        // 非原型分支（阶段 B/C）第一句就是 `runDevelopmentAndHealLoop(ticket = ticket)`，
        // 于是抢在赋值前读到未初始化的 lateinit，抛
        // `UninitializedPropertyAccessException: lateinit property ticket has not been initialized`，
        // 整条工单消失、连卡都发不出来（真机实测：派单后只有 `[msg] inbound` 一行，群里什么都没有）。
        // 原型分支之所以没暴露，是因为它先 `executeCliTask` 挂起，给了赋值一个窗口 —— 这是
        // **靠运气**，不是靠结构。改成 LAZY 后由这里显式 `start()`，顺序由代码保证。
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val outcome: TicketOutcome = try {
                if (isProto) {
                    runPrototypeFlow(
                        ticket = ticket,
                        convId = convId,
                        groupId = groupId,
                        ticketId = ticketId,
                        title = title,
                        repoPath = repoPath,
                        revCounter = revCounter,
                        defaultAgent = defaultAgent,
                        prompt = prompt,
                        host = host,
                    )
                } else {
                    // ==================== STAGE B & C: DEV / BUGFIX FLOW WITH HEAL LOOP ====================
                    runDevelopmentAndHealLoop(
                        ticket = ticket,
                        convId = convId,
                        groupId = groupId,
                        ticketId = ticketId,
                        title = title,
                        repoPath = repoPath,
                        revCounter = revCounter,
                        defaultAgent = defaultAgent,
                        devPrompt = prompt,
                    )
                }
            } catch (e: CancellationException) {
                // abortIfRequested 已把「带卡中止」的终局写进 done（先 complete 者赢）；
                // 这里补无卡兜底，防工单被别的路取消时 invoke 桥的 await 挂死。
                done.complete(TicketOutcome("aborted", null, "aborted", null))
                throw e
            } catch (t: Throwable) {
                done.complete(TicketOutcome("error", null, t.message ?: (t::class.simpleName ?: "task failed"), null))
                throw t
            }
            done.complete(outcome)
        }

        ticket = ActiveTicket(
            ticketId = ticketId,
            convId = convId,
            groupId = groupId,
            job = job,
            title = title,
            repoPath = repoPath,
            stage = "proposed",
            rev = revCounter,
            role = roleNameOf(defaultAgent),
            agentKey = defaultAgent,
            devPrompt = prompt,
            done = done,
        )
        activeTickets[ticketId] = ticket
        // 先入表再起跑：打断信号（`abortIfRequested`）按 convId 找活跃工单，
        // 起跑之后、入表之前的那一瞬间发来的「停」会找不到工单而被当成普通消息。

        // ==================== 团队点将（文档 §二 Proposed 态）====================
        // 派单不再无声地直接开跑：先亮出「谁来干、分几步」—— roster 每一棒挂着承办人，
        // 倒计时到点自动开工（文档 §五「极简注意力」：不强制人等，倒计时不是审批门），
        // 卡上的「立即执行 / 取消」只是把这两个时刻握到人手里。
        val assignee = ticket.role + "（本机）"
        val plan = if (isProto) {
            listOf("原型设计", "开发实现", "测试验收")
        } else {
            listOf("编码实现", "自愈验证", "终审合并")
        }
        scope.launch {
            publishTicketCard(
                groupId = groupId,
                ticketId = ticketId,
                rev = revCounter.get(),
                state = "proposed",
                title = title,
                banner = "团队已就位：" + ticket.role + " 主理，" + TEAM_COUNTDOWN_MS / 1000 +
                    " 秒后自动开工；也可立即执行或取消",
                percent = 0,
                roleName = ticket.role,
                agentId = defaultAgent,
                stageRows = plan.map { it to "pending" },
                assignees = plan.map { assignee },
            )
            delay(TEAM_COUNTDOWN_MS)
            startProposedTicket(ticket)
        }
        return done
    }

    /** 点将卡的自动开工等待（§二 Proposed → Executing 的「倒计时结束」）。
     * 文档示例是 3s，真机实测不够人读完三行 roster 并把手挪到按钮上 ——
     * 点将交互的意义就在这几秒里，掐得太紧它就只剩装饰。 */
    private companion object {
        const val TEAM_COUNTDOWN_MS = 8_000L
    }

    /**
     * 把处于点将期的工单**真正开跑**（LAZY 协程的显式 start）。
     *
     * 双保险幂等：`stage` 判据挡住已开工/已撤销的重复调用；就算并发双双过了判据，
     * `job.start()` 对同一协程也只有第一次生效 —— 倒计时到点与用户点「立即执行」
     * 撞在同一拍也不会跑两遍。
     */
    private fun startProposedTicket(active: ActiveTicket): Boolean {
        if (active.stage != "proposed") return false
        active.stage = "working"
        return active.job.start()
    }

    /** 点将卡上的「立即执行」：提前开工。没有处于点将期的工单时如实返回 false。 */
    internal fun startByCard(cardId: String): Boolean {
        val tid = cardId.removePrefix("chain-")
        val active = activeTickets[tid] ?: activeTickets.values.firstOrNull { it.ticketId == tid } ?: return false
        return startProposedTicket(active)
    }

    /**
     * 宿主还认不认这张卡的工单。为「点了没反应」的如实回执服务：动作帧到达时
     * 工单可能已经不在表里 —— 宿主**重启**（内存态清空）或工单**早已收尾**
     * （终态落卡后即移出）。两种情况下宿主都不可能再为这个按钮做任何事，
     * 调用方（DesktopExtension）要据此把滞留卡翻成失效，而不是静默丢掉。
     *
     * 注意与各 `*ByCard` 的 false 区分：那些 false 可能只是**时序竞争**
     * （倒计时恰好抢先开工、stage 不匹配），工单还活着，真实状态卡马上就到，
     * 此时翻「失效」反而是说谎。只有这里返回 false 才代表宿主彻底无能为力。
     */
    internal fun hasTicketForCard(cardId: String): Boolean {
        val tid = cardId.removePrefix("chain-")
        return activeTickets.containsKey(tid) || activeTickets.values.any { it.ticketId == tid }
    }

    /**
     * 点将卡上的「取消」：撤销尚未开工的工单。
     *
     * 与 `abortIfRequested` 的分工：那是「活干到一半被叫停」（摘进程树）；这里工单
     * 还没起跑，只需取消 LAZY 协程、补一张「已取消」卡。写了理由参数：点卡取消与
     * 「点将期发了新消息」的卡面文案不同，后者不该让人以为是谁按了取消。
     */
    internal fun cancelByCard(cardId: String, reason: String = "Cancelled before start"): Boolean {
        val tid = cardId.removePrefix("chain-")
        val active = activeTickets[tid] ?: activeTickets.values.firstOrNull { it.ticketId == tid } ?: return false
        if (active.stage != "proposed") return false
        active.stage = "aborted"
        activeTickets.remove(tid)
        val cancelledJson = ticketCardJson(
            ticketId = active.ticketId,
            rev = active.rev.incrementAndGet(),
            state = "aborted",
            title = active.title,
            banner = reason,
            percent = 0,
            roleName = active.role,
        )
        active.done.complete(TicketOutcome("aborted", null, reason, cancelledJson))
        active.job.cancel(CancellationException(reason))
        scope.launch { publishCardJson(active.groupId, cancelledJson, active.agentKey) }
        return true
    }

    /**
     * 阶段 A（原型立项）执行体：出原型卡、接追问回调，终局是「原型卡待选」——
     * 后续的确认/微调在 `onFollowUp` 里续跑，不再改写这个终局。
     */
    private suspend fun runPrototypeFlow(
        ticket: ActiveTicket,
        convId: String,
        groupId: String?,
        ticketId: String,
        title: String,
        repoPath: String,
        revCounter: AtomicInteger,
        defaultAgent: String,
        prompt: String,
        host: ControlPlane,
    ): TicketOutcome {
        // ==================== STAGE A: PROTOCOL PROTOTYPE FLOW ====================
        publishTicketCard(
            groupId = groupId,
            ticketId = ticketId,
            // rev 1 是团队点将卡（dispatchSingleWorker 发的 proposed）；这里第一张
            // working 卡必须是 rev 2 —— rev 倒退的卡会被手机端折叠规则丢弃。
            rev = revCounter.incrementAndGet(),
            state = "working",
            title = title,
            banner = "Generating interactive prototype (prototype.html)...",
            percent = 20,
            roleName = ticket.role,
            agentId = defaultAgent,
        )

        val protoPrompt = "You are designing an interactive single-file prototype HTML for the requested concept. " +
            "Generate a self-contained prototype.html file with embedded styles and scripts in the workspace root. " +
            "Do NOT inspect or read files outside the repository.\n\n" + prompt

        var protoCreated = false
        var protoPatch: String? = null
        host.executeCliTask(
            agentKey = defaultAgent,
            prompt = protoPrompt,
            wsPath = repoPath,
            requestedSessionId = null,
            sessionTitle = title,
            onArtifact = { path, name, _, _, _ ->
                if (name == "patch.diff") protoPatch = path
                if (name.contains("prototype") || name.endsWith(".html") || name == "patch.diff") protoCreated = true
            },
            onEvent = { _, _, _, _ -> },
        )

        ticket.stage = "prototype"
        // 选项区**不展示假选项**：CLI 真实产出的是一份 prototype.html，不存在
        // 「Layout A/B/C」三种布局 —— 从前这里硬编码三个选项名，用户点「Layout B」
        // 实际是把它当反馈文本喂回微调，卡面承诺与真实通路对不上。真实通路只有
        // 两条：回复文本微调、Approve 开发（按钮在卡上）。选型交互只在方向确认卡
        // （askPrototypeDirection）里出现 —— 那里的选项来自用户派单原文，是真的。
        // 产物**只能是真交付过的那份**：从沙箱 patch 抽出 prototype.html → 落盘 → 投群，
        // 卡上字节数取落盘文件的真实长度。抽不出来就一个都不宣称 —— 从前这里写死
        // `("prototype.html" to 4096L)`，卡上永远有一行点不动的产物，真机上是死链。
        val protoArtifacts = deliverPrototypeArtifact(groupId, ticketId, protoPatch)

        val protoJson = publishTicketCard(
            groupId = groupId,
            ticketId = ticketId,
            rev = revCounter.incrementAndGet(),
            state = "prototype",
            title = title,
            banner = "Interactive prototype ready. Reply to adjust it, or approve to start development.",
            percent = 50,
            roleName = ticket.role,
            agentId = defaultAgent,
            artifacts = protoArtifacts,
        )

        // Wire onFollowUp callback to handle refinements or approval
        ticket.onFollowUp = { followUpText ->
            if (followUpText == "APPROVE_AND_DEVELOP") {
                ticket.stage = "working"
                val approvedPrompt = "Prototype confirmed! Implement the full features and test suite for: $prompt"
                // 立项之后这张卡「要做的事」变了：从「画原型」变成「照原型实现」。
                // Retry 重放的必须是后者，否则点重试等于把原型再画一遍。
                ticket.devPrompt = approvedPrompt
                runDevelopmentAndHealLoop(
                    ticket = ticket,
                    convId = convId,
                    groupId = groupId,
                    ticketId = ticketId,
                    title = title,
                    repoPath = repoPath,
                    revCounter = revCounter,
                    defaultAgent = defaultAgent,
                    devPrompt = approvedPrompt,
                )
            } else {
                ticket.stage = "working"
                val refinePrompt = "Refine the prototype.html in workspace according to this user feedback: $followUpText. " +
                    "Keep it self-contained in prototype.html."
                var refinePatch: String? = null
                host.executeCliTask(
                    agentKey = defaultAgent,
                    prompt = refinePrompt,
                    wsPath = repoPath,
                    requestedSessionId = null,
                    sessionTitle = title,
                    onArtifact = { path, name, _, _, _ -> if (name == "patch.diff") refinePatch = path },
                    onEvent = { _, _, _, _ -> },
                )
                ticket.stage = "prototype"
                // 微调后的产物同样要真投一次：否则「改好了」这句话在手机上没有任何可打开的东西。
                val refinedArtifacts = deliverPrototypeArtifact(groupId, ticketId, refinePatch)
                publishTicketCard(
                    groupId = groupId,
                    ticketId = ticketId,
                    rev = revCounter.incrementAndGet(),
                    state = "prototype",
                    title = title,
                    banner = "Refined prototype ready. Reply to adjust it further, or approve to start development.",
                    percent = 60,
                    roleName = ticket.role,
                    agentId = defaultAgent,
                    artifacts = refinedArtifacts,
                )
            }
        }
        return TicketOutcome(
            state = "prototype",
            summary = "Interactive prototype ready. Reply to adjust it, or approve to start development.",
            error = null,
            cardJson = protoJson,
        )
    }

    /**
     * 原型产物的**真交付**：沙箱 patch → `prototype.html` 实体 → 投群 → 返回卡上该写的清单。
     *
     * 沙箱跑完就无痕清理，所以「沙箱里生成过 prototype.html」不等于「用户拿得到」。
     * 这一跳把产物全文落成宿主侧文件、按 artifact 免确认投给群里，卡片产物行才指向
     * 一个**真能打开**的东西。
     *
     * 全文有两个来源，按优先级：
     * 1. **快照** `<patchDir>/files/prototype.html` —— 收割时工作树还活着，改动文件
     *    （refine 轮的形态）整份存过；这是唯一能覆盖「多 hunk 修改」的来源；
     * 2. **diff 新侧** —— 新文件（首轮 prototype）由 `extractFileFromPatch` 整份还原。
     *
     * 任何一步不成立（没有补丁 / 两路都取不到全文 / 投递失败）都返回空清单 ——
     * 宁可不宣称，也不要在卡上留一行点不动的产物。
     */
    private suspend fun deliverPrototypeArtifact(
        groupId: String?,
        ticketId: String,
        patchPath: String?,
    ): List<Pair<String, Long>> {
        if (groupId == null || patchPath == null) return emptyList()
        val patchFile = File(patchPath)
        val snapshot = File(patchFile.parentFile, "files/prototype.html")
        val html = when {
            snapshot.isFile -> runCatching { snapshot.readText() }.getOrNull()
            else -> null
        } ?: StageChain.extractFileFromPatch(patchFile, "prototype.html") ?: return emptyList()
        val dir = File(File(System.getProperty("java.io.tmpdir"), "tidings-artifacts"), ticketId)
        val out = File(dir.apply { mkdirs() }, "prototype.html")
        val written = runCatching { out.writeText(html) }.isSuccess
        if (!written) {
            println("[ORCHESTRATOR] prototype artifact write failed: " + out.absolutePath)
            return emptyList()
        }
        // 报价没发起（节点没起 / 群不认识）也算投递失败 —— 卡上不许留一行打不开的产物。
        val delivered = runCatching {
            controller.sendAgentArtifacts(
                ConvId.group(groupId),
                listOf(
                    com.kapp.tidings.core.transport.LocalFile(
                        path = out.absolutePath,
                        name = "prototype.html",
                        size = out.length(),
                    ),
                ),
            )
        }.onFailure { t ->
            println("[ORCHESTRATOR] prototype artifact delivery failed: " + t.message)
        }.getOrDefault(false)
        return if (delivered) listOf("prototype.html" to out.length()) else emptyList()
    }

    /**
     * 把 `patch.diff` 真的送到群里（§6.3 离线 P2P bundle）。
     *
     * ## 为什么必须有这一跳
     *
     * 审阅卡上一直写着「产物：patch.diff，N 字节」，而文件**从没离开过宿主本机** ——
     * 补丁躺在沙箱收割目录里，手机上那一行只是一段描述。§6.3 要的正是「通过 Tidings
     * 本地 P2P 文件传输通道直接投递至移动端」：不经过任何外部托管，审查的人在手机上
     * 就能把这份 diff 打开看。
     *
     * ## 纪律
     *
     * - 走 `purpose=artifact`（免确认）：要不要合并是卡上的决策，文件本身是随附资料；
     * - 失败只记日志、**不改卡**：`patch.diff` 这一行在宿主上是事实（文件确实存在），
     *   而且它只是元数据、不是按钮，不会变成一颗点不动的假按钮；
     * - 不阻塞派单：报价是异步的，这里只等「有没有交给传输层」。
     */
    private fun deliverPatchBundle(groupId: String?, ticketId: String, patchPath: String?): Boolean {
        if (groupId == null || patchPath == null) return false
        val file = File(patchPath)
        if (!file.isFile) {
            println("[ORCHESTRATOR] patch bundle missing on disk (card chain-" + ticketId + "): " + patchPath)
            return false
        }
        val dispatched = runCatching {
            controller.sendAgentArtifacts(
                ConvId.group(groupId),
                listOf(
                    com.kapp.tidings.core.transport.LocalFile(
                        path = file.absolutePath,
                        name = "patch.diff",
                        size = file.length(),
                    ),
                ),
            )
        }.onFailure { t ->
            println("[ORCHESTRATOR] patch bundle delivery threw (card chain-" + ticketId + "): " + t.message)
        }.getOrDefault(false)
        if (dispatched) {
            println(
                "[ORCHESTRATOR] patch bundle dispatched to group " + groupId +
                    " (card chain-" + ticketId + ", " + file.length() + " B)",
            )
        } else {
            println(
                "[ORCHESTRATOR] patch bundle NOT dispatched — it stays on the host " +
                    "(card chain-" + ticketId + ")",
            )
        }
        return dispatched
    }

    private suspend fun runDevelopmentAndHealLoop(
        ticket: ActiveTicket,
        convId: String,
        groupId: String?,
        ticketId: String,
        title: String,
        repoPath: String,
        revCounter: AtomicInteger,
        defaultAgent: String,
        devPrompt: String,
    ): TicketOutcome {
        val host = planeProvider()
            ?: return TicketOutcome("error", null, "control plane unavailable", null)

        publishTicketCard(
            groupId = groupId,
            ticketId = ticketId,
            rev = revCounter.incrementAndGet(),
            state = "working",
            title = title,
            banner = "Executing worker sandbox in isolated worktree...",
            percent = 40,
            roleName = ticket.role,
            agentId = defaultAgent,
        )

        val containedPrompt = "You are working strictly inside the current workspace repository directory. " +
            "Do NOT inspect, search, or read files outside this repository directory.\n\n" + devPrompt

        val maxHealRounds = 3
        var healRound = 0
        var currentPrompt = containedPrompt
        var patchPath: String? = null
        // CLI **自己说的话**（token/思考增量拼起来）。卡面摘要只能从这里来。
        var said = StringBuilder()
        var executionError: String? = null
        // 每轮的原因都留着：末轮那句常常是「没交产物」，而首轮那句才是「模型根本没跑起来」。
        // 只留末轮会把看横幅的人带偏（D4，见 [HealReport]）。
        val roundErrors = mutableListOf<String>()

        while (healRound < maxHealRounds) {
            healRound++
            patchPath = null
            said = StringBuilder()
            val (_, status, error) = host.executeCliTask(
                agentKey = defaultAgent,
                prompt = currentPrompt,
                wsPath = repoPath,
                requestedSessionId = null,
                // 会话标题用工单标题：prompt 首行是系统前导（containment preamble），
                // 从 prompt 截标题就会把整句英文 plumbing 摆上 divider。
                sessionTitle = title,
                onArtifact = { path, name, _, _, _ -> if (name == "patch.diff") patchPath = path },
                onEvent = { eventType, _, summary, body ->
                    // **只累「人话」。** 其余事件的 body 都不是给人读的：
                    // `card` 是卡片 JSON（原样贴进卡面就是把 `{"cardId":…}` 摊给用户）、
                    // `artifact` 是路径/SHA 对账、`tool.started` 是调用参数、
                    // `error` 是英文诊断。从前用的是黑名单（只排 finished/heartbeat/
                    // artifact），于是最后落进摘要的总是那张卡片 JSON。
                    if (eventType == "thought.delta" || eventType == "token.delta") {
                        val chunk = body ?: summary
                        if (!chunk.isNullOrBlank()) said.append(chunk)
                    }
                },
            )
            if (status == "finished" && patchPath != null) {
                executionError = null
                break
            }
            val roundError = error
                ?: (if (patchPath == null) "No patch produced by worker" else "Execution ended in $status")
            executionError = roundError
            roundErrors += roundError
            if (healRound < maxHealRounds) {
                publishTicketCard(
                    groupId = groupId,
                    ticketId = ticketId,
                    rev = revCounter.incrementAndGet(),
                    state = "working",
                    title = title,
                    banner = "Heal loop $healRound/$maxHealRounds: fixing issues ($executionError)...",
                    percent = 40 + healRound * 15,
                    roleName = ticket.role,
                    agentId = defaultAgent,
                )
                currentPrompt = "Previous attempt failed: $executionError. Please inspect tests and workspace, fix all issues, and ensure npm test passes."
            }
        }

        if (executionError != null) {
            ticket.stage = "blocked"
            // 首因 + 末因都给出来（重复的只写一遍）：这两句指向的修法完全不同，
            // 只报末轮会让人去查「为什么没交产物」，而真正的原因是第一轮就挂了。
            val blockedBanner = HealReport.bannerOf(roundErrors, maxHealRounds)
                ?: "Handed back: exceeded max heal rounds ($maxHealRounds) — $executionError"
            val blockedJson = publishTicketCard(
                groupId = groupId,
                ticketId = ticketId,
                rev = revCounter.incrementAndGet(),
                state = "blocked",
                title = title,
                banner = blockedBanner,
                percent = 40,
                roleName = ticket.role,
                agentId = defaultAgent,
            )
            return TicketOutcome("blocked", null, blockedBanner, blockedJson)
        }

        // Success -> review_ready card (sandbox isolated, host repo remains clean until user reviews and merges)
        activeTickets.remove(ticket.ticketId)
        val harvest = patchPath?.let { StageChain.parseHarvest(File(it), File(repoPath).name) }
        val diff = harvest?.first
        val patchBytes = harvest?.second ?: 0L
        val artifactsList = if (patchPath != null) listOf("patch.diff" to patchBytes) else emptyList()
        val defaultSummary = if (diff != null) {
            diff.summary ?: "改动已就绪，等你合并"
        } else {
            "Implementation ready in isolated worktree for review"
        }
        // 取**尾部**再压：结论在最后，开头多是「我先看看仓库」这类过程话。
        val summary = StageChain.summarize(said.toString().takeLast(600)) ?: defaultSummary
        // **发布之前登记**：手机可能在卡片到达的同一秒就点了「合并此补丁」，
        // 先发后登记会漏掉这一拍（动作帧到了、账还没记上 ⇒ 又一次「no sandbox patch」）。
        if (patchPath != null) {
            patchByCard["chain-" + ticketId] = SandboxPatchRef(
                sessionId = "",
                workspacePath = repoPath,
                patchPath = patchPath,
            )
            if (groupId != null) groupByCard["chain-" + ticketId] = groupId
        }
        // §6.3 离线 bundle：补丁随卡一起走 P2P 文件通道，手机上能真的把 diff 打开看。
        // 先发文件、后发卡 —— 卡到的时候那一行产物已经点得开了。
        deliverPatchBundle(groupId, ticketId, patchPath)
        val reviewJson = publishTicketCard(
            groupId = groupId,
            ticketId = ticketId,
            rev = revCounter.incrementAndGet(),
            state = "review_ready",
            title = title,
            banner = summary,
            percent = 100,
            roleName = ticket.role,
            agentId = defaultAgent,
            artifacts = artifactsList,
            diff = diff,
        )
        return TicketOutcome("review_ready", summary, null, reviewJson, patchPath)
    }

    /**
     * 简版工单卡的**纯构建**（不落网络）。打断（`abortIfRequested`）与追问唤醒要把
     * 终局**先**递给 await 中的 invoke 桥、卡随终局一起走 —— 那两个点不能先发网络。
     */
    private fun ticketCardJson(
        ticketId: String,
        rev: Int,
        state: String,
        title: String,
        banner: String?,
        percent: Int,
        roleName: String = "Worker",
    ): String = StageChain.cardJsonOf(
        ChainProgress(
            chainId = ticketId,
            rev = rev,
            state = state,
            title = title,
            currentRole = roleName,
            // 阶段项的状态要**翻译**成棒级词表，不能把卡级 state 直接塞进去 ——
            // `review_ready`/`merged`/`prototype` 这些词不在 items 的词表里，
            // 渲染层会一律退成「未开始」（见 `StageChain.stageStatusOf`）。
            stages = listOf(roleName to StageChain.stageStatusOf(state)),
            percent = percent,
            banner = banner,
        ),
    )

    private suspend fun publishCardJson(groupId: String?, json: String?, agentId: String) {
        if (groupId == null || json == null) return
        runCatching {
            controller.broadcastChainCard(
                groupId = groupId,
                agentId = agentId,
                cardJson = json,
            )
        }.onFailure { t ->
            println("[ORCHESTRATOR] card publish failed: " + t.message)
        }
    }

    private suspend fun publishTicketCard(
        groupId: String?,
        ticketId: String,
        rev: Int,
        state: String,
        title: String,
        banner: String?,
        percent: Int,
        roleName: String = "Worker",
        agentId: String = "codex",
        options: List<Pair<String, Boolean>> = emptyList(),
        artifacts: List<Pair<String, Long>> = emptyList(),
        diff: ChainDiff? = null,
        /** 缺省 = 单棒（[roleName] 一行）；团队点将卡传完整计划，见 [dispatchSingleWorker]。 */
        stageRows: List<Pair<String, String>>? = null,
        /** 与 [stageRows] 平行的承办人名单（roster），一棒一个。 */
        assignees: List<String> = emptyList(),
    ): String? {
        if (groupId == null) return null
        val progress = ChainProgress(
            chainId = ticketId,
            rev = rev,
            state = state,
            title = title,
            currentRole = roleName,
            stages = stageRows ?: listOf(roleName to StageChain.stageStatusOf(state)),
            assignees = assignees,
            percent = percent,
            banner = banner,
            options = options,
            artifacts = artifacts,
            diff = diff,
        )
        val json = StageChain.cardJsonOf(progress)
        publishCardJson(groupId, json, agentId)
        return json
    }

    /**
     * 派单该落在哪个目录。两条判据，都是**确定性**的，都没有就返回 null 由调用方退单。
     *
     * 1. **群声明的默认工作区**：`virtualAgents[].defaultWsId` 对工作区表做精确 id 匹配
     *    （[RelayUiState.workspaceOfGroup]，产品语义上的"这个群绑了哪个项目"）；
     * 2. **老装置**：工作区行的 `wsId` 就是群 id（建群时"关联本地目录"那条路径留下的形状）。
     *
     * 曾经还有第三段兜底「本机第一个工作区」。真机实测中出现过：同一个群连发
     * 两条派单，一条落在 `~/e2e-weather-app`、一条落在 `~/weather-relay` —— 因为
     * `agentWorkspaces` 是 Map，`firstOrNull` 的顺序随哈希。本端一切正常（卡片、进度、
     * 补丁卡都齐），错的是"改哪个仓"，而往别人的仓库里写字是不可撤销的那类错。
     * 兜底的语义从来不是"猜一个仓"，是"没有就退单"。
     */
    private fun resolveWorkspacePath(groupId: String?): String? {
        if (groupId == null) return null
        val state = controller.state.value
        val declared = state.workspaceOfGroup(groupId)
        val legacy = state.agentWorkspaces.values.firstOrNull { it.wsId == groupId }
        val path = (declared ?: legacy)?.wsPath?.takeIf { it.isNotBlank() } ?: return null
        return path.takeIf { isGitRepo(it) }
    }

    private fun isGitRepo(path: String): Boolean =
        git(path, "rev-parse", "--git-dir") != null

    /** 驱动键 → 卡面上的角色显示名（`currentRole`/`stages` 用）。 */
    private fun roleNameOf(agentKey: String): String = when (agentKey.lowercase()) {
        "codex" -> "Codex"
        "claude" -> "Claude"
        "antigravity", "agy" -> "Antigravity"
        "gemini" -> "Gemini"
        else -> agentKey.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    private fun git(repoPath: String, vararg args: String): String? = runCatching {
        val proc = ProcessBuilder(listOf("git", "-C", repoPath) + args.toList())
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        if (!proc.waitFor(30_000L, TimeUnit.MILLISECONDS)) {
            proc.destroyForcibly()
            return@runCatching null
        }
        if (proc.exitValue() != 0) null else out
    }.getOrNull()
}

/**
 * 「这条文本是不是打断指令」的**唯一判据**。
 *
 * 为什么单独拎出来：它此前是 `DesktopWorkerOrchestrator` 里的两个私有成员，于是
 * 只能靠源码扫描测「关键词还在不在」，测不到「`@codex 停` 算不算」。真机上正是
 * 这个组合被漏掉的 —— 判据本身不进任何测试，就只能等出故障时才发现。
 */
internal object AbortSignals {

    private val keywords = setOf("stop", "abort", "cancel", "\u505c", "\u53d6\u6d88")

    /** 去掉行首连续的 `@提及`，返回用户真正的正文。只有 @没有正文时返回空串。 */
    fun stripMentions(text: String): String {
        var rest = text.trim()
        while (rest.startsWith("@")) {
            val cut = rest.indexOfFirst { it == ' ' || it == '\n' || it == '\t' || it == '\u3000' }
            if (cut < 0) return ""
            rest = rest.substring(cut).trim()
        }
        return rest
    }

    /**
     * 命中判据：去提及后**正好是**关键词，或**以关键词开头**。
     *
     * 用 `startsWith` 是为了让「停一下」「stop it」也算数 —— 用户急着叫停时不会
     * 只打一个字。代价是「取消订单」这类正常话会被误判成打断；但只在**有活跃工单**
     * 时才会有人问这句（见 `abortIfRequested` 的第一行），这个代价可以接受。
     */
    fun matches(text: String): Boolean {
        val trimmed = stripMentions(text).lowercase()
        if (trimmed.isEmpty()) return false
        return keywords.any { trimmed == it || trimmed.startsWith(it) }
    }

    /**
     * 整条**就是**关键词（去提及、小写后相等）。
     *
     * 与 [matches] 的分工：`matches` 回答「要不要当作打断处理」（所以 `取消订单` 也算，
     * 宁可误拦不可漏拦）；`isExact` 回答「这**明确**是一句打断指令吗」——
     * 只有它才配得到「当前没有在跑的任务」这条回执。
     *
     * 为什么不能拿 `matches` 去回执：`取消订单`、`停止符` 这类正常话会以关键词开头，
     * 给它们弹一句「无需停止」既误导又吵闹。整条相等时，用户的意思没有第二种解释。
     */
    fun isExact(text: String): Boolean {
        val trimmed = stripMentions(text).lowercase()
        if (trimmed.isEmpty()) return false
        return keywords.any { trimmed == it }
    }
}
