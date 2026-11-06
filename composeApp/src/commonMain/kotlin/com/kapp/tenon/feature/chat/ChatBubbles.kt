// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.chat

import com.kapp.tenon.domain.model.BubbleContentKind
import com.kapp.tenon.domain.model.ChatRowKind
import com.kapp.tenon.domain.model.ChatRowKinds

import com.kapp.tenon.generated.resources.chat_agent_request_grant
import com.kapp.tenon.generated.resources.chat_abort_no_task
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kapp.tenon.domain.MediaKind
import com.kapp.tenon.domain.mediaKindOf
import com.kapp.tenon.domain.RelayUiState
import com.kapp.tenon.core.protocol.PkGames
import com.kapp.tenon.core.protocol.RpsValues
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.platform.ReceivedFileActions
import com.kapp.tenon.ui.components.PkCardView
import com.kapp.tenon.ui.components.PeerAvatar
import com.kapp.tenon.ui.components.ThinkingBlock
import com.kapp.tenon.ui.theme.IconSizes
import com.kapp.tenon.ui.theme.LocalAppSpacing
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import com.kapp.tenon.domain.model.DeliveryState
import com.kapp.tenon.domain.model.MessageUi
import com.kapp.tenon.domain.model.NoticeKind
import com.kapp.tenon.domain.model.NoticeDetail
import com.kapp.tenon.domain.model.hasVisibleText
import com.kapp.tenon.domain.model.TransferState
import com.kapp.tenon.generated.resources.chat_copy
import com.kapp.tenon.generated.resources.chat_delivery_resend
import com.kapp.tenon.generated.resources.chat_forward
import com.kapp.tenon.generated.resources.chat_group_members_changed
import com.kapp.tenon.generated.resources.chat_group_member_removed
import com.kapp.tenon.generated.resources.chat_agent_delegated_by_tag
import com.kapp.tenon.generated.resources.chat_agent_not_authorized
import com.kapp.tenon.generated.resources.chat_group_member_added
import com.kapp.tenon.generated.resources.chat_image
import com.kapp.tenon.generated.resources.chat_pk_dice_outcome
import com.kapp.tenon.generated.resources.chat_pk_dice_title
import com.kapp.tenon.generated.resources.chat_pk_rock
import com.kapp.tenon.generated.resources.chat_pk_paper
import com.kapp.tenon.generated.resources.chat_pk_scissors
import com.kapp.tenon.generated.resources.chat_pk_rps
import com.kapp.tenon.generated.resources.common_name_list_separator
import com.kapp.tenon.generated.resources.chat_group_removed
import com.kapp.tenon.generated.resources.chat_group_renamed
import com.kapp.tenon.generated.resources.chat_nudge_peer
import com.kapp.tenon.generated.resources.chat_nudge_self
import com.kapp.tenon.generated.resources.chat_delete_local
import com.kapp.tenon.generated.resources.chat_recall
import com.kapp.tenon.generated.resources.chat_recall_peer
import com.kapp.tenon.generated.resources.chat_recall_re_edit
import com.kapp.tenon.generated.resources.chat_recall_self
import com.kapp.tenon.generated.resources.chat_reply_action
import com.kapp.tenon.generated.resources.common_copied
import com.kapp.tenon.generated.resources.common_unknown_member
import com.kapp.tenon.generated.resources.ic_check_double
import com.kapp.tenon.generated.resources.ic_clock_pending
import com.kapp.tenon.generated.resources.screen_card_title
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.kapp.tenon.ui.theme.RelayShapes
import com.kapp.tenon.ui.theme.ShapeRoles
import com.kapp.tenon.ui.theme.ComponentSizes
import com.kapp.tenon.ui.components.AppButtonSize
import com.kapp.tenon.ui.components.AppButtonTone
import com.kapp.tenon.ui.components.AppTextButton

/**
 * 一条消息气泡外层 `Surface` 的形状。
 *
 * ## 自定义卡为什么不能用文字气泡的圆角
 *
 * 文件 / 图片 / PK 这三类卡片**自己画边框和底色**：`FileCardView`、
 * `InlineImagePreview`、`PkCardView` 用的都是 `RelayShapes.medium`（12dp）。
 * 外层这层 `Surface` 对它们来说只是容器：`color = Color.Transparent`、
 * `border = null`。但 M3 的 `Surface` 会在 modifier 链末尾**无条件**
 * `.clip(shape)`（material3 1.9.0 `Surface.kt` 的私有 `surface()`），
 * 于是这个"透明容器"仍然会把子内容裁成自己的形状。
 *
 * 真机事故：外层沿用文字气泡的 8/16dp 非对称圆角时，三个 16dp 的角比内层
 * 12dp 更圆，内层边框的圆角弧线全被裁掉，只剩互不相接的横竖短线；
 * 唯有 topStart（8dp < 12dp）留得住 —— 看上去就是「只有左上角是圆的，
 * 另外三个角是直角断口」。所以自定义卡的外层必须用**与内层完全相同**
 * 的形状，让裁剪路径与描边路径重合。
 *
 * 抽成纯函数是为了能被单测钉住：截图门禁能发现"弧线没了"，但说不清是
 * 哪一层的形状裁掉了另一层。
 */
