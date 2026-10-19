// SPDX-License-Identifier: GPL-3.0-only

package app.relay.ui.desktop

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
import androidx.compose.runtime.remember
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

import app.relay.core.protocol.Protocol
import app.relay.feature.chat.ChatScreen
import app.relay.ui.components.EMOJI_PALETTE
import app.relay.ui.theme.IconSizes
import app.relay.ui.theme.LocalAppSpacing
import app.relay.ui.theme.RelayElevations
import app.relay.ui.theme.RelayShapes
import app.relay.generated.resources.Res
import app.relay.generated.resources.chat_attach
import app.relay.generated.resources.chat_byte_over
import app.relay.generated.resources.chat_emoji
import app.relay.generated.resources.chat_group_media_hint
import app.relay.generated.resources.chat_image
import app.relay.generated.resources.chat_nudge
import app.relay.generated.resources.chat_pk
import app.relay.generated.resources.chat_send
import app.relay.generated.resources.chat_toolbar_capture
import app.relay.generated.resources.desktop_composer_draft
import app.relay.generated.resources.desktop_composer_emoji_note
import app.relay.generated.resources.desktop_composer_placeholder
import app.relay.generated.resources.desktop_composer_resize
import app.relay.generated.resources.desktop_composer_route_tcp
import app.relay.generated.resources.desktop_composer_route_udp
import app.relay.generated.resources.desktop_composer_send_hint
import app.relay.generated.resources.hist_search_title
import app.relay.generated.resources.ic_attachment
import app.relay.generated.resources.ic_image
import app.relay.generated.resources.ic_nudge
import app.relay.generated.resources.ic_pk
import app.relay.generated.resources.ic_scissors
import app.relay.generated.resources.ic_search
import app.relay.generated.resources.ic_smile
import app.relay.generated.resources.ic_plus
import app.relay.generated.resources.chat_new_task_action
import androidx.compose.material3.Surface
import androidx.compose.ui.text.font.FontWeight
import app.relay.ui.components.PkPickerDialog
import app.relay.app.model.PeerUi
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

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
) {
    val density = LocalDensity.current
    var split by remember { mutableStateOf(ComposerSplit(ComposerSplit.DEFAULT_RATIO)) }
    var value by remember(peer.nodeId) {
        mutableStateOf(TextFieldValue(draft, TextRange(draft.length)))
    }
    var emojiOpen by remember { mutableStateOf(false) }
    var historySearchOpen by remember(peer.nodeId) { mutableStateOf(false) }
    var pkPickerOpen by remember(peer.nodeId) { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    val bytes = draftByteCount(value.text)
    val mode = composeByteMode(bytes)

    fun send() {
        val text = value.text.trim()
        if (text.isEmpty()) return
        controller.sendToConversation(peer.nodeId, text)
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
                                IconButton(onClick = { emojiOpen = true }, modifier = Modifier.size(ComposerToolIconSize)) {
                                    Icon(painterResource(Res.drawable.ic_smile), contentDescription = stringResource(Res.string.chat_emoji), modifier = Modifier.size(IconSizes.Label))
                                }
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
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                    modifier = Modifier
                                        .padding(start = LocalAppSpacing.current.sm)
                                        .clickable {
                                            val prefix = "@dsh "
                                            val currentText = value.text
                                            if (!currentText.startsWith(prefix)) {
                                                val newText = "$prefix$currentText"
                                                value = value.copy(text = newText, selection = TextRange(newText.length))
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
                        onValueChange = {
                            value = it
                            onDraftChange(it.text)
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
                            Button2(stringResource(Res.string.chat_send), Icons.AutoMirrored.Outlined.Send, enabled = value.text.isNotBlank() && !mode.overLimit, onClick = { send() })
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
            onSelectHit = { _ ->
                historySearchOpen = false
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
