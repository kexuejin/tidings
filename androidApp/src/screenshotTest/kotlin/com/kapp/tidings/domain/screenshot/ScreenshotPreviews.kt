// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain.screenshot

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kapp.tidings.domain.BannerMessage
import com.kapp.tidings.domain.BannerTone
import com.kapp.tidings.domain.BannerUi
import com.kapp.tidings.domain.model.CabinetEntryUi
import com.kapp.tidings.domain.CabinetRecentUpload
import com.kapp.tidings.domain.model.CabinetUiState
import com.kapp.tidings.domain.model.DeliveryState
import com.kapp.tidings.domain.model.FileCardUi
import com.kapp.tidings.domain.ManualPeerEntry
import com.kapp.tidings.domain.model.MessageUi
import com.kapp.tidings.domain.model.NetworkUi
import com.kapp.tidings.domain.model.NodePhase
import com.kapp.tidings.domain.model.PeerUi
import com.kapp.tidings.domain.RelaySettings
import com.kapp.tidings.domain.RelayUiState
import com.kapp.tidings.domain.ShareGrantEntry
import com.kapp.tidings.domain.model.TransferState
import com.kapp.tidings.domain.navBadges
import com.kapp.tidings.feature.agents.AgentConsoleScreen
import com.kapp.tidings.feature.cabinet.CabinetScreen
import com.kapp.tidings.feature.chat.ChatScreen
import com.kapp.tidings.feature.chat.ChatScreenChrome
import com.kapp.tidings.feature.chat.ChatScreenState
import com.kapp.tidings.feature.conversations.ConversationsScreen
import com.kapp.tidings.feature.nodes.AddNodeSheet
import com.kapp.tidings.feature.nodes.NodesScreen
import com.kapp.tidings.feature.settings.SettingsConnectionSubpage
import com.kapp.tidings.feature.settings.SettingsScreen
import com.kapp.tidings.feature.settings.SetAvatarPage
import com.kapp.tidings.navigation.AppNavItems
import com.kapp.tidings.platform.Transport
import com.kapp.tidings.ui.components.CompactScaffold
import com.kapp.tidings.ui.theme.AppTheme
import com.android.tools.screenshot.PreviewTest

/**
 * 截图门禁的参考图来源。
 *
 * 三条规则决定这个门禁值不值得留：
 *
 * 1. **一个预览必须渲染真实界面。** 每个预览都直接调 `composeApp` 里那个真正会被
 *    安装包调用的 Screen 组合函数，并且**套在 `CompactScaffold` 里** —— 底部导航
 *    曾经把 `Conversations` 截成 `Conversati`，只预览屏幕本身是看不见那个 bug 的。
 * 2. **参考图先评审再录制。** 录制是破坏性的：`updateDebugScreenshotTest` 会直接覆盖
 *    参考图，偷懒重录会把这次改动造成的样子变成新的"正常"。
 * 3. **时间必须是确定的。** 同一帧在两次运行里渲染出不同的字，门禁就会永远红灯：
 *    - 走 `relativeTime` 的字段（会话行、`lastSeenAt`）取 `nowMillis() - 偏移`，
 *      差值恒定 ⇒ 文案恒定；
 *    - 走 `formatClock` 的字段（消息气泡）取**固定**时间戳 ⇒ `HH:mm` 恒定。
 *    这两者不能互换：固定时间戳会让相对时间一天天变，`nowMillis()` 会让时钟一直变。
 *
 * 命令：
 *   ./gradlew :androidApp:validateDebugScreenshotTest   # 门禁
 *   ./gradlew :androidApp:updateDebugScreenshotTest     # 重录（先看！）
 *
 * 预览放在这个模块而不是 `composeApp`：截图插件是 Android-only 的，而屏幕本身在共享
 * 模块里。这里引用的都是 `composeApp` 的公开组合函数。
 */


/**
 * 2025-01-01T00:00:00Z，按 UTC+8 算是 08:00 —— 参考图里所有时间都从这里算。
 *
 * 走 `formatClock` 的字段（消息气泡）需要**固定**时间戳；走 `relativeTime` 的字段
 * 需要**固定**的"当前时刻"。两者都钉住，参考图才不会明天早上自己变红。
 */

/** 预览里的"现在"：08:25。传给屏幕的 `now` 参数，绝不读挂钟。 */


private val desktop = PeerUi(
    nodeId = "11111111-1111-4111-8111-111111111111",
    nick = "书房的电脑",
    company = "产品",
    dept = "客户端",
    team = "移动端",
    avatar = 5,
    host = "MacBook-Pro",
    ip = "192.168.1.23",
    platform = "mac",
    udpPort = 17878,
    tcpPort = 17879,
    caps = listOf("fd1", "mrec1"),
    online = true,
    lastSeenAt = at(24),
)

private val colleague = PeerUi(
    nodeId = "33333333-3333-4333-8333-333333333333",
    nick = "小林",
    company = "产品",
    dept = "设计",
    avatar = 2,
    host = "Xiaolin-MBP",
    ip = "192.168.1.31",
    platform = "mac",
    udpPort = 17878,
    tcpPort = 17879,
    caps = listOf("fd1"),
    online = false,
    lastSeenAt = at(-18),
)

private val codexAgent = PeerUi(
    nodeId = "22222222-2222-4222-8222-222222222222",
    nick = "codex · 终端",
    host = "MacBook-Pro",
    ip = "192.168.1.23",
    platform = "linux",
    tcpPort = 17879,
    caps = listOf("ag1", "tw1"),
    online = true,
    lastSeenAt = at(24),
)

private val claudeAgent = PeerUi(
    nodeId = "55555555-5555-4555-8555-555555555555",
    nick = "claude · 文档",
    host = "MacBook-Pro",
    ip = "192.168.1.23",
    platform = "linux",
    tcpPort = 17880,
    caps = listOf("ag1"),
    online = false,
    lastSeenAt = at(13),
)

private val desktopThread = listOf(
    MessageUi(
        id = "m1",
        peerId = desktop.nodeId,
        mine = false,
        text = "手机端连上了吗？我这边捎话已经能看到一个新节点。",
        at = at(0),
    ),
    MessageUi(
        id = "m2",
        peerId = desktop.nodeId,
        mine = true,
        text = "连上了，刚才那条 900 字节的长消息也收到了。",
        at = at(2),
        delivery = DeliveryState.Sent,
    ),
    MessageUi(
        id = "m3",
        peerId = desktop.nodeId,
        mine = true,
        text = "再发一条试试离线暂存：你现在把捎话关掉，我发完再开。",
        at = at(5),
        delivery = DeliveryState.Queued,
    ),
)

private val agentThread = listOf(
    MessageUi(
        id = "a1",
        peerId = codexAgent.nodeId,
        mine = true,
        text = "把移动端的门禁跑一遍，失败就把原因贴回来。",
        at = at(10),
        delivery = DeliveryState.Sent,
    ),
    MessageUi(
        id = "a2",
        peerId = codexAgent.nodeId,
        mine = false,
        text = "六条门禁全绿：core/composeApp/agentNode 测试通过，APK 11.8 MB，iOS framework 已产出。",
        at = at(12),
    ),
)

private val longThread = listOf(
    MessageUi(
        id = "l1",
        peerId = desktop.nodeId,
        mine = true,
        text = "这是一条贴近上限的长消息，用来验证气泡在 800 字节、没有空格的长 URL、" +
            "以及中文混排时都不会把布局挤爆：" +
            "https://example.com/a/very/long/path/without/any/spaces/" +
            "that/should/not/break/the/bubble/and/must/ellipsize/or/wrap/cleanly",
        at = at(15),
        delivery = DeliveryState.Sent,
    ),
    MessageUi(
        id = "l2",
        peerId = desktop.nodeId,
        mine = false,
        text = "收到。中文、英文、emoji 🙂 混排也不该有换行错位。",
        at = at(16),
    ),
)

