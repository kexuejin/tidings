// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui.desktop

import com.kapp.tidings.domain.DesktopPaneActions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup

import com.kapp.tidings.core.protocol.Protocol
import com.kapp.tidings.feature.chat.ChatScreen
import com.kapp.tidings.ui.components.EMOJI_PALETTE
import com.kapp.tidings.ui.theme.IconSizes
import com.kapp.tidings.ui.theme.LocalAppSpacing
import com.kapp.tidings.ui.theme.RelayElevations
import com.kapp.tidings.ui.theme.RelayShapes
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.chat_attach
import com.kapp.tidings.generated.resources.chat_byte_over
import com.kapp.tidings.generated.resources.chat_emoji
import com.kapp.tidings.generated.resources.chat_group_media_hint
import com.kapp.tidings.generated.resources.chat_image
import com.kapp.tidings.generated.resources.chat_mention_all
import com.kapp.tidings.generated.resources.chat_nudge
import com.kapp.tidings.generated.resources.chat_pk
import com.kapp.tidings.generated.resources.chat_reply_cancel
import com.kapp.tidings.generated.resources.chat_reply_preview_prefix
import com.kapp.tidings.generated.resources.chat_send
import com.kapp.tidings.generated.resources.chat_toolbar_capture
import com.kapp.tidings.generated.resources.common_unknown_member
import com.kapp.tidings.generated.resources.desktop_composer_draft
import com.kapp.tidings.generated.resources.desktop_composer_emoji_note
import com.kapp.tidings.generated.resources.desktop_composer_placeholder
import com.kapp.tidings.generated.resources.desktop_composer_resize
import com.kapp.tidings.generated.resources.desktop_composer_route_tcp
import com.kapp.tidings.generated.resources.desktop_composer_route_udp
import com.kapp.tidings.generated.resources.desktop_composer_send_hint
import com.kapp.tidings.generated.resources.hist_search_title
import com.kapp.tidings.generated.resources.ic_attachment
import com.kapp.tidings.generated.resources.ic_image
import com.kapp.tidings.generated.resources.ic_nudge
import com.kapp.tidings.generated.resources.ic_pk
import com.kapp.tidings.generated.resources.ic_scissors
import com.kapp.tidings.generated.resources.ic_search
import com.kapp.tidings.generated.resources.ic_smile
import com.kapp.tidings.generated.resources.ic_plus
import com.kapp.tidings.generated.resources.chat_new_task_action
import androidx.compose.material3.Surface
import androidx.compose.ui.text.font.FontWeight
import com.kapp.tidings.ui.components.PkPickerDialog
import com.kapp.tidings.domain.model.PeerUi
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import com.kapp.tidings.ui.components.AppIconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.kapp.tidings.feature.chat.COMPOSER_FOCUS_REQUEST_IDLE
import com.kapp.tidings.ui.components.MentionTarget
import com.kapp.tidings.ui.components.filterMentionTargets
import com.kapp.tidings.ui.components.insertMention
import com.kapp.tidings.ui.components.mentionQueryBeforeCursor
import com.kapp.tidings.domain.model.MessageUi

// ------------------------------------------------------------------ 浮起输入卡片

/**
 * 桌面专用输入卡片：浮在聊天区之上，不占消息流的高度。
 *
 * 三个刻意的取舍：
 * 1. 自己记 [TextFieldValue] 而不是只记 String —— 只有 selection 也在手上，
 *    表情才能插到光标处而不是永远追加到末尾。
 * 2. 拖拽手柄用 `Modifier.draggable` 自记比例，不引第三方 split 控件：
 *    整个桌面只有一个可拖分隔条，为它引一整个库不值。
 * 3. 字节数按 UTF-8 算 —— 中文一行 3 字节，字符数看着不过 800 的草稿经常超 UDP 限制。
 */

/**
 * 输入栏算「宽裕」的最小宽度，窄于此就丢掉非必要的文字。
 *
 * 约等于底行的草稿/路由提示（~130px）+ 键盘提示（~175px）+ 间距 + 发送按钮（~72px）。
 * 丢的顺序是有讲究的：先丢键盘提示与按钮上的文字，**永不**丢草稿字节数与发送按钮 ——
 * 前者是「这条发不发得出去」的答案，后者是唯一的出口。
 *
 * 更窄的窗口不该出现这个组件：分栏下限由 `DesktopMetrics.MIN_CHAT_PANE_PX` 保证。
 */
private val ComposerRoomyWidth = 420.dp

/**
 * 进入会话时最多重试几次 `requestFocus()`。
 *
 * 目标在焦点树里落定需要一两帧（输入框在子组合里，首帧 `requestFocus()` 返回 false），
 * 所以第一枪必然是空的。给到 30 帧（半秒左右）足够覆盖慢机器，又不会在真正拿不到焦点时
 * 无限空转 —— 循环只在 `requestFocus()` 返回 true 时提前退出。
 */
private const val FOCUS_ATTEMPTS = 30

