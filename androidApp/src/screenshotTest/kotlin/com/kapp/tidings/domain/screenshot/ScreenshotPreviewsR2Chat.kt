// SPDX-License-Identifier: GPL-3.0-only
//
// 第二轮「会话 / 聊天组」精修帧（R2-W1，屏配对台账 2026-10-13 task-15）。
//
// 这一批与第一轮 `ScreenshotPreviewsChat.kt` 的分工：
//
// 1. **第一轮那批继续是规范帧**，回答「这块屏默认长什么样」，也是量具
//    （`tools/design-preview-pairing.py`）判定落点的那一张。它们继续有效，
//    本轮**只重录、不改**（那个文件在禁改清单里、由 Lead 独占）。
// 2. **本文件只放"第一轮那张拍不到、或拍错了"的帧**，三类：
//    - **拍错了**：`m-chat` 的规范帧 `ChatAgentToolsPhonePreview` 传的是
//      `state = chatBase`，而 `chatBase` 的 `messages` 里**没有 `PEER_AGENT` 的键**
//      ⇒ `messagesWith(peerId)` 为空 ⇒ 拍出来是**空会话**（「还没有消息 / 发第一条吧」），
//      与原型 `m03-chat`（有内容的会话）状态不符。这是简报 §2 教训 1 说的「假绿」：
//      帧落在本屏、配对判定 ✅，但状态不对。见 `R2ChatAgentContentPhonePreview`。
//    - **拍不到**：`m-home-more` / `m-msgmenu` 两个菜单是 `DropdownMenu`，内部是
//      `Popup(...)`，Layoutlib **只合成 Popup 容器、不合成条目**（同源第 3 例，
//      前两例是 W2 的 `NodesRowMenuPhonePreview` 与 W1 的 `EmojiPanel`）。
//      处置与 `EmojiPanel` 完全一致：把**内容本体**抽成 public Composable
//      （`ConversationsOverflowItems` / `MessagePressActions`），生产里 `DropdownMenu`
//      照旧包着它、行为一字不变，换来的只是这一层能被拍。
//      两个函数名都刻意**不含 `More` / `Menu`** —— `design-screen-diff.py` 用
//      「原型渲染函数名分词 ∩ 候选函数名分词」消歧本屏入口，`screenHomeMore` 的 `{home,more}`
//      与 `screenMsgMenu` 的 `{menu,msg}` 会把带这两个词的新函数选中、把宿主屏挤出切片
//      （实测 `--check` 因此多出两组「只原型」）。详见两个函数的 KDoc。
//    - **不确定**：`m-histdate` 的规范帧没传 `now` ⇒ `HistoryDateSheet` / `CalendarGrid`
//      落到默认 `nowMillis()`，月历标题（`10月`）、今天那一格、跨月 5 行排布**随真实月份漂移**。
//      本轮给两个函数加了带默认值的 `now` 形参（与 `ConversationsScreen(now = …)` 同一惯例），
//      本文件用 `now = PREVIEW_NOW` 钉死，参考图才确定。
//
// 两条纪律（与第一轮帧文件相同）：
// 1. **一屏一帧，帧里画的必须是那一屏的落点**。配对量具判的是"这一帧渲染的函数里，
//    有没有落在台账那一格写的落点文件里"。本文件的菜单体帧以**宿主屏**为落点
//    （`ConversationsScreen` / `ChatScreen`）—— 量具的 `COMPOSABLE_RE` 只认
//    `Screen|Pane|Dialog|Shell|View|Host|Window|Overlay|Bar|Subpage|Page|Chrome|Panel|Sheet`
//    结尾的名字，`…Menu` **不在词表里**，所以菜单体不会被当成落点，配对照旧判本屏。
// 2. **假数据只从 `com.kapp.tidings.ui.desktop.PreviewState` 取**（它是 `public` 的）。
//    `ScreenshotPreviews*.kt` 里的夹具全是 `private`，跨文件不可见 —— 这是刻意隔离。
package com.kapp.tidings.domain.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kapp.tidings.domain.model.DeliveryState
import com.kapp.tidings.domain.model.FileCardUi
import com.kapp.tidings.domain.model.MessageUi
import com.kapp.tidings.domain.model.NoticeKind
import com.kapp.tidings.domain.RelayUiState
import com.kapp.tidings.domain.model.TransferState
import com.kapp.tidings.feature.chat.ChatScreen
import com.kapp.tidings.feature.chat.ChatScreenChrome
import com.kapp.tidings.feature.chat.ChatScreenState
import com.kapp.tidings.feature.chat.HistorySearchOverlay
import com.kapp.tidings.feature.chat.MessagePressActions
import com.kapp.tidings.feature.conversations.ConversationsOverflowItems
import com.kapp.tidings.feature.conversations.ConversationsScreen
import com.kapp.tidings.ui.components.CompactScaffold
import com.kapp.tidings.ui.components.TopBarOverflowItem
import com.kapp.tidings.ui.components.TopBarOverflowItems
import com.kapp.tidings.ui.desktop.PREVIEW_NOW
import com.kapp.tidings.ui.desktop.agentPeer
import com.kapp.tidings.ui.theme.AppTheme
import com.android.tools.screenshot.PreviewTest

