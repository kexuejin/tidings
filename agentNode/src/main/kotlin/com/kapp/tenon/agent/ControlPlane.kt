// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

import com.kapp.tenon.agent.plugins.PluginHost
import com.kapp.tenon.agent.plugins.PluginRegistry
import com.kapp.tenon.agent.plugins.PluginStateManager
import com.kapp.tenon.agent.plugins.PluginTokenGate
import com.kapp.tenon.agent.plugins.SourceWebhookRegistry
import com.kapp.tenon.agent.plugins.sourceWebhook
import com.kapp.tenon.core.protocol.defaultApprovalChoicesCsv
import com.kapp.tenon.core.protocol.AgentGrantScopes
import com.kapp.tenon.core.protocol.AgentChannelPayload
import com.kapp.tenon.core.protocol.CodecInvoke
import com.kapp.tenon.core.protocol.Codec
import com.kapp.tenon.core.protocol.Envelope
import com.kapp.tenon.core.protocol.MsgTypes
import com.kapp.tenon.core.protocol.Limits
import com.kapp.tenon.core.session.NodeRuntime
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.Executors

/** 取 JsonElement 里的字符串内容；不是字符串就 null（不抛）。 */
internal fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? =
    (this as? JsonPrimitive)?.takeIf { it.isString }

/**
 * agentNode 的 localhost HTTP 控制面。
 *
 * 存在的理由是**隔离**：宿主 agent 插件 / Codex / Claude 只发 HTTP，解耦底层点对点通信的
 * UDP/TCP 协议、不碰 nodeId、不碰握手与 ACK。协议的一切复杂度留在 [NodeRuntime] 里，
 * 于是给 agent 的工具描述可以简单到"POST 一个 to 和一个 text"。
 *
 * 安全模型（默认按最保守解释）：只绑 `127.0.0.1`，不监听任何外部网卡。默认**不要求**
 * token —— 回环上的进程本来就与你有同等权限，加一个默认 token 只会变成"必须手写配置
 * 文件"（用户明确拒绝过这种形态）。一旦 `--token` 或 `RELAY_AGENT_TOKEN` 提供了值，
 * 则每个请求都必须带 `Authorization: Bearer <token>` 或 `X-Tenon-Token: <token>`。
 *
 * 路由：
 *  - `GET  /`                 用法说明（给人看的）
 *  - `GET  /healthz`          存活 + nodeId + 端口 + 节点数 + 待补发数
 *  - `GET  /v1/peers`         节点表快照
 *  - `POST /v1/send`          `{"to":"<IP 或 nodeId>","text":"…"}`
 *  - `POST /v1/send-file`     `{"to":"…","path":"<本机绝对路径>","name":"…","purpose":"share",`
 *    `"groupId":"…","groupRev":7,"tableText":"…"}`（后三个可选；群引用成对出现 = 群聊媒体投递，
 *    `tableText` = 表格图片的文字视图）
 *  - `POST /v1/nudge`         `{"to":"…"}`（戳一碰：只回 ACK 或 unreachable，**不排队**）
 *  - `POST /v1/agent/session` `{"to":"…","sessionId":"…","alias":"…","state":"busy"}`（PPE-1 §6.1）
 *  - `POST /v1/agent/event`   `{"to":"…","sessionId":"…","cursor":42,"eventType":"tool.started"}`（§6.2）
 *  - `GET  /v1/inbox?since=`  跨会话增量拉取（`/v1/messages` 为同义路由）
 *  - `GET  /v1/host/directories`     宿主本地项目目录快照（档案 + 群指针 + 白名单）
 *  - `POST /v1/host/directories`     `{"path":"…","displayName":"…","gitRemote":"…"}` 登记项目目录（幂等）
 *  - `POST /v1/host/group-directory` `{"groupId":"…","dirId":"…"}` 设置/清除群项目指针
 *  - `POST /v1/host/whitelist`       `{"dirId":"…","allowed":true,"readOnly":false}` 放行/收回目录
 *
 * 两条 agent 路由受 §4 约束：**对方没声明 `agn1` 就不发**，返回 409 并建议改用 `/v1/send`。
 *
 * @param httpPort 监听端口；传 0 表示由系统分配，实际端口读 [boundPort]。
 * @param token    空字符串 = 回环放行。
 */