/**
 * 工具行每一颗图标的**方框**边长，等于原型 `.tools .ibtn` 的 28px
 * （`prototype/relay.html:1135`：`width:28px;height:28px`）。
 *
 * 不是 `IconButton` 默认的 48dp：桌面壳里同一族的图标按钮都照原型收窄
 * （`ui/desktop/DesktopConversationsPane.kt` 的 34dp ← 原型通用
 * `.ibtn{34×34}`，`DesktopChrome.kt:164` 的 18dp），工具行这一排尤其明显 ——
 * 原型是 28px 框 + `gap:2px`（`relay.html:1134`）的紧凑排，48dp 会把六颗
 * 拉成一排散开的点。图标本身仍是 [IconSizes.Label]（16dp ← 原型
 * `.tools .ibtn svg{16px}` `relay.html:1136`），所以「框变小、图标不变」。
 */
private val ComposerToolIconSize = 28.dp

/**
 * 这份草稿**现在能不能发** —— 发送按钮与 Enter 键**共用**的同一条判据。
 *
 * ## 为什么必须是同一个函数
 *
 * 发送按钮一直有这条门（超限置灰），但 Enter 直发那条路从前**不查**：超限的草稿按
 * Enter，输入框被清空、字没了，消息实际**没有发出去**（对端按字节预算整帧拒收，
 * 发送侧收不到任何错误 —— AGENTS §3 的「静默丢失」形态）。两处各写一遍判据，
 * 就必然有一处漏；抽成纯函数让「Enter 与按钮同一条门」可被单测钉住。
 */
internal fun desktopComposerCanSend(text: String, mode: ComposeByteMode): Boolean =
    text.isNotBlank() && !mode.overLimit

