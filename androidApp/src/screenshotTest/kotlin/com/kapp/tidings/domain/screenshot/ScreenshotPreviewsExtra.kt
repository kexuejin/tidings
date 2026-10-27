// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.awaitCancellation
import com.kapp.tidings.domain.model.AgentEventUi
import com.kapp.tidings.domain.model.AgentSessionKey
import com.kapp.tidings.domain.model.AgentSessionUi
import com.kapp.tidings.domain.BannerMessage
import com.kapp.tidings.domain.BannerTone
import com.kapp.tidings.domain.BannerUi
import com.kapp.tidings.domain.model.DeliveryState
import com.kapp.tidings.domain.model.FileCardUi
import com.kapp.tidings.domain.ManualPeerEntry
import com.kapp.tidings.domain.MessageSearchResult
import com.kapp.tidings.domain.model.MessageUi
import com.kapp.tidings.domain.model.NetworkUi
import com.kapp.tidings.domain.model.NoticeKind
import com.kapp.tidings.domain.model.NodePhase
import com.kapp.tidings.domain.model.PeerUi
import com.kapp.tidings.domain.RelaySettings
import com.kapp.tidings.domain.RelayUiState
import com.kapp.tidings.domain.model.TransferState
import com.kapp.tidings.core.protocol.ConvId
import com.kapp.tidings.core.protocol.GroupMeta
import com.kapp.tidings.domain.navBadges
import com.kapp.tidings.feature.agents.AgentConsoleScreen
import com.kapp.tidings.feature.chat.ChatScreen
import com.kapp.tidings.feature.chat.ChatScreenChrome
import com.kapp.tidings.feature.chat.ChatScreenState
import com.kapp.tidings.feature.conversations.ConversationsScreen
import com.kapp.tidings.feature.nodes.NodesScreen
import com.kapp.tidings.feature.scan.ScanScreen
import com.kapp.tidings.feature.settings.SettingsAppearSubpage
import com.kapp.tidings.feature.settings.SettingsCapsSubpage
import com.kapp.tidings.feature.settings.SettingsDataSubpage
import com.kapp.tidings.feature.settings.SettingsDiagSubpage
import com.kapp.tidings.feature.settings.SettingsNotifySubpage
import com.kapp.tidings.feature.settings.SettingsScreen
import com.kapp.tidings.feature.settings.THEME_MODE_SYSTEM
import com.kapp.tidings.navigation.AppNavItems
import com.kapp.tidings.navigation.AppRoutes
import com.kapp.tidings.platform.Transport
import com.kapp.tidings.ui.components.CompactScaffold
import com.kapp.tidings.ui.theme.AppTheme
import com.android.tools.screenshot.PreviewTest

/**
 * 截图门禁的第二批：补齐「代码已实现但没有参考图」的状态槽。
 *
 * 与 [ScreenshotPreviews] 同 package、同一套规则（见那个文件头的说明），这里只重复必要的那几条：
 *
 * - 每个预览都渲染**真正被安装包调用**的屏幕组合函数，并套一层真正的 [CompactScaffold]；
 * - 时间全部由固定常量算出来：走 `relativeTime` 的字段拿固定的"现在"，
 *   走 `formatClock` 的字段拿固定时间戳（它内部是 `((ms / 60000) + 8 * 60) % 1440` 的纯算术，
 *   不读系统时区，所以这台机器和那台机器录出来的图是同一张）；
 * - 假数据全部在本文件内自造，不引用 desktop 模块的 `PreviewState.kt`。
 *
 * ## 为什么是另一个文件
 *
 * 因为 `ScreenshotPreviews.kt` 里的 fixture 工厂（`Screen`、`at`、`desktop`、`codexAgent` …）
 * 全是 `private` 顶层声明 —— Kotlin 的 `private` 顶层是**文件私有**，跨文件不可见。
 * 所以本文件自带一份 `ExtraScreen` 包装器与自己的假数据。
 * 这不是重复，而是文件私有的必然结果；把两个文件合成一个才是正解，但那会牵动
 * 13 张既有参考图的文件名（参考图路径里带生成类名 `ScreenshotPreviewsKt/`），本轮不动。
 *
 * 例外是两个**跨文件共用**的 fixture：[offlineAgent] 与 [agentWithOfflineSelectedState] 标成
 * `internal` —— 平板壳的 Agent 帧（`ScreenshotPreviewsTablet.kt`）要用同一份假数据。
 *
 * 参考图会落在 `reference/app/relay/app/screenshot/ScreenshotPreviewsExtraKt/` —— 与旧目录并列。
 */

/**
 * 固定基准时刻：2026-09-10T00:26:40Z（`formatClock` 按 UTC+8 显示为 08:26）。
 *
 * 取一个整点之外的数不是笔误 —— 它顺便证明参考图不依赖"整点"这种巧合，
 * 也不依赖 JVM 的默认时区。参考图可复现的真正前提是这两个函数都不读挂钟。
 */

/** 预览里的"现在"，传给屏幕的 `now` 参数，绝不读挂钟。 */

/** 相对基准时刻偏移若干分钟；负数表示基准之前。 */

private val selfSettings = RelaySettings(
    nodeId = "7f7f7f7f-0000-4000-8000-7f7f7f7f7f7f",
    nick = "我的手机",
    company = "产品",
    dept = "客户端",
    team = "移动端",
    avatar = 3,
    profileRev = 2,
)

private val runningNetwork = NetworkUi(
    phase = NodePhase.Running,
    transport = Transport.Wifi,
    localAddresses = listOf("192.168.1.57"),
    vpnActive = false,
    boundUdpPort = 17878,
    mdnsAdvertised = true,
)

/**
 * 真人节点一个：在线、有部门信息、有未读。
 * 只用来证明"有人但没有 Agent"这件事本身是真的，不是没数据。
 */
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
    lastSeenAt = atOdd(2),
)

private val otherHumanPeer = PeerUi(
    nodeId = "b2b2b2b2-2222-4222-8222-b2b2b2b2b2b2",
    nick = "小林",
    company = "产品",
    dept = "客户端",
    avatar = 4,
    host = "Xiaolin-MBP",
    ip = "192.168.1.32",
    platform = "mac",
    tcpPort = 17879,
    caps = listOf("fd1"),
    online = false,
    lastSeenAt = atOdd(-40),
)

/** 在线的 Agent：只在"选中的是离线 Agent"那张图里当对照。 */
private val codexAgent = PeerUi(
    nodeId = "c3c3c3c3-3333-4333-8333-c3c3c3c3c3c3",
    nick = "codex · 终端",
    host = "MacBook-Pro",
    ip = "192.168.1.23",
    platform = "linux",
    tcpPort = 17879,
    caps = listOf("ag1", "tw1"),
    online = true,
    lastSeenAt = atOdd(3),
)

/** 离线的 Agent：被选中的那一个。`lastSeenAt` 在"现在"之前 35 分钟 ⇒ 相对时间恒为「35 分钟前」。
 *
 * `internal` 而不是 `private`：平板壳的 Agent 帧（`ScreenshotPreviewsTablet.kt` 的
 * `tablet-agents-land*`）要用的就是**同一份** fixture —— 两边各造一份假数据，
 * 迟早一份改了另一份没改，而参考图看起来都"对"。 */
internal val offlineAgent = PeerUi(
    nodeId = "d4d4d4d4-4444-4444-8444-d4d4d4d4d4d4",
    nick = "claude · 文档",
    company = "研发",
    dept = "工具链",
    host = "MacBook-Pro",
    ip = "192.168.1.23",
    platform = "linux",
    tcpPort = 17880,
    caps = listOf("ag1"),
    online = false,
    lastSeenAt = atOdd(-10),
)

/**
 * 与离线 Agent 的会话：最后一条是**已排队**。
 *
 * 排队而不是已送达，因为对端离线时 core 只会把消息压进补发队列 ——
 * 造一条"离线却已送达"的消息，等于让参考图替产品说谎。
 */
private val offlineAgentThread = listOf(
    MessageUi(
        id = "o1",
        peerId = offlineAgent.nodeId,
        mine = true,
        text = "把这次改动的门禁跑一遍，失败就把原因贴回来。",
        at = atOdd(6),
        delivery = DeliveryState.Sent,
    ),
    MessageUi(
        id = "o2",
        peerId = offlineAgent.nodeId,
        mine = false,
        text = "好，我这边收到就去看。",
        at = atOdd(8),
    ),
    MessageUi(
        id = "o3",
        peerId = offlineAgent.nodeId,
        mine = true,
        text = "这条是它下线之后发的，应该留在补发队列里。",
        at = atOdd(9),
        delivery = DeliveryState.Queued,
    ),
)

/**
 * 在线 Agent + 两个结构化会话（PPE-1 `agent-session` / `agent-event`，接收侧接线的证据帧）。
 *
 * 控制台里除了聊天气泡，还多出一段「事件流」：会话卡（别名、状态、开始时间、事件条数）
 * 与最近几条事件。这条路径以前完全没有参考图 —— 接完线不录一张，等于"实现了但没人看得见"。
 *
 * 造假数据踩的坑：
 * - **坑一：`announced = false` 那一条不能省。** 它是"只收到事件、没收到会话宣告"的真实
 *   情形（对端先发事件、宣告帧丢了或还没到），界面必须红字说明，而不是假装会话信息齐全。
 * - **坑二：`cursor` 不是时间戳。** 事件行上的时间取 [AgentEventUi.at]（本端收到的时刻），
 *   游标另画一行；把 cursor 填进 `at` 会造出一张 1970 年的图。
 * - **坑三：`state` 用协议里的取值。** `idle|busy|done|error` 会被翻成中文；自己编一个
 *   （如 `reviewing`）走的是"照原样显示"那半 —— 两张图证明的不是同一件事，这里要的是前者。
 * - **坑四：事件只在内存里。** 持久化（§9 分表）属 P1.5，界面上那句「重启即清空」是真话，
 *   别为了好看把它删掉。
 */
internal val agentWithSessionsState = RelayUiState(
    settings = selfSettings,
    network = runningNetwork,
    peers = listOf(codexAgent),
    messages = mapOf(
        codexAgent.nodeId to listOf(
            MessageUi(
                id = "s1",
                peerId = codexAgent.nodeId,
                mine = true,
                text = "把这次改动的门禁跑一遍。",
                at = atOdd(5),
                delivery = DeliveryState.Sent,
            ),
            MessageUi(
                id = "s2",
                peerId = codexAgent.nodeId,
                mine = false,
                text = "收到，先跑测试，截图那步等会儿说。",
                at = atOdd(6),
            ),
        ),
    ),
    agentSessions = mapOf(
        AgentSessionKey(codexAgent.nodeId, "s-1a97a6f3") to AgentSessionUi(
            peerId = codexAgent.nodeId,
            sessionId = "s-1a97a6f3",
            alias = "refactor runner",
            agentKind = "codex",
            state = "busy",
            title = "把撤回窗口补上",
            startedAt = atOdd(4),
            updatedAt = atOdd(23),
        ),
        AgentSessionKey(codexAgent.nodeId, "s-77c1") to AgentSessionUi(
            peerId = codexAgent.nodeId,
            sessionId = "s-77c1",
            state = "done",
            updatedAt = atOdd(20),
            announced = false,
        ),
    ),
    agentEvents = mapOf(
        AgentSessionKey(codexAgent.nodeId, "s-1a97a6f3") to listOf(
            AgentEventUi(
                sessionId = "s-1a97a6f3",
                peerId = codexAgent.nodeId,
                cursor = 40,
                eventType = "session.started",
                summary = "开始跑门禁",
                at = atOdd(4),
            ),
            AgentEventUi(
                sessionId = "s-1a97a6f3",
                peerId = codexAgent.nodeId,
                cursor = 41,
                eventType = "tool.started",
                summary = ":composeApp:allTests",
                at = atOdd(18),
            ),
            AgentEventUi(
                sessionId = "s-1a97a6f3",
                peerId = codexAgent.nodeId,
                cursor = 42,
                eventType = "tool.failed",
                summary = "截图参考图对不上",
                body = "控制台多了一段事件流，旧参考图自然对不上。",
                at = atOdd(23),
            ),
        ),
        AgentSessionKey(codexAgent.nodeId, "s-77c1") to listOf(
            AgentEventUi(
                sessionId = "s-77c1",
                peerId = codexAgent.nodeId,
                cursor = 7,
                eventType = "assistant.text",
                summary = "改完了，等你确认",
                at = atOdd(20),
            ),
        ),
    ),
)

