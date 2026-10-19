// 桌面 Pane 直调帧（屏配对台账 2026-10-12：W4 那 15 屏的「同域帧」补录）。
//
// 为什么需要这一批，而不是复用 `ScreenshotPreviewsDesktop.kt` 里已有的 `Desk*` 帧：
//
// 1. **量具读的是函数体，不是帧名。** `tools/design-preview-pairing.py` 会解析
//    每个 `@Preview` 的**函数体**，抓里面出现的屏级 Composable 名，再与台账「代码落点」
//    列声明的落点对账。已有的 `Desk*` 帧全都只调一个 `private fun Desk(...)` 壳，
//    函数体里**没有任何屏级 Composable**，于是量具一律记「无法判定」——
//    不是帧错了，是它看不见。这里每个帧**直接调那个 Pane**，函数体第一行就是它。
// 2. **Pane 自己的档位判断要单独钉一次。** `DesktopShell` 会把左侧竖导切掉再交给 Pane，
//    Pane 收到的宽度不等于窗口宽（`FilesPane` 的 KDoc 专门写了这件事）。只拍壳级整帧
//    时，Pane 内部的 `BoxWithConstraints` 分档永远被壳的正确宽度掩盖；这里以真实宽度
//    直调，等于给那半张合同单独一个证据。
//
// 与既有 `Desk*` 帧的关系：**并存，不替换**。壳级整帧回答「整窗长什么样」，这一批回答
// 「这块 Pane 单独画出来是什么样」。两者都要，缺一不可（`ScreenshotPreviewsDesktop.kt`
// 在冻结清单里，本批不改它一行）。
//
// 窗口口径：会话/文件/节点/文件柜四类 Pane 用 1100×720 直调，让 Pane 自己按可用宽度判出
// Expanded（双栏）；`d-search` / `d-search-empty` 两屏是**整幅宽一页**（原型
// `screenDSearch`（`prototype/relay.html:4454`）不调 `dwin(...)`，没有侧栏也没有右栏聊天区），
// 所以同样按 Expanded 直调 `ConversationsPane`，由它自己走 `DesktopGlobalSearchPage` 那一支；
// `d-nomatch` 是**侧栏筛选空态 + 右栏未选态**（原型 `screenDNoMatch`（`:5171`）走 `dwin('chat', …)`），
// 用 `globalSearchEnabled = false` 让侧栏那套筛选回到可达状态，同样是 Expanded 双栏。
// `d-home-more` 那一屏要的是**平板宿主**（无菜单栏/标题栏/状态栏），Pane 级画不出来，
// 所以它是本文件里唯一直接调 `DesktopShell` 的帧（见文件末尾）。
package app.relay.app.screenshot

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.relay.app.GlobalSearchResult
import app.relay.feature.agents.AgentConsoleScreen
import app.relay.ui.desktop.ConversationsPane
import app.relay.ui.desktop.DesktopChromeMode
import app.relay.ui.desktop.DesktopPaneActions
import app.relay.ui.desktop.DesktopPreviewActions
import app.relay.ui.desktop.DesktopPreviewSearchActions
import app.relay.ui.desktop.DesktopShell
import app.relay.ui.desktop.DesktopShellActions
import app.relay.ui.desktop.DesktopView
import app.relay.ui.desktop.DesktopWindowSizeClass
import app.relay.ui.desktop.FilesPane
import app.relay.ui.desktop.NodesPane
import app.relay.ui.desktop.CabinetPane
import app.relay.ui.desktop.PREVIEW_NOW
import app.relay.ui.desktop.desktopPreviewCabinetPeerState
import app.relay.ui.desktop.desktopPreviewConversations
import app.relay.ui.desktop.desktopPreviewEmptyState
import app.relay.ui.desktop.desktopPreviewFilesState
import app.relay.ui.desktop.desktopPreviewFolderState
import app.relay.ui.desktop.desktopPreviewGroupState
import app.relay.ui.desktop.desktopPreviewNoFilesState
import app.relay.ui.desktop.desktopPreviewState
import app.relay.ui.theme.AppTheme
import app.relay.ui.theme.RelayShapes
import com.android.tools.screenshot.PreviewTest

/** 直调帧统一底板：Pane 自己不带背景（背景由壳/主题给），这里补一层。 */
@Composable
private fun PaneBackdrop(darkTheme: Boolean = false, content: @Composable () -> Unit) =
    PreviewBackdrop(darkTheme = darkTheme, cardShape = RelayShapes.medium, content = content)