internal fun chatBubbleShape(
    isCustomCard: Boolean,
    mine: Boolean,
    hasPrevSender: Boolean,
    hasNextSender: Boolean,
): Shape {
    if (isCustomCard) return RelayShapes.medium
    val topStartRadius = if (!mine) 6.dp else 18.dp
    val topEndRadius = if (mine) 6.dp else 18.dp
    val bottomStartRadius = if (!mine && hasNextSender) 6.dp else 18.dp
    val bottomEndRadius = if (mine && hasNextSender) 6.dp else 18.dp
    return RoundedCornerShape(
        topStart = topStartRadius,
        topEnd = topEndRadius,
        bottomEnd = bottomEndRadius,
        bottomStart = bottomStartRadius,
    )
}

/**
 * 气泡内「引用源消息」那条半透明横条的形状。
 *
 * 外层气泡的 `Surface` 会无条件 `.clip(bubbleShape)`（见 [chatBubbleShape]）。
 * 文字气泡有 `sp.md`(12dp) 水平 / `sp.sm`(8dp) 垂直内边距，引用卡缩进得够深，
 * 它的 8dp 圆角整段落在外层 16dp 裁剪弧之内 ⇒ 保持 `RelayShapes.small`。
 *
 * 但自定义卡（文件 / 图片 / PK）的 `bubblePad` 是 `0.dp`，引用卡又 `fillMaxWidth()`，
 * 四边与外层齐平：外层裁剪 12dp、引用卡自己只有 8dp ⇒ 四个角的弧线被外层切掉，
 * 又是一处「只有某几个角是圆的」。这时引用卡必须**逐字沿用外层形状**，
 * 让裁剪路径与它自己的绘制路径重合。回复一条消息再附上文件即可复现
 * （`ChatMessageList` 对每种内容都传 `quotedMessage`，不限文字）。
 *
 * 同样抽成纯函数，好让单测能钉住「自定义卡里两层形状必须同一对象」。
 */
internal fun quotedCardShape(isCustomCard: Boolean): Shape =
    if (isCustomCard) RelayShapes.medium else RelayShapes.small

/**
 * 一条消息气泡。
 *
 * [onRetry] 是失败消息唯一的出口：null 表示这个壳不能重发，红字就只是红字。
 * 它接的是**再发一条同样的内容**，不是服务端按 id 重投 —— core 的待发队列是进程内的
 * （见 `RelayUiState.toRestored` 的说明），重启后没人认得那条旧信封，
 * 与其假装能恢复，不如给一个用户自己能验证的行为。
 */
/**
 * 引用源消息的正文摘要生成。防止当源消息是 PK 游戏、文件或屏幕分享时留空。
 */
@Composable
internal fun quotedMessagePreview(quoted: MessageUi): String {
    if (quoted.text.isNotBlank()) return quoted.text
    return when (ChatRowKinds.bubbleContentOf(quoted)) {
        BubbleContentKind.File -> quoted.file?.name
            ?.ifBlank { stringResource(Res.string.chat_image) }
            ?: quoted.text
        BubbleContentKind.Pk -> {
            val pk = quoted.pk
            if (pk == null) {
                quoted.text
            } else if (pk.game == PkGames.DICE) {
                val dots = pk.value.toIntOrNull() ?: 1
                "[" + stringResource(Res.string.chat_pk_dice_title) + ": " +
                    stringResource(Res.string.chat_pk_dice_outcome, dots) + "]"
            } else {
                val label = when (pk.value) {
                    RpsValues.ROCK -> stringResource(Res.string.chat_pk_rock)
                    RpsValues.PAPER -> stringResource(Res.string.chat_pk_paper)
                    RpsValues.SCISSORS -> stringResource(Res.string.chat_pk_scissors)
                    else -> pk.value
                }
                "[" + stringResource(Res.string.chat_pk_rps) + ": " + label + "]"
            }
        }
        BubbleContentKind.Thinking,
        BubbleContentKind.Text -> if (ChatRowKinds.of(quoted) == ChatRowKind.Screen) {
            "[" + stringResource(Res.string.screen_card_title) + "]"
        } else {
            quoted.text
        }
    }
}

/**
 * 出站消息的极简投递指示对勾。
 */
