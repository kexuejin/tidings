// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 捎话 Tidings contributors

package app.relay.app.screenshot

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.relay.app.model.AgentApprovalUi
import app.relay.app.model.AgentEventUi
import app.relay.app.model.AgentQueueTaskUi
import app.relay.app.model.AgentSessionKey
import app.relay.app.model.AgentSessionUi
import app.relay.app.model.DeliveryState
import app.relay.app.model.MessageUi
import app.relay.app.model.NetworkUi
import app.relay.app.model.NodePhase
import app.relay.app.model.PeerUi
import app.relay.app.RelaySettings
import app.relay.app.RelayUiState
import app.relay.app.model.ScanProgressUi
import app.relay.app.model.ScreenSessionUiState
import app.relay.app.navBadges
import app.relay.core.protocol.ScreenEndReasons
import app.relay.core.protocol.ScreenMode
import app.relay.core.protocol.ScreenPhase
import app.relay.core.protocol.ScreenRole
import app.relay.core.protocol.ScreenSessionState
import app.relay.feature.agents.AgentConsoleScreen
import app.relay.navigation.AppNavItems
import app.relay.navigation.AppRoutes
import app.relay.platform.Transport
import app.relay.ui.components.CompactScaffold
import app.relay.ui.components.RemoteViewSessionDialog
import app.relay.ui.components.ScreenRequestConfirmDialog
import app.relay.ui.desktop.DesktopPreviewActions
import app.relay.ui.desktop.DesktopShell
import app.relay.ui.desktop.DesktopShellActions
import app.relay.ui.desktop.DesktopView
import app.relay.ui.desktop.PREVIEW_NOW as DESKTOP_PREVIEW_NOW
import app.relay.ui.desktop.desktopPreviewState
import app.relay.ui.desktop.linPeer
import app.relay.ui.theme.AppTheme
import com.android.tools.screenshot.PreviewTest

/**
 * W5 的截图门禁：Agent 控制台的**富数据态**与**加载态** + 屏幕协助的**错误态**（两个角色）。
 *
 * ## 为什么是这三件事
 *
 * 前三批帧拍的都是"某个状态槽被覆盖了没有"，本文件不同：它钉的两条状态槽
 * （`agents / loading`、`remote-view / error`）**此前代码里根本没有那一支**，
 * 是本轮补出来的。
 * 换句话说，这些图不是"补一张参考图"，而是"补出来的分支第一次被拍下来" ——
 * 如果哪天真被人删掉，参考图会消失，而不是继续静静地对着一屏别的东西。
 *
 * 另外两帧（`agents-approvals-queue`、`remote-view-viewing-connecting`）解决的是
 * **配对层对上了、但配上的是另一态** 这个问题：`m-agents` 原型拍的是"有审批、有队列、
 * 有会话"的常规态，而此前配给它的 `AgentsEmptyPhonePreview` 是空态 —— 名子能对上
 * 不代表拍的是同一个东西（`ScreenshotPreviewsStateSlots.kt` 文件头第 64-66 行记过同一形状的坑）。
 *
 * ## 时钟只有一个
 *
 * `PreviewNow` = 2025-01-01T00:00:00Z + 25min（UTC+8 是 08:25），与
 * `ScreenshotPreviews.kt` / `ScreenshotPreviewsStateSlots.kt` **同值**。选 08:00 作基准是
 * 为了让本文件能直接落回原型上的那几个时刻：原型 `m11-agents` 的任务队列写着
 * 14:30 / 13:55 / 13:10，那就是 `at(390)` / `at(355)` / `at(310)` —— 造假数据与原图
 * 对得上时刻，并排看的时候少一个"这只是随便填的"的疑点。
 *
 * 屏幕协助那几帧不显示任何时间，所以共用同一个时钟不产生副作用。
 *
 * ## 为什么自带夹具，而不是复用 `ScreenshotPreviewsStateSlots.kt` 的
 *
 * 那边的 `at()` / `selfSettings` / `codexAgent` 全是文件私有（跨文件拿不到），
 * 而它们的 `codexAgent` 昵称叫「codex · 终端」——本文件的 Agent 帧要并排的原型上写的是
 * 「teahouse-mobile / DSH」与「ci-runner / CI」。拿 `codexAgent` 去凑，等于用一份
 * **不是这张图要表达的数据**去对齐，图像对了、含义错了。所以这里按原型造一份最小的，
 * 每条都写了"为什么是这个值"。代价与那边衡量过的一样：有界，且只声明本文件会读的字段。
 */