// ────────────────────────────────── 会话视图（d-nosel / d-empty / d-chat-group）

/**
 * 空态·未选会话（`d-nosel`）——直调 [ConversationsPane]。
 *
 * 钉的是 `ChatPane` 在 `selectedPeerId == null` 时走 `ChatScreen` 的「选一个会话」那一支，
 * 与左侧列表同框。原型 shot：`prototype/screenshots/relay/d08-nosel.png`。
 */
@PreviewTest
@Preview(name = "desk-nosel-pane-expanded", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskConversationsPaneNoSelectionPreview() {
    PaneBackdrop {
        ConversationsPane(
            state = desktopPreviewState(),
            controller = DesktopPreviewActions,
            sizeClass = DesktopWindowSizeClass.Expanded,
            sessions = desktopPreviewConversations(),
            selectedPeerId = null,
            query = "",
            onClearQuery = {},
            onCopyPeerId = {},
            onOpenInNewWindow = null,
            onSelect = {},
            onGoToPairing = {},
            now = PREVIEW_NOW,
        )
    }
}

/**
 * 空态·首次打开（`d-empty`）——直调 [ConversationsPane]，会话表**真的为空**。
 *
 * **2026-10-13（第二轮）起这一帧画的就是原型的首启引导，不再是「空列表 + 未选会话」。**
 * 第一轮这里登记的是「有据缺口」：`prototype/relay.html:5121-5123`（紧邻
 * `function screenDEmpty(){` 的 `:5124` 之上）自述「这一屏是原型提案：Code 里没有对应的
 * 首启引导……它不算既有能力」。本轮 W4 把三处障碍全部拆掉（壳有「未连接」判据、
 * 三张 entry 卡抽成 `OnboardingEntryCard`、二维码复用 `QrCodeView`），
 * `ConversationsPane` 现在有 `if (sessions.isEmpty() && state.peers.isEmpty() &&
 * state.network.phase != NodePhase.Starting) { DesktopEmptyOnboarding(...); return }` 这一支
 * ⇒ **本帧走的正是那一支**，与 `prototype/screenshots/relay/d07-empty.png` 同形。
 * 下面那段「不冒充」的旧注释是历史，保留一句以免有人按旧结论回退：
 * 缺口曾经真实存在过，它不是「不适用」。
 *
 * ⚠️ 注意 `sessions` 必须传**空表**：早先误传 `desktopPreviewConversations()`，
 * 结果与 `DeskConversationsPaneNoSelectionPreview` 的 PNG **逐字节相同**
 * （md5 `f431aee7f1faf8f5fb22a30fff3a836a`）——「空态帧其实不空」这种错，
 * 只看量具或只看帧名都发现不了。
 */
@PreviewTest
@Preview(name = "desk-empty-pane-expanded", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskConversationsPaneEmptyPreview() {
    PaneBackdrop {
        ConversationsPane(
            state = desktopPreviewEmptyState(),
            controller = DesktopPreviewActions,
            sizeClass = DesktopWindowSizeClass.Expanded,
            sessions = emptyList(),
            selectedPeerId = null,
            query = "",
            onClearQuery = {},
            onCopyPeerId = {},
            onOpenInNewWindow = null,
            onSelect = {},
            onGoToPairing = {},
            now = PREVIEW_NOW,
        )
    }
}

/**
 * 会话视图·群聊（`d-chat-group`）——直调 [ConversationsPane]，选中群会话。
 *
 * 群没有 `PeerUi`，`ChatPane` 走 `state.groupPeer(group)` 兜底、`DesktopChatHeader`
 * 另有一整支 `group != null`。原型 shot：`…/d02-chat-group.png`（附带
 * `d21-chat-group-dark.png`）。
 */
@PreviewTest
@Preview(name = "desk-group-pane-expanded", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskConversationsPaneGroupPreview() {
    val state = desktopPreviewGroupState()
    PaneBackdrop {
        ConversationsPane(
            state = state,
            controller = DesktopPreviewActions,
            sizeClass = DesktopWindowSizeClass.Expanded,
            sessions = state.conversations,
            selectedPeerId = state.conversations.first { it.isGroup }.convId,
            query = "",
            onClearQuery = {},
            onCopyPeerId = {},
            onOpenInNewWindow = null,
            onSelect = {},
            onGoToPairing = {},
            now = PREVIEW_NOW,
            onCreateGroup = { _, _, _ -> null },
        )
    }
}

/**
 * 会话视图·群聊·**群主是远端节点**（这才是与 `d-chat-group` 配对的那一支）。
 *
 * 原型 `prototype/relay.html:1674` 的那个群逐字是 `{groupId:'pub', name:'茶话间 · 发布值班',
 * rev:7, ownerId:NID.ka, adminIds:[NID.lin], members:[…5 人]}` —— **别人建的群**；
 * 而 `:4969` 的群头 sub 模板是 `${g.members.length} 位成员 · rev ${g.rev} · 群主 ${peerOf(g.ownerId).nick}`。
 * 代码这边的公共夹具 `desktopPreviewGroupState()`（`ui/desktop/PreviewState.kt:428`）
 * `ownerId = SELF_NODE_ID`，画的是**本机自己建的群** ⇒ `groupOwnerName` 的 `else` 支
 * （已知远端查节点表 / 未知远端回落 `take(8)`）此前 **0 帧**覆盖，而它恰好就是原型画的那一支。
 *
 * 这一帧把 ownerId 换成 `state.peers` 里的一个**远端**节点（复用现有夹具，不照抄原型的
 * ka / 5 人 / rev 7 —— 演示数据不同按本项目先例登记为「演示数据不同」，不追平）。
 * 第二行预期「3 位成员 · rev 4 · 群主 <远端昵称>」；与上面那帧（self 支）一起，
 * 两支回落链各有一张参考图。
 */
@PreviewTest
@Preview(name = "desk-group-remote-owner-pane-expanded", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskConversationsPaneGroupRemoteOwnerPreview() {
    val base = desktopPreviewGroupState()
    val owner = base.peers.first { it.nodeId != base.selfNodeId }.nodeId
    val state = base.copy(
        settings = base.settings.copy(
            groups = base.settings.groups.map { it.copy(ownerId = owner) },
        ),
    )
    PaneBackdrop {
        ConversationsPane(
            state = state,
            controller = DesktopPreviewActions,
            sizeClass = DesktopWindowSizeClass.Expanded,
            sessions = state.conversations,
            selectedPeerId = state.conversations.first { it.isGroup }.convId,
            query = "",
            onClearQuery = {},
            onCopyPeerId = {},
            onOpenInNewWindow = null,
            onSelect = {},
            onGoToPairing = {},
            now = PREVIEW_NOW,
            onCreateGroup = { _, _, _ -> null },
        )
    }
}

// ────────────────────────────────── 全局搜索（d-search / d-search-empty / d-nomatch）

/**
 * 全局搜索（`d-search`）——直调 [ConversationsPane]，**整幅宽一页** + 词 + 首帧结果。
 *
 * 原型 shot `d26-search.png` 是整幅宽的一页：一条 topbar（「会话 / 5 个会话 / +」）、
 * 一条大搜索框（`.spbox`，`prototype/relay.html:4458`）、下面三段命中，**没有侧栏、
 * 也没有右栏聊天区** —— `screenDSearch`（`prototype/relay.html:4454-4472`）连
 * `dwin(...)` 都没调。所以这里给 Expanded（1100×720）：`sizeClass` 在这一支里已经
 * 不影响版式（非空 `query` 在 `when (sizeClass)` 之前就接管了整幅宽），给宽档只是
 * 为了和参考图同宽。
 *
 * `DesktopPreviewSearchActions` 是必须的：接口默认的 `searchGlobal` 返回空结果，
 * 用它拍搜索永远只能拍到「没找到」那一支（`DesktopPaneActions.kt:133`）。
 *
 * `onCreateGroup` 是**武装的**：原型 topbar 的 acts 里就有那颗 `+`
 * （`prototype/relay.html:4455`），不传则画面里那格是空的 —— 少一件原型固有的东西。
 */
@PreviewTest
@Preview(name = "desk-search-page-expanded", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskConversationsPaneSearchPreview() {
    PaneBackdrop {
        ConversationsPane(
            state = desktopPreviewState(),
            controller = DesktopPreviewSearchActions,
            sizeClass = DesktopWindowSizeClass.Expanded,
            sessions = desktopPreviewConversations(),
            selectedPeerId = null,
            query = "协议",
            onClearQuery = {},
            onCopyPeerId = {},
            onOpenInNewWindow = null,
            onSelect = {},
            onGoToPairing = {},
            now = PREVIEW_NOW,
            onCreateGroup = { _, _, _ -> null },
            initialGlobalSearch = DesktopPreviewSearchActions.searchGlobal("协议"),
        )
    }
}

/**
 * 全局搜索·无结果（`d-search-empty`）——整幅宽一页 + 首帧空结果。
 *
 * 「三块都空」这一支此前**只能靠 `LaunchedEffect` 到达**，而截图渲染器不执行协程体
 * ，所以这一支没有冷启动接缝时永远拍不到。接缝是
 * `initialResult` 的空结果分支（`DesktopGlobalSearch.kt` 的 `state` 初值）。
 *
 * 注意它与 `d-nomatch` 是**两条不同的屏**：这一屏是整幅宽搜索页的空态（只有两行居中
 * 文字，没有「清除搜索」按钮 —— 清除入口是工具条搜索框里的 ✕）；`d-nomatch` 是
 * 侧栏自己那套筛选的空态（带按钮）。
 *
 * `onCreateGroup` 同 `DeskConversationsPaneSearchPreview`（原型 topbar 那颗 `+`）。
 */
@PreviewTest
@Preview(name = "desk-search-empty-page-expanded", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskConversationsPaneSearchEmptyPreview() {
    PaneBackdrop {
        ConversationsPane(
            state = desktopPreviewState(),
            controller = DesktopPreviewSearchActions,
            sizeClass = DesktopWindowSizeClass.Expanded,
            sessions = desktopPreviewConversations(),
            selectedPeerId = null,
            query = "捎话",
            onClearQuery = {},
            onCopyPeerId = {},
            onOpenInNewWindow = null,
            onSelect = {},
            onGoToPairing = {},
            now = PREVIEW_NOW,
            onCreateGroup = { _, _, _ -> null },
            initialGlobalSearch = GlobalSearchResult(query = "捎话"),
        )
    }
}

/**
 * 侧栏筛选·无结果（`d-nomatch`）——Expanded 双栏：侧栏筛选空态 + 右栏未选会话。
 *
 * 这一屏**不是**整幅宽搜索页的 Empty（那是上一帧）。原型 `screenDNoMatch`
 * （`prototype/relay.html:5171-5184`）调的是 `dwin('chat', …)`：侧栏
 * 「没有匹配「insets」的结果 / 换个关键词试试，或者清除搜索看全部会话。/ [清除搜索]」，
 * 右栏仍是未选态。代码侧对应物是 `ConversationSidebar` 的筛选空态支
 * （`DesktopConversationsPane.kt` 的 `if (filtering) EmptyState(desktop_no_match…)`），
 * 而它在生产里被全局搜索接管 —— `onSearchGlobal != null` 时侧栏整块换成搜索面板。
 * 本帧用 `globalSearchEnabled = false` 把这个接缝关掉（同一个形参的 KDoc 写明：
 * 生产恒为 true），才拍得到侧栏那半张屏。
 *
 * 两处输入是真的、不是摆拍：`sessions = emptyList()` 正是「按词筛完一条不剩」时
 * 组件收到的那个列表；`controller = DesktopPreviewActions` 而不是
 * `DesktopPreviewSearchActions`，因为后者的 `searchHistoricalMessages` 会返回固定命中
 * （`PreviewState.kt:692`），侧栏就会去画「聊天记录」那一段而**跳过**筛选空态 ——
 * 那拍出来的是另一个状态，不是这一屏。
 *
 * 原型自身一处不一致照记：这一屏的侧栏头逐字是「**群聊**」（`sidehead`），而同族屏
 * （`d-home-more` / `d-chat-group` / `d-files`…）都是「会话」；代码统一用「会话」
 * （`desktop_view_conversations`），不跟着这一屏的孤例走。
 *
 * `onCreateGroup` 武装：原型这一屏的 sidehead 里就有「建群」
 * （`prototype/relay.html:5172`），不传则侧栏头右侧那格是空的。
 */
@PreviewTest
@Preview(name = "desk-nomatch-sidebar-expanded", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskConversationsPaneNoMatchPreview() {
    PaneBackdrop {
        ConversationsPane(
            state = desktopPreviewState(),
            controller = DesktopPreviewActions,
            sizeClass = DesktopWindowSizeClass.Expanded,
            sessions = emptyList(),
            selectedPeerId = null,
            query = "insets",
            onClearQuery = {},
            onCopyPeerId = {},
            onOpenInNewWindow = null,
            onSelect = {},
            onGoToPairing = {},
            now = PREVIEW_NOW,
            onCreateGroup = { _, _, _ -> null },
            globalSearchEnabled = false,
        )
    }
}

// ────────────────────────────────── 文件视图（d-files / d-filesempty / d-folder / d-resume）

/**
 * 文件视图（`d-files`）——直调 [FilesPane]，Expanded 双栏。
 *
 * 这一帧存在的直接理由：`DesktopShell` 此前调 `FilesPane` **没传 `sizeClass`**，
 * Pane 里的 `BoxWithConstraints` 会用默认 `chromeMode = Desktop` 把已经扣过一次的
 * 高度再扣一次，1100×720 下必然 `< 420` ⇒ 误降 Medium，于是「列表/详情」两栏
 * 从来拍不到（壳级那张 `desk-files-expanded` 参考图右半整块空白就是这个根因）。
 * 修复（壳补三行实参）之后这张才成立。
 *
 * `onResumeTransfer` / `onCancelTransfer` 也**必须传**：生产永远传
 * （`DesktopShell.kt:475 FilesPane(` 的 `:488 onResumeTransfer = controller::resumeTransfer`、
 * `:489 onCancelTransfer = controller::cancelTransfer`），不传的帧画的不是产品。
 * 本帧夹具状态里没有 `Failed && !mine` 的文件，所以武装后画面**预计不变** ——
 * 原型 `screenDFiles`（`prototype/relay.html:4980-5013`）逐行扫过也没有「取消/继续」
 * （那两支属会话内文件卡的操作行，见 `:2733` 的 `if (f.state === 'active-out')`）；
 * 武装的价值是「帧忠实于生产」+ 把那 6 条「可空闸门没传」的告警永久消掉。
 */
@PreviewTest
@Preview(name = "desk-files-pane-expanded", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskFilesPaneExpandedPreview() {
    PaneBackdrop {
        FilesPane(
            state = desktopPreviewFilesState(),
            query = "",
            onClearQuery = {},
            onDismissBanner = {},
            onOpenFolder = {},
            onJumpToConversation = {},
            onRefresh = {},
            now = PREVIEW_NOW,
            sizeClass = DesktopWindowSizeClass.Expanded,
            initialTransferId = null,
            onResumeTransfer = {},
            onCancelTransfer = {},
        )
    }
}

/**
 * 文件视图·**已完成的入站文件**（`d-files` 的主体）——直调 [FilesPane]，
 * `initialTransferId = "t-done"`。
 *
 * 原型 `screenDFiles`（`prototype/relay.html:4980-5013`）本身就是**详情栏**：
 * `dwin('files', …)` 的右栏画「头部 + 工具栏 + 落盘/传输信息两张卡」，左栏由
 * `sideFiles('f1')` 给出、默认选中 f1（图纸终稿-v4.pdf，已完成、有落盘路径）。
 * 所以「报告 §3 第 2 条说右侧两张卡代码里本来就有」这句话**只能在选中一条完成态
 * 文件的帧里被证明**：`DeskFilesPaneExpandedPreview` 传 `initialTransferId = null`，
 * 详情栏画的是「选中一个文件查看详情」，证明不了它。
 * 夹具出处：`PreviewState.kt:254-262` 的 `sampleFileCard(TransferState.Completed)`，
 * `transferId = "t-done"`、名字 relay-desktop.dmg、`savedPaths` 非空、`mine = false`。
 */
@PreviewTest
@Preview(name = "desk-files-completed-pane-expanded", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskFilesPaneCompletedPreview() {
    PaneBackdrop {
        FilesPane(
            state = desktopPreviewFilesState(),
            query = "",
            onClearQuery = {},
            onDismissBanner = {},
            onOpenFolder = {},
            onJumpToConversation = {},
            onRefresh = {},
            now = PREVIEW_NOW,
            sizeClass = DesktopWindowSizeClass.Expanded,
            initialTransferId = "t-done",
            onResumeTransfer = {},
            onCancelTransfer = {},
        )
    }
}

/**
 * 空态·无文件（`d-filesempty`）——直调 [FilesPane]，Expanded。
 *
 * 原型 `d09-filesempty.png` 的注释写明「整块换成 EmptyState（列表和详情都不画）…
 * 头部一行（文件 + 打开落盘目录 + 刷新）是常驻的」；`FilesPane` 的 `:190-204` 正是
 * 那个形状。两个可空回调同样武装（理由见 `DeskFilesPaneExpandedPreview` 的 KDoc；
 * 本帧一个文件都没有，画面不受影响）。
 */
@PreviewTest
@Preview(name = "desk-files-empty-pane-expanded", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskFilesPaneEmptyPreview() {
    PaneBackdrop {
        FilesPane(
            state = desktopPreviewNoFilesState(),
            query = "",
            onClearQuery = {},
            onDismissBanner = {},
            onOpenFolder = {},
            onJumpToConversation = {},
            onRefresh = {},
            now = PREVIEW_NOW,
            sizeClass = DesktopWindowSizeClass.Expanded,
            initialTransferId = null,
            onResumeTransfer = {},
            onCancelTransfer = {},
        )
    }
}

/**
 * 文件夹卡片（`d-folder`）——直调 [FilesPane]，选中文件夹传输。
 *
 * 原型 `d24-folder.png` 的右侧详情只要「头部 + 两个按钮」，源码注释写明
 * 「两边都没有子文件列表，这一点是刻意的，不是还没画」。两个可空回调同样武装
 * （理由见 `DeskFilesPaneExpandedPreview` 的 KDoc）。
 */
@PreviewTest
@Preview(name = "desk-files-folder-pane-expanded", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskFilesPaneFolderPreview() {
    PaneBackdrop {
        FilesPane(
            state = desktopPreviewFolderState(),
            query = "",
            onClearQuery = {},
            onDismissBanner = {},
            onOpenFolder = {},
            onJumpToConversation = {},
            onRefresh = {},
            now = PREVIEW_NOW,
            sizeClass = DesktopWindowSizeClass.Expanded,
            initialTransferId = "t-folder",
            onResumeTransfer = {},
            onCancelTransfer = {},
        )
    }
}

/**
 * 断点续传（`d-resume`）——直调 [FilesPane]，选中一条失败入站传输。
 *
 * `onResumeTransfer` / `onCancelTransfer` **必须传**：`FileDetailPane` 里那一支是
 * `state == Failed && !mine && onResumeTransfer != null`，少传就等于续传按钮整支不渲染
 * （同一个「夹具欠武装」的形状，见 `ScreenshotPreviewsHostGap.kt` 的 KDoc）。
 * 壳级那张 `desk-files-resume-expanded` 因为误降 Medium，详情被 320dp 高度上限截到
 * 画面外，续传按钮一次都没进过参考图；这张双栏点开了它。
 */
@PreviewTest
@Preview(name = "desk-files-resume-pane-expanded", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskFilesPaneResumePreview() {
    PaneBackdrop {
        FilesPane(
            state = desktopPreviewFilesState(),
            query = "",
            onClearQuery = {},
            onDismissBanner = {},
            onOpenFolder = {},
            onJumpToConversation = {},
            onRefresh = {},
            now = PREVIEW_NOW,
            sizeClass = DesktopWindowSizeClass.Expanded,
            initialTransferId = "t-fail",
            onResumeTransfer = {},
            onCancelTransfer = {},
        )
    }
}

// ────────────────────────────────── 节点 / Agent / 文件柜

/**
 * 节点视图（`d-nodes`）——直调 [NodesPane]，Expanded 双栏。
 *
 * **2026-10-12 重写后的形状**（R3 task-23）：左栏是一条**收平的**节点列表
 * （行 = 头像 + `displayName` + Agent 徽标 + 右端「在线 / 离线 / 没有回应」+ 等宽
 * `host:port`；无分组小节头、无行尾 ⋯ 菜单），右栏是**选中节点的详情**
 * （大头像 + 名字 + 状态药丸 + 「未分组」+ 备注卡 + 详细信息四行 + 整宽「发消息」）。
 *
 * 与原型 `d05-nodes.png` 的对应关系、以及三处「只有开图才看得见」的修正
 * （宽档丢横幅、壳漏传 `now`、死地址的「最近」语义），都写在
 * `docs/plans/2026-10-13-r3-W4-nodes-detail.md`。
 *
 * 旧 KDoc 描述的是 R1 的版式（左「局域网发现 / 已保存的节点（管理）」+ 右 mDNS 条 +
 * 本机卡 + 仅桌面·本机节点 ID 条），那套结构在新稿里已不存在 —— 本帧仍然直调
 * [NodesPane]（而不是委托手机那份单列 `NodesScreen`），因为原型这一屏就是面板级双栏。
 */
@PreviewTest
@Preview(name = "desk-nodes-pane-expanded", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskNodesPaneExpandedPreview() {
    PaneBackdrop {
        NodesPane(
            state = desktopPreviewState(),
            controller = DesktopPreviewActions,
            onCopyNodeId = {},
            sizeClass = DesktopWindowSizeClass.Expanded,
        )
    }
}

/**
 * 工作区（`d-agents`）——直调 [AgentConsoleScreen]，Expanded。
 *
 * **2026-10-14 改**：这一帧原先直调 `AgentPane`（会话视图里的一个浮层，
 * 2026-10-14 起已删除）。工作区现在是 `DesktopView` 的第五个平级视图，生产路径是
 * 壳的 `DesktopView.Agents` 分支 → `AgentConsoleScreen`；本帧按直调帧的规矩
 * （函数体第一行就是要钉的那个 Composable，见文件头）直接调它，档位 Expanded。
 *
 * 夹具与 `R2DeskAgentsWindowPreview`（整窗）不同：这里用的是通用
 * `desktopPreviewState()`，它的 `agentApprovals` / `agentSessions` 都是空 map，
 * 所以本帧画的是**空态**看板 —— 填充态那条由 `r2-desk-agents-rich` 那个整窗帧守着。
 * 两帧形状互补：这一帧钉「Pane 自己在 Expanded 下怎么收列」，那一帧钉「真数据长什么样」。
 *
 * 结构差：整窗帧还有左侧竖导 + 菜单栏 + 状态栏，本帧只有面板自己那一块。
 */
@PreviewTest
@Preview(name = "desk-agents-pane-expanded", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskAgentPaneExpandedPreview() {
    PaneBackdrop {
        AgentConsoleScreen(
            state = desktopPreviewState(),
            selectedAgentId = null,
            onSelectAgent = {},
            // 原型 `d06-agents` 画的是**填充态**（待处理审批 3 项 + 任务队列 3 项，各带动作）。
            // 传 null 会让审批卡把动作画成不可用 ⇒ 那一支**从未进过任何一张图**
            // （独立验证者 2026-10-12 的 §11.3 就是这个形状）。壳里无条件传
            // （`DesktopShell` 的 `DesktopView.Agents` 分支），所以不传的帧画的不是产品。
            onRespondApproval = { _, _ -> },
            onCancelTask = {},
            onOpenChat = { _, _ -> },
            // 桌面壳（DesktopShell 的 Agents 分支）**不接**手机那套子页导航出口 ⇒ 显式 null；
            // onFindNodes/onRefresh 桌面壳是接的，必须武装，否则那一支不进图。
            onFindNodes = {},
            onRefresh = {},
            onOpenSessionDetail = null,
            onBackToBoard = null,
            modifier = Modifier.fillMaxSize(),
            now = PREVIEW_NOW,
            // 按生产调用点（App.kt 的 AgentConsoleScreen 调用）逐参武装：这五个可空出口在生产侧都是
            // 非 null 闭包，夹具不传就等于依赖它的那一支根本不渲染 —— 截图证据里也就没有它。
            onOpenWorkspaceDetail = null,
            onLeaveWorkspaceDetail = null,
            onLeaveSessionDetail = null,
            onLoadWorkspaceSessions = { _, _ -> },
            onCreateWorkspaceGroup = {},
        )
    }
}

/**
 * 文件柜·同事的（`d-cabinet`）——直调 [CabinetPane]，选中一个同事的文件柜。
 *
 * 原型 `d23-cabinet.png` 是**同事的**文件柜（面包屑 + 名称/大小/修改时间表格 +
 * 复选框 + 底栏「已选 2 项 · 4.4 MB / 另存为… / 下载 N 项」）。台账那一行
 * 此前配的 `DeskCabinetMineExpandedPreview` 画的是「我的文件柜」，是**别的屏**。
 */
@PreviewTest
@Preview(name = "desk-cabinet-peer-pane-expanded", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskCabinetPanePeerPreview() {
    PaneBackdrop {
        CabinetPane(
            state = desktopPreviewCabinetPeerState(),
            controller = DesktopPreviewActions,
            now = PREVIEW_NOW,
            sizeClass = DesktopWindowSizeClass.Expanded,
        )
    }
}

// ────────────────────────────────── 平板宿主（d-home-more）

/**
 * 平板宿主 + 工具栏（`d-home-more`）——本文件里**唯一**直接调 [DesktopShell] 的帧。
 *
 * 为什么不能直调 Pane：这一屏问的不是某块面板，而是**壳本身换了一套 chrome**。
 * 原型 `screenDHomeMore`（`prototype/relay.html:4889` 起）是平板宿主：**没有菜单栏、
 * 没有标题栏、没有状态栏**，窗口级动作（设置 / 切主题 / Agent 控制台 / 复制本机 ID /
 * 诊断包 / 刷新节点 / 打开下载目录 / 退出）全部收进工具栏那颗 **⋮** 里 ——
 * 原型自带的设计注逐字「桌面宿主上它根本不画（`overflowItems = emptyList()`）」。
 *
 * 为什么是「直调 `DesktopShell`」而不是给既有 `private fun Desk(...)` 加形参：
 * `chromeMode: DesktopChromeMode = DesktopChromeMode.Desktop` 本来就是
 * [DesktopShell] 的**公开形参**（`DesktopShell.kt:141`），只是 `Desk(...)` 没往下透。
 * 而 `Desk(...)` 在冻结文件 `ScreenshotPreviewsDesktop.kt` 里（「private helper 若要
 * 放宽可见性 / 加形参，报 Lead 统一改，不要自己动」）⇒ 与其为一张帧去动五个工作组
 * 共享的夹具，不如在自己的帧里直接调公开 API。**一行生产代码都没改**。
 *
 * 判据（照 `Desk(...)` 的注释抄，口径一致）：`initialView` 用壳的冷启动初值而不是
 * `external*Request` 计数器（后者走 `LaunchedEffect`，静态帧在副作用之前就抓完了）；
 * `now = PREVIEW_NOW` 否则相对时间每天都在变；`controlPlanePort = 0`；
 * `actions` 至少要给 `onOpenDownloadsFolder`（桌面/平板宿主真的能开文件管理器）。
 *
 * **这一帧录不到的那一半（登记，不靠帧掩盖）**：⋮ 展开后的那颗菜单本身 ——
 * 菜单开合是渲染期内部状态，`DesktopShell` 没有对应的冷启动形参，静态帧点不了它。
 * 所以本帧钉的是「平板宿主长什么样 + ⋮ 在」，「⋮ 里有哪些项、什么顺序」只能读
 * `DesktopShell.kt:397-417` 的 `overflowItems` 构造（配对报告 §1 逐条登记）。
 *
 * **必须套 [PaneBackdrop]**（首版漏了，已踩到并修）：`DesktopShell` 假设自己被
 * `AppTheme` 包着 —— 既有 `private fun Desk(...)` 的第一行就是 `AppTheme(darkTheme)`。
 * 不套的话整壳落到 M3 **基线紫**上（头像、图标徽标、按钮全变成默认紫），而**所有量具
 * 与配对判定全是绿的**，只有开图能看见。Lead 专门点过这一类（W5 曾栽在同一处）。
 */
@PreviewTest
@Preview(name = "desk-home-more-tablet-host", widthDp = 1100, heightDp = 800, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskHomeMoreTabletHostPreview() {
    PaneBackdrop {
        DesktopShell(
            state = desktopPreviewState(),
            controller = DesktopPreviewActions,
            windowWidthDp = 1100,
            windowHeightDp = 800,
            darkTheme = false,
            controlPlanePort = 0,
            chromeMode = DesktopChromeMode.Tablet,
            initialView = DesktopView.Conversations,
            initialPeerId = null,
            now = PREVIEW_NOW,
            actions = DesktopShellActions(onOpenDownloadsFolder = {}),
        )
    }
}

/**
 * 文件柜·同事的·**已选中两项**（`d-cabinet` 的另一半）。
 *
 * 原型 `d23-cabinet.png` 里前两行是勾上且整行高亮的（需求文档 / contract-v2.pdf），
 * 底栏因此是「已选 2 项 · 4.4 MB / 另存为… / 下载 2 项」。而
 * `desktopPreviewCabinetPeerState()` 的 `selectedNames` 是空的 ⇒ 参考图此前只拍到
 * 「单击选中，双击进入文件夹」那一支：**底栏的选中支、行首方框的 `.on` 态、整行
 * `primaryContainer` 高亮，从来没有被任何一帧覆盖过**。
 *
 * 夹具就地 `copy` 改选中集合（不动 `PreviewState.kt` 里的公共夹具），这样上面那一帧
 * 与这一帧各自钉住底栏的一支。
 */
@PreviewTest
@Preview(name = "desk-cabinet-peer-selected-pane-expanded", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskCabinetPanePeerSelectedPreview() {
    val base = desktopPreviewCabinetPeerState()
    PaneBackdrop {
        CabinetPane(
            state = base.copy(
                cabinet = base.cabinet.copy(
                    selectedNames = setOf("排期表.ods", "会议纪要.md"),
                ),
            ),
            controller = DesktopPreviewActions,
            now = PREVIEW_NOW,
            sizeClass = DesktopWindowSizeClass.Expanded,
        )
    }
}