@Composable
public fun DesktopComposer(
    peer: PeerUi,
    draft: String,
    onDraftChange: (String) -> Unit,
    controller: DesktopPaneActions,
    /**
     * 这个会话是不是群。
     *
     * 为什么不是让本函数自己 `ConvId.isGroup(peer.nodeId)` 推出来：群没有 `PeerUi`，
     * 调用方给的是一个**占位**对端（`RelayUiState.groupPeer`），占位的 `nodeId` 恰好是
     * 会话键 —— 于是"从 nodeId 的形状猜语义"在这里能猜对，但它只是碰巧对。
     * 显式传进来，将来占位对端的 id 怎么变都不会影响这里。
     */
    isGroup: Boolean = false,
    /**
     * `@` 候选（群成员 + 群里可叫醒的 agent）。空 = 这个会话没有可 @ 的对象。
     *
     * **桌面端以前完全没有这个能力**：`DesktopComposer` 自己画输入框，只在那排按钮里
     * 塞了一个硬编码的「@dsh」快捷插入 —— 于是「输入 @ 弹出成员列表」在手机上能用、
     * 桌面上根本没有（真机反馈：「桌面端还是无法输入 @」）。
     *
     * 与移动端用**同一个** [MentionTarget] 与同一个 [insertMention]：插入的 token 与
     * 触发判据同源，不存在"@ 了却叫不醒"的分叉。
     */
    mentionTargets: List<MentionTarget> = emptyList(),
    /**
     * 正在引用的消息；null = 不画引用条。
     *
     * **桌面此前不收它**：气泡菜单点「回复」只改了 ChatScreen 的状态，这里的输入卡片
     * 看都不看 —— 用户点了没反应。引用条样式与手机 [com.kapp.tidings.ui.components.MessageComposer]
     * 的回复预览同款、同一组 strings key。
     */
    replyingTo: MessageUi? = null,
    /** [replyingTo] 的发送者显示名（ChatScreen 用身份解析器算好）；null = 资料未到。 */
    replySenderName: String? = null,
    /** 取消引用。 */
    onCancelReply: () -> Unit = {},
    /**
     * 「新任务」chip 要点名的 agent token（ChatScreen 按**这个群里的 agent kind** 解析，
     * 与手机内置输入栏同一个值）。null = 不是工作区群，chip 不画。
     *
     * **桌面 chip 从前在这里写死 `@dsh `**：换个 agent 群（`@codex` 之类）它插的就是
     * 一个叫不醒的 token。
     */
    newTaskToken: String? = null,
    /**
     * 按消息 id 跳到消息流里那条（历史搜索命中的落点）；null = 不跳。
     *
     * **桌面此前把命中整个丢掉**（`onSelectHit = { _ -> 关窗 }`）：窗口关了，
     * 用户却不知道要找的那条在哪。
     */
    onJumpToMessage: ((String) -> Unit)? = null,
    /**
     * 发送出口：`(正文, 被回复的消息 id)`。
     *
     * 生产调用方（`ChatPane` 的 composerSlot）传的是 [com.kapp.tidings.feature.chat.ChatScreen]
     * 里那个**与手机内置输入栏共用的**出口：它复位草稿、清外部草稿表、清引用态，
     * 并把 replyId 交给发送侧。
     *
     * **桌面从前没有这个参数**：这里直接调 `controller.sendToConversation(peer.nodeId, text)`，
     * 于是「回复」的 replyId 在这一行就丢了（发出去是一条普通消息），发完引用条也挂在
     * 输入框上方不走 —— 用户看到的是"点了回复等于没点"。
     *
     * 默认实现只服务预览 / 截图夹具（它们拿不到 ChatScreen 的状态，也没有会话可言）：
     * 仍然把 replyId 带到发送侧，语义与生产路径一致。
     */
    onSend: (body: String, replyToId: String?) -> Unit = { body, replyToId ->
        controller.sendToConversation(peer.nodeId, body, replyToId)
    },
    /**
     * 「把焦点落到输入框」的意图计数（[COMPOSER_FOCUS_REQUEST_IDLE] = 没有请求）。
     * 由 [com.kapp.tidings.feature.chat.ChatScreen] 的回复 / 长按头像直接 @ / 新任务 chip 递增。
     *
     * 为什么需要这条接缝：那几条动作在手机上调的是 `ChatScreen` 自己那份
     * `composerFocus`，而桌面走的是本卡片 —— 那个 requester 在桌面**没有挂载点**，
     * `requestFocus()` 打印一行警告并返回 false。症状是"点了回复、@ 了人，光标却还在
     * 消息列表上，得自己再点一下输入框"。
     *
     * 键是计数不是布尔（见 [com.kapp.tidings.feature.chat.nextComposerFocusRequest]）：
     * 同一会话里连续两次「回复」也得各触发一次。
     */
    focusRequest: Int = COMPOSER_FOCUS_REQUEST_IDLE,
    /**
     * 只读原因；null = 可以正常输入。被移出群时非空（与手机内置输入栏同一句话）。
     *
     * 只读时**整块收起输入卡片**（留一句说明），与手机侧同一条语义：
     * 让用户打完字再吃一条「发送失败」是最糟的形状。
     */
    readOnlyReason: String? = null,
) {
    val density = LocalDensity.current
    var split by remember { mutableStateOf(ComposerSplit(ComposerSplit.DEFAULT_RATIO)) }
    var value by remember(peer.nodeId) {
        mutableStateOf(TextFieldValue(draft, TextRange(draft.length)))
    }
    // **外部对草稿的改动要把字送进输入框**（与手机端 MessageComposer 的
    // LaunchedEffect(draft) 同一条纪律）。真身：长按头像「直接 @」改的是 ChatScreen
    // 持有的 draft，这里只记自己的 TextFieldValue —— 没有这一步，@ 插进去了、
    // 桌面输入框却纹丝不动，动作静默丢失。
    //
    // 键是 (peer.nodeId, draft) 而不是裸 draft：DesktopComposerFocusGuardTest 盯着
    // 「聚焦效果不许按 draft 键」（每打一个字抢一次焦点会让表情/候选面板点不动）。
    // 本效果**只同步文本、绝不 requestFocus**；正常打字的回路
    // （onValueChange → onDraftChange → draft 流回来）文本恒等，body 是空操作，
    // 光标不会被拽走。外部改动（@ 插入、发送清空）才真的写回。
    LaunchedEffect(peer.nodeId, draft) {
        if (value.text != draft) {
            value = value.copy(text = draft, selection = TextRange(draft.length))
        }
    }
    var emojiOpen by remember { mutableStateOf(false) }
    var mentionMenuOpen by remember { mutableStateOf(false) }
    // 光标前正在进行的那段 `@` 输入（`mentionQueryBeforeCursor` 的产物）：
    // 候选开着时决定**过滤**，变 null（@ 被删 / 跨过空白）就收起候选。
    var mentionQuery by remember { mutableStateOf<String?>(null) }
    var historySearchOpen by remember(peer.nodeId) { mutableStateOf(false) }
    var pkPickerOpen by remember(peer.nodeId) { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    // **进入会话就把焦点给输入框。**
    //
    // 没有这一步时，窗口/会话打开后焦点在根容器上：用户先按空格或直接打字，
    // 按键进不了输入框 —— 表现是「输入框不支持空格」，而**根容器会出现点击高亮**
    // （`.focusable()` 的默认指示），于是更像「点中了什么」。
    //
    // **必须按返回值重试，不能只调一次。** `FocusRequester.requestFocus()` 在目标还没
    // 进入焦点树的头几帧里**返回 false 且不抛异常** —— 而输入框在两层
    // `BoxWithConstraints`（子组合）里，首帧必然是这个状态。旧写法
    // `runCatching { requestFocus() }` 把返回值丢了，于是"聚焦"这个动作每次都
    // 静默失败：用户不点一下输入框，空格与字母都进不去（真机反复报"打不进空格"，
    // 而点击之后一切正常 —— 这条差异就是判据）。用 `withFrameNanos` 等下一帧再试，
    // 拿到 true 立刻停，不无限抢焦点。
    //
    // 只在**换会话**时请求（键是 peer.nodeId）：每条 draft 变化都抢焦点
    // 会让用户点别处（表情面板、候选列表）后又被拽回来。
    LaunchedEffect(peer.nodeId) {
        repeat(FOCUS_ATTEMPTS) {
            if (focusRequester.requestFocus()) return@LaunchedEffect
            withFrameNanos { }
        }
    }

    // **外部要求的焦点**（回复 / 长按头像直接 @ / 新任务）：与上面那条"进会话就聚焦"分开。
    //
    // 上面那条的键是 peer.nodeId，只在换会话时跑；这条的键是**递增计数**，只有
    // `ChatScreen` 真的点名要焦点时才变。两条都留着是必要的：合进上面那条的键
    // 会让"每打一个字"或"每次回复"都重跑一遍带重试的聚焦循环，反而把候选面板拽走。
    //
    // 这里**不用重试、不赌时序**：能点「回复」/「@」时本卡片早已在树上（用户点了消息列表
    // 里的东西），首个组合帧之后 `requestFocus()` 是确定的。用重试反而会在卡片已经
    // 可见之后继续抢焦点。
    LaunchedEffect(focusRequest) {
        if (focusRequest != COMPOSER_FOCUS_REQUEST_IDLE) {
            focusRequester.requestFocus()
        }
    }

    val bytes = draftByteCount(value.text)
    val mode = composeByteMode(bytes)

    fun send() {
        // **与发送按钮同一条判据**（[desktopComposerCanSend]）：超限的草稿发出去会被
        // 对端按字节预算整帧拒收，发送侧收不到任何错误 —— 按 Enter 后输入框被清空、
        // 字没了、消息实际没发出去，正是「静默丢失」的形态。这里不拦，
        // Enter 那条路就是唯一绕过按钮置灰的门。
        if (!desktopComposerCanSend(value.text, mode)) return
        val text = value.text.trim()
        if (text.isEmpty()) return
        // **带 replyId 走 ChatScreen 的出口**（[onSend]）：从前这一行是
        // `controller.sendToConversation(peer.nodeId, text)` —— 「回复」的 replyId 与
        // 发送后的引用态清理都在这条路上丢了。现在发送、清草稿、清引用是同一个动作。
        onSend(text, replyingTo?.id)
        value = TextFieldValue("")
        onDraftChange("")
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val availablePx = with(density) { maxHeight.toPx() }.toInt().coerceAtLeast(ComposerSplit.MIN_HEIGHT_PX)
        val composerPx = split.heightFor(availablePx)
        // 外层必须 wrap content：如果这里 fillMaxSize()，作为 ChatScreen Column 里
        // 一个「无 weight」的子节点，它会一口吃掉全部剩余高度，
        // 上面那个 weight(1f) 的消息区就被挤成 0 高（桌面端聊天区整片消失的根因）。
        Column(Modifier.fillMaxWidth()) {
            // 手柄：向上拖输入框变高（delta 为正表示手指向上）。
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(LocalAppSpacing.current.md10)
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { delta ->
                            val next = split.ratio + (-delta / availablePx.toFloat())
                            split = ComposerSplit(ComposerSplit.clamped(next))
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.DragHandle,
                    contentDescription = stringResource(Res.string.desktop_composer_resize),
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(14.dp),
                )
            }
            Card(
                elevation = CardDefaults.cardElevation(defaultElevation = RelayElevations.Floating),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth().height(with(density) { composerPx.toDp() }),
            ) {
                Column(Modifier.fillMaxSize().padding(LocalAppSpacing.current.md10)) {
                    // 只读：留一句说明、收起输入区（与手机 MessageComposer 的 readOnlyReason 同义）。
                    // 让用户打完字再吃一条「发送失败」是最糟的形状。
                    if (readOnlyReason != null) {
                        Text(
                            text = readOnlyReason,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(vertical = LocalAppSpacing.current.sm6),
                        )
                        return@Column
                    }
                    // 引用条：与手机 MessageComposer 的回复预览同款（同一组 strings key）。
                    // 桌面从前不收 replyingTo —— 气泡菜单点「回复」什么都不会发生。
                    replyingTo?.let { reply ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = LocalAppSpacing.current.sm6),
                        ) {
                            Row(
                                modifier = Modifier.padding(
                                    horizontal = LocalAppSpacing.current.md10,
                                    vertical = LocalAppSpacing.current.sm6,
                                ),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                val replyText = reply.text.ifBlank { reply.file?.name.orEmpty() }
                                // 发送者名由 ChatScreen 用身份解析器算好；资料没到就说
                                // 「未知成员」，不把 8 位 hex 的裸 id 当人名印。
                                val senderLabel = replySenderName ?: stringResource(Res.string.common_unknown_member)
                                Text(
                                    text = stringResource(
                                        Res.string.chat_reply_preview_prefix,
                                        senderLabel,
                                        // 不做 UTF-16 预截断（会劈开 emoji），交给 maxLines+Ellipsis。
                                        replyText,
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = onCancelReply, modifier = Modifier.size(24.dp)) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = stringResource(Res.string.chat_reply_cancel),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        // 工具行照原型 `toolsRow(group=false)` 逐颗对齐
                        // （`prototype/relay.html:3103-3118`）：表情 · PK · 窗口震动 · 截图 ·
                        // 发送图片 · 发文件 ·[弹性]· 历史搜索。
                        // **一律纯图标**：原型工具行里那颗按钮是 `.tools .ibtn`
                        // （`relay.html:1135-1138`）—— 28×28、背景透明、图标 16px、颜色 `--on-2`；
                        // 手机端同一排也是纯图标（`ChatScreen.kt` 的 `ComposerCapability`）。
                        // 这里原先宽档画带文字的「文件… / 截图 / 查找聊天记录」、窄档画图标，
                        // 同一个壳里长出两种输入区 —— 已统一成图标。
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            // 原型 `.tools{gap:2px}`（`relay.html:1134`）· `AppSpacing.xxs`。
                            horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.xxs),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            // 表情在原型工具行的第一位（`relay.html:3109` 的 `i-smile`），
                            // 也是整排里唯一带浮层的：它那一格连着 `EmojiPopup`。
                            Box {
                                AppIconButton(
                                    icon = Res.drawable.ic_smile,
                                    contentDescription = stringResource(Res.string.chat_emoji),
                                    onClick = { emojiOpen = true },
                                    modifier = Modifier.size(ComposerToolIconSize),
                                )
                                if (emojiOpen) {
                                    EmojiPopup(
                                        onPick = { emoji ->
                                            val v = value
                                            val start = v.selection.min.coerceAtLeast(0)
                                            val end = v.selection.max.coerceAtLeast(start)
                                            val text = v.text.substring(0, start) + emoji + v.text.substring(end)
                                            value = v.copy(text = text, selection = TextRange(start + emoji.length))
                                            onDraftChange(text)
                                            // 面板收起后把光标还给输入框，否则接着打字会丢焦点。
                                            emojiOpen = false
                                            focusRequester.requestFocus()
                                        },
                                        onDismiss = { emojiOpen = false },
                                    )
                                }
                            }
                            // PK：原型第 2 颗（`relay.html:3110` 的 `i-pk`）—— 点了开
                            // 「猜拳 / 骰子」二选一的小浮层，不是直接出手。
                            ToolIcon(painterResource(Res.drawable.ic_pk), stringResource(Res.string.chat_pk)) { pkPickerOpen = true }
                            // 窗口震动：原型第 3 颗（`relay.html:3114` 的 `i-nudge`），**只给单聊** ——
                            // 原型注释逐字「窗口震动（nudge）只在单聊出现，群里对『群』做震动没有
                            // 收件人」（`relay.html:1588`）。本端在这条规则上再窄一格：**Agent 会话
                            // 也不画**（震动要落到一个具体窗口上），判据与手机端那两颗逐字同源
                            // （ChatScreen 里内置输入栏与头部两处 onNudge 的武装判据都是
                            // `takeIf { group == null && !peer.isAgent }`）。
                            // 桌面头里原先那颗已按原型撤掉（`DesktopConversationsPane.kt`），
                            // 所以整屏只剩这一处。原型 d-chat 那一屏画了对端 Agent 的震动 ——
                            // 这点差异已登记（见报告 d-chat 一节）。
                            if (!isGroup && !peer.isAgent) {
                                ToolIcon(painterResource(Res.drawable.ic_nudge), stringResource(Res.string.chat_nudge)) { controller.nudge(peer.nodeId) }
                            }
                            // 截图：原型第 4 颗（`relay.html:3115` 的 `i-scissors`）。原型注释
                            // `relay.html:1586`「截图是本地动作，不依赖对端在线，所以整排里只有它
                            // 不吃 disabled」。
                            ToolIcon(painterResource(Res.drawable.ic_scissors), stringResource(Res.string.chat_toolbar_capture)) { controller.triggerScreenCapture(peer.nodeId) }
                            // 发送图片：原型第 5 颗（`i-image`，`relay.html:3116`）。与「发文件」
                            // 共用**同一条** `pick()` 链路 —— `AndroidFilePicker.pick()` 拉的是
                            // `arrayOf("*/*")`（`androidMain/.../AndroidFilePicker.kt` 的 `pick`），图片本来
                            // 就是文件；手机端就是这么画的（`ChatScreen.kt` 的 id="image" 与
                            // `:627` 的 id="file" 共用 `attach`），桌面照它对齐。
                            ToolIcon(painterResource(Res.drawable.ic_image), stringResource(Res.string.chat_image)) { controller.pickAndSendFiles(peer.nodeId) }
                            // 发文件：原型第 6 颗是**回形针**（`i-clip`，`relay.html:3117`），不是文件夹
                            // —— 文件夹在原型里是第 7 颗「发送文件夹」（`i-folder`），本端没有那条链路
                            // （不画，理由见 `fidelity-deviations.yaml` 的 toolsRow 那两节）。
                            // 标签用 `chat_attach`（「发文件」）而不是桌面那份 `desktop_composer_attach`
                            // （「文件…」）：手机端同一颗用的就是前者（`ChatScreen.kt`），
                            // 而带省略号的标签是给带文字的按钮用的，做 contentDescription 读起来是残句。
                            ToolIcon(painterResource(Res.drawable.ic_attachment), stringResource(Res.string.chat_attach)) { controller.pickAndSendFiles(peer.nodeId) }
                            // 群聊多一句 `.shint`（原型 `toolsRow(group=true)` 的
                            // `<span class="shint">仅在线群成员可接收图片/文件</span>`，
                            // `prototype/relay.html:3162`；样式 `.tools .shint{font-size:11px;
                            // color:var(--on-2);margin-left:6px;white-space:nowrap;
                            // overflow:hidden;text-overflow:ellipsis}`，`:1180`）。
                            // 位置在这一排的**行内**、`tsep` 之前 —— 单聊支没有这句
                            // （原型 `:3149-3164` 两支对比着看）。文案复用既有的
                            // `chat_group_media_hint`（手机端群聊同一句，`ChatScreen.kt` 已在用）。
                            if (isGroup) {
                                Text(
                                    text = stringResource(Res.string.chat_group_media_hint),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = LocalAppSpacing.current.sm6),
                                )
                            }
                            // 「新任务」chip：点名的 token 由 ChatScreen 按**这个群里的
                            // agent kind** 解析（与手机内置输入栏同一个值、同一条
                            // insertMention 插入路径）—— 从前在这里写死 "@dsh "，
                            // 换个 agent 群就插出一个叫不醒的 token。
                            // 判据与手机一致：只在工作区群画（newTaskToken != null）。
                            if (newTaskToken != null) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                    modifier = Modifier
                                        .padding(start = LocalAppSpacing.current.sm)
                                        .clickable {
                                            val currentText = value.text
                                            if (!currentText.startsWith(newTaskToken, ignoreCase = true)) {
                                                val (newText, cursor) = insertMention(currentText, 0, 0, newTaskToken)
                                                value = TextFieldValue(newText, TextRange(cursor))
                                                onDraftChange(newText)
                                            }
                                            focusRequester.requestFocus()
                                        },
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Icon(
                                            painter = painterResource(Res.drawable.ic_plus),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(12.dp),
                                        )
                                        Text(
                                            text = stringResource(Res.string.chat_new_task_action),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Medium,
                                        )
                                    }
                                }
                            }
                            // 历史搜索排**行尾**：原型用 `.tools .tsep{flex:1 1 auto}`
                            // （`relay.html:1145`）把它推到最右，而不是摆在中间。
                            Spacer(Modifier.weight(1f))
                            ToolIcon(painterResource(Res.drawable.ic_search), stringResource(Res.string.hist_search_title)) { historySearchOpen = true }
                        }
                    }
                    OutlinedTextField(
                        value = value,
                        onValueChange = { next ->
                            val previousText = value.text
                            value = next
                            onDraftChange(next.text)
                            // 与手机端 MessageComposer **同一份判据**（不是抄一份实现）：
                            // 敲入 `@` 开候选，之后每次输入都重读"光标前那段 @ 输入"——
                            // 它变 null（@ 被删 / 跨过空白）就收起，非空就按它过滤候选。
                            // 桌面从前只开不收、也不过滤：候选挂在那儿盖住正文，删了 @ 还赖着。
                            if (mentionTargets.isNotEmpty()) {
                                if (next.text.length > previousText.length) {
                                    val cursor = next.selection.end
                                    if (cursor > 0 && next.text.getOrNull(cursor - 1) == '@') {
                                        mentionMenuOpen = true
                                    }
                                }
                                val query = mentionQueryBeforeCursor(next.text, next.selection.end)
                                if (mentionMenuOpen && query == null) {
                                    mentionMenuOpen = false
                                }
                                mentionQuery = if (mentionMenuOpen) query else null
                            }
                        },
                        placeholder = { Text(stringResource(Res.string.desktop_composer_placeholder), style = MaterialTheme.typography.bodyMedium) },
                        textStyle = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onPreviewKeyEvent { event ->
                                if (event.key == Key.Enter && event.type == KeyEventType.KeyDown) {
                                    if (event.isShiftPressed) {
                                        val v = value
                                        val at = v.selection.end.coerceAtLeast(0)
                                        val text = v.text.substring(0, at) + "\n" + v.text.substring(at)
                                        value = v.copy(text = text, selection = TextRange(at + 1))
                                        onDraftChange(text)
                                    } else {
                                        send()
                                    }
                                    true
                                } else {
                                    false
                                }
                            },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                    )
                    // `@` 候选：输入 `@` 后弹出（与手机端 MessageComposer 同一套 UI 与同一条
                    // 插入函数）。桌面端此前完全没有这个能力，只有一个硬编码的「@dsh」快捷插入。
                    DropdownMenu(
                        expanded = mentionMenuOpen,
                        onDismissRequest = {
                            mentionMenuOpen = false
                            mentionQuery = null
                        },
                    ) {
                        // 「@所有人」与手机端 MessageComposer 对齐（同一个 strings key、
                        // 同一条 insertMention 插入路径、同一条前缀过滤）。
                        val mentionAllToken = "@" + stringResource(Res.string.chat_mention_all)
                        // 按**光标前的前缀**过滤，而不是不管打到哪儿都把全体成员摊出来
                        // （[filterMentionTargets] 与手机端同一份实现，不复制第二份）。
                        val query = mentionQuery.orEmpty()
                        val showMentionAll = mentionAllToken.removePrefix("@")
                            .startsWith(query, ignoreCase = true)
                        val visibleTargets = filterMentionTargets(mentionTargets, query)
                        if (showMentionAll) {
                            DropdownMenuItem(
                                text = { Text(mentionAllToken) },
                                onClick = {
                                    mentionMenuOpen = false
                                    mentionQuery = null
                                    val (nextText, nextCursor) = insertMention(
                                        text = value.text,
                                        start = value.selection.min,
                                        end = value.selection.max,
                                        token = mentionAllToken,
                                    )
                                    value = TextFieldValue(nextText, TextRange(nextCursor))
                                    onDraftChange(nextText)
                                    focusRequester.requestFocus()
                                },
                            )
                        }
                        visibleTargets.forEach { target ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(target.token)
                                        // 资料没到时如实说一句；有名字的人不画副行（不制造噪声）。
                                        target.note?.let { note ->
                                            Text(
                                                text = note,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    mentionMenuOpen = false
                                    mentionQuery = null
                                    val (nextText, nextCursor) = insertMention(
                                        text = value.text,
                                        start = value.selection.min,
                                        end = value.selection.max,
                                        token = target.token,
                                    )
                                    value = TextFieldValue(nextText, TextRange(nextCursor))
                                    onDraftChange(nextText)
                                    focusRequester.requestFocus()
                                },
                            )
                        }
                    }
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        // 窄聊天栏里按顺序丢东西：先丢键盘提示，再让草稿行省略号截断。
                        // 顺序不能反 —— 反了就成「看得见怎么发，看不见发的是什么」。
                        // 更窄的窗口根本不该分栏，由 DesktopMetrics.MEDIUM_MIN_PX 挡住。
                        val showKeyHints = maxWidth >= ComposerRoomyWidth
                        // 超限时这里说的是「发不出去」，不是「换条路」—— 两件事在手机上是分开说的，
                        // 桌面上也必须分开：hint() 对 Over 返回 null，就是这条判据。
                        val routeHint = mode.hint()
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            // 草稿行必须是**加权**的那一个：Row 先量非加权子项，
                            // 不加权时一条超限长草稿会把发送按钮挤成 0 宽（曾经如此）。
                            Text(
                                if (routeHint == null) {
                                    stringResource(Res.string.chat_byte_over, Protocol.TEXT_TCP_LIMIT)
                                } else {
                                    stringResource(Res.string.desktop_composer_draft, bytes, Protocol.TEXT_UDP_LIMIT, stringResource(routeHint))
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (mode.overLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (showKeyHints) {
                                Text("Enter", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                // 前导那个空格是排版用的（和左边那个 ASCII 的 "Enter" 分开），留在代码里，免得被资源文件的空白折叠规则吃掉。
                                Text(" " + stringResource(Res.string.desktop_composer_send_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.width(LocalAppSpacing.current.md))
                            Button2(stringResource(Res.string.chat_send), Icons.AutoMirrored.Outlined.Send, enabled = desktopComposerCanSend(value.text, mode), onClick = { send() })
                        }
                    }
                }
            }
        }
    }

    if (historySearchOpen) {
        DesktopHistorySearchDialog(
            peer = peer,
            onDismiss = { historySearchOpen = false },
            onSearch = { q, f -> controller.searchInConversation(peer.nodeId, q, f) },
            onSelectHit = { hit ->
                historySearchOpen = false
                // **命中要跳过去，不是只把窗口关掉。** 从前这里把命中整个丢掉：
                // 用户搜到了那条，点一下窗口合上，要找的消息在哪只靠自己翻。
                onJumpToMessage?.invoke(hit.id)
            },
        )
    }

    if (pkPickerOpen) {
        PkPickerDialog(
            onDismiss = { pkPickerOpen = false },
            onPick = { game, value -> controller.sendPk(peer.nodeId, game, value) },
        )
    }
}