// ── 外壳 ──────────────────────────────────────────────────────────────────
//
// 与第一轮同形。**必须自己套 `AppTheme`**：`CompactScaffold` 不套主题，
// 漏掉这一层的症状是整帧掉进 M3 默认配色，而三个量具全绿、只有开图能看见。


/**
 * 菜单体外壳：原型 `.menu{background:var(--surface);border-radius:12px;
 * box-shadow:var(--shadow-pop),0 0 0 1px var(--hair);padding:5px}`
 * （`prototype/relay.html:1422-1435`）。
 *
 * **刻意不做任何定位**：位置与偏移正是这两帧不证明的东西（生产里由 `DropdownMenu`
 * 的 `Popup` 决定，Layoutlib 到不了那一层）。摆一个好看的位置反而会让人以为它证明了。
 * 这里只把壳画出来，让菜单条目本身有正确的底色与圆角 —— M3 `DropdownMenu`
 * 默认的 shape/elevation 与原型不同，直接拿它当基线会把两个偏差一起冻进参考图。
 */
@Composable
private fun MenuPanel(content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .width(190.dp)
            .shadow(8.dp, MaterialTheme.shapes.medium),
    ) {
        Column(modifier = Modifier.padding(vertical = 5.dp)) { content() }
    }
}

// ── 假数据 ────────────────────────────────────────────────────────────────


/**
 * `m-chat`（对端 = Agent）的消息流，逐条照原型 `MSGS_AGENT`
 * （`prototype/relay.html:1763-1785`）。
 *
 * **这一条线程就是本帧存在的全部理由**：第一轮的 `ChatAgentToolsPhonePreview`
 * 渲染同一个 `ChatScreen`、同一个对端，但 `state` 里没有 `agentId` 这个键，
 * 于是 `messagesWith(agentId)` 为空，画出来是空态。夹具状态是屏的一部分，
 * 空会话 ≠ 原型那一屏。
 */
private val agentThread: List<MessageUi> = listOf(
    MessageUi(
        id = "r2-agent-1",
        peerId = agentId,
        mine = false,
        text = "早，今天的 golden vector 又挂了：\n" +
            ":core:compileKotlinJvm 通过\n" +
            ":composeApp:linkDebugFrameworkIos 通过\n" +
            "golden vector parity 失败",
        at = PREVIEW_NOW - 900_000L,
        senderId = agentId,
    ),
    MessageUi(
        id = "r2-agent-2",
        peerId = agentId,
        mine = false,
        text = "原因找到了：codec.ts 对 group-text 用 hasOnlyKeys 校验，" +
            "对端多解一个 \"type\" 字段就整包丢弃。我把校验放宽成未知键透传。",
        at = PREVIEW_NOW - 840_000L,
        senderId = agentId,
    ),
    MessageUi(
        id = "r2-agent-3",
        peerId = agentId,
        mine = true,
        text = "重新跑一遍全量向量，我这边看 desktop 侧的消息列表是不是也补齐了。",
        at = PREVIEW_NOW - 780_000L,
        delivery = DeliveryState.Sent,
    ),
    MessageUi(
        id = "r2-agent-4",
        peerId = agentId,
        mine = false,
        text = "",
        at = PREVIEW_NOW - 720_000L,
        senderId = agentId,
        file = FileCardUi(
            transferId = "t-build-jvm",
            name = "build-jvm.log",
            // 原型 `meta:'2.4 MB · 1284 行'`；`FileCardUi` 没有"行数"字段，
            // 副行只画 `formatBytes(size)`，所以这里是 2.3 MB（2400000/1048576 取一位小数）。
            size = 2_400_000L,
            done = 1_536_000L, // 64%，照原型 `pct:64`
            mine = false,
            state = TransferState.Active,
        ),
    ),
    MessageUi(
        id = "r2-agent-5",
        peerId = agentId,
        mine = false,
        text = "",
        at = PREVIEW_NOW - 660_000L,
        notice = NoticeKind.NudgedByPeer,
    ),
    MessageUi(
        id = "r2-agent-6",
        peerId = agentId,
        mine = false,
        text = "桌面端发的那条 group-text 我这边当时没收到，现在已同步。",
        at = PREVIEW_NOW - 600_000L,
        senderId = agentId,
    ),
    MessageUi(
        id = "r2-agent-7",
        peerId = agentId,
        mine = true,
        text = "ci-runner 现在还离线，这条它自己排着，等上线会补发。",
        at = PREVIEW_NOW - 540_000L,
        delivery = DeliveryState.Queued,
    ),
    MessageUi(
        id = "r2-agent-8",
        peerId = agentId,
        mine = true,
        text = "这条草稿超过 800 字节，会自动改走 TCP 兜底通道。",
        at = PREVIEW_NOW - 480_000L,
        delivery = DeliveryState.Sent,
    ),
    MessageUi(
        id = "r2-agent-9",
        peerId = agentId,
        mine = false,
        text = "",
        at = PREVIEW_NOW - 420_000L,
        notice = NoticeKind.RecalledBySelf,
    ),
)

