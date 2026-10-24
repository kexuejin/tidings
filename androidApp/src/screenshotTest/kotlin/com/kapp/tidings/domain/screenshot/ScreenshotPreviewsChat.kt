// SPDX-License-Identifier: GPL-3.0-only
//
// W1（会话 / 聊天组）的参考帧。任务：docs/plans/2026-10-12-pairing-W1-chat.md
//
// 为什么另起一个文件而不是往 `ScreenshotPreviews.kt` / `…Extra.kt` / `…Dialogs.kt`
// 里加：那三个文件是本轮**冻结面**（见 `docs/plans/2026-10-12-team-briefing.md`
// 的写域表），五个人同时改会互相踩。本文件由 W1 独占。
//
// 两条纪律，写在文件头上免得后来人重犯：
// 1. **一屏一帧，帧里画的必须是那一屏的落点**。配对量具
//    （`tools/design-preview-pairing.py`）判定的是"这一帧渲染的函数里，
//    有没有落在台账那一格写的落点文件里"。把 `ChatScreen` 的帧填到 `m-groupinfo`
//    那一格，就会得到一条"跨域"和一条永远判不红的假 ✅ —— 这正是台账里
//    `m-home-recent` / `m-screencard` / `m-groupinfo` 三格现在的问题。
// 2. **假数据只从 `com.kapp.tidings.ui.desktop.PreviewState` 取**（它是 `public` 的，
//    所以这个文件能用）。`ScreenshotPreviews*.kt` 里的夹具全是 `private`，
//    跨文件不可见 —— 这也是一条**刻意**的隔离：新帧不该悄悄依赖老帧的夹具，
//    否则老帧一改，新帧的参考图就跟着变而没人知道为什么。
package com.kapp.tidings.domain.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.kapp.tidings.domain.model.DeliveryState
import com.kapp.tidings.domain.model.FileCardUi
import com.kapp.tidings.domain.GlobalSearchChatHit
import com.kapp.tidings.domain.GlobalSearchContactHit
import com.kapp.tidings.domain.GlobalSearchFileHit
import com.kapp.tidings.domain.GlobalSearchResult
import com.kapp.tidings.domain.HistorySearchFilter
import com.kapp.tidings.domain.MessageSearchResult
import com.kapp.tidings.domain.model.MessageUi
import com.kapp.tidings.domain.model.PeerUi
import com.kapp.tidings.domain.RelayUiState
import com.kapp.tidings.domain.model.SCREEN_RECORD_VERSION
import com.kapp.tidings.domain.model.ScreenRecordUi
import com.kapp.tidings.domain.model.screenHistoryMessageId
import com.kapp.tidings.domain.model.TransferState
import com.kapp.tidings.core.protocol.ConvId
import com.kapp.tidings.core.protocol.ScreenPhase
import com.kapp.tidings.core.protocol.ScreenRole
import com.kapp.tidings.feature.chat.ChatScreen
import com.kapp.tidings.feature.chat.ChatScreenChrome
import com.kapp.tidings.feature.chat.ChatScreenState
import com.kapp.tidings.feature.chat.HistorySearchOverlay
import com.kapp.tidings.feature.conversations.ConversationsScreen
import com.kapp.tidings.feature.search.GlobalSearchScreen
import com.kapp.tidings.ui.components.AdaptiveDiagDialog
import com.kapp.tidings.ui.components.GroupCreateDialog
import com.kapp.tidings.ui.components.GroupInfoDialog
import com.kapp.tidings.ui.desktop.DesktopComposer
import com.kapp.tidings.ui.desktop.DesktopHistorySearchDialog
import com.kapp.tidings.ui.desktop.DesktopPreviewActions
import com.kapp.tidings.ui.desktop.EmojiPanel
import com.kapp.tidings.ui.desktop.PREVIEW_NOW
import com.kapp.tidings.ui.desktop.agentPeer
import com.kapp.tidings.ui.desktop.desktopPreviewGroupState
import com.kapp.tidings.ui.desktop.desktopPreviewInboxWorkspaceState
import com.kapp.tidings.ui.desktop.desktopPreviewState
import com.kapp.tidings.ui.desktop.linPeer
import com.kapp.tidings.ui.desktop.previewMessage
import com.kapp.tidings.ui.desktop.zhouPeer

// ── 外壳 ──────────────────────────────────────────────────────────────────
//
// 两个壳，对应 `prototype/relay.html` 的 SCREENS 里那两档：
// - `ChatScreen`（带底导）：`m-home-*` / `m-gsearch*` / `m-groupcreate` /
//   `m-sendfolder` / `m-folderin` / `m-screencard` / `m-insets` 这些**没有** `full:true`，
//   原型照样画底导。
// - `BareScreen`（不画底导）：`m-chat` / `m-chat-solo` / `m-chat-group` /
//   `m-msgmenu` / `m-groupinfo` / `m-histsearch` / `m-histdate` 在 SCREENS 里标了
//   `full:true`，原型注释写明"聊天详情在紧凑布局下是整屏，底下不画导航栏"。
//   `ScreenshotPreviews.kt` 的 `ChatPhonePreview` KDoc 自己承认过这一点没做对，
//   这里按原型来。


// ── 假数据 ────────────────────────────────────────────────────────────────
//
// 一律由 `desktopPreviewState()` 派生。三个对端的性格是现成的：
// 林岚在线、老周离线、agent-node 是 Agent（`caps` 带 `CAP_AGENT`）。


/** 群里那个占位对端。群会话键是 `group:<groupId>`，`peer()` 查不到人。 */
private val groupConvId: String = ConvId.group("g-20261007-desk")

/**
 * 手机壳传给 `ChatScreen(
    data = ChatScreenState(
        cacheDir = …,
    )
)` 的非空缓存目录。
 *
 * 它不是一个可有可无的装饰：`onSendVoice` 那一颗语音图标的闸门是
 * `onSendVoice?.takeIf { !peer.isAgent && cacheDir != null }` —— **两个都要非空**
 * 才画。手机生产三个都传（`App.kt:272-274`），所以手机帧也得传，
 * 否则"依赖它的那一支根本不渲染、图里也就没有它"（量具把这叫夹具欠武装）。
 * 桌面生产只传 `onSendPk`，桌面帧**不许**补这一个。
 */
