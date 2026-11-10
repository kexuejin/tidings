// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.AgentExecPolicyStore
import com.kapp.tenon.agent.DelegationTeam
import com.kapp.tenon.agent.DelegationTeamConfigParser
import com.kapp.tenon.agent.drivers.AgentProfiles
import com.kapp.tenon.core.protocol.AgentGrantScopes
import com.kapp.tenon.domain.execDeviceRows
import com.kapp.tenon.domain.rebroadcastGroupMeta
import com.kapp.tenon.domain.ExecGrantView
import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.agent.AgentGrantStore
import com.kapp.tenon.agent.AgentToolApprovalStore
import com.kapp.tenon.agent.AgentSessionRegistry
import com.kapp.tenon.agent.AgentWorkspaceRegistry
import com.kapp.tenon.agent.AgentInboxEnricher
import com.kapp.tenon.agent.ChannelOrchestrator
import com.kapp.tenon.agent.ControlPlane
import com.kapp.tenon.agent.JsonObjectLike
import com.kapp.tenon.agent.GrantReplyOutcome
import com.kapp.tenon.agent.plugins.WebhookPersistence
import com.kapp.tenon.agent.abortRunningSandboxes
import com.kapp.tenon.agent.executePluginTask
import com.kapp.tenon.agent.hasRunningSandboxes
import com.kapp.tenon.agent.pluginForAgent
import com.kapp.tenon.agent.recordSessionPage
import com.kapp.tenon.agent.resolveCliDriver
import com.kapp.tenon.agent.runningSandboxes
import com.kapp.tenon.agent.sendGrantReply
import com.kapp.tenon.domain.RelayController
import com.kapp.tenon.domain.DiffSummary
import com.kapp.tenon.domain.QuickAction
import com.kapp.tenon.domain.QuickActionPort
import com.kapp.tenon.domain.StopAck
import com.kapp.tenon.domain.TicketRef
import com.kapp.tenon.domain.WorkspaceStatus
import com.kapp.tenon.domain.parseQuickAction
import com.kapp.tenon.domain.model.NoticeDetail
import com.kapp.tenon.domain.model.NoticeKind
import com.kapp.tenon.core.session.NodeRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
// 编排面（:orchestration）：工单/责任链/自愈/台账/卡片补丁。桌面壳只装配，不持有实现。

/**
 * agent 侧的「扩展装配」：把控制面挂到本机运行时上，并把 agent 事件接进群聊。
 *
 * ## 为什么住在 :orchestration 而不是桌面壳
 *
 * 它做的是**编排**（会话台账、事件回填、卡片翻面、授权桥、群流转发），不是窗口 / 托盘 /
 * 快捷键。壳只负责注入 controller / scope / 诊断端口（[liveEventSink]），需要一句用户文案
 * 时走 RelayUiTexts 桥。这样别的宿主（手机壳、`:cli` 的测试装置）也能复用同一份 agent 胶水，
 * 而不是「只有桌面端会挂控制面」。
 *
 * ## 为什么不自己 new 一个 NodeRuntime
 * [RelayController] 已经拥有唯一的运行时（它 start 时按设置里的端口绑 UDP/TCP）。
 * 桌面壳如果再建一个 `NodeRuntime`，两个实例会抢同一个端口：后绑的那个要么失败，
 * 要么悄悄分流 UDP 报文，结果就是 UI 收不到消息、控制面也读不到 peer。
 * 所以这里只**读**控制器的运行时（[RelayController.activeRuntime]），把控制面挂上去。
 *
 * ## 为什么控制面要跟着运行时起落
 * 控制面只是一个 localhost 的 JSON 门面，它读的全是运行时的状态。运行时没了，
 * `/v1/peers` 就会返回一份空表 —— 那比 503 更糟，因为调用方会以为「真的没有节点」。
 * 运行时生命周期不暴露事件（见 [RelayController.stop]），所以这里轮询对齐。
 *
 * ## 为什么 agent 台账是这里建的
 * [ControlPlane] 要求记账的**出站**方与**入站**方共用同一个 [AgentSessionRegistry]；
 * 入站那一半由「订阅 `runtime.inbound` 的人」写。桌面端与 agentNode 是两个进程，
 * 所以桌面端必须自己订阅、自己持有，详见 [sessions] 的 KDoc。
 */