/**
 * 文件卡片覆盖三种状态，因为它们在代码里是三个分支：
 * 等接收（要出「接收/拒绝」按钮）、传输中（要出进度条）、已完成（要出落盘路径）。
 * 只画「已完成」会漏掉最需要盯住的按钮布局。
 */
private val fileThread = listOf(
    MessageUi(
        id = "f0",
        peerId = desktop.nodeId,
        mine = false,
        text = "把刚才那份截图发我。",
        at = at(18),
    ),
    MessageUi(
        id = "f1",
        peerId = desktop.nodeId,
        mine = false,
        text = "",
        at = at(19),
        file = FileCardUi(
            transferId = "t-in-1",
            name = "季度复盘.pptx",
            size = 18_432_000L,
            mine = false,
            state = TransferState.Offered,
        ),
    ),
    MessageUi(
        id = "f2",
        peerId = desktop.nodeId,
        mine = true,
        text = "",
        at = at(21),
        delivery = DeliveryState.Sent,
        file = FileCardUi(
            transferId = "t-out-1",
            name = "01-phone-chat-light.png",
            size = 2_048_000L,
            done = 860_160L,
            mine = true,
            state = TransferState.Active,
        ),
    ),
    MessageUi(
        id = "f4",
        peerId = desktop.nodeId,
        mine = true,
        text = "",
        at = at(23),
        // 故意不设 delivery：文件气泡由传输事件驱动，本来就没有 msg 层的投递状态。
        // 这张参考图钉住"不知道就不显示"—— 上一版这里会落到 common_unknown，
        // 真机上于是每张发出去的文件卡片旁边都挂着两个字「未知」。
        file = FileCardUi(
            transferId = "t-out-2",
            name = "relay-file-test.bin",
            size = 20_000L,
            done = 20_000L,
            mine = true,
            state = TransferState.Completed,
            savedPaths = listOf("/storage/emulated/0/Download/relay-file-test.bin"),
        ),
    ),
    MessageUi(
        id = "f3",
        peerId = desktop.nodeId,
        mine = false,
        text = "",
        at = at(23),
        file = FileCardUi(
            transferId = "t-in-2",
            name = "运行日志",
            size = 3_145_728L,
            fileCount = 4,
            done = 3_145_728L,
            mine = false,
            state = TransferState.Completed,
            savedPaths = listOf("/data/user/0/com.kapp.tidings/files/relay/downloads/运行日志"),
        ),
    ),
)

private val selfSettings = RelaySettings(
    nodeId = "44444444-4444-4444-8444-444444444444",
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
    // 运行中的节点默认是"广播中"：有地址时派生值就是它，参考图要照常态录。
    mdnsAdvertised = true,
)

/**
 * 有来有往、有未读、有离线暂存 —— 会话列表最该被盯住的那一帧。
 *
 * 未读与待处理这两个 map 同时喂底栏徽标：`unreadByPeer` 求和 =「消息」上的 3，
 * `pendingByPeer` 里**只有 codexAgent 是 Agent**，所以「Agent」上是 2 ——
 * 小林那 3 条待处理是真人消息，不该算进 Agent 页签（见 `navBadges`）。
 * 原型 `prototype/relay.html:2441` 的 `NAV` 写的是 3 / 1 —— 这一项不必对齐：
 * 预览图钉的是状态分支的组合，不要求与原型逐个数字一致。
 */
private val normalState = RelayUiState(
    settings = selfSettings,
    network = runningNetwork,
    peers = listOf(desktop, codexAgent, colleague, claudeAgent),
    messages = mapOf(
        desktop.nodeId to desktopThread,
        codexAgent.nodeId to agentThread,
    ),
    unreadByPeer = mapOf(colleague.nodeId to 2, desktop.nodeId to 1),
    pendingByPeer = mapOf(colleague.nodeId to 3, codexAgent.nodeId to 2),
)

/**
 * 徽标溢出用的假节点：未读 120 逼出 `99+`（见 [crowdedState]）。
 *
 * 会话顶部的标签条已按产品决策移除，这批节点现在只服务底栏徽标那一帧。
 */
private val crowdedLongName = "会议室大屏（三号楼东侧）"

private val crowdedPeers = listOf(
    Triple("书房电脑", true, false),
    Triple("codex · 终端", true, true),
    Triple("阿 May", false, false),
    Triple("iPhone 15 Pro", true, false),
    Triple("NAS · 备份", true, false),
    Triple(crowdedLongName, false, false),
    Triple("小林", true, false),
    Triple("镜像构建机", true, false),
).mapIndexed { index, (nick, online, isAgent) ->
    PeerUi(
        nodeId = "66666666-6666-4666-8666-66666666666$index",
        nick = nick,
        host = "MacBook-Pro",
        ip = "192.168.1.${23 + index}",
        platform = "mac",
        tcpPort = 17879,
        caps = if (isAgent) listOf("ag1", "tw1") else listOf("fd1"),
        online = online,
        lastSeenAt = at(index.toLong()),
    )
}

/**
 * 未读 120 逼出底栏徽标的 `99+`（`badgeCountText` 规则）；
 * Agent 徽标只数 `codex · 终端` 那一个 agent。
 *
 * 这条溢出规则原来是在标签条上一起看的，标签条移除后这里是**唯一**盯它的地方。
 */
private val crowdedState = RelayUiState(
    settings = selfSettings,
    network = runningNetwork,
    peers = crowdedPeers,
    unreadByPeer = mapOf(crowdedPeers[6].nodeId to 120, crowdedPeers[4].nodeId to 7),
    pendingByPeer = mapOf(crowdedPeers[1].nodeId to 4),
)

private val fileState = RelayUiState(
    settings = selfSettings,
    network = runningNetwork,
    peers = listOf(desktop, codexAgent, colleague, claudeAgent),
    messages = mapOf(desktop.nodeId to fileThread),
)

/**
 * 图片与语音各一条，给内联预览用。
 *
 * 语音那条走 `savedPaths`（收到的东西落在哪），所以会连「已存到 …」一起画出来 —— 那是
 * 界面真实的样子。图片那条走 `localPaths`（我本来就有这个文件），因为它要钉的是
 * **自己发出去的图也有缩略图**，而发端卡片从来没有 `savedPaths`。
 */
private val mediaThread = listOf(
    MessageUi(
        id = "md0",
        peerId = desktop.nodeId,
        mine = false,
        text = "这条语音你听一下。",
        at = at(30),
    ),
    MessageUi(
        id = "md1",
        peerId = desktop.nodeId,
        mine = false,
        text = "",
        at = at(31),
        file = FileCardUi(
            transferId = "t-voice-1",
            name = "voice-2026-10-07.m4a",
            size = 96_000L,
            done = 96_000L,
            mine = false,
            state = TransferState.Completed,
            savedPaths = listOf("/Users/demo/Downloads/voice-2026-10-07.m4a"),
        ),
    ),
    MessageUi(
        id = "md2",
        peerId = desktop.nodeId,
        mine = true,
        text = "",
        at = at(32),
        file = FileCardUi(
            transferId = "t-photo-out",
            name = "screenshot-2026-10-07.png",
            size = 6633L,
            done = 6633L,
            mine = true,
            state = TransferState.Completed,
            localPaths = listOf(PREVIEW_PHOTO_PATH),
        ),
    ),
)

/**
 * 图里那个路径是假的，而且**这张参考图上根本没有缩略图** —— 这是实测结论，不是遗漏。
 *
 * 2026-10-07 录图时给过一个磁盘上真有的 6633 字节 PNG（`/tmp/relay-preview-photo.png`），
 * 渲染出来仍然是普通文件卡：`readFileBytes` 走的是 java.io（`FileIo.android.kt:39`
 * 就是 `File(path)`），文件确实读到了，所以解不出来的只能是 `decodeImageBytes` 的
 * Android 那支 —— 这个源集跑在截图宿主的 JVM 上，`BitmapFactory` 不是真实现。
 *
 * 于是这张图钉住的是另一半行为：**解不出来就安静地退回普通文件卡**，不崩、不画空框、
 * 不弹错误。缩略图本身只能靠真机验（`design/runtime-evidence/` 里那份）。
 * 换句话说，这个常量的值不影响任何像素 —— 换成下面这个假路径后参考图逐字节没变。
 */