/**
 * 工具行里的一颗**纯图标**按钮（表情 / PK / 窗口震动 / 截图 / 发送图片 / 发文件 / 历史搜索）。
 *
 * 形状照原型 `.tools .ibtn`（`prototype/relay.html:1135-1138`）：28×28、背景透明、
 * 图标 16px、颜色 `--on-2`。前两条里「透明底」正好是 M3 [IconButton] 的默认
 * （底色只出现在 hover/按压态，对应的就是原型 `:1137` 的 `:hover`），
 * 「16px」落到 [IconSizes.Label]（该档的语义就是「按钮、横幅、工具栏」，原型 16×12），
 * 色调则是 `IconButton` 默认的 `onSurfaceVariant`。
 * 方框用 [ComposerToolIconSize]（28dp ← 原型 `.tools .ibtn`），**不是** `IconButton`
 * 默认的 48dp：48dp 的触摸目标对触屏友好，但在这排六颗里会变成「一排散开的点」，
 * 与原型紧凑排（28px 框 + 2px gap）差得很远；桌面壳里同族图标按钮都照原型收窄
 * （`ui/desktop/DesktopConversationsPane.kt` 的 34dp、`DesktopChrome.kt`
 * 的 18dp），这一处随它们。指针命中区仍是整个 28dp 方框。
 *
 * [enabled] 是「图片 / 文件 / 文件夹三颗吃 `canSendMedia`、离线时置灰但不隐藏」那条
 * 原型语义（`prototype/relay.html:1587`）的接口位；桌面目前一律可点（这条差异已登记，
 * 见报告 d-chat 一节）。
 */