/** 有两个真人节点、零个 Agent —— Agent 页的空态要证明的是"筛完为空"，不是"什么都没连上"。 */
private val humanOnlyState = RelayUiState(
    settings = selfSettings,
    network = runningNetwork,
    peers = listOf(humanPeer, otherHumanPeer),
    unreadByPeer = mapOf(otherHumanPeer.nodeId to 1),
)

/** 两个 Agent，其中被选中的那一个已离线 35 分钟。同 [offlineAgent]：与平板帧共用，故 `internal`。 */
internal val agentWithOfflineSelectedState = RelayUiState(
    settings = selfSettings,
    network = runningNetwork,
    peers = listOf(codexAgent, offlineAgent),
    messages = mapOf(offlineAgent.nodeId to offlineAgentThread),
    pendingByPeer = mapOf(offlineAgent.nodeId to 1),
)

/**
 * 本机节点已起来、同步正常，但**局域网里一个节点都没发现到**，也没存过任何手动节点。
 *
 * 三样都必须为空才能走到那两个 `EmptyHint`：`peers`（`lanPeers` 由它筛出来）、
 * `settings.manualPeers`、`mdnsServices`。少清一样就只会走到"有数据"那侧，
 * 截图看上去和 `nodes-phone` 一样，门禁却以为覆盖了空态 —— 这就是这个状态槽以前没人盯的原因。
 */
private val nothingFoundState = RelayUiState(
    settings = selfSettings,
    network = runningNetwork,
)

/** 离线对端的会话：最后一条排队中，渲染时的"当前时刻"也是钉死的。 */
private val offlineChatState = RelayUiState(
    settings = selfSettings,
    network = runningNetwork,
    peers = listOf(humanPeer, otherHumanPeer),
    messages = mapOf(
        otherHumanPeer.nodeId to listOf(
            MessageUi(
                id = "q1",
                peerId = otherHumanPeer.nodeId,
                mine = false,
                text = "我这边先下了，回头发你结论。",
                at = atOdd(7),
            ),
            MessageUi(
                id = "q2",
                peerId = otherHumanPeer.nodeId,
                mine = true,
                text = "好，那我先把这段留在这儿。",
                at = atOdd(8),
                delivery = DeliveryState.Queued,
            ),
        ),
    ),
    pendingByPeer = mapOf(otherHumanPeer.nodeId to 1),
)

/**
 * 套一层真正的 [CompactScaffold]，与 [ScreenshotPreviews] 里的同名包装器等价。
 *
 * 之所以复制而不是复用：那边的 `Screen` 是 `private` 顶层（文件私有），跨文件拿不到。
 * 语义保持一致 —— 页签 id 用 [AppRoutes] 常量而不是裸字符串，
 * 徽标用应用自己那个 `navBadges`，不在预览里重算一遍规则。
 */
@Composable
private fun ExtraScreen(
    selectedScreenId: String,
    badges: Map<String, Int> = emptyMap(),
    content: @Composable (Modifier) -> Unit,
) {
    CompactScaffold(
        selectedScreenId = selectedScreenId,
        navItems = AppNavItems,
        onScreenSelected = {},
        badges = badges,
    ) { inner -> content(inner) }
}

/**
 * 状态槽：Agent 空态（局域网里连着一个真人节点，但没有任何 Agent）。
 *
 * 覆盖的分支：`AgentConsoleScreen.kt:103-110` —— `agents.isEmpty()` 时画
 * `EmptyState(agent_empty_title, agent_empty_message)` 然后提前返回。
 * 底栏同时证明 `navBadges` 的 Agent 徽标是 0。
 *
 * 造假数据踩的坑：
 * - **坑一：把 `peers` 直接清空是最省事、也是最没用的做法。** 那样 `state.conversations`
 *   也会跟着空掉，参考图看上去像"整个节点没起来"，而这条分支真正要证明的是
 *   `state.agentPeers`（= `peers.filter { it.isAgent }`，`PeerUi.isAgent` 看的是 `caps` 里有没有
 *   `ag1`）筛空之后仍然有人。所以这里刻意留了**两个在线/离线的真人节点**：
 *   「会话」页签亮着徽标，Agent 页却是空态，两件事同框。
 * - **坑二：`caps` 写错就永远走不到这条分支。** `caps = listOf("fd1")` 是真人；
 *   少写一个 `"ag1"` 会被当成真人而不是 Agent，反过来给它加上 `"ag1"` 又会让这张图变成有数据态。
 *
 * 这一屏是产品的第一次启动面：装好 app 的第一眼看到的就是它，所以必须有参考图。
 */