@Composable
internal fun DeliveryCheckmark(
    delivery: DeliveryState?,
    isFileCompleted: Boolean = false,
    tint: Color? = null,
    modifier: Modifier = Modifier,
) {
    val activeTint = tint ?: MaterialTheme.colorScheme.primary
    val pendingTint = tint?.copy(alpha = 0.7f) ?: MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    val queuedTint = tint?.copy(alpha = 0.5f) ?: MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    if (isFileCompleted || delivery == DeliveryState.Sent) {
        Icon(
            painter = painterResource(Res.drawable.ic_check_double),
            contentDescription = null,
            tint = activeTint,
            modifier = modifier.size(IconSizes.Badge),
        )
    } else if (delivery == DeliveryState.Sending) {
        Icon(
            painter = painterResource(Res.drawable.ic_clock_pending),
            contentDescription = null,
            tint = pendingTint,
            modifier = modifier.size(IconSizes.Badge),
        )
    } else if (delivery == DeliveryState.Queued) {
        Icon(
            painter = painterResource(Res.drawable.ic_clock_pending),
            contentDescription = null,
            tint = queuedTint,
            modifier = modifier.size(IconSizes.Badge),
        )
    }
}

/**
 * `.msg .mav` 那颗 26dp 方头像要的三样东西。
 *
 * 原型把这三样都写死在演示数据里（`m.ini` 首字 / `m.c` 底色 / 是不是 agent），代码侧
 * 没有「发送者 → 颜色」这条规则，所以底色与图形走**已有的头像权威源**
 * （[PeerAvatar] → `AvatarPalette.resolveBg`），不另发明一套。
 */
internal data class ChatAvatar(val name: String, val avatar: Int?, val isAgent: Boolean)