@Composable
private fun ToolIcon(
    painter: Painter,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(ComposerToolIconSize)) {
        Icon(painter, contentDescription = label, modifier = Modifier.size(IconSizes.Label))
    }
}

@Composable
public fun Button2(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    val bg = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest
    val fg = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .background(bg, RelayShapes.small)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = LocalAppSpacing.current.lg, vertical = LocalAppSpacing.current.osm7),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm6),
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(IconSizes.Inline))
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

/**
 * 输入区上方的表情面板。
 *
 * 尺寸照原型：面板 270px 宽、8 列网格、单格 30px 高 17px 字，底部一行
 * 「表情按 UTF-8 计 · 多数占 4 字节」。那行提示不是装饰 —— 表情是普通文本，
 * 每多一个就多吃 2–4 字节，而输入区有 800 B 的单播预算，用户有权在点之前知道代价。
 *
 * 列哪些表情在 [EMOJI_PALETTE]（`ui/components`），手机的表情托盘用同一份列表 ——
 * 鼠标格子小、手指格子大，但"有哪 39 个"两边必须一致。
 *
 * 本函数只剩**容器**一件事：定位、偏移、点外部收起。内容全在 [EmojiPanel]，
 * 两者分开是为了让内容能被参考图钉住（见 [EmojiPanel] 的 KDoc）。
 */