@PreviewTest
@Preview(name = "agents-empty", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun AgentsEmptyPhonePreview() {
    AppTheme(darkTheme = false) {
        ExtraScreen(AppRoutes.AGENTS, badges = navBadges(humanOnlyState)) { inner ->
            AgentConsoleScreen(
                state = humanOnlyState,
                selectedAgentId = null,
                onSelectAgent = {},
                onOpenChat = { _, _ -> },
                // 生产（App.kt）现在给这条空状态接了出口，预览必须跟着接，
                // 否则门禁会放行一张"没有按钮"的假图（审计 §⑤ 点名的结构性盲点）。
                onFindNodes = {},
                onRefresh = {},
                onRespondApproval = { _, _ -> },
                onCancelTask = {},
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
                now = PREVIEW_NOW_ODD,
            )
        }
    }
}

/**
 * 状态槽：选中一个**离线** Agent 的控制台。
 *
 * 覆盖的分支：`AgentConsoleScreen.kt:137-163` —— `!selected.online` 时画
 * `errorContainer` 的 `Surface`（`agent_offline_title` + `agent_offline_message`），
 * 同时 `:135` 的 `AgentStatusCard` 画的是离线那一侧（`common_offline`、灰点）。
 * 消息在 `:165-223` 渲染成时间线卡片（`formatClock`）。
 *
 * 造假数据踩的坑：
 * - **坑一：`selectedAgentId` 必须显式指到离线那个。** `:85` 的兜底是
 *   `agents.firstOrNull { it.nodeId == selectedAgentId } ?: agents.firstOrNull()` ——
 *   传 null 时它会**默默选列表里的第一个**。所以这张图必须把 `codexAgent`（在线）
 *   排在前面、显式选 `offlineAgent`，否则兜底挑中在线的，这张图就名不副实了。
 * - **坑二：只有一个 Agent 时看不到 Agent 标签条。** `:112-128` 的 `FilterChip` 行
 *   是 `agents.size > 1` 才画的，所以这里必须给两个 Agent；顺带也覆盖了"选中态在 chip 上的高亮"。
 * - **坑三：离线文案里的相对时间是两个参数的差。** `relativeTime(lastSeenAt, now)` 要求
 *   `now` 也钉死；`lastSeenAt = atOdd(-10)`、`PREVIEW_NOW_ODD = atOdd(25)` ⇒ 恒为「35 分钟前」。
 * - **坑四：排队消息不能写成已送达。** 对端离线时 `DeliveryState.Queued` 才是真话。
 */
@PreviewTest
@Preview(name = "agents-selected-offline", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun AgentsSelectedOfflinePhonePreview() {
    AppTheme(darkTheme = false) {
        ExtraScreen(AppRoutes.AGENTS, badges = navBadges(agentWithOfflineSelectedState)) { inner ->
            AgentConsoleScreen(
                state = agentWithOfflineSelectedState,
                selectedAgentId = offlineAgent.nodeId,
                onSelectAgent = {},
                onOpenChat = { _, _ -> },
                onFindNodes = {},
                onRefresh = {},
                onRespondApproval = { _, _ -> },
                onCancelTask = {},
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
                now = PREVIEW_NOW_ODD,
            )
        }
    }
}

/**
 * 状态槽：在线 Agent 的控制台里**有结构化会话与事件**（PPE-1 接收侧）。
 *
 * 覆盖的分支：`AgentConsoleScreen` 的结构化区 —— `agentSessionsOf(peerId)` 按 `updatedAt`
 * 倒序画会话卡（`state` 翻译、`announced = false` 的红字、事件计数），`agentEventsOf(sessionId)`
 * 画事件行（`formatClock(at)` + eventType + 游标 + summary/body）。
 *
 * 与 `agents-selected-offline` 的分工：那张钉的是"离线 + 只有聊天消息"，
 * 这张钉的是"在线 + 聊天消息与结构化事件同时存在"，两张合起来才说明两段都接上了。
 */
@PreviewTest
@Preview(name = "agents-sessions", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun AgentsSessionsPhonePreview() {
    AppTheme(darkTheme = false) {
        ExtraScreen(AppRoutes.AGENTS, badges = navBadges(agentWithSessionsState)) { inner ->
            AgentConsoleScreen(
                state = agentWithSessionsState,
                selectedAgentId = codexAgent.nodeId,
                onSelectAgent = {},
                onOpenChat = { _, _ -> },
                onFindNodes = {},
                onRefresh = {},
                onRespondApproval = { _, _ -> },
                onCancelTask = {},
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
                now = PREVIEW_NOW_ODD,
            )
        }
    }
}

/**
 * 状态槽：会话**既没报 `alias`、也没报 `title`** 时，主标题是本地化的「未命名」——
 * **不是** `sessionId`，也不是空白（2026-10-14 修了两次；第二次把末级从 id 改成了无名）。
 *
 * 为什么要单开一张：`alias` / `title` 在协议里都是可选字段（`Codec.AGENT_SESSION_EXPECTED_KEYS`
 * 里与 `sessionId` 并列），对端完全可以只报 `sessionId` + `state`。而且确实存在这样一批会话：
 * 开了就结束、一句 prompt 都没发，日志里**从来没有 `session/title` 事件**
 * （`foldSessionTitle()` 折不出来，`readTitleSnapshots` 只折日志、不看服务内存态的 fallback）。
 * 这时界面上有三种写法**都"看着像正常"**：
 *   ① 拿 `sessionId` 当标题（`session-bd998e9d-…`）—— 本仓改前的写法，把身份键印给用户；
 *   ② 留空 / 显示 `--`；
 *   ③ 有 `title` 就用 `title` —— 这条现在是**对的**，所以本帧**故意不给 title**，
 *      否则主标题会走 title，反而验收不到「未命名」。
 * 前两种不会让任何一条文本门禁变红，只有一张把「未命名」钉在主标题位的参考图会。
 *
 * ## 这张图为什么必须落到会话详情（2026-10-14 第三次修）
 *
 * 前两版这张帧**是空壳**。它只传 `state` + `selectedAgentId`，于是走
 * `AgentConsoleScreen` 的 `isViewingDetail = false` 分支 —— 那个分支画的是
 * `WorkspaceBoardView`（工作区看板），而夹具的会话没有 `wsId`，在看板里落进
 * 「未归属会话」桶，被画成一行「未归属会话 1」。
 *
 * 也就是说**主标题那一段从来没被渲染过**：`tools/render-preview.py --sha` 报
 * 「被改写 0」，看着像幂等，其实是图里根本没有这一段（判据：先看 build 里的渲染图
 * 画了什么，再下结论）。
 *
 * 决定性的开关是 **`inDetail = true`**：只有 `isViewingDetail = true` 才画
 * `SessionDetailView`（`AgentSessionBar` + 会话卡都在那里）。**会话清单本身与 `wsId`
 * 无关** —— `AgentConsoleScreen` 的详情分支取的是 `state.agentSessionsOf(peer.nodeId)`，
 * 是节点下的全部会话，不按工作区过滤（按工作区收窄是看板那一层的事）。
 * 夹具仍然带上 `wsId` / `wsTitle`，只是为了让这条会话像真实数据（有归属的会话），
 * 不是渲染的必要条件。
 *
 * 契约与 DSH 自己一致：`packages/client/ui-workspace/src/client/rows/Rows.tsx` 的
 * `node.title || t('session.untitled')`（文案 `session.untitled` = 「未命名」）。
 * 本帧只钉缺省那一半（有别名 / 有 title 时主标题该走哪一级，见 `AgentSessionCard` 的注释）。
 *
 * 【2026-10-14 见到的实情】`agents-sessions`（本文件 `:487`）目前**也是空壳** —— 同样
 * 只传 `state` + `selectedAgentId`、同样走看板分支。上面那句分工是它 KDoc 的自述，
 * 不是现状；本帧已经落到会话详情，它还没有。是否照本帧的改法一并修
 * （`inDetail = true` + `selectedAgentSessionId`），已记进交接，本轮不动它 ——
 * 那张帧的覆盖关系正被 `docs/plans/2026-10-10-relay-screen-pairing-ledger.md:267`
 * 一类的配对台账核定（台账里已把它记为"跨域/假绿"），不由本帧顺手改。
 */
private val agentWithoutAliasState = agentWithSessionsState.copy(
    agentSessions = mapOf(
        AgentSessionKey(codexAgent.nodeId, "s-4c1f") to AgentSessionUi(
            peerId = codexAgent.nodeId,
            sessionId = "s-4c1f",
            agentKind = "codex",
            // 工作区归属：这一帧要落在**会话详情 / 工作区卡**那条渲染路径上；
            // 一个没有 `wsId` 的会话会被派生归进「未归属会话」桶（那里不画会话名）。
            wsId = "ws-preview",
            wsTitle = "kapp-lib",
            state = "busy",
            // 故意**不报 title / alias**：这一帧钉的就是"两样都没有"时的缺省。
            // 给了 title 主标题就会走 title，反而验收不到「未命名」（见上面的 KDoc）。
            startedAt = atOdd(4),
            updatedAt = atOdd(23),
        ),
    ),
    agentEvents = mapOf(
        AgentSessionKey(codexAgent.nodeId, "s-4c1f") to listOf(
            AgentEventUi(
                sessionId = "s-4c1f",
                peerId = codexAgent.nodeId,
                cursor = 40,
                eventType = "session.started",
                summary = "开始跑门禁",
                at = atOdd(4),
            ),
        ),
    ),
)

@PreviewTest
@Preview(name = "agents-session-no-alias", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun AgentsSessionNoAliasPhonePreview() {
    AppTheme(darkTheme = false) {
        ExtraScreen(AppRoutes.AGENTS, badges = navBadges(agentWithoutAliasState)) { inner ->
            AgentConsoleScreen(
                state = agentWithoutAliasState,
                selectedAgentId = codexAgent.nodeId,
                selectedAgentSessionId = "s-4c1f",
                onSelectAgent = {},
                onOpenChat = { _, _ -> },
                // 走**会话详情**分支：只有 `isViewingDetail = true` 才画 `SessionDetailView`
                // （`AgentSessionBar` + 会话卡），也就是「未命名」该出现的主标题位。
                // 见上面 KDoc 的「为什么必须落到会话详情」。
                inDetail = true,
                // 按生产调用点武装（`App.kt:549-552`）。这一屏的生产侧确实接了
                // 「找节点 / 批准-拒绝 / 取消任务 / 刷新」四个出口；不传就等于画一张
                // 缺了按钮的假图 —— `:designPreviewPairingGate` 的「夹具欠武装」
                // 判的正是这条（可空闸门参数没传 ⇒ 依赖它的那一支根本不渲染）。
                onFindNodes = {},
                onRespondApproval = { _, _ -> },
                onCancelTask = {},
                onRefresh = {},
                // **故意不传 `onOpenSessionDetail` / `onBackToBoard`**：生产在手机
                // 「工作区」这一屏给这两个传的就是 `null`（`App.kt:543-544`，下钻走
                // `AgentConsoleScreen` 自己的 `localDetailSessionId` 兜底），
                // 传上非 null 反而与生产不一致。
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
                now = PREVIEW_NOW_ODD,
            )
        }
    }
}
/**
 * 状态槽：**会话切换浮层**（原型 `#aspick`，`prototype/relay.html:4076-4100` + `.pickrow` CSS `:1431-1443`）。
 *
 * 为什么要单开一张：这一屏在回归门禁里**此前一张帧都没有**（`grep 'SessionPicker|切换会话'
 * androidApp/src/screenshotTest` 命中 0），而它恰好是用户点名「样式不对」的那一处 —— 浮层此前
 * 走 M3 `AlertDialog` 的默认骨架（28dp 圆角 + headlineSmall 24sp 标题 + 默认按钮），与原型
 * `.dlg{border-radius:20px}` / `.dlg h2{font-size:16px;font-weight:700}` / `.btn` 三处都对不上，
 * 却没有任何一张图能把这件事判红。2026-10-14 换成共享骨架 `ui/components/RelayDialog.kt` 后补上本帧。
 *
 * 本帧钉住的四件事（都能被参考图判红）：
 *   ① 浮层底色是 `--surface`（纯白），圆角 20dp，标题 16sp/700；
 *   ② 勾在**行首**且**只有当前项**画（当前项 `aria-current="true"` ⇒ 底色 `--surface-2`）；
 *   ③ 每行只有「名字 + 右侧状态」；`announced = false` 那条的第二行红字起点与名字左对齐
 *      （原型 `.pickwrap .ms{padding:0 4px 0 30px}`，30px = 4 + 18 勾位 + 8 间距）；
 *   ④ 底部只有一颗「关闭」（原型 `.acts` 里就一颗 `.btn`），不是「取消 / 确定」。
 *
 * 夹具用的是 `agentWithSessionsState` 的两个会话：`s-1a97a6f3`（有别名、busy、最近更新 ⇒
 * 当前项）与 `s-77c1`（无别名、done、`announced = false` ⇒ 红字那一行）。
 */
@PreviewTest
@Preview(name = "agents-session-pick", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun AgentsSessionPickPhonePreview() {
    AppTheme(darkTheme = false) {
        ExtraScreen(AppRoutes.AGENTS, badges = navBadges(agentWithSessionsState)) { inner ->
            AgentConsoleScreen(
                state = agentWithSessionsState,
                selectedAgentId = codexAgent.nodeId,
                onSelectAgent = {},
                onOpenChat = { _, _ -> },
                onFindNodes = {},
                onRespondApproval = { _, _ -> },
                onCancelTask = {},
                onRefresh = {},
                onOpenSessionDetail = null,
                onBackToBoard = null,
                // 直接落在二级会话详情台（生产靠点工作区卡才下钻），再让浮层默认打开。
                // `initialSessionPickerOpen` 只给截图门禁用，生产侧一律默认 false。
                                // 二级会话详情台要有 detailSessionId 才会渲染（否则落在看板上、浮层永远不打开 ⇒ 与
                // AgentsSessionsPhonePreview 逐字节相同，重复帧门禁会红）。
                detailSessionId = "s-pick01",
inDetail = true,
                initialSessionPickerOpen = true,
                // 按生产调用点（App.kt 的 AgentConsoleScreen 调用）逐参武装：这五个可空出口在生产侧都是
                // 非 null 闭包，夹具不传就等于依赖它的那一支根本不渲染 —— 截图证据里也就没有它。
                onOpenWorkspaceDetail = { _, _ -> },
                onLeaveWorkspaceDetail = {},
                onLeaveSessionDetail = {},
                onLoadWorkspaceSessions = { _, _ -> },
                onCreateWorkspaceGroup = {},
                modifier = inner,
                now = PREVIEW_NOW_ODD,
            )
        }
    }
}

/**
 * 状态槽：节点页的空态。
 *
 * 覆盖的分支：`nodes.isEmpty() && state.mdnsServices.isEmpty()` ⇒ 一张居中空态卡
 * （`nodes_nodes_empty`）+ 两条 `.krow`（添加节点 / 我的二维码）。已有的 `nodes-phone`
 * 用的是 `discoveryState`（六个节点），**两张图证明的是互斥的两半**。
 *
 * 造假数据踩的坑：
 * - **坑一：空态**没有**独立的加载分支（2026-10-12 原型改稿后）。** 旧稿里
 *   `NodePhase.Starting` 时画的是「正在启动节点…」加载行，那张加载图曾由
 *   `nodes-starting`（`ScreenshotPreviews.kt`）钉住；新稿 `screenNodesEmpty()`
 *   （`prototype/relay.html:3410-3434`）是**无条件**空态卡，`Starting` 分支已从
 *   `NodesScreen` 删掉（它现在只消费 `NodePhase.Failed`）。**所以 `nodes-starting`
 *   那张帧也被删了** —— 它与本帧只剩 `phase` 一个字段不同，渲染出来逐字节相同
 *   （`render-preview.py --dup` 抓到，sha256
 *   `8b073623cde2643f91838d094c31388e51766cdce1a99b5e61a7ceb4fde75b12`）。
 *   本帧仍用 `NodePhase.Running`：`phase` 对节点页已无视觉影响，但用 Running 才是
 *   空态真实的来路（"同步正常但没发现到人"），也避免与将来若恢复相位分支时混淆。
 * - **坑二：三样都得清干净。** `peers` 空；`settings.manualPeers` 用默认空表（不能
 *   复用任何带 `ManualPeerEntry` 的状态）；`mdnsServices` 默认空表。只清 `peers`
 *   而留着手动地址，会走到"局域网空 + 手动有"那半张，和空态不是一回事。
 * - **坑三：空态是一张卡里的一段中文，不是插画。** 这屏的空态视觉重量远低于
 *   `EmptyState`，不要因为"看着太空"就去改被测屏。
 */
@PreviewTest
@Preview(name = "nodes-empty", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun NodesEmptyPhonePreview() {
    AppTheme(darkTheme = false) {
        ExtraScreen(AppRoutes.NODES, badges = navBadges(nothingFoundState)) { inner ->
            NodesScreen(
                state = nothingFoundState,
                onAddPeer = { _, _ -> },
                onRemovePeer = {},
                onCleanupUnreachable = {},
                onRetryStart = {},
                onDismissBanner = {},
                modifier = inner,
            )
        }
    }
}

/**
 * 状态槽：设置第二屏「通知」页（原型 `screenSetNotify`）。
 *
 * 拆分前这一页和另外四张原型页共用一张 `SettingsMoreScreen`（803 行），
 * 五页塞进一张 1600dp 的图 —— 任何一页整段消失都不会有图变红，而
 * `settings-more / normal` 这一格始终是"已覆盖"。2026-10-11 按原型页拆成六个
 * `Settings*Subpage`，每一页各有一帧；本帧是「通知」，也是 `settings-more / normal` 的证据。
 *
 * 坑一：**截图抓的是一帧，所以开关的初值就是参考图。**
 * 页面给每个开关做了一份 `rememberSaveable(<传入值>) { mutableStateOf(传入值) }`，
 * 键就是传进来的值 ⇒ 传什么显示什么，构图稳定，不需要交互。
 * 坑二：三个初值取**生产默认**（`notifyAgentMessages = true` / `notifyMentionOnly = true` /
 * `notifyQuietHours = false`，见 `RelaySettings.kt:130/138/144`）。
 * 坑三：三个回调都给空 lambda，因为生产（`App.kt` 与 `DesktopHosting.kt`）都接了它们 ——
 * 留 null 会录出一张"拨不动"的假图。
 * 坑四：画幅回到 852dp。1600dp 是合并页时代的补偿（那时「数据与存储」在 4200px 处），
 * 拆开后这一页只有一张卡，1600dp 会录出一屏半的空白。
 */@PreviewTest
@Preview(name = "settings-more-phone", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun SettingsMorePhonePreview() {
    AppTheme(darkTheme = false) {
        ExtraScreen(AppRoutes.SETTINGS, badges = navBadges(humanOnlyState)) { inner ->
            SettingsNotifySubpage(
                modifier = inner,
                onBack = {},
                notificationsEnabled = true,
                onNotificationsEnabledChange = {},
                mentionOnlyEnabled = true,
                onMentionOnlyEnabledChange = {},
                quietHoursEnabled = false,
                onQuietHoursEnabledChange = {},
            )
        }
    }
}

/**
 * 状态槽：设置第二屏的**存储卡有动作可点**那一支。
 *
 * 覆盖的分支：`SettingsDataSubpage.kt` 存储卡里 `onOpenDataDirectory != null` 时的
 * 「打开目录」按钮，以及续传残片那一行「有几个残片、能不能清」的常态。
 * `settings-more-loading` 与 `settings-more-long-text` 故意把两个目录回调都留成 null ——
 * 那是**移动端生产的真实处境**（整行不画）；而桌面生产是接了
 * `onOpenDataDirectory = { openInFileManager(controller.dataDirectory) }` 的
 * （`DesktopHosting.kt`），所以"按钮存在时长什么样"也必须有一张图钉住，
 * 否则哪天这一支整行消失，门禁不会有任何反应。
 *
 * `onChangeDataDirectory` 仍然留 null：它在三平台都没有消费者（撤下理由见
 * `SettingsDataSubpage.kt` 该参数的 KDoc），不是"这一张不拍"。
 */
@PreviewTest
@Preview(name = "settings-more-storage", widthDp = 393, heightDp = 1600, showBackground = true, locale = "zh-rCN")
@Composable
private fun SettingsMoreStoragePhonePreview() {
    AppTheme(darkTheme = false) {
        ExtraScreen(AppRoutes.SETTINGS, badges = navBadges(humanOnlyState)) { inner ->
            SettingsDataSubpage(
                modifier = inner,
                dataDirectory = "/data/user/0/com.kapp.tidings/files/relay",
                cacheDirectory = "/data/user/0/com.kapp.tidings/cache/relay",
                onBack = {},
                dataUsageBytes = 268_435_456L,
                cacheUsageBytes = 33_554_432L,
                // 残片用原型上的那个数（`prototype/relay.html:3936` 「3 个 · 18.2 MB」；
                // `formatBytes` 只到整数 MB，所以这里给 18.2 MiB 的字节数）。
                leftoverPartCount = 3,
                leftoverPartBytes = 19_083_674L,
                onClearLeftoverParts = {},
                persistMessagesEnabled = true,
                onPersistMessagesEnabledChange = {},
                onClearHistory = {},
                // **故意不传 `onOpenDataDirectory`**（Lead 2026-10-12 裁决，W3 交裁第 ② 条）：
                // 原型 `screenSetData` 的 hint 逐字「移动端取用文件的出口在会话里那张文件卡上
                // （打开 / 分享），所以这里不放「更改目录」「打开」。」；`screenDSettingsData`
                // 的 hint 又逐字「移动端的同名子页内容与此一致，只少上面那两个目录按钮。」
                // ⇒ 手机这一屏**不该有**「打开」按钮。生产侧手机确实不传（`App.kt:352`
                // 那处调用没有这个实参），只有桌面传（`DesktopHosting.kt:606`）。
                // 夹具传了就是**过武装**：画出生产永远到不了的按钮，截图门禁从此对它变瞎。
                onExperimentalLuciolaChange = {},
            )
        }
    }
}

/**
 * 状态槽：设置第二屏的**统计还没回来**那一支。
 *
 * 覆盖的分支：`SettingsDataSubpage.kt` 里 `dataUsageBytes == null` 与 `leftoverPartCount == null`
 * 两条 null 分支（存储占用的「正在统计…」和续传残片的「正在统计…」）。
 *
 * 这一支以前是**假的**：文案写的是「需要平台层统计后显示」，把"还在数"说成了"数不了"。
 * 实际上 `directoryUsageBytes` 三平台都只返回 `Long`（目录不存在返回 0），
 * `leftoverParts()` 返回 `List`（拿不到运行时是空表），两者都不会失败 ——
 * 所以 `null` 只可能是 `App.kt` 里那个 `produceState` 还没跑完，文案已改成「正在统计…」。
 *
 * 这一帧**能**拍出来，是因为 `SettingsDataSubpage` 把两个统计量当参数收（不是自己 `produceState`），
 * 所以静态帧可以直接注入"在途"这一态；生产里它出现在第一次打开「更多」子屏、
 * 后台线程还在数的那一小段时间。拍不出来的是"数不了" —— 因为那一态根本不存在。
 */
@PreviewTest
@Preview(name = "settings-more-loading", widthDp = 393, heightDp = 1600, showBackground = true, locale = "zh-rCN")
@Composable
private fun SettingsMoreLoadingPhonePreview() {
    AppTheme(darkTheme = false) {
        ExtraScreen(AppRoutes.SETTINGS, badges = navBadges(humanOnlyState)) { inner ->
            SettingsDataSubpage(
                modifier = inner,
                dataDirectory = "/data/user/0/com.kapp.tidings/files/relay",
                cacheDirectory = "/data/user/0/com.kapp.tidings/cache/relay",
                onBack = {},
                // 两个统计量都留 null：这就是 App.kt 的 produceState 还没出值时的样子。
                dataUsageBytes = null,
                cacheUsageBytes = null,
                leftoverPartCount = null,
                leftoverPartBytes = null,
                persistMessagesEnabled = true,
                onPersistMessagesEnabledChange = {},
                onClearHistory = {},
                onExperimentalLuciolaChange = {},
            )
        }
    }
}

/**
 * 状态槽：设置第二屏「外观」页（原型 `screenSetAppear`）。
 *
 * 拆分前这一页没有自己的帧 —— 它和另外四张原型页共用一张 `SettingsMoreScreen`，
 * 一张 1600dp 的图里塞五页，任何一页整段消失都不会有图变红。
 *
 * 坑一：**三档必须有一档是选中的。** `themeMode` 传 `THEME_MODE_SYSTEM`，
 * 与生产 `App.kt` 的初值同源（`controller.state.settings.themeMode` 认不出的值都落回跟随系统）；
 * 传空串会让三段都不选中，那是真机上不存在的构图。
 * 坑二：`onThemeModeChange` 给空 lambda 而不是 null —— 生产两个壳都接了它，
 * 留 null 会录出一张「点不动」的假图。
 */
@PreviewTest
@Preview(name = "settings-more-appear", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun SettingsMoreAppearPhonePreview() {
    AppTheme(darkTheme = false) {
        ExtraScreen(AppRoutes.SETTINGS, badges = navBadges(humanOnlyState)) { inner ->
            SettingsAppearSubpage(
                modifier = inner,
                onBack = {},
                themeMode = THEME_MODE_SYSTEM,
                onThemeModeChange = {},
            )
        }
    }
}

/**
 * 状态槽：设置第二屏「能力声明」页（原型 `screenSetCaps`）。
 *
 * 两个开关的初值取生产默认（`tw1` = 排队保活、`mrec1` = 媒体撤回，两者都 `true`）；
 * 第三个开关 `fd1`（文件直传）**不在参数里** —— 它写死 `checked = false, enabled = false`，
 * 所以任何预览都改不了它，这张图顺带把「它为什么灰着」钉住。
 *
 * 页脚的「对端可见」标注与 `set_caps_saved` 那句「能力声明已更新，节点正在重新广播。」
 * 是原型这一页独有的，前者画在标题栏副标题，后者是卡片下的 hint。
 */
@PreviewTest
@Preview(name = "settings-more-caps", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun SettingsMoreCapsPhonePreview() {
    AppTheme(darkTheme = false) {
        ExtraScreen(AppRoutes.SETTINGS, badges = navBadges(humanOnlyState)) { inner ->
            SettingsCapsSubpage(
                modifier = inner,
                onBack = {},
                textCapabilityEnabled = true,
                onTextCapabilityEnabledChange = {},
                recordCapabilityEnabled = true,
                onRecordCapabilityEnabledChange = {},
            )
        }
    }
}

/**
 * 状态槽：设置第二屏「诊断」页（原型 `screenSetDiag`）。
 *
 * 坑一：**「重新自检」在这一页仍然是禁用的。** `onRunSelfCheck` 生产没接（两个壳都没传），
 * 页面会明说 `set_diag_selfcheck_unavailable`。给它接空 lambda 只会把「点不动」
 * 变成「点了没反应」—— 那比灰着更坏。
 * 坑二：`protocolSummary` 给的是**长串**（真机上的真实形态），不是「v1」两个字。
 * 短串会让「协议摘要会不会把 kv 行撑破」这件事没有任何图钉住。
 */
@PreviewTest
@Preview(name = "settings-more-diag", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun SettingsMoreDiagPhonePreview() {
    AppTheme(darkTheme = false) {
        ExtraScreen(AppRoutes.SETTINGS, badges = navBadges(humanOnlyState)) { inner ->
            SettingsDiagSubpage(
                state = humanOnlyState,
                modifier = inner,
                onBack = {},
                protocolSummary = "v1 · 与 desktop 0.61.1 兼容 · tw1/mrec1/tbl1/fd1/ag1/agn1",
            )
        }
    }
}

/**
 * 状态槽：聊天页对端离线时的输入区。
 *
 * 覆盖的分支：`ui/components/MessageComposer.kt` 里 `if (!peerOnline)` 画出的
 * `chat_peer_offline` 那行小字（`labelSmall` / `onSurfaceVariant`）。
 * 触发条件纯粹来自 `peer.online`（`ChatScreen` 把 `peer.online` 传给 `MessageComposer`），
 * 所以只要 `state.peers` 里那条记录是离线的，就一定走到这一支。
 *
 * 造假数据踩的坑：
 * - **坑一：`peerOnline` 取自 `state.chatPeer(peerId).online`，不是消息。** 只把消息排成
 *   `Queued` 而把对端记成在线，输入区那行字不会出来 —— 这正是最容易造错的一处。
 * - **坑二（已解决）：草稿是屏内状态。** `var draft by remember(peerId)` 曾经没有初值入口，
 *   输入框只能录到 placeholder。后来 `ChatScreen` 开了 `initialDraft` 接缝（与
 *   `initialFilter` / `initialCreating` 这类"屏内状态提成参数"的接缝同一理由），"输入框里
 *   已经打了字"的三个分支现在由 `chat-composer-udp` / `chat-composer-tcp` /
 *   `chat-composer-over` 三张图钉住。
 *   本图仍然只录空草稿 —— 它要覆盖的是**离线那行字**，不是状态行。
 * - **坑三：对端必须真的在 `peers` 里。** `RelayUiState.chatPeer` 在只有消息没有节点记录时
 *   会兜一个 `PeerUi(nodeId, nick = "")` 出来（`:151` 的 `restored` 分支会在顶栏提示
 *   "记录来自本机"）。用真实节点记录才不会把那条恢复提示混进这张图。
 */
@PreviewTest
@Preview(name = "chat-peer-offline", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ChatPeerOfflinePhonePreview() {
    AppTheme(darkTheme = false) {
        ExtraScreen(AppRoutes.CONVERSATIONS, badges = navBadges(offlineChatState)) { inner ->
            ChatScreen(
                data = ChatScreenState(
                    state = offlineChatState,
                    peerId = otherHumanPeer.nodeId,
                ),
                actions = chatPreviewActions(
                    onAttachFile = {},
                    onRecallMessage = {},
                    onNudge = {},
                ),
                chrome = ChatScreenChrome(
                    onBack = {},
                    modifier = inner,
                )
            )
        }
    }
}

/**
 * 状态槽：聊天详情在**没有选中会话**时的样子。
 *
 * 覆盖的分支：`ChatScreen.kt` 顶部的 `peerId == null || peer == null` 早退 —— 标题 +
 * 「去节点页配对」。这条分支以前只有一句"先选一个人"就没了出口（审计 P2-18 第 2 处），
 * 现在 `onFindPeers` 给了它一条真出口，所以必须录一张钉住，否则哪天按钮掉了没人知道。
 *
 * 生产（`App.kt`）在移动端传 `onFindPeers = { nav = nav.selectTab(AppRoutes.NODES) }`；
 * 这里传空 lambda：**按钮的存在与文案才是被钉住的东西**，点下去跳到哪里由导航测试管。
 */
@PreviewTest
@Preview(name = "chat-no-selection", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ChatNoSelectionPhonePreview() {
    AppTheme(darkTheme = false) {
        ExtraScreen(AppRoutes.CONVERSATIONS, badges = navBadges(offlineChatState)) { inner ->
            ChatScreen(
                data = ChatScreenState(
                    state = offlineChatState,
                    peerId = null,
                ),
                actions = chatPreviewActions(
                    onAttachFile = {},
                    onRecallMessage = {},
                    onNudge = {},
                ),
                chrome = ChatScreenChrome(
                    onBack = {},
                    onFindPeers = {},
                    modifier = inner,
                )
            )
        }
    }
}

/** 在线对端 + 一段已有对话：输入栏那三张图共用它（在线才不会把"对方离线"那行混进画面）。 */
private val composerChatState = RelayUiState(
    settings = selfSettings,
    network = runningNetwork,
    peers = listOf(humanPeer),
    messages = mapOf(
        humanPeer.nodeId to listOf(
            MessageUi(
                id = "d1",
                peerId = humanPeer.nodeId,
                mine = false,
                text = "设计规范更新了，输入框那一段要按新的来。",
                at = atOdd(7),
            ),
            MessageUi(
                id = "d2",
                peerId = humanPeer.nodeId,
                mine = true,
                text = "收到，我下午改。",
                at = atOdd(8),
                delivery = DeliveryState.Sent,
            ),
        ),
    ),
)

/** 一句 14 字的中文（42 字节）×20 + 开头那句 ⇒ 912 字节：过了 UDP 的 800，没到 TCP 的 4096。 */
private val tcpRouteDraft = "这段话用来撑过单播上限，说明输入栏会自己改成 TCP 发。" + "先把结论写清楚，再补细节。".repeat(20)

/** 同一句话（13 字 = 39 字节）×110 ⇒ 4290 字节：过了单条硬上限，输入栏必须报错而不是假装还能发。 */
private val overLimitDraft = "先把结论写清楚，再补细节。".repeat(110)

/**
 * 输入栏：草稿还没过单播上限（默认那条路）。
 *
 * 覆盖的分支：`ui/components/MessageComposer.kt` 里状态行的**三个分支中的第一个** ——
 * 字节数按 UDP 上限算，右半边说"局域网直连 · 走 UDP 单播"。
 *
 * 草稿必须由 [ChatScreen] 的 `initialDraft` 接缝带进来：草稿是屏内 `remember` 状态，
 * 靠人手敲键盘才存在，截图门禁永远录不到"输入框里有字"的样子 ——
 * 这也是旧实现下字节数/路由提示/超限报错三条分支全是死角的原因。
 *
 * `onAttachFile` 传空 lambda 而不是省略：被钉住的是**那个附件图标画不画**，
 * 生产（`App.kt`）传的是 `controller::pickAndSendFiles`，点下去做什么由别处管。
 */
@PreviewTest
@Preview(name = "chat-composer-udp", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ChatComposerUdpPhonePreview() {
    AppTheme(darkTheme = false) {
        ExtraScreen(AppRoutes.CONVERSATIONS, badges = navBadges(composerChatState)) { inner ->
            ChatScreen(
                data = ChatScreenState(
                    state = composerChatState,
                    peerId = humanPeer.nodeId,
                ),
                actions = chatPreviewActions(
                    onAttachFile = {},
                    onRecallMessage = {},
                    onNudge = {},
                ),
                chrome = ChatScreenChrome(
                    onBack = {},
                    modifier = inner,
                    initialDraft = "我把截图发你，稍等。",
                )
            )
        }
    }
}

/**
 * 输入栏：草稿过了单播上限、但还没到单条硬上限。
 *
 * 覆盖的分支：状态行第二个分支 —— 分母换成 TCP 上限，右半边改说"超出单播上限，自动改走 TCP"。
 * 这句话是给用户的**后果说明**：他不知道 800 字节是什么，但他需要知道"这样发得出去，只是换了条路"。
 */
@PreviewTest
@Preview(name = "chat-composer-tcp", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ChatComposerTcpPhonePreview() {
    AppTheme(darkTheme = false) {
        ExtraScreen(AppRoutes.CONVERSATIONS, badges = navBadges(composerChatState)) { inner ->
            ChatScreen(
                data = ChatScreenState(
                    state = composerChatState,
                    peerId = humanPeer.nodeId,
                ),
                actions = chatPreviewActions(
                    onAttachFile = {},
                    onRecallMessage = {},
                    onNudge = {},
                ),
                chrome = ChatScreenChrome(
                    onBack = {},
                    modifier = inner,
                    initialDraft = tcpRouteDraft,
                )
            )
        }
    }
}

/**
 * 输入栏：草稿超了单条硬上限。
 *
 * 覆盖的分支：状态行第三个分支 —— 只剩一句报错（复用 `chat_byte_over`），右侧路由提示整条不画：
 * 这时候"走哪条路"已经不是问题了，用户要先删字。发送键同时置灰（`canSend` 为假）。
 */
@PreviewTest
@Preview(name = "chat-composer-over", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ChatComposerOverLimitPhonePreview() {
    AppTheme(darkTheme = false) {
        ExtraScreen(AppRoutes.CONVERSATIONS, badges = navBadges(composerChatState)) { inner ->
            ChatScreen(
                data = ChatScreenState(
                    state = composerChatState,
                    peerId = humanPeer.nodeId,
                ),
                actions = chatPreviewActions(
                    onAttachFile = {},
                    onRecallMessage = {},
                    onNudge = {},
                ),
                chrome = ChatScreenChrome(
                    onBack = {},
                    modifier = inner,
                    initialDraft = overLimitDraft,
                )
            )
        }
    }
}

/**
 * 输入栏：表情面板打开着。
 *
 * 覆盖的面：手机输入栏的表情托盘 —— 8 列网格、44dp 的触控格子、以及**最后一行只有 7 个**
 * 时补的那块空位（不补的话那 7 个会被拉宽，网格看起来就散了）。这些是静态几何，门禁钉得住；
 * "点下去插在光标处"是交互，截图录不到，由 `MessageComposerTest` 里的 `insertEmoji` 用例管。
 *
 * 面板开合是屏内状态，所以只能由 `ChatScreen` 的 `initialEmojiOpen` 接缝带进来
 * （与 `initialDraft` 同一条理由）。草稿特意留了几个字：状态行与面板同时出现才是真实样子，
 * 也顺便证明两者能共存而不是互相顶掉。
 */
@PreviewTest
@Preview(name = "chat-emoji", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ChatEmojiPhonePreview() {
    AppTheme(darkTheme = false) {
        ExtraScreen(AppRoutes.CONVERSATIONS, badges = navBadges(composerChatState)) { inner ->
            ChatScreen(
                data = ChatScreenState(
                    state = composerChatState,
                    peerId = humanPeer.nodeId,
                ),
                actions = chatPreviewActions(
                    onAttachFile = {},
                    onRecallMessage = {},
                    onNudge = {},
                ),
                chrome = ChatScreenChrome(
                    onBack = {},
                    modifier = inner,
                    initialDraft = "晚点开会，",
                    initialEmojiOpen = true,
                )
            )
        }
    }
}

/**
 * 状态槽：撤回与戳一碰留下的**系统提示行**。
 *
 * 覆盖的分支：`ChatScreen.kt` 的 `NoticeLine`（四种 [NoticeKind] 各一行）。这些行不是消息，
 * 所以它们没有气泡、没有时间戳、也没有投递徽标 —— 一条 `notice != null` 的消息整条走另一条渲染路径。
 *
 * 为什么必须有这张图：四种提示里三种**只在协议事件到达时**出现（对方撤回、对方戳我、我戳对方），
 * 靠手点界面根本点不出来；而"自己撤回"那条还要先发一条消息再长按。截图门禁不录，
 * 这四条文案就永远没有参考图 —— 改错一个字、或者哪天 `notice` 被顺手当成普通消息渲染，没人会发现。
 *
 * 假数据的坑：`text` 必须留空。控制器应用撤回时会把正文清掉（协议要求"本地隐藏原消息"），
 * 这里照着造 —— 如果哪天有人给提示行又塞回正文，这张图会先炸。
 */
@PreviewTest
@Preview(name = "chat-notices", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ChatNoticesPhonePreview() {
    AppTheme(darkTheme = false) {
        ExtraScreen(AppRoutes.CONVERSATIONS, badges = navBadges(noticeChatState)) { inner ->
            ChatScreen(
                data = ChatScreenState(
                    state = noticeChatState,
                    peerId = humanPeer.nodeId,
                ),
                actions = chatPreviewActions(
                    onAttachFile = {},
                    onRecallMessage = {},
                    onNudge = {},
                ),
                chrome = ChatScreenChrome(
                    onBack = {},
                    modifier = inner,
                )
            )
        }
    }
}

/** 一段"发生过撤回与戳一碰"的对话：四种提示行各一条，另加两条真消息撑住上下文。 */
private val noticeChatState = RelayUiState(
    settings = selfSettings,
    network = runningNetwork,
    peers = listOf(humanPeer),
    messages = mapOf(
        humanPeer.nodeId to listOf(
            MessageUi(
                id = "x1",
                peerId = humanPeer.nodeId,
                mine = false,
                text = "设计规范更新了，输入框那一段要按新的来。",
                at = atOdd(7),
            ),
            MessageUi(
                id = "x2",
                peerId = humanPeer.nodeId,
                mine = false,
                text = "",
                at = atOdd(8),
                notice = NoticeKind.RecalledByPeer,
            ),
            MessageUi(
                id = "x3",
                peerId = humanPeer.nodeId,
                mine = false,
                text = "",
                at = atOdd(9),
                notice = NoticeKind.NudgedByPeer,
            ),
            MessageUi(
                id = "x4",
                peerId = humanPeer.nodeId,
                mine = true,
                text = "收到，我下午改。",
                at = atOdd(10),
                delivery = DeliveryState.Sent,
            ),
            MessageUi(
                id = "x5",
                peerId = humanPeer.nodeId,
                mine = true,
                text = "",
                at = atOdd(11),
                delivery = DeliveryState.Recalled,
                notice = NoticeKind.RecalledBySelf,
            ),
            MessageUi(
                id = "x6",
                peerId = humanPeer.nodeId,
                mine = true,
                text = "",
                at = atOdd(12),
                notice = NoticeKind.NudgedBySelf,
            ),
        ),
    ),
)

/**
 * 状态槽：扫码页的**等待**帧。
 *
 * 覆盖 `ScanScreen.kt` 的 `waiting == true` 分支：顶部关闭按钮、深色取景区里的白色取景框、
 * 底下「正在启动相机…」，**没有**「再试一次」（还没失败，重试按钮不该出现）。
 *
 * 取景区里那块占位是**故意画的**，不是相机画面：截图里不可能有真相机，画一块看起来像
 * 照片的东西就是造假图。真正的相机预览由 `AppPlatform.qrScannerPreview` 提供，
 * 只有真机能证明它 —— 见 `ACCEPTANCE.md` 的 A15。
 *
 * `scan` 传的是 `awaitCancellation()`：这个预览要停在"正在等"的那一刻，
 * 传 `{ false }` 会让它立刻翻到失败帧，两张图就撞成一张了。
 */
@Preview(name = "scan-waiting", widthDp = 393, heightDp = 852, locale = "zh-rCN")
@PreviewTest
@Composable
private fun ScanWaitingPhonePreview() {
    AppTheme(darkTheme = false) {
        ScanScreen(
            state = scanState,
            preview = { modifier -> ScanPreviewPlaceholder(modifier) },
            scan = { awaitCancellation() },
            onPaired = {},
            onCancel = {},
            onDismissBanner = {},
            modifier = Modifier.fillMaxSize(),
            // 手机端读得了图里的码，所以这条按钮在这一帧里就该在（它不依赖相机权限，
            // 见 ScanScreen 的 importFromImage KDoc）。返回 false = 这次没导入成功。
            importFromImage = { false },
        )
    }
}

/**
 * 状态槽：扫码页的**失败**帧（系统没给相机权限）。
 *
 * 覆盖同一条协程的另一半：`scan()` 立刻返回 false ⇒ `waiting = false`，
 * 于是底下换成取景提示 + 「再试一次」，并把控制器留下的真实原因
 * （`BannerMessage.CameraPermissionDenied`）画成横幅。
 *
 * 这一帧存在的理由就是那条横幅：上一版扫码"点了没反应"，连失败原因都不显示。
 * 「回节点页填地址」这条出路必须和重试同框 —— 一台拿不到相机的设备
 * 照样要能把对方加进来。
 */
@Preview(name = "scan-failed", widthDp = 393, heightDp = 852, locale = "zh-rCN")
@PreviewTest
@Composable
private fun ScanFailedPhonePreview() {
    AppTheme(darkTheme = false) {
        ScanScreen(
            state = scanState.copy(
                banner = BannerUi(
                    message = BannerMessage.CameraPermissionDenied,
                    tone = BannerTone.Warn,
                ),
            ),
            preview = { modifier -> ScanPreviewPlaceholder(modifier) },
            scan = { false },
            onPaired = {},
            onCancel = {},
            onDismissBanner = {},
            modifier = Modifier.fillMaxSize(),
            // 截图抓的是第一帧，`scan()` 还没来得及把 `waiting` 翻下去 —— 见该参数的 KDoc。
            attemptAlreadyFailed = true,
            // 相机被拒的时候这条路最有用：它不碰相机权限。
            importFromImage = { false },
        )
    }
}

/**
 * 状态槽：扫码页在**画不出预览**的平台上的样子。
 *
 * `AppPlatform.qrScannerPreview` 是可空能力槽（沿用仓库"这个壳没这能力就不画"的规矩），
 * 传 null 时页面画一句诚实说明而不是一块黑框 —— 这一帧钉的就是那句话。
 * 目前 Android/iOS 两个壳都给了预览，所以这条分支只有未来的壳会走到；
 * 它仍然是**真实代码路径**，不是为截图编出来的。
 */
@Preview(name = "scan-no-preview", widthDp = 393, heightDp = 852, locale = "zh-rCN")
@PreviewTest
@Composable
private fun ScanNoPreviewPhonePreview() {
    AppTheme(darkTheme = false) {
        ScanScreen(
            state = scanState,
            preview = null,
            scan = { awaitCancellation() },
            onPaired = {},
            onCancel = {},
            onDismissBanner = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** 扫码页的假数据：本机在跑、没有别的东西。横幅由各帧自己加。 */
private val scanState = RelayUiState(
    settings = selfSettings,
    network = NetworkUi(phase = NodePhase.Running),
)

/**
 * 取景区的占位图：深色底 + 居中说明。
 *
 * 刻意**不**画任何像照片的东西（渐变、人脸轮廓、相机图标都不画）—— 截图里没有相机，
 * 画得越像就越像在声称"这就是预览"。
 */
@Composable
private fun ScanPreviewPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().background(Color(0xFF2A2A2A)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "相机预览（截图占位）",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(16.dp),
        )
    }
}

// ============================================================ 状态槽：长文本与错误通道
//
// 这一节补的是 `docs/plans/2026-10-05-relay-state-slot-completion.md` §1.2 里标成
// 「已实现·证据不足」的那一批槽：**分支代码都在，只是没有一张图能证明它渲染正确**。
// 判据是那一节的定义 —— 「生产代码里能定位到该分支，但没有任何图能证明它渲染正确」。
//
// 三类，每类都在参考图里各占一帧：
//   - **错误通道**：会话页与设置页的 `state.banner`（`ConversationsScreen.kt` /
//     `SettingsScreen.kt` 的 `AppBanner`），以及设置页**另一条**通道 `settingsNote`
//     （Info 语气，保存成功后的就地反馈）。两者不是同一个字段，所以是两帧不是一帧。
//   - **长文本**：昵称上限是 32 字（`RelaySettings.LIMIT_NICK`），但会话行、聊天页顶栏、
//     标签条、文件卡片、事件流、手动节点地址这几处**各有各的收不住的方式**，
//     一张长图证明不了另一处。
//   - **空**：聊天页选了会话但一条消息都没有。

/**
 * 32 字上限附近的长昵称 + 长团队串。
 *
 * 刻意贴着 `RelaySettings.LIMIT_NICK = 32` 造：短一点的名字在会话行里根本不会触发省略，
 * 那样这一帧就只是又一张普通会话图，什么都没钉住。
 */
private val longNickPeer = PeerUi(
    nodeId = "e5e5e5e5-5555-4555-8555-e5e5e5e5e5e5",
    nick = "三号楼东侧会议室那台常年不关机的演示机",
    company = "研发中心",
    dept = "客户端基础设施与工具链",
    host = "Meeting-Room-3-East",
    ip = "192.168.1.208",
    platform = "macOS 15.3",
    tcpPort = 17879,
    caps = listOf("fd1", "tw1"),
    online = true,
    lastSeenAt = atOdd(1),
)

/**
 * 长文本：一条长到接近 800 字节上限的消息，**排在最后一条**。
 *
 * 顺序是刻意的：聊天页进来就滚到底，长消息若不是最后一条，参考图里只剩它的尾巴，
 * 而这一帧要证明的正是「800 字节、没有空格的长 URL、中英混排都不撑破气泡」。
 * 会话列表那边同理 —— 预览取的是最后一条的正文。
 */
private val longTextState = RelayUiState(
    settings = selfSettings,
    network = runningNetwork,
    peers = listOf(longNickPeer),
    messages = mapOf(
        longNickPeer.nodeId to listOf(
            MessageUi(
                id = "lt1",
                peerId = longNickPeer.nodeId,
                mine = false,
                text = "设计规范更新了，输入框那一段要按新的来。",
                at = atOdd(12),
            ),
            MessageUi(
                id = "lt2",
                peerId = longNickPeer.nodeId,
                mine = true,
                text = "这是一条贴近 800 字节上限的长消息，用来验证气泡在没有任何空格的长 URL、" +
                    "中文与英文混排时都不会把布局挤爆：" +
                    "https://example.com/a/very/long/path/without/any/spaces/" +
                    "that/should/not/break/the-bubble-and-must-wrap-cleanly",
                at = atOdd(13),
                delivery = DeliveryState.Sent,
            ),
        ),
    ),
)

/**
 * 传输失败与已拒绝：`ChatScreen` 里 `TransferState.Failed` / `Declined` 两条分支。
 *
 * 既有的 `chat-files-phone` 覆盖的是 Offered / Active / Completed 三态 —— 那是**正常**路径。
 * 这两条是异常路径，也是唯一会画「失败原因」和「对方拒绝了」的地方；不录一张，
 * 哪天这两支整段消失，门禁不会有任何反应。
 */
private val failedTransferState = RelayUiState(
    settings = selfSettings,
    network = runningNetwork,
    peers = listOf(humanPeer),
    messages = mapOf(
        humanPeer.nodeId to listOf(
            MessageUi(
                id = "ff1",
                peerId = humanPeer.nodeId,
                mine = false,
                text = "",
                at = atOdd(10),
                file = FileCardUi(
                    transferId = "t-fail",
                    name = "季度复盘.pptx",
                    size = 18_432_000L,
                    done = 4_194_304L,
                    mine = false,
                    state = TransferState.Failed,
                    reason = "hash-mismatch",
                ),
            ),
            MessageUi(
                id = "ff2",
                peerId = humanPeer.nodeId,
                mine = true,
                text = "",
                at = atOdd(11),
                file = FileCardUi(
                    transferId = "t-declined",
                    name = "会议录音.m4a",
                    size = 8_388_608L,
                    mine = true,
                    state = TransferState.Declined,
                ),
            ),
        ),
    ),
)

/**
 * 文件夹气泡：`ChatScreen` 里 `FileCardView` 的 `card.isFolder` 两支 ——
 * `.fi` 里那颗文件夹图标（`ChatScreen.kt:1173`）与计数
 * `transfer_folder_count`（`ChatScreen.kt:1203`）。
 *
 * 为什么单开一张：全仓 `isFolder = true` 此前**零命中**，也就是说不存在任何一帧
 * 渲染过这两支。它们不是装饰 —— 文件夹的副标题走的是另一条格式化分支
 * （`if (card.isFolder)` 优先于 `else if (card.fileCount > 1)`），图标也换了一颗。
 *
 * 第 21 轮起原型 `.filecard` 只有**一个**容器（`fileCard()` 与 `folderCard()` 共用），
 * 文件夹与单文件的区别只在 `.fi` 的图标；原先另画的那枚「文件夹」角标已删（它是
 * `.fs` 那行「N 个文件 · 大小」的第二个说法）。
 * 哪天这两支整段消失，既有的 `chat-files-phone` / `chat-transfer-failed` 都不会有任何反应。
 *
 * `fileCount = 3` 而不是 1：只有 `> 1` 才让 `transfer_folder_count` 带上那个数字，
 * 写成 1 的话这一帧和普通文件卡长得一样，钉不住任何东西。
 */
private val folderTransferState = RelayUiState(
    settings = selfSettings,
    network = runningNetwork,
    peers = listOf(humanPeer),
    messages = mapOf(
        humanPeer.nodeId to listOf(
            MessageUi(
                id = "fd1",
                peerId = humanPeer.nodeId,
                mine = false,
                text = "",
                at = atOdd(12),
                file = FileCardUi(
                    transferId = "t-folder",
                    name = "2026 会议材料",
                    size = 24_600_000L,
                    fileCount = 3,
                    mine = false,
                    isFolder = true,
                    state = TransferState.Offered,
                ),
            ),
        ),
    ),
)

/**
 * 会话内搜索的命中行：三条，把 `HistoryHitItem` 会画的**三种图标**各占一个。
 *
 * `HistoryHitItem`（`HistorySearchOverlay.kt:436-440`）按 `fileName` 的后缀把图标分成
 * `Icons.Outlined.Image` / `InsertDriveFile` / `Outlined.Chat` 三种：带图片后缀的文件、
 * 带非图片后缀的文件、以及没有附件的纯文本。只喂一种，那张图里就只有一个图标，
 * 另外两条分支等于没拍 —— 这正是"看起来覆盖了、其实没覆盖"。
 *
 * 为什么图片那条靠 `.png` 后缀而不是靠 `isImage = true`：那一行的判据**就是后缀**
 * （`ext in listOf("png", "jpg", …)`，`HistorySearchOverlay.kt:421-424`），
 * `MessageSearchResult.isImage` 在手机浮层这条渲染路径上根本没被读。写成
 * `isImage = true` 但文件名不带后缀，图标仍然是文件图标。
 *
 * `timestamp` 走 `atOdd(n)` 固定偏移：命中行右侧显示 `formatClock`，读挂钟就不可复现。
 */
private val historySearchHits = listOf(
    MessageSearchResult(
        id = "hs1",
        convId = humanPeer.nodeId,
        peerId = humanPeer.nodeId,
        senderId = humanPeer.nodeId,
        mine = false,
        timestamp = atOdd(9),
        snippetText = "",
        fileName = "评审纪要.png",
        isImage = true,
    ),
    MessageSearchResult(
        id = "hs2",
        convId = humanPeer.nodeId,
        peerId = humanPeer.nodeId,
        senderId = humanPeer.nodeId,
        mine = false,
        timestamp = atOdd(10),
        snippetText = "",
        fileName = "季度复盘.pdf",
    ),
    MessageSearchResult(
        id = "hs3",
        convId = humanPeer.nodeId,
        peerId = humanPeer.nodeId,
        senderId = selfSettings.nodeId,
        mine = true,
        timestamp = atOdd(11),
        snippetText = "下周三之前把这一版结论发出来，别再拖了。",
    ),
)

/**
 * 手动节点地址长到放不下。
 *
 * 用 IPv6 而不是随便编一串字母：`fe80::` 链路本地地址**天生就长**，是这条路径上真会出现的
 * 形状（用户从对端界面抄过来的就是它）。编一个 60 字的假主机名能撑破布局，但那种数据
 * 永远不会出现，钉住的也就不是真问题。
 */
private val longManualPeerState = RelayUiState(
    settings = selfSettings.copy(
        manualPeers = listOf(
            ManualPeerEntry(host = "fe80::1c2d:3e4f:5a6b:7c8d%en0", port = 17878),
            ManualPeerEntry(host = "192.168.1.23", port = 17878),
        ),
    ),
    network = runningNetwork,
    peers = listOf(humanPeer),
)

/**
 * Agent 事件流里的长文本：长会话标题 + 长事件正文。
 *
 * 事件流的正文是**自由文本**（`agent-event` 的 `body` 由对端给），日志行、报错栈、
 * 一整段模型输出都可能塞进来，是全仓最容易被撑破的一处。
 */
private val longAgentState = agentWithSessionsState.copy(
    agentSessions = mapOf(
        AgentSessionKey(codexAgent.nodeId, "s-1a97a6f3") to AgentSessionUi(
            peerId = codexAgent.nodeId,
            sessionId = "s-1a97a6f3",
            alias = "refactor runner",
            agentKind = "codex",
            state = "busy",
            title = "把撤回窗口补上并复核 docs/plans 里十份文档的未完成项",
            startedAt = atOdd(4),
            updatedAt = atOdd(23),
        ),
    ),
    agentEvents = mapOf(
        AgentSessionKey(codexAgent.nodeId, "s-1a97a6f3") to listOf(
            AgentEventUi(
                sessionId = "s-1a97a6f3",
                peerId = codexAgent.nodeId,
                cursor = 41,
                eventType = "tool.started",
                summary = "python3 tools/check-design-palette.py --strict",
                at = atOdd(18),
            ),
            AgentEventUi(
                sessionId = "s-1a97a6f3",
                peerId = codexAgent.nodeId,
                cursor = 42,
                eventType = "assistant.text",
                summary = "参考图里说不出出处的颜色有 0 张",
                body = "这条正文故意写长：事件流的 body 是对端给的自由文本，日志行、" +
                    "报错栈、一整段模型输出都可能塞进来。它必须换行，而不是把事件卡" +
                    "横向撑破，也不该把时间与游标挤出行外。",
                at = atOdd(23),
            ),
        ),
    ),
)

/** 长资料：四个字段全贴着 32 字上限。设置页的输入框与节点页的副标题都要收得住。 */
private val longProfileState = humanOnlyState.copy(
    settings = selfSettings.copy(
        nick = "我的手机（借给设计评审那台备用机）",
        company = "研发中心 · 客户端基础设施与工具链",
        dept = "客户端基础设施与工具链",
        team = "移动端与桌面端跨平台小组",
    ),
)

/** 会话页 / 设置页上真挂着一条错误横幅。语气是 `Error`（红），不是 Info。 */
private val errorBannerState = humanOnlyState.copy(
    banner = BannerUi(
        message = BannerMessage.BindFailed(17_878, "Address already in use"),
        tone = BannerTone.Error,
    ),
)

/**
 * 设置页的**另一条**通道：`settingsNote`（Info 语气，保存成功后的就地反馈）。
 *
 * 与 [errorBannerState] 不是同一个字段，也不同时出现 —— `SettingsScreen` 里它们是相邻的
 * 两个 `let`。一帧证明不了另一帧。
 */
private val settingsNoteState = humanOnlyState.copy(
    settingsNote = BannerMessage.PortsSaved(udp = 17_878, tcp = 17_879),
)

@PreviewTest
@Preview(name = "conversations-banner", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ConversationsBannerPhonePreview() {
    AppTheme(darkTheme = false) {
        ExtraScreen(AppRoutes.CONVERSATIONS, badges = navBadges(errorBannerState)) { inner ->
            ConversationsScreen(
                state = errorBannerState,
                onOpenChat = {},
                onToggleMute = {},
                onDeleteConversation = {},
                onGoToPairing = {},
                onDismissBanner = {},
                modifier = inner,
                now = PREVIEW_NOW_ODD,
            )
        }
    }
}

@PreviewTest
@Preview(name = "conversations-long-text", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ConversationsLongTextPhonePreview() {
    AppTheme(darkTheme = false) {
        ExtraScreen(AppRoutes.CONVERSATIONS, badges = navBadges(longTextState)) { inner ->
            ConversationsScreen(
                state = longTextState,
                onOpenChat = {},
                onToggleMute = {},
                onDeleteConversation = {},
                onGoToPairing = {},
                onDismissBanner = {},
                modifier = inner,
                now = PREVIEW_NOW_ODD,
            )
        }
    }
}

/**
 * 状态槽：选了会话，但一条消息都没有。
 *
 * 与 `chat-no-selection` 是**两件事**：那一张的对端是 `null`（还没选会话），
 * 这一张选了人、只是还没聊过。`ChatScreen.kt` 里是两个不同的分支，文案也不同。
 */
@PreviewTest
@Preview(name = "chat-empty", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ChatEmptyPhonePreview() {
    AppTheme(darkTheme = false) {
        ExtraScreen(AppRoutes.CONVERSATIONS, badges = navBadges(humanOnlyState)) { inner ->
            ChatScreen(
                data = ChatScreenState(
                    state = humanOnlyState,
                    peerId = humanPeer.nodeId,
                ),
                actions = chatPreviewActions(),
                chrome = ChatScreenChrome(
                    onBack = {},
                    modifier = inner,
                )
            )
        }
    }
}

@PreviewTest
@Preview(name = "chat-long-text", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ChatLongTextPhonePreview() {
    AppTheme(darkTheme = false) {
        ExtraScreen(AppRoutes.CONVERSATIONS, badges = navBadges(longTextState)) { inner ->
            ChatScreen(
                data = ChatScreenState(
                    state = longTextState,
                    peerId = longNickPeer.nodeId,
                ),
                actions = chatPreviewActions(),
                chrome = ChatScreenChrome(
                    onBack = {},
                    modifier = inner,
                )
            )
        }
    }
}

@PreviewTest
@Preview(name = "chat-transfer-failed", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ChatTransferFailedPhonePreview() {
    AppTheme(darkTheme = false) {
        ExtraScreen(AppRoutes.CONVERSATIONS, badges = navBadges(failedTransferState)) { inner ->
            ChatScreen(
                data = ChatScreenState(
                    state = failedTransferState,
                    peerId = humanPeer.nodeId,
                ),
                actions = chatPreviewActions(),
                chrome = ChatScreenChrome(
                    onBack = {},
                    modifier = inner,
                )
            )
        }
    }
}

/**
 * 状态槽：失败传输上的**续传按钮**（同一份 [failedTransferState]，只多传两个回调）。
 *
 * 为什么这一帧和 `chat-transfer-failed` 必须同时存在：上面那张虽然画着失败卡片，
 * 但它没传 `onResumeTransfer`，于是 `ChatScreen.kt:1413` 的
 * `if (!card.mine && onResumeTransfer != null)` 整支不成立 —— 那张图里**没有续传按钮**，
 * 也没有旁边那条取消入口（`ChatScreen.kt:1430` 的 `if (onCancel != null)`）。
 * 换句话说，「失败卡片长什么样」有图，「失败了能怎么办」一直没图。
 *
 * 这一帧只改回调、不改 fixture：两张图并排一比，就能看出按钮是这两个参数**画出来的**，
 * 而不是卡片状态自带的。
 */
@PreviewTest
@Preview(name = "chat-transfer-resume", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ChatTransferResumePhonePreview() {
    AppTheme(darkTheme = false) {
        ExtraScreen(AppRoutes.CONVERSATIONS, badges = navBadges(failedTransferState)) { inner ->
            ChatScreen(
                data = ChatScreenState(
                    state = failedTransferState,
                    peerId = humanPeer.nodeId,
                ),
                actions = chatPreviewActions(
                    onResumeTransfer = {},
                    onCancelTransfer = {},
                ),
                chrome = ChatScreenChrome(
                    onBack = {},
                    modifier = inner,
                )
            )
        }
    }
}

/**
 * 状态槽：文件夹气泡（[folderTransferState]）—— `card.isFolder` 的图标与计数两支。
 *
 * 覆盖 `ChatScreen.kt:1173` 的 `card.isFolder -> Res.drawable.ic_folder`（`.fi` 图标）与
 * `ChatScreen.kt:1203` 的 `if (card.isFolder)`（`transfer_folder_count` 副标题）。
 * 全仓此前 `isFolder = true` 零命中，这两支从未被渲染过；没有这一帧，它们整段删掉
 * 也不会有任何参考图变红。
 */
@PreviewTest
@Preview(name = "chat-folder-phone", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ChatFolderPhonePreview() {
    AppTheme(darkTheme = false) {
        ExtraScreen(AppRoutes.CONVERSATIONS, badges = navBadges(folderTransferState)) { inner ->
            ChatScreen(
                data = ChatScreenState(
                    state = folderTransferState,
                    peerId = humanPeer.nodeId,
                ),
                actions = chatPreviewActions(),
                chrome = ChatScreenChrome(
                    onBack = {},
                    modifier = inner,
                )
            )
        }
    }
}

/**
 * 状态槽：会话内搜索浮层（`ChatScreen.kt:629` 的
 * `if (showHistorySearch && onSearchInConversation != null)`）。
 *
 * 靠 `initialHistorySearch = true` 打开：浮层开合是屏内 `remember`，入口是头部那个放大镜，
 * 静态帧点不了 —— 没有这个接缝，整个浮层（搜索框、三个类型 chip、日期筛选、命中行）
 * 一张参考图都没有。
 *
 * 命中行给三条、把 `HistoryHitItem` 会画的**三种图标各占一个**（图片 / 文件 / 纯文本）：
 * 它按后缀分三种图标（`HistorySearchOverlay.kt:436-440`），只喂一种就等于另外两种分支没拍。
 */
@PreviewTest
@Preview(name = "chat-history-search", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ChatHistorySearchPhonePreview() {
    AppTheme(darkTheme = false) {
        ExtraScreen(AppRoutes.CONVERSATIONS, badges = navBadges(humanOnlyState)) { inner ->
            ChatScreen(
                data = ChatScreenState(
                    state = humanOnlyState,
                    peerId = humanPeer.nodeId,
                ),
                actions = chatPreviewActions(
                    onSearchInConversation = { _, _ -> historySearchHits },
                ),
                chrome = ChatScreenChrome(
                    onBack = {},
                    initialHistorySearch = true,
                    modifier = inner,
                )
            )
        }
    }
}

/**
 * 「已保存」那一段里的长地址放不下时会怎样。
 *
 * **这一帧比真机屏高（1400 而不是 852）**：节点页的「已保存」卡片排在「本机 → 我的二维码 →
 * 局域网发现」之后，852 那一屏里它在折叠线以下，截出来根本看不到要钉的那一行 —— 一张
 * 看不到目标的图不算证据。拉高只为把整页落进同一张图，不代表任何真实屏幕尺寸。
 */
@PreviewTest
@Preview(name = "nodes-long-text", widthDp = 393, heightDp = 1400, showBackground = true, locale = "zh-rCN")
@Composable
private fun NodesLongTextPhonePreview() {
    AppTheme(darkTheme = false) {
        ExtraScreen(AppRoutes.NODES) { inner ->
            NodesScreen(
                state = longManualPeerState,
                onAddPeer = { _, _ -> },
                onRemovePeer = {},
                onCleanupUnreachable = {},
                onRetryStart = {},
                onDismissBanner = {},
    modifier = inner,
            )
        }
    }
}

@PreviewTest
@Preview(name = "agents-long-text", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun AgentsLongTextPhonePreview() {
    AppTheme(darkTheme = false) {
        ExtraScreen(AppRoutes.AGENTS, badges = navBadges(longAgentState)) { inner ->
            AgentConsoleScreen(
                state = longAgentState,
                selectedAgentId = codexAgent.nodeId,
                onSelectAgent = {},
                onOpenChat = { _, _ -> },
                onFindNodes = {},
                onRefresh = {},
                onRespondApproval = { _, _ -> },
                onCancelTask = {},
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
                now = PREVIEW_NOW_ODD,
            )
        }
    }
}

@PreviewTest
@Preview(name = "settings-banner", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun SettingsBannerPhonePreview() {
    AppTheme(darkTheme = false) {
        ExtraScreen(AppRoutes.SETTINGS, badges = navBadges(errorBannerState)) { inner ->
            SettingsScreen(
                state = errorBannerState,
                dataDirectory = "/data/user/0/com.kapp.tidings/files/relay",
                cacheDirectory = "/data/user/0/com.kapp.tidings/cache/relay",
                onSaveProfile = { _, _, _, _, _ -> },
                onSavePorts = { _, _ -> },
                onRestart = {},
                onDismissBanner = {},
                onClearNote = {},
                // 武装这一支：可空出口不传则依赖它的界面根本不渲染（截图证据里没有它）。
                onRemovePickedAvatar = {},
                modifier = inner,
            )
        }
    }
}

@PreviewTest
@Preview(name = "settings-note", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun SettingsNotePhonePreview() {
    AppTheme(darkTheme = false) {
        ExtraScreen(AppRoutes.SETTINGS, badges = navBadges(settingsNoteState)) { inner ->
            SettingsScreen(
                state = settingsNoteState,
                dataDirectory = "/data/user/0/com.kapp.tidings/files/relay",
                cacheDirectory = "/data/user/0/com.kapp.tidings/cache/relay",
                onSaveProfile = { _, _, _, _, _ -> },
                onSavePorts = { _, _ -> },
                onRestart = {},
                onDismissBanner = {},
                onClearNote = {},
                // 武装这一支：可空出口不传则依赖它的界面根本不渲染（截图证据里没有它）。
                onRemovePickedAvatar = {},
                modifier = inner,
            )
        }
    }
}

@PreviewTest
@Preview(name = "settings-long-text", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun SettingsLongTextPhonePreview() {
    AppTheme(darkTheme = false) {
        ExtraScreen(AppRoutes.SETTINGS, badges = navBadges(longProfileState)) { inner ->
            SettingsScreen(
                state = longProfileState,
                dataDirectory = "/data/user/0/com.kapp.tidings/files/relay",
                cacheDirectory = "/data/user/0/com.kapp.tidings/cache/relay",
                onSaveProfile = { _, _, _, _, _ -> },
                onSavePorts = { _, _ -> },
                onRestart = {},
                onDismissBanner = {},
                onClearNote = {},
                // 武装这一支：可空出口不传则依赖它的界面根本不渲染（截图证据里没有它）。
                onRemovePickedAvatar = {},
                modifier = inner,
            )
        }
    }
}

/**
 * 设置第二屏的长文本：数据目录长到会折行，协议摘要也不再是一行短串。
 *
 * 高度仍取 1600dp（与既有两张一致）：这一屏本来就是一屏放不下的，压到 852 只会
 * 把存储卡整段切出画面，证明不了「长路径会不会把卡片撑破」。
 */
@PreviewTest
@Preview(name = "settings-more-long-text", widthDp = 393, heightDp = 1600, showBackground = true, locale = "zh-rCN")
@Composable
private fun SettingsMoreLongTextPhonePreview() {
    AppTheme(darkTheme = false) {
        ExtraScreen(AppRoutes.SETTINGS, badges = navBadges(longProfileState)) { inner ->
            SettingsDataSubpage(
                modifier = inner,
                // 必须用**外置**路径：应用私有路径会被 `isAppPrivateStoragePath` 换成人话，
                // 于是这一帧与 settings-more-storage 渲染成同一张图（门禁抓到的「重复参考图」）。
                // 这一帧的职责就是「长路径会不会把这一行撑破」，所以要让它真的把路径显示出来。
                dataDirectory = "/storage/emulated/0/Download/2026-10-07-quarterly-review-final-v3",
                cacheDirectory = "/storage/emulated/0/Android/data/com.kapp.tidings/cache/relay/thumbs",
                onBack = {},
                dataUsageBytes = 268_435_456L,
                cacheUsageBytes = 33_554_432L,
                // 残片给非零值：这一帧要拍的是"长路径会不会把这一行撑破"，
                // 而 `leftoverPartCount == null/0` 会让整行只剩「正在统计…」/「没有残片」，
                // 撑不撑破就看不出来了。
                leftoverPartCount = 3,
                leftoverPartBytes = 19_083_674L,
                onClearLeftoverParts = {},
                persistMessagesEnabled = true,
                onPersistMessagesEnabledChange = {},
                onClearHistory = {},
                onExperimentalLuciolaChange = {},
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 群聊（README TODO ⑤）
// ---------------------------------------------------------------------------

/**
 * 一个三个人的群：自己、[humanPeer]、[otherHumanPeer]。
 *
 * `members` 里**必须**有自己 —— `GroupMerge.decide` 对本地不认识的群会先看
 * "我在不在成员表里"，不在就整帧不收（`Reject("not-a-member")`）。预览里少写自己，
 * 这个群根本不会出现在会话列表，参考图会拍成一张空列表，而门禁以为覆盖了。
 */
private const val GROUP_ID = "g-20261007-qiushan"

private val groupMeta = GroupMeta(
    groupId = GROUP_ID,
    name = "周末爬山",
    members = listOf(selfSettings.nodeId, humanPeer.nodeId, otherHumanPeer.nodeId),
    rev = 3,
    updatedBy = selfSettings.nodeId,
    updatedTs = atOdd(-60),
    ownerId = selfSettings.nodeId,
)

private val groupConvId = ConvId.group(GROUP_ID)

/**
 * 群会话里最后一条是别人说的：会话列表那一行要显示群名、`2/3 人在线`、以及预览文本。
 *
 * 顺带钉住「群行不画那个压角的在线圆点」—— `ConversationUi.online` 对群恒为 false，
 * 硬画会让每个群都显示成离线，而这里两个人明明在线。
 */
private val groupListState = RelayUiState(
    settings = selfSettings.copy(groups = listOf(groupMeta)),
    network = runningNetwork,
    peers = listOf(humanPeer, otherHumanPeer),
    messages = mapOf(
        groupConvId to listOf(
            MessageUi(
                id = "g-list-1",
                peerId = groupConvId,
                mine = false,
                text = "周六早上八点山脚下集合，别迟到",
                at = atOdd(-8),
                senderId = humanPeer.nodeId,
            ),
        ),
    ),
    unreadByPeer = mapOf(groupConvId to 2),
)

/**
 * 群会话本身：两个发言人各一条、自己一条、外加一条"群成员有变化"的系统提示。
 *
 * 三件事同框才证明得完：署名画的是**别人**的名字（自己那条不画署名）、
 * 系统提示在时间线里是一行灰字而不是气泡、头部画的是群名与 `n/3 人在线`
 * 而不是某个人的在线状态。
 */
private val groupChatState = RelayUiState(
    settings = selfSettings.copy(groups = listOf(groupMeta)),
    network = runningNetwork,
    peers = listOf(humanPeer, otherHumanPeer),
    messages = mapOf(
        groupConvId to listOf(
            MessageUi(
                id = "g-chat-1",
                peerId = groupConvId,
                mine = false,
                text = "周六早上八点山脚下集合，别迟到",
                at = atOdd(-8),
                senderId = humanPeer.nodeId,
            ),
            MessageUi(
                id = "g-chat-2",
                peerId = groupConvId,
                mine = true,
                text = "我带炉子和气罐，谁带水？",
                at = atOdd(-7),
                delivery = DeliveryState.Sent,
                senderId = selfSettings.nodeId,
            ),
            MessageUi(
                id = "g-chat-3",
                peerId = groupConvId,
                mine = false,
                text = "",
                at = atOdd(-6),
                notice = NoticeKind.GroupMembersChanged,
            ),
            MessageUi(
                id = "g-chat-4",
                peerId = groupConvId,
                mine = false,
                text = "我带四升，从北门上山",
                at = atOdd(-5),
                senderId = otherHumanPeer.nodeId,
            ),
        ),
    ),
)

/**
 * 状态槽：会话列表里的群那一节。
 *
 * 与 `conversations-banner` 那张（一个群都没有、走 `conv_no_groups` 提示）是两件事，
 * 两张都在：那一张证明空态说得清楚，这一张证明有群时行画得对。
 */
@PreviewTest
@Preview(name = "conversations-group", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ConversationsGroupPhonePreview() {
    AppTheme(darkTheme = false) {
        ExtraScreen(AppRoutes.CONVERSATIONS, badges = navBadges(groupListState)) { inner ->
            ConversationsScreen(
                state = groupListState,
                onOpenChat = {},
                onToggleMute = {},
                onDeleteConversation = {},
                onGoToPairing = {},
                onDismissBanner = {},
                modifier = inner,
                now = PREVIEW_NOW_ODD,
                onCreateGroup = { _, _ -> },
            )
        }
    }
}

/**
 * 状态槽：建群对话框。
 *
 * 靠 `initialCreating = true` 打开 —— 这个对话框是屏内私有状态，没有这个接缝
 * 就只能靠人手点，门禁永远抓不到它。名单里刻意留了两个真人节点，
 * 才看得见"整行可点 + 复选框本身不可点"这条规矩。
 */
@PreviewTest
@Preview(name = "conversations-group-create", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ConversationsGroupCreatePhonePreview() {
    AppTheme(darkTheme = false) {
        ExtraScreen(AppRoutes.CONVERSATIONS, badges = navBadges(humanOnlyState)) { inner ->
            ConversationsScreen(
                state = humanOnlyState,
                onOpenChat = {},
                onToggleMute = {},
                onDeleteConversation = {},
                onGoToPairing = {},
                onDismissBanner = {},
                modifier = inner,
                now = PREVIEW_NOW_ODD,
                onCreateGroup = { _, _ -> },
                initialCreating = true,
            )
        }
    }
}

/** 状态槽：群会话。头部、署名、系统提示三件事同框。 */
@PreviewTest
@Preview(name = "chat-group", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ChatGroupPhonePreview() {
    AppTheme(darkTheme = false) {
        ExtraScreen(AppRoutes.CONVERSATIONS, badges = navBadges(groupChatState)) { inner ->
            ChatScreen(
                data = ChatScreenState(
                    state = groupChatState,
                    peerId = groupConvId,
                ),
                actions = chatPreviewActions(),
                chrome = ChatScreenChrome(
                    onBack = {},
                    modifier = inner,
                )
            )
        }
    }
}