private const val PREVIEW_PHOTO_PATH = "/Users/demo/Pictures/screenshot-2026-10-07.png"

private val mediaState = RelayUiState(
    settings = selfSettings,
    network = runningNetwork,
    peers = listOf(desktop, codexAgent, colleague, claudeAgent),
    messages = mapOf(desktop.nodeId to mediaThread),
)

private val emptyState = RelayUiState(
    settings = selfSettings,
    // 空态一律用 Running。会话页在 `NodePhase.Starting` 时画的是「正在恢复会话…」
    // （见下面的 `startingState`），而这张图要证明的是**真的空**。
    // 节点页已不再读 `phase`（2026-10-12 原型把空态改成无条件），所以两边都该用 Running。
    network = NetworkUi(phase = NodePhase.Running),
)

/**
 * 启动中：**会话页**空态画「正在恢复会话…」的加载行，只有这一张图作证。
 *
 * 节点页**不再**消费这个相位（原型改稿后空态无条件），所以这个夹具现在只喂
 * `ConversationsLoadingPreview` —— 曾经喂它的 `NodesStartingPreview` 已删，
 * 理由见文件下方那段注释。
 */
private val startingState = RelayUiState(
    settings = selfSettings,
    network = NetworkUi(phase = NodePhase.Starting),
)

/**
 * 重启中：设置屏连接卡的「重连」按钮在 `state.restarting` 期间换成「正在重启…」并禁用。
 *
 * 这是设置屏**唯一**的 loading 渲染分支（`SettingsScreen.kt` 的
 * `AsyncButton(loading = state.restarting, loadingLabel = set_conn_reconnecting)`），
 * 之前没有任何一张图钉住它 —— 图缺了，那条「重连中要看得见」的设计理由也就无从复核。
 */
private val restartingState = normalState.copy(restarting = true)

private val failedState = RelayUiState(
    settings = selfSettings,
    network = NetworkUi(
        phase = NodePhase.Failed,
        boundUdpPort = 17878,
        failure = BannerMessage.BindFailed(17878, "Address already in use"),
    ),
    banner = BannerUi(
        message = BannerMessage.SendFailed("对方 3 秒内没有回应握手"),
        tone = BannerTone.Error,
    ),
    peers = listOf(desktop),
    messages = mapOf(
        desktop.nodeId to listOf(
            MessageUi(
                id = "e1",
                peerId = desktop.nodeId,
                mine = true,
                text = "这条没发出去，气泡上应该显示「失败」，并且有一条能重试的出路。",
                at = at(20),
                delivery = DeliveryState.Failed,
            ),
        ),
    ),
)

private val agentOnlyState = RelayUiState(
    settings = selfSettings,
    network = runningNetwork,
    peers = listOf(codexAgent, claudeAgent),
    messages = mapOf(codexAgent.nodeId to agentThread),
)

private val discoveryState = RelayUiState(
    settings = selfSettings.copy(
        manualPeers = listOf(
            // 三种处境各来一条，一张参考图就能看全（这也是真机上曾经的真实形状）：
            // 有过回应的在线节点、有过回应但关机的节点、换了网段之后再也没回应过的旧地址。
            ManualPeerEntry(host = "192.168.1.23", port = 17878),
            ManualPeerEntry(host = "192.168.1.31", port = 17878),
            ManualPeerEntry(host = "192.168.1.99", port = 17878),
        ),
    ),
    network = runningNetwork,
    peers = listOf(desktop, colleague),
)

/**
 * 套一层真正的 [CompactScaffold]。
 *
 * `selectedScreenId` 传的是**页签 id**，不是屏幕名 —— 聊天详情现在归属于「消息」
 * 页签（顶栏带返回），所以渲染 `ChatScreen` 时这里传 `conversations`，
 * 参考图里「消息」是亮着的那一个。
 *
 * `badges` 与 `RelayApp` 调用同一个 `navBadges`：门禁必须盯住应用真正会画出来的
 * 数字，自己再算一遍就等于把这条规则复制成两份。
 *
 * [landscape] 只在横屏格子里传 true（见 [ConversationsLandscapePreview]）。
 * 生产侧这个值来自 `App.kt` 的 `resolveAppLayoutMode(maxWidth, maxHeight)`，判据是
 * `width > height`；那个函数是 `composeApp` 的 `internal`，`androidApp` 看不到，
 * 所以预览只能**按尺寸自己声明**。这意味着预览的尺寸与这里传的布尔值可能不一致 ——
 * 改格子尺寸时请一并复核：`widthDp > heightDp` 就必须传 true。
 */
/**
 * 手机端文件柜的两个状态槽：配好共享目录的「我的文件柜」，与浏览同事目录。
 *
 * 为什么补这一组：文件柜在**任何形态都没有截图门禁**（审计 §9 缺口①），
 * 移动端这条尤其严重 —— `CabinetScreen` 是本轮 V3 唯一改了宽度的共享屏
 * （两处内容列收进 [com.kapp.tidings.ui.ContentWidths.Pane]），而它此前一张图都没有。
 * 手机上 393dp 远窄于 720，所以收列**必须**是空操作；这两帧就是那条断言的证据：
 * 若哪天 `centerCapped` 在窄容器上没退化成容器宽，这两张图会先红。
 *
 * 桌面端那一组在 `ScreenshotPreviewsDesktop.kt`（`desk-cabinet-*`），
 * 用的是 `ui/desktop/PreviewState.kt` 里的 fixture；移动端不复用它们，
 * 因为那一套的节点 id 与这里不同，混用会让两边的参考图互相牵制。
 */
private val cabinetMineSettings = selfSettings.copy(
    shareRoot = "~/Documents/捎话共享",
    shareMode = "read",
    shareGrants = listOf(
        ShareGrantEntry(nodeId = colleague.nodeId, mode = "write"),
        ShareGrantEntry(nodeId = desktop.nodeId, mode = "read"),
    ),
    cabinetRecentUploads = listOf(
        CabinetRecentUpload(
            transferId = "t-m-cab-1",
            uploaderNodeId = colleague.nodeId,
            uploaderNick = colleague.nick,
            fileCount = 3,
            totalSize = 4_812_000L,
            ts = at(90),
        ),
    ),
)

/** 我的文件柜：已配置。四张卡片（共享目录 / 默认权限 / 单独设过的同事 / 最近有人放进来）。 */
private val cabinetMineState = RelayUiState(
    settings = cabinetMineSettings,
    network = runningNetwork,
    peers = listOf(desktop, codexAgent, colleague, claudeAgent),
)

/**
 * 同事的文件柜：`activePeerId` 非 null 时走 `PeerCabinetView`。
 *
 * 五个条目里两个是目录 —— 目录与文件的大小列格式不同，只给文件就看不出这个分支。
 */
private val cabinetPeerState = cabinetMineState.copy(
    cabinet = CabinetUiState(
        activePeerId = colleague.nodeId,
        currentPath = "2026 会议",
        entries = listOf(
            CabinetEntryUi(name = "第一季度", isDir = true, size = 0L, mtime = at(-2880)),
            CabinetEntryUi(name = "排期表.ods", isDir = false, size = 84_000L, mtime = at(-300)),
            CabinetEntryUi(name = "会议纪要.md", isDir = false, size = 12_400L, mtime = at(-180)),
        ),
        totalEntries = 3,
        currentPerm = "write",
    ),
)

