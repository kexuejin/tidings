// 第二轮「桌面外壳/空态组」精修帧（R2-W4，屏配对台账 2026-10-13 task-18）。
//
// 这一批与第一轮 `ScreenshotPreviewsDesktopPanes.kt` 的分工：
//
// 1. **第一轮那批直调 Pane**，回答「这块 Pane 单独画出来是什么样」，也是量具
//    （`tools/design-preview-pairing.py`）能判定落点的那一张。它们继续有效，
//    本轮**只重录、不改**（那个文件在禁改清单里）。
// 2. **本文件放两种帧**：
//    - 壳级整帧（`DesktopShell(...)`）—— 用来做「原型图 ↔ 代码图」的**并排目视对比**：
//      原型的桌面屏全都带窗口 chrome（菜单栏/标题栏/工具栏/状态栏），只有壳级帧能对上
//      那个构图。凡是在本轮改了「只有壳才看得见的东西」（比如首启空态的整幅宽、工具条
//      那排动作），都必须在这里留一张壳级证据。
//    - Pane 级帧 —— 只有当第一轮那张**参数不同、画面确实不一样**时才新增；
//      参数相同的重复帧会被 `render-preview.py --dup` 判重复（那条护栏是上一轮
//      用一张真重复的 PNG 换来的），这里不制造第二张。
//
// 窗口口径与第一轮一致：1100×720 让内容按 Expanded 判档。
package com.kapp.tidings.domain.screenshot

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.kapp.tidings.domain.ManualPeerEntry
import com.kapp.tidings.domain.RelayUiState
import com.kapp.tidings.ui.desktop.DesktopChromeMode
import com.kapp.tidings.ui.desktop.DesktopPreviewActions
import com.kapp.tidings.ui.desktop.DesktopShell
import com.kapp.tidings.ui.desktop.DesktopShellActions
import com.kapp.tidings.ui.desktop.DesktopView
import com.kapp.tidings.ui.desktop.PREVIEW_NOW
import com.kapp.tidings.ui.desktop.agentPeer
import com.kapp.tidings.ui.desktop.desktopPreviewAgentThreadState
import com.kapp.tidings.ui.desktop.desktopPreviewEmptyState
import com.kapp.tidings.ui.desktop.desktopPreviewGroupState
import com.kapp.tidings.ui.desktop.desktopPreviewNoSelectionState
import com.kapp.tidings.ui.desktop.desktopPreviewState
import com.kapp.tidings.ui.theme.AppTheme
import com.android.tools.screenshot.PreviewTest

/**
 * 壳级帧统一底板：**必须自己套 [AppTheme]**。
 *
 * 第一轮踩过这个坑（同源第 2 例，第 1 例是 W5 的 `AgentsPhoneShell`）：裸调 `DesktopShell`
 * 不套主题时，整壳落到 M3 基线紫（头像、徽标、「新建会话」按钮全变默认紫），
 * 而 `--precheck`、`--dup`、配对判定**三个量具全绿**，只有开图能看见。
 * Pane 级帧有 `PaneBackdrop` 兜底，壳级帧没人替它套。
 */
@Composable
private fun ShellBackdrop(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    AppTheme(darkTheme = darkTheme) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize(),
        ) { content() }
    }
}

// ────────────────────────────────── 首启空态（d-empty）

/**
 * 首启空态·壳级整帧（`d-empty`，原型 `screenDEmpty`，`prototype/relay.html:5124`）。
 *
 * 原型 shot `prototype/screenshots/relay/d07-empty.png` 是**带窗口 chrome** 的
 * （菜单栏/标题栏/工具栏/状态栏俱全），所以目视对比要用这张；
 * 量具那一行（台账落点 `ui/desktop/DesktopConversationsPane.kt / ConversationsPane`）
 * 仍由第一轮的 `DeskConversationsPaneEmptyPreview` 承担 —— 它的参数
 * （`desktopPreviewEmptyState()` + `sessions = emptyList()` + `Expanded`）正好落进
 * 本轮新加的首启空态分支，所以它现在渲染的就是这一屏（重录后 md5 从
 * `dd2363be…` 变成 `b41aa5fc…`），不需要再造一张同参帧。
 *
 * 夹具：`PreviewState.kt:88-98 desktopPreviewEmptyState()` —— 一个对端都没有、
 * 一条消息都没有，`network.localAddresses = ["192.168.1.23"]`、`boundUdpPort = 49213`、
 * `phase = Running`，所以二维码画得出来（原型 shot 里那块棋盘是它的 mock 占位）。
 */