private val agentChatState: RelayUiState = stateWith(agentId, agentThread)

/** `onSendVoice` 的闸门是 `onSendVoice != null && cacheDir != null`，两个都要非空。 */
private const val previewCacheDir = "/tmp/relay-preview-cache"

// ── 帧 ────────────────────────────────────────────────────────────────────

/**
 * `m-chat`（聊天·私聊，对端 = Agent）—— **修正第一轮的空会话假绿**。
 *
 * 原型：`prototype/screenshots/relay/m03-chat.png` / `screenChat(solo)`
 * （`prototype/relay.html:3123`）。规范帧 `ChatAgentToolsPhonePreview` 的对端选对了
 * （`agentPeer()`，与原型 `PEERS[0]` 同档），但**夹具没给它消息**，所以那张图上
 * 写的是「还没有消息 / 发第一条吧。对方离线也没关系，消息会排队等它上线。」
 * 而原型那一屏是有内容的会话（多条气泡 + 一张正在接收的文件卡 + 两条 notice）。
 *
 * 按简报 §2 教训 1「量具说 0 ≠ 适配完成」，五条验收第 1 条（规范帧落在本屏、
 * **状态与 shot 一致**）在这张图上是不通过的。本帧把状态补上。
 *
 * 与原型仍有两处**产品判断差异**（不是适配漏项，已在台账 `m-chat` 行登记）：
 * ① 工具条只有 3 颗（图片/文件/语音），原型满排 7 颗 —— 手机端 `FilePicker`
 * 只有 `pick()`，截图/文件夹两颗没有对应能力；② 顶部没有「会话 · <名字>」那条
 * 会话切换条 —— 本端消息按**对端**存，没有会话维度，画它等于说假话。
 * 原型里的 `daysep`「14:02」日期分隔行本端也没有（数据模型欠账）。
 */