/** 预览里的"现在"：08:25。传给屏幕的 `now` 参数，绝不读挂钟。 */


/** 本机身份。`nodeId` 只求是个稳定常量 —— 它不进画面。 */
private val selfSettings = RelaySettings(
    nodeId = "55555555-5555-4555-8555-555555555555",
    nick = "我的手机",
    company = "产品",
    dept = "客户端",
    team = "移动端",
    avatar = 3,
    profileRev = 2,
)

/** 节点在跑。`Stopped` 时 `AgentsScreen` 会先被"没启动"那层挡住，拍到的就不是本文件要钉的那一支。 */
private val runningNetwork = NetworkUi(
    phase = NodePhase.Running,
    transport = Transport.Wifi,
    localAddresses = listOf("192.168.1.42"),
    boundUdpPort = 17878,
)

/** 一个真人节点：证明"聊天消息"那条流里的对方是真人，不是空数据。 */
private val humanPeer = PeerUi(
    nodeId = "a1a1a1a1-1111-4111-8111-a1a1a1a1a1a1",
    nick = "阿 May",
    company = "产品",
    dept = "设计",
    avatar = 2,
    host = "May-MacBook",
    ip = "192.168.1.31",
    platform = "mac",
    tcpPort = 17879,
    caps = listOf("fd1"),
    online = true,
    lastSeenAt = at(2),
)

/**
 * 选中的那个 Agent：昵称/徽章与原型 `m11-agents` 的第一枚 chip 一致。
 *
 * `caps` 必须含 `"ag1"`：`PeerUi.isAgent` 看的就是它（`RelayUiState.kt:86`）。少这一个
 * 字符串，`state.agentPeers` 就是空表，整屏会走进空态并提前 `return@Column`，
 * 这两张 Agent 图就什么也钉不住了。
 */
private val teahouseAgent = PeerUi(
    nodeId = "7c3e91b5-1d4a-4f68-9b02-9d1e7c3e91b5",
    nick = "teahouse-mobile",
    company = "捎话",
    dept = "工具链",
    avatar = 0,
    host = "MacBook-Pro",
    ip = "192.168.1.23",
    platform = "macOS 15.3",
    tcpPort = 17879,
    caps = listOf("ag1", "tw1"),
    online = true,
    lastSeenAt = at(24),
)

/** 第二个 Agent：chip 行要有两枚才画得出来（`agents.size > 1` 才渲染那一行）。 */
private val ciAgent = PeerUi(
    nodeId = "5a1f2b3c-4d5e-4f60-8a71-2b3c4d5e6f70",
    nick = "ci-runner",
    company = "捎话",
    dept = "构建",
    avatar = 0,
    host = "ci.local",
    ip = "192.168.1.44",
    platform = "Ubuntu 24.04",
    tcpPort = 17879,
    caps = listOf("ag1", "tw1"),
    online = true,
    lastSeenAt = at(22),
)

// ── 富数据态：与原型 m11-agents 同形 ─────────────────────────────────────────

private const val RUN_SESSION = "s-run"
private const val BUILD_SESSION = "s-build"

/**
 * 常规态：两个 Agent、一个"进行中"的会话、三条审批（两条待处理 + 一条已批准）、三条队列任务。
 *
 * 数值都照原型 `m11-agents` 抄：chips「teahouse-mobile DSH 2 项」/「ci-runner CI 1 项」、
 * 审批节「3 项」、队列节「3 项」、事件流「4 条事件」。抄不是为了好看，是为了并排看的时候
 * **差异就是差异本身**，而不是"两边数据本来就不一样"。
 */