private val previewCacheDir: String = "/tmp/relay-preview-cache"

/** 发出去的文件夹：原型 `m-sendfolder` 的「需求文档」，62% 处。 */
private val outgoingFolder: FileCardUi = FileCardUi(
    transferId = "t-folder-out",
    name = "需求文档",
    size = 12_400_000L,
    fileCount = 18,
    done = 7_688_000L,
    mine = true,
    isFolder = true,
    state = TransferState.Active,
)

/** 收到的文件夹：原型 `m-folderin` 的「图纸 等 23 个文件」，已完成。 */
private val incomingFolder: FileCardUi = FileCardUi(
    transferId = "t-folder-in",
    name = "图纸",
    size = 31_800_000L,
    fileCount = 23,
    done = 31_800_000L,
    mine = false,
    isFolder = true,
    state = TransferState.Completed,
    savedPaths = listOf("/data/user/0/com.kapp.tidings/files/relay/downloads/图纸"),
)


/** 发文件夹那一屏的状态：一条进行中的出向文件夹消息。 */
private val sendFolderState: RelayUiState = stateWith(
    zhouId,
    listOf(
        previewMessage(zhouId, mine = true, text = "", at = PREVIEW_NOW - 240_000L, delivery = DeliveryState.Sent)
            .copy(file = outgoingFolder),
    ),
)

/** 收文件夹那一屏的状态：林岚发来的已完成文件夹 + 她随后那句话。 */
private val folderInState: RelayUiState = stateWith(
    linId,
    listOf(
        previewMessage(linId, text = "", at = PREVIEW_NOW - 300_000L)
            .copy(file = incomingFolder, senderId = linId),
        previewMessage(linId, text = "图纸那批一并给你了，尺寸图和装配图都在里面。", at = PREVIEW_NOW - 260_000L)
            .copy(senderId = linId),
    ),
)

/** 历史搜索的四条命中：逐条照原型 `m11d-histsearch` 的 `HITS` 抄。 */
private val histHits: List<MessageSearchResult> = listOf(
    MessageSearchResult(
        id = "h-1", convId = zhouId, peerId = zhouId, senderId = zhouId, mine = false,
        timestamp = PREVIEW_NOW - 90_000_000L, snippetText = "上版协议里 ack 要不要加重试？",
    ),
    MessageSearchResult(
        id = "h-2", convId = zhouId, peerId = zhouId, senderId = zhouId, mine = false,
        timestamp = PREVIEW_NOW - 190_000_000L, snippetText = "协议 v2 我把 timeout 调成 3 秒了",
    ),
    MessageSearchResult(
        id = "h-3", convId = linId, peerId = linId, senderId = linId, mine = false,
        timestamp = PREVIEW_NOW - 210_000_000L, snippetText = "",
        fileName = "需求文档-协议-v2.pdf",
    ),
    MessageSearchResult(
        id = "h-4", convId = linId, peerId = linId, senderId = linId, mine = false,
        timestamp = PREVIEW_NOW - 220_000_000L, snippetText = "协议 v3 定稿，图我贴 wiki 了",
    ),
)

/** 历史搜索的日期范围：原型画的是「10月8日 至 10月10日」。 */
private val histFrom: Long = PREVIEW_NOW - 2 * 24 * 3_600_000L
private val histTo: Long = PREVIEW_NOW

private fun emptySearch(query: String, filter: HistorySearchFilter): List<MessageSearchResult> = emptyList()

// ── 会话列表（m-home-recent / m-home-unread / m-home-more）──────────────────

/**
 * `m-home-recent` —— 消息首页，最近优先。
 *
 * 原型 `m01-home.png`：副标「5 个会话 · 3 条未读，按最近一条消息排」，
 * 列表上方**只有一排 chip**（按种类 / 未读筛），**没有**那条"搜索会话"文本输入框 ——
 * 原型注释写明它已删（按内容找会话走顶栏放大镜的全局搜索，两条轴只留一条）。
 */