@Composable
public fun EmojiPopup(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    // 默认 Popup 点外面收起，所以 onDismissRequest 只需要把开关关掉。
    Popup(alignment = Alignment.BottomStart, offset = IntOffset(0, -8), onDismissRequest = onDismiss) {
        EmojiPanel(onPick = onPick)
    }
}

/**
 * 表情托盘的**内容本体**：一张浮起卡片，里面是 [EMOJI_PALETTE] 的 8 列网格 +
 * 底部那行字节提示。
 *
 * 为什么把它从 [EmojiPopup] 里抽出来：`Popup` 是 Layoutlib 合成不了内容的容器 ——
 * 参考图里只会得到一张**空白圆角矩形**（W2 的 `NodesRowMenuPhonePreview` 就是这样
 * 拍出一张纯白方块的）。抽出内容本体之后，门禁可以直接渲染这一层，把"39 个表情 /
 * 8 列 / 那行提示"钉住。**生产行为一字不变**：[EmojiPopup] 仍然是
 * `Popup(...) { EmojiPanel(onPick) }`，桌面输入区走的还是 [EmojiPopup]。
 *
 * 这一层**证明**什么：网格的列数、单格尺寸、字距、最后一行补位（`repeat(8 - row.size)`）、
 * 底部那行提示的文案与截断行为。这一层**不证明**什么：它相对输入卡片的位置与偏移、
 * 遮罩、点外部收起、键盘/焦点交互 —— 那些归 `Popup` 管，参考图照不到，
 * 已在 `design/fidelity-deviations.yaml` 登记。
 */