private fun agentsRichState(): RelayUiState = RelayUiState(
    settings = selfSettings,
    network = runningNetwork,
    peers = listOf(humanPeer, teahouseAgent, ciAgent),
    // 底部导航的徽标：原型上是「消息(3)」「Agent(1)」。三条未读 + 一条挂起，由 navBadges 算出来。
    unreadByPeer = mapOf(humanPeer.nodeId to 3),
    pendingByPeer = mapOf(teahouseAgent.nodeId to 1),
    messages = mapOf(
        teahouseAgent.nodeId to listOf(
            MessageUi(
                id = "ag-m1",
                peerId = teahouseAgent.nodeId,
                mine = true,
                text = "把移动端的门禁跑一遍，失败就把原因贴回来。",
                at = at(10),
                delivery = DeliveryState.Sent,
            ),
            MessageUi(
                id = "ag-m2",
                peerId = teahouseAgent.nodeId,
                mine = false,
                text = "六条门禁全绿：core / composeApp / agentNode 测试通过，APK 11.8 MB。",
                at = at(12),
            ),
            MessageUi(
                id = "ag-m3",
                peerId = teahouseAgent.nodeId,
                mine = true,
                text = "把 refactor runner 的八条臂跑完，失败的臂单独贴报告。",
                at = at(14),
                delivery = DeliveryState.Sent,
            ),
            MessageUi(
                id = "ag-m4",
                peerId = teahouseAgent.nodeId,
                mine = false,
                text = "第 3 条臂在 kv_keys 那一步断了，报告已附在事件流里。",
                at = at(18),
            ),
        ),
    ),
    agentSessions = mapOf(
        // 「进行中」= state "busy"，与原型会话行右侧的标签逐字一致。
        AgentSessionKey(teahouseAgent.nodeId, RUN_SESSION) to AgentSessionUi(
            peerId = teahouseAgent.nodeId,
            sessionId = RUN_SESSION,
            alias = "refactor runner",
            agentKind = "DSH",
            state = "busy",
            title = "把 runner 拆成八条臂，逐臂跑门禁",
            startedAt = at(4),
            updatedAt = at(24),
        ),
        // 第二个会话是**故意**的：`AgentSessionCard` 只在 `sessions.size > 1` 时才画那个
        // 展开箭头（`canSwitch`）。只给一个会话，原型上行尾的「⌄」在代码图里就不会出现，
        // 于是"有没有切换入口"这件事在两边看起来会不一样。
        AgentSessionKey(teahouseAgent.nodeId, BUILD_SESSION) to AgentSessionUi(
            peerId = teahouseAgent.nodeId,
            sessionId = BUILD_SESSION,
            alias = "build watcher",
            agentKind = "DSH",
            state = "idle",
            title = "守着 CI，构建红了就在聊天里说一声",
            startedAt = at(1),
            updatedAt = at(21),
        ),
    ),
    agentEvents = mapOf(
        AgentSessionKey(teahouseAgent.nodeId, RUN_SESSION) to listOf(
            AgentEventUi(
                sessionId = RUN_SESSION,
                peerId = teahouseAgent.nodeId,
                cursor = 1,
                eventType = "session.started",
                summary = "会话已宣告",
                at = at(4),
            ),
            AgentEventUi(
                sessionId = RUN_SESSION,
                peerId = teahouseAgent.nodeId,
                cursor = 2,
                eventType = "tool.requested",
                summary = "Read /Users/me/Desktop/secrets.env（含密钥）",
                body = "等待审批",
                at = at(9),
            ),
            AgentEventUi(
                sessionId = RUN_SESSION,
                peerId = teahouseAgent.nodeId,
                cursor = 3,
                eventType = "arm.failed",
                summary = "第 3 条臂：kv_keys 断言失败",
                at = at(20),
            ),
        ),
    ),
    agentApprovals = mapOf(
        // `expiresAt = 0L` 是必须的：`AgentApprovalCards.kt:60` 用 `nowMillis()`（**挂钟**）
        // 判过期，写一个真实时间戳会让这两张卡在某个时刻之后自己变成「已过期」，
        // 参考图随日历漂移。0 = 永不过期。
        "ap-read" to AgentApprovalUi(
            approvalId = "ap-read",
            sessionId = RUN_SESSION,
            peerId = teahouseAgent.nodeId,
            tool = "Read",
            summary = "读取 /Users/me/Desktop/secrets.env（含密钥）",
            expiresAt = 0L,
            status = "pending",
        ),
        "ap-bash" to AgentApprovalUi(
            approvalId = "ap-bash",
            sessionId = RUN_SESSION,
            peerId = teahouseAgent.nodeId,
            tool = "Bash",
            summary = "npm run deploy --prod",
            expiresAt = 0L,
            status = "pending",
        ),
        // 第三条是**已批准**：原型上它没有按钮、只有一个绿 chip —— 那一支（`status != "pending"`
        // 时按钮整排消失）删掉的话参考图会有反应，正是要钉的。
        "ap-write" to AgentApprovalUi(
            approvalId = "ap-write",
            sessionId = RUN_SESSION,
            peerId = teahouseAgent.nodeId,
            tool = "Write",
            summary = "把纪要草稿写进「本周纪要」笔记",
            expiresAt = 0L,
            status = "approved",
        ),
    ),
    agentTasks = mapOf(
        "q-7f3a91c2d4" to AgentQueueTaskUi(
            taskId = "q-7f3a91c2d4",
            sessionId = RUN_SESSION,
            peerId = teahouseAgent.nodeId,
            op = "整理",
            prompt = "整理今天 3 场会议的纪要",
            queuedAt = at(390),
        ),
        "q-2b81d0a9f6" to AgentQueueTaskUi(
            taskId = "q-2b81d0a9f6",
            sessionId = RUN_SESSION,
            peerId = teahouseAgent.nodeId,
            op = "生成",
            prompt = "生成本周进度周报",
            queuedAt = at(355),
        ),
        "q-9c04e7b3a5" to AgentQueueTaskUi(
            taskId = "q-9c04e7b3a5",
            sessionId = RUN_SESSION,
            peerId = teahouseAgent.nodeId,
            op = "同步",
            prompt = "把 design-spec.json 同步到知识库",
            queuedAt = at(310),
        ),
    ),
)