@PreviewTest
@Preview(
    name = "r2-chat-agent-content",
    widthDp = 393,
    heightDp = 852,
    showBackground = true,
    locale = "zh-rCN",
)
@Composable
private fun R2ChatAgentContentPhonePreview() {
    val peer = agentPeer()
    BareScreen { inner ->
        ChatScreen(
            data = ChatScreenState(
                state = agentChatState,
                peerId = peer.nodeId,
                cacheDir = previewCacheDir,
                now = PREVIEW_NOW,
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
 * `m-home-more`（消息·顶栏更多菜单）—— **Popup 内容体的可拍帧**。
 *
 * 原型：`prototype/screenshots/relay/m01b-home-more.png` / `screenHomeMore()`
 * （`prototype/relay.html:2861`）= `screenHomeRecent()` 底 + 右上角 `.menu` 六项
 * （建群 / 新建会话 ｜ 只看有未读的 / 全部标为已读 ｜ 排序：最近优先，禁用）。
 *
 * 台账这一行一直是 ⚠️「无本屏预览（Popup 内容不可拍）」。本轮把菜单体抽成
 * `ConversationsOverflowItems`（`feature/conversations/ConversationsScreen.kt`），
 * 生产里 `DropdownMenu` 照旧包着它、行为一字不变，于是这一层能被拍。
 *
 * 菜单里**没有**筛选 chip 那一排 —— 原型 `.annot` 逐字：「归类/过滤不进菜单 ——
 * 菜单里的东西看不见，而"现在看的是不是全部会话"必须一眼可见」。所以本帧的底
 * 是完整的 `ConversationsScreen`（含 chip 行），菜单浮在右上。
 *
 * 配对：量具看这一帧渲染的落点文件是 `ConversationsScreen`（`…Menu` 不在
 * `COMPOSABLE_RE` 后缀词表里），与台账 `m-home-more` 那一格一致 ⇒ ✅。
 */
@PreviewTest
@Preview(
    name = "r2-home-more-menu",
    widthDp = 393,
    heightDp = 852,
    showBackground = true,
    locale = "zh-rCN",
)
@Composable
private fun R2HomeMoreMenuPhonePreview() {
    ChatShell("conversations", chatBase) { inner ->
        Box(modifier = inner) {
            ConversationsScreen(
                state = chatBase,
                onOpenChat = {},
                onGoToPairing = {},
                onDismissBanner = {},
                now = PREVIEW_NOW,
                onCreateGroup = { _, _ -> },
                onMarkAllRead = {},
                onSearchGlobal = {},
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 56.dp, end = 12.dp),
            ) {
                MenuPanel {
                    ConversationsOverflowItems(
                        onCreateGroup = {},
                        onGoToPairing = {},
                        onOnlyUnread = {},
                        onMarkAllRead = {},
                    )
                }
            }
        }
    }
}

/**
 * `m-msgmenu`（长按消息菜单）—— **Popup 内容体的可拍帧**。
 *
 * 原型：`prototype/screenshots/relay/m06-msgmenu.png` / `screenMsgMenu()`
 * （`prototype/relay.html:3021`）= `screenChat()` 底 + `.menu` 五项
 * （撤回（带剩余时间）/ 戳一碰（灰）｜ 复制（灰）/ 转发（灰））。
 *
 * 代码侧菜单是 `回复 / 撤回 / 复制 / 转发`（`ChatScreen.kt:1203-1236`）——
 * **项集与原型不同**：「回复」是代码新增的能力（`MessageBubble` 里无条件接上，
 * 因为"回复"是纯本端状态、不依赖任何回调），「戳一碰」在代码里是顶栏按钮
 * 而不是菜单项，原型把复制/转发画灰是因为那一版「没有对应帧」。这是产品判断差异，
 * 不是适配漏项；本帧画的是代码真实的那四项。
 *
 * 撤回/转发两颗要传回调才画（`onRecallMessage` / `onForwardMessage` 为 null 就不画那一项），
 * 所以本帧两个都武装 —— 那是"这条消息此刻真的能撤回/转发"的完整形态。
 * 「复制」无条件画（`MessagePressActions` 里它不依赖任何回调）。
 *
 * 配对：落点是 `ChatScreen`（`…Menu` 不在词表里），与台账 `m-msgmenu` 那一格一致。
 */
@PreviewTest
@Preview(
    name = "r2-msg-menu",
    widthDp = 393,
    heightDp = 852,
    showBackground = true,
    locale = "zh-rCN",
)
@Composable
private fun R2MsgMenuPhonePreview() {
    BareScreen { inner ->
        Box(modifier = inner) {
            ChatScreen(
                data = ChatScreenState(
                    // 底就是 `screenChat()`（原型 `screenMsgMenu()` 逐字 `return \`${screenChat()}…\``），
                    // 所以对端也是 Agent，不是人。
                    state = agentChatState,
                    peerId = agentId,
                    cacheDir = previewCacheDir,
                    now = PREVIEW_NOW,
                ),
                actions = chatPreviewActions(
                    onAttachFile = { _ -> },
                    onRecallMessage = { _ -> },
                    onForwardMessage = { _, _ -> },
                    onSendPk = { _, _, _ -> },
                    onSearchInConversation = { _, _ -> emptyList() },
                    onSendVoice = { _, _, _ -> },
                ),
                chrome = ChatScreenChrome(
                    modifier = Modifier.fillMaxSize(),
                )
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 14.dp, bottom = 96.dp),
            ) {
                MenuPanel {
                    MessagePressActions(
                        copyText = "你刚才看我那块屏有点卡，我这边把档位调到「流畅」了。",
                        onReply = {},
                        onRecall = {},
                        onForward = {},
                    )
                }
            }
        }
    }
}

/**
 * `m-histdate`（会话内历史搜索·日期范围）—— **确定性的日期帧**。
 *
 * 原型：`prototype/screenshots/relay/m11f2-histdate.png` / `screenHistDate()`
 * （`prototype/relay.html:4568`）= `screenHistSearch()` 底 + `.sheet.drpz`
 * （抓手 / 「日期范围」+ × / 开始·结束两个字段 / 四颗快捷 chip / 月历 / 取消·确定）。
 *
 * **为什么另起一帧**：规范帧 `ChatHistDatePhonePreview` 没传 `now`，
 * `HistoryDateSheet` / `CalendarGrid` 落到默认 `nowMillis()` ⇒ 月历标题（`10月`）、
 * 今天那一格、跨月 5 行排布**随真实月份漂移**，同一份代码在不同月份录出不同参考图。
 * 本轮给两个函数加了带默认值的 `now` 形参（与 `ConversationsScreen(now = …)` 同一惯例），
 * 本帧传 `now = PREVIEW_NOW` 把它钉死。
 *
 * **四颗 chip 一颗都不亮，这是对的**：`selected` 的判据是「当前范围是不是正好
 * 等于由**今天**推出来的那一档」，而本帧的范围是照原型写死的「10月8日 至 10月10日」
 * （`histFrom`/`histTo`），`PREVIEW_NOW` 是 2026-10-05 ⇒ 与今天/近 7 天/近 30 天
 * 三档都不等，与「全部日期」也不等。原型 `q` 里「近 30 天」是 `aria-pressed="true"`
 * （`prototype/relay.html:4569`），那是**原型夹具的日期选择**（它的"今天"就是 10 月 8 日），
 * 不是代码缺陷 —— 要让某颗亮，得让帧的 `initialFromAt/initialToAt` 正好落在那一档上。
 * 代码侧四处硬编码 `selected = false` 的旧写法已改掉（那才是真缺陷：点「今天」
 * 真的改了范围、chip 却一颗都不亮）。
 */
@PreviewTest
@Preview(
    name = "r2-hist-date-deterministic",
    widthDp = 393,
    heightDp = 852,
    showBackground = true,
    locale = "zh-rCN",
)
@Composable
private fun R2HistDateDeterministicPhonePreview() {
    // 照原型 `HITS` 的日期：2026-10-08 至 2026-10-10（`PREVIEW_NOW` 是 2026-10-05）。
    val from = PREVIEW_NOW + 3L * 86_400_000L
    val to = PREVIEW_NOW + 5L * 86_400_000L
    BareScreen { inner ->
        HistorySearchOverlay(
            onDismiss = {},
            onSearch = { _, _ -> emptyList() },
            onSelectHit = {},
            modifier = inner,
            initialQuery = "协议",
            initialResults = emptyList(),
            initialFromAt = from,
            initialToAt = to,
            initialDateSheetOpen = true,
            now = PREVIEW_NOW,
        )
    }
}

/**
 * `m-histdate` 的第二档 —— **快捷 chip 选中态的证据帧**。
 *
 * 原型 `q` 里「近 30 天」是 `aria-pressed="true"`（`prototype/relay.html:4569`），
 * 而代码原来四处硬编码 `selected = false`：点「今天」真的改了范围、chip 却一颗都不亮，
 * 看不出当前筛的是哪一档（真缺陷，已改）。改完后判据是「当前范围是不是正好等于
 * 由**今天**推出来的那一档」—— 这一帧把范围设成「近 30 天」那一档，
 * 图上应当**只有「近 30 天」一颗亮**，这就是那条修复的视觉证据。
 *
 * 上一帧（`R2HistDateDeterministicPhonePreview`）用的是照原型 `HITS` 写死的
 * 自定义区间「9月13日 至 9月15日」，与三档都不等，所以四颗全不亮 —— 两帧合起来
 * 把「选中」与「未选中」两种状态都钉住了。
 */
@PreviewTest
@Preview(
    name = "r2-hist-date-quick30",
    widthDp = 393,
    heightDp = 852,
    showBackground = true,
    locale = "zh-rCN",
)
@Composable
private fun R2HistDateQuick30PhonePreview() {
    // 与 `HistoryDateSheet` 内 `quick30Days` 逐字同式：
    // `dayStart = ((now + 8h) / 1d) * 1d - 8h`（当地零点），`dayEnd = dayStart + 1d - 1ms`。
    val dayStart = ((PREVIEW_NOW + 28_800_000L) / 86_400_000L) * 86_400_000L - 28_800_000L
    val dayEnd = dayStart + 86_399_999L
    BareScreen { inner ->
        HistorySearchOverlay(
            onDismiss = {},
            onSearch = { _, _ -> emptyList() },
            onSelectHit = {},
            modifier = inner,
            initialQuery = "协议",
            initialResults = emptyList(),
            initialFromAt = dayEnd - 30L * 86_400_000L,
            initialToAt = dayEnd,
            initialDateSheetOpen = true,
            now = PREVIEW_NOW,
        )
    }
}

/**
 * 聊天页顶栏「⋯」收纳 —— **菜单体的可拍帧**。
 *
 * 原型规则：`actsHTML(list, max)`（`prototype/relay.html:2834-2845`）直出前 `max` 颗，
 * 其余进 `.hdmore`：`<button class="ibtn">` 一颗「更多」+ `.menu` 逐项
 * （`.hdmore .menu .mi2{font-size:12px;padding:8px 10px}`，`:697`；`.menu` 壳 `:1477-1482`）。
 * 聊天页的 `max` 就是 2（`screenChat()` 的 `topbar(..., acts, ...)`）。
 *
 * **这一帧拍不到生产里的真实触发态**，如实写在这里：手机聊天页当前最多只武装 2 颗动作
 * —— 人对人（在线）= 戳一碰 + 请求屏幕（`ChatScreen.kt:244-247`），Agent = 切换会话
 * （`:253`，且会话数 > 1），群 = 群信息（`:251`）。第 5 颗「历史搜索」在
 * `ChatScreen.kt:252` 被**刻意写死 null**：那里的注释（`:486-489`）逐字说它会挤掉标题，
 * 所以落到了输入区工具排的右端，「同一个屏上只能有一颗」。
 * ⇒ `actions.size > ChatHeaderInlineActions` 这条分支在现网不可达；它存在的意义是
 * 原型那条收纳规则本身 + 共享件 `TopBarOverflowAction` 的能力。
 *
 * 所以本帧画的是**两件事分开的证据**：底是真实可到的聊天页（林岚在线 ⇒ 头部两颗
 * 30dp 方块），浮层是 `TopBarOverflowItems` 的菜单体（用 `MenuPanel` 壳，理由同上面
 * `R2HomeMoreMenuPhonePreview`：`DropdownMenu` 内部是 `Popup`，Layoutlib 只合成容器、
 * 不合成条目）。菜单里那两项是**按动作表顺序会被收进 ⋯ 的两项**（请求屏幕 / 历史搜索），
 * 不是当前真会出现的项 —— 别把它当现网状态读。
 *
 * 已知偏离（记入台账）：① 菜单项走 M3 `DropdownMenuItem`（48dp 触摸目标），原型
 * `.hdmore .menu .mi2` 是 `font-size:12px;padding:8px 10px`；② **本帧的菜单项没有行首图标**
 * —— 生产里 `ChatHeader` 会把动作自己的图标塞进 `leadingIcon`（16dp），而这一帧做不到：
 * androidApp 的 `screenshotTest` 源集**既没有 compose 资源也没有 material-icons**
 * （实测 `com.kapp.tidings.generated.resources` 与 `androidx.compose.material.icons` 都 Unresolved），
 * 所以帧里只能省掉图标。这是帧的局限，不是组件的形状。
 */
@PreviewTest
@Preview(
    name = "r2-chat-overflow-items",
    widthDp = 393,
    heightDp = 852,
    showBackground = true,
    locale = "zh-rCN",
)
@Composable
private fun R2ChatOverflowItemsPhonePreview() {
    ChatShell("conversations", chatBase) { inner ->
        Box(modifier = inner) {
            ChatScreen(
                data = ChatScreenState(
                    state = chatBase,
                    peerId = linId,
                    cacheDir = previewCacheDir,
                    now = PREVIEW_NOW,
                ),
                actions = chatPreviewActions(
                    onNudge = {},
                    onRequestScreenView = {},
                ),
                chrome = ChatScreenChrome(modifier = Modifier.fillMaxSize()),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 56.dp, end = 12.dp),
            ) {
                MenuPanel {
                    TopBarOverflowItems(
                        items = listOf(
                            TopBarOverflowItem(label = "请求屏幕", onClick = {}),
                            TopBarOverflowItem(label = "历史搜索", onClick = {}),
                        ),
                    )
                }
            }
        }
    }
}
