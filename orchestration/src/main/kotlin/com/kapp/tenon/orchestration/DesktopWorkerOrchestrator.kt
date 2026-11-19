// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.ChainDiff
import com.kapp.tenon.agent.ChainProgress
import com.kapp.tenon.agent.ChainRetry
import com.kapp.tenon.agent.ChainStage
import com.kapp.tenon.core.protocol.AgentExecAuthorizer
import com.kapp.tenon.core.protocol.TeamMemberInfo
import com.kapp.tenon.core.protocol.VirtualAgentMember
import com.kapp.tenon.core.protocol.TeamNameMention
import com.kapp.tenon.core.protocol.seatsMatchingToken
import com.kapp.tenon.core.protocol.teamSeatMatchingName
import com.kapp.tenon.agent.ControlPlane
import com.kapp.tenon.agent.DelegationParser
import com.kapp.tenon.agent.DelegationPlan
import com.kapp.tenon.agent.LeaderBrief
import com.kapp.tenon.agent.LeaderMember
import com.kapp.tenon.agent.SandboxPatchRef
import com.kapp.tenon.agent.StageChain
import com.kapp.tenon.agent.drivers.AgentProfiles
import com.kapp.tenon.agent.drivers.DriverCooldownTracker
import com.kapp.tenon.agent.abortRunningSandboxes
import com.kapp.tenon.agent.executeCliTask
import com.kapp.tenon.agent.recentCliSessionState
import com.kapp.tenon.agent.resolveCliDriver
import com.kapp.tenon.core.nowMillis
import com.kapp.tenon.core.protocol.ConvId
import com.kapp.tenon.core.protocol.isTeam
import com.kapp.tenon.domain.CognitiveFlow
import com.kapp.tenon.domain.DispatchBlockedCode
import com.kapp.tenon.domain.RelayController
import com.kapp.tenon.domain.byEmployeeId
import com.kapp.tenon.domain.byTeamId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import com.kapp.tenon.domain.groupParticipantRows
import com.kapp.tenon.domain.RelayUiState
import com.kapp.tenon.domain.TicketRef
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
class DesktopWorkerOrchestrator(
    private val planeProvider: () -> ControlPlane?,
    private val controller: RelayController,
    private val scope: CoroutineScope,
    /**
     * 盲区巡检的落盘目录（编排模型适配设计的观测面，只记录）。
     * null = 不记录（测试与老装置默认关）：观测面必须是可选的，不能把工单绑在它上。
     */
    private val blindSpotDir: File? = null,
    /**
     * 岗位档案 record 的持久化账本（G1 另一半）：内存台账是进程易失的 —— 真机第二单
     * 又把审核棒指给了上一单刚 error 过的 claude。内存查不到时回落这里拿上一进程的
     * 错误史。null = 不做健康回落（测试默认关）。
     */
    private val healthLedger: AgentHealthLedger? = null,
    /**
     * 挡位 B 的 LLM planner（Step 4）：把 `组队:` 目标拆成多棒计划；null / 异常 /
     * 返回 null 一律回落启发式两棒（planner 是增强不是依赖）。产物仍过代码门。
     */
    private val autoPlanner: (suspend (goal: String, available: Set<String>) -> List<ChainStage>?)? = null,
    /**
     * 群模板存储（编排规范 §16）：挡位 B 命中该群模板时直接采用
     * 配置选棒并跳过 planner。null = 没装配（测试与老装置）⇒ 行为与从前逐字一致。
     */
    private val teamStore: DelegationTeamStore? = null,
    /**
     * 团队执行档存储（团队执行方案 §3.2，P0-A）：`@组员` 的宿主侧解析与编译靠它。
     * null = 未装配 ⇒ `@组员` 不进团队分支（行为与从前逐字一致）。
     */
    private val teamExecutionStore: TeamExecutionStore? = null,
    /**
     * 群 → 项目目录 dirId（D16，执行档主键的一半）。null/缺省 = 没装配 ⇒ 群分支
     * 按 NotConfigured 退单。装配方给 HostDirectoryStore.pointerOf。
     */
    private val groupDirIdOf: (groupId: String) -> String? = { null },
    /**
     * dirId → 目录档案的**本机路径**（D16 的另一半）。装配方给
     * `HostDirectoryStore.directoryOf(id)?.path`。
     *
     * 为什么单兵派单需要它：群绑定项目目录在 D16 之后有两条落点 —— 群元数据的
     * `defaultWsId`（席位投影）与宿主目录档案的指针（`groupDirIdOf`）。单兵路径原先
     * 只读前者，于是「用目录指针绑过、但席位投影还没重建」的群会被判成
     * `no git workspace bound to group`（真机现场：手机上选完项目目录，派单还是退单）。
     * 两条落点指的是同一个目录，执行点必须两条都认。null = 未装配 ⇒ 只认老口径。
     */
    private val dirPathOf: (dirId: String) -> String? = { null },
    /**
     * 节点级目录白名单（D10/D15）：`(path, requireWrite) -> 是否放行`。**默认 deny**
     * 在 store 里实现（空白名单 = 全拒）；这里 null = 未装配（测试与老装置），
     * 行为与从前一致。装配后：未登记的路径一律不许执行 —— fail-closed，
     * 不回落任何别的目录（回落 = 换一个仓写字，比拒绝更糟）。
     */
    private val pathAllowed: ((path: String, requireWrite: Boolean) -> Boolean)? = null,
    private val detailedAutoPlanner: (suspend (goal: String, available: Set<String>) -> PlannerPlan?)? = null,
    /**
     * 团队计划的 LLM 重排（leader 动态编排）：只重排 reviewer 顺序与定制各棒指令，
     * 成员/驱动/模型/链形状不许动，门校验不过或 LLM 不可用 ⇒ 回落编译固定链。
     * null = 未装配（测试与老装置）⇒ 行为与从前逐字一致。
     */
    private val teamPlanRefiner: (suspend (plan: DelegationPlan) -> DelegationPlan?)? = null,
    /**
     * 责任链**审核面**的裁决（Planner）：审不审、审几棒、谁审。null = 未装配（测试与老
     * 装置）⇒ 行为与从前逐字一致（编译固定链）。返回 null 同样回落编译链。
     *
     * 与 [teamPlanRefiner] 的分层：本口在形状**未定**之前做 L1 裁决，refiner 在形状
     * 已定之后改 L3 文案；采纳了本口的建议就跳过 refiner（同一批 instruction 不写两遍）。
     */
    private val teamPlannerChain: (suspend (PlannerChainInput) -> OrchestrationSuggestion?)? = null,
    /**
     * 派单指令尾部的**能力简报**（P3 工具面）：告诉这根棒「本机还提供哪些可以调的动作」。
     *
     * 返回 null = 这次不追加（没装配档案面 / headless）—— **不写就是没有**，绝不追加一段
     * 指向不存在路由的说明（agent 会照着去调，然后拿到 404/503，浪费一轮）。
     * 内容来自 `RosterTools.brief()`，与 `GET /v1/agent/tools` 同一份，不另写文案。
     */
    private val rosterBrief: (() -> String?)? = null,
    /**
     * 「本机装了哪些底座」的**接缝**：null = 问控制面（生产路径，唯一正确来源）。
     *
     * 非 null 只用于单元测试：点将（主理人选谁下场）与逐人授权都**要求有候选池**
     * —— 没人可点就谈不上点将，而单测里没有控制面。没有这个接缝，「点将」只能在真机
     * 上验，而它恰恰是最容易静默退回「只按锚点」的那一类改动。
     * **生产装配不许传它**：在不在位只有控制面答得了。
     */
    private val availableDriversHint: (() -> Set<String>?)? = null,
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

    /** Fail-Loud 检查项载荷：崩了就要给得出 observation 与可下钻的 drawer。 */
    data class StageChainFailure(val observation: String, val drawer: String?)

    data class ActiveTicket(
        val ticketId: String,
        val convId: String,
        val groupId: String?,
        val job: Job,
        val title: String,
        val repoPath: String,
        /** 工单创建时刻：执行中卡的耗时段（规格二的 `编码中 (45s)`）从这里起算。 */
        val createdAt: Long = nowMillis(),
        @Volatile var stage: String,
        val rev: AtomicInteger = AtomicInteger(0),
        val role: String = "Worker",
        /**
         * 这张卡的**驱动键**（codex/claude/antigravity…），广播与授权共用它。
         * 曾经写死 `tenon-worker`：卡面看不出是谁干的还在其次 —— 用户点卡上
         * 的 Approve，动作帧按卡片的 agentId 过 `AgentExecAuthorizer`，假名
         * 过不了授权，「已提交，等待宿主确认」永远等不来执行（真机实锤）。
         */
        val agentKey: String = "codex",
        /**
         * 卡面上那行「谁在干」的**人名**（组员名 / 员工档案名），null = 不知道是谁
         * （裸驱动派单）——那时卡面回落驱动名（旧行为）。
         *
         * 为什么单独存：用户点的是**人**（@员工 / @组员），卡面却只认得出**引擎**
         * （「Codex（本机）」×3）—— 同底座两个员工的两张单在用户眼里无法区分，
         * 「这一单点了谁」（planDecision 已带 workerName）在单兵卡上断了线。
         * 广播与授权仍认 [agentKey]（驱动键），人名只进显示字段 —— 两件事两个来源。
         */
        val assigneeName: String? = null,
        /**
         * 这张工单派单时用的**显式工作区指针**（1:1 药丸 / 派单帧携带）。
         * 「方向确认卡 → 派为新任务」那条腿必须原样带回去：1:1 会话没有群绑定
         * 可推导（groupId=null），多工作区宿主上丢了它就必退单
         * 「no git workspace bound to group」（真机现场：ask 卡新任务腿在
         * 药丸明明选着工作区时被退单）。
         */
        val wsId: String? = null,
        /**
         * 这一单用哪个**模型**（执行档 `models.first`）：null = 跟随驱动默认。
         *
         * 与 [agentKey] 同一来源（团队派单从 `TeamPlanCompiler` 带下来），一路带到
         * `executeCliTask(model = …)`。单独存一份而不现查执行档：工单跑起来之后
         * 执行配置可能被改，跑一半换模型比不换更危险。
         */
        val model: String? = null,
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
        /**
         * 打回卡片上用户写的反馈文本（[RevisionFeedback] 解出来的）。
         *
         * 只为一件事存在：[retryByCard] 重跑自愈循环时，把它作为**硬约束前置**进
         * 下一轮提示词（[WorkerPrompt.build]）—— 打回必须能变成下一轮的验收标准，
         * 而不是一句聊过就丢的话。
         */
        @Volatile var userConstraints: String? = null,
        /** 人工纠偏的终验机会是否用过：只允许一次，第二次请求直接转人工（P2 规格 4）。 */
        @Volatile var steeringUsed: Boolean = false,
        /** 终局一次性完成（成功/受阻/退单/中止/原型待选）；后续再 complete 是无操作。 */
        val done: CompletableDeferred<TicketOutcome> = CompletableDeferred(),
        /** 该工单绑定的底层 Agent 执行会话 ID（供工牌穿透使用）。 */
        @Volatile var sessionId: String? = null,
        /**
         * 这一单的责任链形状是谁定的（采纳了 Planner 的裁决，还是回落编译链、为什么）。
         * 终局时随飞行记录落盘：0 审查棒是合法形状，合法的事更需要有据可查。
         */
        @Volatile var planDecision: FlightPlanDecision? = null,
        /**
         * 这一单该找谁审（单兵入口才有）：终态卡上的「请 TA 复检」落点。
         *
         * 存下来而不是发卡时现查：跑到一半编制可能被改，那时现查会把卡上的名字换成
         * 另一个人 —— 用户点的是「当初那个审查员」，不该被静默换人。
         */
        @Volatile var reviewBy: com.kapp.tenon.agent.ChainReviewTarget? = null,
    ) {
        val targetConvId: String get() = groupId ?: convId

        /** 卡面承办人：人名优先，不知道是谁才回落驱动键（旧行为）。 */
        fun displayActor(): String = assigneeName ?: agentKey
    }

    private val activeTickets = ConcurrentHashMap<String, ActiveTicket>()

    /**
     * review_ready 终态卡的**可打回快照**：工单发终态卡时已从 [activeTickets] 移除
     *（终态语义，避免 abort/retry 误操作），但横幅承诺了「等你合并或打回」——
     * 打回要用当初那张工单的全套参数（devPrompt/repoPath/convId/…）才能重跑。
     * 这里留一份只读快照，打回时由 [steerableTicket] 复活进 activeTickets 再走
     * 既有重跑路径（复活即从快照移除）。合并成功后的残留到宿主重启即散，每单
     * 一个对象、ticketId 不复用，不会被误读。
     */
    private val reviewableTickets = ConcurrentHashMap<String, ActiveTicket>()

    /** ticketId → 底层 CLI 会话 id：卡片 JSON/工牌穿透的唯一 producer。 */
    private val ticketSessionIds = ConcurrentHashMap<String, String>()

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

    /**
     * 卡已经翻面（不会再派动作）后丢掉这条登记 —— [groupByCard] 是进程内登记簿，
     * 不清理就随卡片数量线性增长。只在「处理完且卡已翻面」的路径上调。
     */
    fun forgetCardGroup(cardId: String) {
        groupByCard.remove(cardId)
    }

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
    fun stripMentions(text: String): String = AbortSignals.stripMentions(text)

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
    fun abortIfRequested(convId: String, text: String): Boolean {
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
            scope.launch { publishCardJson(active.targetConvId, abortedJson, active.agentKey) }
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
    fun followUpIfActive(convId: String, prompt: String): Boolean {
        val active = activeTicketsForConv(convId).firstOrNull {
            it.stage in setOf("blocked", "prototype", "proposed", "working")
        } ?: return false
        val trimmed = stripMentions(prompt)
        if (trimmed.isBlank()) return false
        if (active.stage == "working" && !isAbortSignal(prompt)) {
            return steerActiveTicket(active, trimmed)
        }
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
                    groupId = active.targetConvId,
                    ticketId = active.ticketId,
                    rev = active.rev.incrementAndGet(),
                    state = "working",
                    title = active.title,
                    banner = "Resumed: processing follow-up",
                    percent = 50,
                    roleName = active.role,
                    agentId = active.agentKey,
                assignees = listOf(active.displayActor()),
                startedAt = active.createdAt,
                )
                handler.invoke(trimmed)
            }
        }
        return true
    }

    /** 待用户裁决去向的 prototype 输入（键 = ticketId）。见 [followUpIfActive] 的判据。 */
    private data class PendingPrototypeInput(val text: String)

    private val pendingPrototypeInputs = ConcurrentHashMap<String, PendingPrototypeInput>()

    /** 绿场目录待确认初始化的工单（键 = ticketId）。 */
    private data class PendingBootstrap(
        val ticketId: String,
        val convId: String,
        val groupId: String?,
        val prompt: String,
        val agentKey: String,
        val wsId: String?,
        val repoPath: String,
        val deferred: CompletableDeferred<TicketOutcome>,
    )

    private val pendingBootstraps = ConcurrentHashMap<String, PendingBootstrap>()

    /**
     * 发「这是调整原型，还是新任务？」的确认卡，返回卡 JSON（供 invoke 路径把
     * 租约以「方向待定」收口 —— 发起方等的是终局，卡片就是这次 invoke 的产出）。
     *
     * 独立 cardId（`chain-<ticketId>-ask`），不顶掉屏上的待选卡；文本暂存在
     * [pendingPrototypeInputs]，action 帧只有 actionId 装不下长文本，点确认时
     * 再取回执行（[confirmPrototypeInput]）。
     */
    private suspend fun askPrototypeDirection(active: ActiveTicket, text: String): JsonObject {
        pendingPrototypeInputs[active.ticketId] = PendingPrototypeInput(text)
        val excerpt = text.take(80) + if (text.length > 80) "…" else ""
        // ChainProgress 没有动作字段（卡上的 actions 一直是手工构建的，如 diff-review
        // 冲突卡），方向确认卡同款：独立 cardId（`chain-<ticketId>-ask`），不顶掉屏上的
        // 待选卡。两个动作各接一个明确去向 —— options 是「回给 agent 的预设文本」，
        // 装不下「去向」语义，所以这里必须走 action 帧。
        val bannerText = controller.uiTexts.adjustOrNewTaskPrompt(excerpt)
        val adjustLabel = controller.uiTexts.sendAsAdjustmentLabel()
        val newTaskLabel = controller.uiTexts.sendAsNewTaskLabel()
        val askJson = buildJsonObject {
            put("cardId", JsonPrimitive("chain-" + active.ticketId + "-ask"))
            put("rev", JsonPrimitive(1))
            put("type", JsonPrimitive("direction-ask"))
            put("state", JsonPrimitive("blocked"))
            put("title", JsonPrimitive(active.title))
            put("progress", JsonPrimitive(60))
            putJsonObject("banner") {
                put("text", JsonPrimitive(bannerText))
                put("tone", JsonPrimitive("info"))
            }
            put("actions", buildJsonArray {
                add(buildJsonObject {
                    put("actionId", JsonPrimitive("refine-pending"))
                    put("label", JsonPrimitive(adjustLabel))
                    put("labelKey", JsonPrimitive("card.action.send_as_adjustment"))
                })
                add(buildJsonObject {
                    put("actionId", JsonPrimitive("dispatch-pending"))
                    put("label", JsonPrimitive(newTaskLabel))
                    put("labelKey", JsonPrimitive("card.action.send_as_new_task"))
                })
            })
        }
        publishCardJson(active.targetConvId, askJson.toString(), active.agentKey)
        return askJson
    }

    /**
     * 方向确认卡上的按钮落地。`cardId` 形如 `chain-<ticketId>-ask`。
     *
     * - 派为新任务：旧原型工单就地收尾（等选布局的等待已无意义），文本走完整派单
     *   路由（是否原型意图由 [isPrototypeIntent] 重新判，不继承旧工单）；
     * - 作为调整：回到原 refine 路径（working 卡 + onFollowUp）。
     */
    fun confirmPrototypeInput(cardId: String, asNewTask: Boolean): Boolean {
        val tid = cardId.removePrefix("chain-").removeSuffix("-ask")
        val pending = pendingPrototypeInputs.remove(tid) ?: return false
        val active = activeTickets[tid] ?: return false
        if (asNewTask) {
            active.stage = "superseded"
            activeTickets.remove(tid)
            active.done.complete(
                TicketOutcome("aborted", null, "superseded: input was dispatched as a new task", null),
            )
            // wsId 必须随腿走：新任务腿继承原工单的显式工作区指针。丢了它，
            // 1:1（groupId=null）+ 多工作区宿主在 resolveWorkspacePath 里无路可走
            // （群绑定/简介判据都不适用，单工作区推导又不唯一），必退单
            // 「no git workspace bound to group」——真机现场见 runtime-evidence
            // 的 DSH 工作区验证证据 §6 O7（方向确认卡新任务腿在药丸选着工作区时被退单）。
            dispatchSingleWorker(active.convId, active.groupId, pending.text, active.agentKey, active.wsId)
        } else {
            active.stage = "working"
            scope.launch {
                publishTicketCard(
                    groupId = active.targetConvId,
                    ticketId = active.ticketId,
                    rev = active.rev.incrementAndGet(),
                    state = "working",
                    title = active.title,
                    banner = "Resumed: processing follow-up",
                    percent = 50,
                    roleName = active.role,
                    agentId = active.agentKey,
                assignees = listOf(active.displayActor()),
                startedAt = active.createdAt,
                )
                active.onFollowUp?.invoke(pending.text)
            }
        }
        return true
    }

    /**
     * 绿场初始化确认卡上的按钮落地。`cardId` 形如 `chain-<ticketId>-bootstrap`。
     */
    fun confirmBootstrap(cardId: String, init: Boolean): Boolean {
        val tid = cardId.removePrefix("chain-").removeSuffix("-bootstrap")
        val pending = pendingBootstraps.remove(tid) ?: return false
        if (init) {
            val initRes = git(pending.repoPath, "init", "-b", "main")
                ?: git(pending.repoPath, "init")
            RelayLog.info("ORCHESTRATOR", "git init on " + pending.repoPath + ": " + initRes)
            // 没有本地身份时 commit 会失败；先补仓库级身份，再确认 HEAD 真的存在。
            git(pending.repoPath, "config", "user.email", "agent@tenon.local")
            git(pending.repoPath, "config", "user.name", "TenonAgent")
            val commitRes = git(pending.repoPath, "commit", "--allow-empty", "-m", "Initial commit")
            val hasHead = git(pending.repoPath, "rev-parse", "--verify", "--quiet", "HEAD") != null
            if (commitRes == null || !hasHead) {
                RelayLog.info("ORCHESTRATOR", "bootstrap failed on " + pending.repoPath + ": no HEAD after init")
                pending.deferred.complete(
                    TicketOutcome(
                        state = "blocked",
                        summary = null,
                        error = "Bootstrap failed: repository has no initial commit",
                        cardJson = null,
                    ),
                )
                return true
            }
            scope.launch {
                val outcome = dispatchSingleWorker(
                    convId = pending.convId,
                    groupId = pending.groupId,
                    prompt = pending.prompt,
                    agentKey = pending.agentKey,
                    wsId = pending.wsId,
                ).await()
                pending.deferred.complete(outcome)
            }
        } else {
            val cancelledOutcome = TicketOutcome(
                state = "aborted",
                summary = null,
                error = "Bootstrap cancelled by user",
                cardJson = null,
            )
            pending.deferred.complete(cancelledOutcome)
        }
        return true
    }

    /**
     * 移动端点击「Approve & Develop」原型确认按钮。
     */
    fun approvePrototype(convId: String): Boolean {
        val active = activeTicketsForConv(convId).firstOrNull { it.stage == "prototype" } ?: return false
        val handler = active.onFollowUp ?: return false
        scope.launch {
            active.stage = "working"
            publishTicketCard(
                groupId = active.targetConvId,
                ticketId = active.ticketId,
                rev = active.rev.incrementAndGet(),
                state = "working",
                title = active.title,
                banner = "Prototype approved: initializing implementation & tests...",
                percent = 30,
                roleName = active.role,
                agentId = active.agentKey,
                assignees = listOf(active.displayActor()),
                startedAt = active.createdAt,
            )
            handler.invoke("APPROVE_AND_DEVELOP")
        }
        return true
    }

    fun approvePrototypeByCard(cardId: String): Boolean {
        val tid = cardId.removePrefix("chain-")
        val active = activeTickets[tid] ?: activeTickets.values.firstOrNull { it.ticketId == tid } ?: return false
        if (active.stage != "prototype") return false
        val handler = active.onFollowUp ?: return false
        scope.launch {
            active.stage = "working"
            publishTicketCard(
                groupId = active.targetConvId,
                ticketId = active.ticketId,
                rev = active.rev.incrementAndGet(),
                state = "working",
                title = active.title,
                banner = "Prototype approved: initializing implementation & tests...",
                percent = 30,
                roleName = active.role,
                agentId = active.agentKey,
                assignees = listOf(active.displayActor()),
                startedAt = active.createdAt,
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
    fun retryByCard(cardId: String): Boolean {
        val tid = cardId.removePrefix("chain-")
        val active = activeTickets[tid] ?: activeTickets.values.firstOrNull { it.ticketId == tid }
        if (active == null) {
            // 链工单的重试：链执行器的恢复注册表（同形状同参数重派，新 chainId）。
            // 探测能力而非写死类型：老装配没有 ChainRetry = 接不住，调用方如实翻面。
            val host = planeProvider() ?: return false
            val retried = (host.chainDispatcher as? ChainRetry)?.retry(tid) == true
            if (retried) {
                RelayLog.info("RELAY-INVOKE", "chain retry dispatched (card " + cardId + ")")
            }
            return retried
        }
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
                    initialConstraints = active.userConstraints,
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

    /**
     * 打回反向注水（Phase 4 任务 2）/ 熔断后的人机纠偏（P2 规格 4）：反馈文本作为硬约束
     * 前置进提示词，**保留已有 worktree 现场**，只追加 1 轮终验（不再是重启整个 3 轮循环）。
     *
     * 动作帧只有一个 actionId 字符串槽，反馈以 `[RevisionFeedback.PREFIX]<文本>`
     * 编码进 actionId（编码侧在 composeApp 卡片层，两侧测试各钉一份）。拆出来的文本
     * 存进 [ActiveTicket.userConstraints]，随后的重跑把每一轮提示词都用它前置 ——
     * 打回不再是死胡同：人的判断变成下一轮的验收标准。
     *
     * 只有**活着且能重跑**的工单接得住打回：与 [retryByCard] 同判据（blocked 才有
     * 可重跑的循环），否则返回 false 由调用方如实翻面，不假装接下了反馈。
     */
    fun rejectRevisionByCard(cardId: String, actionId: String): Boolean {
        val feedback = RevisionFeedback.feedbackOf(actionId) ?: return false
        val tid = cardId.removePrefix("chain-")
        // 活跃表优先；review_ready 工单已退到快照表（发终态卡时移除），从这里复活。
        // 单兵表与快照表都没有 = 团队责任链卡（chain-<chainId> 全程不写 activeTickets）：
        // 交给链执行器的恢复注册表按同形状同参数重派、并把反馈前置进每棒。
        val active = steerableTicket(tid) ?: return rejectViaChain(tid, feedback)
        // 打回的两个落点：熔断受阻卡（纠偏，单次授权）与 **review_ready 终态卡**
        // （「等你合并或打回」的打回 —— 卡面承诺过这个出口，宿主不发动作/不收
        // review_ready 档，承诺就断在卡面上；真机 UX 轮现场）。两档共用同一套
        // 反馈注入：stage 翻 working、反馈进硬约束、重跑开发循环。
        if (active.stage != "blocked" && active.stage != "review_ready") return false
        val prompt = active.devPrompt ?: return false
        // 单次授权：熔断后只追加 1 轮终验，第二次请求直接拒收（转人工，不无限循环）。
        val grant = HealSteering.grantOnce(feedback, active.steeringUsed) ?: return false
        active.steeringUsed = true
        active.userConstraints = grant.instruction
        active.stage = "working"
        // 反哺（§4.3.2 最小形态）：人的打回意见沉淀进仓库避坑账本 —— pattern 留空
        //（恒匹配、垫底层），后续同仓派单都会带上这条约束；pattern 由人按需补精准。
        runCatching {
            PitfallLedger.append(
                File(active.repoPath),
                PitfallLedger.Pitfall(source = "reject", pattern = "", rule = grant.instruction, createdAt = System.currentTimeMillis()),
            )
        }
        scope.launch {
            publishTicketCard(
                groupId = active.targetConvId,
                ticketId = active.ticketId,
                rev = active.rev.incrementAndGet(),
                state = "working",
                title = active.title,
                banner = "Human steering attempt " + grant.attempt + "/" + grant.maxAllowed +
                    ": " + grant.instruction.take(80),
                percent = 40,
                roleName = active.role,
                agentId = active.agentKey,
                assignees = listOf(active.displayActor()),
                startedAt = active.createdAt,
            )
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
                    initialConstraints = grant.instruction,
                    maxHealRounds = 1,
                )
            } catch (e: CancellationException) {
                active.done.complete(TicketOutcome("aborted", null, "aborted", null))
                throw e
            } catch (t: Throwable) {
                TicketOutcome("error", null, t.message ?: (t::class.simpleName ?: "revision failed"), null)
            }
            active.done.complete(outcome)
        }
        return true
    }

    /**
     * 放弃并保留现场（P2 规格 4 场景 4 的第二颗按钮）：不重跑、不清理 worktree，
     * 只把工单终局翻成 aborted 并给一张可见卡 —— 人可以去桌面端接管那 90% 的代码。
     */
    fun abandonHealByCard(cardId: String): Boolean {
        val tid = cardId.removePrefix("chain-")
        // 活跃表优先；review_ready 工单已退到快照表，这里复活后翻 aborted。
        val active = steerableTicket(tid) ?: return false
        // 打回的两个落点：熔断受阻卡（纠偏，单次授权）与 **review_ready 终态卡**
        // （「等你合并或打回」的打回 —— 卡面承诺过这个出口，宿主不发动作/不收
        // review_ready 档，承诺就断在卡面上；真机 UX 轮现场）。两档共用同一套
        // 反馈注入：stage 翻 working、反馈进硬约束、重跑开发循环。
        if (active.stage != "blocked" && active.stage != "review_ready") return false
        active.stage = "aborted"
        active.done.complete(TicketOutcome("aborted", null, "Human abandoned; worktree kept", null))
        scope.launch {
            publishTicketCard(
                groupId = active.targetConvId,
                ticketId = active.ticketId,
                rev = active.rev.incrementAndGet(),
                state = "aborted",
                title = active.title,
                banner = "Abandoned by user; worktree kept at " + active.repoPath,
                percent = 100,
                roleName = active.role,
                agentId = active.agentKey,
                assignees = listOf(active.displayActor()),
                startedAt = active.createdAt,
            )
        }
        return true
    }

    /**
     * 人机纠偏（打回/放弃）按 cardId 反查可操作的工单：活跃表优先，其次是
     * review_ready 快照表（那张卡发出时工单已退下活跃表）。**复活**意味着从
     * 快照移除并放回活跃表 —— 重跑后它又会走一遍完整生命周期（可能再次进快照）。
     * 查不到返回 null，调用方如实翻 stale，不假装接下了动作。
     */
    private fun steerableTicket(tid: String): ActiveTicket? {
        activeTickets[tid]?.let { return it }
        activeTickets.values.firstOrNull { it.ticketId == tid }?.let { return it }
        reviewableTickets.remove(tid)?.let { active -> activeTickets[tid] = active; return active }
        return null
    }

    /**
     * 团队责任链卡的打回出口：链工单不写 [activeTickets]，cardId 去掉前缀就是
     * chainId。探测 `as? ChainRetry`（与 [retryByCard] 同纪律）：老装配没实现
     * reject = 接不住，返回 false 由调用方如实翻 stale，不假装接下反馈。
     */
    private fun rejectViaChain(chainId: String, feedback: String): Boolean {
        val host = planeProvider() ?: return false
        return (host.chainDispatcher as? com.kapp.tenon.agent.ChainRetry)
            ?.reject(chainId, feedback) == true
    }

    /**
     * 审批合流驱动（Phase 4 任务 1）：合并类动作的唯一宿主入口。
     *
     * 硬约束（计划书 §九.1 物理合流防伪造）：`decidedBy` 合流卡的落定**不可由前端
     * 伪造** —— 只有 [DesktopPatchMerge.apply] 真的跑完 `git apply` 且子进程退出码
     * 为 0（返回 [DesktopPatchMerge.Outcome.Applied]），调用方才允许下发 rev+1 的
     * decidedBy 翻面卡；冲突/缺账如实返回 Conflict，调用方发失败卡，**不许**把
     * 冲突包装成已合并。
     *
     * 登记簿优先：工单卡在发 review_ready 的那一刻就登记了（工作区, patch），
     * 不靠会话台账反推（工单卡从不写台账，反查恒为 null —— 真机实锤过的缺陷）。
     */
    fun mergePatchByCard(cardId: String): DesktopPatchMerge.Outcome {
        val ref = patchRefForCard(cardId)
            ?: return DesktopPatchMerge.Outcome.Conflict("no sandbox patch recorded for card " + cardId)
        return DesktopPatchMerge.apply(patchPath = ref.patchPath, workspacePath = ref.workspacePath)
    }

    /**
     * 单兵终态卡上的「请 TA 复检」（`review-patch|<组员名>`）：把这一单的活**另派一次纯审查**。
     *
     * ## 为什么是「另派一单」而不是「在这张卡上加一棒」
     *
     * 这张卡背后没有链：单兵入口本来就不展开审查棒（`@组员` 干什么就是什么）。要审，
     * 就得有一次真的进程 —— 而每一次真的进程都应该是**一张自己能收尾的卡**，否则
     * 「审完了没有、审出什么」在这张已经终态的卡上无处安放。所以：新工单、新卡、
     * 审查员的结论按既有通路（无补丁 + 有正文 ⇒ `done`）落到那张卡上。
     *
     * ## 判据
     *
     * 四处都得有，缺一不派、如实返回 false 让调用方翻面并留日志：
     * 会话键（登记簿 → 落库卡片行）、补丁账（[TicketPatchLedger]）、这一单的目标
     * （卡片标题）、以及**组员名回查**（[TeamExecutionResolver]，按编制读驱动键）。
     *
     * 授权不在这一层判：卡片动作帧（`op=action`）只带 cardId + actionId，**不带发送者**，
     * 宿主判不出「是谁点的」。这与 `merge-patch` / `retry` / `start-task` 是同一个信任级，
     * 这里不发明一套比合并补丁更严的判据 —— 那只会让人以为这一层有权判据而别处没有。
     */
    fun reviewByCard(cardId: String, reviewerToken: String): Boolean {
        val token = reviewerToken.trim()
        if (token.isEmpty()) return false
        // 会话键：登记簿（活着时最准）→ 落库的卡片行 `peerId`（宿主重启后唯一还在的）。
        // 与 `attach-team` / 合并分支同一条兜底 —— 只认登记簿的话，重启后点这张卡
        // 就是「毫无反应」，而那正是本仓反复踩过的那条。
        val row = controller.state.value.messages.values
            .asSequence()
            .flatten()
            .lastOrNull { r -> (r.renderCard?.get("cardId") as? JsonPrimitive)?.contentOrNull == cardId }
        // 登记簿的历史条目可能是**裸 groupId**（修 e5f0bf4e 之前的旧写入）或会话键；
        // 卡片行的 peerId 恒为会话键。两种形态都认，归一成 groupId 再判群。
        val convKey = groupByCard[cardId] ?: row?.peerId ?: run {
            RelayLog.info("RELAY-INVOKE", "request-review: no conversation for card " + cardId)
            return false
        }
        val groupId = ConvId.groupIdOf(convKey)
            ?: convKey.takeIf { raw -> controller.state.value.settings.groups.any { it.groupId == raw } }
            ?: run {
                RelayLog.info("RELAY-INVOKE", "request-review: conv is not a group (" + convKey + ")")
                return false
            }
        val ledger = TicketPatchLedger.lookup(cardId)
        val patch = ledger?.patchPath
            ?.let { path -> runCatching { File(path).takeIf { it.isFile }?.readText() }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
            ?: run {
                // 静默出口必须留读数：账上没这一笔 = 「合并」那条路也接不住这张卡
                // （账簿是两颗按钮共用的地基），用户会以为只有复检坏了。
                RelayLog.info(
                    "RELAY-INVOKE",
                    "request-review: no patch ledger entry (card " + cardId +
                        ", ledger=" + (ledger != null) + ", file=" +
                        ledger?.patchPath?.let { File(it).isFile } + ")",
                )
                return false
            }
        val goal = (row?.renderCard?.get("title") as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: run {
                RelayLog.info("RELAY-INVOKE", "request-review: card has no title (card " + cardId + ")")
                return false
            }
        val hit = resolveTeamTarget(groupId, token).also {
            if (it !is TeamExecutionResolver.Target.Hit) {
                RelayLog.info(
                    "RELAY-INVOKE",
                    "request-review: token '" + token + "' resolved " + it::class.simpleName +
                        (if (it is TeamExecutionResolver.Target.NotConfigured) " (" + it.reason + ")" else ""),
                )
            }
        } as? TeamExecutionResolver.Target.Hit ?: return false
        val spec = hit.profile.members.firstOrNull {
            it.agentId.trim().equals(hit.member.agentId.trim(), ignoreCase = true)
        } ?: run {
            RelayLog.info(
                "RELAY-INVOKE",
                "request-review: member " + hit.member.agentId + " not in the execution profile",
            )
            return false
        }
        // 审查员自己也得在位：`@组员` 派单时判过的是干活那位，没判过审的人。
        val available = availableAgentKeys()
        if (available != null && spec.driverKey !in available) {
            RelayLog.info(
                "RELAY-INVOKE",
                "request-review: driver '" + spec.driverKey + "' not in place (available: " +
                    available.joinToString(",") + ")",
            )
            return false
        }
        val instruction = CardReviewAsk.instruction(
            goal = goal,
            roleLabel = hit.member.role,
            customPrompt = spec.customPrompt,
            patch = patch,
        )
        RelayLog.info(
            "RELAY-INVOKE",
            "request-review: card " + cardId + " -> " + spec.agentId +
                " (driver " + spec.driverKey + ", " + patch.length + " chars of diff) group=" + groupId,
        )
        dispatchSingleWorker(
            ConvId.group(groupId), groupId, instruction, spec.driverKey,
            wsId = hit.seat.defaultWsId,
            model = spec.models.firstOrNull(),
        )
        return true
    }

    fun followUpByCard(cardId: String, text: String): Boolean {
        val tid = cardId.removePrefix("chain-")
        val active = activeTickets[tid] ?: activeTickets.values.firstOrNull { it.ticketId == tid } ?: return false
        if (active.stage !in setOf("blocked", "prototype")) return false
        val handler = active.onFollowUp ?: return false
        val trimmed = stripMentions(text)
        if (trimmed.isBlank()) return false
        scope.launch {
            active.stage = "working"
            publishTicketCard(
                groupId = active.targetConvId,
                ticketId = active.ticketId,
                rev = active.rev.incrementAndGet(),
                state = "working",
                title = active.title,
                banner = "Resumed: processing follow-up",
                percent = 50,
                roleName = active.role,
                agentId = active.agentKey,
                assignees = listOf(active.displayActor()),
                startedAt = active.createdAt,
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
        wsId: String? = null,
        /** 原文被点名的键（小写）；null = 没点名（例如工作区群里回复 agent 的接话）。 */
        mentionedAgentKey: String? = null,
    ) {
        val groupId = ConvId.groupIdOf(convId)
        val active = activeTicketForConv(convId)

        if (replyToId != null && handleCardFollowUpQa(convId, groupId, replyToId, prompt, wsId, mentionedAgentKey)) {
            return
        }

        // 1. Direct Abort signal（第二道防线：`@codex 停` 这类「既是 prompt 又是打断」
        //    的消息由入站前移判定拦下；万一有别的入口直接调到这里，这里再拦一次）。
        if (active != null && isAbortSignal(prompt)) {
            abortIfRequested(convId, prompt)
            return
        }

        if (active != null && active.stage == "working") {
            val trimmedPrompt = stripMentions(prompt)
            if (trimmedPrompt.isNotBlank()) {
                steerActiveTicket(active, trimmedPrompt)
                return
            }
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
                assignees = listOf(active.displayActor()),
                startedAt = active.createdAt,
                )
                handler.invoke(prompt)
                return
            }
        }

        // 3. 认知研讨流分诊（P2 规格 1）：没带显式派单指令 ⇒ **只读讨论**，永不进工单。
        //
        //    位置必须是这里（委派解析之前）：`组队:` / 阶段语法被 CognitiveFlow 判成
        //    Deliver，所以真派单不会被吞进讨论；反过来，一句「这个接口怎么设计」不会
        //    拉起 WorktreeSandbox —— 那才是本条规格要防的不可撤销错误。
        //
        //    fire-and-forget：讨论要跑外部 CLI（几十秒级），不能把入站管线挂在这里，
        //    否则同一群里后续消息全部排队。
        // 显式 @ 点名 = **显式派单指令**（路由表：显式点名 ⇒ 工单）—— 不进讨论。
        // A22 D18：这里曾用 `isMentioningAgent(prompt, ...)` 重判点名，但 prompt 在域层
        // 已经被 `extractAgentPrompt` 剥净了 @，那句判据恒为 false —— 群 @ 工单句
        // （"create …" 不带软动词）被分诊改判成只读讨论，工单静默消失。
        // 事实改由路由点随帧带过来（[mentionedAgentKey]），不在接收侧重算第二遍。
        val explicitMention = groupId != null && mentionedAgentKey != null
        if (!explicitMention && CognitiveFlow.classify(prompt) == CognitiveFlow.Intent.Discuss) {
            scope.launch { runDiscussion(convId, groupId, prompt, agentKey = null, wsId = wsId) }
            return
        }

        // 4. New intake: delegation plans first (dual-mode: explicit @-assignment / auto 组队),
        //    then the legacy explicit stage syntax. Plans must pass the code gate (StageChainGate)
        //    before any chain is started — vendor-independent review is a hard rule, not advice.
        val available = availableAgentKeys()

        // **P0-0.3 文本派单授权收口**：文本 @ 原先只有一条本地 notice（不进协议），
        // 执行路径根本不看授权 —— 意图路径有授权而文本路径没有，同一句 @ 两条路两种结果。
        // 团队执行会放大这个洞（一条群消息拉起多驱动链），这里统一收口：
        // 点名的是**本机托管席位** ⇒ 必须过 AgentExecAuthorizer；未授权 ⇒ 广播 blocked 卡
        // （拒绝必须全群可见，本地 notice 手机看不到）并止步。
        if (groupId != null && mentionedAgentKey != null) {
            val denial = deniedSeatDispatch(groupId, mentionedAgentKey, senderPeerId, prompt)
            if (denial != null) {
                recordApprovalRequest(senderPeerId, denial.agentId, "seat dispatch: " + denial.reason.take(60))
                publishTeamDispatchBlocked(
                    groupId, prompt, denial.reason,
                    approvalRecorded = true,
                    code = DispatchBlockedCode.NOT_AUTHORIZED,
                )
                return
            }
        }

        // **团队执行分支**（P0-A，§4.3）：点的是组员 token ⇒ 由席位宿主编译责任链。
        // 两个执行入口（文本路由 / invoke 桥）共用 resolveTeamTarget + compile —— 两条路各说各话
        // 是本仓已有的分叉形态。非组员 token（领队 / kind）保持既有路径逐字不变。
        //
        // **条件里不再要求 `teamExecutionStore != null`**：`@团队名` 命中而本机没有团队执行面时，
        // 若直接跳过这一段，团队名会流到下面的 `AgentProfiles.resolveKey`（匹配不上 ⇒ null）
        // ⇒ `dispatchSingleWorker(null)` 用默认驱动跑一棒。那是「按了整队、跑了个没人配过的底」。
        if (groupId != null && mentionedAgentKey != null) {
            when (val teamTarget = resolveTeamTarget(groupId, mentionedAgentKey)) {
                is TeamExecutionResolver.Target.NotConfigured -> {
                    // 缺配置不猜（§2）：绝不回落到"默认 codex 单棒"。blocked 卡带分类码（P2）。
                    publishTeamDispatchBlocked(
                        groupId, prompt,
                        teamTarget.reason + " (member @" + mentionedAgentKey + ")",
                        code = teamTarget.code,
                    )
                    return
                }
                is TeamExecutionResolver.Target.NotInGroup -> {
                    // 队伍还没进这个群（TEAM_NOT_IN_GROUP）：blocked 卡带码与主行动路由。
                    publishTeamDispatchBlocked(
                        groupId, prompt,
                        teamTarget.reason,
                        code = teamTarget.code,
                        teamIdForAttach = teamTarget.team.teamId,
                    )
                    return
                }
                is TeamExecutionResolver.Target.Hit -> {
                    // 卡面称呼 = 用户打过的那个 token（团队名 / 组员名）。
                    if (runTeamDispatch(convId, groupId, senderPeerId, teamTarget, prompt, mentionedAgentKey)) return
                    return
                }
                TeamExecutionResolver.Target.None -> Unit // 非组员 token：既有路径
            }
        }

        if (available != null) {
            val plan = parseDelegation(prompt, available, groupId)
            if (plan != null) {
                delegateByPlan(convId, groupId, plan, available, wsId)
                return
            }
        }
        val stages = StageChain.parseStages(prompt)
        if (stages.size >= 2) {
            dispatchMultiRoleChain(convId, groupId, prompt, stages, wsId = wsId)
        } else if (groupId != null) {
            // 群路径保留既有兜底（群绑定解析有据）：点名了谁就把这一棒交给谁。
            // **点名原文原样下传**：驱动解析（执行档 > 员工档案 > 键归一）在
            // dispatchSingleWorker 一处收口 —— 这里先 resolveKey、解析不出传 null 的旧写法
            // 会把「档案配了底座」的席位打回默认驱动，正是 A5 要拔的兜底。
            dispatchSingleWorker(convId, groupId, prompt, mentionedAgentKey, wsId = wsId)
        }
        // 1:1 纯文本到此为止（决议矩阵蓝图 §4.2 的 PlainChat 分支）：普通聊天不派单——
        // 派单走药丸触发的 dispatchDirectInvoke（invoke 通路显式携带 agentKind+wsId）。
        // 从前的「1:1 纯文本默认落 codex」在多工作区宿主上必然退单，工单卡还会派进
        // 不可见的伪会话——移除默认派单就是本次行为变更的全部内容（守卫钉住）。
    }

    /**
     * invoke 执行桥的**可等待入口**：真机 `@agent` 派单帧带 `agentIntent`，域层走
     * `invokeExecutor.execute`（请求-响应），不走 `onAgentPrompt` 的文本路由 ——
     * 这曾经是阶段 A 原型 / 追问唤醒 / 自愈退单在真机上「单测全绿、现场不存在」的
     * 根因：能力都写在编排器里，而真机那条路直调 `executeCliTask` 绕过了它。
     *
     * 现在执行桥交给这里。四个分支与 [handlePrompt] / [followUpIfActive] 同判据、同顺序：
     *
     * 1. **打断**：`abortIfRequested` 已同步把终局（带卡）写进 `done`，await 即得；
     * 2. **受阻工单追问**：唤醒后续跑，等待方拿到「Resumed」回执 —— 真正的结论
     *    由后续工单卡承担（这时 invoke 的语义只是「你的话送到工单了」）；
     * 3. **原型待选追问**：出方向确认卡，租约以 done + 方向卡收口（不是新派单）；
     * 4. **新派单**：建工单并 await 终局（成功/受阻/退单/中止/原型待选）。
     *
     * `agentKey` 来自派单帧的 `agentId`（codex/claude/antigravity…），不再写死 codex。
     */
    suspend fun handlePromptAwait(
        convId: String,
        senderPeerId: String,
        prompt: String,
        replyToId: String?,
        agentKey: String?,
        wsId: String? = null,
        /** 卡面承办人名（见 [ActiveTicket.assigneeName]）：桥上知道是谁就带下来。 */
        assigneeName: String? = null,
    ): TicketOutcome {
        val groupId = ConvId.groupIdOf(convId)
        val active = activeTicketForConv(convId)

        if (replyToId != null) {
            val qaOutcome = handleCardFollowUpQaAwait(convId, groupId, replyToId, prompt, wsId, agentKey)
            if (qaOutcome != null) return qaOutcome
        }

        if (active != null && isAbortSignal(prompt)) {
            abortIfRequested(convId, prompt)
            return active.done.await()
        }

        if (active != null && active.stage == "working" && !isAbortSignal(prompt)) {
            val trimmedPrompt = stripMentions(prompt)
            if (trimmedPrompt.isNotBlank()) {
                steerActiveTicket(active, trimmedPrompt)
                return TicketOutcome("working", "Steering accepted: previous task aborted; redispatching with new instruction", null, null)
            }
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
                scope.launch { publishCardJson(active.targetConvId, json, active.agentKey) }
                scope.launch { handler.invoke(prompt) }
                return TicketOutcome("working", "Resumed: processing follow-up", null, json)
            }
        }

        // 原型待选期来的正文：**不是新派单** —— 与 [followUpIfActive] 同判据（invoke
        // 桥的三个分支必须与文本路由对齐）。原型还没定稿，这张正文该走方向确认卡。
        // 真机现场：这条分支缺失 ⇒ 1:1 药丸派单进原型待选后，同一句
        // 追问在文本路出了方向卡、在 invoke 路又开了一张全新工单（屏上的
        // 「Reply to adjust it」与编排器的实际行为相反）；若追问又逢宿主过载，
        // 新单退单、租约悬空。租约以「方向待定」收口：done + 方向卡，发起方屏上
        // 的卡片就是这次 invoke 的产出。
        if (active != null && active.stage == "prototype") {
            val trimmedPrompt = stripMentions(prompt)
            if (trimmedPrompt.isNotBlank()) {
                val askJson = askPrototypeDirection(active, trimmedPrompt)
                return TicketOutcome(
                    "prototype",
                    controller.uiTexts.adjustOrNewTaskPrompt(
                        trimmedPrompt.take(80) + if (trimmedPrompt.length > 80) "…" else "",
                    ),
                    null,
                    askJson.toString(),
                )
            }
        }

        // 点将期的正文 = 新派单（与 followUpIfActive 同判据）：先撤销倒计时中的工单，
        // 否则 3 秒到点旧单照跑，群里出现两张并行抢沙箱的卡。
        if (active != null && active.stage == "proposed") {
            cancelByCard("chain-" + active.ticketId, "Superseded by a new message")
        }

        // 认知研讨流分诊 —— **invoke 桥不参与分诊**（A22 真机立案）：走到这里的每一单
        // 都是用户在 1:1 里选了工作区、按了发送的**显式派单**，药丸本身就是意图信号；
        // 文本分诊（"how to" 之类的问句标记）把真实工单重新判成讨论 ⇒ 用户选好仓库
        // 发出去的需求变成一场只读聊天（真机：长描述句带 "documents how to run" 被
        // INQUIRY 命中，工单没了）。分诊只属于**普通文本**入口（handlePrompt）——
        // 那里没有药丸，句子语气是唯一的意图线索。判据仍是 CognitiveFlow 一份。
        // 显式带 agentKey 的 invoke 单 = 人已经选了工作区/点了那个 agent，句子语气不该
        // 再把它改判成只读讨论（与文本路由的 mentionedAgentKey 豁免同一条产品口径）。
        val explicitDispatch = !agentKey.isNullOrBlank()
        if (groupId != null && CognitiveFlow.classify(prompt) == CognitiveFlow.Intent.Discuss && !explicitDispatch) {
            return runDiscussion(convId, groupId, prompt, agentKey, wsId)
        }

        // 委派计划优先（双挡：显式点名 / 组队自动），过代码门后才轮到旧阶段语法。
        val available = availableAgentKeys()

        // **团队执行分支**（invoke 桥，§4.3：两个执行入口必须同判据同顺序）。
        // 注意：invoke 路径的 agentKey 是**席位/驱动键**（药丸选的目标），不是组员 token；
        // 组员语义只从文本 @ 来 —— 这里只对 mentionedAgentKey 事实上是组员 token 的
        // 情形生效（runTeamDispatch 内部判），领队派单照旧。
        // **授权收口**（P0-0.3）：invoke 桥的 agentKey 若点名了本机托管席位，同样过
        // AgentExecAuthorizer（与文本路由同一份判据）。团队组员语义只从文本 @ 来
        // （§4.6：agentIntent 不带成员键），团队分发在 [handlePrompt]。
        if (groupId != null) {
            val denial = deniedSeatDispatch(groupId, agentKey, senderPeerId, prompt)
            if (denial != null) {
                recordApprovalRequest(senderPeerId, denial.agentId, "seat dispatch: " + denial.reason.take(60))
                val json = publishTeamDispatchBlocked(groupId, prompt, denial.reason, approvalRecorded = true)
                return TicketOutcome("blocked", "Team dispatch denied", denial.reason, json)
            }
        }

        if (available != null) {
            val plan = parseDelegation(prompt, available, groupId)
            if (plan != null) {
                return delegateByPlanAwait(convId, groupId, plan, available, wsId)
            }
        }
        val stages = StageChain.parseStages(prompt)
        // **派得出去才回受理**。从前这里不看返回值就 `return "accepted"`：宿主没装链执行器
        // （`chainDispatcher == null`）或群没绑仓时，链一棒都不会跑、群里连卡都没有，
        // 而调用方拿到一个「已受理」的回执 —— 用户等一张永不到来的卡。
        // 现在派不出去就**回落到单棒**：宁可只用第一棒的 agent 干完，也不能静默吞单。
        if (stages.size >= 2 && dispatchMultiRoleChain(convId, groupId, prompt, stages, wsId = wsId)) {
            // 多角色链有自己的卡与生命周期（ChainDispatcher），这里只回受理。
            return TicketOutcome("accepted", prompt.lineSequence().firstOrNull()?.trim()?.take(60), null, null)
        }

        // **原始键直接交给 dispatchSingleWorker**：归一必须排在「单兵底座覆盖」之后
        // （覆盖要靠席位 slug 才查得到执行档，归一之后 slug 已经不在了）。归一本体
        // 留在那里，注释也随它一起搬过去。
        return dispatchSingleWorker(convId, groupId, prompt, agentKey, wsId, assigneeName = assigneeName).await()
    }

    /**
     * 一次认知研讨留下的**提议**（等机主点头才转工单）。
     *
     * 只留在内存里是有意的：宿主重启后这张卡上的按钮已经没有对应的提案对象，
     * `hasTicketForCard` 会如实返回 false，客户端把卡翻成失效 —— 比伪造一个
     * 「重新开始」的入口诚实。
     */
    private data class Proposal(
        val cardId: String,
        val convId: String,
        val groupId: String?,
        /** 讨论收口后提炼出的目标：点击「开工」时用它重新过一遍模板与代码门。 */
        val goal: String,
        /** 参与讨论的 agent —— 卡面与日志都要能回答「这方案是谁给的」。 */
        val agents: List<String>,
        /**
         * 任务级显式绑定：讨论发生时挂接的工作区指针，开工时随单走。
         * 提议是工单的前身，绑定跟工单走而不跟会话走——在这里丢掉，
         * 开工就会退回群绑定/自动推导，1:1 多工作区宿主直接退单。
         */
        val wsId: String? = null,
    )

    /** 待确认的提议（cardId → 提议）。 */
    private val proposals = ConcurrentHashMap<String, Proposal>()

    /**
     * 认知研讨流（P2 规格 1）：**只读、受控回合、零工单**。
     *
     * 三条硬约束都在这个函数体里，少一条这条规格就不成立：
     * 1. **零 worktree**：全程不碰 `WorktreeSandbox` / `DesktopStageChain`，cwd 就是群
     *    绑定的那个目录（只读用）。调研期间不会多出一个分支、也不会多出一次提交；
     * 2. **每人 1 轮**（[CognitiveFlow.MAX_AGENT_TURNS]）：一轮之后由宿主收口。
     *    让 agent 自己决定还要不要再说一句，就是一个没有终止条件的循环；
     * 3. **写工具拦截**：只读档若真报了写工具（驱动把 `tool.started` 送上来），
     *    立刻停掉后面的棒、不出提议卡，改发一张如实翻面的 failed 卡。提示词是
     *    「请只读」，这一层是「不许写」—— 后者不能只靠前者。
     */
    private suspend fun runDiscussion(
        convId: String,
        groupId: String?,
        prompt: String,
        agentKey: String?,
        wsId: String? = null,
    ): TicketOutcome {
        val host = planeProvider()
            ?: return TicketOutcome("error", null, "control plane unavailable", null)
        val available = availableAgentKeys().orEmpty()
        val targets = discussionTargets(prompt, agentKey, available)
        if (targets.isEmpty()) {
            return TicketOutcome("error", null, "no agent to discuss with (no mention, no roster)", null)
        }
        val title = prompt.lineSequence().firstOrNull()?.trim()?.take(60) ?: "Discussion"
        val workdir = discussionWorkdir(groupId)
        var violated: String? = null
        var turns = 0
        outer@ for (turn in 0 until CognitiveFlow.MAX_AGENT_TURNS) {
            for (key in targets) {
                host.executeCliTask(
                    agentKey = key,
                    prompt = CognitiveFlow.readOnlyPrompt(prompt),
                    wsPath = workdir,
                    requestedSessionId = null,
                    sessionTitle = title,
                    // 会话对齐键（§9 阶段三）：讨论流的 L3 审批卡也归到发起会话。
                    convId = convId,
                ) { eventType, _, summary, _ ->
                    if (violated == null && eventType == "tool.started" && !CognitiveFlow.allowsTool(summary)) {
                        violated = summary?.takeIf { it.isNotBlank() } ?: "(unnamed tool)"
                    }
                }
                turns++
                if (violated != null) break@outer
            }
        }

        val breach = violated
        if (breach != null) {
            RelayLog.info(
                READ_ONLY_LOG_TAG,
                "violation groupId=" + groupId + " tool=" + breach + " after=" + turns + " turns",
            )
            val json = ticketCardJson(
                ticketId = "discussion-" + System.currentTimeMillis().toString(36),
                rev = 1,
                state = "failed",
                title = title,
                banner = "Read-only discussion aborted: a write tool (" + breach + ") was attempted",
                percent = 0,
                roleName = roleNameOf(targets.first()),
            )
            scope.launch { publishCardJson(groupId, json, targets.first()) }
            return TicketOutcome("failed", null, "read-only violation: " + breach, json)
        }

        // 轮次耗尽：宿主收口，**不再互相唤醒**（TURN_EXHAUSTED 是 NO_WAKE 的一种）。
        // 与 `NO_WAKE` 用同一个 tag：排障脚本按 tag 抓「该叫没叫 / 叫完不收口」两类问题。
        RelayLog.info(
            RelayController.NO_WAKE_LOG_TAG,
            "TURN_EXHAUSTED groupId=" + groupId + " turns=" + turns + " agents=" + targets.joinToString(","),
        )

        val goal = discussionGoal(prompt)
        val cardId = "discussion-" + System.currentTimeMillis().toString(36)
        // 卡面先把**推荐团队**亮出来（模板优先）：点击时还会重算一次，但人得先看得见
        // 「谁来干、谁审」才谈得上点头。
        val plan = parseDelegation(DISCUSSION_GOAL_PREFIX + goal, available, groupId)
        val worker = plan?.workerStage?.agentKey ?: targets.first()
        val json = publishTicketCard(
            groupId = groupId,
            ticketId = cardId,
            rev = 1,
            state = "proposed",
            title = goal,
            banner = "Read-only discussion done (" + turns + " rounds) — start to hand off",
            percent = 0,
            roleName = plan?.workerStage?.role ?: roleNameOf(worker),
            agentId = worker,
            stageRows = plan?.stages?.map { it.role to "pending" },
            assignees = plan?.stages?.map { it.agentKey }.orEmpty(),
        )
        if (json != null) {
            proposals[cardId] = Proposal(cardId, convId, groupId, goal, targets, wsId)
            trimProposals()
        }
        return TicketOutcome("done", "discussion done: " + turns + " rounds with " + targets.joinToString(","), null, json)
    }

    /** 提议表上限：讨论是高频动作，不封顶就是一个只会长的内存表。 */
    private fun trimProposals() {
        while (proposals.size > MAX_PROPOSALS) {
            val oldest = proposals.keys.firstOrNull() ?: return
            proposals.remove(oldest)
        }
    }

    /**
     * 讨论里该唤醒谁：**用户点名的人**优先，没点名就用默认干活棒答一轮。
     *
     * 不点名也答（而不是静默吞掉一次提问）：群里「@ 了却没人应」正是本仓反复出现的
     * 那类投诉；但多厂商对账只在点名时才成立 —— 单人答就是单人视角，卡面如实写轮数。
     */
    private fun discussionTargets(
        prompt: String,
        agentKey: String?,
        available: Set<String>,
    ): List<String> {
        val explicit = agentKey?.let { AgentProfiles.normalizeKey(it) }?.takeIf { it in available }
        val mentioned = RelayController.mentionedAgentKeys(prompt, available)
        val ordered = buildList {
            explicit?.let { add(it) }
            mentioned.forEach { key -> if (key !in this) add(key) }
        }
        return ordered.ifEmpty {
            AgentProfiles.defaultWorkerKey(available)?.let { listOf(it) }.orEmpty()
        }
    }

    /**
     * 只读档的工作目录：群绑定的那个目录即可 —— **不要求是 git 仓**（讨论不需要版本库），
     * 更**绝不**建 worktree。判据与 [resolveWorkspacePath] 的两条来源一致，只是去掉了
     * `isGitRepo` 这一刀：把「不是 git 仓」当成「不能讨论」，等于让纯文档目录永远无法
     * 参与研讨。
     */
    private fun discussionWorkdir(groupId: String?): String? {
        if (groupId == null) return null
        val state = controller.state.value
        val declared = state.workspaceOfGroup(groupId)
        val legacy = state.agentWorkspaces.values.firstOrNull { it.wsId == groupId }
        return (declared ?: legacy)?.wsPath?.takeIf { it.isNotBlank() }
    }

    /** 提议卡的目标：用户原话首行，≤120 字（与 `DelegationParser` 的 goal 截断同口径）。 */
    private fun discussionGoal(prompt: String): String {
        val first = prompt.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            ?: prompt.trim()
        return first.take(120)
    }

    private fun qaWorkdir(wsId: String?, patchRef: String?, groupId: String?): String? {
        val resolved = resolveWorkspacePath(groupId, wsId)
        if (resolved != null) return resolved
        if (patchRef != null) {
            val file = File(patchRef)
            if (file.exists() && file.parentFile != null) {
                return file.parentFile.absolutePath
            }
        }
        return null
    }

    private suspend fun handleCardFollowUpQa(
        convId: String,
        groupId: String?,
        replyToId: String,
        prompt: String,
        wsId: String?,
        agentKey: String?,
    ): Boolean {
        // replyTo 是被引用**消息**的信封 id，不是卡片 id；必须先解出 cardId 再查账簿。
        val quoted = controller.state.value.messages[convId]?.firstOrNull { it.id == replyToId }
            ?: controller.findMessageById(replyToId)
        val cardId = (quoted?.cardPayload ?: quoted?.card)
            ?.get("cardId")
            ?.let { (it as? JsonPrimitive)?.content }
            ?: replyToId
        val patchRef = patchRefForCard(cardId)?.patchPath ?: TicketPatchLedger.lookup(cardId)?.patchPath
        if (patchRef == null && !activeTickets.containsKey(cardId.removePrefix("chain-"))) return false
        val workdir = qaWorkdir(wsId, patchRef, groupId) ?: return false

        val patchContent = if (patchRef != null) {
            val f = File(patchRef)
            if (f.exists()) f.readText() else null
        } else null

        val augmentedPrompt = if (patchContent != null) {
            "User follow-up question regarding card " + cardId + ":\n" + prompt +
                "\n\nContext patch:\n" + patchContent.take(6000)
        } else {
            "User follow-up question regarding card " + cardId + ":\n" + prompt +
                "\n\nContext notice: The patch artifact is missing or has been cleaned up. Please answer honestly without inventing fake code diffs."
        }

        val readOnly = CognitiveFlow.readOnlyPrompt(augmentedPrompt)
        // 不变量 1（不猜底座）：帧没带键、活跃工单也没有记录 ⇒ 拒绝这次只读问答并留痕，
        // 绝不回落默认驱动。旧写法 `?: "codex"` 会把任何来历不明的卡片都派给 codex。
        val targetDriver = agentKey ?: activeTickets[cardId.removePrefix("chain-")]?.agentKey
        if (targetDriver == null) {
            RelayLog.info("RELAY-QA", "no driver for Q&A card=" + cardId + " — refusing to guess a default")
            return false
        }
        val host = planeProvider() ?: return false
        val said = StringBuilder()
        var violated: String? = null
        host.executeCliTask(
            agentKey = targetDriver,
            prompt = readOnly,
            wsPath = workdir,
            requestedSessionId = null,
            sessionTitle = "Q&A: " + prompt.take(30),
            convId = convId,
            // 只读问答必须挂宿主侧工具白名单（执行前拦），prompt 只是请求。
            toolPolicy = { tool -> CognitiveFlow.allowsTool(tool) },
        ) { eventType, _, summary, _ ->
            if (eventType == "token.delta" && summary != null) {
                said.append(summary)
            }
            if (violated == null && eventType == "tool.started" && !CognitiveFlow.allowsTool(summary)) {
                violated = summary?.takeIf { it.isNotBlank() } ?: "(unnamed tool)"
            }
        }
        if (violated != null) {
            val breach = "Read-only Q&A aborted: attempted to use a write tool: " + violated
            if (groupId != null) {
                controller.broadcastAgentSpeech(groupId, targetDriver, breach, replyToId)
            } else {
                controller.sendDirectAgentSpeech(convId, targetDriver, breach, replyToId)
            }
            return true
        }
        val answer = said.toString().trim()
        if (answer.isNotBlank()) {
            if (groupId != null) {
                controller.broadcastAgentSpeech(groupId, targetDriver, answer, replyToId)
            } else {
                controller.sendDirectAgentSpeech(convId, targetDriver, answer, replyToId)
            }
        }
        return true
    }

    private suspend fun handleCardFollowUpQaAwait(
        convId: String,
        groupId: String?,
        replyToId: String,
        prompt: String,
        wsId: String?,
        agentKey: String?,
    ): TicketOutcome? {
        val handled = handleCardFollowUpQa(convId, groupId, replyToId, prompt, wsId, agentKey)
        return if (handled) {
            TicketOutcome("done", "Card Q&A completed", null, null)
        } else {
            null
        }
    }

    private fun steerActiveTicket(active: ActiveTicket, instruction: String): Boolean {
        if (active.stage != "working") return false
        val originalPrompt = active.devPrompt ?: active.title
        if (!abortByCard("chain-" + active.ticketId, "Steered by user request")) return false
        val combinedPrompt = "User steering instruction (the previous run was stopped and is being restarted): " +
            instruction + "\n\nOriginal task: " + originalPrompt
        scope.launch {
            dispatchSingleWorker(
                convId = active.convId,
                groupId = active.groupId,
                prompt = combinedPrompt,
                agentKey = active.agentKey,
                wsId = active.wsId,
            )
        }
        return true
    }

    /**
     * 提议卡上的「开工」：把讨论收口的目标**重新**过一遍模板与代码门。
     *
     * 为什么点击时重算、而不是发布时把计划存下来：`delegation-teams.json` 是入口 B
     * 可手改的文件，几分钟里可能被改过；点击该按**当下**的配置派人。何况代码门
     * （在位 / 异厂商 / 冷却）本来就只能在派单那一刻裁决。
     */
    private fun startProposalByCard(cardId: String): Boolean {
        val proposal = proposals.remove(cardId) ?: return false
        val available = availableAgentKeys() ?: return false
        val plan = parseDelegation(DISCUSSION_GOAL_PREFIX + proposal.goal, available, proposal.groupId)
            ?: return false
        scope.launch { delegateByPlan(proposal.convId, proposal.groupId, plan, available, proposal.wsId) }
        RelayLog.info(
            "RELAY-INVOKE",
            "proposal started from discussion agents=" + proposal.agents.joinToString(",") + " card=" + cardId,
        )
        return true
    }

    /** 提议卡上的「取消」：撤掉提案，群里补一张已取消卡（不调用任何进程）。 */
    private fun cancelProposalByCard(cardId: String, reason: String): Boolean {
        val proposal = proposals.remove(cardId) ?: return false
        // 中止卡只是展示（不派单），但发送者标签也不能瞎猜成一个驱动 —— 解析不出就用
        // 中性 kind（与 `effectiveToken` 的回落同形），不冒充「这是 codex 的卡」。
        val agent = proposal.agents.firstOrNull() ?: "agent"
        val json = ticketCardJson(
            ticketId = cardId,
            rev = 2,
            state = "aborted",
            title = proposal.goal,
            banner = reason,
            percent = 0,
            roleName = roleNameOf(agent),
        )
        scope.launch { publishCardJson(proposal.groupId, json, agent) }
        return true
    }

    /**
     * 多棒责任链：每一棒按 `stage.agentKey` 换 agent（[com.kapp.tenon.agent.StageChainRunner]
     * 的 `runOneStage` 只认那一格），所以「谁干活」由派单文本里每棒的括号决定。
     *
     * @return true = 已交给链执行器（后台跑）；false = 这台宿主没有链执行器或群没绑仓，
     *         调用方**必须**回落到单棒，不能把一次派单静默吞掉。
     */
    private fun dispatchMultiRoleChain(
        convId: String,
        groupId: String?,
        prompt: String,
        stages: List<ChainStage>,
        target: String? = null,
        module: String? = null,
        context: String? = null,
        wsId: String? = null,
        /** 主理人上下文：非 null = 这条链卡住时由他接手决定下一步。 */
        leaderBrief: LeaderBrief? = null,
        /** 形状来源（飞行记录）：团队派单带下来，其它入口 null。 */
        planTrace: com.kapp.tenon.agent.ChainPlanTrace? = null,
    ): Boolean {
        val host = planeProvider() ?: return false
        val repo = resolveWorkspacePath(groupId, wsId) ?: return false
        val chainDispatcher = host.chainDispatcher ?: return false
        scope.launch {
            chainDispatcher.start(
                groupId = groupId,
                repoPath = repo,
                title = prompt.take(60),
                stages = stages,
                target = target,
                module = module,
                context = context,
                leader = leaderBrief,
                planTrace = planTrace,
            )
        }
        return true
    }

    /**
     * 把「团队 + 席位」翻译成主理人的决策上下文。
     *
     * 三样东西各有出处，不许互相顶替：`leaderAgentId` 说**是谁**（执行档主键），
     * 席位名册说**叫什么、什么岗位**（展示事实），执行档说**用什么底座**（执行配置）。
     * lead 自己在执行档里就没配过 ⇒ null，整条链回落到纯规约 —— 不假装有人主事。
     */
    private fun leaderBriefOf(
        seat: VirtualAgentMember,
        profile: TeamExecutionProfile,
        goal: String,
    ): LeaderBrief? {
        val leaderId = profile.leaderAgentId.trim().lowercase()
        if (leaderId.isEmpty()) return null
        val leaderSpec = profile.members.firstOrNull { it.agentId.trim().lowercase() == leaderId }
        val members = profile.members.map { spec ->
            val declared = seat.teamMembers.firstOrNull {
                it.agentId.trim().lowercase() == spec.agentId.trim().lowercase()
            }
            LeaderMember(
                agentId = spec.agentId.trim().lowercase(),
                name = declared?.name?.ifBlank { null } ?: spec.agentId,
                role = declared?.role?.ifBlank { null }
                    ?: if (spec.isWorker) TeamExecutionKinds.WORKER else TeamExecutionKinds.REVIEWER,
                isWorker = spec.isWorker,
                driverKey = spec.driverKey,
                model = spec.models.firstOrNull(),
            )
        }
        if (members.isEmpty()) return null
        val leaderDeclared = seat.teamMembers.firstOrNull {
            it.agentId.trim().lowercase() == leaderId
        }
        return LeaderBrief(
            leaderAgentId = leaderId,
            leaderName = leaderDeclared?.name?.ifBlank { null } ?: leaderId,
            leaderPrompt = leaderSpec?.customPrompt?.trim()?.takeIf { it.isNotEmpty() },
            teamName = seat.teamName?.takeIf { it.isNotBlank() } ?: seat.name,
            goal = goal,
            members = members,
        )
    }

    /**
     * 本机在位的 agent 档案键（已归一）。控制面不在 → null：调用方跳过委派解析，
     * 走既有路径 —— 门不能因为自己拿不到事实而把单拦死。
     *
     * `DesktopExtension` 的 invoke 桥也用这一份把群里点名的席位 ID 归位到档案键：
     * 「哪些驱动在位」不许在装配层再问一遍，否则两处名册迟早分叉。
     */
    internal fun availableAgentKeys(): Set<String>? {
        availableDriversHint?.let { return it() }
        val host = planeProvider() ?: return null
        return AgentProfiles.knownKeys().filter { host.resolveCliDriver(it) != null }.toSet()
    }

    /**
     * 健康名册（G1，真机复验过）：在位 且 最近一次会话不是 error。
     * 只用于**自动选棒**（挡位 B 的启发式 / planner 名册）—— 显式点名不受它管
     * （用户指名就要派，健康问题由链失败即停兜底）；全员不健康 → 退回全在位集合
     * （回避不能变成禁用）。
     */
    private fun healthyAgentKeys(available: Set<String>): Set<String> {
        val host = planeProvider() ?: return available
        val healthy = available.filterTo(mutableSetOf()) { key ->
            // 内存台账优先（本进程内的新鲜事实）；查不到再回落持久化账本（上一进程的错误史）。
            val driverId = host.resolveCliDriver(key)?.driverId
            val recent = host.recentCliSessionState(key) ?: driverId?.let { healthLedger?.lastState(it) }
            recent != "error"
        }
        return healthy.ifEmpty { available }
    }

    /** 盲区巡检记录（只记录，永不抛）：终局事实按月落 JSONL，巡检每小时聚合一窗。 */
    fun recordBlindSpot(kind: String, title: String?, detail: String?) {
        val dir = blindSpotDir ?: return
        BlindSpotPatrol.record(dir, BlindSpotPatrol.Entry(System.currentTimeMillis(), kind, title, detail))
    }

    /**
     * §16.4 单源解析：两条派单入口（文本路由 / invoke 桥）都必须走这里，防分叉。
     * 群模板按 groupId 查（私聊 groupId=null 自然无模板）；没有装配 teamStore 时
     * 行为与从前逐字一致。
     */
    private fun parseDelegation(prompt: String, available: Set<String>, groupId: String?): DelegationPlan? =
        DelegationParser.parse(
            prompt, available, healthyAgentKeys(available),
            team = groupId?.let { teamStore?.teamFor(it) },
        )

    /** 挡位 B 的 LLM 拆解：成功则替换启发式计划，任何失败原样回落（planner 是增强不是依赖）。 */
    private suspend fun refinedPlan(plan: DelegationPlan, available: Set<String>): DelegationPlan {
        if (plan.source != DelegationPlan.Source.AUTO) return plan
        // §15 第 9 条：命中群模板 = 人已显式选定团队 ⇒ 短路跳过 PlannerLlm
        //（省 Token 与 20s 延迟，避开 planner 异厂商提示词缺陷）。
        // DelegationTemplateWiringGuardTest 钉住这一行不许被注释或回溯（反向验证已红过一次）。
        // leader 动态编排：先让 LLM 重排一次（reviewer 顺序 + 各棒指令按需求定制），
        // 门校验不过 / LLM 不可用 ⇒ ?: 右侧原样回落编译固定链 —— 编译链是保底。
        if (plan.fromTemplate) return teamPlanRefiner?.invoke(plan) ?: plan
        val detailedPlanner = detailedAutoPlanner
        if (detailedPlanner != null) {
            val detailed = runCatching { detailedPlanner(plan.goal, healthyAgentKeys(available)) }.getOrNull()
            if (detailed != null) {
                return DelegationPlan(
                    goal = plan.goal,
                    stages = detailed.stages,
                    source = DelegationPlan.Source.AUTO,
                    target = detailed.target,
                    module = detailed.module,
                    context = detailed.context,
                )
            }
        }
        val planner = autoPlanner ?: return plan
        // planner 名册只给健康 agent（G1）：二进制在位 ≠ 健康，真机已踩过。
        val stages = runCatching { planner(plan.goal, healthyAgentKeys(available)) }.getOrNull() ?: return plan
        return DelegationPlan(plan.goal, stages, DelegationPlan.Source.AUTO)
    }

    /**
     * **团队执行解析**（§4.3）：token → (席位, 组员, 执行档)。只有组员 token 才产生
     * 非 None 目标；领队 / 宿主自报 kind / 未知 token 恒为 None（既有路径接管）。
     * 执行档读取失败 / 过期 / 席位没绑工作区 ⇒ NotConfigured（blocked 卡），**绝不**
     * 返回 None 让调用方落进 codex 兜底。
     */
    private fun resolveTeamTarget(
        groupId: String,
        mentionedAgentKey: String?,
    ): TeamExecutionResolver.Target {
        val st = controller.state.value
        val group = groupId.let { id -> st.settings.groups.firstOrNull { it.groupId == id } }
        val store = teamExecutionStore
        if (store == null) {
            // 没有团队执行面时**只有团队名**要在这里拦：组员 token 的既有行为是 None
            // （交给既有单 agent 路径），而团队名在那边解析不出任何席位 ——
            // 交给它只会变成「用默认驱动跑一棒」，必须出 blocked 卡。
            val isTeamName = group?.teamSeatMatchingName(mentionedAgentKey.orEmpty()) is TeamNameMention.Hit
            return if (isTeamName) {
                TeamExecutionResolver.Target.NotConfigured(
                    null,
                    DispatchBlockedCode.NO_DRIVER,
                    "team dispatch is not available on this host",
                )
            } else {
                TeamExecutionResolver.Target.None
            }
        }
        val target = TeamExecutionResolver(store, groupDirIdOf).resolve(
            groupId = groupId,
            mentionedKey = mentionedAgentKey,
            group = group,
            selfNodeId = st.selfNodeId,
            teamProfiles = st.settings.teamProfiles,
        )
        // **派单期按目录建执行档**（方案 §3.4 P1b-4）：档案在、席位在、目录在、唯独没有
        // 执行档 ⇒ 用档案名册就地建出初值（第一名 worker、其余 reviewer），复用成员的
        // driverKey/model 从员工档案读、人设从 `TeamMemberInfo.prompt` 浇进 `customPrompt`。
        // 初值**只在 driverKey 全部明确时落盘**（validate 拒绝空键，而且「不猜底座」不许
        // 造半份档）—— 有空键就如实 blocked（NO_DRIVER，去群编制指派），不猜。
        if (target is TeamExecutionResolver.Target.NotConfigured &&
            target.code == DispatchBlockedCode.NO_DRIVER &&
            target.seat?.teamId != null
        ) {
            bootstrapTeamExecutionProfile(groupId, target.seat!!, store)?.let {
                return TeamExecutionResolver(store, groupDirIdOf).resolve(
                    groupId = groupId,
                    mentionedKey = mentionedAgentKey,
                    group = group,
                    selfNodeId = st.selfNodeId,
                    teamProfiles = st.settings.teamProfiles,
                )
            }
        }
        return target
    }

    /**
     * 执行档**初值**落盘；null = 没建成（成员底座不齐 / 校验拒绝）—— 调用方保持 blocked，
     * 不静默放行一个派不出去的档。
     */
    private fun bootstrapTeamExecutionProfile(
        groupId: String,
        seat: com.kapp.tenon.core.protocol.VirtualAgentMember,
        store: TeamExecutionStore,
    ): Boolean {
        val st = controller.state.value
        val team = st.settings.teamProfiles.byTeamId(seat.teamId) ?: return false
        val dirId = groupDirIdOf(groupId)?.trim().orEmpty()
        if (dirId.isEmpty()) return false
        if (seat.teamMembers.isEmpty()) return false
        val members = seat.teamMembers.mapIndexed { i, m ->
            // 复用行（agentId = employeeId）读档案的 driverKey/model —— 声明值，不反解；
            // 新建行（m-…）没有档案底座 ⇒ 空 ⇒ 整份不落盘（不猜）。
            val employee = st.settings.employeeProfiles.byEmployeeId(m.agentId)
            val model = employee?.model
                ?.takeIf { it.isNotBlank() && employee.driverKey.isNotBlank() }
                ?.let { if (com.kapp.tenon.agent.drivers.DriverModelFlags.supports(employee.driverKey)) listOf(it) else emptyList() }
                ?: emptyList()
            com.kapp.tenon.domain.TeamExecutionMemberUi(
                agentId = m.agentId,
                // 初值（方案 §3.4 P1b-4）：名册第一名 = worker，其余 reviewer。
                kind = if (i == 0) {
                    com.kapp.tenon.domain.TeamExecutionKindsUi.WORKER
                } else {
                    com.kapp.tenon.domain.TeamExecutionKindsUi.REVIEWER
                },
                driverKey = employee?.driverKey.orEmpty(),
                models = model,
                // 人设属于团队名册的一行（§2②）：派单期浇进执行档 customPrompt 初值。
                customPrompt = m.prompt?.takeIf { it.isNotBlank() },
            )
        }
        if (members.any { it.driverKey.isBlank() }) return false
        val verdict = store.save(
            TeamExecutionProfile(
                dirId = dirId,
                leaderAgentId = seat.agentId,
                updatedAt = 0,
                members = members.map {
                    TeamExecutionMemberSpec(
                        agentId = it.agentId,
                        kind = it.kind,
                        driverKey = it.driverKey,
                        models = it.models,
                        customPrompt = it.customPrompt,
                    )
                },
            ),
            declaredMemberIds = seat.teamMembers.map { it.agentId.trim() }.toSet(),
        )
        return verdict is TeamExecutionSaveVerdict.Saved
    }

    /**
     * **单兵席位的底座覆盖**（不变量 5）：单兵席位（`entityType=single`，没有
     * `teamMembers`）不进团队编译，但它的底座同样该能在执行配置里换。
     *
     * 只对一个明确条件生效：**传进来的键不是档案键**（`AgentProfiles.normalizeKey` 为 null）
     * 而能在群里匹配到本机席位 —— 也就是「按席位 slug 派单」那条路。团队派单传的是
     * 编译后的 `driverKey`（本来就是档案键），所以不会被这里二次改写。
     *
     * 返回 (driverKey, model)；null = 没有覆盖（没配过 / 未指派 / 不是本机席位 / 群没绑目录）——
     * 一律回落既有解析，**绝不猜**一个底座出来。
     */
    private fun seatExecutionOverride(groupId: String?, seatKey: String): Pair<String, String?>? {
        val store = teamExecutionStore ?: return null
        val st = controller.state.value
        val group = st.settings.groups.firstOrNull { it.groupId == groupId } ?: return null
        val seat = group.seatsMatchingToken(seatKey).singleOrNull() ?: return null
        if (seat.hostNodeId != st.selfNodeId) return null
        val dirId = groupDirIdOf(group.groupId)?.trim().orEmpty()
        if (dirId.isEmpty()) return null
        val profile = store.profileFor(dirId, seat.agentId) ?: return null
        // 空 driverKey = 用户还没指派：保持既有解析（按席位 slug 走），不替人做决定。
        val workers = profile.workers
        if (workers.isEmpty() || workers.any { it.driverKey.isBlank() }) return null
        // **点将之后"这个席位用什么底座"不再唯一**：编制里可能有 N 位能干活的人，
        // 而按席位 slug 派单这条路不知道这一单点了谁。全部一致（同底座同模型）时没有
        // 歧义，照旧覆盖；只要有一个不一致就返回 null —— 猜一个底座等于替主理人点将。
        return workers.map { worker ->
            (AgentProfiles.normalizeKey(worker.driverKey) ?: return null) to
                worker.models.firstOrNull()?.takeIf { it.isNotBlank() }
        }.distinct().singleOrNull()
    }

    /**
     * **员工档案的派单配置**（A5，方案 §2②）：席位 → `employeeId` → 档案 `driverKey`+`model`。
     *
     * 优先级在调用方排：执行档（[seatExecutionOverride]，按目录、按队伍，更具体）> 这里
     * （员工档案的声明值）> 拒绝。null = 席位没带员工引用、或档案没配 `driverKey`
     * （老数据）。卡面上的**承办人名**解析在 [TicketActorNaming]（纯函数，测试直喂）；
     * 这里只答「用什么引擎」—— 两个问题两个来源，别合并。
     */
    private fun employeeDriverConfig(groupId: String?, seatKey: String): Pair<String, String?>? {
        val st = controller.state.value
        val group = st.settings.groups.firstOrNull { it.groupId == groupId } ?: return null
        val seat = group.seatsMatchingToken(seatKey).singleOrNull() ?: return null
        if (seat.hostNodeId != st.selfNodeId) return null
        val profile = st.settings.employeeProfiles.byEmployeeId(seat.employeeId) ?: return null
        if (profile.driverKey.isBlank()) return null
        return profile.driverKey to profile.model
    }

    /** 本群全部可被 @ 的 token（候选与匹配同源的那一份；组员名已由 P0-0.1 进池）。 */
    private fun agentKindsOf(groupId: String?): Set<String> {
        if (groupId == null) return emptySet()
        val st = controller.state.value
        val ctx = st.senderContext(ConvId.group(groupId))
        return groupParticipantRows(ctx).mentionKeys
    }

    /**
     * **团队派单执行**（文本路由，§4.3）：授权 → 编译 → 分发/出卡。
     * 返回 true = 本分支已完全处理（调用方 return）；false = 解析后无目标（理论不可达，
     * 防御分支）。
     */
    private suspend fun runTeamDispatch(
        convId: String,
        groupId: String,
        senderPeerId: String,
        target: TeamExecutionResolver.Target.Hit,
        prompt: String,
        /**
         * 卡面里怎么称呼这个目标：`@团队名` 传团队名、`@组员` 传组员名。
         * 拒绝文案要报用户打过的那个词，不报内部 token。
         */
        targetLabel: String,
    ): Boolean {
        val st = controller.state.value
        val group = st.settings.groups.firstOrNull { it.groupId == groupId } ?: return false
        val profile = target.profile
        val seatMembers = target.seat.teamMembers
        val available = availableAgentKeys()
        // prompt 在域层已经剥净 @（onAgentPrompt 的契约），直接当 goal 用。
        val goal = prompt
        val brief = rosterBrief?.invoke()
        // **逐成员授权（§4.4，mulica 式员工可见性）**：判据与意图路径同一份
        // （[AgentExecAuthorizer]）。两次判，各自有明确职责：
        //
        // 1. **锚点先判**（下面这几行）：被点名的人自己没授权 ⇒ 立即拒。放在编译前有
        //    三个理由 —— 失败快；不给主理人一次拿别人顶上的机会（点谁就该谁能跑）；
        //    也不让人靠一个必然被拒的派单去试探本机装了哪些底座。
        // 2. **编译后逐人再判**：主理人一旦有换人权，执行人就不再是锚点。谁真的会被
        //    调起就判谁，否则就是「拿 A 的授权跑 B」（见 [TeamDispatchAuthorization]）。
        fun verdictFor(agentId: String) = AgentExecAuthorizer.authorize(
            senderId = senderPeerId,
            group = group,
            grantAllows = { node -> controller.invokeGrantAllows?.invoke(agentId, node) == true },
            policy = controller.invokeExecPolicyOf?.invoke(agentId),
            selfNodeId = st.selfNodeId,
        )
        val anchorVerdict = verdictFor(target.member.agentId)
        // **点将改变了「锚点没授权」的含义**：`@团队名` / `@主理人` 的锚点只是编制首位，
        // 不是用户点的那个人 —— 他没授权不等于整队没人能干活。判据分档住
        // [TeamDispatchAuthorization.entryPermits]（纯函数，两档语义各一条用例），
        // 两档都排在编译之前：全队都不可调起时不许先拿到宿主侧错误（试探底座）。
        val permitted = TeamDispatchAuthorization.entryPermits(
            entry = target.entry,
            anchorVerdict = anchorVerdict,
            workers = profile.workers.map { it.agentId },
            verdictOf = ::verdictFor,
        )
        if (!permitted) {
            publishUnauthorizedDispatch(
                groupId, prompt, senderPeerId,
                TeamDispatchAuthorization.Denial(target.member.agentId, anchorVerdict),
                who = targetLabel,
            )
            return true
        }
        // **审核面裁决**（L1）只在「整队接活 + 命中 worker」时问：
        // - `@组员` = 单兵，链形状根本不由这一单决定，问了只是白花 20s；
        // - 命中 reviewer 是对当前工作区的纯审查任务，也没有可裁决的形状。
        val hitIsWorker = profile.members.firstOrNull {
            it.agentId.trim().lowercase() == target.member.agentId.trim().lowercase()
        }?.isWorker == true
        val asksPlanner = hitIsWorker && target.entry != DispatchEntry.MEMBER
        val suggestion = if (asksPlanner) {
            decideChainShape(profile, seatMembers, target.member, goal, available)
        } else {
            null
        }
        var compiled = TeamPlanCompiler.compile(
            profile, seatMembers, target.member, goal, available,
            // 只在真装配了档案面时追加：否则 agent 会照着一份空说明书去调不存在的动作。
            capabilityBrief = brief,
            suggestion = suggestion,
            entry = target.entry,
        )
        // Planner 的选择若被**既有硬门**否掉（同厂商 / 冷却 / 不在位），回落编译固定链
        // 再裁决一次 —— planner 的坏选择不该变成用户的一次失败。这里只**复用**那道门，
        // 不复制它的任何判据（同一判据两处实现会各自腐坏）。
        if (suggestion != null && compiled is TeamPlanCompiler.Compiled.Plan) {
            val verdict = StageChainGate.enforce(
                compiled.plan,
                available ?: emptySet(),
                cooldownRemainingMs = DriverCooldownTracker.Shared::remainingMs,
            )
            if (verdict !is StageChainGate.Decision.Pass) {
                compiled = TeamPlanCompiler.compile(
                    profile, seatMembers, target.member, goal, available,
                    capabilityBrief = brief,
                )
            }
        }
        // **逐人授权，按编译结果判**（见 [TeamDispatchAuthorization]）：锚点已经过；
        // 这里守的是「主理人换人之后新出现的人」。整份拒绝时不问任何人 —— 一次只暴露
        // 一个阻塞点，用户批完再来一次就能推进到下一个。
        val denial = TeamDispatchAuthorization.firstDenied(compiled.participants) { verdictFor(it) }
        if (denial != null) {
            val isAnchor = denial.agentId.trim().equals(target.member.agentId.trim(), ignoreCase = true)
            val who = if (isAnchor) {
                targetLabel
            } else {
                val name = seatMembers.firstOrNull {
                    it.agentId.trim().equals(denial.agentId.trim(), ignoreCase = true)
                }?.name?.ifBlank { null }
                "delegated member " + (name ?: denial.agentId) + " of " + targetLabel
            }
            publishUnauthorizedDispatch(groupId, prompt, senderPeerId, denial, who = who)
            return true
        }
        // 单兵派单**不写** planDecision：它没有「团队形状决策」这回事。写进去会让
        // 卡面按「0 审查棒」给它打上「未经独立审查」——用户点一个人干活，被提示
        // 「这队没审查」，那是另一个意思。
        val decision = if (target.entry == DispatchEntry.MEMBER) null else {
            planDecisionOf(
                compiled, suggestion,
                askedPlanner = asksPlanner,
                seatMembers = seatMembers,
                anchorAgentId = target.member.agentId,
            )
        }
        RelayLog.info(
            "PLAN",
            "entry=" + target.entry.name.lowercase() +
                // 链首那一棒的人：点将之后它与 `@` 锚点可能不是同一个人，日志不写它
                // 就查不出「为什么跑的是 B 不是 A」。
                " worker=" + (compiled.participants.firstOrNull() ?: "-") +
                " source=" + (decision?.source ?: "none") +
                " stages=" + (decision?.stageCount ?: 0) +
                " reviewers=" + (decision?.reviewerCount ?: 0) +
                " group=" + groupId,
        )
        return when (val outcome = compiled) {
            is TeamPlanCompiler.Compiled.Rejected -> {
                publishTeamDispatchBlocked(groupId, prompt, outcome.reason, code = outcome.code)
                true
            }
            is TeamPlanCompiler.Compiled.Single -> {
                // 单棒（无 reviewer，或 reviewer 直呼）：§5.3 不经过 StageChainGate。
                if (available == null) {
                    publishTeamDispatchBlocked(
                        groupId,
                        prompt,
                        "control plane unavailable",
                        code = DispatchBlockedCode.NO_DRIVER,
                    )
                    return true
                }
                dispatchSingleWorker(
                    convId, groupId, outcome.instruction, outcome.agentKey,
                    wsId = target.seat.defaultWsId,
                    model = outcome.model,
                    planDecision = decision,
                    // **卡面承办人是人**：与团队链卡同一份 seatMembers 名字来源
                    // （compilePlanned 的 assignee 也取它）—— 单棒不能只有引擎名。
                    assigneeName = seatMembers.firstOrNull {
                        it.agentId.trim().equals(outcome.agentId.trim(), ignoreCase = true)
                    }?.name?.trim()?.takeIf { it.isNotEmpty() },
                    // **单兵卡的第二颗按钮：这活该找谁审。**
                    //
                    // 名册简报（`rosterBrief`）早就告诉过那位组员该找谁，但那是给它看的；
                    // 卡面前的人只能自己去编制里找谁管审。只在这一档给：链（`Compiled.Plan`）
                    // 的审查棒本来就在链上，纯审查单棒（`reviewOnly`）问「谁来审审查员」更是荒谬。
                    reviewBy = if (outcome.reviewOnly) {
                        null
                    } else {
                        CardReviewAsk.pickReviewTarget(
                            profile = profile,
                            declared = seatMembers,
                            available = available,
                            excludeAgentId = outcome.agentId,
                        )
                    },
                )
                true
            }
            is TeamPlanCompiler.Compiled.Plan -> {
                // **workspace 显式取席位默认工作区**（§4.3）：不用 resolveWorkspacePath 的
                // 群级推导 —— 同群多席位时会在错误的仓里跑。
                val dispatched = delegateByPlan(
                    convId, groupId, outcome.plan, available ?: emptySet(),
                    wsId = target.seat.defaultWsId,
                    // 采纳了 Planner 就不再让 refiner 把同一批 instruction 改写一遍。
                    refine = !outcome.plannedByLlm,
                    // 主理人在场：链中途卡住时由他决定下一步（常规流转仍走规约）。
                    leaderBrief = leaderBriefOf(target.seat, profile, goal),
                    // 链路径的飞行记录靠它落 `planDecision`（单棒那条自己带）。
                    planTrace = decision?.toChainTrace(),
                )
                if (!dispatched) {
                    // §5.5：派不出去也要可见 —— delegateByPlan 的 Boolean 原先会被丢掉。
                    publishTeamDispatchBlocked(
                        groupId,
                        prompt,
                        "chain executor unavailable or no workspace bound",
                        code = DispatchBlockedCode.NO_DIRECTORY,
                    )
                }
                true
            }
        }
    }

    /**
     * 问一次审核面裁决（[TeamPlannerLlm] / 注入的 [teamPlannerChain]）。
     *
     * 只把**在位且未冷却**的成员列进候选池：不可用的成员进池只会换来一次无效往返，
     * 而它的建议一旦点名不可用的人，还得整份作废回落编译链。
     */
    private suspend fun decideChainShape(
        profile: TeamExecutionProfile,
        seatMembers: List<TeamMemberInfo>,
        hitMember: TeamMemberInfo,
        goal: String,
        available: Set<String>?,
    ): OrchestrationSuggestion? {
        val planner = teamPlannerChain ?: return null
        val usable = available ?: return null
        val declared = seatMembers.associateBy { it.agentId.trim().lowercase() }
        val pool = profile.members
            .filter { it.driverKey in usable }
            .map { spec ->
                val seat = declared[spec.agentId.trim().lowercase()]
                TeamPlannerLlm.briefOf(spec, seat?.name.orEmpty(), seat?.role.orEmpty())
            }
        if (pool.isEmpty()) return null
        val input = PlannerChainInput(
            goal = goal,
            // 默认落点，不是硬约束：点将权已经在主理人手里，这里只告诉他"用户默认点了谁"。
            defaultWorkerAgentId = hitMember.agentId,
            members = pool,
        )
        return runCatching { planner(input) }.getOrNull()
    }

    /**
     * 形状来源的留痕（进飞行记录 / 日志 / 卡面）。[askedPlanner] = 这次派单本来要不要问
     * Planner —— 不问的（纯审查）与「问了但没有答案」必须在读数上分得开，
     * 否则「没装配 Planner」会被读成「LLM 判定不用审」。
     *
     * **承办人也记在这里**：点将之后链首那一棒未必是用户点的锚点，而卡面只画岗位。
     * 不写这两个字段，「为什么跑的是 B」就只剩日志能答（`RelayLog.PLAN` 也只写到
     * agentId，名字还得靠人记）。
     */
    private fun planDecisionOf(
        compiled: TeamPlanCompiler.Compiled,
        suggestion: OrchestrationSuggestion?,
        askedPlanner: Boolean,
        seatMembers: List<TeamMemberInfo>,
        anchorAgentId: String,
    ): FlightPlanDecision? {
        val stageCount: Int
        val reviewerCount: Int
        val adopted: Boolean
        when (compiled) {
            is TeamPlanCompiler.Compiled.Single -> {
                stageCount = 1
                reviewerCount = if (compiled.reviewOnly) 1 else 0
                adopted = compiled.plannedByLlm
            }
            is TeamPlanCompiler.Compiled.Plan -> {
                stageCount = compiled.plan.stages.size
                reviewerCount = compiled.plan.stages.size - 1
                adopted = compiled.plannedByLlm
            }
            is TeamPlanCompiler.Compiled.Rejected -> return null
        }
        val source = when {
            adopted -> FlightPlanDecision.SOURCE_LLM
            suggestion != null -> FlightPlanDecision.SOURCE_FALLBACK_GATE
            askedPlanner -> FlightPlanDecision.SOURCE_FALLBACK_UNAVAILABLE
            else -> FlightPlanDecision.SOURCE_COMPILED
        }
        val workerAgentId = compiled.participants.firstOrNull()
        // 显示名取群声明（人叫什么是名册的事实）；取不到才回落 agentId —— 卡面写
        // `m-coder` 比写「coder」更不像人，但比留空强。
        val workerName = workerAgentId?.let { id ->
            seatMembers.firstOrNull { it.agentId.trim().equals(id.trim(), ignoreCase = true) }
                ?.name?.ifBlank { null }
        }
        return FlightPlanDecision(
            source = source,
            stageCount = stageCount,
            reviewerCount = reviewerCount,
            rationale = if (adopted) suggestion?.rationale else null,
            workerAgentId = workerAgentId,
            workerName = workerName ?: workerAgentId,
            anchorAgentId = anchorAgentId,
        )
    }

    /**
     * **文本派单授权收口**（P0-0.3）：点名了**本机托管席位**（领队 token / 席位 agentId）
     * 时必须过授权；未授权返回可读原因（调用方出 blocked 卡并止步）。
     * 组员 token / 解析不出席位 / 非群 ⇒ null（既有路径接管 —— 组员的授权在团队分支内做）。
     */
    /** 席位派单被拒：理由 + 授权判据用的那把 agentId（审批回流按它记申请）。 */
    private data class SeatDenial(val agentId: String, val reason: String)

    private fun deniedSeatDispatch(
        groupId: String?,
        mentionedAgentKey: String?,
        senderPeerId: String,
        prompt: String,
    ): SeatDenial? {
        if (groupId == null || mentionedAgentKey.isNullOrBlank()) return null
        val st = controller.state.value
        val group = st.settings.groups.firstOrNull { it.groupId == groupId } ?: return null
        // 团队名（`@开发团队`）不是席位 token：整队的**执行主体**是执行档里的 worker 组员，
        // 不是领队席位。策略授权在 [runTeamDispatch] 里按 `target.member.agentId` 逐成员判，
        // 这里若再按领队 seat 早拒，会出现「领队仅自己可用、但 worker 开放」时外部整队调不动
        // —— 与"每个员工各自决定给谁用"相反。所以团队席位**只做宿主归属判据**，不在这里早拒。
        val teamSeat = (group.teamSeatMatchingName(mentionedAgentKey) as? TeamNameMention.Hit)?.seat
        val seat = group.seatsMatchingToken(mentionedAgentKey).singleOrNull() ?: teamSeat ?: return null
        if (seat.hostNodeId != st.selfNodeId) return null // 不是本机席位：不归本机拦
        if (teamSeat != null) return null // 团队席位：授权下放到逐成员（runTeamDispatch）。
        // 非团队单席位（`@dsh`）：它走 dispatchSingleWorker，没有逐成员授权环节，
        // 所以策略授权必须在这里做（否则单席位 @ 完全无闸）。
        val verdict = AgentExecAuthorizer.authorize(
            senderId = senderPeerId,
            group = group,
            grantAllows = { node -> controller.invokeGrantAllows?.invoke(seat.agentId, node) == true },
            policy = controller.invokeExecPolicyOf?.invoke(seat.agentId),
            selfNodeId = st.selfNodeId,
        )
        if (with(AgentExecAuthorizer) { verdict.permitsExecution() }) return null
        // 卡面全群可见：@ 团队被拒时说的是团队名，不是领队 token —— 用户打的是「开发团队」，
        // 报 `dsh-lead` 会让人以为点错了对象。
        val label = if (teamSeat != null) {
            "team " + (teamSeat.teamName?.trim()?.takeIf { it.isNotEmpty() } ?: teamSeat.name)
        } else {
            "seat " + seat.name
        }
        return SeatDenial(
            agentId = seat.agentId,
            reason = "not authorized for " + label + " (" + verdict + ")",
        )
    }

    /** 团队派单被拒/未配置的**可见出口**：blocked 卡（全群可见），不许静默吞。 */
    /**
     * 授权拒绝的唯一出口（锚点早拒与「编译后新增的人被拒」共用）。
     *
     * 审批回流（与 direct / cross-host invoke 同口径）：被拒不进死胡同 —— 记一条授权
     * 申请（键 = **被拒的那个** agentId），机主在可信设备面板批一次（scope=AGENT），
     * 同一个目标下一次就能过。没有这条回流，「mulica 式逐成员授权」对 AI 建队的 m-*
     * 编制行就是永远打不开的门：UI 没有按 m-* 写策略的入口，被拒后连申请都不存在。
     *
     * @param who 卡面里怎么称呼被拒的人（用户打过的那个词 / 组员名）。
     */
    private suspend fun publishUnauthorizedDispatch(
        groupId: String?,
        prompt: String,
        senderPeerId: String,
        denial: TeamDispatchAuthorization.Denial,
        who: String,
    ) {
        recordApprovalRequest(
            senderPeerId, denial.agentId,
            "team dispatch: " + who.take(40),
        )
        publishTeamDispatchBlocked(
            groupId, prompt,
            "not authorized for " + who + " (" + denial.verdict + ")",
            approvalRecorded = true,
        )
    }

    private suspend fun publishTeamDispatchBlocked(
        groupId: String?,
        goal: String,
        reason: String,
        /** true = 已落一条授权申请（可信设备面板可见），卡面要说出路而不是只说死。 */
        approvalRecorded: Boolean = false,
        /** 分类码（§3.5）：卡 JSON 带 `blockedCode`，UI 按 code 选 chip/文案/主行动；老端宽容忽略。 */
        code: DispatchBlockedCode? = null,
        /** `TEAM_NOT_IN_GROUP` 的主行动路由：把这支队伍加进本群（[DispatchBlockedCode.TEAM_NOT_IN_GROUP]）。 */
        teamIdForAttach: String? = null,
    ): String? {
        recordBlindSpot(BlindSpotPatrol.KIND_DELEGATION_REJECTED, goal.take(60), reason.take(200))
        val banner = "Team dispatch blocked: " + reason +
            (if (approvalRecorded) "; approval request recorded for the host owner" else "")
        val ticketId = "team-" + com.kapp.tenon.core.nowMillis().toString(36)
        val json = ticketCardJson(
            ticketId = ticketId,
            rev = 1,
            state = "blocked",
            title = goal.take(60),
            banner = banner,
            percent = 0,
            roleName = "Team",
        )
        if (groupId != null) {
            // 登记簿存**会话键**（group:<id>，KDoc 的契约）：裸 id 会让消费方的
            // `ConvId.groupIdOf` 解不出（真机 UX 轮现场：复检卡点了没反应）。
            groupByCard["chain-$ticketId"] = ConvId.group(groupId)
        }
        publishCardJson(groupId, cardJsonWithBlockedMeta(json, code, teamIdForAttach), "agent")
        return json
    }

    /**
     * blocked 卡的**分类码 / 主行动路由**元数据：卡 JSON 是宽容解码面（老手机端忽略
     * 未知键，不画 chip 而已，不崩），所以这里只做追加、不改既有键。
     *
     * **不往 `actions` 里塞按钮**：卡面的主行动由渲染端按 `blockedCode` 自己画
     * （`CardBlockedBanner`），它走 `labelKey` → 本端词条。在这里再塞一条动作，
     * 会让同一颗按钮出现两次，而塞进去的 `label` 只能是宿主的语言（手机壳翻不回去）。
     */
    private fun cardJsonWithBlockedMeta(
        json: String,
        code: DispatchBlockedCode?,
        teamIdForAttach: String?,
    ): String {
        if (code == null && teamIdForAttach == null) return json
        return runCatching {
            val obj = Json.parseToJsonElement(json).jsonObject
            val extras = LinkedHashMap<String, JsonElement>()
            code?.let { extras["blockedCode"] = JsonPrimitive(it.name) }
            teamIdForAttach?.let { extras["attachTeamId"] = JsonPrimitive(it) }
            JsonObject(LinkedHashMap(obj.toMap() + extras)).toString()
        }.getOrDefault(json)
    }

    /**
     * 审批回流：被拒的派单给机主留一条**可批准的申请**（`AgentGrantStore.recordRequest`），
     * 与 1:1 / 跨宿主 invoke 的被拒口径完全一致。批准写 scope=AGENT + 同一把 agentId，
     * 下一次 `grantAllows` 就命中 —— 拒绝从死胡同变成一次审批的距离。
     */
    private fun recordApprovalRequest(nodeId: String, agentId: String, reason: String) {
        controller.invokeRecordGrantRequest?.invoke(nodeId, agentId, reason)
    }

    /** 委派计划的统一出口（文本路由，fire-and-forget）。 */
    private suspend fun delegateByPlan(
        convId: String,
        groupId: String?,
        plan: DelegationPlan,
        available: Set<String>,
        wsId: String? = null,
        /**
         * 是否让 [teamPlanRefiner] 再改一遍文案。false = 形状与文案已由 Planner 裁决，
         * 同一批 instruction 不写两遍（第二遍既白付 20s，又会把裁掉的审查棒语义说回来）。
         */
        refine: Boolean = true,
        /** 主理人上下文：非 null = 这条链由某个 Lead 负责，卡住时他会接手。 */
        leaderBrief: LeaderBrief? = null,
        /** 形状来源（飞行记录）：团队派单带下来，其它入口 null。 */
        planTrace: com.kapp.tenon.agent.ChainPlanTrace? = null,
    ): Boolean {
        val effective = if (refine) refinedPlan(plan, available) else plan
        return when (val decision = StageChainGate.enforce(
            effective,
            available,
            cooldownRemainingMs = DriverCooldownTracker.Shared::remainingMs,
        )) {
            is StageChainGate.Decision.Pass ->
                dispatchPlanStages(convId, groupId, effective, decision.stages, wsId, leaderBrief, planTrace)
            is StageChainGate.Decision.Downgrade -> {
                dispatchSingleWorker(convId, groupId, plan.goal, decision.stage.agentKey, wsId)
                true
            }
            is StageChainGate.Decision.Reject -> {
                publishDelegationRejected(groupId, plan.goal, decision.reason, plan.fromTemplate)
                true
            }
        }
    }

    /** 委派计划的统一出口（invoke 桥，await 终局 —— 真机派单走这条）。 */
    private suspend fun delegateByPlanAwait(
        convId: String,
        groupId: String?,
        plan: DelegationPlan,
        available: Set<String>,
        wsId: String? = null,
    ): TicketOutcome {
        val effective = refinedPlan(plan, available)
        return when (val decision = StageChainGate.enforce(
            effective,
            available,
            cooldownRemainingMs = DriverCooldownTracker.Shared::remainingMs,
        )) {
            is StageChainGate.Decision.Pass -> {
                if (dispatchPlanStages(convId, groupId, effective, decision.stages, wsId)) {
                    TicketOutcome("accepted", plan.goal.take(60), null, null)
                } else {
                    // 链派不出去（没绑仓/没装链执行器）：回落单棒，不静默吞单。
                    dispatchSingleWorker(convId, groupId, plan.goal, decision.stages.first().agentKey, wsId).await()
                }
            }
            is StageChainGate.Decision.Downgrade ->
                dispatchSingleWorker(convId, groupId, plan.goal, decision.stage.agentKey, wsId).await()
            is StageChainGate.Decision.Reject -> {
                val json = publishDelegationRejected(groupId, plan.goal, decision.reason, plan.fromTemplate)
                TicketOutcome("blocked", "Delegation rejected", decision.reason, json)
            }
        }
    }

    /** 计划编译结果 → 执行：≥2 棒走链，1 棒走单棒（防御分支）。 */
    private fun dispatchPlanStages(
        convId: String,
        groupId: String?,
        plan: DelegationPlan,
        stages: List<ChainStage>,
        wsId: String? = null,
        leaderBrief: LeaderBrief? = null,
        /** 形状来源：进飞行记录（链执行器在 :agentNode，只收这份标量快照）。 */
        planTrace: com.kapp.tenon.agent.ChainPlanTrace? = null,
    ): Boolean {
        if (stages.isEmpty()) return false
        if (stages.size == 1) {
            dispatchSingleWorker(convId, groupId, plan.goal, stages[0].agentKey, wsId)
            return true
        }
        return dispatchMultiRoleChain(
            convId, groupId, plan.goal, stages,
            target = plan.target, module = plan.module, context = plan.context, wsId = wsId,
            leaderBrief = leaderBrief,
            planTrace = planTrace,
        )
    }

    /** 拒绝也要可见：一张 blocked 卡说明为什么、当前有什么 —— 不许静默吞委派。 */
    private suspend fun publishDelegationRejected(
        groupId: String?,
        goal: String,
        reason: String,
        fromTemplate: Boolean = false,
    ): String? {
        recordBlindSpot(BlindSpotPatrol.KIND_DELEGATION_REJECTED, goal.take(60), reason.take(200))
        val json = ticketCardJson(
            ticketId = "delegation-" + System.currentTimeMillis().toString(36),
            rev = 1,
            state = "blocked",
            title = goal.take(60),
            // §16.4：模板计划被门拒绝时，banner 注入稳定 ASCII 前缀 —— 用户一眼知道
            // 是自己的模板配置被卡（而不是启发式选棒出错）；E2E 只断言这个前缀，
            // 不断言本地化全句（卡面文案要走 designLocaleParityGate）。
            banner = (if (fromTemplate) "Team template: " else "") + "Delegation rejected: " + reason,
            percent = 0,
            roleName = "Delegation",
        )
        publishCardJson(groupId, json, "codex")
        return json
    }

    /**
     * 工单终局失败落会话的唯一出口：这里只描述事实（哪一单、谁、为什么），
     * 「发成群文本还是 1:1 系统提示」由 [RelayController.announceDispatchFailure]
     * 判 —— 与群意图路径共用同一个发布入口，避免两条路径再次各说各话。
     */
    private fun announceTerminalFailure(
        convId: String,
        agentKey: String,
        title: String,
        ticketId: String,
        outcome: TicketOutcome,
    ) {
        if (outcome.state !in TERMINAL_FAILURE_STATES) return
        controller.announceDispatchFailure(
            convId = convId,
            agentKey = agentKey,
            agentName = roleNameOf(agentKey),
            dispatchId = ticketId,
            title = title,
            reason = (outcome.error ?: outcome.summary).orEmpty(),
            unavailable = false,
        )
    }

    private fun dispatchSingleWorker(
        convId: String,
        groupId: String?,
        prompt: String,
        agentKey: String? = null,
        wsId: String? = null,
        /** 执行档带来的模型（`models.first`）：null = 跟随驱动默认。 */
        model: String? = null,
        /** 责任链形状的决策留痕（团队派单带下来，其它入口 null = 与从前一致）。 */
        planDecision: FlightPlanDecision? = null,
        /**
         * 这一单该找谁审（单兵入口才有，见 [ActiveTicket.reviewBy]）：终态卡上画成
         * 「请 TA 复检」那颗按钮。null = 卡形状与从前一字不差。
         */
        reviewBy: com.kapp.tenon.agent.ChainReviewTarget? = null,
        /**
         * 卡面上的承办人名（组员名 / 员工档案名）：调用方知道是谁就显式带（团队派单
         * 从 seatMembers 解），不知道就留 null —— 内部按 rawKey 回查席位/档案。
         */
        assigneeName: String? = null,
    ): CompletableDeferred<TicketOutcome> {
        val ticketId = "t" + System.currentTimeMillis().toString(36)
        val title = prompt.lineSequence().firstOrNull()?.trim()?.take(60) ?: "Task $ticketId"

        // 派单帧带 agentId 时随帧走（codex/claude/antigravity…）；文本路由没带 → 拒绝。
        // 提前到 handback 之前：退单卡也要挂真名（广播身份与授权都认这个键）。
        val rawKey = agentKey?.takeIf { it.isNotBlank() }
        // **单兵底座**（不变量 5）：按席位 slug 派单时，若执行配置里指派过底座就改用它。
        // 只在「传进来的不是档案键」时查 —— 团队派单传的是 `Compiled.Single.agentKey`
        // （本来就是 driverKey），不会在这里被二次改写。
        val seatOverride = rawKey
            ?.takeIf { AgentProfiles.normalizeKey(it) == null }
            ?.let { seatExecutionOverride(groupId, it) }
        // **员工档案**（A5，方案 §2②）：执行档（更具体）没给时，读席位引用的员工档案 ——
        // 声明值优先于键反解。null = 席位没带员工引用 / 档案没配 driverKey（老数据）。
        val profileCfg = rawKey
            ?.takeIf { AgentProfiles.normalizeKey(it) == null }
            ?.let { employeeDriverConfig(groupId, it) }
        // 席位全名（`dsh-<host>-<workspace>`）不是档案键：由 [AgentProfiles.resolveKey] 归一
        // 成底座键。**不再 `?: raw` 透传**：解析不出的键说明「没有任何明确底座来源」，按
        // 不变量 1 拒绝并说明，而不是把一个来历不明的字符串递给 executeCliTask。
        val routed = rawKey?.let { raw ->
            AgentProfiles.normalizeKey(raw)
                ?: AgentProfiles.resolveKey(raw, availableAgentKeys().orEmpty())
        }
        // 不变量 1（**不猜底座**）：执行档、员工档案、显式点名三者都没有 ⇒ 拒绝并说明，
        // 绝不回落默认驱动。旧写法 `?: "codex"` 正是「不猜底座」要拔掉的那颗钉子 ——
        // 拒因在 P2 升格为 NO_DRIVER 分类码，人话先落在这里。
        // 不变量 1（**不猜底座**）：执行档、员工档案、显式点名三者都没有 ⇒ 拒绝并说明，
        // 绝不回落默认驱动。旧写法 `?: "codex"` 正是「不猜底座」要拔掉的那颗钉子 ——
        // 拒因在 P2 升格为 NO_DRIVER 分类码，人话先落在这里。
        val defaultAgent = seatOverride?.first ?: profileCfg?.first ?: routed ?: run {
            val reason = "no driver configured for @" + (rawKey ?: "(unnamed)") +
                " — assign a runtime in the team roster or the employee profile"
            RelayLog.info("DISPATCH", reason + " group=" + groupId + " conv=" + convId)
            val refused = TicketOutcome("blocked", null, reason, null)
            if (groupId != null) scope.launch {
                publishTeamDispatchBlocked(groupId, prompt, reason, code = DispatchBlockedCode.NO_DRIVER)
            }
            // 不变量 2（不静默）：1:1（groupId == null）没有群卡片可用，也必须在会话里
            // 留一条可见通知 —— 旧实现靠「缺省 codex」混过这一关，A5 之后这里自己发声。
            else announceTerminalFailure(convId, rawKey ?: "unassigned", title, ticketId, refused)
            return CompletableDeferred(refused)
        }
        val effectiveModel = seatOverride?.second ?: profileCfg?.second ?: model

        val host = planeProvider()
            ?: run {
                // 控制面都没起来 = 这一单派不出去；没有工单卡可发，但会话里必须有一句
                // 「为什么」，不能让调用方只拿到一个永远不 complete 的 deferred。
                val unavailable = TicketOutcome("error", null, "control plane unavailable", null)
                announceTerminalFailure(convId, defaultAgent, title, ticketId, unavailable)
                return CompletableDeferred(unavailable)
            }
        val (repoPath, workspaceDenial) = resolveWorkspaceWithDenial(groupId, wsId)

        val done = CompletableDeferred<TicketOutcome>()

        // Hand-back if repo is invalid or missing
        if (repoPath == null || !isGitRepo(repoPath)) {
            val file = repoPath?.let { java.io.File(it) }
            if (repoPath != null && file != null && file.isDirectory && !isGitRepo(repoPath)) {
                pendingBootstraps[ticketId] = PendingBootstrap(
                    ticketId = ticketId,
                    convId = convId,
                    groupId = groupId,
                    prompt = prompt,
                    agentKey = defaultAgent,
                    wsId = wsId,
                    repoPath = repoPath,
                    deferred = done,
                )
                val bootstrapCardJson = buildJsonObject {
                    put("cardId", JsonPrimitive("chain-" + ticketId + "-bootstrap"))
                    put("rev", JsonPrimitive(1))
                    put("type", JsonPrimitive("direction-ask"))
                    put("state", JsonPrimitive("blocked"))
                    put("title", JsonPrimitive(title))
                    put("progress", JsonPrimitive(0))
                    putJsonObject("banner") {
                        put("text", JsonPrimitive("Directory is not a git repository. Initialize Git workspace to proceed?"))
                        put("tone", JsonPrimitive("info"))
                    }
                    put("actions", buildJsonArray {
                        add(buildJsonObject {
                            put("actionId", JsonPrimitive("bootstrap-init"))
                            put("label", JsonPrimitive("Initialize Git & Proceed"))
                            put("approval", JsonPrimitive(true))
                        })
                        add(buildJsonObject {
                            put("actionId", JsonPrimitive("bootstrap-cancel"))
                            put("label", JsonPrimitive("Cancel"))
                        })
                    })
                }
                scope.launch { publishCardJson(groupId ?: convId, bootstrapCardJson.toString(), defaultAgent) }
                return done
            }
            recordBlindSpot(
                BlindSpotPatrol.KIND_HANDBACK,
                title,
                "no git workspace bound to group (agent=$defaultAgent)",
            )
            // 分类码（§3.5）：**两种成因两条出路**。回收授权与「还没选目录」是两件事，
            // 前者让用户去授权、后者让用户去选，卡面文案与主行动都由 code 决定。
            val workspaceCode = when (workspaceDenial) {
                WorkspaceDenial.DIR_NOT_ALLOWED -> DispatchBlockedCode.DIR_NOT_ALLOWED
                else -> DispatchBlockedCode.NO_DIRECTORY
            }
            val handedBackBanner = if (workspaceCode == DispatchBlockedCode.DIR_NOT_ALLOWED) {
                "Handed back: the group's project directory is not allowed on this host"
            } else {
                "Handed back: no git workspace bound to group"
            }
            val handedBack = TicketOutcome(
                state = "blocked",
                summary = null,
                error = handedBackBanner,
                cardJson = cardJsonWithBlockedMeta(
                    ticketCardJson(
                        ticketId = ticketId,
                        rev = 1,
                        state = "blocked",
                        title = title,
                        banner = handedBackBanner,
                        percent = 0,
                        roleName = roleNameOf(defaultAgent),
                    ),
                    workspaceCode,
                    null,
                ),
            )
            scope.launch { publishCardJson(groupId ?: convId, handedBack.cardJson, defaultAgent) }
            announceTerminalFailure(convId, defaultAgent, title, ticketId, handedBack)
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
                // Fail-Loud（任务 5）：执行体抛了 = 工单既没成功也没被收尾 —— 立刻把
                // state=failed 红卡发出去（stderr 尾部进抽屉），严禁让卡挂在 running。
                // 先 complete 终局、后发卡：invoke 桥不等网络，等它的是人。
                val drawer = CrashReport.drawerOf(t)
                val failedJson = ticketCardJson(
                    ticketId = ticketId,
                    rev = revCounter.incrementAndGet(),
                    state = "failed",
                    title = title,
                    banner = "Worker crashed: " + (t.message ?: (t::class.simpleName ?: "crash")),
                    percent = 40,
                    roleName = ticket.role,
                )
                scope.launch {
                    publishCardJson(
                        groupId ?: convId,
                        OrchestratorCards.withFailureCheck(
                            runCatching {
                                kotlinx.serialization.json.Json.parseToJsonElement(failedJson) as kotlinx.serialization.json.JsonObject
                            }.getOrDefault(kotlinx.serialization.json.JsonObject(emptyMap())),
                            observation = t.message ?: (t::class.simpleName ?: "crash"),
                            drawer = drawer,
                        ).toString(),
                        defaultAgent,
                    )
                }
                val failedOutcome = TicketOutcome(
                    "failed",
                    null,
                    t.message ?: (t::class.simpleName ?: "task failed"),
                    failedJson,
                )
                announceTerminalFailure(convId, defaultAgent, title, ticketId, failedOutcome)
                done.complete(failedOutcome)
                throw t
            }
            announceTerminalFailure(convId, defaultAgent, title, ticketId, outcome)
            done.complete(outcome)
        }

        // 卡面承办人：**人名优先**（用户点的是 @员工 / @组员，不是引擎）。
        // 调用方显式带的（团队派单从 seatMembers 解）> 按 rawKey 回查席位/档案
        // > 说不清是谁（裸驱动）回落驱动名 —— 与旧行为逐字一致。
        val resolvedAssignee = assigneeName ?: TicketActorNaming.assigneeNameOf(
            controller.state.value.settings.groups.firstOrNull { it.groupId == groupId },
            controller.state.value.settings.employeeProfiles,
            rawKey,
        )
        ticket = ActiveTicket(
            ticketId = ticketId,
            convId = convId,
            groupId = groupId,
            job = job,
            title = title,
            repoPath = repoPath,
            stage = "proposed",
            rev = revCounter,
            role = resolvedAssignee ?: roleNameOf(defaultAgent),
            agentKey = defaultAgent,
            wsId = wsId,
            model = effectiveModel,
            devPrompt = prompt,
            done = done,
            planDecision = planDecision,
            reviewBy = reviewBy,
            assigneeName = resolvedAssignee,
        )
        activeTickets[ticketId] = ticket
        // 先入表再起跑：打断信号（`abortIfRequested`）按 convId 找活跃工单，
        // 起跑之后、入表之前的那一瞬间发来的「停」会找不到工单而被当成普通消息。

        // ==================== 团队点将（文档 §二 Proposed 态）====================
        // 派单不再无声地直接开跑：先亮出「谁来干、分几步」—— roster 每一棒挂着承办人，
        // 倒计时到点自动开工（文档 §五「极简注意力」：不强制人等，倒计时不是审批门），
        // 卡上的「立即执行 / 取消」只是把这两个时刻握到人手里。
        scope.launch {
            // 承办人行：人名直用（「UX 小工」—— 人不在「本机/远端」的维度上）；
            // 不知道是谁（驱动名回落）才带「（本机）」—— 那句话答的是「哪台机在跑」。
            val assignee = ticket.assigneeName ?: controller.uiTexts.localAssignee(ticket.role)
            val plan = controller.uiTexts.stageRoles(isProto)
            publishTicketCard(
                groupId = groupId ?: convId,
                ticketId = ticketId,
                rev = revCounter.get(),
                state = "proposed",
                title = title,
                banner = teamReadyBannerWithReviewNotice(ticket),
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

    /**
     * 就位横幅 + 「本单由 X 承办」+「未经独立审查」标注。
     *
     * 0 审查棒是**合法形状**（单点、低风险、可自验），代码不替用户认定「必须审」；
     * 但「这单没有独立复核」必须让人看得见 —— 否则它和一张常规团队单长得一模一样，
     * 用户只能等到出问题才发现没人复核过。
     *
     * 同理，主理人换了人也要说出来：`@团队名` 的锚点只是默认落点，而卡面的承办人
     * 那一列写的是**岗位**，不是「你点的那个人」。
     *
     * 纯审查单自己就是审查棒，不打这个标；1:1 单席位派单没有链路形状（`planDecision`
     * 为 null），同样不打。
     */
    private suspend fun teamReadyBannerWithReviewNotice(ticket: ActiveTicket): String {
        val base = controller.uiTexts.teamReadyBanner(ticket.role, TEAM_COUNTDOWN_MS / 1000)
        val decision = ticket.planDecision
        // 只在**真的换了人**时才问文案桥：没换人（绝大多数单）不该多走一次资源解析。
        val picked = if (PlanDecisionBanner.workerSwitched(decision)) {
            controller.uiTexts.pickedWorkerNotice(decision?.workerName.orEmpty())
        } else {
            ""
        }
        return PlanDecisionBanner.compose(base, controller.uiTexts.unreviewedNotice(), decision, picked)
    }

    /** 点将卡的自动开工等待（§二 Proposed → Executing 的「倒计时结束」）。
     * 文档示例是 3s，真机实测不够人读完三行 roster 并把手挪到按钮上 ——
     * 点将交互的意义就在这几秒里，掐得太紧它就只剩装饰。 */
    private companion object {
        const val TEAM_COUNTDOWN_MS = 8_000L

        /** 看门狗轮询周期：判定的粒度上限（真判定用注入时钟，周期只影响响应延迟）。 */
        const val WATCHDOG_POLL_MS = 1_000L

        /**
         * §9 `/test` 的工单任务书：ASCII —— 编排层不写用户文案（cjkLiteralGate），
         * 卡面标题如实显示这行字；真正的「结论」由工单的验收卡与产物通道承担。
         */
        const val QUICK_TEST_PROMPT: String = "Run the test suite and give a short verdict"

        /**
         * 提议卡目标的解析前缀：`team:` 是 `组队:` 的**等价别名**（见
         * `DelegationParser.AUTO_PREFIX`）—— 走它就能复用「模板优先 → 启发式」那一条
         * 既有选棒逻辑，而不用在讨论流里另写一份选人规则。
         */
        const val DISCUSSION_GOAL_PREFIX: String = "team: "

        /** 只读档的诊断 tag：与 `NO_WAKE` 分开，排障时两类问题不混在一起。 */
        const val READ_ONLY_LOG_TAG: String = "READ_ONLY"

        /** 待确认提议的上限（讨论高频，不封顶就是只涨不落的内存表）。 */
        const val MAX_PROPOSALS: Int = 32

        /**
         * 工单终局失败的三档。`aborted` 是用户主动叫停（卡已经回了「已停止」），
         * 不该再补一条失败通知；`review_ready` / `prototype` 是成功路径。
         */
        val TERMINAL_FAILURE_STATES: Set<String> = setOf("blocked", "failed", "error")
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
    fun startByCard(cardId: String): Boolean {
        val tid = cardId.removePrefix("chain-")
        val active = activeTickets[tid] ?: activeTickets.values.firstOrNull { it.ticketId == tid }
        if (active != null) return startProposedTicket(active)
        // 讨论收口的提议卡（P2 规格 1）：它不是工单，表里没有 ActiveTicket ——
        // 点「开工」才把方案变成一张真工单。顺序在工单之后：同名 id 撞上时工单优先。
        return startProposalByCard(tid)
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
    fun hasTicketForCard(cardId: String): Boolean {
        val tid = cardId.removePrefix("chain-")
        return activeTickets.containsKey(tid) || activeTickets.values.any { it.ticketId == tid } ||
            proposals.containsKey(tid)
    }

    /**
     * 点将卡上的「取消」：撤销尚未开工的工单。
     *
     * 与 `abortIfRequested` 的分工：那是「活干到一半被叫停」（摘进程树）；这里工单
     * 还没起跑，只需取消 LAZY 协程、补一张「已取消」卡。写了理由参数：点卡取消与
     * 「点将期发了新消息」的卡面文案不同，后者不该让人以为是谁按了取消。
     */
    fun cancelByCard(cardId: String, reason: String = "Cancelled before start"): Boolean {
        val tid = cardId.removePrefix("chain-")
        val active = activeTickets[tid] ?: activeTickets.values.firstOrNull { it.ticketId == tid }
        if (active == null) {
            // 提议卡（讨论收口、还没变成工单）：撤提案 + 补一张已取消卡，不碰任何进程。
            return cancelProposalByCard(tid, reason)
        }
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
        scope.launch { publishCardJson(active.targetConvId, cancelledJson, active.agentKey) }
        return true
    }

    /**
     * 工单卡上的「停止」（D-NEW-2 的残留形态：卡上根本没有按钮）。
     *
     * 与 [abortIfRequested]（群里打 `stop`）落到**同一套收尾**：定态 `aborted`、移出活跃表、
     * 补一张「已停止」卡、取消工单协程（沙箱在 ≤100ms 轮询周期里摘进程树）。区别只是
     * 入口 —— 一个来自文本，一个来自卡上按钮。
     *
     * **为什么不复用宿主那条 `abortRunningSandboxes`**：那条只摘进程、不动工单。CLI 被
     * SIGTERM 后退出码非零，自愈循环把它当「本轮失败」再来两轮 —— 用户按了「停止」，
     * 等来的是又跑两轮加一张受阻卡。停止必须是**终态**，不是一次失败。
     */
    fun abortByCard(cardId: String, reason: String = "Aborted by user request"): Boolean {
        val tid = cardId.removePrefix("chain-")
        val active = activeTickets[tid] ?: activeTickets.values.firstOrNull { it.ticketId == tid } ?: return false
        active.stage = "aborted"
        activeTickets.remove(active.ticketId)
        pendingPrototypeInputs.remove(active.ticketId)
        val abortedJson = ticketCardJson(
            ticketId = active.ticketId,
            rev = active.rev.incrementAndGet(),
            state = "aborted",
            title = active.title,
            banner = reason,
            percent = 0,
            roleName = active.role,
        )
        active.done.complete(TicketOutcome("aborted", null, reason, abortedJson))
        active.job.cancel(CancellationException("aborted by card action: " + cardId))
        scope.launch { publishCardJson(active.targetConvId, abortedJson, active.agentKey) }
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
        val targetId = groupId ?: convId
        // ==================== STAGE A: PROTOCOL PROTOTYPE FLOW ====================
        publishTicketCard(
            groupId = targetId,
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
            assignees = listOf(ticket.displayActor()),
            startedAt = ticket.createdAt,
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
            convId = convId,
            model = ticket.model,
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
        val protoArtifacts = deliverPrototypeArtifact(targetId, ticketId, protoPatch)

        val protoJson = publishTicketCard(
            groupId = targetId,
            ticketId = ticketId,
            rev = revCounter.incrementAndGet(),
            state = "prototype",
            title = title,
            banner = "Interactive prototype ready. Reply to adjust it, or approve to start development.",
            percent = 50,
            roleName = ticket.role,
            agentId = defaultAgent,
            assignees = listOf(ticket.displayActor()),
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
                    convId = convId,
                    model = ticket.model,
                    onArtifact = { path, name, _, _, _ -> if (name == "patch.diff") refinePatch = path },
                    onEvent = { _, _, _, _ -> },
                )
                ticket.stage = "prototype"
                // 微调后的产物同样要真投一次：否则「改好了」这句话在手机上没有任何可打开的东西。
                val refinedArtifacts = deliverPrototypeArtifact(targetId, ticketId, refinePatch)
                publishTicketCard(
                    groupId = targetId,
                    ticketId = ticketId,
                    rev = revCounter.incrementAndGet(),
                    state = "prototype",
                    title = title,
                    banner = "Refined prototype ready. Reply to adjust it further, or approve to start development.",
                    percent = 60,
                    roleName = ticket.role,
                    agentId = defaultAgent,
                    assignees = listOf(ticket.displayActor()),
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

    private fun toConvPeerId(target: String): String =
        if (ConvId.isGroup(target)) target
        else if (controller.state.value.settings.groups.any { it.groupId == target }) ConvId.group(target)
        else target

    /**
     * 原型产物的**真交付**：沙箱 patch → `prototype.html` 实体 → 投群/投对端 → 返回卡上该写的清单。
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
        targetId: String?,
        ticketId: String,
        patchPath: String?,
    ): List<Pair<String, Long>> {
        if (targetId == null || patchPath == null) return emptyList()
        val patchFile = File(patchPath)
        val snapshot = File(patchFile.parentFile, "files/prototype.html")
        val html = when {
            snapshot.isFile -> runCatching { snapshot.readText() }.getOrNull()
            else -> null
        } ?: StageChain.extractFileFromPatch(patchFile, "prototype.html") ?: return emptyList()
        val dir = File(File(System.getProperty("java.io.tmpdir"), "tenon-artifacts"), ticketId)
        val out = File(dir.apply { mkdirs() }, "prototype.html")
        val written = runCatching { out.writeText(html) }.isSuccess
        if (!written) {
            RelayLog.info("ORCHESTRATOR", "prototype artifact write failed: " + out.absolutePath)
            return emptyList()
        }
        val peerId = toConvPeerId(targetId)
        val delivered = runCatching {
            controller.sendAgentArtifacts(
                peerId,
                listOf(
                    com.kapp.tenon.core.transport.LocalFile(
                        path = out.absolutePath,
                        name = "prototype.html",
                        size = out.length(),
                    ),
                ),
            )
        }.onFailure { t ->
            RelayLog.info("ORCHESTRATOR", "prototype artifact delivery failed: " + t.message)
        }.getOrDefault(false)
        return if (delivered) listOf("prototype.html" to out.length()) else emptyList()
    }

    /**
     * P2P 大产物直投（升级计划 §4.2-3）：把**超过卡内预算**的完整产物经既有
     * 免确认文件通路（purpose=artifact）投到群里，返回**真实发起直投**的文件名集合。
     *
     * ## 与 deliverPatchBundle 的分工
     *
     * 补丁本体由 deliverPatchBundle 恒投（6.3 的那一跳）—— 这里不重发它，
     * 否则对端收到两份同名报价；这里补的是收割目录里其余的大产物
     * （report.json / task.log）。两种走的是同一条传输通路，谁也没有新造协议。
     *
     * 返回值喂给卡面 artifacts 条目的 delivered 标记：报价没发起的文件如实标
     * false —— delivered 写投递事实，不是愿望。
     */
    private fun deliverOversizedArtifacts(targetId: String?, patchPath: String?): Set<String> {
        if (targetId == null || patchPath == null) return emptySet()
        val dir = File(patchPath).parentFile ?: return emptySet()
        val requests = listOf("report.json", "task.log")
            .map { ArtifactPipe.requestFor(File(dir, it)) }
            .filterNotNull()
            // 0 B 产物不投也不占 delivered：空文件对审阅没有增量。
            .filter { it.bytes > 0L }
        if (requests.isEmpty()) return emptySet()
        // 直投的意义就是不把全文塞进卡；这里量的是自描述条目本身 ——
        // 它远小于全文，但预算校验仍必须真的跑一遍（发送侧不生产注定被拒的帧）。
        val metaBytes = ArtifactPipe.requestsCardBytes(requests)
        if (metaBytes > ArtifactPipe.CARD_BYTES) {
            RelayLog.info("ORCHESTRATOR", "artifact pipe meta exceeds card budget: " + metaBytes)
            return emptySet()
        }
        val peerId = toConvPeerId(targetId)
        val delivered = runCatching {
            controller.sendAgentArtifacts(
                peerId,
                requests.map { r ->
                    com.kapp.tenon.core.transport.LocalFile(path = r.path, name = r.name, size = r.bytes)
                },
            )
        }.onFailure { t ->
            RelayLog.info("ORCHESTRATOR", "artifact pipe delivery threw: " + t.message)
        }.getOrDefault(false)
        if (!delivered) {
            RelayLog.info("ORCHESTRATOR", "artifact pipe NOT dispatched — files stay on the host")
            return emptySet()
        }
        RelayLog.info("ORCHESTRATOR", "artifact pipe dispatched " + requests.size + " file(s) to " + targetId)
        return requests.map { it.name }.toSet()
    }

    /**
     * 把 `patch.diff` 真的送到群里（§6.3 离线 P2P bundle）。
     *
     * ## 为什么必须有这一跳
     *
     * 审阅卡上一直写着「产物：patch.diff，N 字节」，而文件**从没离开过宿主本机** ——
     * 补丁躺在沙箱收割目录里，手机上那一行只是一段描述。§6.3 要的正是「通过 Tenon
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
    private fun deliverPatchBundle(targetId: String?, ticketId: String, patchPath: String?): Boolean {
        if (targetId == null || patchPath == null) return false
        val file = File(patchPath)
        if (!file.isFile) {
            RelayLog.info("ORCHESTRATOR", "patch bundle missing on disk (card chain-" + ticketId + "): " + patchPath)
            return false
        }
        val peerId = toConvPeerId(targetId)
        val dispatched = runCatching {
            controller.sendAgentArtifacts(
                peerId,
                listOf(
                    com.kapp.tenon.core.transport.LocalFile(
                        path = file.absolutePath,
                        name = "patch.diff",
                        size = file.length(),
                    ),
                ),
            )
        }.onFailure { t ->
            RelayLog.info("ORCHESTRATOR", "patch bundle delivery threw (card chain-" + ticketId + "): " + t.message)
        }.getOrDefault(false)
        if (dispatched) {
            RelayLog.info("ORCHESTRATOR",
                "patch bundle dispatched to " + targetId +
                    " (card chain-" + ticketId + ", " + file.length() + " B)",
            )
        } else {
            RelayLog.info("ORCHESTRATOR",
                "patch bundle NOT dispatched — it stays on the host " +
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
        /** 打回注水（任务 2）：上一轮被退回时用户写的硬约束，进**每一轮**提示词前置。 */
        initialConstraints: String? = null,
        /** 自愈轮数上限；缺省 3 轮。人工纠偏只传 1 —— 追加且仅追加一轮终验。 */
        maxHealRounds: Int = HealSteering.BASE_ROUNDS,
    ): TicketOutcome {
        val host = planeProvider()
            ?: return TicketOutcome("error", null, "control plane unavailable", null)
        val targetId = groupId ?: convId

        publishTicketCard(
            groupId = targetId,
            ticketId = ticketId,
            rev = revCounter.incrementAndGet(),
            state = "working",
            title = title,
            banner = "Executing worker sandbox in isolated worktree...",
            percent = 40,
            roleName = ticket.role,
            agentId = defaultAgent,
            assignees = listOf(ticket.displayActor()),
            startedAt = ticket.createdAt,
        )

        // 反哺注入（§4.3.2）：仓库账本里与这单 scope 命中的规约，拼在用户反馈之前 ——
        // 账本条目是「这仓的长期事实」，用户反馈是「这单的临时要求」。
        val ledgerBlock = runCatching {
            PitfallLedger.promptBlockFor(
                PitfallLedger.selectFor(
                    File(repoPath),
                    (title + "\n" + devPrompt).lowercase(),
                ),
            )
        }.getOrNull()
        val combinedConstraints = listOfNotNull(initialConstraints, ledgerBlock).joinToString("\n\n")
        val recordedPhases = mutableListOf<FlightPhase>()
        val promptStart = nowMillis()
        val containedPrompt = WorkerPrompt.build(devPrompt, combinedConstraints)
        recordedPhases.add(FlightPhase("prompt_assembly", (nowMillis() - promptStart).coerceAtLeast(0L), "ok"))

        var healRound = 0
        var currentPrompt = containedPrompt
        var patchPath: String? = null
        // CLI **自己说的话**：只收 `token.delta`（真发言）。卡面摘要只能从这里来。
        var said = StringBuilder()
        // 思考增量单独收。它**不进**摘要：真机上模型最后一段常常是「任务看起来是只读的，
        // 所以我该直接给答案」这类内心独白，把它当结论贴到卡面上，用户看到的就是模型
        // 在自言自语而不是 `git log` 的输出。思考只喂崩溃抽屉（那里要的是现场尾部）。
        var thought = StringBuilder()
        var executionError: String? = null
        // 每轮的原因都留着：末轮那句常常是「没交产物」，而首轮那句才是「模型根本没跑起来」。
        // 只留末轮会把看横幅的人带偏（D4，见 [HealReport]）。
        val roundErrors = mutableListOf<String>()
        // 最后一轮验收的完整报告（Acceptance 报告面，Step 5）：review_ready 卡上的
        // 「验收」检查行从这里来 —— 门判过的东西，卡上要看得见。
        var verificationReport: TicketVerification.Report? = null
        // 只读 / 问答式任务的收敛判据（见 [PatchlessAnswer]）：CLI 自己说 finished、
        // 却一行没改 —— 有答案就是答案，不再往 blocked 里推。
        var patchlessRounds = 0
        var answeredWithoutChanges = false
        // 最近一次非空发言。收敛时用它当卡面摘要：末轮可能只回一句「没东西要改」，
        // 而用户要看的结论常常在上一条里。
        var lastAnswer = ""

        while (healRound < maxHealRounds) {
            healRound++
            patchPath = null
            said = StringBuilder()
            thought = StringBuilder()
            // 每轮一套新鲜的看门狗与心跳：租约从本轮第一次事件重新起算。
            val watchdog = SandboxIdleWatchdog(clock = { nowMillis() })
            val emitter = HeartbeatEmitter()
            var lastTool: String? = null
            var watchdogJob: Job? = null
            // 熔断标志（任务 4）：FORCE_KILL 档把它立起来，executeCliTask 返回后本工单
            // 不再走普通失败路径 —— 发 state=timeout 卡并把该轮如实记为看门狗止损。
            var circuitBroken = false
            try {
                watchdogJob = scope.launch {
                    while (true) {
                        delay(WATCHDOG_POLL_MS)
                        when (watchdog.poll()) {
                            SandboxIdleWatchdog.Verdict.OBSERVE -> Unit
                            SandboxIdleWatchdog.Verdict.TERMINATE -> {
                                // 死人开关第一档：先 SIGTERM（沙箱 abort 自带
                                // TERM → 宽限 → SIGKILL 的树级升级）。此刻起再次有
                                // 输出仍会把租约复位（feed），进程自己缓过来不算事故。
                                val killed = runCatching { host.abortRunningSandboxes() }.getOrDefault(0)
                                RelayLog.info("ORCHESTRATOR",
                                    "watchdog idle lease tripped (ticket " + ticketId +
                                        ", round " + healRound + "): sandboxes terminated=" + killed,
                                )
                            }
                            SandboxIdleWatchdog.Verdict.FORCE_KILL -> {
                                // 熔断（任务 4）：租约与宽限都过了还没推进 —— 发
                                // state=timeout 卡（释放 worktree/文件锁已由沙箱 abort
                                // 的无痕清理承担），工单如实收尾，绝不挂在 running。
                                circuitBroken = true
                                runCatching { host.abortRunningSandboxes() }
                                RelayLog.info("ORCHESTRATOR", "watchdog circuit breaker tripped (ticket " + ticketId + ")")
                                val timeoutJson = OrchestratorCards.timeoutCardJson(
                                    ticketId = ticketId,
                                    rev = revCounter.incrementAndGet(),
                                    title = title,
                                    banner = "Watchdog: no worker output for " +
                                        (watchdog.idleTimeoutMs / 1000) + "s — sandbox killed, " +
                                        "worktree released. Retry to run it again.",
                                    roleName = ticket.role,
                                )
                                publishCardJson(targetId, timeoutJson, defaultAgent)
                                break
                            }
                        }
                    }
                }
                val execStart = nowMillis()
                val (cliSessionId, status, error) = host.executeCliTask(
                    agentKey = defaultAgent,
                    prompt = currentPrompt,
                    wsPath = repoPath,
                    requestedSessionId = null,
                    // 会话标题用工单标题：prompt 首行是系统前导（containment preamble），
                    // 从 prompt 截标题就会把整句英文 plumbing 摆上 divider。
                    sessionTitle = title,
                    convId = convId,
                    model = ticket.model,
                    onArtifact = { path, name, _, _, _ -> if (name == "patch.diff") patchPath = path },
                    onEvent = { eventType, _, summary, body ->
                        // 生命迹象：驱动事件 = 子进程输出管道来了新字节。看门狗 feed 在
                        // **每一个**事件上发生 —— 它判的是 I/O 空闲，不是事件语义。
                        watchdog.feed()
                        // 心跳探针（任务 3）：tool.started 携带当前子动作；发射器内部
                        // 节流（无变化不发 + 最小间隔），返回 null 就不发帧。
                        if (eventType == "tool.started") {
                            lastTool = summary ?: lastTool
                            val beat = emitter.note(summary, body?.take(120))
                            if (beat != null) {
                                val beatJson = OrchestratorCards.heartbeatCardJson(
                                    ticketId = ticketId,
                                    rev = revCounter.incrementAndGet(),
                                    title = title,
                                    roleName = ticket.role,
                                    beat = beat,
                                )
                                scope.launch { publishCardJson(targetId, beatJson, defaultAgent) }
                            }
                        }
                        // **只累「人话」。** 其余事件的 body 都不是给人读的：
                        // `card` 是卡片 JSON（原样贴进卡面就是把 `{"cardId":…}` 摊给用户）、
                        // `artifact` 是路径/SHA 对账、`tool.started` 是调用参数、
                        // `error` 是英文诊断。从前用的是黑名单（只排 finished/heartbeat/
                        // artifact），于是最后落进摘要的总是那张卡片 JSON。
                        //
                        // 思考与发言还要**分开**：「这段任务看起来是只读的，我该直接给答案」
                        // 是内心独白，不是给用户的结论。真机上模型最后一段正是这句 ——
                        // 混在一起时卡面横幅就变成了模型的自言自语。
                        if (eventType == "thought.delta") {
                            val chunk = body ?: summary
                            if (!chunk.isNullOrBlank()) thought.append(chunk)
                        } else if (eventType == "token.delta") {
                            val chunk = body ?: summary
                            if (!chunk.isNullOrBlank()) said.append(chunk)
                        }
                    },
                )
                cliSessionId?.takeIf { it.isNotBlank() }?.let { sid ->
                    ticket.sessionId = sid
                    ticketSessionIds[ticketId] = sid
                }
                val execDuration = (nowMillis() - execStart).coerceAtLeast(0L)
                val execVerdict = if (error == null && (status == "done" || status == "finished" || patchPath != null)) "ok" else "error"
                recordedPhases.add(FlightPhase("sandbox_execution_round_$healRound", execDuration, execVerdict))
                if (circuitBroken) {
                    // 看门狗熔断后的该轮：不是普通失败。超时卡已经发过（FORCE_KILL 档），
                    // 这里只把工单如实收尾 —— 阻塞态 + 看门狗因由，下一轮提示词不再硬跑。
                    executionError = "watchdog: no worker output for " + (watchdog.idleTimeoutMs / 1000) +
                        "s — process killed, worktree released"
                    roundErrors += executionError
                    break
                }
                val produced = status == "finished" && patchPath != null
                // **验收门（P4）**：交了补丁不等于干完了。在同一基线上重放本轮补丁、跑仓库
                // 自己的测试（[TicketVerification]），红了就算本轮失败 —— 直接进下一轮提示词。
                //
                // 没有这道门时的真机现场：注入一个恒失败的测试后派单，补丁只改了目标文件、
                // `npm test` 仍红，工单照样翻「待合并」，红灯被原样带进工作区。卡上那根
                // 「自愈验证」棒显示的是 CLI 自己跑测试的自述，不是编排器的判据。
                val verifyStart = nowMillis()
                val verdictReport = if (produced) {
                    val patch = patchPath
                    runCatching { patch?.let { TicketVerification.verifyReport(repoPath, it) } }
                        .getOrElse { t ->
                            // 验收自身崩了不许把工单判死：那是宿主的问题，不是产物的问题。
                            RelayLog.info("verify",
                                "crashed (ticket " + ticketId + "): " +
                                    (t.message ?: t::class.simpleName),
                            )
                            null
                        }
                } else {
                    null
                }
                val verifyDuration = (nowMillis() - verifyStart).coerceAtLeast(0L)
                if (verdictReport != null) {
                    val verifyVerdict = if (verdictReport.failure == null) "ok" else "failed"
                    recordedPhases.add(FlightPhase("verification_round_$healRound", verifyDuration, verifyVerdict))
                }
                val verdict = verdictReport?.failure
                if (produced && verdict == null) {
                    verificationReport = verdictReport
                    executionError = null
                    break
                }
                if (said.isNotBlank()) lastAnswer = said.toString()
                // **只读任务的出口**（[PatchlessAnswer]）：零补丁不等于没干活。
                // 从前这里无条件落进「No patch produced by worker」，于是「帮我看下」
                // 这类单被催满三轮、最后挂 blocked —— 用户看到的是假警报。
                if (patchPath == null) {
                    when (PatchlessAnswer.verdictOf(
                        status = status,
                        saidSomething = said.isNotBlank(),
                        patchlessRoundsSoFar = patchlessRounds,
                        canNudge = healRound < maxHealRounds,
                    )) {
                        PatchlessAnswer.Verdict.Nudge -> patchlessRounds++
                        PatchlessAnswer.Verdict.Answered -> {
                            answeredWithoutChanges = true
                            executionError = null
                            break
                        }
                    }
                }
                val roundError = when {
                    verdict != null -> verdict.reason
                    else -> error
                        ?: (if (patchPath == null) "No patch produced by worker" else "Execution ended in $status")
                }
                val distilledError = ErrorDistiller.distill(roundError)
                executionError = roundError
                roundErrors += roundError
                if (healRound < maxHealRounds) {
                    publishTicketCard(
                        groupId = targetId,
                        ticketId = ticketId,
                        rev = revCounter.incrementAndGet(),
                        state = "working",
                        title = title,
                        banner = "Heal loop $healRound/$maxHealRounds: fixing issues ($executionError)...",
                        percent = 40 + healRound * 15,
                        roleName = ticket.role,
                        agentId = defaultAgent,
                        assignees = listOf(ticket.displayActor()),
                        startedAt = ticket.createdAt,
                    )
                    // 重试提示词按失败类分化（真机观察 O6：问答式任务没有补丁是
                    // **正常终态**，统一喊「fix all issues and ensure npm test passes」
                    // 会把模型逼到编造无关改动去凑补丁——真机现场：+33 −2 的卡片
                    // 词表测试改动与原任务毫无关系）。无 patch 且无验收判负时，
                    // 明确告诉它「没有文件变更也可以收尾」，不许再拿测试当借口。
                    currentPrompt = if (patchPath == null && verdict == null) {
                        "Previous attempt ended without a patch ($distilledError). " +
                            "If the task genuinely needs no file changes, finish with the final answer text and do NOT create or modify files to satisfy a patch requirement. " +
                            "Otherwise make exactly the change the task asks for."
                    } else {
                        "Previous attempt failed: $distilledError. Please inspect tests and workspace, fix all issues, and ensure all tests pass."
                    }
                }
            } finally {
                watchdogJob?.cancel()
            }
        }

        if (answeredWithoutChanges) {
            return publishAnsweredWithoutChanges(
                targetId = targetId,
                ticketId = ticketId,
                title = title,
                rev = revCounter.incrementAndGet(),
                roleName = ticket.role,
                agentId = defaultAgent,
                startedAt = ticket.createdAt,
                said = lastAnswer,
            )
        }

        if (executionError != null) {
            ticket.stage = "blocked"
            // 首因 + 末因都给出来（重复的只写一遍）：这两句指向的修法完全不同，
            // 只报末轮会让人去查「为什么没交产物」，而真正的原因是第一轮就挂了。
            val blockedBanner = (HealReport.bannerOf(roundErrors, maxHealRounds)
                ?: "Handed back: exceeded max heal rounds ($maxHealRounds) — $executionError") +
                " | " + HealSteering.exhaustedTag(maxHealRounds) +
                " | one optional human-steering round remains (worktree kept)"
            // 盲区巡检（Step 6，只记录）：受阻终局进按月账本，每小时聚合一次。
            recordBlindSpot(BlindSpotPatrol.KIND_BLOCKED, title, blockedBanner.take(200))
            // Fail-Loud（Phase 4 任务 5）：崩溃不是终局横幅一句话的事 —— 子进程非 0
            // 退出的现场（驱动事件里攒下的输出尾部）必须进 checks 抽屉，让人能下钻
            // 看 stderr，而不是对着一句 "cli exited with code 1" 猜。
            val blockedJson = publishTicketCard(
                groupId = targetId,
                ticketId = ticketId,
                rev = revCounter.incrementAndGet(),
                state = "blocked",
                title = title,
                banner = blockedBanner,
                percent = 40,
                roleName = ticket.role,
                agentId = defaultAgent,
                assignees = listOf(ticket.displayActor()),
                startedAt = ticket.createdAt,
                failure = StageChainFailure(
                    observation = executionError,
                    // 抽屉要的是**现场尾部**，所以这里思考 + 发言都要（卡面横幅只取发言）：
                    // 真机上失败那一刻能看懂的线索常常在思考里（「这个仓没有测试脚本」）。
                    drawer = CrashReport.drawerOf((thought.toString() + "\n" + said.toString()).trim())
                        ?: CrashReport.drawerOf(executionError),
                ),
                humanSteering = !ticket.steeringUsed,
            )
            return TicketOutcome("blocked", null, blockedBanner, blockedJson)
        }

        // Success -> review_ready card (sandbox isolated, host repo remains clean until user reviews and merges)
        // 终态工单从活跃表退下，但**留一份可打回快照**：横幅承诺「等你合并或打回」，
        // 打回（rejectRevisionByCard / abandonHealByCard）要凭它复活工单重跑。没有它，
        // review_ready 时查 activeTickets 恒空 ⇒ 按钮点了翻 stale（真机现场）。
        reviewableTickets[ticket.ticketId] = ticket
        activeTickets.remove(ticket.ticketId)
        val harvest = patchPath?.let { StageChain.parseHarvest(File(it), File(repoPath).name) }
        val diff = harvest?.first
        val patchBytes = harvest?.second ?: 0L
        val artifactsList = if (patchPath != null) listOf("patch.diff" to patchBytes) else emptyList()
        val defaultSummary = if (diff != null) {
            controller.uiTexts.diffReadyPrompt()
                .ifBlank { "Implementation ready in isolated worktree for review" }
        } else {
            "Implementation ready in isolated worktree for review"
        }
        // banner 的事实优先序（P1）：diff 摘要（N 个文件 +X −Y）是**可信事实**，
        // chat 尾巴是 agent 说过的话（真机出过「stop」当摘要的事故）。有 diff 就说事实。
        val changeDigest = diff?.let { d ->
            controller.uiTexts.diffReadyDetail(
                d.summary,
                (d.additions ?: 0).toLong(),
                (d.deletions ?: 0).toLong(),
            )
                .takeIf { it.isNotBlank() } ?: d.summary
        }
        val summary = changeDigest
            ?: (StageChain.summarize(said.toString().takeLast(600)) ?: defaultSummary)
        // **发布之前登记**：手机可能在卡片到达的同一秒就点了「合并此补丁」，
        // 先发后登记会漏掉这一拍（动作帧到了、账还没记上 ⇒ 又一次「no sandbox patch」）。
        if (patchPath != null) {
            patchByCard["chain-" + ticketId] = SandboxPatchRef(
                sessionId = "",
                workspacePath = repoPath,
                patchPath = patchPath,
            )
            groupByCard["chain-" + ticketId] = groupId?.let { ConvId.group(it) } ?: targetId
            // 同一笔账落到磁盘（[TicketPatchLedger]）：内存表随宿主重启蒸发，
            // 而补丁文件还在 `/tmp/tenon-ws/artifacts/…` 里 —— 真机现场就是这样
            // 得到「重启后点合并 = cannot resolve workspace」的。
            TicketPatchLedger.record(
                cardId = "chain-" + ticketId,
                convKey = targetId,
                workspacePath = repoPath,
                patchPath = patchPath,
            )
        }
        // 飞行记录：证据只来自真实跑过的验收；崩溃/全跳过写 unknown，绝不填 0 冒充通过。
        val hostVerified = verificationReport?.items?.any { it.passed && !it.skipped } == true
        val verifiedCommand = verificationReport?.items
            ?.firstOrNull { it.passed && !it.skipped && it.name != TicketVerification.REPLAY_ITEM }
            ?.name
        FlightRecorder.record(
            FlightRecord(
                taskId = ticketId,
                targetDirectory = File(repoPath).name,
                metrics = FlightMetrics(
                    totalDurationMs = (nowMillis() - ticket.createdAt).coerceAtLeast(0L),
                    selfHealingRounds = (healRound - 1).coerceAtLeast(0),
                    exitCode = if (hostVerified) 0 else null,
                    patchSizeBytes = patchBytes,
                ),
                phases = listOf(
                    FlightPhase("execution", (nowMillis() - ticket.createdAt).coerceAtLeast(0L), "ok"),
                ),
                hostEvidence = FlightEvidence(
                    testCommand = verifiedCommand,
                    finalExitCode = if (hostVerified) 0 else null,
                    verifiedBy = if (hostVerified) {
                        FlightRecord.VERIFIED_BY_HOST_PROCESS
                    } else {
                        FlightRecord.VERIFIED_BY_AGENT_SELF_REPORT
                    },
                ),
                // 形状的来源：采纳了 Planner 的裁决 / 回落编译链 / 这单不经 Planner。
                planDecision = ticket.planDecision,
            ),
            outputDir = File(controller.dataDirectory, "flight-records"),
        )
        // §6.3 离线 bundle：补丁随卡一起走 P2P 文件通道，手机上能真的把 diff 打开看。
        // 先发文件、后发卡 —— 卡到的时候那一行产物已经点得开了。
        // P2P 大产物直投（升级计划 §4.2-3）：收割目录里其余的大产物（结构化报告 /
        // 运行日志）经同一文件通路投群；补丁本体的投递事实取自上一跳的返回值。
        // 卡面条目据此写 delivered —— 没投出去的产物不许谎报已送达。
        val pipedNames = buildSet {
            if (deliverPatchBundle(targetId, ticketId, patchPath)) add("patch.diff")
            addAll(deliverOversizedArtifacts(targetId, patchPath))
        }
        val reviewJson = publishTicketCard(
            groupId = targetId,
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
            // Acceptance 报告面（Step 5）：门真跑过 → 卡上加「验收」检查行；
            // 全跳过（无测试脚本等）→ 不加行，不冒充验过。
            stageRows = verificationReport?.checklistRow()?.let { row ->
                listOf(ticket.role to "done", row)
            },
            assignees = verificationReport?.checklistRow()
                ?.let { listOf(roleNameOf(defaultAgent), "orchestrator") }
                ?: emptyList(),
            // 自描述契约（§7.4）：intent/delivered 只在 review_ready 终态卡上出现，
            // 其余调用点不传，卡形状一字不变。
            selfDescribeDelivered = pipedNames,
            // 单兵卡的第二颗按钮：编制里有在位的审查员才有，见 [ActiveTicket.reviewBy]。
            reviewBy = ticket.reviewBy,
        )
        return TicketOutcome("review_ready", summary, null, reviewJson, patchPath)
    }

    /**
     * 只读 / 问答式任务的终局卡：**做完了，但一行文件都没改**。
     *
     * 为什么不复用 `review_ready`：那张卡的全部意义是「等你合并」—— 零补丁时它既会
     * 摆出一个点不动的「合并此补丁」，又隐含「有改动可看」，两样都不成立。这里发
     * `state = "done"` + 进度 100 + 零产物零动作：手机 `CardStateTag` 对没有动作的卡按
     * `progress == 100` 退成「已完成」，**不必给状态词表加新词**（那份词表两端对账，
     * 加一个词要同时改解析、文案、资源三处）。
     *
     * 横幅必须同时说两件事：结论是什么、以及**没有文件被改**。只说「完成」会让用户
     * 以为改动已经落地；只说「没改文件」又像是失败。
     */
    private suspend fun publishAnsweredWithoutChanges(
        targetId: String?,
        ticketId: String,
        title: String,
        rev: Int,
        roleName: String,
        agentId: String,
        startedAt: Long?,
        said: String,
    ): TicketOutcome {
        activeTickets.remove(ticketId)
        val answer = StageChain.summarize(dedupeRepeatedSpeech(said.takeLast(600)))
        val banner = controller.uiTexts.answeredNoChangesBanner(answer.orEmpty()).ifBlank {
            if (answer.isNullOrBlank()) {
                "Finished — no files changed"
            } else {
                "Finished — no files changed: $answer"
            }
        }
        val json = publishTicketCard(
            groupId = targetId,
            ticketId = ticketId,
            rev = rev,
            state = "done",
            title = title,
            banner = banner,
            percent = 100,
            roleName = roleName,
            agentId = agentId,
            assignees = listOf(agentId),
            startedAt = startedAt,
        )
        if (!answer.isNullOrBlank() && targetId != null) {
            val isGroup = ConvId.isGroup(targetId)
            scope.launch {
                if (isGroup) {
                    controller.broadcastAgentSpeech(targetId, agentId, answer)
                } else {
                    controller.sendDirectAgentSpeech(targetId, agentId, answer)
                }
            }
        }
        return TicketOutcome("done", banner, null, json)
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
            sessionId = ticketSessionIds[ticketId],
        ),
    )

    private suspend fun publishCardJson(targetId: String?, json: String?, agentId: String) {
        if (targetId == null || json == null) return
        runCatching {
            controller.broadcastChainCard(
                groupId = targetId,
                agentId = agentId,
                cardJson = json,
            )
        }.onFailure { t ->
            RelayLog.info("ORCHESTRATOR", "card publish failed: " + t.message)
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
        /** Fail-Loud 现场（非空 = 卡上多一条带 observation/drawer 的 failed 检查项）。 */
        failure: StageChainFailure? = null,
        /**
         * 真实发起过 P2P 直投的产物名集合（升级计划 §4.2-3 / §7.4）。
         * 非空 = artifacts 条目升级为自描述（补 intent 与 delivered）；
         * 缺省 = 卡形状一字不变 —— 只有 review_ready 终态卡传它。
         */
        selfDescribeDelivered: Set<String> = emptySet(),
        /** 熔断卡专用：数据驱动换上纠偏动作（输入槽 + 放弃保留现场），缺省零变化。 */
        humanSteering: Boolean = false,
        /** 工单创建时刻：非空时执行中/终态卡都带耗时段（规格二的 `(45s)`/`耗时 1m20s`）。 */
        startedAt: Long? = null,
        /** 终态卡的「请 TA 复检」落点（单兵才有）：null = 卡形状与从前一致。 */
        reviewBy: com.kapp.tenon.agent.ChainReviewTarget? = null,
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
            elapsedSec = startedAt?.takeIf { it > 0 }?.let { start -> ((nowMillis() - start) / 1000).coerceAtLeast(0) },
            percent = percent,
            banner = banner,
            options = options,
            artifacts = artifacts,
            diff = diff,
            sessionId = ticketSessionIds[ticketId],
            reviewBy = reviewBy,
        )
        val baseCard: kotlinx.serialization.json.JsonObject = StageChain.cardOf(progress)
        val decorated: kotlinx.serialization.json.JsonObject = if (selfDescribeDelivered.isEmpty()) {
            baseCard
        } else {
            ArtifactPipe.upgradeArtifactsSelfDescribing(baseCard, selfDescribeDelivered)
        }
        val card: kotlinx.serialization.json.JsonObject =
            if (humanSteering) HealSteering.cardWithSteeringActions(decorated) else decorated
        val fullJson: String = if (failure == null) {
            card.toString()
        } else {
            OrchestratorCards.withFailureCheck(card, failure.observation, failure.drawer).toString()
        }
        // 5 KiB 软预算：长文本落盘为本地 Artifact（进程内，绝不进网络帧），卡内只留指针；
        // 结构性超预算换同 cardId 的最小卡，保证群消息增量始终为 1 且不截断原 JSON。
        val budget = TicketCardBudget.enforce(fullJson) { name, text ->
            runCatching {
                val dir = File(System.getProperty("java.io.tmpdir"), "tenon-artifacts")
                dir.mkdirs()
                File(dir, name).writeText(text)
                true
            }.getOrDefault(false)
        }
        if (budget is TicketCardBudget.Outcome.Minimal) {
            RelayLog.info("ORCHESTRATOR", "ticket card folded to minimal: " + budget.bytes + " B")
        }
        val json: String = budget.json
        // 发送侧预算闸门（与 OrchestratorCards.guarded 同一口径）：自描述条目变大后
        // 整卡仍不得超 24 KiB —— 超了在这里响，而不是在对端静默拒收时才被发现。
        val bytes = com.kapp.tenon.core.protocol.Codec.utf8ByteLength(json)
        check(bytes <= com.kapp.tenon.core.protocol.Limits.CARD_BYTES) {
            "worker card exceeds CARD_BYTES after self-describing artifacts: " + bytes
        }
        publishCardJson(groupId, json, agentId)
        return json
    }

    /**
     * 派单该落在哪个目录。三条判据，都是**确定性**的，都没有就返回 null 由调用方退单。
     *
     * 1. **群声明的默认工作区**：`virtualAgents[].defaultWsId` 对工作区表做精确 id 匹配
     *    （[RelayUiState.workspaceOfGroup]，产品语义上的"这个群绑了哪个项目"）；
     * 2. **老装置**：工作区行的 `wsId` 就是群 id（建群时"关联本地目录"那条路径留下的形状）；
     * 群绑定只有**席位声明**这一条来源（D5/F2）：「群简介里写了路径 = 绑定了目录」
     * 的 2b 推导已删 —— 简介不是绑定，指针才是；席位投影由指针流程写入。
     *
     * 曾经还有第三段兜底「本机第一个工作区」。真机实测中出现过：同一个群连发
     * 两条派单，一条落在 `~/e2e-weather-app`、一条落在 `~/weather-relay` —— 因为
     * `agentWorkspaces` 是 Map，`firstOrNull` 的顺序随哈希。本端一切正常（卡片、进度、
     * 补丁卡都齐），错的是"改哪个仓"，而往别人的仓库里写字是不可撤销的那类错。
     * 兜底的语义从来不是"猜一个仓"，是"没有就退单"。
     */
    fun resolveWorkspacePath(groupId: String?, wsId: String? = null): String? =
        resolveWorkspaceWithDenial(groupId, wsId).first

    /**
     * [resolveWorkspacePath] 的**带原因**版本：`null` 有两种成因，界面上是两条不同的出路
     * （「这个群还没选项目目录」vs「这台电脑没被授权访问它」），所以不能都退化成 null。
     *
     * 判据顺序与白名单语义（默认 deny）都不变，只是把「为什么没有」带出来。
     */
    private fun resolveWorkspaceWithDenial(
        groupId: String?,
        wsId: String? = null,
    ): Pair<String?, WorkspaceDenial> {
        val hit = resolveWorkspacePathCandidate(groupId, wsId)
        return when (val denial = workspaceDenialOf(hit, hit?.let { pathAllowed?.invoke(it, true) })) {
            WorkspaceDenial.NONE -> hit to denial
            // **节点级白名单（D10/D15）**：目录没被机主显式放行 ⇒ 终局退单，**不回落**
            // 任何别的推导 —— 换一个仓写字比「不执行」更不可接受。
            else -> null to denial
        }
    }

    private fun resolveWorkspacePathCandidate(groupId: String?, wsId: String? = null): String? {
        val state = controller.state.value
        // 0. 显式指定的 wsId（例如 1:1 对话挂接的项目指针）。未命中 ⇒ **终局退单**：
        //    这里曾是落穿点——指针无效会掉进群绑定/单工作区推导，单工作区宿主上
        //    一次指错的派单会静默写进那唯一的仓。显式指针只有两种结局：对的仓，或退单。
        if (wsId != null) {
            val ws = state.agentWorkspaces.values.firstOrNull { it.wsId == wsId }
            val path = ws?.wsPath?.takeIf { it.isNotBlank() }
            if (path != null && isGitRepo(path)) return path
            return null
        }
        // 1. 群绑定的工作区
        if (groupId != null) {
            val declared = state.workspaceOfGroup(groupId)
            val legacy = state.agentWorkspaces.values.firstOrNull { it.wsId == groupId }
            val path = (declared ?: legacy)?.wsPath?.takeIf { it.isNotBlank() }
            if (path != null && isGitRepo(path)) return path
            // 1b. **D16 的目录指针**：同一件事的第二条落点（见 [dirPathOf] 的 KDoc）。
            // 只读指针、不猜顺序：指针唯一，解不出就是没有。
            val pointed = groupDirIdOf(groupId)?.let { dirPathOf(it) }?.takeIf { it.isNotBlank() }
            if (pointed != null && isGitRepo(pointed)) return pointed
        }
        // 3. 宿主仅绑定 1 个工作区时自然推导（1:1 直连场景自动锁定）
        val localWorkspaces = state.agentWorkspaces.values.filter {
            it.peerId == state.selfNodeId && !it.wsPath.isNullOrBlank()
        }
        if (localWorkspaces.size == 1) {
            val path = localWorkspaces.first().wsPath
            if (path != null && isGitRepo(path)) return path
        }
        return null
    }

    /**
     * 链卡合并用：群 → 工作区路径（[resolveWorkspacePath] 的公开口）。
     *
     * 链卡（`chain-*`）的产物在链分支上、不在沙箱补丁登记簿里，合并由
     * DesktopExtension 的 merge-patch 分支走 [DesktopPatchMerge.applyBranch]；
     * 那边只需要「这个群绑的是哪个仓」—— 解析判据只有这一份，不另写。
     */
    fun workspacePathForGroup(groupId: String?): String? = resolveWorkspacePath(groupId)

    /**
     * 链卡合并的**重启兜底**：哪个本地仓真的拥有 `chain/<id>` 分支。
     *
     * [workspacePathForGroup] 走的是「群 → 默认工作区」这条产品语义，它的两个依据
     * （[groupByCard] 登记簿、群绑定表）在宿主重启后一个空、一个对 1:1 会话不成立 ——
     * 于是出现了「分支就在仓里躺着，宿主却说不知道往哪合」（真机
     * `cannot resolve workspace for chain card chain-tmw79h7vw`）。
     *
     * 判据换成仓库自己的 refs：链的每根棒都提交在 `chain/<id>` 上、worktree 收尾即删，
     * 分支是唯一存世形态 ⇒ **谁有这条分支，谁就是这条链改过的仓**。候选只取本机
     * 绑定的工作区（不扫盘），多个仓都有同名分支时返回 null 由调用方如实报受阻 ——
     * 不猜，同 [resolveWorkspacePath] 删掉「第一个工作区」兜底的理由。
     */
    fun workspacePathForChainBranch(branch: String): String? {
        val state = controller.state.value
        val candidates = state.agentWorkspaces.values
            .filter { it.peerId == state.selfNodeId }
            .mapNotNull { it.wsPath?.takeIf(String::isNotBlank) }
        return ChainWorkspaceResolve.workspaceOwning(branch, candidates)
    }

    /**
     * §9 `/status` 的「在跑工单」读数：该群第一个未终局工单的标题（点将中或执行中）。
     * null = 没有在跑的 —— 如实返回，不把已完成的单充数。
     */
    fun activeTicketTitleOf(groupId: String?): String? {
        if (groupId == null) return null
        return activeTickets.values
            .firstOrNull { it.groupId == groupId && (it.stage == "proposed" || it.stage == "working") }
            ?.title
    }

    /**
     * §9 `/test`：直呼单棒工单管线（§9.1 复用既有工单，不新建任务流）。**不走**
     * handlePrompt 的路由面 —— `/test` 是显式命令，不该被委派解析/门改写成别的形状。
     * `dispatchSingleWorker` 同步入表 ⇒ 工单引用可证伪（取不到 = 环境不可用，返回 null）。
     */
    fun dispatchQuickTest(convId: String, groupId: String?): TicketRef? {
        val title = QUICK_TEST_PROMPT.lineSequence().firstOrNull()?.trim()?.take(60)
        dispatchSingleWorker(convId, groupId, QUICK_TEST_PROMPT)
        return activeTickets.values
            .firstOrNull { it.convId == convId && it.title == title }
            ?.let { TicketRef(it.ticketId) }
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
object AbortSignals {

    /** `/stop` 是 N2 快捷指令形态的同一句话（§9.1）：与自然语言同源同执行路径 —— 词表只有这一份。 */
    private val keywords = setOf("stop", "abort", "cancel", "/stop", "\u505c", "\u53d6\u6d88")

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

/**
 * 单兵工单卡面上的**承办人名**（组员名 / 员工档案名 / 席位名）。null = 这一单
 * 说不清是谁（裸驱动派单），卡面回落驱动名 —— 与旧行为逐字一致。
 *
 * 为什么要它（真机 UX 轮的现场）：用户点的是 @员工 / @组员（**人**），卡面
 * 却只认得出引擎（「Codex（本机）」×3）—— 同底座两个员工的两张单在用户眼里
 * 无法区分，planDecision 已带 workerName（团队链卡答得出「这一单点了谁」），
 * 单兵卡在同一件事上断了线。
 *
 * 判据顺序：组员 token（`@组员` 直派，团队席位 member 行的名字）→ 员工档案名
 * （跨群一致的档案事实）→ 本群别名（employeeName）→ 席位名。与
 * `employeeDriverConfig` 同一份座位事实，各答各的问题：那个答「用什么引擎」，
 * 这个答「是谁在干」。
 */
internal object TicketActorNaming {

    fun assigneeNameOf(
        group: com.kapp.tenon.core.protocol.GroupMeta?,
        employeeProfiles: List<com.kapp.tenon.domain.EmployeeProfile>,
        seatKey: String?,
    ): String? {
        val key = seatKey?.trim()?.lowercase().orEmpty()
        if (group == null || key.isEmpty()) return null
        group.virtualAgents.orEmpty().filter { it.isTeam }
            .flatMap { it.teamMembers }
            .firstOrNull { it.name.trim().lowercase() == key }
            ?.let { member -> return member.name.trim().ifBlank { null } }
        val seat = group.seatsMatchingToken(seatKey.orEmpty()).singleOrNull() ?: return null
        employeeProfiles.byEmployeeId(seat.employeeId)?.let { profile ->
            profile.name.trim().takeIf { it.isNotEmpty() }?.let { return it }
        }
        return seat.employeeName?.trim()?.takeIf { it.isNotEmpty() }
            ?: seat.name.trim().takeIf { it.isNotEmpty() }
    }
}