/**
 * 加载态：**一个 Agent 都还没发现，但局域网探测正在进行中**。
 *
 * 这一支是本轮补出来的：此前 `state.scanProgress != null` 时也走空态，等于在还没扫完的
 * 时候告诉用户"这里什么都没有，去配对"。判据、生产者链与消费点见
 * `AgentConsoleScreen.kt` 里 `AgentLoadingState` 的 KDoc。
 *
 * `scannedHosts = 37 / totalHosts = 254`：一个"扫到一半"的数，不是 0 也不是满 ——
 * 两端取值都会让这一帧看起来像另一种状态（0 像没开始、254 像已完成）。
 * `peers` 里留一个真人：真实的加载态不是"网络里什么都没有"，而是"还没发现 Agent"。
 */
private fun agentsLoadingState(): RelayUiState = RelayUiState(
    settings = selfSettings,
    network = runningNetwork,
    peers = listOf(humanPeer),
    scanProgress = ScanProgressUi(
        currentCidr = "192.168.1.0/24",
        scannedHosts = 37,
        totalHosts = 254,
        discoveredCount = 0,
    ),
)

/**
 * 真壳：底部导航、徽标都走应用自己的实现，不在预览里重算规则。
 *
 * **必须包 `AppTheme(darkTheme = false)`**：`CompactScaffold` 自己不套主题，漏掉这一层
 * 整帧会落回 Material3 基线调色板（紫 `#6750A4`），而不是 Tidings 主色绿 `#2D6A4F`
 * —— 这个错**只有打开图才看得出来**：配对判定与差集都不读颜色。
 */
@Composable
private fun AgentsPhoneShell(
    state: RelayUiState,
    content: @Composable (Modifier) -> Unit,
) {
    AppTheme(darkTheme = false) {
        CompactScaffold(
            selectedScreenId = AppRoutes.AGENTS,
            navItems = AppNavItems,
            onScreenSelected = {},
            badges = navBadges(state),
        ) { inner ->
            content(inner)
        }
    }
}

/**
 * Agent 控制台 —— **局域网探测进行中、还没有 Agent** 的那一支（状态槽 `agents / loading`）。
 *
 * 这是本轮补齐的代码分支，不是补的图：`AgentConsoleScreen` 原先在 `agentPeers` 为空时
 * 无条件画 `EmptyState`（「还没有 Agent 节点」+ 去配对），而 `state.scanProgress` 非空
 * 表示后台正在扫 —— 两者撞上时用户被指向一个错误的结论。
 *
 * `onRespondApproval` / `onCancelTask` 按生产调用点武装（手机 `App.kt` 的
 * `onCancelTask = controller::cancelAgentTask`、桌面壳 `DesktopShell` 的
 * `DesktopView.Agents` 分支同名实参）：
 * 这两个参数是可空闸门，传 `null` 时 `批准`/`拒绝`/`取消` 三颗按钮**整段不渲染**
 * （`AgentApprovalCards.kt` 的 `status == "pending" && !expired && onRespond != null`）。
 * 本帧这一支（`agentPeers` 为空）走加载分支、看不到队列，但门禁扫的是"夹具有没有传真"，
 * 且探测中恰是最该能取消任务的时刻，所以照样武装。
 */