@PreviewTest
@Preview(name = "r2-empty-shell", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun R2EmptyShellPreview() {
    ShellBackdrop {
        DesktopShell(
            state = desktopPreviewEmptyState(),
            controller = DesktopPreviewActions,
            windowWidthDp = 1100,
            windowHeightDp = 720,
            darkTheme = false,
            controlPlanePort = 0,
            chromeMode = DesktopChromeMode.Desktop,
            initialView = DesktopView.Conversations,
            initialPeerId = null,
            now = PREVIEW_NOW,
            actions = DesktopShellActions(
                onOpenDownloadsFolder = {},
                onOpenPeerWindow = {},
                onExportDiagnostics = {},
            ),
        )
    }
}

// ────────────────────────────────── 未选会话（d-nosel）

/**
 * 未选会话·壳级整帧（`d-nosel`，原型 `screenDNoSel`，`prototype/relay.html:5145-5151`）。
 *
 * 状态就是「有会话、但一个都没选中」：`desktopPreviewNoSelectionState()`
 * （`PreviewState.kt:101 = desktopPreviewState()`）、`initialPeerId = null`
 * ⇒ 左列表照常画、右栏是 `ChatScreen` 的那条空态
 * （`feature/chat/ChatScreen.kt:376-386`）。
 *
 * 与 `prototype/screenshots/relay/d08-nosel.png` 的已知差异：
 * 1. **原型右栏还有第三行 `.hint`**「也可以直接把文件拖进窗口，捎话会问你要发给谁。」
 *    （`relay.html:5149`，`.hint` 带 `margin-top:14px`）—— 代码**没有这一行**，
 *    而且原型自己在源码注释里就写明「代码里没有这句，**算提案**」
 *    （`relay.html:5142-5144`）。要补的话得改 `feature/chat/ChatScreen.kt:377-384`
 *    的 `EmptyState(...)`（那是 W1 的写域，本轮只报不改，见报告 §3 D7）。
 * 2. 左列表内容：原型 5 条（teahouse-mobile/老周/茶话间/Lin/ci-runner）+「建群 5」，
 *    夹具 3 条 +「建群 3」 —— 夹具差。
 * 3. 状态栏右端「配对链接 / 12.4 MB / 诊断包」缺前两项（真缺陷，报告 §3 D1/D2）。
 */
@PreviewTest
@Preview(name = "r2-nosel-shell", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun R2NoSelShellPreview() {
    ShellBackdrop {
        DesktopShell(
            state = desktopPreviewNoSelectionState(),
            controller = DesktopPreviewActions,
            windowWidthDp = 1100,
            windowHeightDp = 720,
            darkTheme = false,
            controlPlanePort = 0,
            chromeMode = DesktopChromeMode.Desktop,
            initialView = DesktopView.Conversations,
            initialPeerId = null,
            now = PREVIEW_NOW,
            actions = DesktopShellActions(
                onOpenDownloadsFolder = {},
                onOpenPeerWindow = {},
                onExportDiagnostics = {},
            ),
        )
    }
}

// ────────────────────────────────── 桌面单聊（d-chat）

/**
 * 桌面单聊·壳级整帧（`d-chat`，原型 `screenDChat`，`prototype/relay.html:4892-4913`）。
 *
 * **为什么用 Agent 会话**：原型 shot `prototype/screenshots/relay/d01-chat.png` 的
 * 对端就是 `teahouse-mobile`（badge `Agent`），头部动作是**两颗**
 * （复制节点 ID / 在新窗口打开，`screenDChat` 的 `acts` 字面量）。代码侧同档同样的
 * 对端给出的也正是这两颗 —— `DesktopConversationsPane.kt:608` 的 `onCopyId` 与
 * `:609` 的 `onOpenInNewWindow`；「屏幕协助」（`onRequestScreenView`，`:610-612`）
 * 有 `.takeIf { group == null && !peer.isAgent && peer.online }`，Agent 会话不画。
 * ⇒ 换成一个人对端会多出「屏幕协助」而少了与 shot 的逐颗对应，所以这里钉 Agent。
 *
 * **`onOpenPeerWindow` 必须武装**：`ui/desktop/DesktopShell.kt:97`
 * `val onOpenPeerWindow: ((String) -> Unit)? = null` 是可空的，不传则头部那一颗
 * 整颗不画。第一轮的 `Desk(...)` helper（`ScreenshotPreviewsDesktop.kt:66` 的
 * `noActions`）只传了 `onOpenDownloadsFolder`，所以 `DeskConversationsExpandedPreview`
 * 的头部只有「屏幕协助 / 复制节点 ID」两颗 —— 那一张本轮**只重录不改**（禁改清单），
 * 少的那一颗由这一张补上。
 *
 * 与 shot 的已知差异（都已在报告 d-chat 一节逐条登记）：
 * 1. 工具行缺第 7 颗「发送文件夹」—— 本端没有「选目录→发过去」链路
 *    （`fidelity-deviations.yaml:402-414` 的既有裁决）。
 * 2. 工具行**不画第 3 颗「窗口震动」**：原型 shot 画了（`relay.html:3114`），但它自陈的
 *    规则是「只在单聊出现，群里对『群』做震动没有收件人」（`relay.html:1588`），
 *    Agent 会话算不算单聊它没写；本端按手机端
 *    `feature/chat/ChatScreen.kt:601-603` 收紧到「不是 Agent」。
 * 3. 正文：**本轮已补**（D4）。新增夹具 `desktopPreviewAgentThreadState()`
 *    （`ui/desktop/PreviewState.kt`，只追加：一条入站气泡 + 一张入站文件卡 + 一条我方气泡），
 *    照原型 `screenDChat()` 的正文形状（`relay.html:4892-4913`）。文案与文件名是演示数据，
 *    原型用的是 `build-jvm.log`。
 * 4. 计数：原型 会话 5 / 建群 5，夹具 3 / 3。
 * 5. **状态栏右侧两处**（第一轮报告里的 D1/D2，**Lead 已裁**）：
 *    a. 「落盘量」读数（原型 `prototype/relay.html:2620` 的 `hdd` 12.4 MB）**本轮不画**，
 *    登记为偏差（见 `design/fidelity-deviations.yaml` 末尾那条 + 报告 §3 回执）。
 *    b. 「配对链接」**已修**（D1）：`DesktopStatusBar.kt` 改用
 *    `network.localAddresses.firstOrNull()` + `boundUdpPort`（0 时退回 `settings.udpPort`），
 *    并走权威构造器 `com.kapp.tidings.domain.pairingPayload(host, port)`；TCP 那格同理改用
 *    `settings.tcpPort`（原先恒读成 0）。三处的根因都是「拿 `state.peers` 当所有人用」，
 *    而 `PeerRegistry(selfNodeId)`（`core/.../transport/PeerRegistry.kt:40`）本就拒绝记录自己。
 *
 * 本帧把 `onExportDiagnostics` 也武装上（生产传的是真实现，
 * `desktopApp/.../DesktopWindow.kt:321 onExportDiagnostics = { exportDiagnostics(host) }`，
 * 所以状态栏那颗「诊断包」在产品里是有的）。
 */
@PreviewTest
@Preview(name = "r2-chat-shell-agent", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun R2ChatShellAgentPreview() {
    ShellBackdrop {
        DesktopShell(
            state = desktopPreviewAgentThreadState(),
            controller = DesktopPreviewActions,
            windowWidthDp = 1100,
            windowHeightDp = 720,
            darkTheme = false,
            controlPlanePort = 0,
            chromeMode = DesktopChromeMode.Desktop,
            initialView = DesktopView.Conversations,
            initialPeerId = agentPeer().nodeId,
            now = PREVIEW_NOW,
            actions = DesktopShellActions(
                onOpenDownloadsFolder = {},
                onOpenPeerWindow = {},
                onExportDiagnostics = {},
            ),
        )
    }
}

// ────────────────────────────────── 节点视图（d-nodes）

/**
 * 节点视图·壳级整帧（`d-nodes`，原型 `screenDNodes`，`prototype/relay.html:4992-4996`）。
 *
 * 这是用户 2026-10-12 亲改原型后的**新稿**：左栏一份收平的单列表（头像 + 昵称 + `Agent` tag
 * + 「在线/离线 · host:port」），右栏是该节点的详情（大头像 + 药丸 + 备注卡 + 详细信息卡
 * + 整宽「发消息」）。旧稿把手机那份 `NodesScreen` 整张塞进右栏（又画了一遍列表），
 * 本轮已按新稿重写 `ui/desktop/DesktopNodesPane.kt`。
 *
 * **为什么必须用壳级帧**：新稿把「N 个节点 · M 在线」放到**窗口标题栏**的副标题
 * （原型 `tsub('nodes')`，`relay.html:4849-4853`），那一行只有桌面 chrome 才画
 * （`ui/desktop/DesktopChrome.kt:258`）；面板自己只在不带标题栏的宿主上补一行
 * （`showNodeCountInPane = !chromeMode.showsDesktopChrome`，见 `DesktopShell.kt`）。
 * Pane 级帧（第一轮的 `DeskNodesPaneExpandedPreview`）看不到标题栏这一行。
 *
 * 夹具：`desktopPreviewState()` 的对端是 林岚 / 老周 / agent-node 三条（原型 shot 是 6 条：
 * 多出 Huang、ci-runner 离线、老周的旧笔记本 没有回应），不预置就选中左列表第一项
 * （= 林岚在线）。计数差属夹具差，已登记。
 */
@PreviewTest
@Preview(name = "r2-nodes-shell", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun R2NodesShellPreview() {
    ShellBackdrop {
        DesktopShell(
            state = desktopPreviewState(),
            controller = DesktopPreviewActions,
            windowWidthDp = 1100,
            windowHeightDp = 720,
            darkTheme = false,
            controlPlanePort = 0,
            chromeMode = DesktopChromeMode.Desktop,
            initialView = DesktopView.Nodes,
            initialPeerId = null,
            now = PREVIEW_NOW,
            actions = DesktopShellActions(
                onOpenDownloadsFolder = {},
                onOpenPeerWindow = {},
                onExportDiagnostics = {},
            ),
        )
    }
}

/**
 * 节点视图·「没有回应」详情态（原型 `nodeDetailBody` 的 `p.never` 支）。
 *
 * 这一支在实机上要求「手动保存过一条地址、但那条地址从没连通过」⇒ 列表里它排在所有对端之后，
 * 且**没有 nodeId**（`ManualPeerStatus.unreachable = nodeId == null`）。所以它既拍不到、
 * 也没有备注键：左栏那行是红点 + 「没有回应 · host:port」，右栏不画备注卡
 * （`RemarkCard` 的 `entry.nodeId != null` 守卫）、「发消息」置灰、下方给一行居中 hint。
 *
 * 夹具做法：`desktopPreviewState()` + 一条永不对得上的 `ManualPeerEntry`
 * （`manualPeerStatuses()` 按 `ip/host` + `udpPort` 匹配，`192.168.1.90` 不在对端表里
 * ⇒ `unreachable`）；选中项用**列表 key**（`"manual:<host>:<port>"`）预置 ——
 * 这条死地址没有 nodeId，只能靠 key 选中，所以 `NodesPane` 的 `initialNodeId`
 * 同时接受两者。
 *
 * 已知差异：原型 shot 里这条行是「老周的旧笔记本 · 192.168.1.90:17878」，本帧照抄；
 * 原型的死地址**也画备注卡**（它的 `nodeDetailBody` 不区分），代码不画 —— 数据层没有 key
 * 可存（`RelaySettings.peerRemarks` 按 nodeId 索引），登记为偏差，不造库。
 */
@PreviewTest
@Preview(name = "r2-nodes-never-shell", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun R2NodesNeverShellPreview() {
    ShellBackdrop {
        DesktopShell(
            state = nodesNeverState(),
            controller = DesktopPreviewActions,
            windowWidthDp = 1100,
            windowHeightDp = 720,
            darkTheme = false,
            controlPlanePort = 0,
            chromeMode = DesktopChromeMode.Desktop,
            initialView = DesktopView.Nodes,
            initialPeerId = "manual:192.168.1.90:17878",
            now = PREVIEW_NOW,
            actions = DesktopShellActions(
                onOpenDownloadsFolder = {},
                onOpenPeerWindow = {},
                onExportDiagnostics = {},
            ),
        )
    }
}

// ────────────────────────────────── ⋮ 溢出面板（d-home-more）

/**
 * `d-home-more` —— 平板宿主的「⋯」展开态。
 *
 * 这一屏的**全部内容**就是原型 `dMore(true)` 画的那块面板（`prototype/relay.html:2622-2637`），
 * 而生产的菜单走 `DropdownMenu` → `Popup`，Layoutlib 不组合它的子内容（所以此前的参考图里
 * 只有 ⋮ 一颗按钮，面板是空的）。这张帧用壳的截图接缝 `overflowPanelPreview = true`：
 * 壳把**同一份 `items`** 交给 `ToolbarOverflowPanel` 就地画一遍
 * （生产调用方一律不传这个参数，`DesktopShell` 的默认值是 `false`）。
 *
 * 构图照参考图 `prototype/screenshots/relay/d01b-tabhost-more.png`：平板宿主
 * （`chromeMode = Tablet` ⇒ 无菜单栏、无标题栏）、Expanded 判档、选中 `teahouse-mobile`（Agent）。
 *
 * 六个 actions 全给上，八条才齐 —— 少哪条哪条就不画，那是刻意的
 * （平板真的没有诊断包导出 / 系统下载目录 / 退出应用）。
 */
@PreviewTest
@Preview(name = "r2-home-more-shell", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun R2HomeMoreShellPreview() {
    ShellBackdrop {
        DesktopShell(
            state = desktopPreviewAgentThreadState(),
            controller = DesktopPreviewActions,
            windowWidthDp = 1100,
            windowHeightDp = 720,
            darkTheme = false,
            controlPlanePort = 0,
            chromeMode = DesktopChromeMode.Tablet,
            initialView = DesktopView.Conversations,
            initialPeerId = agentPeer().nodeId,
            now = PREVIEW_NOW,
            overflowPanelPreview = true,
            actions = DesktopShellActions(
                onOpenSettings = {},
                onToggleTheme = {},
                onExportDiagnostics = {},
                onOpenDownloadsFolder = {},
                onRefresh = {},
                onQuit = {},
            ),
        )
    }
}

// ────────────────────────────────── 群聊·深色（d-chat-group 的第二张 shot）

/**
 * `d-chat-group` 的**深色** shot：原型 `screenDChatGroup()` 的同一屏在 `d21-chat-group-dark.png`
 * 里再拍了一张（`prototype/screenshots/relay/`，与 d02 同尺寸、同构图，只有主题不同）。
 *
 * 浅色那张的落点是第一轮的 Pane 级帧
 * `ScreenshotPreviewsDesktopPanes.kt` 的 `DeskConversationsPaneGroupPreview`
 * （台账「代码落点」= `ConversationsPane`，量具只读得到 Pane 级帧）；深色这张用壳级帧补，
 * 因为原型的深色 shot 同样带窗口 chrome（菜单栏/标题栏/工具栏/状态栏），只有壳级帧能对上构图。
 *
 * 建帧时（2026-10-13）改了两处群聊专用像素，两张 shot 都要重录：
 * ①群头「N/M 人在线」标签紧贴群名（`prototype/relay.html:4891` 的 `o.badge` 就写在标题旁边）；
 * ②工具行末尾多一句 `.shint`「仅在线群成员可接收图片/文件」（`:3162`，单聊支没有）。
 */
@PreviewTest
@Preview(name = "r2-chat-group-dark-shell", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun R2ChatGroupDarkShellPreview() {
    val state = desktopPreviewGroupState()
    ShellBackdrop(darkTheme = true) {
        DesktopShell(
            state = state,
            controller = DesktopPreviewActions,
            windowWidthDp = 1100,
            windowHeightDp = 720,
            darkTheme = true,
            controlPlanePort = 0,
            chromeMode = DesktopChromeMode.Desktop,
            initialView = DesktopView.Conversations,
            initialPeerId = state.conversations.first { it.isGroup }.convId,
            now = PREVIEW_NOW,
            actions = DesktopShellActions(onOpenDownloadsFolder = {}, onExportDiagnostics = {}),
        )
    }
}

/** `desktopPreviewState()` + 一条永不对得上的手动地址（「没有回应」那条详情态要用）。 */
private fun nodesNeverState(): RelayUiState =
    desktopPreviewState().let { base ->
        base.copy(
            settings = base.settings.copy(
                manualPeers = base.settings.manualPeers +
                    ManualPeerEntry(host = "192.168.1.90", port = 17878, name = "老周的旧笔记本"),
            ),
        )
    }
