// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app.screenshot

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.relay.app.model.AgentEventUi
import app.relay.app.model.AgentSessionKey
import app.relay.app.model.AgentSessionUi
import app.relay.app.model.CabinetUiState
import app.relay.app.model.DeliveryState
import app.relay.app.model.FileCardUi
import app.relay.app.model.MessageUi
import app.relay.app.model.NetworkUi
import app.relay.app.model.NodePhase
import app.relay.app.model.PeerUi
import app.relay.app.RelaySettings
import app.relay.app.RelayUiState
import app.relay.app.model.TransferState
import app.relay.app.navBadges
import app.relay.feature.agents.AgentConsoleScreen
import app.relay.feature.cabinet.CabinetScreen
import app.relay.feature.chat.ChatScreen
import app.relay.feature.chat.ChatScreenChrome
import app.relay.feature.chat.ChatScreenState
import app.relay.feature.conversations.ConversationsScreen
import app.relay.feature.settings.SettingsConnectionSubpage
import app.relay.feature.settings.SettingsUpdateSubpage
import app.relay.feature.settings.SettingsProfileSubpage
import app.relay.feature.update.UpdateCheckState
import app.relay.feature.update.UpdateCheckStatus
import app.relay.navigation.AppNavItems
import app.relay.navigation.AppRoutes
import app.relay.platform.Transport
import app.relay.ui.components.CompactScaffold
import app.relay.ui.theme.AppTheme
import com.android.tools.screenshot.PreviewTest

/**
 * 截图门禁的第三批：六个「代码里那一支在、但没有参考图」的状态槽。
 *
 * ## 为什么单开一个文件，而不是补进 `ScreenshotPreviewsExtra.kt`
 *
 * 这六个槽不是"想多拍几张"，是**完成度报告里被工具判成缺口的那几条**：
 *
 * | 槽 | 代码锚点 | 锚点命中 | 为什么原先没有证据 |
 * |---|---|---|---|
 * | `chat / transfer_in_progress` | `feature/chat/ChatScreen.kt` 的 `TransferState.` | 5 | 没拍过 |
 * | `agents / error` | `feature/agents/AgentConsoleScreen.kt` 的 `agent_session_state_error` | 2 | 没拍过 |
 * | `settings-more / error` | `feature/settings/SettingsUpdateSubpage.kt` 的 `UpdateCheckStatus.Error` | 1 | 没拍过 |
 * | `conversations / offline_queue_banner` | `feature/conversations/ConversationsScreen.kt` 的 `conv_offline_banner` | 2 | **拍过，但拍到的是另一条横幅**（见该帧 KDoc） |
 * | `cabinet / empty` | `feature/cabinet/PeerCabinetView.kt` 的 `entries\.isEmpty\(\)` | 0 | **代码里本来就没有这一支**，本批补的（见该帧 KDoc） |
 * | `cabinet / error` | `feature/cabinet/PeerCabinetView.kt` 的 `errorMessage` | 0 | **同上**：`CabinetUiState.errorMessage` 数据层一直有，UI 层零读取 |
 *
 * 前四支代码都在，预览缺 —— 也就是「删掉整段分支，参考图不会有任何反应」。
 * 修法只能是补帧，补帧必须落在可写文件里（`ScreenshotPreviewsExtra.kt` 等五个文件
 * 当时由并行的另一会话在途），所以这里自带夹具。
 *
 * 后两支（`cabinet / empty`、`cabinet / error`）比前四支更靠前一步：**分支本身也是这一批补的**。
 * 它们的锚点原先指向 `feature/cabinet/CabinetScreen.kt` —— 那是**指错了文件**：
 * 该文件只做「选了自己还是选了同事」的分发，目录列表渲染在 `PeerCabinetView.kt`。
 * 锚点指向一个永远不会出现这两行的文件，命中数恒为 0，看起来像"功能没做"，
 * 其实连"该去哪找"都指错了。**量具指错文件，比量具漏量更难发现** ——
 * 漏量至少读数还是 0，指错文件会让 0 显得理所当然。
 *
 * 第四支的成因不同，值得单说：它**有**一张被词表认领的图，但那张图钉的是同一屏上
 * 的另一条横幅（`state.banner` 而不是 `totalPending`）。**名字能匹配，不代表匹配的是
 * 同一个东西** —— 这类"覆盖数虚高"只有把帧拍出来、再收窄词表才修得掉。
 *
 * ## 自带夹具的代价，与为什么不复用
 *
 * `ScreenshotPreviews.kt` / `ScreenshotPreviewsExtra.kt` 里的夹具与 `at()` / `PREVIEW_NOW`
 * 几乎全是 `private`（文件私有），跨文件拿不到；能拿到的 `internal` 只有 `offlineAgent` /
 * `agentWithSessionsState` 少数几个，而它们表达的**不是**这几帧要钉的状态。
 * 与其为了复用去改对面在途的文件，不如在这里造一份最小的、只服务这几帧的夹具。
 *
 * 代价是同一个 `RelaySettings`/`NetworkUi` 在这里有第二份写法。这个代价是**有界的**：
 * 这里只声明这几帧真正会读到的字段，且下面每一条都写了"为什么是这个值"。
 * 反过来说，如果哪天有人把 `at()` 的口径改了，这两份夹具会各自漂移 —— 所以时间基
 * 与 `ScreenshotPreviews.kt` 保持**同一个常量值**（`1_735_689_600_000L`，2025-01-01T00:00:00Z，
 * UTC+8 是 08:00），而不是各写各的。
 *
 * ## 三条规矩（与另外两个预览文件完全一致）
 *
 * 1. 渲染**真正被安装包调用**的屏幕组合函数，并套一层真正的 [CompactScaffold]；
 * 2. 时间必须确定：`formatClock` 走固定时间戳，`relativeTime` 走固定的"现在"；
 * 3. 造假数据要能站得住 —— 每一处"为什么是这个值"都写在下面，因为它就是这张图的全部意义。
 */

