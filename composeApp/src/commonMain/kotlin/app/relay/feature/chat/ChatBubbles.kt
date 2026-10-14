// SPDX-License-Identifier: GPL-3.0-only

package app.relay.feature.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.relay.app.MediaKind
import app.relay.app.mediaKindOf
import app.relay.app.RelayUiState
import app.relay.generated.resources.Res
import app.relay.platform.ReceivedFileActions
import app.relay.ui.components.PkCardView
import app.relay.ui.components.PeerAvatar
import app.relay.ui.components.formatBytes
import app.relay.ui.components.formatClock
import app.relay.ui.theme.IconSizes
import app.relay.ui.theme.LocalAppSpacing
import app.relay.ui.theme.accents
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import app.relay.app.model.DeliveryState
import app.relay.app.model.FileCardUi
import app.relay.app.model.MessageUi
import app.relay.app.model.NoticeKind
import app.relay.app.model.TransferState
import app.relay.generated.resources.chat_copy
import app.relay.generated.resources.chat_delivery_resend
import app.relay.generated.resources.chat_forward
import app.relay.generated.resources.chat_group_members_changed
import app.relay.generated.resources.chat_group_removed
import app.relay.generated.resources.chat_group_renamed
import app.relay.generated.resources.chat_nudge_peer
import app.relay.generated.resources.chat_nudge_self
import app.relay.generated.resources.chat_recall
import app.relay.generated.resources.chat_recall_peer
import app.relay.generated.resources.chat_recall_self
import app.relay.generated.resources.chat_reply_action
import app.relay.generated.resources.common_cancel
import app.relay.generated.resources.common_unknown
import app.relay.generated.resources.file_accept
import app.relay.generated.resources.file_bubble_count
import app.relay.generated.resources.file_decline
import app.relay.generated.resources.file_open
import app.relay.generated.resources.file_saved_to
import app.relay.generated.resources.file_share
import app.relay.generated.resources.file_state_active_in
import app.relay.generated.resources.file_state_active_out
import app.relay.generated.resources.file_state_completed_in
import app.relay.generated.resources.file_state_completed_out
import app.relay.generated.resources.file_state_declined
import app.relay.generated.resources.file_state_failed
import app.relay.generated.resources.file_state_offered_in
import app.relay.generated.resources.file_state_offered_out
import app.relay.generated.resources.file_table_hide_text
import app.relay.generated.resources.file_table_show_text
import app.relay.generated.resources.file_table_truncated
import app.relay.generated.resources.ic_close
import app.relay.generated.resources.ic_file
import app.relay.generated.resources.ic_folder
import app.relay.generated.resources.ic_image
import app.relay.generated.resources.ic_speaker
import app.relay.generated.resources.r2_chat_folder_progress
import app.relay.generated.resources.r2_chat_folder_receiving
import app.relay.generated.resources.r2_chat_folder_sending
import app.relay.generated.resources.transfer_action_resume
import app.relay.generated.resources.transfer_folder_count
import app.relay.generated.resources.transfer_resume_hint
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import app.relay.ui.theme.ShapeRoles

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
    /** 撤回这条消息。null = 现在撤不了（不是自己的、是文件、或过了 5 分钟窗口）。 */
    onRecall: (() -> Unit)? = null,
    onReply: (() -> Unit)? = null,
    onForward: (() -> Unit)? = null,
    /** 群消息气泡上方署的发件人名。null = 不署（私聊，或这条是自己发的）。 */
    senderName: String? = null,
    /**
     * 气泡左侧那颗 26dp 方头像（原型 `.msg .mav`）。null = 不画。
     *
     * 原型里 `.mav` 只在消息带 `ini` 时出现，而演示数据里**只有别人发的**消息带 `ini`
     * —— 自己发的一条都没有。所以这里也只在非 [MessageUi.mine] 时给值。
     */
    senderAvatar: ChatAvatar? = null,
    quotedMessage: MessageUi? = null,
    onScrollToQuoted: (() -> Unit)? = null,
    onImageClick: ((String) -> Unit)? = null,
) {
    val sp = LocalAppSpacing.current
    val bubbleColor = if (message.mine) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    var menuOpen by remember { mutableStateOf(false) }
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
            PeerAvatar(
                name = av.name,
                isAgent = av.isAgent,
                size = 26.dp,
                avatar = av.avatar,
                shape = ShapeRoles.Avatar,
            )
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
                    .bubbleMaxWidth(this@BoxWithConstraints.maxWidth, bubbleFraction)
                    .combinedClickable(
                        onClick = {}, onLongClick = { menuOpen = true },
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
                    val quotedSender = quotedMessage.senderId?.take(8) ?: quotedMessage.peerId.take(8)
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
                    Text(text = message.text, style = MaterialTheme.typography.bodyMedium)
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
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                MessagePressActions(
                    copyText = message.text.ifBlank { message.file?.name.orEmpty() },
                    onReply = onReply?.let { { menuOpen = false; it() } },
                    onRecall = onRecall?.let { { menuOpen = false; it() } },
                    onForward = onForward?.let { { menuOpen = false; it() } },
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
    onReply: (() -> Unit)? = null,
    onRecall: (() -> Unit)? = null,
    onForward: (() -> Unit)? = null,
) {
    val clipboardManager = LocalClipboardManager.current
    if (onReply != null) {
        DropdownMenuItem(
            text = { Text(stringResource(Res.string.chat_reply_action)) },
            onClick = onReply,
        )
    }
    if (onRecall != null) {
        DropdownMenuItem(
            text = { Text(stringResource(Res.string.chat_recall)) },
            onClick = onRecall,
        )
    }
    DropdownMenuItem(
        text = { Text(stringResource(Res.string.chat_copy)) },
        onClick = {
            if (copyText.isNotEmpty()) {
                clipboardManager.setText(AnnotatedString(copyText))
            }
        },
    )
    if (onForward != null) {
        DropdownMenuItem(
            text = { Text(stringResource(Res.string.chat_forward)) },
            onClick = onForward,
        )
    }
}

/**
 * 时间线上的一行系统提示（撤回、戳一碰）。
 *
 * 居中的小灰字，不画气泡、不带时间戳：它不是谁说的话，是"这里发生过一件事"。
 * 文案全部来自资源，控制器只给出 [NoticeKind]（见 `RelayUiState.NoticeKind`）。
 *
 * 原型 `.notice{align-self:center;font-size:11px;color:var(--on-2);background:var(--surface-2);
 * padding:3px 10px;border-radius:var(--r-chip);margin:4px 0}` —— 它是一颗**胶囊**，不是
 * 裸文字。`.daysep`（日期分隔条）用的是同一条形状，只差没有 `gap`。
 */
@Composable
internal fun NoticeLine(kind: NoticeKind) {
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
            Text(
                text = stringResource(
                    when (kind) {
                        NoticeKind.RecalledByPeer -> Res.string.chat_recall_peer
                        NoticeKind.RecalledBySelf -> Res.string.chat_recall_self
                        NoticeKind.NudgedByPeer -> Res.string.chat_nudge_peer
                        NoticeKind.NudgedBySelf -> Res.string.chat_nudge_self
                        NoticeKind.GroupMembersChanged -> Res.string.chat_group_members_changed
                        NoticeKind.GroupRenamed -> Res.string.chat_group_renamed
                        NoticeKind.RemovedFromGroup -> Res.string.chat_group_removed
                    },
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = sp.md10, vertical = 3.dp),
            )
        }
    }
}