class DesktopExtension(
    private val controller: RelayController,
    private val scope: CoroutineScope,
    /** 0 = 由系统分配端口；实际端口读 [controlPlanePort]。 */
    private val httpPort: Int = 0,
    /** 空串 = 回环放行（见 [ControlPlane] 的 token 约定）。 */
    private val token: String = "",
    /**
     * agent 实时输出的**诊断落点**（桌面 Inspector 的环形缓冲）。
     *
     * 这是编排层与壳之间的端口：诊断视图属于宿主 UI，编排层只负责「把事件交出去」，
     * 不依赖任何 UI 模块。**null = 静默无诊断**（测试 / 无壳装配时的正常形态，不影响
     * 业务：事件照样入台账、照样进群聊，只是没人展示实时缓冲）。
     */
    private val liveEventSink: ((sessionId: String, line: String) -> Unit)? = null,
    /**
     * 调度中枢的持久化台账（Job Table）。null = 触发式编排不装配（webhook 保持
     * no-consumer 拒收）。桌面壳用 `createSchedulerStore(platform)` 提供——与消息
     * /Agent 库共用同一条驱动。
     */
    private val schedulerStore: com.kapp.tenon.domain.SchedulerStore? = null,
) {
    private var plane: ControlPlane? = null

    /**
     * 当前控制面挂着的运行时。必须记引用而不是只记「已挂」：
     * `RelayController.restart()` 会新建运行时而未必出现可观察到的 null 窗口
     * （旧运行时被 stop 后马上换新的），只比「plane != null」会让控制面永远留在
     * 已死的旧运行时上，`/v1/peers` 就成了一份过期的空表。
     */
    private var attachedRuntime: NodeRuntime? = null
    private var followJob: Job? = null

    /**
     * 当前运行时的 agent 会话台账；未挂时为 null。
     *
     * ## 为什么桌面端要自己持有一个
     *
     * [ControlPlane] 的 `sessions` 形参要求「出站写入方与入站写入方共用同一个实例」：
     * 控制面只记自己**发出**的 agent 帧，**收到**的帧得由拥有 `runtime.inbound` 的
     * 那一方补上，否则面板只看得到一半会话（自己发的，看不到对方发的）。
     *
     * 在 agentNode 进程里，那一方是 `Main`；而桌面端是**另一个进程**，`Main` 里那个
     * 实例根本不在这里。所以这条约束在桌面端的正确落点就是：**本进程自己订阅
     * 本进程运行时的入站流，并把同一个实例交给控制面**（`ControlPlane` 的 `sessions` 形参
     * KDoc 说的就是这件事）。解析规则复用 `recordInbound`，与 `Main` 逐字同源。
     *
     * 随运行时重挂而重建：台账是**当前态**的视图（见 [AgentSessionRegistry] 的 KDoc），
     * 换了一个运行时之后，旧运行时的会话已经跟我们没关系了，留着比清掉更误导。
     */
    private var sessions: AgentSessionRegistry? = null

    /**
     * 入站订阅的 Job；与 [sessions] 同生命周期，[detach] 时一并取消。
     *
     * 订阅由 `ControlPlane.observeInbound` 托管（三张台账一份规则），
     * 它**必须**可取消：桌面端会随运行时重启重挂控制面，旧任务必须取消以防重复记账。
     */
    private var inboundJob: Job? = null

    /**
     * 托管模式插件宿主（M2）：扫描数据目录下的 `plugins/`、拉起子进程并注入
     * 控制面地址与插件 token；插件就绪后自己注册。随控制面同生命周期：
     * [detach] 时 SIGTERM 全部子进程（插件会先注销再退）。
     *
     * 拉起失败**绝不**上抛：插件是加装能力，坏了最多是「没有插件入驻」，
     * 不能拖垮桌面壳。
     */
    private var pluginHost: com.kapp.tenon.agent.plugins.PluginHost? = null

    private var workerOrchestrator: DesktopWorkerOrchestrator? = null

    // ---- 触发式编排（提案 §9 阶段二）：持久化队列 + 调度中枢 + 只读执行通道 ----
    // schedulerStore 为 null（未注入 Job Table 台账）时整组不装配：webhook 走
    // SourceWebhookRegistry 的 no-consumer 503，如实不可用，不装假队列。

    /** 触发路由定义（manifest triggers 注册通过后对账填充）。 */
    val triggerRegistry = TriggerRegistry()

    private var triggerQueue: TriggerEventDrain? = null
    private var hostScheduler: DesktopHostScheduler? = null

    /** 触发任务对账指纹：注册清单无变化时每拍零成本。 */
    private var lastTriggerFingerprint: String? = null

    /**
     * L1 会话免审的**宿主侧台账**（§4.2 分级免审；落盘 `session-exempts.json`）。
     *
     * 写入方：手机卡片上的「本会话免审」（`session-exempt|<cardId>` 动作，见
     * [onHostCardAction] 的免审分支）。读取方：后续低危动作的自动验讫（[LowRiskReviewer]
     * 判低危 + 本表命中 ⇒ 不再逐次弹审批）。键是 (会话键, agentKey)，带过期租约 ——
     * 会话粒度不是无限期。与 [toolApprovalStore] 分表的理由：那张表按 tool 名跨会话，
     * 这张按会话粒度带租约，撤一口不相干。
     */
    private var sessionExemptLedger: SessionExemptLedger? = null

    /**
     * §16 群模板存储（`delegation-teams.json`）。与授权台账同生命周期：attach 建、
     * detach 清。读写锁 / 0600 / 三态失败语义都在 store 那一层，这里只装配。
     */
    private var delegationTeamStore: DelegationTeamStore? = null

    /** 团队执行档存储（团队执行方案 §3.2）：attach 建 / detach 清（与群模板同生命周期）。 */
    private var teamExecutionStore: TeamExecutionStore? = null

    /**
     * agent 事件 → 群聊的全部有状态逻辑（去重键 / 已转发水位 / 在飞占位 / 补发 / 群匹配）。
     *
     * 两个形参是**取值器**而不是副本：运行时与台账都随控制面重挂而变，转发这一层只许读
     * 当前值 —— 给它一份副本，就会在重挂之后继续往旧运行时发帧（`attachedRuntime` 的 KDoc
     * 记着这条坑）。
     */
    private val eventForwarder = AgentEventForwarder(
        controller = controller,
        scope = scope,
        currentRuntime = { attachedRuntime },
        registryRef = { sessions },
    )

    /** 产物与卡片投递（失败只影响「看不看得见」，不影响活干没干 —— 见类的 KDoc）。 */
    private val artifactDelivery = ArtifactDelivery(
        controller = controller,
        scope = scope,
        tickets = { workerOrchestrator },
    )

    /** 控制面实际监听端口；未启动时为 0。菜单栏与状态栏都读它。 */
    val controlPlanePort: Int
        get() = plane?.boundPort ?: 0

    var grantStore: AgentGrantStore? = null

    /**
     * 「始终允许」免批表 —— 桌面设置页「可信设备」里那一块读/撤销用的。
     *
     * 直取控制面上的**同一个实例**（`ControlPlane.toolApprovalStore`），不是另开一个
     * store 指同一个文件：这份表由对端的 `agent-ack.kind=always` 写、由审批入口读，
     * 面板只是第三只手；两个实例各有各的内存与锁，面板撤掉的规则要等下一次落盘才被
     * 入口看见 —— 中间那段时间里界面上的「已撤销」是假账。
     *
     * 控制面没挂上（未 start / 已 detach）时为 null：面板据此如实显示「暂时读不到」，
     * 而不是画一张看起来"一条规则都没有"的空表（那会让用户以为免批已被清空）。
     */
    val toolApprovalStore: AgentToolApprovalStore?
        get() = plane?.toolApprovalStore

    /**
     * 每个 agent 的执行范围（谁能让它干活）。与 [grantStore] 同一档暴露给 UI ——
     * 两者都是**宿主自己的**台账，面板读写它们就是本机用户在配自己的机器。
     */
    var execPolicyStore: AgentExecPolicyStore? = null
        private set

    /**
     * 把一个授权决定回给设备（`agent-grant` 帧，宿主 → 设备）。
     *
     * 桌面端「设置 → 授信设备」面板上人按的每个按钮都走这里：面板先写本机台账
     * （[grantStore]），再调本方法把决定告诉对端。实现只有一份，在 [ControlPlane.sendGrantReply]
     * 里 —— 与 HTTP 的 `PUT /v1/agent/grants` 的 `grantReply` 分支共用；此前面板够不到
     * 那份实现，于是"用户点完、手机看不到"。
     *
     * 控制面没挂上（端口被占 / 未 start）时如实返回 [GrantReplyOutcome.Failed]，
     * **不假装发了**：面板据此显示"发送失败"。
     */
    suspend fun sendGrantReply(decision: GrantDecision): GrantReplyOutcome {
        // 原因用 ASCII：本文件的 CJK 字面量计数已在棘轮基线上（新增一处就是给门禁添一条红），
        // 而这是一句诊断而不是用户可见文案。
        val plane = plane ?: return GrantReplyOutcome.Failed(decision.nodeId, "control-plane-not-running")
        return plane.sendGrantReply(
            nodeId = decision.nodeId,
            state = decision.state,
            scope = decision.scope,
            agentKey = decision.agentKey,
            wsId = decision.wsId,
            access = decision.access,
            isMyDevice = decision.isMyDevice,
            delegatedApproval = decision.delegatedApproval,
        )
    }

    /**
     * 开始跟随控制器生命周期。必须在 [RelayController.start] **之前**调用：
     * 运行时的创建是异步完成的，之后再挂会错过时机。
     */
    fun start() {
        if (followJob != null) return
        followJob = scope.launch {
            while (isActive) {
                // **单拍异常隔离**：followJob 不只做触发对账，还负责控制面随运行时
                // 重挂、入站订阅、工单桥那一整套装配。任何一拍里冒出来的异常（插件
                // 清单解析、cron 表达式、台账 IO）若直接上抛，整个 followJob 就死了：
                // 此后运行时重启再也不会重挂控制面，症状是「面板一切正常但 /v1 全空」，
                // 且没有任何一处会打印「跟随循环已退出」。
                // 取消必须原样放行——吞掉它会让已取消的协程继续循环。
                try {
                    syncOnce()
                    syncTriggersTick()
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    RelayLog.info("RELAY-EXT", "follow tick failed (isolated; next poll retries): " + t.toString())
                }
                delay(FOLLOW_POLL_MS)
            }
            // 协程被取消（窗口退出）时也别把控制面留在监听状态。
            detach()
        }
    }

    fun stop() {
        followJob?.cancel()
        followJob = null
        detach()
    }

    /**
     * 一拍对齐：运行时换了就重挂，没了（stop / 重启中）就摘掉。
     * 幂等 —— 稳态下每拍只做一次引用比较，不产生任何副作用。
     */
    private fun syncOnce() {
        val runtime = controller.activeRuntime()
        if (runtime == null || (plane != null && attachedRuntime !== runtime)) {
            detach()
            if (runtime == null) return
        }
        if (plane != null) return
        // 台账先建再 start：控制面一旦 start()，`/v1/agent/sessions` 就可能被问，
        // 那一刻台账必须已经在收对方的帧了（空列表对调用方就是「本机没有会话」）。
        // 入站订阅不再在这里手写 —— 三张台账（会话 / 工作区 / 授权）的解析规则
        // 只有 `ControlPlane.observeInbound` 这一份，桌面端、agentNode、测试共用，
        // 集中维护解析分支，防止分叉遗漏。
        // 岗位档案 record 的持久化账本（G1）：内存台账重启即失，错误史必须落盘存活。
        val healthLedger = runCatching {
            AgentHealthLedger(java.io.File(System.getProperty("user.home"), ".tenon/agent-health.jsonl"))
        }.getOrNull()
        val registry = AgentSessionRegistry(
            onRecordSession = { rec ->
                healthLedger?.record(rec.agentKind, rec.sessionId, rec.state, rec.updatedAt)
                controller.recordLocalSession(
                    sessionId = rec.sessionId,
                    alias = rec.alias,
                    agentKind = rec.agentKind,
                    state = rec.state,
                    title = rec.title,
                    startedAt = rec.startedAt,
                    agentId = rec.agentId,
                    wsId = rec.wsId,
                    wsTitle = rec.wsTitle,
                    wsPath = rec.wsPath,
                    updatedAt = rec.updatedAt,
                )
            },
            onRecordEvent = { ev -> eventForwarder.handleRecordEvent(ev) },
        )
        val workspaces = AgentWorkspaceRegistry(
            onRecord = { rec -> recordLocalWorkspaceFrom(controller, rec) },
        )
        val grants = AgentGrantStore(java.io.File(controller.dataDirectory, "agent-grants.json"))
        // T-G2：标签路由的存储与装配 —— peer-tags.json（D-021 裁决：不建表）。
        val tagStore = com.kapp.tenon.core.persist.PeerTagFileStore(
            java.io.File(controller.dataDirectory, "peer-tags.json").absolutePath,
        )
        tagStore.load()
        grantStore = grants
        // L1 会话免审台账：与授权三桥同生命周期（attach 建、detach 清）。文件名不与
        // 既有台账冲突；读写锁与落盘在 store 自己那一层，这里只装配。
        val sessionExempts = SessionExemptLedger(
            java.io.File(controller.dataDirectory, "session-exempts.json"),
        )
        sessionExemptLedger = sessionExempts
        val execPolicies = AgentExecPolicyStore(
            java.io.File(controller.dataDirectory, "agent-exec-policies.json"),
        )
        execPolicyStore = execPolicies
        // §16 群模板存储：delegation-teams.json，与授权三台账同生命周期（attach 建 / detach 清）。
        val delegationTeams = DelegationTeamStore(java.io.File(controller.dataDirectory, "delegation-teams.json"))
        delegationTeamStore = delegationTeams
        // 团队执行档（团队执行方案 §3.2，P0-A）：team-execution.json，同生命周期。
        // 宿主本地的执行配置（driver/model）——不进 group 帧、不广播给全群。
        val teamExecution = TeamExecutionStore(java.io.File(controller.dataDirectory, "team-execution.json"))
        teamExecutionStore = teamExecution
        // M7-T2（D-016 后半步）：Channel 委派的接收宿主侧编排器。幂等 / 互斥 / 租约 /
        // 结果回执都在编排器内；这里只把「解析 + 执行」接到与 /v1/plugin/task 同源的
        // executePluginTask（两处各写一份迟早漂）。授权门在域层（controller 的
        // channelOrchestrator 桥，见下）：编排器只管承接后的调度，不做授权判定。
        // 编排器要先于 ControlPlane 建（构造参数），而 resolve/execute 要用 ControlPlane
        // 的 pluginForAgent / executePluginTask —— 用局部 lateinit 解这个环（start() 之前
        // 编排器不可能被触发：帧进不来）。
        lateinit var createdPlane: ControlPlane
        val channelOrchestrator = ChannelOrchestrator(
            resolve = { payload -> payload.toAgent?.let { createdPlane.pluginForAgent(it) } },
            execute = { entry, payload ->
                var finishSummary: String? = null
                var lastError: String? = null
                val (sessionId, status, error) = createdPlane.executePluginTask(
                    entry, payload.task, null, payload.sessionId,
                ) { eventType, _, summary, _ ->
                    when (eventType) {
                        "finished" -> finishSummary = summary
                        "error" -> lastError = summary
                    }
                }
                if (sessionId == null) {
                    // 会话都开不出来（插件不可达）：如实 error，不冒充 no-plugin ——
                    // 插件在台账里，只是此刻够不着（与 invoke 桥同一口径）。
                    ChannelOrchestrator.Outcome(
                        ok = false,
                        error = error ?: "cannot open a session with " + entry.pluginId,
                    )
                } else {
                    ChannelOrchestrator.Outcome(
                        ok = status != "error",
                        summary = finishSummary,
                        error = if (status == "error") (lastError ?: "plugin task ended in error") else null,
                    )
                }
            },
            sendResult = { toNode, channelId, ok, status, summary, artifactIds, error ->
                // T-G10：结果投递有界重试（对端离线时丢一次 = 发起方白等租约超时）。
                // 安全性：发起侧结果消费按 channelId 首赢去重（域层），重投不复制消息。
                var attempt = 0
                while (true) {
                    val outcome = runCatching {
                        runtime.sendAgentChannelResult(toNode, channelId, ok, status, summary, artifactIds, error)
                    }
                    if (outcome.isSuccess || attempt >= 3) {
                        if (outcome.isFailure) {
                            RelayLog.info("channel", "result delivery failed after " + (attempt + 1) + " attempts: " + channelId)
                        }
                        break
                    }
                    attempt++
                    delay(2_000L * attempt)
                }
            },
            scope = scope,
        )
        // T-3b/E1：CLI 驱动注册表（沙箱装配）—— 必须先于 ControlPlane（构造注入）。
        val driverRegistry = com.kapp.tenon.agent.drivers.DriverRegistry.withCliDrivers(
            sandbox = com.kapp.tenon.agent.drivers.WorktreeSandboxExecutor(
                com.kapp.tenon.agent.drivers.WorktreeSandboxExecutor.defaultRoot(),
            ),
        )
        val dataDir = java.io.File(controller.dataDirectory)
        val pluginsDir = java.io.File(dataDir, "plugins")
        val stateManager = com.kapp.tenon.agent.plugins.PluginStateManager(
            java.io.File(dataDir, "plugins-state.json"),
        )
        val created = ControlPlane(
            runtime, httpPort, token,
            cliDrivers = driverRegistry,
            sessions = registry, workspaces = workspaces, grants = grants, execPolicies = execPolicies,
            channelOrchestrator = channelOrchestrator,
            pluginStateManager = stateManager,
            pluginsDir = pluginsDir,
            // **注入判定**：判据只有一份（core 的 mentionedVirtualAgent），这里只提供
            // 「这个群的元数据长什么样」这个事实 —— desktopApp 能读到 composeApp 的
            // settings.groups，agentNode 读不到，所以由这里注入。
            // **桌面端只提供事实**：这个群的元数据。
            // 「@ 到谁」「谁有权执行」「投给哪个会话」三个判据都在 Tenon 侧
            // （core 的判据 + ControlPlane 的台账），桥接侧只消费结论。
            inboxEnricher = object : AgentInboxEnricher {
                override fun groupMetaOf(payload: JsonObjectLike): GroupMeta? {
                    val groupId = payload.groupId ?: return null
                    return controller.state.value.settings.groups
                        .firstOrNull { it.groupId == groupId }
                }
            },
        )
        try {
            createdPlane = created
            // 工单编排器：文本路由（onAgentPrompt）与 invoke 执行桥（下方 CLI 分支）
            // 的**共同**执行入口 —— 真机 `@agent` 派单帧走后者，两者必须落进同一个
            // 编排器，工单卡/原型立项/追问唤醒/自愈退单才在两条路上都存在。
            // 盲区巡检（Step 6，只记录）：终局事实按月落 ~/.tenon/blindspot/，
            // 每小时聚合最近 24h 打一行日志。落盘失败永远不影响工单。
            val blindSpotDir = runCatching {
                java.io.File(System.getProperty("user.home"), ".tenon/blindspot")
            }.getOrNull()
            val workerOrchestrator = DesktopWorkerOrchestrator(
                { createdPlane }, controller, scope, blindSpotDir, healthLedger,
                // 挡位 B 的 LLM planner（Step 4）：`组队:` 目标先拆解再组队；
                // 无 key / 超时 / 解析失败由 PlannerLlm 自己返回 null，编排器回落启发式两棒。
                autoPlanner = { goal, available -> PlannerLlm.refine(goal, available) },
                // §16 群模板：挡位 B 命中该群模板时直接采用配置选棒并跳过 planner。
                teamStore = delegationTeams,
                detailedAutoPlanner = { goal, available -> PlannerLlm.refinePlan(goal, available) },
                // 团队执行档：@组员 的宿主侧解析与编译（§4.3）。
                teamExecutionStore = teamExecution,
            )
            // 团队执行档端口（§6.3）：UI 读写宿主本地执行配置的唯一通路（手机端为 null ⇒ 只读）。
            controller.teamExecutionPort = DesktopTeamExecutionPort(teamExecution) {
                workerOrchestrator.availableAgentKeys()
            }
            if (blindSpotDir != null) {
                BlindSpotPatrol.startPatrol(blindSpotDir, scope)
            }
            this.workerOrchestrator = workerOrchestrator
            // 责任链执行体（`POST /v1/agent/chain`）：接上 CLI 沙箱、宿主仓 git 与群卡片广播。
            // 在 `created` 之后装配 —— 执行体要用 `created.executeCliTask`（同一个实例，
            // 否则链跑的会话与面板看到的台账是两份）。
            created.chainDispatcher = DesktopStageChain.dispatcher(
                { createdPlane },
                controller,
                scope,
                // L1 会话免审（§4.2）查表器：链验收棒 review_ready 时按会话键查免审台账，
                // 命中且各棒低危 ⇒ Reviewer 自动合流（DesktopStageChain 内只信这条回路的结论）。
                sessionExemptOf = { sid -> sessionExempts.isExempt(sid, agentKey = null, now = System.currentTimeMillis()) },
                // 盲区账本（G2）：链级 blocked 与单棒工单共用同一份按月 JSONL。
                onBlindSpot = { kind, title, detail -> workerOrchestrator.recordBlindSpot(kind, title, detail) },
            )
            created.groupCardPublisher = { groupId, agentId, cardJson ->
                artifactDelivery.publishCardNow(groupId, agentId, cardJson)
            }
            created.start()
            inboundJob = created.observeInbound(scope)
            plane = created
            attachedRuntime = runtime
            // 触发式编排接线（提案 §9 阶段二）：webhook 事件的**唯一**生产消费者是
            // 这里的持久化桥——listener 只做 ingest（落 Job Table + 踢排水），编排
            // 流消费走 TriggerEventDrain 的表驱动循环，不走 listeners fan-out。
            schedulerStore?.let { store ->
                // 回投目标的存在性校验器（提案 §5.2）：只放行本机 settings 配置过的
                // 群会话——结果卡走群通道，1:1 的卡不随帧外发（TriggerExecutor KDoc）。
                created.triggerTargetConvKnown = { convId ->
                    TriggerAssembly.targetGroupKnown(
                        convId,
                        controller.state.value.settings.groups.map { it.groupId },
                    )
                }
                val executor = TriggerExecutor(controller, { createdPlane })
                val queue = TriggerEventDrain(store, scope)
                queue.handler = { event ->
                    val route = triggerRegistry.resolve(event.sourceId)
                    if (route == null) {
                        TriggerVerdict.Fail(
                            "no trigger route registered for source \"" + event.sourceId +
                                "\" (manifest triggers register it)",
                        )
                    } else if (!route.matches(event)) {
                        // §7.2 filter 前置过滤：纯代码规则未命中 → skipped，0 Token。
                        TriggerVerdict.Skip("filtered by trigger route rules for \"" + event.sourceId + "\"")
                    } else {
                        val fields = TriggerPrompts.eventFields(event)
                        val outcome = executor.execute(
                            agentKey = route.agentKey,
                            title = "trigger:" + event.sourceId,
                            instruction = TriggerPrompts.interpolate(route.promptTemplate, fields),
                            sourceId = event.sourceId,
                            event = event,
                            targetGroupId = route.targetGroupId,
                            convId = route.targetGroupId?.let { com.kapp.tenon.core.protocol.ConvId.group(it) },
                        )
                        if (outcome.done) TriggerVerdict.Done else TriggerVerdict.Fail(outcome.error ?: "trigger task failed")
                    }
                }
                val scheduler = DesktopHostScheduler(store, scope, executeCronTask = { task ->
                    val outcome = executor.execute(
                        agentKey = task.agentKey,
                        title = "cron:" + task.taskId.substringAfterLast('/'),
                        instruction = task.prompt,
                        sourceId = null,
                        event = null,
                        targetGroupId = task.targetConvId,
                        convId = task.targetConvId?.let { com.kapp.tenon.core.protocol.ConvId.group(it) },
                    )
                    if (!outcome.done) {
                        // cron 任务失败已由执行通道回投失败卡；这里再如实打日志，
                        // 让「调度器派了但执行失败」在宿主侧留痕。
                        RelayLog.info("RELAY-TRIGGER", "cron task " + task.taskId + " failed: " + (outcome.error ?: "unknown"))
                    }
                })
                // cron 任务目录在阶段三由注册通过的 manifest triggers 填充
                // （scheduler.registerCronTasks）；阶段二先起引擎。
                queue.start()
                scheduler.start()
                // **ACK 之前的持久化出口**（不注册 listener）：webhook 的 200 现在
                // 以「这一行真的进了 Job Table」为前提。旧接线把 ingest 放在异步
                // listener 里，ACK 先回、落盘失败被 runCatching 吞掉、false 被忽略，
                // 事件可以永久丢失且发送方看到的是成功。
                //
                // 三态必须分开：ingest 的 false 同时可能是「重放」与「插入失败」，
                // 靠 eventExists 区分；进程重启后内存幂等表已失，迟到的重投必须
                // 算已受理（Duplicate → 200 duplicate=true），否则会被当成落盘失败
                // 再回一次 503，发送方无限重试。
                created.sourceWebhooks.durableSink = { sourceId, event ->
                    val at = System.currentTimeMillis()
                    val record = com.kapp.tenon.domain.TriggerEventRecord(
                        eventId = event.id,
                        sourceId = sourceId,
                        type = event.type,
                        subject = event.subject,
                        summary = event.summary,
                        detail = event.detail,
                        link = event.link,
                        state = com.kapp.tenon.domain.TriggerEventStates.PENDING,
                        error = null,
                        createdAt = at,
                        updatedAt = at,
                    )
                    try {
                        if (queue.ingest(record)) {
                            WebhookPersistence.Stored
                        } else if (store.eventExists(sourceId, event.id)) {
                            WebhookPersistence.Duplicate
                        } else {
                            WebhookPersistence.Failed(
                                "job table refused the insert for " + sourceId + "/" + event.id,
                            )
                        }
                    } catch (t: Throwable) {
                        WebhookPersistence.Failed(
                            "job table ingest failed for " + sourceId + "/" + event.id + ": " +
                                (t.message ?: t.toString()),
                        )
                    }
                }
                triggerQueue = queue
                hostScheduler = scheduler
                lastTriggerFingerprint = null
            }
            sessions = registry
            // 托管模式插件：目录里有就拉起（token 注入 + 自注册）；坏了如实记录但不上抛。
            runCatching {
                com.kapp.tenon.agent.plugins.BuiltinPluginSeeder.seed(pluginsDir)
                val host = com.kapp.tenon.agent.plugins.PluginHost(
                    dataDirectory = dataDir,
                    controlBaseUrl = { "http://127.0.0.1:" + created.boundPort },
                    plugins = created.plugins,
                    stateManager = stateManager,
                )
                val reports = host.start()
                pluginHost = host
                created.pluginHostRef = { pluginHost }
                reports.forEach { r ->
                    if (!r.started) {
                        RelayLog.info("RELAY-PLUGIN", "skipped " + r.dirName + ": " + (r.reason ?: "unknown"))
                    }
                }
            }.onFailure { t ->
                RelayLog.info("RELAY-PLUGIN", "host failed (ignored): " + (t.message ?: t::class.simpleName))
            }
            eventForwarder.backfillMissingAgentEvents()
            // 补发时机：启动（StateFlow 的首个值）与**重连 / 对端上线 / 换网**。
            // 不能只等下一次启动 —— 用户离线一分钟回来，那条消息就该在一分钟内到。
            scope.launch {
                delay(3000L)
                eventForwarder.backfillMissedGroupForwards()
                // **群元数据补发只做一次，且不挂在状态流上。**
                //
                // 第一版把它放进下面那个 `collect`，结果**自我触发了循环**：广播会改变
                // 在线对端集合 → 状态流又发一个值 → 又广播。真机上是每 15 秒一轮、
                // 队列瞬间涨到 286 条 group-info 帧，全是同一个 rev。
                //
                // 补发要解决的是「对端错过了那一次快照」，而这件事只需要在**本机启动后**
                // 做一次 —— 对端在线时它会自己来问（`op:need`），离线时队列会替它留着。
                controller.rebroadcastGroupMeta()
                controller.state
                    .map { st -> st.network.phase to st.peers.filter { it.online }.map { it.nodeId }.sorted() }
                    .distinctUntilChanged()
                    .collect {
                        eventForwarder.backfillMissedGroupForwards()
                        // **群元数据也要补**：它是快照，而广播只在「变更时」发一次。
                        // 对端在那一刻离线就永远缺 `virtualAgents` —— 接收端还会因为「我已知
                        // 这个 rev」而不再发 need，自己走不出来（真机：手机上三处全是 `dsh`）。
                        //
                        // `rebroadcastGroupMeta` 自带 `(对端, 群) → 已发 rev` 台账，
                        // 所以挂在这个会抖动的状态流上是安全的：同 rev 只发一次。
                        // 第一版没有台账，真机上每 15 秒一轮、队列涨到 286 帧 —— 别去掉它。
                        controller.rebroadcastGroupMeta()
                    }
            }
            controller.onLocalSessionPageRequest = { peerId, wsId, offset, limit ->
                created.recordSessionPage(peerId, wsId, offset, limit)
                true
            }
            // **执行名单的宿主侧实现**：台账在桌面端（`AgentGrantStore`），控制器拿不到。
            //
            // 名单 = `scope=AGENT` 且 agentKey 匹配的授权记录；可信 = `scope=ALL`。
            // 两者都从**同一份快照**读，所以界面上看到的「可信」与「已勾」永远属于同一时刻 ——
            // 分两次读会在并发改动时给出自相矛盾的一屏。
            controller.onExecDevices = { agentKey ->
                val key = agentKey?.trim()?.takeIf { it.isNotEmpty() }
                if (key == null) {
                    null
                } else {
                    val snapshot = grants.snapshot()
                    val rows = snapshot.grants.map {
                        ExecGrantView(
                            nodeId = it.nodeId,
                            scope = it.scope,
                            agentKey = it.agentKey,
                            isMyDevice = it.isMyDevice,
                        )
                    }
                    // 已知设备：群成员 ∪ 对端。标签取 peers 的昵称，拿不到就留给派生层兜底（id 前 8 位）。
                    val st = controller.state.value
                    // **本机自己一定用 `settings.nick`**：它不在对端表里（那是「别人」），
                    // 也不是每个群都在 `memberNicks` 里记了自己。漏了它就会退化成
                    // nodeId 前 8 位 —— 界面上显示一串字母，而用户明明给这台机器起过名字。
                    //
                    // 合并顺序 = 优先级：本机名 > 对端昵称 > 群成员昵称；
                    // `distinctBy` 保留**第一条**，所以有名字的来源要排在前面，
                    // 且空串不算名字（否则一条空 nick 会把真名挤掉）。
                    val known = buildList {
                        if (st.selfNodeId.isNotBlank()) add(st.selfNodeId to st.settings.nick)
                        addAll(st.peers.map { it.nodeId to it.nick })
                        st.settings.groups.forEach { g ->
                            g.members.forEach { m -> add(m to (g.memberNicks?.get(m).orEmpty())) }
                        }
                    }
                        // 空串标签等于「没有名字」，不能占位 —— 它会把后面的真名挤掉。
                        .filter { it.second.isNotBlank() }
                        .distinctBy { it.first }
                    execDeviceRows(
                        selfNodeId = st.selfNodeId,
                        agentKey = key,
                        knownDevices = known,
                        grants = rows,
                    )
                }
            }
            controller.onSetExecDeviceAllowed = { agentKey, nodeId, allowed ->
                // **只动 `scope=AGENT`**：取消时不该碰「这台设备可信任」那条记录 ——
                // 「不许它执行这个 agent」与「不再信任这台设备」是两件事。
                if (allowed) {
                    grants.grant(
                        nodeId = nodeId,
                        scope = AgentGrantScopes.AGENT,
                        agentKey = agentKey,
                        grantedBy = "panel",
                        at = System.currentTimeMillis(),
                    )
                } else {
                    grants.revoke(nodeId = nodeId, scope = AgentGrantScopes.AGENT, agentKey = agentKey)
                }
            }
            // M4 跨端派单（规格 §6.2/§6.3）：域层 ↔ 控制面的双向装配。
            //
            // 派发方向：`/v1/agent/invoke` 的 HTTP 入口只解包，真正的派发（租约、
            // 看板卡、零刷屏纪律、审批防重）都在域层网道 —— 这里只把两者接起来。
            created.invokeDispatcher = { groupId, agentId, prompt, wsId, deadlineMs ->
                controller.dispatchCrossHostInvoke(groupId, agentId, prompt, wsId, deadlineMs)
            }
            // 执行方向：本机承接派单 = 找本机插件 → executePluginTask。与
            // `/v1/plugin/task`（面板派单）走**同一个**实现，台账同源 —— 两处各写一份，
            // 迟早漂成「面板派单有台账、执行桥派单没台账」。
            // S4-4b：派单前先看目标插件在不在（`/v1/plugins` 的 status，PluginRegistry 按 healthz 探活维护）。
            // offline ⇒ 域层立刻回 failed 横幅；unknown/online ⇒ 域层照旧派单，租约兜底。
            controller.invokePluginPresenceOf = { agentId ->
                created.pluginForAgent(agentId)?.status?.let { status ->
                    when (status) {
                        com.kapp.tenon.agent.plugins.PluginRegistry.STATUS_ONLINE ->
                            com.kapp.tenon.domain.PluginPresence.ONLINE
                        com.kapp.tenon.agent.plugins.PluginRegistry.STATUS_OFFLINE ->
                            com.kapp.tenon.domain.PluginPresence.OFFLINE
                        else -> null
                    }
                }
            }
            // M7-T2（D-016 后半步 / D-017 修订）：入站 agent-channel 的域层分流。
            // 授权门在这里（域层才有群元数据）——编排器只管承接后的调度。
            // 判据复用 grants 的执行面判定（同一批可信设备，forExecution 口径）：
            // 发起 agent 必须已获批执行；未授权回 denied（如实拒绝，记 grant 申请），
            // 而不是交给编排器跑出去。targetTag 寻址（T4）暂不支持 ⇒ 如实答 no-plugin。
            // payload 已由 :app 解码成 AgentChannelPayload（desktopApp 类路径上没有
            // kotlinx-serialization，解析不过河 —— M4 执行桥同一条纪律）。
            controller.channelOrchestrator = { payload, fromNode ->
                // T-G9 transfer 宣告（v1）：接管开始时向原宿主宣告会话（origin=
                // transferred）—— 原主的看板看到「任务已移交本机」。执行仍归编排器
                // （租约/互斥/幂等都在它那里），宣告只管可见性。
                if (payload.op == com.kapp.tenon.core.protocol.ChannelOps.TRANSFER) {
                    bridgeBlockingUnit("channel.transfer.announce") {
                        runtime.sendAgentSession(
                            peerId = fromNode,
                            sessionId = "ch-" + payload.channelId,
                            agentKind = payload.fromAgent,
                            state = com.kapp.tenon.core.protocol.AgentSessionStates.BUSY,
                            title = payload.task.take(80),
                            origin = "transferred",
                            updatedAt = System.currentTimeMillis(),
                        )
                    }
                }
                // T-G2 ④：targetTag 帧此前一律 NO_PLUGIN（"lands with M7-T4" 的悬空账）。
                // 现在：路由方选中**本机**才会把帧送到这 —— 校验本机真持有该标签（防御：
                // 不持标签不接活），持有则照常走授权 + 执行；不持才如实 NO_PLUGIN。
                val requestedTag = payload.targetTag
                if (requestedTag != null) {
                    val localNodeId = runtime.profile.nodeId
                    val holdsTag = tagStore.nodeIdsWithTag(requestedTag).contains(localNodeId)
                    if (!holdsTag) {
                        bridgeBlockingUnit("channel.tag.noPlugin") {
                            runtime.sendAgentChannelResult(
                                fromNode, payload.channelId, false,
                                com.kapp.tenon.core.protocol.CodecInvoke.InvokeResultStates.NO_PLUGIN,
                                null, null, "node does not hold tag " + requestedTag,
                            )
                        }
                        true // 已应答：调用方不再回 no-executor
                    } else {
                        // 本机持标签 = 有承接资格；授权判据与无标签路径同一条。
                        val grantOk = grants.allows(fromNode, agentKey = payload.fromAgent, forExecution = true)
                        if (!grantOk) {
                            bridgeBlockingUnit("channel.tag.denied") {
                                runtime.sendAgentChannelResult(
                                    fromNode, payload.channelId, false,
                                    com.kapp.tenon.core.protocol.CodecInvoke.InvokeResultStates.DENIED,
                                    null, null, "execution not authorized for " + payload.fromAgent,
                                )
                            }
                            true
                        } else {
                            true // 授权通过：交给编排器（调用方不再回 no-executor，执行走下方 execute）
                        }
                    }
                } else {
                    val grantOk = grants.allows(fromNode, agentKey = payload.fromAgent, forExecution = true)
                    if (!grantOk) {
                        bridgeBlockingUnit("channel.denied") {
                            runtime.sendAgentChannelResult(
                                fromNode, payload.channelId, false,
                                com.kapp.tenon.core.protocol.CodecInvoke.InvokeResultStates.DENIED,
                                null, null, "execution not authorized for " + payload.fromAgent,
                            )
                        }
                        grants.recordRequest(fromNode, payload.fromAgent, "cross-host channel", System.currentTimeMillis())
                        true // 已应答（denied）
                    } else {
                        created.channelAccepted(payload, fromNode) // 授权过 ⇒ 编排器承接
                    }
                }
            }
            controller.invokeExecutor = object : com.kapp.tenon.domain.AgentInvokeExecutor {
                override suspend fun execute(
                    request: com.kapp.tenon.domain.AgentInvokeTaskRequest,
                ): com.kapp.tenon.domain.AgentInvokeExecution {
                    val self = controller.state.value.selfNodeId
                    // 群里 @ 的是**席位 ID**（`dsh-a030882e`），驱动注册表认的是
                    // **档案键**（`dsh`）。不归位 ⇒ resolveCliDriver 恒 null ⇒ CLI 分支
                    // 整段跳过、直接 NO_PLUGIN，真机上表现为「点了没反应」。
                    // 在位名册与编排器同源（availableAgentKeys），不在装配层再问一遍。
                    val cliKey = AgentProfiles.resolveKey(
                        request.agentId,
                        workerOrchestrator.availableAgentKeys().orEmpty(),
                    ) ?: request.agentId
                    // wsId → 路径的解析表只在宿主本机（隐私收口：路径不出宿主）。
                    val wsPath = request.wsId?.trim()?.takeIf { it.isNotEmpty() }?.let { id ->
                        controller.state.value.agentWorkspaces.values
                            .firstOrNull { it.peerId == self && it.wsId == id }?.wsPath
                    }
                    // E1：插件优先，**CLI 驱动兜底** —— @antigravity/@claude/@codex 这类
                    // 没有插件进程的 agent 走 DriverRegistry 的本机子进程驱动（沙箱
                    // 语义在驱动内）。
                    //
                    // CLI 派单**交给编排器**，不再直调 executeCliTask：真机 `@agent` 帧
                    // 全走本执行器，直调会把编排器的工单卡、阶段 A 原型立项、追问唤醒、
                    // 自愈退单整段绕过去 —— 能力写在编排器里，真机那条路上却不存在。
                    // `handlePromptAwait` 是请求-响应形状（await 工单终局），与 invoke
                    // 的语义正好对上；租约的 `wsId`（任务级显式绑定）原样穿透，
                    // 编排器按「显式 wsId > 群绑定 > 单工作区」次序解析，解析不出就退单。
                    RelayLog.info(
                        "OCGO-BRIDGE",
                        "invoke agent=" + request.agentId + " wsId=" + request.wsId +
                            " convId=" + request.convId + " groupId=" + request.groupId +
                            " plugin=" + created.pluginForAgent(request.agentId)?.pluginId +
                            " cliKey=" + cliKey +
                            " cliDriver=" + (created.resolveCliDriver(cliKey)?.driverId ?: "null"),
                    )
                    if (created.pluginForAgent(request.agentId) == null &&
                        created.resolveCliDriver(cliKey) != null
                    ) {
                        val group = request.groupId?.takeIf { it.isNotBlank() }
                        // 回卡会话的优先级：1:1 直派显式带的 convId（发起方的单聊）> 群 > 兜底。
                        // 伪会话兜底（wsId/agent: 前缀）的卡谁也看不见——历史形状，仅剩
                        // 无 convId 的旧调用触达。
                        val targetConvId = request.convId?.takeIf { it.isNotBlank() }
                            ?: if (group != null) {
                                com.kapp.tenon.core.protocol.ConvId.group(group)
                            } else {
                                request.wsId?.takeIf { it.isNotBlank() } ?: ("agent:" + request.agentId)
                            }
                        val outcome = workerOrchestrator.handlePromptAwait(
                            convId = targetConvId,
                            senderPeerId = self,
                            prompt = request.prompt,
                            replyToId = null,
                            agentKey = cliKey,
                            wsId = request.wsId,
                        )
                        // 产物投递**全部归编排器审阅流**（deliverPatchBundle + 
                        // deliverOversizedArtifacts，在 handlePromptAwait 内完成）。
                        // 这里曾经的补丁直投是**双发**：同一份 patch.diff 两份报价并发
                        // 写对端同一路径（downloads/patch.diff/patch.diff）—— 一次成功、
                        // 一次「写入本机磁盘失败」（D13 族第 3 例的真身）。摘除后单路投递。
                        return if (outcome.error != null) {
                            // 受阻/中止/退单：**带终局卡**回 —— 派单处看到有卡就不再补
                            // 「没干完」横幅：用户按了停，看到「已取消」就够了。
                            com.kapp.tenon.domain.AgentInvokeExecution.withCard(
                                status = com.kapp.tenon.core.protocol.CodecInvoke.InvokeResultStates.ERROR,
                                summary = outcome.summary,
                                error = outcome.error,
                                cardJsonText = outcome.cardJson,
                            )
                        } else {
                            com.kapp.tenon.domain.AgentInvokeExecution.done(outcome.summary, outcome.cardJson)
                        }
                    }
                    val entry = created.pluginForAgent(request.agentId)
                        ?: return com.kapp.tenon.domain.AgentInvokeExecution(
                            status = com.kapp.tenon.core.protocol.CodecInvoke.InvokeResultStates.NO_PLUGIN,
                            error = "no local plugin hosts agent \"" + request.agentId + "\"",
                        )
                    val workerCard = java.util.concurrent.atomic.AtomicReference<String?>(null)
                    var finishSummary: String? = null
                    var lastError: String? = null
                    val deliverGroupId = request.groupId
                    // 沙箱产物（T-3b）：结构化回调收集（路径只走进程内），任务成功后
                    // 投进群 —— 群文件扇出由域层负责（RelayControllerTransfers 的群
                    // 路径），这里只把本机文件交给它。回调在 executePluginTask 的挂起
                    // 调用内顺序发生，普通 List 收集无并发窗口。
                    val artifacts = mutableListOf<Triple<String, String, Long>>()
                    val (sessionId, status, error) = created.executePluginTask(
                        entry, request.prompt, wsPath, null,
                        sessionTitle = sessionTitleFor(request.prompt),
                        onArtifact = { path, name, bytes, _, _ -> artifacts.add(Triple(path, name, bytes)) },
                    ) { eventType, _, summary, body ->
                        when (eventType) {
                            // body 是卡片 JSON 的**文本**（解析在域层的 done 工厂里做 ——
                            // 这边的类路径上没有 kotlinx-serialization，也不该有）。
                            "card" -> workerCard.set(body)
                            "finished" -> finishSummary = summary
                            "error" -> lastError = summary
                        }
                    }
                    val states = com.kapp.tenon.core.protocol.CodecInvoke.InvokeResultStates
                    return when {
                        // 会话都开不出来（插件不可达）：如实回 error，不冒充 no-plugin
                        // —— 插件在台账里，只是此刻够不着。
                        sessionId == null -> com.kapp.tenon.domain.AgentInvokeExecution(
                            status = states.ERROR,
                            error = error ?: "cannot open a session with \"" + entry.pluginId + "\"",
                        )
                        status == "error" -> com.kapp.tenon.domain.AgentInvokeExecution(
                            status = states.ERROR,
                            summary = finishSummary,
                            error = lastError ?: "plugin task ended in error",
                        )
                        else -> {
                            // 成功且带产物：patch.diff 投进派单来源的群（群文件扇出在
                            // 域层；sendFiles 的群路径按 ConvId.group 收敛）。投递失败
                            // 不改写结果状态 —— 文件没送到是投递事实，不是任务失败；
                            // 卡片（含产物条目）照常上群，可从 report.json 对账。
                            artifactDelivery.deliverCliArtifacts(artifacts, deliverGroupId)
                            com.kapp.tenon.domain.AgentInvokeExecution.done(finishSummary, workerCard.get())
                        }
                    }
                }
            }
            // 授权三桥（§6.3）：可信设备判据、每 agent 执行范围、未授权申请进台账
            // （面板的待批列表会冒出来 —— 面板在设置深处，不主动通知就没人去批）。
            controller.invokeGrantAllows = { agentId, nodeId ->
                grants.allows(nodeId, agentKey = agentId, forExecution = true)
            }
            controller.invokeExecPolicyOf = { agentId -> execPolicies.policyOf(agentId) }
            controller.invokeRecordGrantRequest = { nodeId, agentId, reason ->
                grants.recordRequest(nodeId, agentId, reason, System.currentTimeMillis())
            }
            // 宿主侧卡片动作出口（§7.3-2 的插件转发段）：判定记账与全群广播在域层
            // 原子台账，这里只负责把动作送进插件。会话解析：调用方给的优先，缺了按
            // agent 最近一次会话兜；都拿不到就如实放弃（不编一个 id 给插件）。
            // T-G2 ③：发起侧标签路由两桥的**生产装配**（T-2c 只留了 nullable 缝，
            // 本体一直没接 —— 桥不装，requiredTag 消费端永远走失败横幅）。
            // 选择语义 = PeerTagRouter（在线优先 + nodeId 字典序，1e774b1a 钉过）。
            // T-G3：实时增量流 → 进程内环形缓冲（Inspector 面板读它；不落库）。
            scope.launch {
                created.liveAgentEvents.collect { event ->
                    liveEventSink?.invoke(event.sessionId, event.body ?: "")
                }
            }
            controller.peerTagRouter = { tag ->
                com.kapp.tenon.core.persist.PeerTagRouter.resolve(
                    tag,
                    tagStore.nodeIdsWithTag(tag),
                ) { nodeId ->
                    runtime.peerList().firstOrNull { it.profile?.nodeId == nodeId }?.online == true
                }
            }
            controller.channelDispatch = { nodeId, channelId, tag, task, deadlineMs ->
                scope.launch {
                    runCatching {
                        runtime.sendAgentChannel(
                            peerId = nodeId,
                            channelId = channelId,
                            op = com.kapp.tenon.core.protocol.ChannelOps.ASSIGN,
                            fromAgent = "tenon-host",
                            task = task,
                            targetTag = tag,
                            deadlineMs = deadlineMs,
                        )
                    }.onFailure { t ->
                        RelayLog.info("RELAY-INVOKE", "channel dispatch failed: " + (t.message ?: (t::class.simpleName ?: "unknown")))
                    }
                }
            }
            // 卡片动作的分派全在 HostCardActions（11 个分支、340 行）；装配处只转一手。
            val cardActions = HostCardActions(
                controller = controller,
                scope = scope,
                plane = { created },
                selfNodeId = runtime.profile.nodeId,
                tickets = { workerOrchestrator },
                sessions = { sessions },
                exempts = { sessionExemptLedger },
                delivery = artifactDelivery,
            )
            controller.onHostCardAction = cardActions::onHostCardAction
            // **执行由 DSH 注入桥承担，这里不再跑本地驱动。**
            //
            // 原先这里接的是 `driverRegistry` 里的 `native` 驱动 —— 它自己 KDoc 写着
            // 「没有挂载执行运行时：这次不会执行命令、不读写文件、不产出结果」，
            // 于是用户 @agent 后收到的是那句「本机 native 驱动的兜底」，而活一件没干。
            // 那正是本仓反复出现的「界面在说假话」形态：一个**永远成功但什么都不做**的执行器。
            //
            // 真正的执行路径是 `dsh-tenon`：它轮询本机 agentNode 的 `/v1/inbox`，
            // 按随行给出的 `agentHint`（addressd / 授权 / targetSessionId）把 prompt
            // 注入宿主上的 DSH 会话。那条路径**与本回调无关** —— 它只看 inbox。
            //
            // 所以这里保持**非 null**（群消息路由要靠 `onAgentPrompt != null` 判「本机有 agent」），
            // 但什么都不做：让既有的 `NativeRelayDriver` 不再产出假回复。
            //
            // 代价（如实记在这里）：**没装 dsh-tenon 的宿主，@agent 将得不到任何回复**。
            // 那比回一句「我没有运行时」诚实 —— 后者让用户以为 agent 收到了却干不了活。
            // DSH Super-Agent alignment: single worker direct dispatch, follow-up wake-up, immediate abort
            // （workerOrchestrator 在本 try 块开头创建 —— invoke 执行桥与文本路由共用同一实例。）
            // §7.2 诊断日志桥：域层路由点就地发出的 NO_WAKE 等诊断落进 RelayLog 环，
            // 诊断包经 recentLogLines 取「最近日志」节。这是 :app 诊断的唯一出口。
            controller.diagnosticLogSink = { tag, message -> RelayLog.info(tag, message) }
            controller.onAgentPrompt = { convId, senderPeerId, prompt, replyToId, mentionedAgentKey ->
                workerOrchestrator.handlePrompt(
                    convId, senderPeerId, prompt, replyToId,
                    mentionedAgentKey = mentionedAgentKey,
                )
            }
            // §6.1 打断桥：挂在**入站文本**上，不挂在 onAgentPrompt 上 ——
            // 「停」不是 prompt，挂在后者就永远进不来（真机实测：收到了、没反应）。
            //
            // 两件事都要做，缺一不成：
            // 1. 编排器侧取消工单（卡片立刻翻 `Aborted by user request`）；
            // 2. **直接摘进程**（`abortRunningSandboxes`）—— 取消只是"请它自己停"，卡在阻塞读
            //    的 CLI 未必及时响应；真机实测卡片已 aborted、同一沙箱里的 `codex exec`
            //    七分钟后仍在跑。进程摘掉的路径不经过协程，所以它才是「停」的确定性那一半。
            //
            // 消费条件：命中关键词，且**本机确实有活在跑**（有工单，或有沙箱任务）。
            //
            // 没有活在跑时，从前一律 `false`（当普通消息放过）—— 于是用户打完 `stop`
            // 看到的是一条自己发的聊天气泡，任务该跑还在跑、或者压根没跑，界面一句话
            // 都不说（真机截图里就是这样：卡已经是「待合并」，用户又打了一条 `stop`）。
            // 现在：**整条就是关键词**时回一条系统提示并消费掉，不再静默；
            // `取消订单` 这类以关键词开头的正常话仍然照常放行（`isExact` 的判据）。
            controller.onAgentAbort = { convId, text ->
                val planeNow = createdPlane
                if (!workerOrchestrator.isAbortSignal(text)) {
                    false
                } else {
                    val hadTicket = workerOrchestrator.abortIfRequested(convId, text)
                    if (hadTicket || planeNow.hasRunningSandboxes()) {
                        scope.launch { planeNow.abortRunningSandboxes() }
                        true
                    } else if (workerOrchestrator.isExactAbortSignal(text)) {
                        controller.postNotice(
                            convId,
                            com.kapp.tenon.domain.model.NoticeKind.AbortNoRunningTask,
                        )
                        true
                    } else {
                        false
                    }
                }
            }
            // 阶段 A 追问与微调桥：挂在入站文本上
            controller.onAgentFollowUp = { convId, senderPeerId, text ->
                workerOrchestrator.followUpIfActive(convId, text)
            }
            // §16.6 入口 A：对话即配置 —— 固定句式的确定性拦截（§15 第 12 条裁决），
            // 与打断桥同一条前置拦截链：命中即消费，不匹配照常走路由。
            controller.onDelegationTeamConfig = { convId, text ->
                tryConfigureDelegationTeam(controller, convId, text)
            }
            // §9.2 快捷指令桥（§15 第 13 条裁决：同一条前置拦截链；/stop 由打断桥消费）。
            controller.onQuickAction = { convId, text ->
                tryHandleQuickAction(controller, convId, text)
            }
        } catch (t: Throwable) {
            // 控制面是「加装」能力，绑不上（例如 8788 被别的进程占了）不能拖垮整个桌面壳：
            // UI 和传输层跟它无关，仍然可用。这里吞掉异常，菜单栏的状态栏会显示端口 0。
            plane = null
            attachedRuntime = null
            sessions = null
            workerOrchestrator = null
            controller.onLocalSessionPageRequest = null
            controller.onAgentPrompt = null
            controller.onAgentAbort = null
            controller.onAgentFollowUp = null
            controller.diagnosticLogSink = null
            controller.onDelegationTeamConfig = null
            controller.onQuickAction = null
            delegationTeamStore = null
        teamExecutionStore = null
            controller.onExecDevices = null
            controller.onSetExecDeviceAllowed = null
            inboundJob?.cancel()
            inboundJob = null
        }
    }

    /**
     * §16.6 入口 A 的落点：句式已由 [DelegationTeamConfigParser] 命中（没命中不会进到这里），
     * 这里做「归一 → 异厂商校验 → 落盘 → 可见回执」。**拒绝必须可见**（§15 第 8 条精神）：
     * 拒绝若只活在日志里，用户对着一句没反应的配置语以为存上了。
     *
     * 回执走系统提示行（[NoticeKind]，本地可见、不进协议）；roster/原因放 `NoticeDetail.detail`
     * （ASCII 展示名），措辞本地化在渲染端 —— 编排层不写用户文案。
     */
    private fun tryConfigureDelegationTeam(controller: RelayController, convId: String, text: String): Boolean {
        // 模板挂在群上：私聊没有 groupId，句式再对也不消费（§16.1）。
        val groupId = com.kapp.tenon.core.protocol.ConvId.groupIdOf(convId) ?: return false
        val (workerRaw, reviewerRaw) = DelegationTeamConfigParser.parse(text) ?: return false
        val worker = AgentProfiles.normalizeKey(workerRaw)
        val reviewer = AgentProfiles.normalizeKey(reviewerRaw)
        if (worker == null || reviewer == null) {
            // 拒绝与确认同回执纪律：广播群消息（notice 是宿主本地的，配置者看不到）。
            controller.sendToConversation(                convId,
                "team config rejected: unknown agent " + workerRaw + " / " + reviewerRaw,
            )
            return true
        }
        val saved = delegationTeamStore?.save(
            DelegationTeam(
                groupId = groupId,
                workerKey = worker,
                reviewerKey = reviewer,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        if (saved != true) {
            val why = if (AgentProfiles.vendorOf(worker) == AgentProfiles.vendorOf(reviewer)) {
                "same vendor: " + (AgentProfiles.vendorOf(worker) ?: "?")
            } else {
                "store rejected save"
            }
            controller.sendToConversation(convId, "team config rejected: " + why, null)
            return true
        }
        val roster = listOf(worker, reviewer)
            .mapNotNull { AgentProfiles.profileOf(it)?.displayName }
            .joinToString(" / ")
        controller.sendToConversation(convId, "team config saved for this group: " + roster, null)
        return true
    }

    /**
     * §9.2 快捷指令桥：`parseQuickAction` 全词匹配，命中 → `QuickActionPort` 执行 +
     * **可见回执**（NoticeKind.QuickActionReceipt，消费了消息就必须说话）；
     * 未命中 → false，原样落回唤醒判据（零行为变更）。
     *
     * `/stop` 不在此桥：AbortSignals 词表已含 `/stop`，打断桥在链条更前面消费 ——
     * 四条指令同一语义（显式命令不过唤醒判据），stop 且共用同一执行路径与回执（§9.1）。
     */
    private fun tryHandleQuickAction(controller: RelayController, convId: String, text: String): Boolean {
        val action = parseQuickAction(text) ?: return false
        if (action == QuickAction.STOP) return false
        val groupId = com.kapp.tenon.core.protocol.ConvId.groupIdOf(convId)
        val port = quickActionPort()
        val receipt: String = when (action) {
            QuickAction.STATUS ->
                port.status(groupId)?.let { s ->
                    "branch=" + (s.branch ?: "?") +
                        " changed=" + s.changedFiles + " untracked=" + s.untrackedFiles +
                        (s.activeTicket?.let { " ticket=" + it.take(40) } ?: "")
                } ?: NO_GIT_RECEIPT
            QuickAction.DIFF ->
                port.diff(groupId)?.let { d -> "files=" + d.files + " +" + d.added + " -" + d.removed }
                    ?: NO_GIT_RECEIPT
            QuickAction.TEST ->
                port.runTests(groupId)?.let { "ticket " + it.ticketId + " (card follows in this group)" }
                    ?: NO_WORKER_RECEIPT
            QuickAction.STOP -> return false
        }
        // 回执必须**广播**成群消息：notice 是宿主本地数据，手机上的发送者什么都看不到
        // （真机 E2E 抓到：宿主库有 QuickActionReceipt、手机库没有 —— 「发送方看到静默」）。
        controller.sendToConversation(convId, receipt, null)
        return true
    }

    /** §9.2 QuickActionPort 宿主实现。git 只读；环境不可用如实返回 null（不编读数）。 */
    private fun quickActionPort(): QuickActionPort = object : QuickActionPort {
        override fun status(groupId: String?): WorkspaceStatus? {
            val orch = workerOrchestrator ?: return null
            val repo = orch.workspacePathForGroup(groupId) ?: return null
            val porcelain = gitOut(repo, "status", "--porcelain")?.lines()?.filter { it.isNotBlank() } ?: return null
            return WorkspaceStatus(
                branch = gitOut(repo, "rev-parse", "--abbrev-ref", "HEAD"),
                changedFiles = porcelain.count { !it.startsWith("??") },
                untrackedFiles = porcelain.count { it.startsWith("??") },
                activeTicket = orch.activeTicketTitleOf(groupId),
            )
        }

        override fun diff(groupId: String?): DiffSummary? {
            val repo = workerOrchestrator?.workspacePathForGroup(groupId) ?: return null
            // git diff --shortstat：" 3 files changed, 10 insertions(+), 2 deletions(-)"；
            // 无变更时输出为空 ⇒ 三个 0（如实的「没有变更」，不是 null 的「环境不可用」）。
            val shortstat = gitOut(repo, "diff", "--shortstat") ?: return DiffSummary(0, 0, 0)
            return DiffSummary(
                files = Regex("(\\d+) files? changed").find(shortstat)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                added = Regex("(\\d+) insertions?\\(\\+\\)").find(shortstat)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                removed = Regex("(\\d+) deletions?\\(-\\)").find(shortstat)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            )
        }

        override fun runTests(groupId: String?): TicketRef? {
            val orch = workerOrchestrator ?: return null
            val conv = groupId?.let { com.kapp.tenon.core.protocol.ConvId.group(it) } ?: return null
            return orch.dispatchQuickTest(conv, groupId)
        }

        override fun stop(groupId: String?, reason: String): StopAck {
            // 与文本 stop 同一执行路径（§9.1）：abortIfRequested + abortRunningSandboxes。
            // PID 级纪律在 WorktreeSandbox.destroyProcessTree 内部；回执是计数（可证伪）。
            // 非挂起接口里的挂起下一跳：走全仓唯一的 runBlocking 通道（有上界、有读数）。
            val planeNow = plane ?: return StopAck(0, 0)
            return bridgeBlocking("quickaction-stop", fallback = StopAck(0, 0)) {
                val orch = workerOrchestrator
                if (orch != null && groupId != null) {
                    orch.abortIfRequested(com.kapp.tenon.core.protocol.ConvId.group(groupId), reason)
                }
                val aborted = planeNow.abortRunningSandboxes()
                StopAck(aborted, planeNow.runningSandboxes())
            }
        }
    }

    /** git 只读查询：超时 / 非零退出 / 空输出 ⇒ null（调用方如实回执「环境不可用」）。 */
    private fun gitOut(repo: String, vararg args: String): String? = runCatching {
        val proc = ProcessBuilder(listOf("git") + args)
            .directory(java.io.File(repo))
            .start()
        if (!proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return null
        }
        if (proc.exitValue() != 0) return null
        proc.inputStream.bufferedReader().readText().trim().ifBlank { null }
    }.getOrNull()

    /**
     * 触发任务对账（提案 §9 阶段三）：注册通过的插件清单 → 调度任务/事件路由。
     * 指纹去重——注册清单无变化时每拍零成本；插件注销后对账式移除（不残响）。
     */
    private fun syncTriggersTick() {
        val scheduler = hostScheduler ?: return
        val currentPlane = plane ?: return
        val manifests = currentPlane.plugins.list().map { it.manifest }.filter { it.triggers.isNotEmpty() }
        // 指纹用**整份清单的 toString()**，而不是手挑几个字段（判据与理由见
        // triggerManifestFingerprint 的 KDoc）。
        val fingerprint = triggerManifestFingerprint(manifests)
        if (fingerprint == lastTriggerFingerprint) return
        try {
            val tasks = TriggerAssembly.fromManifests(manifests)
            scheduler.syncCronTasks(tasks.cronTasks)
            // 整表一次换快照：clear()+逐条 register() 之间的空表窗口会让并发消费
            // 把一条已落盘事件判成「无路由」而永久失败（见 TriggerRegistry KDoc）。
            val rejected = triggerRegistry.replaceAll(tasks.eventRoutes)
            rejected.forEach { route ->
                // 两个插件声明了同一个 sourceId：先注册者胜，冲突必须说出来 ——
                // 静默覆盖的症状是「我配的触发器有时不生效」，事后查不出来。
                RelayLog.info(
                    "RELAY-TRIGGER",
                    "webhook sourceId collision: \"" + route.sourceId + "\" already claimed by another route; " +
                        "this one is ignored (first registration wins)",
                )
            }
            RelayLog.info(
                "RELAY-TRIGGER",
                "trigger tasks synced: " + tasks.cronTasks.size + " cron, " + tasks.eventRoutes.size + " webhook route(s)",
            )
            // **整拍成功才记账**：指纹是「对账成功」的判据，不是「试过了」的判据。
            // 旧写法在干活之前就写指纹，fromManifests / syncCronTasks 一抛错，
            // 下一拍因指纹相等直接 return —— 触发器就此永久停在半同步状态。
            lastTriggerFingerprint = fingerprint
        } catch (t: Throwable) {
            lastTriggerFingerprint = null
            RelayLog.info("RELAY-TRIGGER", "trigger sync tick failed; will retry next poll: " + t.toString())
        }
    }

    private fun detach() {
        workerOrchestrator = null
        sessionExemptLedger = null
        controller.onLocalSessionPageRequest = null
        controller.onAgentPrompt = null
        controller.onAgentAbort = null
        controller.onAgentFollowUp = null
        // M4 跨端派单五桥随控制面一起撤：留着它们，域层会把派单交给一个已停止的控制面。
        controller.invokeExecutor = null
        controller.invokePluginPresenceOf = null
        controller.invokeGrantAllows = null
        controller.invokeExecPolicyOf = null
        controller.invokeRecordGrantRequest = null
        controller.onHostCardAction = null
        // 先停插件（SIGTERM，插件会注销再退）再停控制面：注销要走控制面的 HTTP 路由。
        pluginHost?.stop()
        pluginHost = null
        // 触发式编排随控制面撤（先停派发循环再停 HTTP——listener 正往队列里 ingest）。
        triggerQueue?.stop()
        triggerQueue = null
        hostScheduler?.stop()
        hostScheduler = null
        lastTriggerFingerprint = null
        plane?.stop()
        plane = null
        attachedRuntime = null
        sessions = null
        grantStore = null
        execPolicyStore = null
        // 先停控制面再撤订阅：collector 里会调 runtime 发送（grant 回包），
        // 运行时可能紧随其后被 stop —— 留着它跑就是往死运行时里发帧。
        inboundJob?.cancel()
        inboundJob = null
    }

    /**
     * 「这条用户消息**已经发过群**」的水位（sessionId → 已转发到群的最大 cursor）。
     *
     * 为什么必须单独记：alreadyInChat 只能回答「本地会话里有没有这句」，而**用户自己打的字
     * 无论如何都会先落进本地会话** —— 本地有 ≠ 群里发过。离线 / rt == null 时那次群发送会被
     * 静默跳过，事后没有任何痕迹可查（真机反馈：手机离线期间错过的用户消息永远补不回来）。
     *
     * 为什么**落盘**：补发时机是「下次启动」与「重连 / 对端上线」，重启后必须仍然知道哪些发过 ——
     * 只放内存会在每次启动时把历史用户消息重发一遍。
     */
    private var groupForwardLedger: GroupForwardLedger? = null

    /**
     * 正在投递中的（会话 × 群 × 游标）。
     *
     * 为什么需要它：水位**只在 send 成功后才记**（记早了就永远补不回来），而补发触发点是
     * 「换网 / 对端上线」，会在上一次 send 还在飞时再跑一遍 ⇒ 同一个 (session, group, cursor)
     * 被选中两次 ⇒ **群里出现两条**。占位在选中前加、失败释放、成功由水位接管。
     */
    private val inFlightGroupForwards = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 事件路由诊断：只有类型/游标/长度/原因与计数，**没有任何正文**。 */
    val eventDiagnostics: EventRouterDiagnostics get() = eventForwarder.eventDiagnostics

    private companion object {
        /**
         * 运行时生命周期不暴露事件，只能轮询。1s 足够 —— 这个窗口只影响
         * 「重启后控制面多久恢复」，不影响正确性。
         */
        const val FOLLOW_POLL_MS = 1_000L

        /** 群没绑仓的如实回执：环境不可用，不是错误，更不是静默。 */
        const val NO_GIT_RECEIPT = "no git workspace bound to this group"

        /** 宿主没有可派单对象时的如实回执。 */
        const val NO_WORKER_RECEIPT = "no worker available on this host"
    }
}