/** 与 `ScreenshotPreviews.kt` 同值：2025-01-01T00:00:00Z（UTC+8 是 08:00）。 */

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

/**
 * 节点已在跑。
 *
 * `Running` 而不是 `Stopped`：这几帧要钉的都是**屏内状态**（会话出错 / 排队横幅 / 更新失败 / 传输进行中），
 * 不是"节点没起来"。节点停在 `Stopped` 时，`AgentsScreen` 之类会先被"没启动"那层挡住，
 * 拍出来的就不是这三支了 —— 造假数据最容易在这里把两件事混成一件。
 */
private val runningNetwork = NetworkUi(
    phase = NodePhase.Running,
    transport = Transport.Wifi,
    localAddresses = listOf("192.168.1.42"),
    boundUdpPort = 17878,
)

/** 一个真人节点：`conversations` 排队横幅、`settings-more` 与 `chat` 三帧用它，证明"有人"，不是空数据。 */
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
 * 一个在线的 Agent 节点。
 *
 * `caps` 必须含 `"ag1"` —— `PeerUi.isAgent` 看的就是它（`RelayUiState.kt:86`）。
 * 少写这一个字符串，`state.agentPeers` 就是空表，`AgentConsoleScreen` 会走进
 * 「一个 Agent 都没有」那条空态并提前 `return@Column`，这张图就什么也钉不住了。
 * 反过来多写一个 `"ag1"` 到真人节点上，真人会变成 Agent。
 */
private val codexAgent = PeerUi(
    nodeId = "c3c3c3c3-3333-4333-8333-c3c3c3c3c3c3",
    nick = "codex · 终端",
    company = "研发",
    dept = "工具链",
    host = "MacBook-Pro",
    ip = "192.168.1.23",
    platform = "linux",
    tcpPort = 17879,
    caps = listOf("ag1", "tw1"),
    online = true,
    lastSeenAt = at(3),
)

/**
 * 状态槽：Agent 控制台里**会话状态是 `error`** 的那一支。
 *
 * 覆盖的分支：`AgentConsoleScreen.kt:557` 的
 * `"error" -> stringResource(Res.string.agent_session_state_error) to MaterialTheme.colorScheme.error`。
 *
 * 为什么这一支此前一张图都没有：`SessionStateLabel` 的四个已知状态里，`idle`/`busy`/`done`
 * 都被既有帧覆盖过（`agents-sessions` 钉的是 `busy` 与 `done`），**只有 `error` 没有**。
 * 也就是说这一支整段删掉、或者被误改成 `onSurfaceVariant`，参考图不会有任何反应 ——
 * 而它恰恰是四个状态里唯一用 `colorScheme.error` 上色的那一个，改错了肉眼最该看得见。
 *
 * 造假数据踩的坑：
 * - **坑一：`state` 必须写协议里的 `"error"`。** 自己编一个（如 `"failed"`）会走
 *   `else -> state to onSurfaceVariant` 那半，画出来是灰色的原文，**不是**这一支。
 *   两个分支都"有字"，参考图上看不出区别，只有颜色能分辨 —— 这正是它需要一张图的原因。
 * - **坑二：`selectedAgentId` 必须显式指到这个 Agent。** `:151` 的兜底是
 *   `agents.firstOrNull { it.nodeId == selectedAgentId } ?: agents.firstOrNull()`，
 *   传 null 会**默默选列表第一个**。这里只有一个 Agent，但显式传值仍然必要 ——
 *   将来有人往这个 fixture 里加第二个 Agent 时，兜底会静默改变这张图的含义。
 * - **坑三：会话卡只在 `agentSessionsOf(peerId)` 非空时画。** 会话必须挂在
 *   `codexAgent.nodeId` 上（`AgentSessionUi.peerId`），挂错节点名会话卡不出现。
 * - **坑四：`announced` 留默认 `true`。** 这张图钉的是"状态标签的颜色"，不是
 *   "只收到事件没收到宣告"那条红字（那一条由 `agents-sessions` 里的 `s-77c1` 负责）。
 *   把两者混进一帧，出问题时不知道是哪一条断了。
 * - **坑五：`cursor` 不是时间戳。** 事件行的时间取 [AgentEventUi.at]；`cursor` 另画一行。
 */