@PreviewTest
@Preview(name = "chat-home-recent", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ChatHomeRecentPhonePreview() {
    ChatShell("conversations", chatBase) { inner ->
        ConversationsScreen(
            state = chatBase,
            onOpenChat = {},
            onToggleMute = {},
            onDeleteConversation = {},
            onGoToPairing = {},
            onDismissBanner = {},
            onCreateGroup = { _, _ -> },
            onSearchGlobal = {},
            modifier = inner,
            now = PREVIEW_NOW,
        )
    }
}

/**
 * `m-home-recent` 的**下半屏**：会话区之后那一段「工作区」。
 *
 * 为什么不改上面那帧而要另起一帧：`ConversationsScreen` 的「工作区」段只在宿主给了
 * 两个入口 lambda 时才画（`onOpenWorkspaceUnit` / `onOpenWorkspaceBoard` 任一为 null
 * 就整段不画 —— 别的屏不该平白长出一段）。上面那帧传的是 null，拍到的就是"没有这一段"
 * 的样子；这一帧把两个入口传齐，并换成 [desktopPreviewInboxWorkspaceState]
 * （两个 agent 类型 + 一条待审批 + 一台没上报类型的旧版节点），才能把组头、
 * 行内「N 项待批」药丸、降级到「未上报类型」那一档、尾巴行一起拍进同一张图。
 */
@PreviewTest
@Preview(name = "chat-home-recent-workspaces", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ChatHomeRecentWorkspacesPhonePreview() {
    val state = desktopPreviewInboxWorkspaceState()
    ChatShell("conversations", state) { inner ->
        ConversationsScreen(
            state = state,
            onOpenChat = {},
            onToggleMute = {},
            onDeleteConversation = {},
            onGoToPairing = {},
            onDismissBanner = {},
            onCreateGroup = { _, _ -> },
            onSearchGlobal = {},
            onOpenWorkspaceUnit = {},
            onOpenWorkspaceBoard = {},
            modifier = inner,
            now = PREVIEW_NOW,
        )
    }
}

/**
 * `m-home-unread` —— 只看有未读的。
 *
 * 原型 `m01c-home-unread.png`：副标换成「2 个会话有未读 · 共 3 条」，
 * 只留两行，而且**banner 整条不画** —— 按未读过滤之后"还有 N 条在等对方上线"
 * 与眼前这两行无关，留着只是占位。
 */
@PreviewTest
@Preview(name = "chat-home-unread", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ChatHomeUnreadPhonePreview() {
    ChatShell("conversations", chatBase) { inner ->
        ConversationsScreen(
            state = chatBase,
            onOpenChat = {},
            onToggleMute = {},
            onDeleteConversation = {},
            onGoToPairing = {},
            onDismissBanner = {},
            onCreateGroup = { _, _ -> },
            onSearchGlobal = {},
            initialFilter = "unread",
            modifier = inner,
            now = PREVIEW_NOW,
        )
    }
}

/**
 * 状态槽：会话页「选中的筛选一条都没匹配上」。
 *
 * 这是 `filtered.isEmpty()` 那一支 —— 与「还没有会话」（`conversations.isEmpty()`）
 * **是两条不同的空态**，文案也必须不同：前者是"筛掉了"，后者是"一个都还没有"。
 *
 * 为什么这一帧是本轮新加的：列表上方那条"搜索会话"文本框删掉之后，原本唯一
 * 钉住这条分支的帧（`ConversationsNoMatchPhonePreview`）也一并删了；而这条分支
 * 本身没删 —— 筛选 chip 还在，点「群聊」而一个群都没有就会落到这里。
 * 所以空态文案从"没有匹配「x」的会话"改成"这个筛选下没有会话 / 看全部"，
 * 并且**必须有一张图钉住它**，否则这次改动在门禁里是隐形的。
 *
 * 造假数据：`chatBase` 有三个会话（Agent 1 / 同事 2 / 群聊 0），
 * `initialFilter = "group"` 于是落空 —— 不需要造任何新夹具。
 */
@PreviewTest
@Preview(name = "chat-filter-empty", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ChatFilterEmptyPhonePreview() {
    ChatShell("conversations", chatBase) { inner ->
        ConversationsScreen(
            state = chatBase,
            onOpenChat = {},
            onToggleMute = {},
            onDeleteConversation = {},
            onGoToPairing = {},
            onDismissBanner = {},
            onCreateGroup = { _, _ -> },
            onSearchGlobal = {},
            initialFilter = "group",
            modifier = inner,
            now = PREVIEW_NOW,
        )
    }
}

// ── 聊天详情（m-chat / m-chat-group）──────────────────────────────────────

/**
 * `m-chat` —— 单聊，输入区**三段**齐全。
 *
 * 原型 `m03-chat.png` 的输入区是三段，不是一段：
 * 1. 一排图标工具条（表情 · PK · 窗口震动 · 截图 · 图片 · 文件 · 文件夹），
 * 2. 输入框 + 右侧圆形发送键，
 * 3. 计数行「0 / 800 字节」+「局域网直连 · 走 UDP 单播」。
 *
 * 本帧拍到的是 1 与 3 里**本端真有实现**的那几项：表情 · PK · 窗口震动 · 图片 · 文件 · 语音，
 * 以及工具条右端的历史搜索。**截图 / 文件夹两颗画不出来** —— 不是忘了：
 * `FilePicker` 只有 `pick()`（`PlatformContracts.kt:139-141`），没有截图/相机入口；
 * 目录选择器 `pickDirectory()` 属 `SaveDirectoryPicker`（`:143-145`）且只用于**保存目录**，
 * 发送侧"选一个目录发过去"整条链路不存在（`isFolder = true` 生产零调用）。
 * "按下去没反应的图标比没有更糟"是本仓的既有规矩（见 `MessageComposer` 的
 * `ComposerCapability` KDoc）。**图片那颗能通所以画了**：`pick()` 拉的是「任意类型」
 * 通配（`AndroidFilePicker.kt:98`），图片本来就是文件，复用同一条发送链路。
 * 三者都不加平台 API —— 那是新功能。
 *
 * ⚠ 本帧的对端是**人**（林岚），而原型 `m03-chat` 是 **Agent 私聊**
 * （`PEERS[0]` = `teahouse-mobile`，顶栏带 `Agent` 徽标）。Agent 档另有一帧
 * [ChatAgentToolsPhonePreview]：那一档工具条**只剩三颗**（表情 · 图片 · 文件），
 * 因为本端把 PK / 窗口震动 / 语音都按 `!peer.isAgent` 关掉了 —— 与原型不同，
 * 是产品判断。原型那条 `swbar`（会话切换条）在本端**没有落点**，见报告。
 */
@PreviewTest
@Preview(name = "chat-tools", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ChatToolsPhonePreview() {
    BareScreen { inner ->
        ChatScreen(
            data = ChatScreenState(
                state = chatBase,
                peerId = linId,
                cacheDir = previewCacheDir,
            ),
            actions = chatPreviewActions(
                onAttachFile = {},
                onNudge = {},
                onSendPk = { _, _, _ -> },
                onSearchInConversation = { _, _ -> emptyList() },
                onSendVoice = { _, _, _ -> },
            ),
            chrome = ChatScreenChrome(
                modifier = inner,
            )
        )
    }
}

/**
 * `m-chat-group` —— 群聊，工具条少一颗、多一句提示。
 *
 * 原型 `m04-chat-group.png` 与单聊的差只有两处（注释写明是**刻意**的）：
 * - **整项不画「窗口震动」**（不是画出来再置灰）：震动要落到一个具体窗口上，
 *   群里没有单一收件人，这件事在群里**不成立**；
 * - 腾出的位置给一句 `仅在线群成员可接收图片/文件`。
 *
 * 对比图片 / 文件那三颗的处理方式（原型的说法是"有这事，现在送不出去"→ 置灰留位），
 * 两种缺失的表达方式必须分开 —— 这是原型注释里唯一一处专门讲"置灰 vs 不画"的段落。
 */
@PreviewTest
@Preview(name = "chat-group-tools", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ChatGroupToolsPhonePreview() {
    val groupState = desktopPreviewGroupState()
    BareScreen { inner ->
        ChatScreen(
            data = ChatScreenState(
                state = groupState,
                peerId = groupConvId,
                cacheDir = previewCacheDir,
            ),
            actions = chatPreviewActions(
                onAttachFile = {},
                onSendPk = { _, _, _ -> },
                onSearchInConversation = { _, _ -> emptyList() },
                onSendVoice = { _, _, _ -> },
            ),
            chrome = ChatScreenChrome(
                modifier = inner,
            )
        )
    }
}

/**
 * `m-chat` / `m-chat-solo` 的 **Agent 私聊档** —— 原型这两屏的对端是 Agent
 * （`teahouse-mobile`，顶栏带 `Agent` 徽标 + `在线`），不是人。
 *
 * 它和 [ChatToolsPhonePreview] 的差别**不在夹具而在产品判断**：本端把 PK、窗口震动、
 * 语音三颗都按 `!peer.isAgent` 关掉（`ChatScreen.kt` 的三处 `takeIf`），所以 Agent 档
 * 工具条只剩 表情 · 图片 · 文件 + 右端历史搜索。原型 `m03-chat` 画的是**满排 7 颗**，
 * 因为它把 PK/戳一碰也当成对 Agent 可用的动作 —— 这条差异是产品选择，不是适配漏项，
 * 所以照本端真实行为出图，并在此写明。
 *
 * ⚠ 原型这两屏**标志性的那条 `swbar`（「会话 refactor runner ｜ 2 项 ｜ 进行中 ▾」）
 * 本帧里没有**，也不是漏画：本端的会话内消息流按**对端**存（`RelayUiState.kt:672`
 * `messages: Map<String, List<MessageUi>>`、`:752 messagesWith(peerId) = messages[peerId]`），
 * `sessionId` 在整条消息管线里一次都不出现（`grep -rn "sessionId" RelayControllerMessages.kt`
 * 零命中），`nav.activeAgentSessionId` 只被 Agent 控制台消费（`App.kt` 的手机
 * `AgentConsoleScreen` 落点；桌面那条走壳的 `DesktopView.Agents` 分支）——
 * 也就是说：在这个聊天面板里切会话，面板上不会有任何变化。
 * 画一条会切、切了没反应的会话条，正是本仓反复抓的"死控件"。已按 lead 的规矩
 * **停下上报**（见报告 §13），不自己造数据模型。
 */
@PreviewTest
@Preview(name = "chat-agent-tools", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ChatAgentToolsPhonePreview() {
    val peer = agentPeer()
    BareScreen { inner ->
        ChatScreen(
            data = ChatScreenState(
                state = chatBase,
                peerId = peer.nodeId,
                cacheDir = previewCacheDir,
            ),
            actions = chatPreviewActions(
                onAttachFile = {},
                onNudge = {},
                onSendPk = { _, _, _ -> },
                onSearchInConversation = { _, _ -> emptyList() },
                onSendVoice = { _, _, _ -> },
            ),
            chrome = ChatScreenChrome(
                modifier = inner,
            )
        )
    }
}

// ── 文件夹（m-sendfolder / m-folderin）────────────────────────────────────

/**
 * `m-sendfolder` —— 我发出去的文件夹。
 *
 * 原型 `m11d-sendfolder.png`：绿气泡里一张白卡（文件夹图标 + 名字 + `62% · 12.4 MB /
 * 12.4 MB · 1.2 MB/s` + 进度条 + 「发送中」），下面一条 notice「对方已接收 · 正在拉取 18 项」；
 * **计数行右端换成「一次最多 2,000 个文件」** —— 那一屏真正要提醒的上限不是路由，
 * 是文件条数。这条替换规则落在 `MessageComposer(hintTrailing = …)`。
 *
 * 原型注释另有一条规矩值得记在这里：**文件夹在协议里就是多文件传输**，
 * 卡片永远只有一行、进度是整包的百分比，不是「第 7/18 个」—— 子文件列表在代码里
 * 根本没有出口，画成可折叠树等于编功能。
 */
@PreviewTest
@Preview(name = "chat-send-folder", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ChatSendFolderPhonePreview() {
    ChatShell("conversations", sendFolderState) { inner ->
        ChatScreen(
            data = ChatScreenState(
                state = sendFolderState,
                peerId = zhouId,
                cacheDir = previewCacheDir,
            ),
            actions = chatPreviewActions(
                onAttachFile = {},
                onCancelTransfer = {},
                onSendPk = { _, _, _ -> },
                onSendVoice = { _, _, _ -> },
            ),
            chrome = ChatScreenChrome(
                modifier = inner,
            )
        )
    }
}

/**
 * `m-folderin` —— 别人发来的文件夹，已完成。
 *
 * 原型 `m11e-folderin.png`：卡上方有**署名**（`Lin`），卡内是
 * 「图纸 等 23 个文件」+「23 项 · 31.8 MB ·」+「已完成」+ 两颗按钮 **打开 / 分享**。
 *
 * 那两颗按钮只在**收到的**文件上出现（`receivedFileActions`）：文件落在应用内部目录，
 * 文件管理器看不见，没有这两颗就等于文件进了黑洞。自己发出去的本来就知道在哪，
 * 所以不给。本帧没传 `receivedFileActions` ⇒ 图上没有那两颗按钮 —— 这是**故意的**，
 * 让"没有出口"这件事在参考图里看得见，而不是让预览假装平台支持。
 */
@PreviewTest
@Preview(name = "chat-folder-in", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ChatFolderInPhonePreview() {
    ChatShell("conversations", folderInState) { inner ->
        ChatScreen(
            data = ChatScreenState(
                state = folderInState,
                peerId = linId,
                cacheDir = previewCacheDir,
            ),
            actions = chatPreviewActions(
                onAttachFile = {},
                onSendPk = { _, _, _ -> },
                onSendVoice = { _, _, _ -> },
            ),
            chrome = ChatScreenChrome(
                modifier = inner,
            )
        )
    }
}

// ── 历史搜索（m-histsearch / m-histdate）──────────────────────────────────

/**
 * `m-histsearch` —— 会话内历史搜索（整屏覆盖层）。
 *
 * 原型 `m11d-histsearch.png` 与代码 `HistorySearchOverlay` 的结构几乎一致：
 * 裸放大镜 + 描边输入框 + 圆形 ×、类型 chips（全部/图片/文件）、日期按钮 + 清除、
 * 「结果 N 条」、命中行、页脚「已列出这 4 条 · LIKE 最多取 50 条」。
 *
 * 本帧钉住的是**日期筛选已生效**那一版：日期按钮写「10月8日 至 10月10日」而不是
 * 「全部日期」。没有 `initialFromAt` / `initialToAt` 两个接缝，这一版永远录不到 ——
 * 而"按钮文案跟着范围变"正是原型那一屏与默认态唯一的差别。
 */
@PreviewTest
@Preview(name = "chat-hist-search", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ChatHistSearchPhonePreview() {
    BareScreen { inner ->
        HistorySearchOverlay(
            onDismiss = {},
            onSearch = { _, _ -> histHits },
            onSelectHit = {},
            modifier = inner,
            initialQuery = "协议",
            initialResults = histHits,
            initialFromAt = histFrom,
            initialToAt = histTo,
        )
    }
}

/**
 * `m-histdate` —— 同一个浮层上再叠一层日期面板。
 *
 * 原型 `m11f2-histdate.png`：scrim + 贴底 sheet（上圆角、铺满宽）= `.grab` 小手柄、
 * 「日期范围」+ 右端 ×、两个字段「开始 10月8日 ›」「结束 10月10日 ›」、
 * 快捷 chip 今天 / 近 7 天 / **近 30 天**（绿选中）/ 全部日期、月历
 * （‹ 10月 ›，周头 一二三四五六日，8 日深绿 / 9 日浅绿 / 10 日深绿）、底部 取消 / 确定。
 *
 * 原型注释写明手机这一支是**贴底对话框**，不是把月历摊在条件区里 ——
 * 与桌面 `d-histsearch`（左侧栏里嵌一份月历）是两种形态。
 */
@PreviewTest
@Preview(name = "chat-hist-date", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ChatHistDatePhonePreview() {
    BareScreen { inner ->
        HistorySearchOverlay(
            onDismiss = {},
            onSearch = { _, _ -> histHits },
            onSelectHit = {},
            modifier = inner,
            initialQuery = "协议",
            initialResults = histHits,
            initialFromAt = histFrom,
            initialToAt = histTo,
            initialDateSheetOpen = true,
        )
    }
}

// ── 建群 / 群信息 / 适配对照 ──────────────────────────────────────────────

/**
 * `m-groupcreate` —— 建群对话框。
 *
 * 原型 `m05-groupcreate.png` 是**四边留白的居中浮层卡**（左右各约 48px、圆角 ~20px），
 * 不是贴底 sheet；里面有标签「群名」、填好的群名、标签「选成员（自己一定在里面）」、
 * 六行成员（勾选框 + 圆角方头像 + 昵称 + 副行在线/离线 + 右端 Agent 徽标）、
 * 底部 取消 / **创建（可用）**。
 *
 * 本帧特意把群名与三个勾选成员都摆好：空态那一版（名字空着、一个没勾、创建灰着）
 * 看不出"在线排前面""自己一定在里面""Agent 带徽标"这三条规矩。
 */
@PreviewTest
@Preview(name = "chat-group-create", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ChatGroupCreatePhonePreview() {
    val peers: List<PeerUi> = listOf(linPeer(), zhouPeer(), agentPeer())
    BareScreen { inner ->
        ChatShell("conversations", chatBase) { shell ->
            androidx.compose.foundation.layout.Box(modifier = shell) {
                ConversationsScreen(
                    state = chatBase,
                    onOpenChat = {},
                    onToggleMute = {},
                    onDeleteConversation = {},
                    onGoToPairing = {},
                    onDismissBanner = {},
                    onCreateGroup = { _, _ -> },
                    onSearchGlobal = {},
                    modifier = inner,
                    now = PREVIEW_NOW,
                )
            }
            GroupCreateDialog(
                peers = peers,
                onDismiss = {},
                onConfirm = { _, _ -> },
                initialName = "茶话间 · 发布值班",
                initialChosen = listOf(zhouPeer().nodeId, linPeer().nodeId, agentPeer().nodeId),
            )
        }
    }
}

/**
 * `m-groupinfo` —— 群信息。
 *
 * 原型 `m07-groupinfo.png` 是**整屏**（`full:true`，顶栏 返回 +「群信息」+
 * 副「只读：改名与成员变更由群主发 group{"info"}」），三张卡：群资料（含
 * 群简介 / 群公告 / groupId 三条 kv 行）、成员（每行 mono nodeId + · 在线/离线）、
 * 「退出群聊」→「本端没有这条出口」。
 *
 * 代码这一屏是 `GroupInfoDialog`（居中对话框）。本帧如实拍对话框的现状，
 * **不假装它是整屏**：形态差异（dialog vs 整屏）、以及代码里多出来的三个管理出口
 * （编辑群公告与简介 / 邀请新成员 / 移出）都写进报告，等裁定。
 */
@PreviewTest
@Preview(name = "chat-group-info", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ChatGroupInfoPhonePreview() {
    val groupState = desktopPreviewGroupState()
    val group = groupState.settings.groups.first()
    BareScreen { inner ->
        ChatShell("conversations", groupState) { shell ->
            androidx.compose.foundation.layout.Box(modifier = shell) {
                ChatScreen(
                    data = ChatScreenState(
                        state = groupState,
                        peerId = groupConvId,
                        cacheDir = previewCacheDir,
                    ),
                    actions = chatPreviewActions(
                        onSendPk = { _, _, _ -> },
                        onSendVoice = { _, _, _ -> },
                    ),
                    chrome = ChatScreenChrome(
                        modifier = inner,
                    )
                )
            }
            // 四个回调全补：两个生产调用点都无条件传（`ChatScreen.kt:623-626`、
            // `DesktopConversationsPane.kt:393-396`）。不补的后果不是"少画一颗按钮"，
            // 而是群信息弹窗的成员管理出口**从未进过任何一张图**。
            GroupInfoDialog(
                group = group,
                selfNodeId = groupState.settings.nodeId,
                peers = groupState.peers,
                memberOnlineCount = 2,
                onDismiss = {},
                onAddMembers = {},
                onRemoveMember = {},
                onUpdateMeta = { _, _, _ -> },
                onLeaveGroup = {},
            )
        }
    }
}

/**
 * `m-insets` —— 适配对照与诊断。
 *
 * 原型 `m14-insets.png` 是**四张卡的整页**（目标 banner / 旧版真机症状 / 新版现状 /
 * 真机验收），真落点是 `ui/components/AdaptiveDiagDialog.kt`，但那里只有 69 行、
 * 一颗标题 + 一行 kv + 一个关闭按钮 ⇒ **真缺口**，本帧如实拍现状。
 *
 * 另外：台账把这一屏的落点写成 `ConversationsScreen.kt`，于是
 * `Window620PhoneShellPreview`（它渲染的是 `ConversationsScreen`）被判成"本屏" ✅ ——
 * 那是一格**假绿**：它根本不是适配对照页。
 */
@PreviewTest
@Preview(name = "chat-insets", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ChatInsetsPhonePreview() {
    BareScreen { inner ->
        ChatShell("conversations", chatBase) { shell ->
            androidx.compose.foundation.layout.Box(modifier = shell) {
                ConversationsScreen(
                    state = chatBase,
                    onOpenChat = {},
                    onToggleMute = {},
                    onDeleteConversation = {},
                    onGoToPairing = {},
                    onDismissBanner = {},
                    modifier = inner,
                    now = PREVIEW_NOW,
                )
            }
            AdaptiveDiagDialog(onDismiss = {})
        }
    }
}

// ── 桌面（d-chat / d-histsearch / d-groupcreate）──────────────────────────
//
// 三帧都只画**右栏聊天面板**（`ChatScreen` + `composerSlot = DesktopComposer`），
// 不画左边那条导航竖导与会话列表：那是 W4 的 `DesktopShell` / `DesktopConversationsPane`，
// 已经有一组帧（`ScreenshotPreviewsDesktop.kt`）。这里补的是"桌面聊天那一栏自己长什么样"，
// 所以刻意不带外壳 —— 台账这三格的落点本来就是 `ChatScreen`。

/** `d-chat` —— 桌面右栏的聊天面板：消息流 + 浮起的输入卡片（工具条 / 草稿计数 / 快捷键提示）。 */
@PreviewTest
@Preview(name = "chat-desk-pane", widthDp = 900, heightDp = 700, showBackground = true, locale = "zh-rCN")
@Composable
private fun ChatDeskPanePreview() {
    val peer = linPeer()
    BareScreen { inner ->
        ChatScreen(
            data = ChatScreenState(
                state = chatBase,
                peerId = linId,
            ),
            actions = chatPreviewActions(
                onSendPk = { _, _, _ -> },
            ),
            chrome = ChatScreenChrome(
                showHeader = false,
                composerSlot = { args ->
                DesktopComposer(
                peer = peer,
                draft = args.draft,
                onDraftChange = args.onDraftChange,
                mentionTargets = args.mentionTargets,
                controller = DesktopPreviewActions,
            )
                },
                modifier = inner,
            )
        )
    }
}

/** `d-histsearch` —— 桌面版历史搜索：**覆盖层对话框**（保留侧栏），不是普通页面。 */
@PreviewTest
@Preview(name = "chat-desk-hist-search", widthDp = 900, heightDp = 700, showBackground = true, locale = "zh-rCN")
@Composable
private fun ChatDeskHistSearchPreview() {
    val peer = linPeer()
    BareScreen { inner ->
        ChatScreen(
            data = ChatScreenState(
                state = chatBase,
                peerId = linId,
            ),
            actions = chatPreviewActions(
                onSendPk = { _, _, _ -> },
            ),
            chrome = ChatScreenChrome(
                showHeader = false,
                composerSlot = { args ->
                DesktopComposer(peer, args.draft, args.onDraftChange, DesktopPreviewActions)
                },
                modifier = inner,
            )
        )
        DesktopHistorySearchDialog(
            peer = peer,
            onDismiss = {},
            onSearch = { _, _ -> histHits },
            onSelectHit = {},
            initialQuery = "协议",
        )
    }
}

/** `d-groupcreate` —— 桌面版建群：同一张居中浮层卡，背后是聊天面板。 */
@PreviewTest
@Preview(name = "chat-desk-group-create", widthDp = 900, heightDp = 700, showBackground = true, locale = "zh-rCN")
@Composable
private fun ChatDeskGroupCreatePreview() {
    val peers: List<PeerUi> = listOf(linPeer(), zhouPeer(), agentPeer())
    BareScreen { inner ->
        ChatScreen(
            data = ChatScreenState(
                state = chatBase,
                peerId = linId,
            ),
            actions = chatPreviewActions(
                onSendPk = { _, _, _ -> },
            ),
            chrome = ChatScreenChrome(
                showHeader = false,
                composerSlot = { args ->
                DesktopComposer(linPeer(), args.draft, args.onDraftChange, DesktopPreviewActions)
                },
                modifier = inner,
            )
        )
        GroupCreateDialog(
            peers = peers,
            onDismiss = {},
            onConfirm = { _, _ -> },
            initialName = "茶话间 · 发布值班",
            initialChosen = listOf(zhouPeer().nodeId, linPeer().nodeId, agentPeer().nodeId),
        )
    }
}

/**
 * `d-emoji` —— 桌面版表情托盘的**内容本体**（`EmojiPanel`）。
 *
 * 这一屏此前一张参考图都没有：面板的开合是 `DesktopComposer` 屏内的 `remember`
 * （`emojiOpen`），入口是输入框右侧那颗表情按钮，静态帧点不了。台账那一格填的
 * `ChatEmojiPhonePreview`（`ScreenshotPreviewsExtra.kt`）是一张**手机**帧，
 * 拿它对一屏**桌面**屏 —— 配错了。
 *
 * **为什么渲染的是 `EmojiPanel` 而不是 `EmojiPopup`**：`EmojiPopup` 是
 * `Popup(...)`（`ui/desktop/DesktopComposer.kt`），Layoutlib **只合成 Popup 的容器、
 * 不合成它的内容** —— 直接渲染 `EmojiPopup` 只会得到一张**空白圆角矩形**
 * （W2 的 `NodesRowMenuPhonePreview` 就是这样拍出一张纯白方块的；Lead 的裁定是
 * **"空框当基线比没有更糟"**，所以那张帧已被删掉、不留在基线里）。
 * 于是把内容本体抽成 `public fun EmojiPanel(onPick)`，`EmojiPopup` 改成
 * `Popup(...) { EmojiPanel(onPick) }` —— **生产行为一字不变**，换来的只是这一层能被拍。
 *
 * 这一帧**证明**什么：39 个表情、8 列网格、单格 30dp 高 17sp 字、最后一行 7 个的补位
 * （`repeat(8 - row.size)`，不补的话那 7 个会被拉宽、网格就散了）、底部那行
 * 「表情按 UTF-8 计 · 多数占 4 字节」的文案与单行截断。
 *
 * 这一帧**不证明**什么（**别把它当成 d-emoji 整屏已覆盖**）：面板相对输入卡片的
 * 位置与偏移（`Alignment.BottomStart` + `IntOffset(0, -8)`）、遮罩、点外部收起、
 * 键盘与焦点交互 —— 那四样归 `Popup` 管，参考图照不到，已登记进
 * `design/fidelity-deviations.yaml`。
 *
 * 原型真值 `screenDEmoji`（`prototype/relay.html:5428`）：`chatPane({ …, cmp: { bytes: 12,
 * emoji: true, draft: '好，我这边也同步一下 👍' } })` —— 它拍的是"面板展开在输入区上方"，
 * 也就是本帧**不**证明的那一半。
 */
@PreviewTest
@Preview(name = "chat-desk-emoji-panel", widthDp = 900, heightDp = 700, showBackground = true, locale = "zh-rCN")
@Composable
private fun ChatDeskEmojiPanelPreview() {
    BareScreen { inner ->
        // 刻意**不做任何定位**：位置与偏移正是这一帧不证明的东西，
        // 摆一个好看的位置反而会让人以为它证明了。
        Box(inner) {
            EmojiPanel(onPick = {})
        }
    }
}

// ── 全局搜索（m-gsearch / m-gsearch-empty）────────────────────────────────//
// 这两帧的数据**不是**从 `PreviewState` 取的，而是照原型 `screenGSearch` 的
// 三个夹具数组（`SEARCH_HITS` / `GS_CHATS` / `GS_FILES`，`prototype/relay.html:2925-2945`）
// 逐条抄下来的字面量。理由：`desktopPreviewState()` 里没有一份"搜「协议」会命中这四条"
// 的数据 —— 真实检索走的是控制器内存表，而这三个数组是**原型的展示数据**。
// 造一份与原型不同的命中，这一屏就永远对不上原型那张图。
//
// 三条落点（原型 `searchHit` / `gscChat` / `gscFile`）：
// - 联系人 → 会话（私聊键 = nodeId，走 `ConvId.single`）；
// - 聊天记录 → 那个会话；
// - 文件 → 文件柜（`gscFile` 的注释：文件在协议里属文件柜那一套）。

/** 命中版：老周在线 / Huang 离线，两段会话、两个文件，逐条照原型 `m01e-gsearch.png`。 */
private val gsHits: GlobalSearchResult = GlobalSearchResult(
    query = "协议",
    contacts = listOf(
        GlobalSearchContactHit(
            nodeId = zhouId,
            title = "老周",
            subtitle = "研发 · §后端",
            isAgent = false,
            online = true,
        ),
        GlobalSearchContactHit(
            nodeId = "n-huang0000000004",
            title = "Huang",
            subtitle = "研发 · §客户端",
            isAgent = false,
            online = false,
        ),
    ),
    chats = listOf(
        GlobalSearchChatHit(
            convId = ConvId.single(zhouId),
            title = "老周",
            matchCount = 7,
            latestAt = PREVIEW_NOW - 26 * 3_600_000L,
            snippet = "上次那版协议要不要加个 ack 重试？",
        ),
        GlobalSearchChatHit(
            convId = ConvId.single(linId),
            title = "Lin",
            matchCount = 3,
            latestAt = PREVIEW_NOW - 27 * 3_600_000L,
            snippet = "图纸那批一并给你了，尺寸图和装配图都在里面。",
        ),
    ),
    files = listOf(
        GlobalSearchFileHit(
            messageId = "gs-f1",
            convId = ConvId.single(linId),
            convTitle = "Lin",
            name = "需求文档-协议-v2.pdf",
            at = PREVIEW_NOW - 29 * 3_600_000L,
            isImage = false,
        ),
        GlobalSearchFileHit(
            messageId = "gs-f2",
            convId = "n-huang0000000004",
            convTitle = "Huang",
            name = "insets-复核-10-07.png",
            at = PREVIEW_NOW - 3 * 24 * 3_600_000L,
            isImage = true,
        ),
    ),
)

/**
 * `m-gsearch` —— 全局搜索，三段都有命中。
 *
 * 原型 `m01e-gsearch.png`：顶栏那一行装的是**输入框**（返回 + 带放大镜的框 + 圆形 ×），
 * 不是标题；下面是**一页里的三块**（联系人 / 聊天记录 / 文件），分组标题只有三个词、
 * 没有数量徽标、没有筛选器、命中不高亮；**底导仍在**（这一屏在 SCREENS 里没有 `full:true`）。
 *
 * 「聊天记录」那一段一行是**一个会话**（「与 老周 的聊天 · 7 条相关」），不是一条消息 ——
 * 这一条最容易画错成"消息列表"，所以它在这张图上必须是可核对的。
 */
@PreviewTest
@Preview(name = "chat-global-search", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ChatGlobalSearchPhonePreview() {
    ChatShell("conversations", chatBase) { inner ->
        GlobalSearchScreen(
            onSearch = { _, _ -> gsHits },
            onBack = {},
            onOpenChat = {},
            onOpenFile = {},
            modifier = inner,
            now = PREVIEW_NOW,
            initialQuery = "协议",
        )
    }
}

/**
 * `m-gsearch-empty` —— 三块都空。
 *
 * 原型 `m01f-gsearch-empty.png`：同一根顶栏（值「捎话」）+ 居中两行
 * 「没有找到「捎话」相关的内容」（粗）+「请检查输入是否有误，或换个关键词」。
 *
 * 这张图钉的是**"空关键词"与"搜了但没命中"不是同一件事**：
 * `GlobalSearchResult.isEmpty` 的 KDoc 明写「空关键词不算空结果 —— 那是"还没开始搜"，
 * 界面上是初始态不是无结果态，两者文案不同」。所以本帧必须给一个**非空**的词。
 */
@PreviewTest
@Preview(name = "chat-global-search-empty", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ChatGlobalSearchEmptyPhonePreview() {
    ChatShell("conversations", chatBase) { inner ->
        GlobalSearchScreen(
            onSearch = { _, _ -> GlobalSearchResult(query = "捎话") },
            onBack = {},
            onOpenChat = {},
            onOpenFile = {},
            modifier = inner,
            now = PREVIEW_NOW,
            initialQuery = "捎话",
        )
    }
}

// ── 屏幕协助历史卡（m-screencard）──────────────────────────────────────────

/**
 * `m-screencard` 那条记录：viewer 侧、正在协助中。
 *
 * 时刻取 [PREVIEW_NOW]（同一帧里那两条气泡是 `PREVIEW_NOW - 15min` / `- 10min`，即 08:11 /
 * 08:16，卡片紧随其后 = 08:26）。原型那张图的副行逐字是「14:22 · 仅查看」，但原型自己的
 * 演示数据也不自洽（`daysep` 写 14:02、卡片写 14:22）；**帧要的是同一屏内的时钟自洽**，
 * 所以不照抄 14:22（照抄会让卡片比它上面的气泡晚 5h55m，2026-10-12 独立验证者把这条记成了
 * 「参考帧混用两个时钟」）。时钟口径是 `screenCardClock(at, now)`：同一天只给 `HH:mm`，
 * 跨天才带日期（茶话间 `ScreenHistoryCard.vue` 的 `separatorTime` 就是这个行为）。
 * 所以帧里必须把 `now` 也钉成 [PREVIEW_NOW]，否则同一条记录在不同日子会录出两种像素。
 *
 * `phase = Active` 而不是 `Ended`：原型画的那张卡是 `class="screencard active"` ——
 * 底色 `primaryContainer`、状态词 `primary`。**边框不随 active/Ended 变，随角色变**：
 * viewer（= `mine`）不画边框，peer 侧才画 `--hair`（茶话间 `.screen-card{border:1px solid
 * var(--line)}` 是基础样式、`.mine` 只覆盖底色；原型 `.screencard.active{border-color:
 * transparent}` 之所以把边框抹掉，是因为那张演示卡恰好既是 viewer 又 active，两条规则重合、
 * 靠它分不出是谁在起作用）。两态长得不一样，只录一帧就等于把另一态交给运气。
 */
private val screenCardRecord: ScreenRecordUi = ScreenRecordUi(
    v = SCREEN_RECORD_VERSION,
    role = ScreenRole.Viewer,
    phase = ScreenPhase.Active,
    requestedAt = PREVIEW_NOW,
    startedAt = PREVIEW_NOW,
)

/**
 * `m-screencard` —— 消息流里那张屏幕协助历史卡。
 *
 * 原型 `prototype/screenshots/relay/m11g-screencard.png` 画的是一条自己发出的正文气泡（「你刚才看我那块屏有点卡，
 * 我这边把档位调到「流畅」了。」），**紧跟其后**一张 260 宽的历史卡
 * （主行「屏幕协助」+ 状态词「协助中」，副行「14:22 · 仅查看」）。
 *
 * 三件事只有这一帧能证明，三把量具全看不见：
 * 1. **卡是独立的一条消息**（`MessageUi.screen`），不是气泡的附件 —— 所以它自己占一行，
 *    左右归属由**它自己的** `mine` 决定（生产者把它设成"本侧是 viewer"，见
 *    `ScreenRecordUi.role` 的 KDoc）。原型把它画在前一条气泡的同一个 `.col` 里，
 *    那只是设计稿的排版写法；茶话间 `MessageRow.vue` 就是把它当独立消息渲染的。
 * 2. `active` 态的底色与状态词颜色，以及**边框按角色画**（viewer 侧无边框，peer 侧才画
 *    `--hair`）—— 与 `active`/`Ended` 无关。
 * 3. 时钟行同日只给 `HH:mm` —— 靠 `now = PREVIEW_NOW` 钉住。
 *
 * 两处**刻意不画**，都写在帧上免得后来人当缺陷补：
 * - 原型那张图里还有一条 `.notice`「对方请求查看你的屏幕」。产品里"有人请求看你屏幕"
 *   是一张**模态同意卡**（`RemoteViewSessionDialog`），不是消息流里的系统行 ——
 *   `NoticeKind` 里没有这一档，也**不该**有：请求是当下要回应的动作，不是一条历史记录。
 * - 原型还有一条 `daysep`「14:02」（日期分隔行）。代码里没有日期分隔行这个能力
 *   （W1 报告 §9 已把它登记成数据模型欠账），这一帧不假装有。
 */
@PreviewTest
@Preview(name = "chat-screen-card", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ChatScreenCardPhonePreview() {
    val record = screenCardRecord
    val state = stateWith(
        linId,
        listOf(
            previewMessage(linId, text = "我这边画面有点花，你看是不是码率的事", at = PREVIEW_NOW - 900_000L),
            previewMessage(
                linId,
                mine = true,
                text = "你刚才看我那块屏有点卡，我这边把档位调到「流畅」了。",
                at = PREVIEW_NOW - 600_000L,
            ),
            MessageUi(
                id = screenHistoryMessageId(linId, "s-preview-1"),
                peerId = linId,
                // 本侧是 viewer ⇒ 卡片靠右（生产者同一条规矩）。
                mine = true,
                // 生产写入的是 `screenCardText(record)` —— 它查 `screen_card_text_active`
                // 的中文值「屏幕协助进行中」（`ScreenHistoryCard.kt:235` 的 key 映射）。
                // 这里只能写死：本模块的 classpath 上没有 `compose.components.resources`
                // （`StringResource` 类型不可见），拿不到那把 key。
                // 卡本身**不渲染这个字段**（它只喂会话列表预览与搜索），所以图上看到的是
                // 卡片自己的状态词「协助中」；这个字面量的作用只是"这条消息不是空正文"。
                text = "屏幕协助进行中",
                at = record.requestedAt,
                screen = record,
            ),
        ),
    )
    ChatShell("conversations", state) { inner ->
        ChatScreen(
            data = ChatScreenState(
                state = state,
                peerId = linId,
                cacheDir = previewCacheDir,
                now = PREVIEW_NOW,
            ),
            actions = chatPreviewActions(
                onAttachFile = {},
                onNudge = {},
                onSendPk = { _, _, _ -> },
                onSearchInConversation = { _, _ -> emptyList() },
                onSendVoice = { _, _, _ -> },
                onRequestScreenView = {},
            ),
            chrome = ChatScreenChrome(
                modifier = inner,
            )
        )
    }
}
