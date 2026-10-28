// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.conversations

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.kapp.tidings.ui.components.PressableSurface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kapp.tidings.ui.components.KindBadge
import com.kapp.tidings.ui.components.PeerAvatar
import com.kapp.tidings.ui.components.StatusDot
import com.kapp.tidings.ui.components.badgeCountText
import com.kapp.tidings.ui.components.relativeTime
import com.kapp.tidings.ui.theme.LocalAppSpacing
import com.kapp.tidings.ui.theme.LocalCardShape
import com.kapp.tidings.ui.theme.relayCardShadow
import org.jetbrains.compose.resources.stringResource
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.chat_group_online
import com.kapp.tidings.generated.resources.conv_action_delete
import com.kapp.tidings.generated.resources.conv_action_mute
import com.kapp.tidings.generated.resources.conv_action_pin
import com.kapp.tidings.generated.resources.conv_action_unmute
import com.kapp.tidings.generated.resources.conv_action_unpin
import com.kapp.tidings.generated.resources.conv_no_messages
import com.kapp.tidings.generated.resources.conv_preview_draft
import com.kapp.tidings.generated.resources.conv_preview_mine
import com.kapp.tidings.generated.resources.conv_preview_sender
import com.kapp.tidings.generated.resources.conv_pending_count
import com.kapp.tidings.generated.resources.conv_row_more_actions
import com.kapp.tidings.generated.resources.conv_row_pinned
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.Icon
import org.jetbrains.compose.resources.painterResource
import com.kapp.tidings.domain.WorkspaceUnitGroupUi
import com.kapp.tidings.domain.WorkspaceUnitUi
import com.kapp.tidings.generated.resources.conv_inbox_ws_badge_unknown_kind
import com.kapp.tidings.generated.resources.conv_inbox_ws_pending_pill
import com.kapp.tidings.generated.resources.conv_inbox_ws_row_agent_none
import com.kapp.tidings.generated.resources.conv_inbox_ws_row_agent_sessions
import com.kapp.tidings.generated.resources.conv_inbox_ws_row_unnamed
import com.kapp.tidings.generated.resources.conv_inbox_ws_row_ws_sessions
import com.kapp.tidings.generated.resources.ic_bot
import com.kapp.tidings.generated.resources.ic_terminal
import com.kapp.tidings.ui.theme.IconSizes
import com.kapp.tidings.domain.model.ConversationUi
import com.kapp.tidings.ui.theme.SurfaceTiers

/**
 * 会话行之间的分隔线（**内缩**，不与行的圆角相撞）。
 *
 * ## 为什么加它 —— 这不是"原型有"，是产品判断
 *
 * 原型 \`.sidelist{gap:1px}\` + \`.li{border-radius:12px}\` 其实**没有分割线**，
 * 它靠 hover / 选中态分层。那是 Web 列表的写法：滚动区短、鼠标随手可见。
 * 而桌面端这一列窄、行多、要长时间扫读 —— 一条极淡的线能把"行的基线"立住。
 * 之前删除它时只看了原型，忽略了使用场景，是判断失误。
 *
 * ## 为什么是**内缩**而不是通栏
 *
 * 行已经是 12dp 圆角，通栏线会顶到圆角上，视觉上像把行切成两半。
 * 内缩到**文字左沿**（= 水平内边距 + 头像宽 + 图文间距）之后，线与文字对齐 ——
 * 这是 macOS / iOS 列表分隔的常规做法，也和"圆角行 + 间距"这种分组方式相容。
 *
 * 厚度不写字面量：\`HorizontalDivider\` 的默认 \`thickness\` 就是 1dp（\`DividerDefaults.Thickness\`），
 * 自己写 \`height(1.dp)\` 只会多一处会漂移的尺寸。
 */