@PreviewTest
@Preview(name = "agents-loading", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun AgentsLoadingPhonePreview() {
    val state = agentsLoadingState()
    AgentsPhoneShell(state) { inner ->
        AgentConsoleScreen(
            state = state,
            selectedAgentId = null,
            onSelectAgent = {},
            onOpenChat = { _, _ -> },
            onFindNodes = {},
            onRespondApproval = { _, _ -> },
            onCancelTask = {},
            onRefresh = {},
            onOpenSessionDetail = null,
            onBackToBoard = null,
            // 按生产调用点（App.kt 的 AgentConsoleScreen 调用）逐参武装：这五个可空出口在生产侧都是
            // 非 null 闭包，夹具不传就等于依赖它的那一支根本不渲染 —— 截图证据里也就没有它。
            onOpenWorkspaceDetail = { _, _ -> },
            onLeaveWorkspaceDetail = {},
            onLeaveSessionDetail = {},
            onLoadWorkspaceSessions = { _, _ -> },
            onCreateWorkspaceGroup = {},
            modifier = inner,
            now = PREVIEW_NOW,
            )
    }
}

/**
 * Agent 控制台 —— 常规态：会话进行中 + 待处理审批 + 任务队列（对应原型 `m11-agents`）。
 *
 * 为什么必须有这一帧：此前配给 `m11-agents` 的是 `AgentsEmptyPhonePreview`（空态）与
 * `AgentsLongTextPhonePreview`（长文本态），**没有一个帧画过审批卡与队列行**。
 * 也就是说这两节的代码整段删掉，配对报告一行都不会变色。
 *
 * 夹具刻意的选择（每一处都影响这张图要表达的东西）：
 * - 审批给两条 `pending` + 一条 `approved`：把"按钮排"与"绿色 chip"两支同时拍进一帧；
 * - 队列给三条：`SectionHeader` 右上角的计数只有 `> 1` 才看得出是计数而不是装饰；
 * - 会话给两个：`canSwitch` 才会画展开箭头（原型行尾那个「⌄」）；
 * - `onRespondApproval` / `onCancelTask` **传了非 null**：`批准`/`拒绝`/`取消` 按钮
 *   只在回调存在时渲染，传 `null` 拍出来的是一屏"只读"的界面，与安装包里看到的不一样。
 */
@PreviewTest
@Preview(name = "agents-approvals-queue", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun AgentsApprovalsQueuePhonePreview() {
    val state = agentsRichState()
    AgentsPhoneShell(state) { inner ->
        AgentConsoleScreen(
            state = state,
            selectedAgentId = teahouseAgent.nodeId,
            onSelectAgent = {},
            // 显式指到 run 会话：默认兜底会取列表第一个，将来夹具长到三个会话时这张图的含义会被静默改掉。
            selectedAgentSessionId = RUN_SESSION,
            onSelectAgentSession = {},
            onOpenChat = { _, _ -> },
            onRespondApproval = { _, _ -> },
            onCancelTask = {},
            onFindNodes = {},
            onRefresh = {},
            onOpenSessionDetail = null,
            onBackToBoard = null,
            // 按生产调用点（App.kt 的 AgentConsoleScreen 调用）逐参武装：这五个可空出口在生产侧都是
            // 非 null 闭包，夹具不传就等于依赖它的那一支根本不渲染 —— 截图证据里也就没有它。
            onOpenWorkspaceDetail = { _, _ -> },
            onLeaveWorkspaceDetail = {},
            onLeaveSessionDetail = {},
            onLoadWorkspaceSessions = { _, _ -> },
            onCreateWorkspaceGroup = {},
            modifier = inner,
            now = PREVIEW_NOW,
        )
    }
}

// ── 屏幕协助：错误态（两个角色）─────────────────────────────────────────────

private fun screenSession(
    role: ScreenRole,
    phase: ScreenPhase,
    reason: String? = null,
): ScreenSessionState = ScreenSessionState(
    sessionId = "s-preview",
    peerId = linPeer().nodeId,
    peerName = linPeer().nick,
    peerIp = linPeer().ip,
    role = role,
    phase = phase,
    requestedAt = PREVIEW_NOW - 30_000L,
    startedAt = PREVIEW_NOW - 20_000L,
    reason = reason,
)

@Composable
private fun DialogBackdrop(content: @Composable () -> Unit) {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            content()
        }
    }
}