@Composable
internal fun MessageBubble(
    message: MessageUi,
    bubbleFraction: Float,
    onAcceptTransfer: (String) -> Unit,
    onDeclineTransfer: (String) -> Unit,
    onCancelTransfer: ((String) -> Unit)? = null,
    onResumeTransfer: ((String) -> Unit)? = null,
    receivedFileActions: ReceivedFileActions? = null,
    onRetry: ((String) -> Unit)? = null,
    /** 撤回这条消息。null = 现在撤不了（过了 5 分钟窗口时也传 null，但下面三项仍然给值）。 */
    onRecall: (() -> Unit)? = null,
    /**
     * 撤回菜单项要不要**画出来**。
     *
     * 与 [onRecall] 分开：超过 5 分钟后点击入口没了，但这一项**仍然要显示**并置灰 ——
     * 直接消失会让用户以为是自己撤过了，或者以为这里本来就没有这个功能。
     */
    recallVisible: Boolean = false,
    /** 撤回菜单项的副标题：`mm:ss`，或已本地化的「超时」。 */
    recallMeta: String = "",
    /** 最后 10 秒转危险色。 */
    recallUrgent: Boolean = false,
    onReply: (() -> Unit)? = null,
    onForward: (() -> Unit)? = null,
    /** 只删本机这一条；null = 这个壳不提供删除。行为与「撤回」无关，见 `MessagePressActions`。 */
    onDelete: (() -> Unit)? = null,
    /** 群消息气泡上方署的发件人名。null = 不署（私聊，或这条是自己发的）。 */
    senderName: String? = null,
    /**
     * 气泡左侧那颗 26dp 方头像（原型 `.msg .mav`）。null = 不画。
     *
     * 原型里 `.mav` 只在消息带 `ini` 时出现，而演示数据里**只有别人发的**消息带 `ini`
     * —— 自己发的一条都没有。所以这里也只在非 [MessageUi.mine] 时给值。
     */
    senderAvatar: ChatAvatar? = null,
    /**
     * 长按发送者头像**直接 @ 这个人/这个 agent**。null = 不提供入口（私聊、自己发的）。
     *
     * 只有群里有意义：群里「对着头像说」是最自然的指人方式，比先敲 `@` 再从候选里找快。
     * token 由调用方从身份解析器取（[ChatAvatar] 同一次解析的产物），气泡不自己拼名字。
     */
    onMentionSender: (() -> Unit)? = null,
    quotedMessage: MessageUi? = null,
    /**
     * [quotedMessage] 的发送者显示名（调用方用身份解析器算好）。
     *
     * 为什么由调用方给：身份规则全在 `SenderIdentityContext` 那一层，气泡不该知道。
     * null = 资料没到 —— 引用卡如实说「未知成员」，**不把 senderId 前 8 位的裸 hex
     * 当人名印**。
     */
    quotedSenderName: String? = null,
    onScrollToQuoted: (() -> Unit)? = null,
    onImageClick: ((String) -> Unit)? = null,
    /**
     * 正文按 Markdown 渲染。
     *
     * 只有 **agent 发的**消息为真（判据在调用方：私聊看会话对端，群聊看发送者）。
     * 人发的消息保持纯文本 —— 用户打的 `*星号*` 就该原样显示，而不是被当成强调。
     */
    renderMarkdown: Boolean = false,
    /** 引用跳转时的平滑高亮脉冲。 */
    isHighlighted: Boolean = false,
    /** 连续发件编组：前一条消息来自同一发件人。 */
    hasPrevSender: Boolean = false,
    /** 连续发件编组：后一条消息来自同一发件人。 */
    hasNextSender: Boolean = false,
) {
    // 空气泡防线：正文只有看不见的字符、又没有文件 / PK / 思考块可画时，整条不画。
    // 判据只看"有没有东西可显示"，不看 delivery —— 待发/失败的文件消息有 file 卡，
    // 不会被这条误伤。
    if (!hasVisibleText(message.text) &&
        ChatRowKinds.bubbleContentOf(message) == BubbleContentKind.Text
    ) {
        return
    }
    val sp = LocalAppSpacing.current
    val card = message.file
    val previewImagePath = if (card != null && card.state == TransferState.Completed && mediaKindOf(card) == MediaKind.Image) {
        if (card.mine) card.localPaths.firstOrNull() else card.savedPaths.firstOrNull()
    } else null
    val isPureImage = previewImagePath != null
    val isFileCard = card != null && !isPureImage
    val isCustomCard = isPureImage || isFileCard || message.pk != null

    val bubbleColor = if (isCustomCard) {
        Color.Transparent
    } else if (message.mine) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    var menuOpen by remember { mutableStateOf(false) }
    // 「删除本机这条」是破坏性动作：先立确认框（与列表删除同一规矩）。
    var confirmDelete by remember { mutableStateOf(false) }
    // 长按菜单的触感确认（HapticFeedbackType.LongPress）：气泡的长按是唯一入口，
    // 没有触感时用户分不清「按住了」与「按了个寂寞」—— 尤其菜单弹出有延迟的时候。
    val haptics = LocalHapticFeedback.current
    val bubbleBorder: BorderStroke? = if (!isCustomCard && !message.mine) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    } else {
        null
    }

    val bubbleShape = chatBubbleShape(isCustomCard, message.mine, hasPrevSender, hasNextSender)

    val highlightColor by animateColorAsState(
        targetValue = if (isHighlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.28f) else Color.Transparent,
        animationSpec = tween(durationMillis = 350),
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!message.mine) {
            senderAvatar?.let { av ->
                if (!hasNextSender) {
                    val avatarContent: @Composable () -> Unit = {
                        PeerAvatar(
                            name = av.name,
                            isAgent = av.isAgent,
                            size = ComponentSizes.MessageAvatar,
                            avatar = av.avatar,
                            shape = ShapeRoles.Avatar,
                        )
                    }
                    if (onMentionSender != null) {
                        Box(
                            modifier = Modifier.pointerInput(av.name, onMentionSender) {
                                detectTapGestures(onLongPress = { onMentionSender() })
                            },
                        ) { avatarContent() }
                    } else {
                        avatarContent()
                    }
                } else {
                    // 与头像同宽：连续消息折叠头像后，文字仍要与上一行对齐。
                    Spacer(Modifier.width(ComponentSizes.MessageAvatar))
                }
                Spacer(Modifier.width(sp.sm))
            }
        }
        // 菜单要锚在气泡上，所以外面套一层 BoxWithConstraints；气泡上限见 bubbleMaxWidth。
        BoxWithConstraints {
            Column {
            if (!hasPrevSender) {
                senderName?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 3.dp, bottom = 3.dp),
                    )
                }
            }
            Surface(
                color = bubbleColor,
                shape = bubbleShape,
                border = bubbleBorder,
                modifier = Modifier
                    .bubbleMaxWidthForText(
                        availableWidth = this@BoxWithConstraints.maxWidth,
                        fraction = bubbleFraction,
                        text = message.text,
                    )
                    .background(highlightColor, shape = bubbleShape)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuOpen = true
                        },
                    ),
            ) {
                val bubblePad = if (isCustomCard) {
                    PaddingValues(0.dp)
                } else {
                    PaddingValues(horizontal = sp.lg14, vertical = sp.md10)
                }

                Column(Modifier.padding(bubblePad)) {
                // 被引用的源消息卡片：点击可跳至原消息
                if (quotedMessage != null) {
                    val quotedBody = quotedMessagePreview(quotedMessage)
                    val quotedSender = quotedSenderName
                        ?: stringResource(Res.string.common_unknown_member)
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                        // 自定义卡的 bubblePad=0：这一层与外层裁剪路径齐平，必须同形。
                        shape = quotedCardShape(isCustomCard),
                        border = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = sp.sm6)
                            .then(
                                if (onScrollToQuoted != null) Modifier.clickable(onClick = onScrollToQuoted) else Modifier
                            ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min)
                                .padding(end = sp.sm, top = sp.xs, bottom = sp.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .width(3.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = sp.sm),
                            ) {
                                Text(
                                    text = quotedSender,
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = quotedBody,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = sp.xxs),
                                )
                            }
                        }
                    }
                }
                val pk = message.pk
                if (isPureImage && previewImagePath != null) {
                    InlineImagePreview(
                        path = previewImagePath,
                        onClick = onImageClick?.let { click -> { click(previewImagePath) } },
                    )
                } else if (card != null) {
                    FileCardView(
                        card = card,
                        onAcceptTransfer = onAcceptTransfer,
                        onDeclineTransfer = onDeclineTransfer,
                        onCancel = onCancelTransfer?.let { cancel -> { cancel(card.transferId) } },
                        onResumeTransfer = onResumeTransfer,
                        receivedFileActions = receivedFileActions,
                        onImageClick = onImageClick,
                    )
                } else if (pk != null) {
                    PkCardView(pk = pk)
                } else {
                    // 跨模块 public 属性先落成局部量，否则 smart cast 不成立。
                    val reasoning = message.reasoning
                    if (!reasoning.isNullOrBlank()) {
                        ThinkingBlock(
                            reasoning = reasoning,
                            elapsedMs = message.reasoningElapsedMs,
                            isStreaming = false,
                            modifier = Modifier.padding(bottom = sp.xs),
                        )
                    }
                    if (message.text.isNotBlank()) {
                        if (renderMarkdown) {
                            MarkdownMessage(message.text)
                        } else {
                            // 气泡正文 16sp（bodyLarge）：聊天是主界面，14sp 的对话正文
                            // 是「demo 感」来源之一（iMessage/WeChat 17、Telegram 15~16）。
                            // 原型默认 --fs-bub:14px，但自带 font-lg 档就是 16px
                            // （relay.html:1617），16 在原型的设计空间内；偏差已登记。
                            Text(text = linkifyPlainText(message.text), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                if (!isCustomCard && message.mine && message.delivery == DeliveryState.Failed && onRetry != null) {
                    Row(
                        modifier = Modifier
                            .padding(top = sp.xs)
                            .align(Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppTextButton(
                            text = stringResource(Res.string.chat_delivery_resend),
                            size = AppButtonSize.Compact,
                            tone = AppButtonTone.Danger,
                            onClick = { onRetry(message.text) },
                        )
                    }
                }
            }
            }
            }
            if (confirmDelete) {
                DeleteChatMessageConfirmDialog(
                    onDismiss = { confirmDelete = false },
                    onConfirm = {
                        confirmDelete = false
                        onDelete?.invoke()
                    },
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                MessagePressActions(
                    copyText = message.text.ifBlank { message.file?.name.orEmpty() },
                    onReply = onReply?.let { { menuOpen = false; it() } },
                    onRecall = onRecall?.let { { menuOpen = false; it() } },
                    recallVisible = recallVisible,
                    recallMeta = recallMeta,
                    recallUrgent = recallUrgent,
                    onForward = onForward?.let { { menuOpen = false; it() } },
        onDelete = onDelete?.let { { menuOpen = false; confirmDelete = true } },
                )
            }
        }
    }
}