@PreviewTest
@Preview(name = "agents-session-error", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun AgentsSessionErrorPhonePreview() {
    val state = RelayUiState(
        settings = selfSettings,
        network = runningNetwork,
        peers = listOf(codexAgent),
        messages = mapOf(
            codexAgent.nodeId to listOf(
                MessageUi(
                    id = "e1",
                    peerId = codexAgent.nodeId,
                    mine = true,
                    text = "把这次改动的门禁跑一遍。",
                    at = at(5),
                    delivery = DeliveryState.Sent,
                ),
                MessageUi(
                    id = "e2",
                    peerId = codexAgent.nodeId,
                    mine = false,
                    text = "门禁跑挂了，截图参考图对不上，我把控制台贴在上面了。",
                    at = at(23),
                ),
            ),
        ),
        agentSessions = mapOf(
            AgentSessionKey(codexAgent.nodeId, "s-error01") to AgentSessionUi(
                peerId = codexAgent.nodeId,
                sessionId = "s-error01",
                alias = "gate runner",
                agentKind = "codex",
                // 就是这一支：协议取值里的 error。
                state = "error",
                title = "跑 :androidApp:validateDebugScreenshotTest",
                startedAt = at(4),
                updatedAt = at(23),
            ),
        ),
        agentEvents = mapOf(
            AgentSessionKey(codexAgent.nodeId, "s-error01") to listOf(
                AgentEventUi(
                    sessionId = "s-error01",
                    peerId = codexAgent.nodeId,
                    cursor = 40,
                    eventType = "session.started",
                    summary = "开始跑门禁",
                    at = at(4),
                ),
                AgentEventUi(
                    sessionId = "s-error01",
                    peerId = codexAgent.nodeId,
                    cursor = 41,
                    eventType = "tool.started",
                    summary = ":androidApp:validateDebugScreenshotTest",
                    at = at(18),
                ),
                AgentEventUi(
                    sessionId = "s-error01",
                    peerId = codexAgent.nodeId,
                    cursor = 42,
                    eventType = "tool.failed",
                    summary = "截图参考图对不上",
                    body = "3 张参考图与当前渲染不一致，需要确认是有意改动还是回归。",
                    at = at(23),
                ),
            ),
        ),
    )
    AppTheme(darkTheme = false) {
        StateSlotScreen(AppRoutes.AGENTS, badges = navBadges(state)) { inner ->
            AgentConsoleScreen(
                state = state,
                selectedAgentId = codexAgent.nodeId,
                onSelectAgent = {},
                onOpenChat = { _, _ -> },
                onFindNodes = {},
                onRefresh = {},
                onRespondApproval = { _, _ -> },
                onCancelTask = {},
                onOpenSessionDetail = null,
                onBackToBoard = null,
                modifier = inner,
                now = PREVIEW_NOW,
            )
        }
    }
}

/**
 * 状态槽：设置第二屏「版本与更新」卡的**检查更新失败**那一支。
 *
 * 覆盖的分支：`SettingsUpdateSubpage.kt` 里 `when (updateState.status)` 的
 * `UpdateCheckStatus.Error -> Text(stringResource(Res.string.set_update_failed, …), color = colorScheme.error)`。
 *
 * 为什么这一支此前一张图都没有：这张卡有五个状态（`Idle`/`Checking`/`UpToDate`/
 * `UpdateAvailable`/`Error`），既有三帧里 `settings-more-phone` 走的是 `Idle`（什么都不画），
 * **没有一个走 `Error`**。`Error` 是唯一用 `colorScheme.error` 上色的一支，
 * 也是唯一会把 `updateState.errorMessage` 显示给用户的一支 —— 整段删掉不会有图变红。
 *
 * 造假数据踩的坑：
 * - **坑一：`errorMessage` 不能省。** 文案是
 *   `stringResource(Res.string.set_update_failed, updateState.errorMessage.orEmpty())`，
 *   留空会渲染出"更新失败："后面什么都没有的半句话。这句空话在真机上就是
 *   "用户看到报错却不知道错在哪"，而这正是这张图要防的。
 * - **坑二：`status` 必须是 `Error`，不是 `Idle` + 有 `errorMessage`。** `when` 判的是
 *   `status`；把消息填上而状态留在 `Idle`，那一支走的是 `Idle -> Unit`，**一个字都不画**。
 * - **坑三：画幅要够高 —— 而且「跟同屏其他帧一样高」不够。**
 *   「版本与更新」卡在整屏最底部，`393×852` 拍不到它；但 `heightDp = 1600`
 *   （同屏另外三帧 `settings-more-phone` / `-storage` / `-loading` 的画幅）**同样拍不到**：
 *   `verticalScroll` 的视口就是画幅本身，诊断卡在 4200px 处还没画完，
 *   更新卡整张落在视口之外。第一版照抄 `1600` 录出来的图，**从上到下没有一个像素**
 *   属于 `Error` 那一支 —— 状态设了、锚点命中了、门禁绿了，而图里什么都没有。
 *   这正是这张图要防的那种假证据，只不过这次假在拍摄参数上，不在夹具上。
 *   故取 `heightDp = 1820`：先保证被覆盖的那一支真的在画面里，
 *   再谈和同屏其他帧画幅可比 —— 可比性让位于"图里得看得见"。
 *   （实测：更新卡底边在 ~1740dp 处，1820 留出约 80dp 余量，卡的下圆角不被导航栏切；
 *   `1600` 差一点点，`2000` 会在卡下留近 260dp 空白。）
 *   复现判据：`magick <png> -crop 1032x600+0+<h-700> +repage -` 看尾部，
 *   尾部若只有 `自检尚未接线` 就说明更新卡又被裁掉了。
 * - **坑四：`appVersion` 要给值。** 不给时兜底是 `"relay-0.1.0"`，那一行是**编出来的版本号**；
 *   给一个固定串让这行是真值，不是兜底。
 * - **坑五：三个"没有消费者"的回调仍然留 null**（`onChangeDataDirectory` /
 *   `onOpenDataDirectory` / `onRunSelfCheck`）。理由与 `settings-more-phone` 相同：
 *   生产也没接，填空 lambda 会录出一张比真机更好看的假图。
 *   `onCheckForUpdates` 则要给 —— 生产（`App.kt`）接了它，不给的话「检查更新」按钮是灰的，
 *   那又不是真机上的样子。
 */
@PreviewTest
@Preview(name = "settings-more-update-error", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun SettingsMoreUpdateErrorPhonePreview() {
    val state = RelayUiState(
        settings = selfSettings,
        network = runningNetwork,
        peers = listOf(humanPeer),
        updateState = UpdateCheckState(
            status = UpdateCheckStatus.Error,
            info = null,
            errorMessage = "无法连接到更新服务器（DNS 解析失败）",
        ),
    )
    AppTheme(darkTheme = false) {
        StateSlotScreen(AppRoutes.SETTINGS, badges = navBadges(state)) { inner ->
            SettingsUpdateSubpage(
                state = state,
                modifier = inner,
                onBack = {},
                appVersion = "relay-0.1.0",
                onCheckForUpdates = {},
            )
        }
    }
}

/**
 * 状态槽：聊天页文件卡片的**传输进行中**那一支（`TransferState.Active`）。
 *
 * 覆盖的分支：`ChatScreen.kt:1299` 的
 * `TransferState.Active -> { LinearProgressIndicator(progress = { card.fraction }); … }`，
 * 含两条子分支 ——
 * - 收进来的（`card.mine = false`）：进度条 + 「已接收 N%」；
 * - 发出去的（`card.mine = true`）且 `onCancelTransfer != null`：多一颗取消按钮
 *   （`ChatScreen.kt:1328` 的 `if (onCancel != null && card.mine)`）。
 *
 * 一帧里同时放两张卡，因为**这两条子分支的触发条件互斥**（`mine` 的真假），
 * 分成两帧会多一份夹具、还看不出"同一屏里两种进行中长得不一样"。
 *
 * 为什么这一支此前一张图都没有：文件卡的五个状态里，`Offered`（`chat-files-phone`）、
 * `Completed`（`chat-files-phone`）、`Failed`/`Declined`（`chat-transfer-failed`）
 * 都有图，**只有 `Active` 没有**。而 `Active` 是唯一画 `LinearProgressIndicator` 的一支，
 * 也是唯一画取消按钮的一支 —— 这两样整段删掉，既有的四张文件卡参考图都不会有任何反应。
 *
 * 造假数据踩的坑：
 * - **坑一：`done` 必须落在 `0 < done < size` 之间。** `FileCardUi.fraction` 是
 *   `done / size` 并夹到 `0f..1f`。`done = 0` 时进度条是空的（看起来像卡住），
 *   `done == size` 时是满的（看起来像传完了）—— 两种都不代表"进行中"。
 *   这里取 `8_388_608 / 18_432_000 ≈ 45%`，与下面那句「已接收 45%」对得上。
 * - **坑二：取消按钮是 `onCancelTransfer` 画出来的，不是状态自带的。**
 *   不传这个参数，`Active` 那一支仍然成立、进度条仍然画，**但取消按钮不出现** ——
 *   与 `chat-transfer-failed` / `chat-transfer-resume` 那一对同一条理由：
 *   "能怎么办"是参数决定的，必须显式给。
 * - **坑三：`onResumeTransfer` 不给。** 续传只对 `Failed` 有意义；
 *   在这一帧里给一个 `Failed` 才会用的回调，等于让参考图暗示"进行中也能续传"。
 * - **坑四：`at()` 要用固定时间戳。** 气泡时间走 `formatClock`，用 `nowMillis()` 会让
 *   参考图每分钟变一次，门禁永远红灯。
 */
@PreviewTest
@Preview(name = "chat-transfer-active", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ChatTransferActivePhonePreview() {
    val state = RelayUiState(
        settings = selfSettings,
        network = runningNetwork,
        peers = listOf(humanPeer),
        messages = mapOf(
            humanPeer.nodeId to listOf(
                MessageUi(
                    id = "ta0",
                    peerId = humanPeer.nodeId,
                    mine = false,
                    text = "把刚才那份截图发我。",
                    at = at(18),
                ),
                MessageUi(
                    id = "ta1",
                    peerId = humanPeer.nodeId,
                    mine = false,
                    text = "",
                    at = at(19),
                    // 收进来的：进度条 + 「已接收 45%」，没有取消按钮。
                    file = FileCardUi(
                        transferId = "t-in-active",
                        name = "季度复盘.pptx",
                        size = 18_432_000L,
                        done = 8_388_608L,
                        mine = false,
                        state = TransferState.Active,
                    ),
                ),
                MessageUi(
                    id = "ta2",
                    peerId = humanPeer.nodeId,
                    mine = true,
                    text = "",
                    at = at(21),
                    delivery = DeliveryState.Sent,
                    // 发出去的：进度条 + 「已发送 45%」+ 取消按钮（因为下面传了 onCancelTransfer）。
                    file = FileCardUi(
                        transferId = "t-out-active",
                        name = "01-phone-chat-light.png",
                        size = 2_048_000L,
                        done = 921_600L,
                        mine = true,
                        state = TransferState.Active,
                    ),
                ),
            ),
        ),
    )
    AppTheme(darkTheme = false) {
        StateSlotScreen(AppRoutes.CONVERSATIONS, badges = navBadges(state)) { inner ->
            ChatScreen(
                data = ChatScreenState(
                    state = state,
                    peerId = humanPeer.nodeId,
                ),
                actions = chatPreviewActions(
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
 * 会话列表的**离线排队横幅**：`state.totalPending > 0` 时那一条
 * 「有 N 条消息还在等对方上线，会自动补发」。
 *
 * ## 为什么这一帧非补不可
 *
 * 这个槽原先被算成「有证据已覆盖」，靠的是词表 `(?i)banner` 认领了
 * `ConversationsBannerPhonePreview`（`ScreenshotPreviewsExtra.kt:1467`）。但那一帧渲染的是
 * `errorBannerState`（`tone = BannerTone.Error`）⇒ 它钉的是 `ConversationsScreen.kt:221`
 * 的 `state.banner?.let { AppBanner(...) }`，而本槽在 `:225` 的
 * `if (state.totalPending > 0) { … conv_offline_banner … }`。**同一屏上两条横幅，名字里
 * 都只有 banner，一个名字匹配的量具分不清它们** —— 于是「错误横幅的图」被记成了
 * 「排队横幅的证据」，覆盖数还因此涨了一个。这就是第十个量具洞：名字能匹配，不代表
 * 它匹配的是同一个东西。
 *
 * ## 这一帧钉的是哪一支，怎么判
 *
 * `pendingByPeer` 是唯一入口：`totalPending` 是它的派生值（`RelayUiState.kt:808`
 * `val totalPending: Int get() = pendingByPeer.values.sum()`），所以 `2` 这个数字必须写在
 * `pendingByPeer` 上，不能凭空设 `totalPending`。会话行的「待发」计数
 * （`pendingCount = pendingByPeer[id] ?: 0`，`:853`）读的是同一个字段 —— 也就是说这一帧
 * 会同时拍到横幅与行上的计数，两处必须一致，不一致就是我在造假数据。
 *
 * 判定判据（可复制）：图里应出现灰底圆角横幅「有 2 条消息还在等对方上线，会自动补发。」，
 * 且该会话行的预览文案带「2 待发」。只看到其中之一，说明钉的不是这一支。
 */
@PreviewTest
@Preview(name = "conversations-offline-queue", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ConversationsOfflineQueuePhonePreview() {
    val state = RelayUiState(
        settings = selfSettings,
        network = runningNetwork,
        peers = listOf(humanPeer),
        messages = mapOf(
            humanPeer.nodeId to listOf(
                MessageUi(
                    id = "q1",
                    peerId = humanPeer.nodeId,
                    mine = true,
                    text = "把刚才那份截图发我。",
                    at = at(5),
                    // Queued 而不是 Sent：对方此刻不在线，消息排在本地队列里。
                    delivery = DeliveryState.Queued,
                ),
            ),
        ),
        // 2 条排队 —— 这个数字是本帧唯一要钉的东西，其余字段只负责让它可见。
        pendingByPeer = mapOf(humanPeer.nodeId to 2),
    )
    AppTheme(darkTheme = false) {
        StateSlotScreen(AppRoutes.CONVERSATIONS, badges = navBadges(state)) { inner ->
            ConversationsScreen(
                state = state,
                onOpenChat = {},
                onGoToPairing = {},
                onDismissBanner = {},
                modifier = inner,
                now = PREVIEW_NOW,
            )
        }
    }
}

/**
 * 状态槽：同事文件柜里**目录是空的**那一支。
 *
 * 覆盖的分支：`feature/cabinet/PeerCabinetView.kt` 的
 * `else if (cabinet.entries.isEmpty()) { item { EmptyState(...) } }`。
 *
 * ## 这一支为什么此前一张图都没有，而且**原型也没有**
 *
 * 原型 `screenCabinetPeer`（`prototype/relay.html`）里只有"有文件"的那一格，
 * GALLERY 里搜 `empty|空|没有` 只命中 `.cbfhint`（「已选 2 项 · 4.4 MB」）与一条 `.annot`
 * —— **没有空态分支可对齐**。所以这张图钉的不是"原型长这样"，而是"这条分支真的在"：
 * 删掉 `entries.isEmpty()` 那一段，这张参考图会从"空目录提示"变回"只有表头的空列表"。
 * 这正是它作为证据的全部意义 —— 状态槽的分母是 UI 状态槽，不是原型画过的格数。
 *
 * ## 造假数据踩的坑
 *
 * - **坑一：`entries` 必须真空，不能只把 `totalEntries` 写 0。**
 *   `totalEntries` 只喂顶栏的「N 项」计数（`cabinet_peer_item_count`），
 *   列表画的是 `cabinet.entries`。只改计数，列表照样是空 —— 看着像对了，
 *   但 `entries.isEmpty()` 那一支根本没被走到，图钉的是另一条路径。
 * - **坑二：`errorMessage` 必须留 null。** 错误态排在空态前面（`if/else if`），
 *   两者同时给值，拍到的是错误态，这张图就变成 `cabinet-error-phone` 的重复。
 * - **坑三：`currentPath` 要写成一个真实存在的目录名。** 它进面包屑，
 *   写空串会画成"根目录下空"，与"进了某个空文件夹"是两件事。
 * - **坑四：`currentPerm` 用 `read`。** 空目录 + 可写权限时底栏会多出上传入口，
 *   把"空"和"能不能写"两个维度混进一帧 —— 这一帧只钉空态。
 */
@PreviewTest
@Preview(name = "cabinet-empty-phone", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun CabinetEmptyPhonePreview() {
    val state = RelayUiState(
        settings = selfSettings,
        network = runningNetwork,
        peers = listOf(humanPeer),
        cabinet = CabinetUiState(
            activePeerId = humanPeer.nodeId,
            currentPath = "2026 会议",
            // 真空表：不是 totalEntries = 0 而已（见坑一）。
            entries = emptyList(),
            totalEntries = 0,
            currentPerm = "read",
        ),
    )
    AppTheme(darkTheme = false) {
        StateSlotScreen(AppRoutes.CABINET, badges = navBadges(state)) { inner ->
            CabinetScreen(
                state = state,
                onSetShareRoot = {},
                onSetShareMode = {},
                onRemoveShareGrant = {},
                onSelectPeer = {},
                onLoadPath = {},
                onToggleSelection = {},
                onDownloadEntries = { _, _ -> },
                // 显式传空实现而不是留 null：本帧 `currentPerm = "read"`，上传入口本来
                // 就该被权限挡掉 —— 传了它，「图里没有上传按钮」才只有一个原因（只读）。
                // 注意注释要写在参数**同行或上方**、不能夹在参数之间：静态解析器按顶层逗号
                // 切参数，夹在中间的多行注释会把后面那个参数吞进注释块里，`x =` 匹配不到。
                onUploadToCabinet = {},
                modifier = inner,
                now = PREVIEW_NOW,
            )
        }
    }
}

/**
 * 状态槽：同事文件柜**打不开**（控制器把 `deny.reason` 写进了 `errorMessage`）那一支。
 *
 * 覆盖的分支：`feature/cabinet/PeerCabinetView.kt` 的
 * `if (loadError != null) { item { ErrorState(...) } }`。
 *
 * ## 这一支的特殊之处：它是"数据层有、UI 层零"的典型
 *
 * `CabinetUiState.errorMessage` 一直由控制器写入（对端拒绝 / 目录不可达时填 `deny.reason`），
 * 但在补这一支之前，**唯一读它的是单元测试** —— 真实用户点进同事的文件柜，
 * 界面上什么反应都没有，看上去只是"没加载出来"。所以这张图是这条通路的第一份 UI 证据。
 *
 * 原型同样没有画过错误态（`screenCabinetPeer` 只有正常一格），所以取值走 App 既有的
 * `ErrorState` 原语，**不是从原型抄的** —— 不许拿这张图当"原型要求这样做"的依据。
 *
 * ## 造假数据踩的坑
 *
 * - **坑一：必须走 `errorMessage`，不能改 `entries` 来"模拟失败"。**
 *   给一个空 `entries` 拍到的是空态（`else if` 分支），两条分支长得像、成因完全不同。
 * - **坑二：`code` 传空串。** `ErrorState` 里 `error.code.takeIf { it.isNotBlank() }`
 *   决定要不要画第二行机器码；`CabinetUiState` 只存一句人话，
 *   这里编一个 `"ECONNREFUSED"` 会画出一行**产品永远不会画**的文案。
 * - **坑三：文案要写成产品真的会收到的那种。** 这条 `deny.reason` 由对端给出，
 *   所以写成对端口吻（"对方未共享该目录"），不是本机口吻。
 * - **坑四：`currentPath` 保持非空。** 「重新加载」按钮调的是
 *   `onLoadPath(cabinet.currentPath)`；空路径下重试等于回根目录，
 *   图上看不出来，但按钮的语义已经变了。
 */
@PreviewTest
@Preview(name = "cabinet-error-phone", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun CabinetErrorPhonePreview() {
    val state = RelayUiState(
        settings = selfSettings,
        network = runningNetwork,
        peers = listOf(humanPeer),
        cabinet = CabinetUiState(
            activePeerId = humanPeer.nodeId,
            currentPath = "2026 会议",
            entries = emptyList(),
            totalEntries = 0,
            currentPerm = "read",
            // 对端口吻的拒绝原因：这一支钉的就是"这句话终于有地方显示"。
            errorMessage = "对方未共享该目录",
        ),
    )
    AppTheme(darkTheme = false) {
        StateSlotScreen(AppRoutes.CABINET, badges = navBadges(state)) { inner ->
            CabinetScreen(
                state = state,
                onSetShareRoot = {},
                onSetShareMode = {},
                onRemoveShareGrant = {},
                onSelectPeer = {},
                onLoadPath = {},
                onToggleSelection = {},
                onDownloadEntries = { _, _ -> },
                // 显式传空实现而不是留 null：本帧 `currentPerm = "read"`，上传入口本来
                // 就该被权限挡掉 —— 传了它，「图里没有上传按钮」才只有一个原因（只读）。
                // 注意注释要写在参数**同行或上方**、不能夹在参数之间：静态解析器按顶层逗号
                // 切参数，夹在中间的多行注释会把后面那个参数吞进注释块里，`x =` 匹配不到。
                onUploadToCabinet = {},
                modifier = inner,
                now = PREVIEW_NOW,
            )
        }
    }
}

/**
 * 屏配对：设置二级页**我的资料**（台账 `m-set-profile`）。
 *
 * 覆盖的面：`feature/settings/SettingsScreen.kt:424` 的 `SettingsProfileSubpage` 整页。
 *
 * ## 为什么这一屏此前在台账里是「无本屏预览」，而它其实早就实现了
 *
 * 并排配对板（`tools/design-pairing-board.py`）第一次 `--check` 时报
 * 「并排 52/61」，缺的 9 行里就有这一行。缺的原因**不是功能没做** ——
 * `SettingsProfileSubpage` 是 `public`，`SettingsScreen.kt:212` 的
 * `onNavigateProfile = { subpage = SUBPAGE_PROFILE }` 早就把它接上了路由 ——
 * 而是**全项目从来没有为它写过一帧**。台账那一格原先填的是 `CabinetUnsetPhonePreview`，
 * 那是个**跨域误配**（拿文件柜的帧去认领设置页的验收）。
 *
 * 这正是 goal ① 里「图 + Preview 双有」这句话的分量所在：
 * **一行台账有落点、有代码、有验收档，仍然可能一张图都没有**；
 * 而一张填错的图比空格更坏，因为它让这一行看起来已经有人看过了。
 *
 * ## 画幅比手机屏略长（900dp 而不是 852dp）
 *
 * 原型 `screenSetProfile`（`prototype/relay.html:3682`）是 `.pad.set` 里一张
 * `.idcard` + 一张装五个字段的 `.card` + 一条 `.hint`，整页比一屏手机屏长一点。
 * 852 时「保存资料」按钮刚好落在折叠线边上（实测卡片下沿约 844dp），
 * 再高一点就会被裁掉 —— 而被裁掉的恰好是"保存按钮到底画不画"这个分支所在处。
 * 取 900 让整张卡连同卡后 24dp 留白一起进画面。与 `settings-more-phone`（1600dp）、
 * `desk-ime-smoke-tall`（800dp）同一个理由：**加高画幅是唯一能在单帧里拍到折叠线
 * 以下那半屏的办法**。代价是它**不代表真实手机视口**，不要拿它当尺寸断言。
 *
 * ## 造假数据
 *
 * 用本文件共用的 `selfSettings` —— 这一屏画的就是**本机身份**，
 * 原型那张图里的 `AV_NICK` 是原型自己的演示身份，内容本来就该不同。
 * 字段值不钉任何分支（这一屏没有 if/else 状态槽），它钉的是**版面**：
 * 一帧在手，`SettingsProfileSubpage` 整段删掉、或者被谁改回"只有一个昵称输入框"，
 * 参考图会立刻变。
 */
@PreviewTest
@Preview(name = "settings-profile-phone", widthDp = 393, heightDp = 900, showBackground = true, locale = "zh-rCN")
@Composable
private fun SettingsProfilePhonePreview() {
    val state = RelayUiState(
        settings = selfSettings,
        network = runningNetwork,
        peers = listOf(humanPeer),
    )
    AppTheme(darkTheme = false) {
        StateSlotScreen(AppRoutes.SETTINGS, badges = navBadges(state)) { inner ->
            SettingsProfileSubpage(
                state = state,
                onSaveProfile = { _, _, _, _, _ -> },
                // 显式传 onBack 而不是留 null：留 null 时 `LargeTitleBar` 不画返回箭头，
                // 「这一页怎么回去」在图上就看不见了 —— 原型那一版是有返回的。
                onBack = {},
                // 头像分段控件第三档「上传图片」的出口。**必须传**：生产侧 `App.kt` 传的是
                // 真 lambda（切到 `SETTINGS_PAGE_AVATAR`），不传的话这一帧画的第三档是
                // **置灰态**，而置灰态不是产品 —— 原型 `avSeg()`（`prototype/relay.html:2149-2156`）
                // 画的是三档等宽、第三档可点。不传 = 欠武装帧。
                onOpenAvatarPage = {},
                modifier = inner,
            )
        }
    }
}

/**
 * 屏配对：设置二级页**连接**（台账 `m-set-conn`）。
 *
 * 覆盖的面：`feature/settings/SettingsScreen.kt:568` 的 `SettingsConnectionSubpage` 整页。
 *
 * ## 与原型的三处已知差异（这一帧把差异拍下来，而不是让它隐形）
 *
 * 原型 `screenSetConn`（`prototype/relay.html:3814`）是三张卡：
 * ① 端口卡（默认 UDP 端口 + TCP 只读行 + 重连/保存连接配置）
 * ② **我的二维码**卡（180×180 码 + 载荷串）
 * ③ 网段探测与同步卡。
 * 代码这一页只有 ① 与 ③ —— 二维码是**另一屏**（`m-myqr`，落点 `feature/scan/ScanScreen.kt`）。
 * 所以并排看时右图会比左图短一截，**这是结构差异不是渲染缺失**；
 * 取舍已记在 `docs/plans/2026-10-08-relay-prototype-gap-plan.md`，本轮不改代码。
 * 另两处：卡片顺序（代码把保存/重连放在端口卡内底部，原型放在 `.cardfoot`）、
 * 网段行（代码用 `SectionCard` + `OutlinedTextField` 加网段，原型另有「探测/删除」两枚小按钮）。
 *
 * ## 画幅就用标准手机屏（852dp）
 *
 * 代码这一页只有两张卡，852 装得下。**故意不再加高**：原型那张图
 * （`prototype/screenshots/relay/m13b-set-conn.png`，890×1808）是固定视口，
 * 右边也用同一视口，并排时两栏同比例，人一眼就能看出"右图短一截 = 少一张二维码卡"，
 * 而不是被一个拉高的画幅骗成"内容一样多"。
 *
 * ## 造假数据
 *
 * - `defaultPort` 传 `null`：走 `portSeed = defaultPort ?: settings.udpPort.toString()`
 *   的**兜底那一半**，也就是生产上真正会走的那一半（`App.kt` 不传 `defaultPort`）。
 *   传一个字符串会拍到另一条路径，图上分辨不出来，但它已经不是同一支了。
 * - `onAddScanRange` / `onRemoveScanRange` / `onTriggerCidrScan` 用默认空实现，
 *   与生产一致（移动端这三项没接）；`onRestart` 传空实现，
 *   因为这一帧要钉的是**版面**，不是"点重连会不会转圈"（那一支看 `settings-restarting`）。
 */
@PreviewTest
@Preview(name = "settings-conn-phone", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun SettingsConnPhonePreview() {
    val state = RelayUiState(
        settings = selfSettings,
        network = runningNetwork,
        peers = listOf(humanPeer),
    )
    AppTheme(darkTheme = false) {
        StateSlotScreen(AppRoutes.SETTINGS, badges = navBadges(state)) { inner ->
            SettingsConnectionSubpage(
                state = state,
                defaultPort = null,
                onSavePorts = { _, _ -> },
                onRestart = {},
                onBack = {},
                modifier = inner,
            )
        }
    }
}

/**
 * 套一层真正的 [CompactScaffold]，与另外两个预览文件里的同名包装器等价。
 *
 * 为什么复制而不是复用：那边的 `Screen` / `ExtraScreen` 都是 `private` 顶层（文件私有），
 * 跨文件拿不到。语义保持一致 —— 页签 id 用 [AppRoutes] 常量而不是裸字符串，
 * 徽标用应用自己那个 `navBadges`，不在预览里重算一遍规则。
 */
@Composable
private fun StateSlotScreen(
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
