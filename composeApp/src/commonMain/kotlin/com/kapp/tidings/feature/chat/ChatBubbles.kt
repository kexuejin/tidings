// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.chat

import com.kapp.tidings.generated.resources.chat_agent_request_grant
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
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
import com.kapp.tidings.domain.MediaKind
import com.kapp.tidings.domain.mediaKindOf
import com.kapp.tidings.domain.RelayUiState
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.platform.ReceivedFileActions
import com.kapp.tidings.ui.components.PkCardView
import com.kapp.tidings.ui.components.PeerAvatar
import com.kapp.tidings.ui.components.ThinkingBlock
import com.kapp.tidings.ui.components.formatBytes
import com.kapp.tidings.ui.components.formatClock
import com.kapp.tidings.ui.theme.IconSizes
import com.kapp.tidings.ui.theme.LocalAppSpacing
import com.kapp.tidings.ui.theme.accents
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import com.kapp.tidings.domain.model.DeliveryState
import com.kapp.tidings.domain.model.FileCardUi
import com.kapp.tidings.domain.model.MessageUi
import com.kapp.tidings.domain.model.NoticeKind
import com.kapp.tidings.domain.model.NoticeDetail
import com.kapp.tidings.domain.model.hasVisibleText
import com.kapp.tidings.domain.model.TransferState
import com.kapp.tidings.generated.resources.chat_copy
import com.kapp.tidings.generated.resources.chat_delivery_resend
import com.kapp.tidings.generated.resources.chat_forward
import com.kapp.tidings.generated.resources.chat_group_members_changed
import com.kapp.tidings.generated.resources.chat_group_member_removed
import com.kapp.tidings.generated.resources.chat_agent_not_authorized
import com.kapp.tidings.generated.resources.chat_group_member_added
import com.kapp.tidings.generated.resources.common_name_list_separator
import com.kapp.tidings.generated.resources.chat_group_removed
import com.kapp.tidings.generated.resources.chat_group_renamed
import com.kapp.tidings.generated.resources.chat_nudge_peer
import com.kapp.tidings.generated.resources.chat_nudge_self
import com.kapp.tidings.generated.resources.chat_delete_local
import com.kapp.tidings.generated.resources.chat_recall
import com.kapp.tidings.generated.resources.chat_recall_peer
import com.kapp.tidings.generated.resources.chat_recall_self
import com.kapp.tidings.generated.resources.chat_reply_action
import com.kapp.tidings.generated.resources.common_cancel
import com.kapp.tidings.generated.resources.common_copied
import com.kapp.tidings.generated.resources.common_unknown
import com.kapp.tidings.generated.resources.common_unknown_member
import com.kapp.tidings.generated.resources.chat_file_send_failed_hint
import com.kapp.tidings.generated.resources.file_accept
import com.kapp.tidings.generated.resources.file_bubble_count
import com.kapp.tidings.generated.resources.file_decline
import com.kapp.tidings.generated.resources.file_open
import com.kapp.tidings.generated.resources.file_saved_to
import com.kapp.tidings.generated.resources.file_reason_offer_expired
import com.kapp.tidings.generated.resources.file_reason_peer_offline
import com.kapp.tidings.generated.resources.file_reason_hash_mismatch
import com.kapp.tidings.generated.resources.file_reason_size_mismatch
import com.kapp.tidings.generated.resources.file_reason_write_error
import com.kapp.tidings.generated.resources.file_reason_path_escape
import com.kapp.tidings.generated.resources.file_reason_busy
import com.kapp.tidings.generated.resources.file_reason_canceled
import com.kapp.tidings.generated.resources.file_reason_not_found
import com.kapp.tidings.generated.resources.file_reason_bad_frame
import com.kapp.tidings.generated.resources.file_reason_socket_error
import com.kapp.tidings.generated.resources.file_reason_timeout
import com.kapp.tidings.generated.resources.file_share
import com.kapp.tidings.generated.resources.file_state_active_in
import com.kapp.tidings.generated.resources.file_state_active_out
import com.kapp.tidings.generated.resources.file_state_completed_in
import com.kapp.tidings.generated.resources.file_state_completed_out
import com.kapp.tidings.generated.resources.file_state_declined
import com.kapp.tidings.generated.resources.file_state_failed
import com.kapp.tidings.generated.resources.file_state_offered_in
import com.kapp.tidings.generated.resources.file_state_offered_out
import com.kapp.tidings.generated.resources.file_table_hide_text
import com.kapp.tidings.generated.resources.file_table_show_text
import com.kapp.tidings.generated.resources.file_table_truncated
import com.kapp.tidings.generated.resources.ic_close
import com.kapp.tidings.generated.resources.ic_file
import com.kapp.tidings.generated.resources.ic_folder
import com.kapp.tidings.generated.resources.ic_image
import com.kapp.tidings.generated.resources.ic_speaker
import com.kapp.tidings.generated.resources.r2_chat_folder_progress
import com.kapp.tidings.generated.resources.r2_chat_folder_receiving
import com.kapp.tidings.generated.resources.r2_chat_folder_sending
import com.kapp.tidings.generated.resources.transfer_action_resume
import com.kapp.tidings.generated.resources.transfer_folder_count
import com.kapp.tidings.generated.resources.transfer_resume_hint
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.kapp.tidings.ui.theme.ShapeRoles
import com.kapp.tidings.ui.components.AppButton
import com.kapp.tidings.ui.components.AppTextButton
import com.kapp.tidings.ui.components.AppButtonSize