/**
 * 长按一条消息弹出的菜单的**内容本体**，从 `DropdownMenu` 里抽出来单独成函数。
 *
 * 为什么要抽：`DropdownMenu` 内部是 `Popup`，而 Layoutlib 只合成 Popup 的容器、
 * 不合成它的内容 ⇒ 被 `DropdownMenu` 包住的菜单在参考图里永远是空的，
 * `m04-msgmenu` 这一屏因此一直是台账里的 ⚠️「无本屏预览」。
 * 抽成 public 函数之后，帧可以直接渲染菜单体，菜单项于是第一次有了参考图。
 * 与 `ConversationsOverflowItems`（`feature/conversations/ConversationsScreen.kt`）
 * 和 `EmojiPanel`（`ui/desktop/DesktopComposer.kt`）是同一条先例。
 *
 * **函数名里不能出现「Menu」这个词**（原来叫 `MessageLongPressMenu`，已改）：
 * `tools/design-screen-diff.py` 用「原型渲染函数名的分词 ∩ 候选 Composable 名的分词」
 * 消歧「这一屏由哪个函数画」。原型这一屏叫 `screenMsgMenu`，分词 `{menu, msg}`；
 * 原名里**正好有一个 `menu`** ⇒ 得 1 分胜出，量具把「菜单体」当成整屏、只量那一个函数，
 * 真正画这一屏的 `ChatScreen` 反而落选。后果是**量具的切片变小**（`ChatScreen` 供的间距/
 * 圆角消失、被报成「只原型」），`design-screen-diff.py --check` 多出
 * `spacing|proto 新增 ['1.5','24']`。去掉 `Menu` 后交集为空、回落到整文件路径，逐键与基线一致。
 * 同一个坑的另一半见 `ConversationsOverflowItems`。
 *
 * 项序与出现条件照原型 `m04-msgmenu`（`screenMsgMenu`）：
 * 回复 / 撤回 / 复制 / 转发 —— 前三项按各自回调是否存在决定画不画，
 * 「复制」无条件（它只依赖文本，不依赖宿主给了什么回调）。
 * 原型那一版没有「删除」——删除在协议里没有对应操作，不做成按下去没反应的项。
 *
 * @param copyText 点「复制」时写进剪贴板的文本；空串时这一项按下去不做事。
 *   由调用方算好（气泡文本为空时退到文件名），菜单自己不读 `MessageUi`。
 */