@Composable
public fun EmojiPanel(onPick: (String) -> Unit) {
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = RelayElevations.Floating),
        shape = RelayShapes.medium,
    ) {
        Column(Modifier.width(270.dp).padding(start = LocalAppSpacing.current.sm, end = LocalAppSpacing.current.sm, top = LocalAppSpacing.current.sm, bottom = LocalAppSpacing.current.sm6)) {
            EMOJI_PALETTE.chunked(8).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    row.forEach { item ->
                        Box(
                            Modifier
                                .weight(1f)
                                .height(IconSizes.Hero)
                                .clickable { onPick(item) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(item, fontSize = 17.sp)
                        }
                    }
                    // 最后一行只有 7 个。不补空位的话，那 7 个会被拉得比上面每格更宽，
                    // 网格看起来就散了。
                    repeat(8 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Text(
                stringResource(Res.string.desktop_composer_emoji_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = LocalAppSpacing.current.xxs, end = LocalAppSpacing.current.xxs, top = LocalAppSpacing.current.xs),
            )
        }
    }
}

/**
 * 这一档会走哪条路；真的发不出去时没有路可走，返回 null。
 *
 * 返回 null 不是「还没算出来」，而是一个判据：调用点看到 null 就该说
 * 「超出单条上限（%d 字节），请分两条发。」，而不是继续说路线。
 */
public fun ComposeByteMode.hint(): StringResource? = when (this) {
    ComposeByteMode.Udp -> Res.string.desktop_composer_route_udp
    ComposeByteMode.Tcp -> Res.string.desktop_composer_route_tcp
    ComposeByteMode.Over -> null
}