/**
 * 一条消息气泡。
 *
 * [onRetry] 是失败消息唯一的出口：null 表示这个壳不能重发，红字就只是红字。
 * 它接的是**再发一条同样的内容**，不是服务端按 id 重投 —— core 的待发队列是进程内的
 * （见 `RelayUiState.toRestored` 的说明），重启后没人认得那条旧信封，
 * 与其假装能恢复，不如给一个用户自己能验证的行为。
 */
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
) {
    // 空气泡防线：正文只有看不见的字符、又没有文件 / PK / 思考块可画时，整条不画。
    // 判据只看"有没有东西可显示"，不看 delivery —— 待发/失败的文件消息有 file 卡，
    // 不会被这条误伤。
    if (!hasVisibleText(message.text) &&
        message.file == null &&
        message.pk == null &&
        message.reasoning.isNullOrBlank()
    ) {
        return
    }
    val sp = LocalAppSpacing.current
    val bubbleColor = if (message.mine) {
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
    // 原型 `.bub{border:1px solid var(--hair)}`，`.msg.me .bub{border-color:transparent}`。
    // `--hair` 在 `:root` 里是 rgba(0,0,0,.07)；`outlineVariant` 即它的
    // 真值（半透明黑），不再是「借一个同灰族的近似」。`--surface-3` 是另一个**填充** token，
    // 落在 `MaterialTheme.accents.surface3`（见 ui/theme/Accents.kt 的语义拆分说明）。
    val bubbleBorder = if (message.mine) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    // 原型 `.bub{border-radius:var(--r-bubble);border-top-left-radius:6px}` +
    // `.msg.me .bub{border-top-right-radius:6px}`：**说话方那一侧**的上角收成 6px，
    // 其余三角是 `--r-bubble`（= shapes.medium 12）。
    val bubbleShape = if (message.mine) {
        RoundedCornerShape(topStart = 12.dp, topEnd = 6.dp, bottomEnd = 12.dp, bottomStart = 12.dp)
    } else {
        RoundedCornerShape(topStart = 6.dp, topEnd = 12.dp, bottomEnd = 12.dp, bottomStart = 12.dp)
    }
    // 原型 `.msg{display:flex;gap:8px}` + `.msg .mav{margin-top:auto}`：头像与气泡列并排
    // 8px，头像贴的是这一行的**下沿**，不是上沿。
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        senderAvatar?.let { av ->
            // 长按头像 = 直接 @：入口挂在**头像**上而不是气泡上 —— 气泡的长按已经是
            // 「回复 / 撤回 / 复制 / 转发」那个菜单，两个手势落在同一个目标上会打架。
            // 包一层 Box 而不是给 PeerAvatar 加 modifier：头像组件是全仓共用的，为一个
            // 手势往它身上开口子，下一个使用点就会照着这个口子长出别的自由度。
            val avatarContent: @Composable () -> Unit = {
                PeerAvatar(
                    name = av.name,
                    isAgent = av.isAgent,
                    size = 26.dp,
                    avatar = av.avatar,
                    shape = ShapeRoles.Avatar,
                )
            }
            if (onMentionSender != null) {
                // 只用长按手势，**不挂 onClick**：combinedClickable 必须给一个 onClick，
                // 而"点了没反应"的空实现正是 DeadButtonGuard 盯的那种死按钮（同一个文件
                // 已有气泡自己那一处，再多一处就会被判红）。detectTapGestures 只注册
                // onLongPress，单击在这个头像上本来就是"没有动作"。
                Box(
                    modifier = Modifier.pointerInput(av.name, onMentionSender) {
                        detectTapGestures(onLongPress = { onMentionSender() })
                    },
                ) { avatarContent() }
            } else {
                avatarContent()
            }
            Spacer(Modifier.width(sp.sm))
        }
        // 菜单要锚在气泡上，所以外面套一层 BoxWithConstraints；气泡上限见 bubbleMaxWidth。
        BoxWithConstraints {
            Column {
            // 原型 `.msg .sender{font-size:11px;color:var(--on-2);margin:0 0 3px 3px}`：
            // 署名在气泡**外面**、气泡上方，左缩进 3、下边距 3。
            senderName?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 3.dp, bottom = 3.dp),
                )
            }
            Surface(
                color = bubbleColor,
                shape = bubbleShape,
                border = bubbleBorder,
                modifier = Modifier
                    // `maxWidth` 要显式取外层 BoxWithConstraints 的接收者：`Column` 与
                    // `BoxWithConstraints` 都带 `@LayoutScopeMarker`，嵌套后外层被遮蔽。
                    // 长文本按内容放开（见 bubbleMaxWidthForText 的 KDoc：比例本身与原型一致，
                    // 但 80 字以上的中文在 271dp 里要折 6~7 行，读起来太费劲）。
                    .bubbleMaxWidthForText(
                        availableWidth = this@BoxWithConstraints.maxWidth,
                        fraction = bubbleFraction,
                        text = message.text,
                    )
                    .combinedClickable(
                        onClick = {},
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuOpen = true
                        },
                    ),
            ) {
                // 原型 `.bub{padding:8px 12px}`；纯文件气泡 `.bub.bf{padding:5px}`
                // （文件卡自带内边距，外面只留一圈）。
                val bubblePad = if (message.file != null || message.pk != null) {
                    PaddingValues(sp.sm6)
                } else {
                    PaddingValues(horizontal = sp.md, vertical = sp.sm)
                }
                Column(Modifier.padding(bubblePad)) {
                // 被引用的源消息卡片：点击可跳至原消息
                if (quotedMessage != null) {
                    val quotedBody = quotedMessage.text.ifBlank { quotedMessage.file?.name.orEmpty() }
                    // 发送者名走身份解析（调用方算好传进来）；资料没到就如实说
                    // 「未知成员」，不再印 8 位 hex。
                    val quotedSender = quotedSenderName
                        ?: stringResource(Res.string.common_unknown_member)
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = sp.sm6)
                            .then(
                                if (onScrollToQuoted != null) Modifier.clickable(onClick = onScrollToQuoted) else Modifier
                            ),
                    ) {
                        Text(
                            text = "$quotedSender: $quotedBody",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = sp.sm, vertical = sp.xs),
                        )
                    }
                }
                val card = message.file
                val pk = message.pk
                if (card != null) {
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
                            // 人发的消息**不渲染 Markdown**（用户打的星号就该原样显示），
                            // 但 URL 仍然要能点 —— 这两件事不是一回事。局域网场景里贴地址很常见，
                            // 之前整条纯文本连识别都没有，对端只能看着。
                            Text(text = linkifyPlainText(message.text), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Row(
                    modifier = Modifier.padding(top = sp.xs),
                    horizontalArrangement = Arrangement.spacedBy(sp.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = formatClock(message.at),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (message.mine) {
                        // 只有真的知道投递状态时才显示。文件气泡的 delivery 是 null（它由传输
                        // 事件驱动，不走 msg 的 ACK 状态），上一版这里落到 `common_unknown`，
                        // 于是真机上文件卡片旁边挂着两个字「未知」—— 不知道就别说话。
                        message.delivery?.let { delivery ->
                            // 「已排队」在原型里用的是 --on-queued-c（浅色 #5C3D1E / 深色
                            // #F5E6D3），那对颜色正好等于本主题的 onSecondaryContainer：
                            // 原型的 queued 容器色就是 secondaryContainer（#F5E6D3 / #5C3D1E）。
                            // 之前这里落回中灰 onSurfaceVariant，浅色下和已送达的 primary 混成一片，
                            // 看不出「这条还在排队」。顺带一提：这对颜色对页面底色分别是
                            // 8.01:1 与 12.6:1，小字也够，不是拿对比度换的。
                            val tint = when (delivery) {
                                DeliveryState.Failed -> MaterialTheme.colorScheme.error
                                DeliveryState.Queued -> MaterialTheme.colorScheme.onSecondaryContainer
                                // 「发送中」用与「已排队」同一档的弱色调：它既不是失败（不该红），
                                // 也不是既成事实（不该用 primary 那种"已送达"的确定感）。
                                DeliveryState.Sending -> MaterialTheme.colorScheme.onSecondaryContainer
                                else -> MaterialTheme.colorScheme.primary
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                // 已送达与已排队各有各的图标语义（design-spec: delivered / queued），
                                // 失败和撤回只有文案，不硬凑图标。
                                delivery.stateIcon()?.let { icon ->
                                    Icon(
                                        painter = painterResource(icon),
                                        contentDescription = null,
                                        tint = tint,
                                        modifier = Modifier.size(13.dp),
                                    )
                                }
                                Text(
                                    text = delivery.label(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = tint,
                                )
                            }
                        }
                        // 「发送失败」是唯一一种用户还能做点什么的状态，所以重发出口只挂在这里。
                        // 入站消息没有投递态（[MessageUi.delivery] 只对自己发出的有意义），
                        // 没有 onRetry 的壳也不画 —— 一个按下去没反应的按钮比没有按钮更糟。
                        if (message.mine && message.delivery == DeliveryState.Failed && onRetry != null) {
                            TextButton(
                                onClick = { onRetry(message.text) },
                                contentPadding = PaddingValues(horizontal = sp.sm),
                            ) {
                                Text(
                                    text = stringResource(Res.string.chat_delivery_resend),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
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
            onClick = onDelete,
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
) {
    val sp = LocalAppSpacing.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        Surface(
            // `--surface-2` = #F0EFE9 = surfaceVariant；`--r-chip` = 999 = 药丸。
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = CircleShape,
            modifier = Modifier.padding(vertical = sp.xs),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = noticeText(kind, detail, displayNameOf),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = sp.md10, end = sp.xs, top = 3.dp, bottom = 3.dp),
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
            }
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
    return stringResource(
        when (kind) {
            NoticeKind.RecalledByPeer -> Res.string.chat_recall_peer
            NoticeKind.RecalledBySelf -> Res.string.chat_recall_self
            NoticeKind.NudgedByPeer -> Res.string.chat_nudge_peer
            NoticeKind.NudgedBySelf -> Res.string.chat_nudge_self
            NoticeKind.GroupMembersChanged -> Res.string.chat_group_members_changed
            // @ 到了 agent 但没有执行权：如实说是「没执行」，不给用户留悬念。
            NoticeKind.AgentNotAuthorized -> Res.string.chat_agent_not_authorized
            NoticeKind.GroupRenamed -> Res.string.chat_group_renamed
            NoticeKind.RemovedFromGroup -> Res.string.chat_group_removed
        },
    )
}
/**
 * 文件气泡。
 *
 * 与文字气泡共用同一条消息流：一次 transfer 只产生一条消息，进度事件原地更新它，
 * 于是「发文件」和「发消息」在会话里是同一种东西，不需要第二套列表，也不会两处对不上。
 */
@Composable
internal fun FileCardView(
    card: FileCardUi,
    onAcceptTransfer: (String) -> Unit,
    onDeclineTransfer: (String) -> Unit,
    /**
     * 撤回这次传输。null = 这个壳没有撤回能力，按钮就不画。
     *
     * 只在 [FileCardUi.mine] 为真时才用得上：core 的 `TransferManager.cancelOutgoing`
     * 动的是**出站**表，收文件那一侧没有取消接口，给收件人一个取消按钮只会点了没反应。
     */
    onCancel: (() -> Unit)? = null,
    /** 续传失败的入站文件。 */
    onResumeTransfer: ((String) -> Unit)? = null,
    /**
     * 打开 / 分享**收到的**文件。null = 本平台没有这条出口，那两个按钮都不画。
     *
     * 只对收到的文件有意义：它们落在应用内部目录，用户没有别的办法把它取出来。
     */
    receivedFileActions: ReceivedFileActions? = null,
    onImageClick: ((String) -> Unit)? = null,
) {
    val sp = LocalAppSpacing.current
    // 原型 `.filecard{display:flex;gap:10px;align-items:center;padding:9px 10px;
    // background:var(--surface);border:1px solid var(--hair);border-radius:12px;
    // min-width:196px}`（`.win .filecard` 是 210）。文件夹走的是同一个 `.filecard`
    // （`folderCard`），区别只在 `.fi` 里那颗图标 —— 所以「文件夹」这三个字不再另画
    // 一枚徽章：下面 `.fs` 那行本来就有「N 个文件 · 大小」，徽章是同一件事的第二个说法。
    Row(
        modifier = Modifier
            .widthIn(min = 196.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .padding(horizontal = sp.md10, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(sp.md10),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // `.filecard .fi{width:34px;height:34px;border-radius:9px;background:var(--primary-c);
        // color:var(--on-primary-c)}` + `.fi svg{18px}`。取哪一颗图标沿用 `mediaKindOf`。
        val fiIcon = when {
            card.isFolder -> Res.drawable.ic_folder
            mediaKindOf(card) == MediaKind.Image -> Res.drawable.ic_image
            mediaKindOf(card) == MediaKind.Audio -> Res.drawable.ic_speaker
            else -> Res.drawable.ic_file
        }
        Surface(
            shape = ShapeRoles.Avatar,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(34.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(fiIcon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(IconSizes.Body),
                )
            }
        }
        Column(Modifier.weight(1f)) {
        // `.fn{font-size:12px;font-weight:600;overflow:hidden;text-overflow:ellipsis;
        // white-space:nowrap}` —— 一行，超出省略（原型不让它折出第二行）。
        Text(
            text = card.name,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            // 原型 folderCard 的 accepted 分支把**进度口径**写在这一行：
            // `${pct}% · ${sz} / ${sz} · ${spd}`（relay.html:4259-4261），状态行只留状态词。
            // 代码原来无论什么状态都只画计数，于是「62%」被挪进状态行、这一行退化成
            // 纯计数 —— 与原型「副行看进度、状态行看状态」的分工相反。
            // 速率（1.2 MB/s）本端没有采样管线（FileCardUi 无速度字段，见
            // third_source/teahouse .../FileCard.vue:82 的 transfers.speed），故缺末段。
            text = if (card.isFolder && card.state == TransferState.Active) {
                stringResource(
                    Res.string.r2_chat_folder_progress,
                    (card.fraction * 100f).toInt(),
                    formatBytes(card.done),
                    formatBytes(card.size),
                )
            } else if (card.isFolder) {
                stringResource(Res.string.transfer_folder_count, card.fileCount) + " · " + formatBytes(card.size)
            } else if (card.fileCount > 1) {
                stringResource(Res.string.file_bubble_count, card.fileCount) +
                    " · " + formatBytes(card.size)
            } else {
                formatBytes(card.size)
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 表格图片的文字视图（协议 §3）。
        //
        // 表格里的数字要能选中复制，图片做不到这件事 —— 这就是协议为什么要为它留一个字段。
        // 措辞刻意不是"图片/文字切换"：这个开关说的是**文字**这一件事，
        // 图片预览是另一回事（见 [InlineImagePreview]），两者互不代替。
        val tableText = card.tableText
        if (tableText != null) {
            var showTableText by remember(card.transferId) { mutableStateOf(false) }
            TextButton(
                onClick = { showTableText = !showTableText },
                contentPadding = PaddingValues(),
                modifier = Modifier.padding(top = sp.xxs),
            ) {
                Text(
                    text = stringResource(
                        if (showTableText) {
                            Res.string.file_table_hide_text
                        } else {
                            Res.string.file_table_show_text
                        },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (showTableText) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().padding(top = sp.xs),
                ) {
                    // 制表符文本一行可能很长，让它横向滚而不是折成一片 ——
                    // 折行之后列就对不齐了，而"列对得齐"正是这段文字存在的理由。
                    Text(
                        text = tableText,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .padding(sp.sm)
                            .horizontalScroll(rememberScrollState()),
                    )
                }
                if (card.tableTextTruncated) {
                    Text(
                        text = stringResource(Res.string.file_table_truncated),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = sp.xs),
                    )
                }
            }
        }

        when (card.state) {
            TransferState.Offered -> {
                Text(
                    text = if (card.mine) {
                        stringResource(Res.string.file_state_offered_out)
                    } else {
                        stringResource(Res.string.file_state_offered_in)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 只有对方发来的、还没被接收的才给按钮：自己的出价等对方点，
                // 已经开始的传输再给"接收"只会让人以为点了能重来。
                // 判据抽成 [fileCardShowsAccept] 是为了能被单测钉住 —— 尤其是
                // 「进度态（Active）里绝不出现『接收』」这条，靠肉眼看布局迟早会漏。
                if (fileCardShowsAccept(card)) {
                    Row(
                        modifier = Modifier.padding(top = sp.sm),
                        horizontalArrangement = Arrangement.spacedBy(sp.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 主操作「接收」实心、次操作「拒绝」降级成文字 —— 与 Completed
                        // 分支的「打开 / 分享」用同一条规矩，两张卡的手感才一致。
                        Button(
                            onClick = { onAcceptTransfer(card.transferId) },
                            contentPadding = PaddingValues(horizontal = sp.md10, vertical = sp.xs),
                        ) {
                            Text(stringResource(Res.string.file_accept), style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(
                            onClick = { onDeclineTransfer(card.transferId) },
                            contentPadding = PaddingValues(horizontal = sp.sm, vertical = sp.xs),
                        ) {
                            Text(stringResource(Res.string.file_decline), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            TransferState.Active -> {
                val percent = (card.fraction * 100f).toInt()
                // 原型 `.bar{height:3px;border-radius:2px;background:var(--surface-3);margin-top:6px;
                // overflow:hidden}` + `.bar i{background:var(--primary)}` —— 传输进度是一条 3px 高、
                // 圆角 2px 的实心条，没有 M3 那圈描边端点、也没有末端停靠点。
                // 原型 `.bar{height:3px;border-radius:2px;background:var(--surface-3)}`：
                // 轨道是**填充** token（`accents.surface3`），不是描边用的 `--hair`。
                LinearProgressIndicator(
                    progress = { card.fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = sp.sm6)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.accents.surface3,
                    strokeCap = StrokeCap.Butt,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = sp.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        // 文件夹的进度已经写在副行，状态行按原型只留状态词
                        // （`accepted → 传输中/发送中`，relay.html:4254-4255）。
                        // 单个文件的进度没有别的地方可写，仍留在状态行（原型 fileCard 的
                        // `active-in/out` label 就是带百分号的，relay.html:2714-2715）。
                        text = if (card.isFolder) {
                            stringResource(
                                if (card.mine) {
                                    Res.string.r2_chat_folder_sending
                                } else {
                                    Res.string.r2_chat_folder_receiving
                                },
                            )
                        } else if (card.mine) {
                            stringResource(Res.string.file_state_active_out, percent)
                        } else {
                            stringResource(Res.string.file_state_active_in, percent)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    // 「正在发」是唯一一个用户仍然反悔得回来的时刻：撤了就是撤了，
                    // 传到一半的文件留在那里只会让人以为还在跑。
                    if (onCancel != null && card.mine) {
                        IconButton(
                            onClick = { onCancel() },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                painter = painterResource(Res.drawable.ic_close),
                                contentDescription = stringResource(Res.string.common_cancel),
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            TransferState.Completed -> {
                // 内联预览：图片画缩略图、音频给一个「播放」按钮。
                //
                // 位置在状态文案之前，因为它是**内容**，下面几行都是元数据。
                // 收端要等传完才有 `savedPaths`，发端在 offer 时就有 `localPaths` ——
                // 两边都拿不到路径（老消息、字段缺失）就什么都不画，退回原来那张文件卡。
                val previewPath = if (card.mine) {
                    card.localPaths.firstOrNull()
                } else {
                    card.savedPaths.firstOrNull()
                }
                if (previewPath != null) {
                    when (mediaKindOf(card)) {
                        MediaKind.Image -> InlineImagePreview(
                            path = previewPath,
                            onClick = onImageClick?.let { click -> { click(previewPath) } },
                        )
                        MediaKind.Audio -> InlineAudioPreview(card.transferId, previewPath)
                        MediaKind.Other -> Unit
                    }
                }
                Text(
                    text = if (card.mine) {
                        stringResource(Res.string.file_state_completed_out)
                    } else {
                        stringResource(Res.string.file_state_completed_in)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                val savedPath = card.savedPaths.firstOrNull()
                savedPath?.let { path ->
                    // 「已存到 <路径>」是**事实陈述**，必须保留（见 [ReceivedFileActions] 的 KDoc：
                    // 收下的文件落在应用内部目录，用户没有别的办法知道它在哪）。
                    // 但完整路径是实现细节（`/data/user/0/com.kapp.tidings/files/relay/downloads/…`）：
                    // 它一折成两行就把卡片本身压住了，而用户真正要看的是**文件名那一段**。
                    // 所以这里显示**省略过的**路径（中间用 `…` 标记、保留末尾），并且只占一行；
                    // 完整路径在「打开 / 分享」交给系统之后仍然可见。
                    Text(
                        text = stringResource(Res.string.file_saved_to, elidePathTail(path)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 收到的文件落在应用内部目录（filesDir/relay/downloads），文件管理器看不见 ——
                // 没有出口的话，那句"已存到 <路径>"就是一条死路。自己发出去的不给：
                // 本来就知道存在哪，而且 P0 的缺口专指"收到的拿不到"。
                if (!card.mine && receivedFileActions != null && savedPath != null) {
                    Row(
                        modifier = Modifier.padding(top = sp.sm),
                        horizontalArrangement = Arrangement.spacedBy(sp.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 这里不猜"能不能打开"：按钮只是入口，真正的动作在平台层
                        // （Android 是 ACTION_VIEW / ACTION_SEND + FileProvider）。
                        //
                        // **主次**：「打开」是这张卡的主操作（用户拿到文件后的下一步就是它），
                        // 给实心 [Button]；「分享」是次要出口，降级成 [TextButton]。
                        // 两个都是纯文字按钮时看不出该点哪个 —— 这与 Offered 分支的
                        // 「接收（实心）/ 拒绝（文字）」是同一条规矩。
                        // 用按钮族的 **Compact 档**（9×5 / labelSmall），不用裸 M3 `Button`：
                        // M3 的 `Button` 自带 `minHeight = 40dp`，`contentPadding` 只压得动横向，
                        // 于是这张卡里那行按钮的**背景比文字高一大截**（真机反馈 #5）。
                        // 同一处「打开/分享」的主次关系不变：主操作实心、次操作文字。
                        AppButton(
                            text = stringResource(Res.string.file_open),
                            onClick = { receivedFileActions.open(savedPath) },
                            size = AppButtonSize.Compact,
                        )
                        AppTextButton(
                            text = stringResource(Res.string.file_share),
                            onClick = { receivedFileActions.share(savedPath) },
                            size = AppButtonSize.Compact,
                        )
                    }
                }
            }

            TransferState.Failed -> {
                Text(
                    text = stringResource(
                        Res.string.file_state_failed,
                        // core 给的是**机器码**（socket-error / hash-mismatch / offer-expired…），
                        // 直接印给用户等于让用户去猜"是我网坏了还是软件坏了"。
                        // 这里翻译成人话；认不出的码**原样显示**（不吞掉事实，也不编一个说法）。
                        transferFailureReason(card.reason) ?: stringResource(Res.string.common_unknown),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                if (card.mine) {
                    // 出站失败**没有续传入口**（core 只有入站 resume；重发的正路是重新挑文件），
                    // 所以要把下一步说清楚，而不是只留一句失败原因 —— 那读起来像死路。
                    Text(
                        text = stringResource(Res.string.chat_file_send_failed_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = sp.xs),
                    )
                }
                if (!card.mine && onResumeTransfer != null) {
                    Row(
                        modifier = Modifier.padding(top = sp.sm6),
                        horizontalArrangement = Arrangement.spacedBy(sp.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(Res.string.transfer_resume_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Button(
                            onClick = { onResumeTransfer(card.transferId) },
                            contentPadding = PaddingValues(horizontal = sp.md10, vertical = sp.xs),
                        ) {
                            Text(stringResource(Res.string.transfer_action_resume), style = MaterialTheme.typography.labelSmall)
                        }
                        if (onCancel != null) {
                            TextButton(
                                onClick = onCancel,
                                contentPadding = PaddingValues(horizontal = sp.sm, vertical = sp.xs),
                            ) {
                                Text(stringResource(Res.string.common_cancel), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            TransferState.Declined -> Text(
                text = stringResource(Res.string.file_state_declined),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    }
}

/**
 * 把一条**实现细节**路径压成一行可读的尾巴：`…/downloads/q1.bin`。
 *
 * 为什么不是直接 `maxLines = 2`：文件卡里的完整路径是
 * `/data/user/0/com.kapp.tidings/files/relay/downloads/q1.bin` 这种应用内部目录，折成两行就把卡片
 * 本身压住了；而按行尾截断（`TextOverflow.Ellipsis`）留下的恰恰是最没用的前缀。
 * 所以这里**从中间省略**：留 `…` 标记 + 末尾一段（文件名所在处），并且**优先从 `/` 起**，
 * 让结果读起来像一段路径而不是被切碎的字符串。
 *
 * 它是纯函数、没有平台依赖，所以能被单测直接钉住（见 `FileCardStyleTest`）。
 *
 * @param maxChars 展示上限（含 `…`）。默认值对应卡片里那一行的可用宽度。
 */
internal fun elidePathTail(path: String, maxChars: Int = 34): String {
    if (path.length <= maxChars) return path
    val tail = path.takeLast(maxChars - 1)
    val slash = tail.indexOf('/')
    val readable = if (slash > 0) tail.substring(slash) else tail
    return "…" + readable
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
/**
 * 这张文件卡要不要给「接收」按钮。
 *
 * 两条规矩，缺一不可：
 *  · **对方发来的**才给（自己发出去的出价在等对方点，本端给「接收」没有意义）；
 *  · 而且必须是**还没开始传**的 `Offered`。
 *
 * 第二条是需求里点名的那条：`Active`（正在接收 62%）时再出现一个「接收」按钮，用户会以为
 * 点了能重来。抽成纯函数是为了让它**能被测到** —— 布局里「少画一个按钮」用肉眼迟早会漏。
 */
internal fun fileCardShowsAccept(card: FileCardUi): Boolean =
    card.state == TransferState.Offered && !card.mine && !card.autoAccepted

/**
 * 把 core 的传输失败**机器码**翻译成人话；认不出时返回 null（调用方退回原文或「未知」）。
 *
 * ## 为什么必须有这一层
 *
 * `TransferState.Failed` 的 `reason` 是传输层造的诊断码（`socket-error`、`hash-mismatch`、
 * `offer-expired`、`timeout-waiting-pull-ok`…）。它们对排障有价值、对用户毫无意义 ——
 * 真机上用户看到的是「文件传输失败：socket-error」，第一反应是「这软件坏了还是我网坏了」，
 * 而这恰恰是产品最该替他回答的问题。
 *
 * ## 两道纪律
 *
 * 1. **认不出的码原样显示**（返回 null ⇒ 调用方画 `common_unknown`）。绝不把不认识的码
 *    硬塞进某个笼统说法里 —— 那是在替事实编故事。
 * 2. **每条都要同时说「发生了什么」和「我该怎么办」**。只说「失败了」等于把问题原样退回给用户。
 *
 * 前缀匹配是刻意的：错误码族里有 `timeout` / `timeout-data` / `timeout-waiting-pull-ok`
 * 这样的派生形态，逐个枚举迟早漏。
 */
@Composable
internal fun transferFailureReason(reason: String?): String? {
    val code = reason?.trim()?.lowercase().orEmpty()
    if (code.isEmpty()) return null
    return when {
        code.startsWith("offer-expired") -> stringResource(Res.string.file_reason_offer_expired)
        code.startsWith("peer-offline") -> stringResource(Res.string.file_reason_peer_offline)
        code.startsWith("hash-mismatch") -> stringResource(Res.string.file_reason_hash_mismatch)
        code.startsWith("size-mismatch") -> stringResource(Res.string.file_reason_size_mismatch)
        code.startsWith("write-error") || code.startsWith("part-read-error") ->
            stringResource(Res.string.file_reason_write_error)
        code.startsWith("path-escape") -> stringResource(Res.string.file_reason_path_escape)
        code.startsWith("busy") -> stringResource(Res.string.file_reason_busy)
        code.startsWith("cancel") || code == "close" || code == "closed" ->
            stringResource(Res.string.file_reason_canceled)
        code.startsWith("not-found") -> stringResource(Res.string.file_reason_not_found)
        code.startsWith("bad-frame") || code.startsWith("bad-offset") || code.startsWith("bad-msg") ->
            stringResource(Res.string.file_reason_bad_frame)
        code.startsWith("socket-error") || code.startsWith("disconnected") || code == "type" ->
            stringResource(Res.string.file_reason_socket_error)
        code.startsWith("timeout") || code.startsWith("ack-timeout") || code.startsWith("gave-up") ->
            stringResource(Res.string.file_reason_timeout)
        else -> null
    }
}