@Composable
public fun MessagePressActions(
    copyText: String,
    /**
     * 只删本机这一条（不动协议、不通知对方）。
     *
     * 与「撤回」是两件事：撤回有时限、会告诉对方；删除是纯本机行为 ——
     * 「我自己不想再看到它」。没有这一项时，用户想清掉一条只有两条路：
     * 撤回（超时就没了）或清空整个会话（那是核弹）。
     */
    onDelete: (() -> Unit)? = null,
    onReply: (() -> Unit)? = null,
    onRecall: (() -> Unit)? = null,
    onForward: (() -> Unit)? = null,
    /** 见 [MessageBubble.recallVisible]：超时后仍要显示并置灰。 */
    recallVisible: Boolean = false,
    /** 撤回项副标题：`mm:ss` 或「超时」。 */
    recallMeta: String = "",
    /** 最后 10 秒转危险色。 */
    recallUrgent: Boolean = false,
    /**
     * 复制成功后收菜单。null = 由调用方自己管（菜单会留着，但项名已经翻成「已复制」）。
     *
     * 为什么要收：复制是**瞬态动作**，「点了有没有成」必须当场给反馈。
     * 此前点了没有任何变化 —— 用户不知道成没成，常重复点。
     */
    onDismiss: (() -> Unit)? = null,
) {
    val clipboardManager = LocalClipboardManager.current
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    // 复制反馈：点完把这一项的字翻成「已复制」，短暂停留后收起菜单。
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(COPY_FEEDBACK_MS)
            onDismiss?.invoke()
        }
    }
    if (onReply != null) {
        DropdownMenuItem(
            text = { Text(stringResource(Res.string.chat_reply_action)) },
            onClick = onReply,
        )
    }
    if (recallVisible) {
        // 剩余时间写在项名右边（原型菜单也是这个位置）。超时后**不隐藏**、只置灰 ——
        // 隐藏等于"不解释为什么不能撤"，而用户最需要知道的正是这一点。
        val metaColor = if (recallUrgent) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
        DropdownMenuItem(
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm),
                ) {
                    Text(stringResource(Res.string.chat_recall))
                    if (recallMeta.isNotEmpty()) {
                        Text(
                            text = recallMeta,
                            style = MaterialTheme.typography.labelSmall,
                            color = metaColor,
                        )
                    }
                }
            },
            enabled = onRecall != null,
            onClick = { onRecall?.invoke() },
        )
    }
    // 没有可复制的文本就**不画这一项**（此前它一直在，点了什么都不发生 —— 死项）。
    if (copyText.isNotEmpty()) {
        DropdownMenuItem(
            text = {
                Text(
                    stringResource(
                        if (copied) Res.string.common_copied else Res.string.chat_copy,
                    ),
                )
            },
            onClick = {
                clipboardManager.setText(AnnotatedString(copyText))
                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                copied = true
            },
        )
    }
    if (onForward != null) {
        DropdownMenuItem(
            text = { Text(stringResource(Res.string.chat_forward)) },
            onClick = onForward,
        )
    }
    if (onDelete != null) {
        DropdownMenuItem(
            text = {
                Text(
                    text = stringResource(Res.string.chat_delete_local),
                    color = MaterialTheme.colorScheme.error,
                )
            },
            onClick = {
                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                onDelete()
            },
        )
    }
}