/**
 * 屏幕协助 —— **看别人屏幕的人**遇到中断（状态槽 `remote-view / error`）。
 *
 * `ScreenPhase` 本轮才第一次有 `Error` 成员：此前 `finishScreenSession(reason)` 把
 * **所有**失败原因也一律写成 `Ended`，而 UI 侧 `if (session.isEnded) return` 直接让
 * 弹窗消失 —— 失败时会话窗口不见了，没有一句话说为什么。
 *
 * `reason = "protocol-error"`：这是传输层会真的发出来的动态码之一
 * （`ScreenStream.kt:170/:295/:310/:319/:325`），也正是此前**没有任何本地化文案**的那一个
 * （`screenReasonText` 的 `else -> reason` 会把它原样印在界面上，本轮一并补全）。
 */
@PreviewTest
@Preview(name = "remote-view-error-viewer", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun RemoteViewErrorViewerPhonePreview() {
    DialogBackdrop {
        RemoteViewSessionDialog(
            state = ScreenSessionUiState(
                session = screenSession(ScreenRole.Viewer, ScreenPhase.Error, ScreenEndReasons.PROTOCOL_ERROR),
            ),
            onAccept = {},
            onReject = {},
            onStop = {},
            onSetMode = { _: ScreenMode -> },
        )
    }
}

/**
 * 屏幕协助 —— **正在共享屏幕的人**遇到中断（同一状态槽的另一个角色）。
 *
 * 与上一帧同一分支、只差 `role`：错误卡片在 Viewer 支是**整屏黑底 + 居中卡片**
 * （否则用户会透过遮罩看见后面那屏黑画面），在 Sharer 支是常规对话框。
 * 两种底衬都在这一对帧里，改坏了其中一种，两张图之一会有反应。
 *
 * `reason = "capture-ended"`：采集自己停掉的结束码，同样是此前会漏出英文协议 token 的一个。
 */
@PreviewTest
@Preview(name = "remote-view-error-sharer", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun RemoteViewErrorSharerPhonePreview() {
    DialogBackdrop {
        RemoteViewSessionDialog(
            state = ScreenSessionUiState(
                session = screenSession(ScreenRole.Sharer, ScreenPhase.Error, ScreenEndReasons.CAPTURE_ENDED),
            ),
            onAccept = {},
            onReject = {},
            onStop = {},
            onSetMode = {},
        )
    }
}

/**
 * 屏幕协助 —— **看别人屏幕的人**，还在连接中。
 *
 * 这一帧钉的是一句话的归属：`Connecting` 此前与 `Requesting` 共用
 * 「等待对方同意屏幕协助…」，而请求早就发出去了。原型上这是两句不同的话
 * （`d30-reqscreen` 讲"等对方同意"、`d32-viewscreen` 的画面区讲"正在连接屏幕…"），
 * 所以 `Connecting` 走 `screen_remote_view_connecting`、其余相位仍是等同意。
 *
 * 与 `RemoteViewViewingPhonePreview`（已连上、纯黑有画面区）成对：同为 Viewer，
 * 差在 `phase`，一眼能看出"还没连上"与"已连上"不是同一块界面。
 */
@PreviewTest
@Preview(name = "remote-view-viewing-connecting", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun RemoteViewViewingConnectingPhonePreview() {
    DialogBackdrop {
        RemoteViewSessionDialog(
            state = ScreenSessionUiState(
                session = screenSession(ScreenRole.Viewer, ScreenPhase.Connecting),
            ),
            onAccept = {},
            onReject = {},
            onStop = {},
            onSetMode = { _: ScreenMode -> },
        )
    }
}

/**
 * 屏幕协助 —— **刚发出请求、还没被答复**（对应原型 `d30-reqscreen`）。
 *
 * 这张图是**证据，不是配对通过**：原型 `d30-reqscreen` 画的是一个浮层小表单
 * （标题「请求查看屏幕」+ 一段"你只能查看画面、双方随时可结束"的说明 + 取消 / 发送请求），
 * 而代码里根本没有这一步 —— `ChatScreen` 头部那个投屏按钮 `onClick` 直接调
 * `controller.requestScreenView(peerId)`（`ChatScreen.kt:798`、`DesktopConversationsPane.kt:522`），
 * 点了就发出去。所以本帧拍到的**只能**是这个发出去之后的等待界面。
 * 那个缺口写在配对报告的 `d-reqscreen` 一节，不在这一帧里糊过去。
 *
 * 名字的副作用，先说清楚：状态槽词表只看**函数名**，`RemoteView*PhonePreview` 都会匹配
 * `remote-view / normal` 那条正则，所以本帧会把 `normal` 槽多算一条 —— 而它的真实相位是
 * `Requesting`。这正是 `ScreenshotPreviewsStateSlots.kt` 文件头记过的"名字匹配上了、
 * 匹配的不是同一个东西"。标出来，而不是让它静默虚高：收窄词表是量具那边的活。
 */
@PreviewTest
@Preview(name = "remote-view-requesting", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun RemoteViewRequestingPhonePreview() {
    DialogBackdrop {
        RemoteViewSessionDialog(
            state = ScreenSessionUiState(
                session = screenSession(ScreenRole.Viewer, ScreenPhase.Requesting),
            ),
            onAccept = {},
            onReject = {},
            onStop = {},
            onSetMode = { _: ScreenMode -> },
        )
    }
}

// ────────────────────────────────── 桌面宿主形态（Lead 审计单 task-8）

/**
 * 宿主底衬 —— 桌面/平板**壳级**帧的外壳。
 *
 * 与 [DialogBackdrop] 是两件事，别互相顶替：那个套的是"只有一个对话框"的裸底，
 * 这个套的是"整壳 + 壳最外层的覆盖层"。第一行都必须是 [AppTheme]：壳**假设自己被
 * 主题包着**，漏了整壳落到 M3 基线紫（头像 / 徽标 / 按钮全变默认紫），而
 * `--precheck`、`--dup`、配对判定**全部是绿的**，只有开图看得见 —— 简报 §9 第 39 行
 * 专门点过这一类，我上一轮（`AgentsPhoneShell` 忘套主题）与 W4 各栽过一次。
 *
 * 为什么复制而不复用 W4 的 `private fun PaneBackdrop`：它是
 * `ScreenshotPreviewsDesktopPanes.kt` 的**文件私有**顶层函数，跨文件拿不到；
 * 语义完全一致（同样的 `AppTheme` + `Surface(surface, fillMaxSize)`）。
 */


/**
 * 桌面壳 + 一个由喂入相位决定的屏幕协助覆盖层（三张宿主帧共用）。
 *
 * 依据是 `DesktopShell.kt:547` 逐字把 `RemoteViewSessionDialog(state = state.screenSession, …)`
 * 画在**壳的最外层**（`:555` 是文件末行）⇒ 桌面形态**本来就存在**，这三张帧之前缺的是证据，
 * 不是实现。尺寸取同族桌面帧的 1100×720。
 *
 * 三个"不传就会变味"的形参，照 W4 `DeskHomeMoreTabletHostPreview` 的判据抄：
 * · `now` 必须固定：相对时间（`最近在线`/`N 分钟前`）否则每天都在漂。这里用**桌面夹具的**
 *   `DESKTOP_PREVIEW_NOW`（1_789_000_000_000L）而不是本文件手机夹具的 `PREVIEW_NOW` ——
 *   壳里的数据全部相对前者偏移，混用会让壳上出现"x 天前"这种假读数。
 * · `initialView` 走壳的**冷启动初值**（默认就是 `Conversations`，这里显式写出来）；
 *   外部请求计数器走 `LaunchedEffect`，静态帧在副作用之前就抓完了。
 * · `controlPlanePort = 0`：预览没有真控制面端口，显示 0 而不是编一个。
 *
 * 对话框那四个回调都传**非空** lambda：`onAccept`/`onReject` 不传则同意页两颗按钮整段不画
 * （生产 `DesktopShell.kt:549-552` 四条全传）—— 预览里的空 lambda 就是"点了没反应"，
 * 但**有按钮**才是产品形状。
 */
@Composable
private fun DeskShellWithScreenSession(session: ScreenSessionState) {
    DesktopShell(
        state = desktopPreviewState().copy(
            screenSession = ScreenSessionUiState(session = session),
        ),
        controller = DesktopPreviewActions,
        windowWidthDp = 1100,
        windowHeightDp = 720,
        darkTheme = false,
        controlPlanePort = 0,
        initialView = DesktopView.Conversations,
        initialPeerId = null,
        now = DESKTOP_PREVIEW_NOW,
        actions = DesktopShellActions(
            onOpenDownloadsFolder = {},
            onOpenPeerWindow = {},
        ),
    )
}

/**
 * 桌面宿主 · 屏幕协助 **请求已发出、等对方答复**（原型 shot `d30-reqscreen`，2304×1600）。
 *
 * 对应原型那个 `floatwin('', …)` 独立浮窗（`prototype/relay.html:4620 screenDReqScreen`）。
 * **它拍的不是原型那一屏**，这一点必须写在报告里而不是糊过去：原型画的是"发请求**之前**的
 * 确认小表单"（标题「请求查看屏幕」+ 说明 banner + 取消 / 发送请求）。那一屏由
 * `DeskReqScreenConfirmPreview`（`@Preview(name = "desk-reqscreen-confirm")`）单独钉住；
 * 本帧能钉住的是**请求发出之后**的等待面（Viewer + `Requesting`）。
 */
@PreviewTest
@Preview(name = "desk-reqscreen-host", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskReqScreenHostPreview() {
    PreviewBackdrop {
        DeskShellWithScreenSession(screenSession(ScreenRole.Viewer, ScreenPhase.Requesting))
    }
}

/**
 * 桌面宿主 · 屏幕协助 **对方想看你屏幕、待你同意**（原型 shot `d31-sharescreen`，2304×1600）。
 *
 * 对应 `prototype/relay.html:4627 screenDShareScreen`。Sharer + `AwaitingConsent` 是
 * 同意页**唯一**能画出两颗按钮（拒绝 / 同意共享）的相位 —— 这正是把四个回调都传非空的意义。
 */
@PreviewTest
@Preview(name = "desk-sharescreen-host", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskShareScreenHostPreview() {
    PreviewBackdrop {
        DeskShellWithScreenSession(screenSession(ScreenRole.Sharer, ScreenPhase.AwaitingConsent))
    }
}

/**
 * 桌面宿主 · 屏幕协助 **正在看对方屏幕**（原型 shot `d32-viewscreen`，2304×1600）。
 *
 * 对应 `prototype/relay.html:4651 screenDViewScreen`。取 `Active` 而不是 `Connecting`：
 * 这张帧要钉的是"已经在看"时桌面上是什么样 —— 整屏黑 + 顶部半透明控制条
 * **两行**（第一行：标题 / 目标帧率 / 清晰度 / 关闭；第二行：适应窗口·100% 分段 / 结束查看，
 * 即原型那个 `.rvseg` + `.rvstop`）。画面区是**纯黑**，因为静态夹具没有真帧数据
 * （`latestFrameBytes = null`），**不编一张假屏幕图**；原型画面区那句
 * 「正在连接屏幕…」属于 `Connecting` 相位，已由手机帧
 * `RemoteViewViewingConnectingPhonePreview` 钉住（见报告 §4.3）。
 *
 * 不证明：模式下拉展开后的四项（`DropdownMenu` 是 Popup，Layoutlib 只合成容器不合成内容）、
 * 以及「适应窗口 / 100%」按下去之后画面真的变了的**交互**（帧只能证明控件在那里、默认选中
 * 的是「适应窗口」）。
 */
@PreviewTest
@Preview(name = "desk-viewscreen-host", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskViewScreenHostPreview() {
    PreviewBackdrop {
        DeskShellWithScreenSession(screenSession(ScreenRole.Viewer, ScreenPhase.Active))
    }
}

/**
 * 桌面宿主 · 屏幕协助 **请求确认表单**（原型 shot `d30-reqscreen`，2304×1600）。
 *
 * 这是 `d30-reqscreen` 那一屏**真正的**内容：`prototype/relay.html:4620 screenDReqScreen` 画的是
 * 发请求**之前**的确认卡 —— 标题「请求查看屏幕」+ 一句说明（"对方同意并选择屏幕后才会开始
 * 共享。你只能查看画面，无法操作对方电脑；双方都可以随时结束。"）+「取消」+「发送请求」。
 * 代码在这一轮之前**没有这一步**（聊天头部那颗图标按钮的 onClick 直接调
 * `controller.requestScreenView(peerId)`，点一下请求就出门了），所以这一屏的内容此前在
 * 树里根本不存在 —— 本帧钉的是补上之后的样子。
 *
 * 证明：这张卡的四件东西都在、文案逐字对得上、两颗按钮的层级是「取消」在上「发送请求」在下。
 * 不证明：它从哪儿弹出来（真机上由 `ChatScreen.kt:872` 那颗 `ic_screen_share` 图标按钮 /
 * 桌面 `DesktopConversationsPane.kt:571` 的头部动作触发）、点外部是否关闭、返回键行为。
 * 也不证明"发送请求"之后真的发出去了 —— `onSend` 在帧里传的是空 lambda。
 */
@PreviewTest
@Preview(name = "desk-reqscreen-confirm", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskReqScreenConfirmPreview() {
    PreviewBackdrop {
        ScreenRequestConfirmDialog(onCancel = {}, onSend = {})
    }
}