@Composable
internal fun ConversationListDivider() {
    HorizontalDivider(
        // 内缩量用**一个** token（`xxxl`=48，约等于「水平内边距 9 + 头像 32 + 图文间距 10」的 51）。
        //
        // 不要写成 `osm9 + xxl + md10`：那会把**头像宽度**塞进一个间距表达式里，
        // 而 `:designScreenDiffGate` 扫的是「间距语境的实参」—— 它会在该屏的代码取值集合里
        // 记下一个间距 32，而原型里没有 `padding:…32…`，于是判成"代码写了原型没有的值"。
        // 那个报警是对的：**尺寸不是间距**，混在算术里就说不清是哪一个。
        modifier = Modifier.padding(start = LocalAppSpacing.current.xxxl),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}


/**
 * 长按会话行弹出的动作菜单体：置顶 / 静音 / 删除 —— **不含 Popup**。
 *
 * public 只有一个理由：截图门禁在 `:androidApp` 的 `screenshotTest` 源集里，是**另一个
 * 模块**，Kotlin 的 `internal` 对它不可见；而 Layoutlib 只合成 `Popup` 的容器、不合成
 * 内容（`Dialog` 族能完整组合，见 `ScreenshotPreviewsDialogs.kt` 文件头）。菜单体因此
 * 必须是一个能被门禁**直接调用**的独立 Composable，否则「长按菜单」永远没有参考图 ——
 * 它此前零帧正是这个原因（旧的 KDoc 说"抽出来就能渲染"，但它是 private，门禁够不到）。
 *
 * 参数接**原语**（`isPinned` / `isMuted`）而不是 `ConversationUi`：门禁帧不该为了拍一张
 * 菜单体去构造整个会话模型（AGENTS §2.6「适配器：接原语，不接壳的模型类型」）。
 *
 * 不是对外 API：仓库外调用点为零，除 [ConversationRowMenu] 与截图门禁外不要新增调用。
 */
@Composable
public fun ConversationActionsMenuContent(
    isPinned: Boolean,
    isMuted: Boolean,
    onTogglePin: (() -> Unit)?,
    onToggleMute: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    if (onTogglePin != null) {
        DropdownMenuItem(
            text = {
                Text(
                    stringResource(
                        if (isPinned) Res.string.conv_action_unpin else Res.string.conv_action_pin,
                    ),
                )
            },
            onClick = onTogglePin,
        )
    }
    if (onToggleMute != null) {
        DropdownMenuItem(
            text = {
                Text(
                    stringResource(
                        if (isMuted) Res.string.conv_action_unmute else Res.string.conv_action_mute,
                    ),
                )
            },
            onClick = onToggleMute,
        )
    }
    if (onDelete != null) {
        DropdownMenuItem(
            text = {
                Text(
                    text = stringResource(Res.string.conv_action_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            },
            onClick = onDelete,
        )
    }
}

/**
 * 长按会话行弹出的菜单：Popup 外壳 + [ConversationActionsMenuContent] 菜单体。
 *
 * 这里只剩"把菜单体放进 Popup"和收起菜单两件事，**不含任何文案** —— 文案与项序都在
 * 菜单体里，两种渲染路径（真实 Popup / 门禁帧）因此不可能各说各话。
 */
@Composable
private fun ConversationRowMenu(
    row: ConversationUi,
    offset: DpOffset = DpOffset.Zero,
    onDismiss: () -> Unit,
    onTogglePin: (() -> Unit)?,
    onToggleMute: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    DropdownMenu(
        expanded = true,
        offset = offset,
        onDismissRequest = onDismiss,
    ) {
        ConversationActionsMenuContent(
            isPinned = row.isPinned,
            isMuted = row.isMuted,
            onTogglePin = onTogglePin,
            onToggleMute = onToggleMute,
            onDelete = onDelete,
        )
    }
}

/**
 * 静音行预览的"弱一档"**不用透明度表达**。
 *
 * 真身：先前是 onSurfaceVariant.copy(alpha = 0.6f)，压在本行底色上实算只有
 * **2.66:1（亮）/ 3.21:1（暗）** —— 12sp 小字低于 4.5:1，低视力与强光下读不出
 * "这个静音会话说了什么"。而"静音"只是弱化优先级，不该牺牲可读性。
 *
 * 现在改用**字重**这个非颜色通道：非静音 Medium、静音 Normal，两档颜色都是
 * onSurfaceVariant（亮 6.47:1 / 暗 6.43:1，达标）。弱化仍看得出来，但文字始终可读；
 * 也不再依赖"透明色叠在会变化的行底色上"这种不可预算的写法。
 */
private val MutedPreviewWeight = FontWeight.Normal
private val ActivePreviewWeight = FontWeight.Medium

/**
 * 行内的「置顶」小标识。
 *
 * `design/icon` 与 drawable 资源里都**没有**已映射的图钉图标（查过
 * `composeResources/drawable/` 与 design-icon-baseline：无 pin 一族），而
 * AGENTS.md 的 icon 消费率棘轮禁止为此新引一枚库图标 —— 所以按行内文字 tag 落地，
 * 形状与 `KindBadge` 的弱化态同一族（`--surface-2` 底 + `--on-2` 字，原型 `.tag.muted`）。
 */
@Composable
private fun PinnedTag() {
    val sp = LocalAppSpacing.current
    Surface(color = SurfaceTiers.Raised, shape = CircleShape) {
        Text(
            text = stringResource(Res.string.conv_row_pinned),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // 纵向 1.5px snap 到 `sp.xxs`（2dp）—— 与 `UnassignedCountTag` 同一个口径，
            // 不为半像素在行组件里新开一处 `.dp` 字面量（欠债棘轮只许降）。
            modifier = Modifier.padding(horizontal = sp.sm6, vertical = sp.xxs),
        )
    }
}

@Composable
internal fun ConversationRow(
    row: ConversationUi,
    now: Long,
    onOpenChat: (String) -> Unit,
    /**
     * 桌面两栏的选中态。手机端没有这个状态（列表独占一屏），用默认值即可。
     *
     * 这个参数是**合并两份实现时唯一需要新增的东西** —— 它原来是桌面那份
     * `public fun ConversationRow(conv, selected, now, onSelect)` 的全部差异。
     */
    selected: Boolean = false,
    /**
     * 长按会话行弹出的动作。**三个都为 null 时整份菜单不出现**（不画空菜单）。
     *
     * 为什么挂在**长按**上：行本身只有一个目标（打开会话），再挂一个常驻 ⋯ 按钮
     * 会挤掉标题宽度（原型已经因为 394px 宽难看清把行内 ⋯ 撤过一次）。
     * 长按是移动端的标准手势，桌面也吃同一个实现（§2.6：行是内容，两端共用）。
     */
    onTogglePin: (() -> Unit)? = null,
    onToggleMute: (() -> Unit)? = null,
    onDeleteConversation: (() -> Unit)? = null,
    /**
     * 群会话的「删除并退出群聊」：删除确认框里的第二个动作（见
     * [DeleteConversationConfirmDialog] 的 `onConfirmAndLeave`）。
     *
     * 为什么删群会话要顺带提供退群：删掉的只是本机记录，群还在，下一条群消息会把
     * 会话重建 —— 用户以为「删了就没了」，其实只是清空了历史。要真正离开得走群信息
     * 里的「退出群聊」；把两者放进同一个确认框，用户才不用先删再找。
     * null = 这个壳没有退群能力（单聊 / 预览）。
     */
    onLeaveGroup: (() -> Unit)? = null,
    /**
     * 群会话预览该署的**发送者显示名**；null = 不署名（私聊、最后一条是自己说的、
     * 或解析不出 —— 名字由域层解析，见 `RelayUiState.previewSenderNameOf`，UI 不自拼）。
     */
    previewSenderName: String? = null,
    /**
     * 这个会话的草稿预览（**已经拼好、带「草稿」标记**的最终文案）；null = 没有草稿。
     *
     * 为什么由调用方给而不是在这里查：草稿表活在**壳**里（手机 App.kt、桌面 DesktopShell），
     * 列表组件拿不到；而"草稿长什么样"属于这一屏的呈现规则，所以壳只给值、这里只画。
     *
     * 真身：草稿保留（切走再回来不丢）早就做了，但列表上没有任何痕迹 ——
     * 用户写到一半切出去，那行仍显示上一条消息，会话一多就只能逐个点进去找。
     */
    draftPreview: String? = null,
) {
    val sp = LocalAppSpacing.current
    var menuOpen by remember { mutableStateOf(false) }
    // 菜单里的「删除」只把确认框立起来；真正的删除回调必须过用户确认 ——
    // 一次点击直接删、没有回头路，是「长按菜单」这种易误触入口上最坏的形状。
    var confirmDelete by remember { mutableStateOf(false) }
    val hasMenu = onTogglePin != null || onToggleMute != null || onDeleteConversation != null
    val haptics = LocalHapticFeedback.current

    // 桌面有鼠标，所以要有 hover；手机的指针永远不在"悬停"状态，`hoverable` 无害。
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    // 底色优先级：选中 > hover > 透明。
    //
    // **这是全仓库唯一一处定义"会话行长什么样"的地方。** 它此前有两份平行实现
    // （手机是 16dp 圆角白卡 + 投影，桌面是整幅宽平铺行 + 40% 透明度分割线），
    // 于是同一个应用里同一个列表长两个样 —— 桌面那条分割线正是"没做圆角行"的补偿。
    //
    // 现在按原型 `.li` 收口：
    //   `.li{padding:7px 9px;border-radius:12px;min-height:52px}`（prototype/relay.html:734）
    //   `.li:hover{background:var(--surface-2)}`
    //   `.li[aria-selected="true"]{background:var(--surface);box-shadow:0 0 0 1px var(--hair)}`
    // 圆角行 + 行间间距本身就承担了分组，**所以不要分割线**。
    //
    // 底色**刻意偏离原型一处**：原型选中态是 `background:var(--surface)` + 1px `--hair` 环，
    // 而本 App 的 `background`(#FAFAF8) 与 `surface`(#FFFFFF) 在亮色下只差 **1.02:1** ——
    // 照抄等于"亮色主题下选中态看不见"（独立验证者按真实色值算过）。
    // 所以：选中用 `surfaceVariant` 实底、hover 用它的一半，让两者有可见层次，
    // 同时**不用 primaryContainer**（那是主色实底，会与未读徽标抢注意力）。
    // 三档用**三个不同的表面层级**，而不是同一层加不同透明度 ——
    // 原型给的正是三个变量：`--surface` / `--surface-2` / `--surface-3`（:1083）。
    // 之前全用 `surfaceVariant` + alpha，是因为没注意到主题里 `surfaceContainerHigh` 早就定义好了
    // （Color.kt 亮 #F3F2EC / 暗 #262827），只是**没有任何界面用它**。
    val background = when {
        selected -> MaterialTheme.colorScheme.surfaceVariant
        hovered -> SurfaceTiers.Raised
        else -> Color.Transparent
    }
    // 键盘焦点：桌面用户按 Tab 必须能**走进列表**、并看见焦点在哪。
    //
    // 此前行上只有 hover 与 combinedClickable：鼠标一切正常，键盘用户按 Tab 直接跳过整个
    // 列表（全仓唯一的 .focusable() 是桌面壳根 Column，用来收字母快捷键），于是"打开一个会话"
    // 在桌面上只能靠鼠标。这里补两件：可聚焦 + 一圈明确的焦点环（primary 2dp，
    // 与"选中"的 1dp outline 环刻意不同 —— 两个状态可能同时成立，颜色/粗细都要分得开）。
    val density = LocalDensity.current
    var touchOffset by remember { mutableStateOf(DpOffset.Zero) }
    var focused by remember { mutableStateOf(false) }
    val modifier = Modifier
        .fillMaxWidth()
        .clip(MaterialTheme.shapes.medium)
        .background(background)
        .onFocusChanged { focused = it.isFocused }
        .focusable()
        .hoverable(interaction)
        .then(
            // 选中态的 1px 内描边（原型那圈 `0 0 0 1px var(--hair)`）。
            // 用 drawBehind 而不是 border：border 会被外层的 clip 裁掉一半。
            //
            // 颜色必须在**这里**取出：drawBehind 的 lambda 不在组合上下文里，
            // 在里面读 MaterialTheme 会编译失败（@Composable invocations ...）。
            //
            // 环用 `outline` 而不是原型的 `--hair`（= outlineVariant）：
            // outlineVariant 对 surface 只有 1.17:1（亮）/ 1.4:1（暗），低于非文本对比下限。
            //
            // `cornerRadius` **必须显式传**：`DrawScope.drawRoundRect` 的默认是
            // `CornerRadius.Zero`，不传就画成方角，再被外层的 `clip(shapes.medium)` 裁掉四角 ——
            // 结果是"看起来没有描边"（这一条是独立验证者发现的代码级缺陷，不是取舍）。
            if (selected || focused) {
                val ring = if (focused) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                }
                val width = if (focused) 2.dp else 1.dp
                val radius = with(LocalDensity.current) { 12.dp.toPx() }
                Modifier.drawBehind {
                    drawRoundRect(
                        color = ring,
                        cornerRadius = CornerRadius(radius, radius),
                        style = Stroke(width = width.toPx()),
                    )
                }
            } else {
                Modifier
            },
        )
        // 捕获按下时的横向触控偏移，供菜单对齐触发区域
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                touchOffset = DpOffset(
                    x = (down.position.x / density.density).dp,
                    y = 0.dp,
                )
            }
        }
        // 长按开菜单；单击仍是打开会话。combinedClickable 必须给 onClick ——
        // 这里的 onClick 本来就有真动作（打开），不是死按钮。
        //
        // onLongClickLabel：长按是唯一入口但屏幕上**没有任何可见的 affordance**，
        // 读屏用户拿不到「这里还有个菜单」的任何线索（无障碍死路）。给长按一个
        // 语义标签（「更多操作」），TalkBack / VoiceOver 会把它读成行的自定义动作。
        // 触感反馈：长按弹出的是**瞬态菜单**，没有触感确认时用户不知道按没按上
        //（松手才弹出、无按下态延续），LongPress 震动就是那一下"收到"。
        .combinedClickable(
            onClick = { onOpenChat(row.convId) },
            onLongClickLabel = if (hasMenu) stringResource(Res.string.conv_row_more_actions) else null,
            onLongClick = if (hasMenu) {
                {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuOpen = true
                }
            } else null,
        )

    Box(
        modifier = modifier.defaultMinSize(minHeight = sp.xxxl),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = sp.osm9, vertical = sp.osm7),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(sp.md10),
        ) {
        // 头像 32dp + 圆角 12（原型 `.li .av{width:32px;height:32px;border-radius:12px}`）。
        // 会话行是原型里**唯一**用方形头像的地方。
        Box {
            PeerAvatar(
                name = row.title,
                isAgent = row.isAgent,
                size = sp.xxl,
                isGroup = row.isGroup,
                avatar = row.avatar,
                shape = MaterialTheme.shapes.medium,
            )
            // 群不画那个圆点：它表达的是"这一个对端在不在"，而群是一群人。
            // 硬画的结果是每个群都显示离线（`ConversationUi.online` 对群恒 false），
            // 用户会以为群废了。成员数在下面那行用字说清楚。
            if (!row.isGroup) {
                // 原型 `.li .av .p{width:10px;height:10px;border:2px solid var(--bg)}`
                // —— 点外面有一圈底色，把它从头像上"抠"出来。
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(IconSizes.Badge)
                        .background(MaterialTheme.colorScheme.background, CircleShape)
                        .padding(sp.xxs),
                    contentAlignment = Alignment.Center,
                ) {
                    StatusDot(row.online, size = IconSizes.Badge - sp.xxs - sp.xxs)
                }
            }
        }

        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                // 原型 `.li .t1{gap:5px}` —— 标题与 KindBadge 之间 5px（`spacing.oxs5`）。
                horizontalArrangement = Arrangement.spacedBy(sp.oxs5),
            ) {
                Text(
                    text = row.title,
                    // 原型 `.li .t1{font-size:14px;font-weight:600}`。
                    // 合并前手机用 bodyLarge(16sp)/Medium(500)、桌面用 bodyMedium(14sp)/Medium，
                    // 两边都不对：字号与字重都要按原型走。
                    // 字重没有 token 组（`tokens.weights` 为 null），而 `FontWeight.SemiBold`
                    // 在生产里已用 126 次 —— 用它就是本仓惯例。
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                KindBadge(row.isAgent)
                // 置顶/静音过去只在长按菜单里可见，行上没有任何痕迹 —— 用户钉完一条，
                // 过两天忘了它为什么一直排在最上面。置顶给一枚行内 tag（位置在标题之后，
                // 不占右列的"时间/未读"格）；静音不挂 tag，它改的是下面预览那一行的分量。
                if (row.isPinned) {
                    PinnedTag()
                }
            }
            // 「我说的」加前缀：否则用户在列表里看到一句熟悉的话，分不清是哪边说的。
            // 前缀只加在**真有消息**那一档；「还没聊过」那句不是消息，加前缀会说反。
            // 群会话里别人说的署发送者（名字由域层用与 convTitle 同一个解析器给出，
            // UI 只拼装）—— 群里多人说话，一句正文分不清是谁说的。名字解析不出
            //（最后一条不在内存窗口 / 旧端帧没署名）就退回不带署名的正文，不编名字。
            // 草稿优先：它比"上一条消息"更接近用户此刻的状态，而且必须**说清这是一条草稿** ——
            // 只把正文换掉会让它看起来像"我发过这句话"（用户会以为消息已经发出去了）。
            val previewText = draftPreview?.let { stringResource(Res.string.conv_preview_draft, it) }
                ?: row.preview?.let { text ->
                when {
                    row.isGroup && !row.lastMessageIsMine && previewSenderName != null ->
                        stringResource(Res.string.conv_preview_sender, previewSenderName, text)
                    row.lastMessageIsMine -> stringResource(Res.string.conv_preview_mine, text)
                    else -> text
                }
            } ?: stringResource(Res.string.conv_no_messages)
            Text(
                text = previewText,
                style = MaterialTheme.typography.bodySmall,
                // 静音的视觉语义 = **弱一档**，但只用字重（见 MutedPreviewWeight 的 KDoc）：
                // 颜色两档相同，保证小字始终 ≥4.5:1。
                // 草稿用主色（一眼看出"这行是我的未完成输入"，而不是一条已发消息）；
                // 其余仍用次级色 + 字重区分静音。
                color = if (draftPreview != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = if (row.isMuted) MutedPreviewWeight else ActivePreviewWeight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = sp.xxs),
            )
            if (row.isGroup) {
                // 「2/5 人在线」而不是一个绿点：群行上真正影响决策的是"现在发出去
                // 有没有人立刻看到"，那是一个数字，不是一个布尔。
                Text(
                    text = stringResource(
                        Res.string.chat_group_online,
                        row.memberOnlineCount,
                        row.memberCount,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = sp.xxs),
                )
            }
        }

        // 原型 `.li .rt{display:grid;gap:3px}` —— 右列（时间/未读/待发）之间 3px。
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(sp.oxs3)) {
            Text(
                // 只用**最近一条消息**的时间：没聊过的会话（lastMessageAt == 0）整格留空。
                // 不能用 row.at —— 它在没消息时会退回对端 lastSeenAt，局域网发现一刷新
                // 就变成"刚刚"，正是用户反馈的"没聊过的会话一直顶在最上面"的一半。
                text = if (row.lastMessageAt > 0) relativeTime(row.lastMessageAt, now) else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (row.unread > 0) {
                Surface(color = MaterialTheme.colorScheme.primary, shape = CircleShape) {
                    Text(
                        // ≥100 折成「99+」：徽标回答的是"有没有没看的"，不是精确计数器，
                        // 三位数会把药丸撑变形。规则与页签徽标共用一份（badgeCountText），
                        // 否则同一个未读数在两处显示成两个样子。
                        text = badgeCountText(row.unread),
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = sp.sm, vertical = sp.xxs),
                    )
                }
            }
            if (row.pendingCount > 0) {
                Text(
                    // 待发条数是用户可见文案，走资源而不是内联中文。
                    text = stringResource(Res.string.conv_pending_count, row.pendingCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (menuOpen) {
        ConversationRowMenu(
            row = row,
            offset = touchOffset,
            onDismiss = { menuOpen = false },
            onTogglePin = onTogglePin?.let { act -> { menuOpen = false; act() } },
            onToggleMute = onToggleMute?.let { act -> { menuOpen = false; act() } },
            // 删除项不再直接执行：先把确认框立起来（见 confirmDelete 的注释）。
            onDelete = onDeleteConversation?.let { act -> { menuOpen = false; confirmDelete = true } },
        )
    }
}

if (confirmDelete) {
    DeleteConversationConfirmDialog(
        onDismiss = { confirmDelete = false },
        onConfirm = {
            confirmDelete = false
            onDeleteConversation?.invoke()
        },
        isGroup = row.isGroup,
        // 顺序：先退群（本机把群去掉），再清本机记录 —— 反过来的话，退群时写进去的
        // 那条提示会被同一次删除清掉，看上去像「没退成功」。
        onConfirmAndLeave = if (row.isGroup && onLeaveGroup != null && onDeleteConversation != null) {
            {
                confirmDelete = false
                onLeaveGroup()
                onDeleteConversation()
            }
        } else {
            null
        },
    )
}
}

/**
 * 消息列表中工作区的混排单行卡片（不展开内部 session，对齐会话行高度与样式）。
 * 点击整行直接下钻到工作区详情。
 */
@Composable
internal fun WorkspaceFeedRow(
    unit: WorkspaceUnitUi,
    group: WorkspaceUnitGroupUi,
    now: Long,
    onOpenWorkspaceUnit: (WorkspaceUnitUi) -> Unit,
) {
    val sp = LocalAppSpacing.current
    PressableSurface(
        onClick = { onOpenWorkspaceUnit(unit) },
        color = MaterialTheme.colorScheme.surface,
        // 与 `ConversationRow` 同一张卡、同一口径（原型 `.card` 的 `--r-card`，见上）。
        shape = LocalCardShape.current,
        modifier = Modifier
            .fillMaxWidth()
            // 与会话行同一张卡、同一投影口径（见 `relayCardShadow`）。
            .relayCardShadow(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = sp.lg, vertical = sp.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(sp.lg),
        ) {
            Box {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(if (unit.isWorkspace) Res.drawable.ic_terminal else Res.drawable.ic_bot),
                        contentDescription = null,
                        modifier = Modifier.size(IconSizes.Title),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(14.dp)
                        .background(MaterialTheme.colorScheme.surface, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    StatusDot(group.online)
                }
            }

            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(sp.sm),
                ) {
                    val title = when {
                        unit.isWorkspace -> unit.wsTitle?.takeIf { it.isNotBlank() }
                            ?: stringResource(Res.string.conv_inbox_ws_row_unnamed)
                        else -> unit.agentKind
                            ?: stringResource(Res.string.conv_inbox_ws_badge_unknown_kind)
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    KindBadge(isAgent = true)
                }
                val subtitle = if (unit.isWorkspace) {
                    stringResource(
                        Res.string.conv_inbox_ws_row_ws_sessions,
                        unit.agentKind ?: group.nodeName,
                        unit.sessionCount,
                    )
                } else if (unit.sessionCount > 0) {
                    stringResource(Res.string.conv_inbox_ws_row_agent_sessions, unit.sessionCount)
                } else {
                    stringResource(Res.string.conv_inbox_ws_row_agent_none)
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = sp.xs),
                )
            }

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(sp.sm),
            ) {
                Text(
                    text = if (unit.lastActivityAt > 0) relativeTime(unit.lastActivityAt, now) else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (unit.pendingCount > 0) {
                    Surface(
                        color = MaterialTheme.colorScheme.error,
                        shape = CircleShape,
                    ) {
                        Text(
                            text = stringResource(Res.string.conv_inbox_ws_pending_pill, unit.pendingCount),
                            color = MaterialTheme.colorScheme.onError,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = sp.sm, vertical = sp.xxs),
                        )
                    }
                }
            }
        }
    }
}