/** 还没配置过共享目录：用户第一次进文件柜必然看到的那一屏。 */
private val cabinetUnsetState = RelayUiState(
    settings = selfSettings,
    network = runningNetwork,
    peers = listOf(desktop, codexAgent, colleague, claudeAgent),
)

@Composable
private fun Screen(
    selectedScreenId: String,
    badges: Map<String, Int> = emptyMap(),
    landscape: Boolean = false,
    content: @Composable (Modifier) -> Unit,
) {
    CompactScaffold(
        selectedScreenId = selectedScreenId,
        navItems = AppNavItems,
        onScreenSelected = {},
        badges = badges,
        landscape = landscape,
    ) { inner -> content(inner) }
}

@PreviewTest
@Preview(name = "conversations-phone", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ConversationsPhonePreview() {
    AppTheme(darkTheme = false) {
        Screen("conversations", badges = navBadges(normalState)) { inner ->
            ConversationsScreen(
                state = normalState,
                onOpenChat = {},
                onToggleMute = {},
                onDeleteConversation = {},
                onGoToPairing = {},
                onDismissBanner = {},
                modifier = inner,
                now = PREVIEW_NOW,
            )
        }
    }
}

/**
 * 手机上的聊天详情：顶栏带返回、下面一条会话标签条、底栏 5 项带徽标。
 *
 * 这一帧是新导航的全貌。与原型 `prototype/relay.html` 的聊天屏有一处**形态差异**：
 * 原型把聊天详情画成**整屏、根本不画底栏**（`m-chat` 标了 `full:true`，`:1365`；
 * `device()` 据此走 `noNav` 分支，`:1490` / `:1497`），而这一帧把底栏一起画了出来，
 * 好在一张图里同时钉住顶栏返回 + 底栏徽标；「消息」保持高亮 ——
 * 详情页归属于某个页签是 Material 的常规行为，"什么都没选中"会让用户不知道自己在哪。
 */
@PreviewTest
@Preview(name = "chat-phone", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ChatPhonePreview() {
    AppTheme(darkTheme = false) {
        Screen("conversations", badges = navBadges(normalState)) { inner ->
            ChatScreen(
                data = ChatScreenState(
                    state = normalState,
                    peerId = desktop.nodeId,
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
 * 620×620 的方窗：**手机外壳**，不是平板壳。
 *
 * 这一帧钉的是断点决定。生产里 `resolveAppLayoutMode` 要求宽度够
 * `DesktopMetrics.MEDIUM_MIN_PX`（= 竖导 64 + 侧栏 300 + 聊天栏下限 300 = 664）才分栏；
 * 620 宽分栏后聊天栏只剩 256px，桌面输入栏底行在那里会叠字、把发送按钮挤出屏幕。
 * 所以这个尺寸落到单栏：聊天栏拿到整幅 620px，导航交给底部导航条。
 *
 * 注意它和已删的 `conversations-chat-tablet-land-dark` 的区别：那张画的是手搓的双栏
 * `Row`，生产不存在；这一帧的手机壳就是 620×620 生产真跑的东西。
 */
@PreviewTest
@Preview(name = "window-620-phone-shell", widthDp = 620, heightDp = 620, showBackground = true, locale = "zh-rCN")
@Composable
private fun Window620PhoneShellPreview() {
    AppTheme(darkTheme = false) {
        Screen("conversations", badges = navBadges(normalState)) { inner ->
            ConversationsScreen(
                state = normalState,
                onOpenChat = {},
                onToggleMute = {},
                onDeleteConversation = {},
                onGoToPairing = {},
                onDismissBanner = {},
                modifier = inner,
                now = PREVIEW_NOW,
            )
        }
    }
}

/** 文件卡片：等接收 / 传输中 / 已完成三种状态必须同框，否则按钮与进度条没人盯。 */
@PreviewTest
@Preview(name = "chat-files-phone", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ChatFilesPhonePreview() {
    AppTheme(darkTheme = false) {
        Screen("conversations") { inner ->
            ChatScreen(
                data = ChatScreenState(
                    state = fileState,
                    peerId = desktop.nodeId,
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
 * 图片与语音的内联预览：收到的音频有一个「播放」按钮，发出去的图自己也能看到缩略图。
 *
 * 图片那张用的是 `mine = true` + `localPaths` —— 刻意不画「已存到 <路径>」，
 * 于是这张参考图里不含任何机器相关的路径文本，换台机器录出来还是同一张。
 *
 * 但**这张图里没有缩略图**：截图宿主上解不出 PNG（原因见 `PREVIEW_PHOTO_PATH` 的
 * 说明），所以它钉住的是「解不出来就退回普通文件卡」。缩略图本身在真机上验。
 */
@PreviewTest
@Preview(name = "chat-media-phone", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ChatMediaPhonePreview() {
    AppTheme(darkTheme = false) {
        Screen("conversations") { inner ->
            ChatScreen(
                data = ChatScreenState(
                    state = mediaState,
                    peerId = desktop.nodeId,
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

/** Agent 控制台是本产品区别于普通 IM 的主界面，必须单独盯住。 */
@PreviewTest
@Preview(name = "agents-phone", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun AgentsPhonePreview() {
    AppTheme(darkTheme = false) {
        Screen("agents") { inner ->
            AgentConsoleScreen(
                state = agentOnlyState,
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
                now = PREVIEW_NOW,
            )
        }
    }
}

@PreviewTest
@Preview(name = "nodes-phone", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun NodesPhonePreview() {
    AppTheme(darkTheme = false) {
        Screen("nodes") { inner ->
            NodesScreen(
                state = discoveryState,
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
 * 添加节点弹层单独出一张参考图：它现在是「怎么加一个节点」的唯一入口，
 * 以前它是整屏，所以没有任何一张图覆盖它。
 *
 * [sheetState] 必须显式给一个 `initialValue = SheetValue.Expanded` 的状态：
 * ModalBottomSheet 默认是 Hidden 再动画展开，而截图抓的是**一帧**，动画没跑到位时
 * 录下来的就是一张纯底色空图 —— 踩过一次，所以在这里写死。
 */
@OptIn(ExperimentalMaterial3Api::class)
@PreviewTest
@Preview(name = "nodes-add-sheet", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun AddNodeSheetPhonePreview() {
    val density = LocalDensity.current
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            AddNodeSheet(
                state = emptyState,
                onConnect = { _, _ -> },
                onDismiss = {},
                // 手机壳（`App.kt`）现在真的传了这条路径，帧里也要传 ——
                // 不传的话这一帧画的是"桌面版弹层"，与它声称的手机壳不符。
                onScanQr = {},
                sheetState = remember(density) {
                    SheetState(
                        skipPartiallyExpanded = true,
                        // 这两个阈值只影响"半展开"停在哪儿；这一帧直接给
                        // Material 的默认值，显式写出来是因为它们没有默认值。
                        positionalThreshold = { with(density) { 56.dp.toPx() } },
                        velocityThreshold = { with(density) { 1250.dp.toPx() } },
                        initialValue = SheetValue.Expanded,
                    )
                },
            )
        }
    }
}

/**
 * 弹层的「扫码」段（手机壳，真的能扫）。
 *
 * 与上一帧同一张弹层，只差分段控件停在哪一段：`initialScanMode = true` 时画的是
 * 取景框提示 + 「打开相机扫码」那颗真按钮。节点页那行「扫码配对」现在就是把人送到这里
 * （`NodesScreen` 的 `sheetStartsOnScan`），所以这一帧是那条入口的**唯一**证据。
 *
 * 分段是弹层内部状态，外部点不进去，所以靠 `initialScanMode` 参数录 —— 这与本文件里
 * 显式传 `SheetState` 是同一类做法（都是为了门禁能录到"已经走到位"的那一帧）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@PreviewTest
@Preview(name = "nodes-add-sheet-scan", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun AddNodeSheetScanPhonePreview() {
    val density = LocalDensity.current
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            AddNodeSheet(
                state = emptyState,
                onConnect = { _, _ -> },
                onDismiss = {},
                onScanQr = {},
                initialScanMode = true,
                sheetState = remember(density) {
                    SheetState(
                        skipPartiallyExpanded = true,
                        positionalThreshold = { with(density) { 56.dp.toPx() } },
                        velocityThreshold = { with(density) { 1250.dp.toPx() } },
                        initialValue = SheetValue.Expanded,
                    )
                },
            )
        }
    }
}

/** 设置屏是「凭据/资料必须有录入界面」那条硬约束的落点，必须有一张参考图。 */
@PreviewTest
@Preview(name = "settings-phone", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun SettingsPhonePreview() {
    AppTheme(darkTheme = false) {
        Screen("settings", badges = navBadges(normalState)) { inner ->
            SettingsScreen(
                state = normalState,
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
 * 头像上传整页（`m-set-avatar`）：渲染 [SetAvatarPage] —— 原型 `screenSetAvatar` 的
 * 四步指引页（选本地图 → 裁正方形 → 编码+信息 → 写真档）。
 *
 * **这一帧以前画错了屏**：早先它渲染的是 `SettingsProfileSubpage`（「我的资料」编辑页，
 * 头像只是其中一个字段），而原型 `m13a2-set-avatar` 是一整页上传指引。并排板子把两者摆
 * 在一起才看出来是「拿错的帧顶了缺」——「两侧齐全」不等于「配的是同一屏」。
 */
@PreviewTest
@Preview(name = "set-avatar-page-phone", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun SetAvatarPagePreview() {
    AppTheme(darkTheme = false) {
        // 套 `Screen`：原型 `m13a2-set-avatar` 的底部那一排（消息/文件柜/节点/Agent/设置）
        // 是**底导航 chrome**（`.dnav`），不是页面内容 —— 必须由外壳画。
        // 页面 body 里再画一遍就是把外壳当内容（早先那版正是这么错的）。
        Screen("settings", badges = navBadges(normalState)) { inner ->
            SetAvatarPage(
                state = normalState,
                onSaveProfile = { _, _, _, _, _ -> },
                // 原型 `screenSetAvatar`（`prototype/relay.html:3741`）逐字
                // `topbar('上传头像','选一张图 → 裁方 → 哈希 → 宣告','','m-set-profile','set')`
                // —— 第 4 参 `'m-set-profile'` 就是返回目标 ⇒ **原型有返回键**。
                // 传 null 会把返回键整支画没（`SetAvatarPage.kt:117` 的 KDoc「非 null 时顶栏
                // 左侧画返回键」），那与原型不符。夹具只需非 null（箭头指向由导航层决定），
                // 故传空 lambda。Lead 2026-10-12 裁决（W3 交裁第 ① 条）。
                onBack = {},
                modifier = inner,
            )
        }
    }
}

/**
 * 同一页的深色版。
 *
 * 这一页大量使用 `.kv` 哈希卡、虚线选图框与 `surface-2` 提示块 —— 深色下最容易出现
 * 「虚线看不见」或「次级文字糊在背景上」，浅色下完全看不出来。
 */
@PreviewTest
@Preview(name = "set-avatar-page-phone-dark", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun SetAvatarPageDarkPreview() {
    AppTheme(darkTheme = true) {
        Screen("settings", badges = navBadges(normalState)) { inner ->
            SetAvatarPage(
                state = normalState,
                onSaveProfile = { _, _, _, _, _ -> },
                // 原型 `screenSetAvatar`（`prototype/relay.html:3741`）逐字
                // `topbar('上传头像','选一张图 → 裁方 → 哈希 → 宣告','','m-set-profile','set')`
                // —— 第 4 参 `'m-set-profile'` 就是返回目标 ⇒ **原型有返回键**。
                // 传 null 会把返回键整支画没（`SetAvatarPage.kt:117` 的 KDoc「非 null 时顶栏
                // 左侧画返回键」），那与原型不符。夹具只需非 null（箭头指向由导航层决定），
                // 故传空 lambda。Lead 2026-10-12 裁决（W3 交裁第 ① 条）。
                onBack = {},
                modifier = inner,
            )
        }
    }
}

/**
 * 设置屏的 loading：连接卡的「重连」正在跑（`state.restarting`），按钮换成
 * 「正在重启…」且不可再点。852 高看不到这张卡的下半部分（重启按钮在端口输入框
 * 下面），所以这一帧刻意高 1400 —— 与 `nodes-long-text` 同理：图高不够就等于没证明。
 *
 * 直接拍连接子页：`initialSubpage` 的 `if (state.restarting)` 劫持已删（用户不该
 * 因为节点在重启就被换页），SettingsScreen 首页不再自动跳到连接页 —— 但「重连按钮
 * 变『正在重启…』」这条 loading 证据就在连接子页里，这一帧仍钉它。
 */
@PreviewTest
@Preview(name = "settings-restarting", widthDp = 393, heightDp = 1400, showBackground = true, locale = "zh-rCN")
@Composable
private fun SettingsRestartingPreview() {
    AppTheme(darkTheme = false) {
        Screen("settings", badges = navBadges(restartingState)) { inner ->
            SettingsConnectionSubpage(
                state = restartingState,
                defaultPort = null,
                onSavePorts = { _, _ -> },
                onRestart = {},
                onAddScanRange = {},
                onRemoveScanRange = {},
                onTriggerCidrScan = {},
                onBack = {},
                modifier = inner,
            )
        }
    }
}

@PreviewTest
@Preview(name = "conversations-dark", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ConversationsDarkPreview() {
    AppTheme(darkTheme = true) {
        Screen("conversations", badges = navBadges(normalState)) { inner ->
            ConversationsScreen(
                state = normalState,
                onOpenChat = {},
                onToggleMute = {},
                onDeleteConversation = {},
                onGoToPairing = {},
                onDismissBanner = {},
                modifier = inner,
                now = PREVIEW_NOW,
            )
        }
    }
}

/** 大字号：中文长文案在 1.3 倍字号下最容易挤爆，必须有一张参考图。 */
@PreviewTest
@Preview(
    name = "chat-large-font",
    widthDp = 393,
    heightDp = 852,
    fontScale = 1.3f,
    showBackground = true,
    locale = "zh-rCN",
)
@Composable
private fun ChatLargeFontPreview() {
    AppTheme(darkTheme = false) {
        Screen("conversations") { inner ->
            ChatScreen(
                data = ChatScreenState(
                    state = normalState.copy(
                    messages = mapOf(desktop.nodeId to longThread),
                ),
                    peerId = desktop.nodeId,
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

/** 空态是产品的硬需求（打开就要知道该做什么），不是可选项。 */
@PreviewTest
@Preview(name = "conversations-empty", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ConversationsEmptyPreview() {
    AppTheme(darkTheme = false) {
        Screen("conversations") { inner ->
            ConversationsScreen(
                state = emptyState,
                onOpenChat = {},
                onToggleMute = {},
                onDeleteConversation = {},
                onGoToPairing = {},
                onDismissBanner = {},
                modifier = inner,
                now = PREVIEW_NOW,
            )
        }
    }
}

// nodes-starting 已删（2026-10-12，`render-preview.py --dup` 抓到）：
//
// 这张帧原来钉的是「NodePhase.Starting 时节点页画『正在启动节点…』的加载行」。原型改稿后
// `screenNodesEmpty()`（`prototype/relay.html:3410-3434`）是**无条件**空态卡，那条 `Starting`
// 分支随之从 `NodesScreen` 删掉（它现在只消费 `NodePhase.Failed`）—— 于是本帧的夹具
// `startingState` 与 `NodesEmptyPhonePreview` 的 `nothingFoundState` 只剩 `phase` 一个字段
// 不同，而节点页已不再读它 ⇒ **两张图逐字节相同**（sha256
// `8b073623cde2643f91838d094c31388e51766cdce1a99b5e61a7ceb4fde75b12`）。
//
// 帧名不同、量具全绿、配对表各占一行，只有比哈希能发现 —— 留着就是让重复帧冒充两张证据。
// 删掉它**不会**让 `nodes/scanning` 状态槽掉档（该槽的证据是 `AddNodeSheetScanPhonePreview`
// 等扫码帧；实测删前删后都是 53/53）。
//
// `startingState` 本身保留：会话页**仍**有 `NodePhase.Starting` 分支
// （`ConversationsScreen.kt:336`），下一张 `ConversationsLoadingPreview` 用的就是它。

/** 启动中的会话页：历史还在从盘上恢复，这时说「还没有会话」同样是把过程说成了结论。 */
@PreviewTest
@Preview(name = "conversations-loading", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ConversationsLoadingPreview() {
    AppTheme(darkTheme = false) {
        Screen("conversations") { inner ->
            ConversationsScreen(
                state = startingState,
                onOpenChat = {},
                onToggleMute = {},
                onDeleteConversation = {},
                onGoToPairing = {},
                onDismissBanner = {},
                modifier = inner,
                now = PREVIEW_NOW,
            )
        }
    }
}

/**
 * 启动失败：状态卡写着「启动失败 · 见下方提示」，下方必须真的有原因和一次重试。
 *
 * 这是 `network.failure` 唯一被界面读到的地方 —— 在接上 `ErrorState` 之前，绑定失败的
 * 真实原因（端口被占之类）全仓没有任何界面显示，用户只被告知失败、拿不到原因、也没有
 * 第二次机会。这张图同时钉住「原因」与「重试出口」两样。
 */
@PreviewTest
@Preview(name = "nodes-failed", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun NodesFailedPreview() {
    AppTheme(darkTheme = false) {
        Screen("nodes") { inner ->
            NodesScreen(
                state = failedState,
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

/** 错误态同样要有一条出路：失败气泡 + 可关闭的横幅 + 网络失败原因。 */
@PreviewTest
@Preview(name = "chat-error", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ChatErrorPreview() {
    AppTheme(darkTheme = false) {
        Screen("conversations") { inner ->
            ChatScreen(
                data = ChatScreenState(
                    state = failedState,
                    peerId = desktop.nodeId,
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
 * 徽标溢出：未读 120（8 个会话）逼出底栏的 `99+`。
 *
 * 会话顶部的标签条已按产品决策移除，这一帧因此只剩一件事要盯：一个三位数未读
 * 不能让底栏徽标把图标挤变形，必须是 `99+`（复用的 `chat-tabs-crowded` 场景）。
 */
@PreviewTest
@Preview(name = "chat-crowded-badge", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ChatCrowdedBadgePreview() {
    AppTheme(darkTheme = false) {
        Screen("conversations", badges = navBadges(crowdedState)) { inner ->
            ChatScreen(
                data = ChatScreenState(
                    state = crowdedState,
                    peerId = crowdedPeers.last().nodeId,
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
 * 手机**横屏**（852×393）：底导换成左侧竖导。
 *
 * 这一帧补的是审计 §9 缺口②：全仓原本没有 852×393 的预览，而
 * `AppLayoutModeTest.kt:23-25` 恰恰断言这一档落 `CompactLandscape` ——
 * 判出来了、有测试盯着，却没有任何一张图证明它长什么样。
 *
 * ## 为什么横屏必须换形态
 *
 * 393 dp 的高要同时容纳状态栏、顶栏、内容、底导。底导连同安全区约 60 dp，
 * 换掉它内容区能多出约 15% 的高度；而竖导吃的是宽度，852 dp 上完全富余。
 * 原型对此的立场是 `nav: 'rail'`（`prototype/relay.html:2429` 的
 * `tabland` 档，`:1929 const useRail = s.nav === 'rail'`）。
 *
 * ## 这一帧要盯住的三件事
 *
 * - 竖导**在左**、内容在右，且两者之间的分隔线是 1px 的 `outlineVariant`；
 * - 底导**完全不出现** —— 出现就说明 `landscape` 没传到 [CompactScaffold]；
 * - 内容没有被竖导挤压成横向溢出（`ConversationsScreen` 在 788 dp 可用宽度下
 *   应该仍是单列列表，不该出现文字被裁一半）。
 */
@PreviewTest
@Preview(name = "conversations-landscape", widthDp = 852, heightDp = 393, showBackground = true, locale = "zh-rCN")
@Composable
private fun ConversationsLandscapePreview() {
    AppTheme(darkTheme = false) {
        Screen("conversations", badges = navBadges(normalState), landscape = true) { inner ->
            ConversationsScreen(
                state = normalState,
                onOpenChat = {},
                onToggleMute = {},
                onDeleteConversation = {},
                onGoToPairing = {},
                onDismissBanner = {},
                modifier = inner,
                now = PREVIEW_NOW,
            )
        }
    }
}

/**
 * 手机横屏的「节点」页：竖导 + 列表。
 *
 * 与 [ConversationsLandscapePreview] 成对：一张证明列表型页面在横屏可用，
 * 一张证明换的是壳而不是某一个页面。两帧都用同一个 `landscape = true`，
 * 所以底导若因某条分支漏传而回来，两张图会同时暴露。
 */
@PreviewTest
@Preview(name = "nodes-landscape", widthDp = 852, heightDp = 393, showBackground = true, locale = "zh-rCN")
@Composable
private fun NodesLandscapePreview() {
    AppTheme(darkTheme = false) {
        Screen("nodes", landscape = true) { inner ->
            NodesScreen(
                state = discoveryState,
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
 * 手机上的「我的文件柜」：已配置共享目录。
 *
 * 这是移动端文件柜的主帧。`CabinetScreen` 在 393dp 下不可能触到 720 的上限，
 * 所以这张图同时是「收列原语在窄容器上是空操作」的证据 —— 它必须与
 * `desk-cabinet-mine-expanded`（1100dp，收列真的生效）并排看。
 */
@PreviewTest
@Preview(name = "cabinet-mine-phone", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun CabinetMinePhonePreview() {
    AppTheme(darkTheme = false) {
        Screen("cabinet", badges = navBadges(cabinetMineState)) { inner ->
            CabinetScreen(
                state = cabinetMineState,
                onSetShareRoot = {},
                onSetShareMode = {},
                onRemoveShareGrant = {},
                onSelectPeer = {},
                onLoadPath = {},
                onToggleSelection = {},
                onDownloadEntries = { _ -> },
                modifier = inner,
                now = PREVIEW_NOW,
            )
        }
    }
}

/**
 * 手机上的「同事文件柜」：左栏选中同事，右栏是目录列表。
 *
 * 与 `cabinet-mine-phone` 只差一个 `activePeerId`，但画的是 `PeerCabinetView`
 * 而不是 `MineCabinetView` —— 两帧必须并排看，否则其中一支坏了没人知道。
 */
@PreviewTest
@Preview(name = "cabinet-peer-phone", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun CabinetPeerPhonePreview() {
    AppTheme(darkTheme = false) {
        Screen("cabinet", badges = navBadges(cabinetPeerState)) { inner ->
            CabinetScreen(
                state = cabinetPeerState,
                onSetShareRoot = {},
                onSetShareMode = {},
                onRemoveShareGrant = {},
                onSelectPeer = {},
                onLoadPath = {},
                onToggleSelection = {},
                onDownloadEntries = { _ -> },
                modifier = inner,
                now = PREVIEW_NOW,
            )
        }
    }
}

/**
 * 手机上的文件柜、**还没配置过**：只有那张「还没设置共享目录」的卡片。
 *
 * 与 `cabinet-mine-phone` 是一对：一帧证明配好之后的样子，一帧证明没配之前的样子。
 */
@PreviewTest
@Preview(name = "cabinet-unset-phone", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun CabinetUnsetPhonePreview() {
    AppTheme(darkTheme = false) {
        Screen("cabinet", badges = navBadges(cabinetUnsetState)) { inner ->
            CabinetScreen(
                state = cabinetUnsetState,
                onSetShareRoot = {},
                onSetShareMode = {},
                onRemoveShareGrant = {},
                onSelectPeer = {},
                onLoadPath = {},
                onToggleSelection = {},
                onDownloadEntries = { _ -> },
                modifier = inner,
                now = PREVIEW_NOW,
            )
        }
    }
}

/**
 * **大字号 2.0 倍**：会话列表行。
 *
 * 补审计 §9 缺口⑤。
 * 全仓此前只有 `chat-large-font` 一处 `fontScale`，而且是 **1.3 倍** —— 1.3 是「有点大」，
 * 2.0 才是系统「最大」档（历代 Android 都有的硬上限）。1.3 证明不了极端。
 *
 * ## 为什么选会话列表
 *
 * 这是打开应用第一眼看到的屏，也是全仓**单行信息密度最高**的一行：头像 + 在线圆点、
 * 昵称 + Agent 徽标、预览文案、相对时间、未读徽标、待发条数 —— 六样东西抢同一行的宽。
 * 中文昵称没有空格可断，2.0 倍下只会整字换行或省略号，不会优雅折行；
 * 拉丁文那套「单词边界换行」的经验在这里不成立。
 *
 * ## 2.0 倍下最可能坏在哪
 *
 * 行尾那个 `Column(horizontalAlignment = End)` **没有 `weight`**
 * （`feature/conversations/ConversationsScreen.kt:434`）—— 它按内容固有宽度抢占，
 * 昵称那列（`Modifier.weight(1f)`，同文件 `:395`）只能吃剩下的。而里面的
 * `relativeTime` 与「N 条待发」两个 `Text` **都没有 `maxLines` / `overflow`**
 * （同文件 `:435` 与 `:451`）：2.0 倍下它们放不进剩余宽度时会自己折成两行，
 * 把整行撑高、与头像中线错开；而左边的昵称因为写了 `maxLines = 1` + `Ellipsis`
 * （`:400-406`）反而优雅收尾 —— 一行里两半的表现不一致，
 * 这正是只靠 1.0 倍图看不出来的东西。
 *
 * ## 没有它会怎样
 *
 * 1.3 倍那帧（`chat-large-font`）画的是**聊天详情**，根本不含会话列表；
 * 于是这个屏在极端字号下长什么样，全仓没有任何证据。门禁红灯时也无从判断
 * 是「本来就该这样」还是「刚被改坏」。
 *
 * 顺带：这一帧和下面两帧都套在 [Screen] 里，也就是都画了 `CompactScaffold` 的
 * 底部导航 —— 历史上真出过 `Conversations` → `Conversati` 的截断，所以底部标签
 * 在 2.0 倍下的表现**每帧都在钉**，不需要再单独开一帧。
 */
@PreviewTest
@Preview(
    name = "conversations-font2x",
    widthDp = 393,
    heightDp = 852,
    fontScale = 2.0f,
    showBackground = true,
    locale = "zh-rCN",
)
@Composable
private fun ConversationsFont2xPreview() {
    AppTheme(darkTheme = false) {
        Screen("conversations", badges = navBadges(normalState)) { inner ->
            ConversationsScreen(
                state = normalState,
                onOpenChat = {},
                onToggleMute = {},
                onDeleteConversation = {},
                onGoToPairing = {},
                onDismissBanner = {},
                modifier = inner,
                now = PREVIEW_NOW,
            )
        }
    }
}

/**
 * 2.0 倍字号那帧用的草稿：**过单播上限、没超单条上限**的那一档。
 *
 * 「先把结论写清楚，再补细节。」13 字 = 39 字节，×22 ⇒ **858 字节**，
 * 落在 800 < n ≤ 4096 之间 ⇒ `MessageComposer.kt:186` 的 `onUdp` 为假、
 * `:184` 的 `overLimit` 也为假，于是走 **TCP 路由分支**（状态行宽度最坏的一条，
 * 理由见下面 `chat-composer-font2x` 的 KDoc）。
 *
 * 用 `repeat` 而不是把 286 个字面量抄一遍：参考图只关心**字节数**落在哪一档，
 * 把同一句话重复 22 遍既看不出别的东西，又让 diff 变得不可读。
 */
private val font2xTcpDraft = "先把结论写清楚，再补细节。".repeat(22)

/**
 * **大字号 2.0 倍**：聊天输入栏的状态行（字节数 + 走哪条路）。
 *
 * ## 为什么选输入栏状态行
 *
 * 这是全仓**唯一**把两段自然语言塞进同一行、中间只靠一个 `Spacer(Modifier.weight(1f))`
 * 撑开的地方（`ui/components/MessageComposer.kt:405-441`）。它不是列表，没有「行可以变高」
 * 这个退路 —— 它压在输入框正下方，左边是发送键、右边是屏幕边缘，两头都没有余量。
 *
 * ## 2.0 倍下最可能坏在哪
 *
 * 那两个 `Text` **都没有 `maxLines` / `overflow`**（`MessageComposer.kt:412` 与 `:432`）。
 * 两段相加超过可用宽时，中间那个 `weight` 的 Spacer 已经被压到 0，
 * **没有任何收窄手段**，只能折行或溢出 —— 折行会把输入栏顶高、挤压上方的消息列表，
 * 溢出的字直接看不见。
 *
 * 草稿特意取**过单播上限但没超单条上限**的那一档（`858 / 4096 字节`，即 TCP 分支），
 * 因为那是**宽度最坏的一条分支**，不是随手挑的：
 *
 * | 分支 | 左半 | 右半 | 合计（em，粗估） |
 * | --- | --- | --- | --- |
 * | UDP（短草稿） | `30 / 800 字节` 5.75 | `局域网直连 · 走 UDP 单播` 11.00 | **16.75** |
 * | TCP（本帧） | `858 / 4096 字节` 6.75 | `超出单播上限，自动改走 TCP` 12.75 | **19.50** |
 *
 * 直觉容易反过来：UDP 那句「局域网直连 · 走 UDP 单播」**看着**更长，
 * 但它 CJK 字少、夹着拉丁与空格；TCP 那句几乎全是等宽汉字。按字形量，
 * TCP 分支左右两半都更宽，合计比 UDP 多约 2.75 em —— 2.0 倍下约 60dp，
 * 在 393dp 上正好是「刚好放得下」与「放不下」之间的那点余量。
 * 超限分支（`overLimit`）反而更窄：路由提示整条不画（`:429` 的 `if (!overLimit)`）。
 *
 * ## 没有它会怎样
 *
 * 现有的 `chat-composer-udp` / `chat-composer-tcp` / `chat-composer-over` 三帧全是 1.0 倍，
 * 而且都在 `ScreenshotPreviewsExtra.kt` 里（那个文件这一轮归别人在改）。状态行在极端
 * 字号下会不会把发送键挤出屏幕、会不会与表情面板叠在一起，没有任何图能回答。
 */
@PreviewTest
@Preview(
    name = "chat-composer-font2x",
    widthDp = 393,
    heightDp = 852,
    fontScale = 2.0f,
    showBackground = true,
    locale = "zh-rCN",
)
@Composable
private fun ChatComposerFont2xPreview() {
    AppTheme(darkTheme = false) {
        Screen("conversations", badges = navBadges(normalState)) { inner ->
            ChatScreen(
                data = ChatScreenState(
                    state = normalState,
                    peerId = desktop.nodeId,
                ),
                actions = chatPreviewActions(
                    onAttachFile = {},
                    onRecallMessage = {},
                    onNudge = {},
                ),
                chrome = ChatScreenChrome(
                    onBack = {},
                    modifier = inner,
                    initialDraft = font2xTcpDraft,
                )
            )
        }
    }
}

/**
 * **大字号 2.0 倍**：文件柜的目录列表（表格式行）。
 *
 * ## 为什么选文件柜列表
 *
 * 它是全仓**唯一**用写死 dp 当列宽的表格式列表：`feature/cabinet/CabinetScreen.kt:490`
 * 与 `:512` 两处 `Modifier.width(90.dp)`。别的列表都是「一列 `weight(1f)` 吃满剩余」，
 * 宽度不够时自动收窄；只有这里有一个**不会跟着字号长的固定格子**，
 * 所以它是「字号变大」与「布局写死」这两件事唯一正面相撞的地方。
 *
 * ## 2.0 倍下最可能坏在哪
 *
 * 大小列写死 90dp 且那个 `Text` **没有 `maxLines`**（`:512`），而名称列是
 * `weight(1f)` + `maxLines = 1` + `Ellipsis`（`:511`）。字号翻倍、格子不变，
 * 两个格子的**相对比例**因此在 2.0 倍下被改写：名称列能用的宽度变小，
 * 大小列那 90dp 里塞的字却大了一倍。名称列有省略号兜底，大小列没有 ——
 * 所以一旦 `formatBytes` 的结果放不进 90dp，它会在格子里折成两行，
 * 而名称列始终一行 ⇒ 同一行的两个格子高度不一致、行与行开始错位；
 * 表头「名称 / 大小」（`:489-490`）跟数据行共用同一组宽度，会跟着一起错。
 *
 * **诚实的边界**：这一帧的 fixture 只有两条文件行，`formatBytes` 给出的是
 * `82 KB` / `12 KB`（5 个字形），即便按 2.0 倍估也仍放得进 90dp ——
 * 也就是说**这张图很可能录不出折行**。真正会把 90dp 撑破的是 `1023 KB`
 * 这种 7 字形的量级（`Common.kt:476-481` 的分档上限），而 fixture 里没有。
 * 所以这一帧的价值是**建立基线**：它钉住的是「字号翻倍后这一屏长什么样」，
 * 而不是「已经坏了」。真正会把它撑破的量级见下面 `cabinet-peer-font2x-maxsize`
 * 那一帧 —— 两帧分工：这一帧保证与 1.0 倍图（`cabinet-peer-phone`）**同数据**、
 * 可逐像素对照；那一帧换成最坏数据、保证这条机制真的能被触发。
 * 两件事都要，所以是两帧而不是把这一帧的数据换掉。
 *
 * 保留这一帧而不是换掉它的理由：它是全仓唯一「固定 dp 列宽 + 此前零门禁」的组合
 * （见下），是这类风险的唯一观察点。**它不是均匀撒出来的第三屏**，而是三个候选里
 * 唯一「布局写死了、且没有任何图」的那一个。
 *
 * ## 没有它会怎样
 *
 * 文件柜此前在**任何形态**都没有截图门禁（审计 §9 缺口①：全仓唯一一个完全无门禁的
 * 一级页面），V8 这一轮才刚补上 `cabinet-mine-phone` / `cabinet-peer-phone` /
 * `cabinet-unset-phone` 三帧 —— 而这三帧全是 1.0 倍。也就是说这一屏的字号行为
 * 从门禁建立起就没有被钉过一次。
 */
@PreviewTest
@Preview(
    name = "cabinet-peer-font2x",
    widthDp = 393,
    heightDp = 852,
    fontScale = 2.0f,
    showBackground = true,
    locale = "zh-rCN",
)
@Composable
private fun CabinetPeerFont2xPreview() {
    AppTheme(darkTheme = false) {
        Screen("cabinet", badges = navBadges(cabinetPeerState)) { inner ->
            CabinetScreen(
                state = cabinetPeerState,
                onSetShareRoot = {},
                onSetShareMode = {},
                onRemoveShareGrant = {},
                onSelectPeer = {},
                onLoadPath = {},
                onToggleSelection = {},
                onDownloadEntries = { _ -> },
                modifier = inner,
                now = PREVIEW_NOW,
            )
        }
    }
}

/**
 * 与 [cabinetPeerState] 同形状，只把文件大小换成 [formatBytes] 的**分档上限**
 * —— 也就是大小列能画出的最长那个字符串。
 *
 * `Common.kt:476-481` 的分档是 `<1024 → "N B"` / `<1 MiB → "N KB"` / `<1 GiB → "N MB"`，
 * 每档取到最大时是 `1023 KB` / `1022 MB` / `1022 GB`，**都是 7 个字形**，
 * 这就是这个函数的上限（`1 GB` 以上反而回到 2 字形，更短）。
 * 所以 `1023 KB` 不是随手编的大数，而是**大小列可能画出的最坏值**。
 */
private val cabinetPeerMaxSizeState = cabinetPeerState.copy(
    cabinet = cabinetPeerState.cabinet.copy(
        entries = listOf(
            CabinetEntryUi(name = "第一季度", isDir = true, size = 0L, mtime = at(-2880)),
            CabinetEntryUi(name = "排期表.ods", isDir = false, size = 1023L * 1024, mtime = at(-300)),
            CabinetEntryUi(name = "会议纪要.md", isDir = false, size = 1022L * 1024 * 1024, mtime = at(-180)),
        ),
    ),
)

/**
 * **大字号 2.0 倍 + 大小列最坏值**：文件柜那张表真正会被撑破的那一档。
 *
 * ## 为什么和 `cabinet-peer-font2x` 分成两帧
 *
 * 那一帧用的是生产 fixture 原样的数据（`82 KB` / `12 KB`，5 个字形），
 * 2.0 倍下仍放得进 90dp —— **它录不出折行，也就证明不了这条机制没坏**。
 * 一帧永远绿的门禁等于没有门禁：它只会在有人真的改坏时，和别的帧一起绿着。
 *
 * 这一帧把数据换成 `1023 KB` / `1022 MB`（7 个字形，[formatBytes] 的分档上限），
 * 于是「字号翻倍 + 列宽写死 90dp」这两件事正面相撞，真的能被触发。
 * 粗估：`bodySmall` 12sp × 2.0 = 24sp，数字宽约 0.55em ⇒ 13dp/字，
 * 7 个字形连字距约 **93dp > 90dp** —— 就压在边界外沿。
 *
 * 两帧分工：`cabinet-peer-font2x` 与 1.0 倍的 `cabinet-peer-phone` **同数据**，
 * 保证字号是唯一变量、可逐像素对照；这一帧换最坏数据，保证机制真能被触发。
 *
 * ## 2.0 倍下最可能坏在哪
 *
 * `CabinetScreen.kt:512` 那个 `Text` **没有 `maxLines`**，所以放不下时它会**折成两行**，
 * 而同行左侧的名称列是 `weight(1f)` + `maxLines = 1` + `Ellipsis`（`:511`）
 * —— 结果是一行里两个格子高度不一致、行与行开始错位；表头「名称 / 大小」
 * （`:489-490`）与数据行共用同一组宽度，会跟着一起错。
 * 目录行显示的是 `—`（单字符，`:512` 的 `isDir` 分支），**不会折** ——
 * 所以如果参考图里出现「目录行对齐、文件行错位」，那正是这条机制被触发，
 * 不是渲染噪声。
 */
@PreviewTest
@Preview(
    name = "cabinet-peer-font2x-maxsize",
    widthDp = 393,
    heightDp = 852,
    fontScale = 2.0f,
    showBackground = true,
    locale = "zh-rCN",
)
@Composable
private fun CabinetPeerMaxSizeFont2xPreview() {
    AppTheme(darkTheme = false) {
        Screen("cabinet", badges = navBadges(cabinetPeerMaxSizeState)) { inner ->
            CabinetScreen(
                state = cabinetPeerMaxSizeState,
                onSetShareRoot = {},
                onSetShareMode = {},
                onRemoveShareGrant = {},
                onSelectPeer = {},
                onLoadPath = {},
                onToggleSelection = {},
                onDownloadEntries = { _ -> },
                modifier = inner,
                now = PREVIEW_NOW,
            )
        }
    }
}