/**
 * 时间线上的一行系统提示（撤回、戳一碰）。
 *
 * 居中的小灰字，不画气泡、不带时间戳：它不是谁说的话，是「这里发生过一件事」。
 * 文案全部来自资源，控制器只给出 [NoticeKind]（见 `RelayUiState.NoticeKind`）。
 *
 * 原型 `.notice{align-self:center;font-size:11px;color:var(--on-2);background:var(--surface-2);
 * padding:3px 10px;border-radius:var(--r-chip);margin:4px 0}` —— 它是一颗**胶囊**，不是
 * 裸文字。`.daysep`（日期分隔条）用的是同一条形状，只差没有 `gap`。
 */
@Composable
internal fun NoticeLine(
    kind: NoticeKind,
    /**
     * 通知细节（谁把谁加/移了）。**null 或没有可用细节时退回那句中性文案** ——
     * 老库里的记录没有这一列，不能因为它们就把这条通知画不出来。
     */
    detail: NoticeDetail? = null,
    /**
     * nodeId → 展示名。查不到时调用方负责退回 8 位短 id（与成员面板同一取舍）。
     *
     * 传函数而不是在这里查：`SenderIdentityContext` 在 `ChatScreen` 那一层，
     * 而这个组件不该知道身份解析的全部规则。
     */
    displayNameOf: ((String) -> String)? = null,
    /**
     * 「申请授权」的动作；null = 不提供入口。
     *
     * 只在 [NoticeKind.AgentNotAuthorized] 上会出现，而且**入口的有无由调用方决定** ——
     * 组件不该知道「这台设备是不是 agent 宿主」「node 起没起来」这些事。
     */
    onRequestGrant: (() -> Unit)? = null,
    /** 自己撤回消息后的快速「重新编辑」出口。 */
    onReEdit: (() -> Unit)? = null,
) {
    val sp = LocalAppSpacing.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        Surface(
            // `--surface-2` = #F0EFE9 = surfaceVariant；`--r-chip` = 999 = 药丸，半透明柔和贴合聊天流背景。
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            shape = CircleShape,
            modifier = Modifier.padding(vertical = sp.xs),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val hasAction = (kind == NoticeKind.AgentNotAuthorized && onRequestGrant != null) ||
                    (kind == NoticeKind.RecalledBySelf && onReEdit != null)
                Text(
                    text = noticeText(kind, detail, displayNameOf),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        start = sp.md10,
                        end = if (hasAction) sp.xs else sp.md10,
                        top = sp.oxs3,
                        bottom = sp.oxs3,
                    ),
                )
                // 「申请授权」只在无权限那一档上出现：用户读到「没有执行」的同时就有一个动作，
                // 而不是让他自己去某个页面上找入口。
                if (kind == NoticeKind.AgentNotAuthorized && onRequestGrant != null) {
                    AppTextButton(
                        text = stringResource(Res.string.chat_agent_request_grant),
                        onClick = onRequestGrant,
                        size = AppButtonSize.Small,
                    )
                }
                if (kind == NoticeKind.RecalledBySelf && onReEdit != null) {
                    AppTextButton(
                        text = stringResource(Res.string.chat_recall_re_edit),
                        onClick = onReEdit,
                        size = AppButtonSize.Small,
                    )
                }
            }
        }
    }
}

/**
 * 机器回执系统行（快捷指令回执 / 模板配置等）：
 * 纯文本居中药丸胶囊（.notice），与 [NoticeLine] 视觉规范逐值一致。
 */
@Composable
internal fun NoticeLine(
    text: String,
    modifier: Modifier = Modifier,
) {
    val sp = LocalAppSpacing.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            shape = CircleShape,
            modifier = Modifier.padding(vertical = sp.xs),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    horizontal = sp.md10,
                    vertical = sp.oxs3,
                ),
            )
        }
    }
}

/**
 * 一条通知要显示的文字。
 *
 * **有细节就说具体的，没有就退回中性文案** ——「%1$s邀请%2$s加入群聊」比「群成员有变化」
 * 多说的是「谁做的、涉及谁」，而这两件事正是用户想知道的。
 *
 * 细节缺席有两种合法来源：老的落库记录（那一列是后加的），以及真的无从判断（对端只
 * 发了一个新快照、我们又拿不到旧成员表）。两种都该安静地退回中性文案，而不是报错或留空。
 */