/**
 * 文件气泡。
 *
 * 与文字气泡共用同一条消息流：一次 transfer 只产生一条消息，进度事件原地更新它，
 * 于是"发文件"和"发消息"在会话里是同一种东西，不需要第二套列表，也不会两处对不上。
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
                if (!card.mine) {
                    Row(
                        modifier = Modifier.padding(top = sp.sm),
                        horizontalArrangement = Arrangement.spacedBy(sp.md),
                    ) {
                        Button(onClick = { onAcceptTransfer(card.transferId) }) {
                            Text(stringResource(Res.string.file_accept))
                        }
                        TextButton(onClick = { onDeclineTransfer(card.transferId) }) {
                            Text(stringResource(Res.string.file_decline))
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
                    Text(
                        text = stringResource(Res.string.file_saved_to, path),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 收到的文件落在应用内部目录（filesDir/relay/downloads），文件管理器看不见 ——
                // 没有出口的话，那句"已存到 <路径>"就是一条死路。自己发出去的不给：
                // 本来就知道存在哪，而且 P0 的缺口专指"收到的拿不到"。
                if (!card.mine && receivedFileActions != null && savedPath != null) {
                    Row(
                        modifier = Modifier.padding(top = sp.sm),
                        horizontalArrangement = Arrangement.spacedBy(sp.md),
                    ) {
                        // 这里不猜"能不能打开"：按钮只是入口，真正的动作在平台层
                        // （Android 是 ACTION_VIEW / ACTION_SEND + FileProvider）。
                        TextButton(onClick = { receivedFileActions.open(savedPath) }) {
                            Text(stringResource(Res.string.file_open))
                        }
                        TextButton(onClick = { receivedFileActions.share(savedPath) }) {
                            Text(stringResource(Res.string.file_share))
                        }
                    }
                }
            }

            TransferState.Failed -> {
                Text(
                    text = stringResource(
                        Res.string.file_state_failed,
                        card.reason ?: stringResource(Res.string.common_unknown),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
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