class ControlPlane(
    internal val runtime: NodeRuntime,
    internal val httpPort: Int,
    internal val token: String,
    /** 宿主负载探针（T-G7）：null/缺省 = 真实 CPU 探针；测试注入固定值。 */
    internal val loadProbe: (() -> Double?)? = { com.kapp.tenon.agent.HostLoadGuard.systemCpuLoad() },
    /** CLI 驱动注册表（E1）：@claude/@codex/@antigravity 的群内派单执行桥。 */
    internal val cliDrivers: com.kapp.tenon.agent.drivers.DriverRegistry? = null,
    internal val startedAt: Long = System.currentTimeMillis(),
    /**
     * 公网入口（隧道暴露的控制面地址），空串 = 没配 ⇒ 配对二维码不生成。
     *
     * 只影响 `GET /v1/pairing-qr` 一条路由。
     */
    internal val publicUrl: String = "",
    /**
     * agent 会话台账。控制面在这里记下**发出**的 agent 帧，`GET /v1/agent/sessions`
     * 从它读 —— 因为 agent 帧既不进 `messageStore`（出站不写）也不该混进 `/v1/inbox`（噪声），
     * 没有这个台账，面板就问不到「本机有哪些 agent 会话」。见 [AgentSessionRegistry] 的 KDoc。
     *
     * 由 `Main` 建好传进来（而不是在这里 `= AgentSessionRegistry()`），因为**入站**的
     * agent 帧是 `Main` 里那条 `runtime.inbound` 订阅写的：两边必须是同一个实例，
     * 否则收发的会话各记一份，面板只能看到一半。
     */
    internal val sessions: AgentSessionRegistry,
    /**
     * 工作区台账（PPE-1 增补 D3）。与 [sessions] 同一条「两边必须同一实例」的纪律：
     * 出站由本控制面写，入站由宿主进程订阅写。
     */
    internal val workspaces: AgentWorkspaceRegistry = AgentWorkspaceRegistry(),
    /**
     * 授权策略（PPE-1 增补 D7）。**执行点在这里**：投递前按策略过滤，
     * 「没授权」才等于「没数据」（父稿 §3.3）。决定在桌面端（面板经
     * `GET/PUT /v1/agent/grants` 写），这里只拦。
     */
    internal val grants: AgentGrantStore = AgentGrantStore(defaultGrantFile()),
    /**
     * 「这个 tool 以后免审」的表（按 **tool 名**粒度，不是整 agent）。
     *
     * 它只在 `POST /v1/agent/approval` 的**入口**被查一次：命中就不再打扰对端，
     * 直接记一条 `approved` 并回 `status=auto-approved`。规则由对端点「始终允许」
     * （`agent-ack.kind = always`）产生，见 [AgentSessionRegistry.approvalPolicy]。
     */
    internal val toolApprovals: AgentToolApprovalStore = AgentToolApprovalStore(defaultToolApprovalFile()),
    /**
     * 宿主本地的项目目录档案 + 群指针 + 白名单（`host-directories.json`）。装配方传
     * **同一实例**（也接到域层的 `HostDirectoryPort`）：面板与 HTTP 路由必须同锁同落盘
     * （与 [sessions] / [toolApprovals] 同一条「两边必须同一实例」的纪律）。
     */
    internal val hostDirectoryStore: HostDirectoryStore = HostDirectoryStore(HostDirectoryStore.defaultFile()),
    /** F4（C3）：`sessionId → convId` 宿主本地台账（落盘，重启仍在）。 */
    internal val conversationLedger: SessionConversationLedger = SessionConversationLedger(SessionConversationLedger.defaultFile()),
    /** PPE-1 增补 D8：设备发来的会话分页请求，留给宿主答（见 AgentSessionPageStore 的 KDoc）。 */
    internal val sessionPages: AgentSessionPageStore = AgentSessionPageStore(),
    /**
     * `/v1/inbox` 的注入判定附加信息。null = 不提供（纯聊天用途）。判据只有一份、住在
     * composeApp（要 `settings.groups`），agentNode 拿不到 —— 见 [AgentInboxEnricher]。
     */
    internal val inboxEnricher: AgentInboxEnricher? = null,
    /** 注入判定的**最近读数**（「接入与通道」页唯一数据源）。本控制面独占一份，装配方只读转交界面端口；
     * 两份实例的症状是「桥在干活、页面说没判定过」。 */
    val inboxTrace: InboxInjectionTrace = InboxInjectionTrace(),
    /**
     * **每个 agent 各自的执行范围**（key = agentKey）。落盘保存，见 [AgentExecPolicyStore]。
     *
     * ## 为什么按 agent 而不是按宿主
     *
     * 一台机器可以同时装 dsh 与 codex（`RelayUiState.agentKindsByHost` 就是「宿主 → 多个 kind」）。
     * 宿主级一份配置做不到「允许群主用 dsh、但不允许用 codex」。
     *
     * ## 缺省 = 最严
     *
     * 表里没有的 agent 一律 `HOST_ONLY`（由 store 兜底）。新装的 agent 因此自动安全。
     */
    internal val execPolicies: AgentExecPolicyStore = AgentExecPolicyStore(File(defaultGrantFile().parentFile, "agent-exec-policies.json")),
    /**
     * 已注册插件台账（M2 宿主网关）。注册请求来自插件进程（回环上的另一个进程），
     * 台账在宿主这一侧；GET /v1/plugins 读它，驱动与派单路径也用它。
     */
    val plugins: PluginRegistry = PluginRegistry(),
    /**
     * 插件注册的 token 门闸（**强制**，方案 §2.3）。默认来源 = 环境变量
     * `TENON_PLUGIN_TOKEN`，其次数据目录下 0600 的 `plugins/plugin-token`；
     * 两者都没有时注册端点整体关闸（fail-closed）。它与控制面自身的 [token] 是
     * 两把不同的锁：插件注册只认插件 token，其余路由只认 [token]。
     */
    val pluginTokenGate: PluginTokenGate = PluginTokenGate.fromEnvironment(),
    val sourceWebhooks: SourceWebhookRegistry = SourceWebhookRegistry(),
    /**
     * 触发器回投会话的存在性校验器（提案 §5.2，宿主级事实）。
     *
     * desktopApp/DesktopExtension 装配域层后接上（本机 conversations 判定）；
     * null = 未接线 ⇒ 含 `triggers` 且带 `targetConvId` 的清单**拒绝注册**
     * （fail-closed：收下无法验证的回投目标等于把结果卡投进一个没人看见的会话）。
     */
    var triggerTargetConvKnown: ((convId: String) -> Boolean)? = null,
    /**
     * 跨端派单的宿主侧委托（M4）。见 [AgentInvokeDispatcher] 的 KDoc：
     * desktopApp 装配域层后接上；agentNode 单进程保持 null ⇒ 503。
     */
    var invokeDispatcher: AgentInvokeDispatcher? = null,
    /**
     * M7-T2（D-016 后半步）：Channel 委派的**接收宿主侧编排器**。null = 本机不承接
     * 委派（agentNode 单进程测试 / 未装配的宿主）—— 入站帧如实答 no-executor，
     * 不静默吞（AGENTS §3：_declare 了就要能答_）。
     *
     * 由装配方在 [start] 之前传入；入站分派与 HTTP 受理判定都读它。
     */
    val channelOrchestrator: ChannelOrchestrator? = null,
    /**
     * 单向责任链的执行器（`docs/DYNAMIC-MULTI-AGENT-TEAM-ORCHESTRATION.md` §2）。
     *
     * 与 [invokeDispatcher] 同一个接缝纪律：执行体在壳层（要 git、要群、要卡片广播），
     * agentNode 单进程保持 null ⇒ `/v1/agent/chain` 如实回 503，不假装接了单。
     * 写成 `var` 是因为壳里要拿 `created.executeCliTask` —— 它得等 ControlPlane 造完才存在。
     */
    var chainDispatcher: AgentChainDispatcher? = null,
    /** 群卡片广播委托（壳层注入）：用于 ask_question 等需要向协同群广播结构化卡片的长轮询交互。 */
    var groupCardPublisher: ((groupId: String, agentId: String, cardJson: String) -> Unit)? = null,
    val pluginStateManager: PluginStateManager = PluginStateManager(defaultGrantFile().parentFile),
    val pluginsDir: File = File(defaultGrantFile().parentFile, "plugins"),
    var pluginHostRef: (() -> PluginHost?)? = null,
    /**
     * 员工档案写面（P3 工具面）。null = 未装配 ⇒ `/v1/agent/tools` 如实回 503。
     *
     * 端口在本层只定义、由 `:orchestration` 装配（它握着 `RelayController`）。写成 `var`
     * 与 [chainDispatcher] 同一条理由：壳里要等控制面造完才能把实现接上。
     */
    var rosterWriter: RosterWritePort? = null,
    /**
     * agent 写档位（`request` / `auto`）。**只读文件、没有 HTTP 写入口**：
     * 拿到控制面 token 的本地进程也包括 agent 自己，给它一条写路由等于让它给自己放行。
     */
    val rosterWritePolicy: RosterWritePolicy = RosterWritePolicy(File(defaultGrantFile().parentFile, "roster-write.json")),
    /**
     * 工具面审批的等待上限（默认与 hook 同一档 `Limits.APPROVAL_TTL_MS`）。
     *
     * 可注入只为一件事：**超时 = 拒绝** 这条判据要能被测到。真等 120 秒的用例没人跑，
     * 而不测它，「超时会不会放过」就永远只是注释里的一句话。
     */
    val rosterApprovalTtlMs: Long = Limits.APPROVAL_TTL_MS,
) {

    // ------------------------------------------------------------------ 状态
    //
    // **全部**可变状态都在这一段（构造参数 + 这里声明的字段）。路由文件只读，不存 ——
    // `ControlPlaneRouteSplitTest.routeFilesDeclareNoState` 钉着这一条。理由不是洁癖：
    // `init` 装的授权门禁、`stop()` 收的监听与探测、每个 store 的落盘时机都发生在本文件，
    // 状态一旦散进各资源文件，"谁在什么时候被装、被清"就没有单一答案了。

    internal val json = Json { ignoreUnknownKeys = true }

    private var server: HttpServer? = null

    /**
     * 插件事件的**每会话游标**（sessionId → 递增计数）。台账按游标排序、同游标就地
     * 替换 —— 每次派单从 1 重数会把上一次的事件覆盖掉。键随插件会话数增长，
     * 与台账（AgentSessionRegistry）同生命周期、同量级。
     */
    internal val pluginEventCursors = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()

    /**
     * 只读通道的**执行前**工具白名单（sessionId → 判据），提案 §7.1 第 3 条约束。
     *
     * 判据由派单侧（`:orchestration` 的 `TriggerExecutor`）以纯函数传进来 —— 白名单
     * 本体在 `:app` 的 `CognitiveFlow`，`:agentNode` 不依赖它。见 [ReadOnlyToolPolicy]。
     *
     * 为什么要执行前的判据：`tool.started` 事件是「已经开始执行」的通知，不是「请求
     * 批准」。只靠事件判定，越界的那个调用**已经落地**了，能做的只有事后判负。装了
     * hook 的 CLI 会先 POST `/v1/agent/hook/approval`，那条路径才有机会拒绝 ——
     * 判据就挂在这里，由 `ControlPlaneHookRoutes` 的审批路由读。
     *
     * 生命周期严格跟着一次派单：`executeCliTask` 起进程**之前**登记、`finally` 里清掉。
     * 不清就会把「只读」泄漏给同 id 的下一次任务。
     */
    internal val readOnlyToolPolicies =
        java.util.concurrent.ConcurrentHashMap<String, ReadOnlyToolPolicy>()

    init {
        // 审批回执的**授权门禁**（fail-closed）：宿主在这里自己装判据，而不是相信客户端。
        // 台账由外部建好传进来（桌面端内嵌控制面与独立进程共用同一个实例），门禁装在这一层，
        // 两种进程模型才不可能各判一套。
        sessions.approvalAuthority = approvalAuthorityFor(grants, sessions)
        // 「始终允许」的落地：一条 `agent-ack.kind = always` 被应用之后才记规则。
        // 装在**应用回执的唯一那一处**（applyAckToApproval 的钩子），而不是在这里再判一次
        // `kind == always` —— 那样"要不要记"就有了两个判据。
        sessions.approvalPolicy = { record, agentKey ->
            record.tool?.let { tool ->
                toolApprovals.remember(
                    nodeId = record.nodeId,
                    agentKey = agentKey,
                    // F3：免批带授予来源会话的 ws 维度（null 只命中 null）。
                    wsId = record.sessionId?.let { sessions.sessionOrNull(record.nodeId, it)?.wsId },
                    tool = tool,
                    grantedBy = record.nodeId,
                    at = record.updatedAt,
                )
            }
        }
    }

    /** 实际监听端口。`httpPort` 传 0 时在 [start] 之后才有效。 */
    var boundPort: Int = httpPort
        private set

    /**
     * 「始终允许」免批表（给宿主自己的设置面板读/撤销用）。
     *
     * 暴露的是**同一个实例**，不是一份副本：面板上看到的那几行必须就是
     * [agentApproval] 入口判定时查的那张表。另起一个 `AgentToolApprovalStore`
     * 指同一个文件也不保险 —— 两个实例各有各的内存与锁，面板撤掉的规则要等
     * 下一次落盘才被入口看见（中间那段时间里"已撤销"是假的）。
     */
    val toolApprovalStore: AgentToolApprovalStore
        get() = toolApprovals

    /**
     * 是否注册仅开发可用的路由（默认关：生产路径不该有它）。 */
    internal val devRoutesEnabled: Boolean =
        System.getenv("TENON_DEV_ROUTES")?.let { it == "1" || it.equals("true", ignoreCase = true) } == true

    /** 解析失败（3 秒握手没等到）的短期记忆：key = "to[:port]"，value = 到期时刻。 */
    internal val resolveFailures = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * 项目目录 → agent 工作区的解析器（决策稿 §3.1）。判据只有这一份：执行点问的是它，
     * 不许各自手写一遍「dirId → wsId」。records 每次现取 [workspaces] 的**本机**行 ——
     * 工作区台账是当前态视图（随重挂重建），解析器不许持有台账快照。
     */
    internal val directoryResolver = ProjectDirectoryResolver(
        selfNodeId = runtime.nodeId,
        records = { workspaces.workspaces().filter { it.nodeId == runtime.nodeId } },
    )


    /**
     * 插件**没声明** `choices` 时，宿主替它填的那一份：这台宿主能兑现的全部处置。
     *
     * ## 为什么这个默认值归宿主，不归插件
     *
     * `choices` 是「**对端可以怎么答**」，而能不能兑现某一档是**宿主**的事实 ——
     * `always` 要落进宿主的免批表（`AgentToolApprovalStore`）、`allow_session` 要走
     * `applyAckToApproval`，插件两样都不掌握。插件不声明时留空，等于让对端只看得见
     * 「批准 / 拒绝」，一条它其实能兑现的路永远没有入口（现场：手机端「始终允许」
     * 的按钮画得出来、却因为没人声明而从不出现）。
     *
     * ## 为什么四个值都写进来，而不是只写协议文档那三个
     *
     * 文档 §6.5 的值域是 `allow,deny` / `allow,deny,always`；`allow_session` 是本端增补的
     * 第五个回执取值，宿主同样兑现。少写它，对端就会以为只能整条批准或整个工具批准，
     * 中间那一档（只管这个会话）凭空消失。
     *
     * 插件**显式声明**时一律以插件为准（含它只想给两档的情况）：那是它的决定权，不是我们的。
     *
     * CSV 本体收编进 core 的 `defaultApprovalChoicesCsv()`（S4-2f 收口）：前两档改说
     * **生态词** `allow,deny` —— 此前用回执词汇 `approved,denied`，照文档读的第三方会把
     * 宿主的明确声明读成沉默。取值单一来源，agentNode 不再自己拼。
     */
    internal val defaultApprovalChoices: String = defaultApprovalChoicesCsv()

    /** conversationId 键 → 待注入消息（FIFO，封顶 50 条防涨潮）。 */
    internal val hookInjectPool = java.util.concurrent.ConcurrentHashMap<String, MutableList<String>>()

    /** E2E 派单的执行 scope（HTTP 线程不能被沙箱任务占住；独立于调用方）。 */
    internal val dispatchScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
    )

    /** wsPath → 最近一次宣告它的 hook 会话键（feed 方按工作区投递的别名解析）。 */
    internal val hookWsAlias = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** hook 卡片的会话内游标（同一会话多张卡按序推进，面板按游标排序）。 */
    internal val hookCardCursors = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()

    /**
     * 实时增量流（T-G3）：delta 类事件的**进程内**管道 —— 「不落库」的可见性由它
     * 承担（桌面 Inspector 订阅）。replay=0：后来者不看历史（历史在 result 卡与
     * 台账的终局事件里），extraBufferCapacity 给流式峰值留缓冲，满了丢最旧语义
     * 由 tryEmit 的调用方容忍（日志丢行 ≠ 状态丢帧）。
     */
    val liveAgentEvents = kotlinx.coroutines.flow.MutableSharedFlow<LiveAgentEvent>(
        replay = 0, extraBufferCapacity = 1024,
    )

    // -------------------------------------------------------------- 生命周期

    fun start() {
        if (server != null) return
        val s = HttpServer.create(InetSocketAddress(LOOPBACK, httpPort), 16)
        // **读路由必须永远有空线程**（task-35 真机）。
        //
        // 现场：手机切到移动网后，本机 127.0.0.1:8788 连续 5 次 HTTP 000、每次卡满 8.00s，
        // 切回 WiFi 立刻恢复。8.00s 正是"给一个不可达对端发消息"的完整退避（UDP 1+2+4 秒 +
        // TCP 兜底），而这些写路由的处理器是同步函数 —— 一个线程被按住整整 8 秒。
        // 池子原来只有 4 条，几条这样的请求就能把 **/v1/peers** 一起饿死，而那正是观测跨网
        // 状态、判断"长连接有没有建起来"的唯一手段（"暂不可读"就是这么来的）。
        //
        // 这里给 16 条：并发阻塞写最多几条，读路由仍然拿得到线程。回归测试见
        // ControlPlaneTest.peersStaysFastEvenWhenBlockingSendsFillTheHandlerPool。
        // **虚拟线程（JDK 21+）优先**：处理器里有十几处要按住线程等协程（发消息的退避、
        // 按主机名解析对端要等 3 秒握手）。固定池再大也是有限资源 —— 真机现场
        // `jcmd Thread.print` 里 16 条 `pool-1-thread` 全卡在解析对端上，于是连 `/healthz`
        // 与 `/v1/peers` 都打不开，"控制面整个挂了"。虚拟线程让"阻塞"只花一条虚拟线程，
        // 读路由永远还有空。没有虚拟线程（JDK < 21）就退回固定池 —— 行为与加这段之前一致。
        //
        // 每一处按住都经 `CarrierHold.hold(标签, 预算)`：语义不变（返回值/异常原样透传），
        // 但超过预算会在 stdout 留一行 `[RELAY-HOLD]`，不用再靠真机复现才知道是哪一处慢了。
        s.executor = virtualThreadExecutor() ?: Executors.newFixedThreadPool(HANDLER_THREADS)
        s.createContext("/") { exchange -> handle(exchange) }
        s.start()
        server = s
        boundPort = s.address.port
        // 插件健康探测随控制面起停：控制面活着，探测就活着；stop() 之后不再探测。
        plugins.startMonitoring()
    }

    /**
     * JDK 21+ 的「每个任务一条虚拟线程」执行器；没有就返回 null（调用方退回固定池）。
     *
     * 反射取：这个模块要兼顾没有该 API 的 JDK，编译期直接引用会让老 JDK 连加载都
     * 失败 —— 而控制面是 agent 宿主的心脏，不许因为"想用新 API"整个起不来。
     */
    private fun virtualThreadExecutor(): java.util.concurrent.Executor? = runCatching {
        java.util.concurrent.Executors::class.java
            .getMethod("newVirtualThreadPerTaskExecutor")
            .invoke(null) as java.util.concurrent.Executor
    }.getOrNull()

    fun stop() {
        plugins.stopMonitoring()
        sourceWebhooks.dispose()
        server?.stop(0)
        server = null
    }

    /**
     * 订阅本运行时的入站流，把三类 agent 帧记进三张台账（会话 / 工作区 / 授权待办）。
     *
     * 存在的理由与 `recordInbound` 同一条：解析规则只有这一份。`Main`（agentNode 进程）、
     * 桌面端内嵌控制面（`DesktopExtension`）与本类的测试都调它 —— 谁自己手写一段
     * `runtime.inbound.collect { ... }`，谁就会在下一帧类型加入时漏接一个分支
     * （症状都是"宣告发了、面板看不到"，且只在真机上暴露）。
     *
     * 授权申请的回包（`agent-grant-list`）也在这里：设备问一次，宿主答一次当前清单，
     * 让手机端四档状态里「已授权」与「未申请」可区分（父稿 §3.3：沉默 ≠ 拒绝）。
     * 回包失败不影响记账 —— 台账是本机事实，对方下次申请还会拿到清单。
     */
    fun observeInbound(scope: CoroutineScope): Job = scope.launch {
            runtime.inbound.collect { envelope ->
                sessions.recordInbound(envelope)
                workspaces.recordInboundWorkspace(envelope)
                // 会话历史在宿主侧，节点答不了：只排队，等宿主来读（见该 store 的 KDoc）。
                sessionPages.recordInbound(envelope)
                // M7-T2：agent-channel **不在这里直通编排器**。决策包 §1.6 的入站授权
                // （AgentExecAuthorizer：群成员身份 + 可信设备）判据住在域层，agentNode
                // 只有设备级 grants —— 直通等于绕过那道门。唯一入口是 [channelAccepted]，
                // 由域层在授权通过后调用；没有域层的帧由域层的诚实拒绝兜底（no-executor）。
                if (grants.recordInboundGrant(envelope)) {
                    val snap = grants.snapshot()
                    // 能发来 grant-request 就说明对方认识 grant 帧（cap 门控在它的发送侧做），
                    // 这里直接回当前清单：沉默 ≠ 拒绝，四档状态必须可区分（父稿 §3.3）。
                    // 一条都没授权过时回一条 total=0 的清单 —— 对方据此显示「未申请/待批」
                    // 而不是空列表（空列表分不清"没权限"与"没数据"）。
                    runCatching {
                        val mine = snap.grants.filter { it.nodeId == envelope.from }
                        if (mine.isEmpty()) {
                            runtime.sendAgentGrantList(envelope.from, AgentGrantScopes.ALL, null, null, null, 0)
                        } else {
                            mine.forEach { g ->
                                runtime.sendAgentGrantList(
                                    // total 是**给这台设备看的**清单长度（对方据此显示「共 N 条」）。
                                    // 传整份台账的 size 会把别台设备的授权算进来 ——
                                    // 同胞 /v1/agent/sessions 的 total 用的就是本设备的行数。
                                    peerId = envelope.from,
                                    scope = g.scope,
                                    agentKey = g.agentKey,
                                    wsId = g.wsId,
                                    access = g.access,
                                    total = mine.size.toLong(),
                                )
                            }
                        }
                    }
                }
                observeInboundToolRules(envelope)
            }
        }


    /**
     * 把一条入站 `agent-channel` 交给编排器；拆不出来的帧如实回 no-executor。
     *
     * 为什么不在这里做授权：决策包 §1.6 说入站过 `AgentExecAuthorizer`，但那道门的
     * 判据（群元数据 / virtualAgents / 可信设备台账）住在**域层**；agentNode 只有
     * grants 白名单（对设备，不对发起 agent）。桌面壳装配时把 [channelAccepted]
     * 接到域层分流（授权判据复用 invokeCtl 那一套）；agentNode 单进程没有域层 ⇒
     * [channelOrchestrator] 为 null，一律诚实拒绝 —— 与 /v1/agent/invoke 的 503
     * 同一条「不假装接了单」的纪律。
     */
    private suspend fun offerToChannelOrchestrator(envelope: com.kapp.tenon.core.protocol.Envelope) {
        val orchestrator = channelOrchestrator
        val payload = (envelope.payload as? kotlinx.serialization.json.JsonObject)
            ?.let { runCatching { Codec.wireJson.decodeFromJsonElement(AgentChannelPayload.serializer(), it) }.getOrNull() }
        if (orchestrator == null || payload == null) {
            // 帧已过 Codec 校验才可能到这儿；拆不出来 = 内部错。即便如此也要答：
            // 发起方在等一帧结果，沉默会让它白等到租约超时。
            val channelId = (envelope.payload as? kotlinx.serialization.json.JsonObject)
                ?.get("channelId")?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content
                ?: return
            runCatching {
                runtime.sendAgentChannelResult(
                    peerId = envelope.from,
                    channelId = channelId,
                    ok = false,
                    status = CodecInvoke.InvokeResultStates.NO_EXECUTOR,
                    error = "this host has no channel orchestrator",
                )
            }
            return
        }
        orchestrator.offer(payload, envelope.from)
    }

    /**
     * 域层分流判定（桌面壳装配用）：这台宿主此刻**愿不愿意承接**这条委派？
     *
     * 返回 true = 编排器已受理（幂等/互斥/租约/回执都归它）；false = 调用方按
     * 自己的判据回 no-executor。授权判据在调用方（域层才有群元数据），编排器
     * 只答「本机有没有承接能力」。null = 本机没有编排器。
     */
    fun channelAccepted(payload: AgentChannelPayload, fromNode: String): Boolean {
        val orchestrator = channelOrchestrator ?: return false
        orchestrator.offer(payload, fromNode)
        return true
    }
    // ---------------------------------------------------------------- routing

    private fun handle(exchange: HttpExchange) {
        try {
            val path = exchange.requestURI.path.trimEnd('/').ifEmpty { "/" }
            val method = exchange.requestMethod.uppercase()
            // `/healthz` 的 CORS 预检必须在**鉴权之前**答：预检请求按规范不带 Authorization，
            // 放在 authorized 之后会被打成 401，浏览器那边表现为「跨域被拒」。
            if (path == "/healthz" && method == "OPTIONS") {
                allowLoopbackCors(exchange)
                exchange.responseHeaders.add("Access-Control-Allow-Methods", "GET, OPTIONS")
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
                return
            }
            // 插件注册两条路由**不走** authorized()：它们认的是插件 token（强制），
            // 不是控制面自身的 [token]。放在鉴权之前，两个 token 体系互不牵连。
            if (path == "/v1/plugin/register" && method == "POST") {
                pluginRegister(exchange)
                return
            }
            if (path == "/v1/plugin/unregister" && method == "POST") {
                pluginUnregister(exchange)
                return
            }
            if (path.startsWith("/v1/webhook/") && method == "POST") {
                sourceWebhook(exchange, path.removePrefix("/v1/webhook/"))
                return
            }
            if (!authorized(exchange)) {
                respond(exchange, 401, errorJson("unauthorized", "缺少或错误的 token"))
                return
            }
            when {
                path == "/" && method == "GET" -> respond(exchange, 200, helpJson())
                path == "/healthz" && method == "GET" -> {
                    allowLoopbackCors(exchange)
                    respond(exchange, 200, healthzJson())
                }
                path == "/v1/pairing-qr" && method == "GET" -> pairingQr(exchange)
                path == "/v1/peers" && method == "GET" -> respond(exchange, 200, peersJson())
                path == "/v1/send" && method == "POST" -> send(exchange)
                path == "/v1/send-file" && method == "POST" -> sendFile(exchange)
                path == "/v1/transfers" && method == "GET" -> transfers(exchange)
                path == "/v1/nudge" && method == "POST" -> nudge(exchange)
                // 仅开发可用（TENON_DEV_ROUTES=1）：在没有相机/触控的环境（iOS 模拟器、
                // 集成测试）里完成「扫码后向对端发配对自证」这一步。前提是 pin 与
                // pairingSecrets 已由扫码流程写进宿主状态 —— 它只负责发帧，不建立信任。
                path == "/v1/dev/pair" && method == "POST" && devRoutesEnabled -> devPair(exchange)
                path == "/v1/agent/session" && method == "POST" -> agentSession(exchange)
                path == "/v1/agent/event" && method == "POST" -> agentEvent(exchange)
                path == "/v1/agent/approval" && method == "POST" -> agentApproval(exchange)
                path == "/v1/agent/hook/session" && method == "POST" -> agentHookSession(exchange)
                path == "/v1/agent/hook/approval" && method == "POST" -> agentHookApproval(exchange)
                path == "/v1/agent/hook/inject" && method == "POST" -> agentHookInject(exchange)
                path == "/v1/agent/hook/inject-feed" && method == "POST" -> agentHookInjectFeed(exchange)
                path == "/v1/agent/hook/card" && method == "POST" -> agentHookCard(exchange)
                path == "/v1/agent/hook/question" && method == "POST" -> agentHookQuestion(exchange)
                path == "/v1/agent/hook/dispatch" && method == "POST" -> agentHookDispatch(exchange)
                path == "/v1/agent/sessions" && method == "GET" -> agentSessions(exchange)
                path == "/v1/agent/workspace" && method == "POST" -> agentWorkspace(exchange)
                path == "/v1/agent/workspaces" && method == "GET" -> agentWorkspaces(exchange)
                path == "/v1/agent/session-pages" && method == "GET" -> agentSessionPages(exchange)
                path == "/v1/agent/session-pages" && method == "POST" -> createAgentSessionPage(exchange)
                path == "/v1/agent/session-pages/resolve" && method == "POST" -> resolveAgentSessionPage(exchange)
                path == "/v1/agent/grants" && method == "GET" -> readGrants(exchange)
                path == "/v1/agent/grants" && method == "PUT" -> writeGrants(exchange)
                // agent 工具面（P3）：目录读这里，写动作走 POST；写档位只读文件（见 invokeTool 的说明）。
                path == "/v1/agent/tools" && method == "GET" -> listTools(exchange)
                path == "/v1/agent/tools" && method == "POST" -> invokeTool(exchange)
                path == "/v1/agent/tools/employees" && method == "GET" -> listToolEmployees(exchange)
                // 已注册插件台账（只读；宿主进程与面板看「装了哪些插件、各在线与否」）。
                path == "/v1/plugins" && method == "GET" -> pluginsList(exchange)
                path == "/v1/plugin/toggle" && method == "POST" -> pluginToggle(exchange)
                path == "/v1/plugin/states" && method == "GET" -> pluginStates(exchange)
                path == "/v1/plugin/install" && method == "POST" -> pluginInstall(exchange)
                path == "/v1/plugin/uninstall" && method == "POST" -> pluginUninstall(exchange)
                path == "/v1/plugin/settings" && method == "POST" -> pluginSaveSettings(exchange)
                // 宿主侧派单/操作/点查：插件网关的执行入口（Layer 3 与群聊桥都走这里）。
                path == "/v1/plugin/task" && method == "POST" -> pluginDispatch(exchange)
                path == "/v1/plugin/action" && method == "POST" -> pluginAction(exchange)
                // 宿主本地项目目录（决策稿 §5.1）：目录档案 / 群指针 / 白名单，只服务本机面板。
                path == "/v1/host/directories" && method == "GET" -> hostDirectories(exchange)
                path == "/v1/host/directories" && method == "POST" -> hostDirectoryRegister(exchange)
                path == "/v1/host/group-directory" && method == "POST" -> hostGroupDirectory(exchange)
                path == "/v1/host/whitelist" && method == "POST" -> hostWhitelist(exchange)
                // 跨端派单（M4，规格 §6.2）：HTTP 入口只解包，派发委托给域层网道（§6.2 裁决：
                // 与 /v1/agent/run 的单向宣告帧是两种职责，详见追踪器的路由对账记录）。
                path == "/v1/agent/invoke" && method == "POST" -> agentInvoke(exchange)
                // M7-T2（决策包 §1.6）：Channel 委派的 HTTP 入口 —— 发起侧的门在这里：
                // 对端没声明 agc1 ⇒ 409，帧根本不构造（发送侧不产出注定被拒的帧）。
                path == "/v1/agent/channel" && method == "POST" -> agentChannel(exchange)
                // 单向责任链（DYNAMIC-MULTI-AGENT-TEAM-ORCHESTRATION §2）：一次请求跑完
                // 「原型 → 开发 → 验收」，每棒独立沙箱、棒间以提交交接。与 /v1/agent/invoke
                // （单棒派单）是两种粒度：那条一次只跑一棒，这条一次跑一条链。
                path == "/v1/agent/chain" && method == "POST" -> agentChain(exchange)
                (path == "/v1/inbox" || path == "/v1/messages") && method == "GET" -> inbox(exchange)
                else -> respond(
                    exchange,
                    404,
                    errorJson("not-found", "未知路由 $method $path（GET / 看用法）"),
                )
            }
        } catch (t: Throwable) {
            // 已经回过包再抛（例如客户端提前断开）不应把整个 handler 线程带走。
            runCatching {
                respond(exchange, 500, errorJson("internal", t.message ?: t.toString()))
            }
        } finally {
            runCatching { exchange.close() }
        }
    }

    private fun authorized(exchange: HttpExchange): Boolean {
        if (token.isEmpty()) return true
        val bearer = exchange.requestHeaders.getFirst("Authorization")
        if (bearer != null && bearer.removePrefix("Bearer ").trim() == token) return true
        return exchange.requestHeaders.getFirst("X-Tenon-Token") == token
    }
    // ---------------------------------------------------------------- helpers


    internal fun respond(exchange: HttpExchange, code: Int, body: JsonElement) {
        val bytes = body.toString().encodeToByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    internal fun errorJson(code: String, message: String): JsonObject = buildJsonObject {
        put("ok", false)
        put("error", code)
        put("message", message)
    }

    internal fun readJsonObject(exchange: HttpExchange): JsonObject? {
        val raw = exchange.requestBody.readBytes().decodeToString()
        val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        if (obj == null) {
            respond(exchange, 400, errorJson("bad-json", "请求体必须是 JSON 对象"))
            return null
        }
        return obj
    }

    /**
     * 取一个 JSON **数字**（不是"能被解析成数字的字符串"）。
     *
     * `jsonPrimitive.longOrNull` 会把 `"42"` 也读成 42，而 `Codec` 对 `cursor` 这类字段要求
     * 真整数 —— 两边不一致就会出现"HTTP 收了、帧却被自检拒收"。这里先排除字符串。
     */
    /** 布尔值：字符串与 null 一律不算（与 [numberOrNull] 同纪律）。 */
    internal fun boolOrNull(element: JsonElement?): Boolean? {
        val primitive = element?.jsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.booleanOrNull
    }

    internal fun numberOrNull(element: JsonElement?): Long? {
        val primitive = element?.jsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.longOrNull
    }

    internal fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        return raw.split('&').mapNotNull { pair ->
            if (pair.isEmpty()) return@mapNotNull null
            val eq = pair.indexOf('=')
            if (eq < 0) {
                decode(pair) to ""
            } else {
                decode(pair.substring(0, eq)) to decode(pair.substring(eq + 1))
            }
        }.toMap()
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    companion object {
        /** 跨端派单的缺省期限（HTTP 入口未指定时）；与域层缺省同一数值。 */
        const val DEFAULT_INVOKE_DEADLINE_MS: Long = 10L * 60_000
        const val LOOPBACK: String = "127.0.0.1"
        const val DEFAULT_HTTP_PORT: Int = 8788

        /**
         * 处理器线程数（task-35）。
         *
         * 为什么不是 4：写路由要按住线程等协程，最长一次是"给不可达对端发消息"的完整
         * 退避（约 8 秒）。池子太小时，几条并发写就能把读路由（/v1/peers）饿死 —— 真机上表现为
         * 回环控制面连续 8 秒超时、诊断手段被自己挡住。
         */
        private const val HANDLER_THREADS: Int = 16

        /** 「解析不到这个对端」的记忆时长：短到对端一上线就能重新解析，长到不会每次重试都付 3 秒。 */
        internal const val RESOLVE_FAILURE_TTL_MS: Long = 5_000L

        /** 失败记忆的容量上限（超过就顺手清一次过期项）。 */
        internal const val RESOLVE_FAILURE_MAX: Int = 256

        /** `/v1/inbox` 没给 `limit` 时的缺省条数。 */
        internal const val DEFAULT_LIMIT: Int = 200

        /** `/v1/inbox` 单次最多返回多少条（再大就该分页了）。 */
        internal const val MAX_LIMIT: Int = 1000

        /**
         * 这个对端**是否已经迁到 luciola**（有它自己的发现/信道能力位）。
         *
         * 判据只看能力位而不是版本号：能力位是被对端**主动声明**的，而版本号字符串
         * 各家写法不一（relay-0.1.0 / 0.61.1 …）。没有这个位 = 老端。
         */
        internal fun posesLuciolaCaps(profile: com.kapp.tenon.core.protocol.Profile): Boolean =
            profile.caps.contains(MsgTypes.CAP_LINK) &&
                profile.caps.contains(MsgTypes.CAP_FILE_CHANNEL)

        /**
         * 授权台账的默认落点（只在调用方没显式传 [AgentGrantStore] 时用到）。
         *
         * 曾经落在 `java.io.tmpdir`：那是**重启即失**的目录，而这份文件记的是人点过的
         * 「哪台设备可以看我的 agent」。丢了它，下次启动 [AgentGrantStore.load] 走
         * 「文件不在」分支、策略退回出厂 `auto`（全放行）—— 一个能自己消失的授权台账
         * 比没有台账更坏，因为它看起来像配置过。现在跟桌面端同一套规矩，落到用户数据目录。
         *
         * 注意：**正常路径不经过这里**。桌面端在 `DesktopExtension` 里显式传
         * `dataDirectory/agent-grants.json`；这个默认值只服务于不传 grants 的调用方
         * （测试、以及将来可能有的独立 CLI）。
         */
        internal fun defaultGrantFile(): File =
            File(com.kapp.tenon.agent.plugins.HostDirs.default(), "agent-grants.json")

        /**
         * 「始终允许」免批表的默认落点。
         *
         * 与 [defaultGrantFile] 同一套规矩（用户数据目录，不是 `tmpdir`）：这份表记的是
         * 人点过的"这个工具以后别问我了"，重启即失等于每次启动都重新问一遍 ——
         * 而"忘了"的方向是收紧（多问一次），不会放行，所以这里丢文件的代价比授权台账小，
         * 但同样没有理由让它落在一个会消失的目录里。
         */
        internal fun defaultToolApprovalFile(): File =
            File(com.kapp.tenon.agent.plugins.HostDirs.default(), "agent-tool-approvals.json")

        /**
         * 项目目录档案的默认落点（与 [defaultGrantFile] 同一套规矩：用户数据目录）。
         * 白名单为空的档案效果是全拒；落在重启即失的目录里等于每次启动都全拒，用户会
         * 以为设置丢了。**正常路径不经过这里**（桌面端显式传
         * `dataDirectory/host-directories.json`），默认值只服务于测试与独立 CLI。
         */
    }
}