@Composable
private fun noticeText(
    kind: NoticeKind,
    detail: NoticeDetail?,
    displayNameOf: ((String) -> String)?,
): String {
    if (kind == NoticeKind.GroupMembersChanged && detail != null && displayNameOf != null) {
        val actor = detail.actor?.takeIf { it.isNotBlank() }
        // 「谁做的」不知道时，宁可退回中性文案，也不要编一个主语 ——
        // 「某人邀请你加入群聊」这种半截话比不说更让人困惑。
        if (actor != null) {
            val separator = stringResource(Res.string.common_name_list_separator)
            val names = { ids: List<String> -> ids.joinToString(separator) { displayNameOf(it) } }
            when {
                detail.added.isNotEmpty() -> return stringResource(
                    Res.string.chat_group_member_added,
                    displayNameOf(actor),
                    names(detail.added),
                )
                detail.removed.isNotEmpty() -> return stringResource(
                    Res.string.chat_group_member_removed,
                    displayNameOf(actor),
                    names(detail.removed),
                )
            }
        }
    }
    // **这条必须带参数**：它说的是「哪一个 agent 没执行」。
    //
    // 真机第一次跑时这里返回的是无参资源，界面上原样显示了 `%1$s 没有执行：…`。
    // 抬头看代码完全正常（枚举分支、资源名都对），只有**跑起来看屏幕**才看得见 ——
    // 正是本仓那条「测试绿 ≠ 测到了」的又一例。
    if (kind == NoticeKind.AgentNotAuthorized) {
        // 名字拿不到就退回中性文案，不显示半截占位符。
        val agentName = detail?.actor?.takeIf { it.isNotBlank() }
        if (agentName != null) {
            return stringResource(Res.string.chat_agent_not_authorized, agentName)
        }
    }
    if (kind == NoticeKind.AgentDelegatedByTag) {
        // T-2c：任务按算力标签委派给了远端节点 —— 说清「哪个 agent、按什么标签、去了哪台」。
        val agentName = detail?.actor?.takeIf { it.isNotBlank() }
        val routing = detail?.detail?.takeIf { it.isNotBlank() }
        if (agentName != null && routing != null) {
            return stringResource(Res.string.chat_agent_delegated_by_tag, agentName, routing)
        }
    }
    return stringResource(
        when (kind) {
            NoticeKind.RecalledByPeer -> Res.string.chat_recall_peer
            NoticeKind.RecalledBySelf -> Res.string.chat_recall_self
            NoticeKind.NudgedByPeer -> Res.string.chat_nudge_peer
            NoticeKind.NudgedBySelf -> Res.string.chat_nudge_self
            NoticeKind.GroupMembersChanged -> Res.string.chat_group_members_changed
            // @ 到了 agent 但没有执行权：如实说是「没执行」，不给用户留悬念。
            NoticeKind.AgentNotAuthorized -> Res.string.chat_agent_not_authorized
            NoticeKind.AgentDelegatedByTag -> Res.string.chat_agent_delegated_by_tag
            NoticeKind.GroupRenamed -> Res.string.chat_group_renamed
            NoticeKind.RemovedFromGroup -> Res.string.chat_group_removed
            // 发了「停」但本机没在跑：从前完全静默，用户以为停了（真机踩过）。
            NoticeKind.AbortNoRunningTask -> Res.string.chat_abort_no_task
        },
    )
}
/**
 * 把纯文本里的 http(s) 链接标成可点（LinkAnnotation.Url）。
 *
 * 为什么单独一条：人发的消息**故意**不渲染 Markdown（用户打的 `*星号*` 该原样显示），
 * 但「不渲染 Markdown」不等于「不识别链接」。此前整条纯文本连 URL 都不认：
 * 对端看到一串地址却点不动 —— 而局域网/隧道地址正是这个应用最常见的粘贴内容。
 *
 * 抽成纯函数：判据（认哪些、切在哪）可被单测钉住，不依赖任何平台能力。
 */
internal fun linkifyPlainText(text: String): AnnotatedString = buildAnnotatedString {
    append(text)
    plainTextUrls(text).forEach { (start, end) ->
        addLink(LinkAnnotation.Url(text.substring(start, end)), start, end)
    }
}

/**
 * 纯文本里认得的链接区间（`[start, end)`）。**只认 http/https** —— 其它 scheme
 * （`file:` / `intent:` 之类）交给 Markdown 那一侧的策略，纯文本不给人一条能点开本地的路。
 */
/** 复制反馈在菜单里停留多久（够看清「已复制」，又不至于让人等）。 */
private const val COPY_FEEDBACK_MS = 900L

internal fun plainTextUrls(text: String): List<Pair<Int, Int>> =
    PLAIN_TEXT_URL_REGEX.findAll(text).map { it.range.first to it.range.last + 1 }.toList()

// URL 的终止符里包含中文标点，但源码里不能出现 CJK 字符（CJK 门禁按字面量计），
// 所以用 Unicode 转义表达：\u3001 、 \u3002 。 \uFF0C ， \uFF1B ； \uFF09 ）
private val PLAIN_TEXT_URL_REGEX = Regex("https?://[^\\s<>\"'\\u3001\\u3002\\uFF0C\\uFF1B\\uFF09]+")
