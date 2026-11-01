// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import com.kapp.tidings.agent.ChainDiff
import com.kapp.tidings.agent.ChainProgress
import com.kapp.tidings.agent.ChainStage
import com.kapp.tidings.agent.ControlPlane
import com.kapp.tidings.agent.StageChain
import com.kapp.tidings.core.protocol.ConvId
import com.kapp.tidings.domain.RelayController
import com.kapp.tidings.domain.RelayUiState
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
    data class ActiveTicket(
        val ticketId: String,
        val convId: String,
        val groupId: String?,
        val job: Job,
        val title: String,
        val repoPath: String,
        @Volatile var stage: String,
        val rev: AtomicInteger = AtomicInteger(0),
        @Volatile var onFollowUp: (suspend (String) -> Unit)? = null,
    )

    private val activeTickets = ConcurrentHashMap<String, ActiveTicket>()

    fun isAbortSignal(text: String): Boolean = AbortSignals.matches(text)

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
        val active = activeTickets[convId] ?: return false
        if (!isAbortSignal(text)) return false
        active.stage = "aborted"
        activeTickets.remove(convId)
        active.job.cancel(CancellationException("aborted by user: " + text.trim()))
        scope.launch {
            publishTicketCard(
                groupId = active.groupId,
                ticketId = active.ticketId,
                rev = active.rev.incrementAndGet(),
                state = "aborted",
                title = active.title,
                banner = "Aborted by user request",
                percent = 0,
            )
        }
        return true
    }

    /**
     * 群内活跃工单的追问与微调唤醒（测试方案阶段 A 原型确认与追问唤醒）。
     */
    internal fun followUpIfActive(convId: String, text: String): Boolean {
        val active = activeTickets[convId] ?: return false
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
            )
            handler.invoke(trimmed)
        }
        return true
    }

    /**
     * 移动端点击「Approve & Develop」原型确认按钮。
     */
    internal fun approvePrototype(convId: String): Boolean {
        val active = activeTickets[convId] ?: return false
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
            )
            handler.invoke("APPROVE_AND_DEVELOP")
        }
        return true
    }

    internal fun approvePrototypeByCard(cardId: String): Boolean {
        val tid = cardId.removePrefix("chain-")
        val active = activeTickets.values.firstOrNull { it.ticketId == tid } ?: return false
        return approvePrototype(active.convId)
    }

    internal fun followUpByCard(cardId: String, text: String): Boolean {
        val tid = cardId.removePrefix("chain-")
        val active = activeTickets.values.firstOrNull { it.ticketId == tid } ?: return false
        return followUpIfActive(active.convId, text)
    }

    suspend fun handlePrompt(
        convId: String,
        senderPeerId: String,
        prompt: String,
        replyToId: String?,
    ) {
        val groupId = ConvId.groupIdOf(convId)
        val active = activeTickets[convId]

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
    ) {
        val host = planeProvider() ?: return
        val repoPath = resolveWorkspacePath(groupId)

        val ticketId = "t" + System.currentTimeMillis().toString(36)
        val title = prompt.lineSequence().firstOrNull()?.trim()?.take(60) ?: "Task $ticketId"

        // Hand-back if repo is invalid or missing
        if (repoPath == null || !isGitRepo(repoPath)) {
            scope.launch {
                publishTicketCard(
                    groupId = groupId,
                    ticketId = ticketId,
                    rev = 1,
                    state = "blocked",
                    title = title,
                    banner = "Handed back: no git workspace bound to group",
                    percent = 0,
                )
            }
            return
        }

        val revCounter = AtomicInteger(1)
        val defaultAgent = "codex"
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
            if (isProto) {
                // ==================== STAGE A: PROTOCOL PROTOTYPE FLOW ====================
                publishTicketCard(
                    groupId = groupId,
                    ticketId = ticketId,
                    rev = revCounter.get(),
                    state = "working",
                    title = title,
                    banner = "Generating interactive prototype (prototype.html)...",
                    percent = 20,
                )

                val protoPrompt = "You are designing an interactive single-file prototype HTML for the requested concept. " +
                    "Generate a self-contained prototype.html file with embedded styles and scripts in the workspace root. " +
                    "Do NOT inspect or read files outside the repository.\n\n" + prompt

                var protoCreated = false
                host.executeCliTask(
                    agentKey = defaultAgent,
                    prompt = protoPrompt,
                    wsPath = repoPath,
                    requestedSessionId = null,
                    onArtifact = { _, name, _, _, _ -> if (name.contains("prototype") || name.endsWith(".html") || name == "patch.diff") protoCreated = true },
                    onEvent = { _, _, _, _ -> },
                )

                ticket.stage = "prototype"
                val protoOptions = listOf(
                    "Layout A (Minimalist)" to true,
                    "Layout B (Capsule Cards)" to false,
                    "Layout C (Compact Table)" to false,
                )
                val protoArtifacts = listOf("prototype.html" to 4096L)

                publishTicketCard(
                    groupId = groupId,
                    ticketId = ticketId,
                    rev = revCounter.incrementAndGet(),
                    state = "prototype",
                    title = title,
                    banner = "Interactive prototype ready. Select layout or reply to adjust.",
                    percent = 50,
                    options = protoOptions,
                    artifacts = protoArtifacts,
                )

                // Wire onFollowUp callback to handle refinements or approval
                ticket.onFollowUp = { followUpText ->
                    if (followUpText == "APPROVE_AND_DEVELOP") {
                        ticket.stage = "working"
                        runDevelopmentAndHealLoop(
                            ticket = ticket,
                            convId = convId,
                            groupId = groupId,
                            ticketId = ticketId,
                            title = title,
                            repoPath = repoPath,
                            revCounter = revCounter,
                            defaultAgent = defaultAgent,
                            devPrompt = "Prototype confirmed! Implement the full features and test suite for: $prompt",
                        )
                    } else {
                        ticket.stage = "working"
                        val refinePrompt = "Refine the prototype.html in workspace according to this user feedback: $followUpText. " +
                            "Keep it self-contained in prototype.html."
                        host.executeCliTask(
                            agentKey = defaultAgent,
                            prompt = refinePrompt,
                            wsPath = repoPath,
                            requestedSessionId = null,
                            onArtifact = { _, _, _, _, _ -> },
                            onEvent = { _, _, _, _ -> },
                        )
                        ticket.stage = "prototype"
                        publishTicketCard(
                            groupId = groupId,
                            ticketId = ticketId,
                            rev = revCounter.incrementAndGet(),
                            state = "prototype",
                            title = title,
                            banner = "Refined prototype ready. Select layout or reply to adjust.",
                            percent = 60,
                            options = protoOptions,
                            artifacts = protoArtifacts,
                        )
                    }
                }
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
        }

        ticket = ActiveTicket(
            ticketId = ticketId,
            convId = convId,
            groupId = groupId,
            job = job,
            title = title,
            repoPath = repoPath,
            stage = "working",
            rev = revCounter,
        )
        activeTickets[convId] = ticket
        // 先入表再起跑：打断信号（`abortIfRequested`）按 convId 找活跃工单，
        // 起跑之后、入表之前的那一瞬间发来的「停」会找不到工单而被当成普通消息。
        job.start()
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
    ) {
        val host = planeProvider() ?: return

        publishTicketCard(
            groupId = groupId,
            ticketId = ticketId,
            rev = revCounter.incrementAndGet(),
            state = "working",
            title = title,
            banner = "Executing worker sandbox in isolated worktree...",
            percent = 40,
        )

        val containedPrompt = "You are working strictly inside the current workspace repository directory. " +
            "Do NOT inspect, search, or read files outside this repository directory.\n\n" + devPrompt

        val maxHealRounds = 3
        var healRound = 0
        var currentPrompt = containedPrompt
        var patchPath: String? = null
        var lastSaid: String? = null
        var executionError: String? = null

        while (healRound < maxHealRounds) {
            healRound++
            patchPath = null
            lastSaid = null
            val (_, status, error) = host.executeCliTask(
                agentKey = defaultAgent,
                prompt = currentPrompt,
                wsPath = repoPath,
                requestedSessionId = null,
                onArtifact = { path, name, _, _, _ -> if (name == "patch.diff") patchPath = path },
                onEvent = { eventType, _, summary, body ->
                    if (eventType != "finished" && eventType != "heartbeat" && eventType != "artifact") {
                        val candidate = (body ?: summary)?.trim()
                        if (!candidate.isNullOrBlank() && candidate != "stop") {
                            lastSaid = candidate
                        }
                    }
                },
            )
            if (status == "finished" && patchPath != null) {
                executionError = null
                break
            }
            executionError = error ?: (if (patchPath == null) "No patch produced by worker" else "Execution ended in $status")
            if (healRound < maxHealRounds) {
                publishTicketCard(
                    groupId = groupId,
                    ticketId = ticketId,
                    rev = revCounter.incrementAndGet(),
                    state = "working",
                    title = title,
                    banner = "Heal loop $healRound/$maxHealRounds: fixing issues ($executionError)...",
                    percent = 40 + healRound * 15,
                )
                currentPrompt = "Previous attempt failed: $executionError. Please inspect tests and workspace, fix all issues, and ensure npm test passes."
            }
        }

        if (executionError != null) {
            ticket.stage = "blocked"
            publishTicketCard(
                groupId = groupId,
                ticketId = ticketId,
                rev = revCounter.incrementAndGet(),
                state = "blocked",
                title = title,
                banner = "Handed back: exceeded max heal rounds ($maxHealRounds) \u2014 $executionError",
                percent = 40,
            )
            return
        }

        // Success -> review_ready card (sandbox isolated, host repo remains clean until user reviews and merges)
        activeTickets.remove(convId)
        val harvest = patchPath?.let { StageChain.parseHarvest(File(it), File(repoPath).name) }
        val diff = harvest?.first
        val patchBytes = harvest?.second ?: 0L
        val artifactsList = if (patchPath != null) listOf("patch.diff" to patchBytes) else emptyList()
        val defaultSummary = if (diff != null) {
            "${diff.summary ?: "Changes ready"}, all tests verified"
        } else {
            "Implementation ready in isolated worktree for review"
        }
        val summary = StageChain.summarize(lastSaid) ?: defaultSummary
        publishTicketCard(
            groupId = groupId,
            ticketId = ticketId,
            rev = revCounter.incrementAndGet(),
            state = "review_ready",
            title = title,
            banner = summary,
            percent = 100,
            artifacts = artifactsList,
            diff = diff,
        )
    }

    private suspend fun publishTicketCard(
        groupId: String?,
        ticketId: String,
        rev: Int,
        state: String,
        title: String,
        banner: String?,
        percent: Int,
        options: List<Pair<String, Boolean>> = emptyList(),
        artifacts: List<Pair<String, Long>> = emptyList(),
        diff: ChainDiff? = null,
    ): String? {
        if (groupId == null) return null
        val progress = ChainProgress(
            chainId = ticketId,
            rev = rev,
            state = state,
            title = title,
            currentRole = "Worker",
            stages = listOf("Worker" to state),
            percent = percent,
            banner = banner,
            options = options,
            artifacts = artifacts,
            diff = diff,
        )
        val json = StageChain.cardJsonOf(progress)
        runCatching {
            controller.broadcastChainCard(
                groupId = groupId,
                agentId = "tidings-worker",
                cardJson = json,
            )
        }.onFailure { t ->
            println("[ORCHESTRATOR] card publish failed: " + t.message)
        }
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
}
