// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import com.kapp.tidings.ui.theme.RelayElevations
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.heightIn
import com.kapp.tidings.feature.chat.localDayKey
import com.kapp.tidings.generated.resources.chat_day_divider_today
import com.kapp.tidings.generated.resources.chat_day_divider_yesterday
import com.kapp.tidings.generated.resources.chat_forward_title
import com.kapp.tidings.generated.resources.common_cancel
import androidx.compose.ui.unit.sp
import com.kapp.tidings.domain.BannerMessage
import com.kapp.tidings.domain.BannerTone
import com.kapp.tidings.domain.BannerUi
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.banner_camera_denied
import com.kapp.tidings.generated.resources.banner_camera_unavailable
import com.kapp.tidings.generated.resources.banner_capture_canceled
import com.kapp.tidings.generated.resources.banner_capture_unavailable
import com.kapp.tidings.generated.resources.banner_group_created
import com.kapp.tidings.generated.resources.banner_group_left
import com.kapp.tidings.generated.resources.banner_group_members_added
import com.kapp.tidings.generated.resources.banner_group_member_removed
import com.kapp.tidings.generated.resources.banner_too_many_files
import com.kapp.tidings.generated.resources.banner_group_no_permission
import com.kapp.tidings.generated.resources.banner_group_name_required
import com.kapp.tidings.generated.resources.banner_group_name_too_long
import com.kapp.tidings.generated.resources.banner_group_needs_member
import com.kapp.tidings.generated.resources.banner_group_unknown
import com.kapp.tidings.generated.resources.banner_invalid_qr
import com.kapp.tidings.generated.resources.banner_no_qr_in_image
import com.kapp.tidings.generated.resources.banner_notification_denied
import com.kapp.tidings.generated.resources.banner_group_meta_updated
import com.kapp.tidings.generated.resources.chat_forward_success
import com.kapp.tidings.generated.resources.banner_remote_relay_cleared
import com.kapp.tidings.generated.resources.banner_remote_relay_configured
import com.kapp.tidings.generated.resources.banner_scan_failed
import com.kapp.tidings.generated.resources.banner_scan_range_added
import com.kapp.tidings.generated.resources.banner_scan_range_invalid
import com.kapp.tidings.generated.resources.banner_scan_range_triggered
import com.kapp.tidings.generated.resources.banner_screen_view_ended
import com.kapp.tidings.generated.resources.banner_screen_view_unavailable
import com.kapp.tidings.generated.resources.screen_reason_app_exit
import com.kapp.tidings.generated.resources.screen_reason_busy
import com.kapp.tidings.generated.resources.screen_reason_canceled
import com.kapp.tidings.generated.resources.screen_reason_capture_ended
import com.kapp.tidings.generated.resources.screen_reason_capture_failed
import com.kapp.tidings.generated.resources.screen_reason_declined
import com.kapp.tidings.generated.resources.screen_reason_disconnected
import com.kapp.tidings.generated.resources.screen_reason_locked
import com.kapp.tidings.generated.resources.screen_reason_permission_denied
import com.kapp.tidings.generated.resources.screen_reason_protocol_error
import com.kapp.tidings.generated.resources.screen_reason_suspended
import com.kapp.tidings.generated.resources.screen_reason_timeout
import com.kapp.tidings.generated.resources.screen_reason_unknown
import com.kapp.tidings.generated.resources.screen_reason_unsupported
import com.kapp.tidings.generated.resources.screen_reason_user
import com.kapp.tidings.generated.resources.cabinet_banner_dir_updated
import com.kapp.tidings.generated.resources.cabinet_banner_share_stopped
import com.kapp.tidings.generated.resources.cabinet_download_count
import com.kapp.tidings.generated.resources.common_agent
import com.kapp.tidings.generated.resources.common_close
import com.kapp.tidings.generated.resources.common_human
import com.kapp.tidings.generated.resources.chat_nudge_failed
import com.kapp.tidings.generated.resources.chat_nudge_throttled
import com.kapp.tidings.generated.resources.chat_send_failed
import com.kapp.tidings.generated.resources.chat_send_too_long
import com.kapp.tidings.generated.resources.disc_added
import com.kapp.tidings.generated.resources.disc_cleanup_done
import com.kapp.tidings.generated.resources.disc_cleanup_none
import com.kapp.tidings.generated.resources.disc_duplicate
import com.kapp.tidings.generated.resources.disc_invalid_host
import com.kapp.tidings.generated.resources.error_unknown
import com.kapp.tidings.generated.resources.file_offered
import com.kapp.tidings.generated.resources.file_pick_failed
import com.kapp.tidings.generated.resources.file_transfer_failed
import com.kapp.tidings.generated.resources.nav_badge_overflow
import com.kapp.tidings.generated.resources.node_bind_failed_reason
import com.kapp.tidings.generated.resources.node_not_started
import com.kapp.tidings.generated.resources.nodes_port_bad
import com.kapp.tidings.generated.resources.set_caps_saved
import com.kapp.tidings.generated.resources.set_invalid_nick
import com.kapp.tidings.generated.resources.set_ports_saved
import com.kapp.tidings.generated.resources.set_saved
import com.kapp.tidings.generated.resources.time_days_ago
import com.kapp.tidings.generated.resources.time_hours_ago
import com.kapp.tidings.generated.resources.time_just_now
import com.kapp.tidings.generated.resources.time_minutes_ago
import com.kapp.tidings.generated.resources.ic_bot
import com.kapp.tidings.generated.resources.ic_groups
import com.kapp.tidings.ui.theme.accents
import com.kapp.tidings.ui.theme.LocalAppSpacing
import com.kapp.tidings.domain.model.ConversationUi
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import com.kapp.tidings.ui.theme.SurfaceTiers

/**
 * Agent 节点在界面上的强调色。与 `design/design-spec.json` 的 `agentAccent` 一致。
 *
 * 两态值放在 `ui/theme/Accents.kt`（`MaterialTheme.accents`），这里不再各写一份 ——
 * 以前这两个是写死的浅色 val，深色模式下 Agent 头像是块刺眼亮白。
 */

/**
 * 在线/离线圆点。离线用中性灰而不是红色 —— 离线是常态，不是错误。
 *
 * [color] 是给同步状态条这类场景开的后门：那里要点的是"mDNS 广播中"而不是
 * "某个 peer 在线"，沿用 online 的绿/灰语义会表达错意思。默认 null 时行为不变。
 */
@Composable
fun StatusDot(online: Boolean, size: Dp = 8.dp, color: Color? = null) {
    Box(
        Modifier
            .size(size)
            .background(
                color = color
                    ?: if (online) MaterialTheme.accents.online else MaterialTheme.colorScheme.outline,
                shape = CircleShape,
            ),
    )
}

/**
 * 头像：真人根据 avatar 预设值或取昵称首字，Agent 用机器人图标，群用群组图标。
 *
 * [shape] 不是装饰：原型里头像圆角**按上下文变**，只有会话行是正圆。
 * `.av{width:40px;border-radius:50%}`（会话行 `.row.conv .av` 48px 正圆）、
 * `.li .av{32px;border-radius:12px}`、`.dhead .av{36px;12px}`、
 * `.hav{40px;13px}`、`.topbar.chat .hav{36px;12px}`、`.msg .mav{26px;9px}`。
 * 全画成正圆会把「圆角方块」这一整族头像抹平 —— 那是看得见的差异，不是细节。
 */
@Composable
fun PeerAvatar(
    name: String,
    isAgent: Boolean = false,
    size: Dp = 40.dp,
    isGroup: Boolean = false,
    avatar: Int? = null,
    avatarBytes: ByteArray? = null,
    shape: Shape = CircleShape,
) {
    val customBitmap = androidx.compose.runtime.remember(avatarBytes) {
        avatarBytes?.let { com.kapp.tidings.platform.decodeImageBytes(it) }
    }
    val animal = if (isAgent || isGroup || avatar == null) null else AvatarPalette.resolveAnimal(avatar)
    val background = when {
        isAgent -> MaterialTheme.accents.agentAccentContainer
        isGroup -> MaterialTheme.colorScheme.secondaryContainer
        avatar != null -> AvatarPalette.resolveBg(avatar, name)
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val foreground = when {
        isAgent -> MaterialTheme.accents.agentAccent
        isGroup -> MaterialTheme.colorScheme.onSecondaryContainer
        avatar != null -> Color.White
        else -> MaterialTheme.colorScheme.primary
    }
    Box(
        modifier = Modifier
            .size(size)
            .background(background, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (customBitmap != null && !isAgent && !isGroup) {
            Image(
                bitmap = customBitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(shape),
            )
        } else if (isAgent || isGroup) {
            Icon(
                painter = painterResource(if (isAgent) Res.drawable.ic_bot else Res.drawable.ic_groups),
                contentDescription = null,
                tint = foreground,
                modifier = Modifier.size(size * 0.55f),
            )
        } else if (animal != null) {
            Text(
                text = animal,
                fontSize = (size.value * 0.47f).sp,
                textAlign = TextAlign.Center,
            )
        } else {
            Text(
                text = name.take(1).uppercase().ifBlank { "茶" },
                color = foreground,
                fontSize = (size.value * 0.42f).sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * 「Agent」/「同事」小徽标。原型 `.tag`：11px/600、`padding:1.5px 6px`、`border-radius:var(--r-chip)`=999（药丸）。
 *
 * Agent 侧的文字色是 [com.kapp.tidings.ui.theme.RelayAccents.agentAccentText] 而不是 `agentAccent`：
 * 后者压在 agent 容器上只有 4.26:1，11sp 小字不够。
 */
@Composable
fun KindBadge(isAgent: Boolean) {
    val sp = LocalAppSpacing.current
    // 标签类 → `--surface-2`（原型 `.tag` / `.pill` 都用这一档，见 [SurfaceTiers] 的 KDoc）。
    // 以前写 `surfaceVariant`（= `--surface-3` 那档），于是标签与内嵌小控件同色，两层纵深化成一层。
    val background = if (isAgent) MaterialTheme.accents.agentAccentContainer else SurfaceTiers.Raised
    val foreground = if (isAgent) MaterialTheme.accents.agentAccentText else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(color = background, shape = CircleShape) {
        Text(
            text = stringResource(if (isAgent) Res.string.common_agent else Res.string.common_human),
            color = foreground,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = sp.sm6, vertical = 1.5.dp),
        )
    }
}

/** 分组标题。 */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val sp = LocalAppSpacing.current
    if (action == null) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(horizontal = sp.xs, vertical = sp.sm6),
        )
    } else {
        // 带动作时标题与动作同一行：动作必须**贴着**它所属的那一节，
        // 放到页面顶部就会变成"这个按钮管整页"，而它只管这一节。
        Row(
            modifier = modifier.fillMaxWidth().padding(horizontal = sp.xs, vertical = sp.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            action()
        }
    }
}

/**
 * 把结构化的 [BannerMessage] 翻成给用户看的中文。
 *
 * 控制器只说"发生了什么"（`BindFailed(port, reason)`），文案在这里落地 ——
 * 这样控制器不必、也不能去碰 `stringResource`。
 */
@Composable
fun bannerText(message: BannerMessage): String = when (message) {
    BannerMessage.EmptyHost -> stringResource(Res.string.disc_invalid_host)
    BannerMessage.InvalidHost -> stringResource(Res.string.disc_invalid_host)
    BannerMessage.InvalidPort -> stringResource(Res.string.nodes_port_bad)
    BannerMessage.DuplicatePeer -> stringResource(Res.string.disc_duplicate)
    is BannerMessage.Handshaking -> stringResource(Res.string.disc_added, message.target)
    BannerMessage.NodeNotStarted -> stringResource(Res.string.node_not_started)
    is BannerMessage.TooManyFiles ->
        stringResource(Res.string.banner_too_many_files, message.count, message.limit)
    is BannerMessage.MessageTooLong ->
        stringResource(Res.string.chat_send_too_long, message.bytes, message.limit)
    is BannerMessage.SendFailed -> stringResource(Res.string.chat_send_failed, message.detail)
    BannerMessage.NickEmpty -> stringResource(Res.string.set_invalid_nick)
    is BannerMessage.ProfileSaved -> stringResource(Res.string.set_saved, message.rev)
    is BannerMessage.PortsSaved ->
        stringResource(Res.string.set_ports_saved, message.udp, message.tcp)
    BannerMessage.CapabilitiesSaved -> stringResource(Res.string.set_caps_saved)
    is BannerMessage.BindFailed ->
        stringResource(
            Res.string.node_bind_failed_reason,
            message.port,
            message.reason ?: stringResource(Res.string.error_unknown),
        )
    is BannerMessage.PickFailed -> stringResource(Res.string.file_pick_failed, message.detail)
    is BannerMessage.TransferFailed ->
        stringResource(Res.string.file_transfer_failed, message.detail)
    is BannerMessage.FilesOffered ->
        stringResource(Res.string.file_offered, message.count, message.name)
    BannerMessage.CameraPermissionDenied -> stringResource(Res.string.banner_camera_denied)
    BannerMessage.NotificationPermissionDenied -> stringResource(Res.string.banner_notification_denied)
    BannerMessage.CameraUnavailable -> stringResource(Res.string.banner_camera_unavailable)
    is BannerMessage.ScanFailed -> stringResource(Res.string.banner_scan_failed, message.detail)
    BannerMessage.InvalidQrCode -> stringResource(Res.string.banner_invalid_qr)
    BannerMessage.NoQrInImage -> stringResource(Res.string.banner_no_qr_in_image)
    BannerMessage.UnknownError -> stringResource(Res.string.error_unknown)
    BannerMessage.NoUnreachablePeers -> stringResource(Res.string.disc_cleanup_none)
    is BannerMessage.UnreachablePeersRemoved ->
        stringResource(Res.string.disc_cleanup_done, message.count)
    BannerMessage.NudgeFailed -> stringResource(Res.string.chat_nudge_failed)
    BannerMessage.NudgeThrottled -> stringResource(Res.string.chat_nudge_throttled)
    BannerMessage.GroupNameRequired -> stringResource(Res.string.banner_group_name_required)
    is BannerMessage.GroupNameTooLong ->
        stringResource(Res.string.banner_group_name_too_long, message.limit)
    BannerMessage.GroupNeedsMember -> stringResource(Res.string.banner_group_needs_member)
    is BannerMessage.GroupCreated ->
        stringResource(Res.string.banner_group_created, message.name, message.memberCount)
    BannerMessage.GroupUnknown -> stringResource(Res.string.banner_group_unknown)
    BannerMessage.GroupLeft -> stringResource(Res.string.banner_group_left)
    is BannerMessage.GroupMembersAdded ->
        stringResource(Res.string.banner_group_members_added, message.count)
    BannerMessage.GroupMemberRemoved -> stringResource(Res.string.banner_group_member_removed)
    BannerMessage.GroupNoPermission -> stringResource(Res.string.banner_group_no_permission)
    BannerMessage.GroupMetaUpdated -> stringResource(Res.string.banner_group_meta_updated)
    BannerMessage.ForwardSuccess -> stringResource(Res.string.chat_forward_success)
    is BannerMessage.RemoteRelayConfigured ->
        stringResource(Res.string.banner_remote_relay_configured, message.name)
    BannerMessage.RemoteRelayCleared ->
        stringResource(Res.string.banner_remote_relay_cleared)
    BannerMessage.CabinetDirUpdated ->
        stringResource(Res.string.cabinet_banner_dir_updated)
    BannerMessage.CabinetShareStopped ->
        stringResource(Res.string.cabinet_banner_share_stopped)
    is BannerMessage.CabinetDownloadStarted ->
        stringResource(Res.string.cabinet_download_count, formatCount(message.count.toLong()))
    BannerMessage.ScanRangeInvalid ->
        stringResource(Res.string.banner_scan_range_invalid)
    is BannerMessage.ScanRangeAdded ->
        stringResource(Res.string.banner_scan_range_added, message.cidr)
    is BannerMessage.ScanRangeTriggered ->
        stringResource(Res.string.banner_scan_range_triggered, message.hostCount)
    BannerMessage.CaptureUnavailable ->
        stringResource(Res.string.banner_capture_unavailable)
    is BannerMessage.CaptureSaved ->
        message.path
    BannerMessage.CaptureCanceled ->
        stringResource(Res.string.banner_capture_canceled)
    BannerMessage.ScreenViewUnavailable ->
        stringResource(Res.string.banner_screen_view_unavailable)
    is BannerMessage.ScreenSessionEnded ->
        stringResource(Res.string.banner_screen_view_ended, screenReasonText(message.reason))
}

/**
 * 屏幕协助的结束/拒绝原因码 → 用户看得懂的一句话。
 *
 * **这里必须覆盖协议里全部 14 个取值**（`ScreenRejectReasons` 6 个 + `ScreenEndReasons` 9 个，
 * `"timeout"` 两处同值）：这个函数是用户看到失败原因的唯一出口，漏一个就会把
 * `protocol-error` 这种协议 token 直接印在界面上。
 *
 * 兜底分支**不原样返回 reason**：宁可说一句"原因未知"，也不要把 `foo-bar-baz` 印给用户 ——
 * 未知码只可能来自对端发了集合外的字符串，那种情况下协议 token 对用户零信息量。
 */
@Composable
fun screenReasonText(reason: String): String = when (reason) {
    com.kapp.tidings.core.protocol.ScreenRejectReasons.DECLINED -> stringResource(Res.string.screen_reason_declined)
    com.kapp.tidings.core.protocol.ScreenRejectReasons.BUSY -> stringResource(Res.string.screen_reason_busy)
    com.kapp.tidings.core.protocol.ScreenRejectReasons.UNSUPPORTED -> stringResource(Res.string.screen_reason_unsupported)
    com.kapp.tidings.core.protocol.ScreenRejectReasons.PERMISSION_DENIED -> stringResource(Res.string.screen_reason_permission_denied)
    com.kapp.tidings.core.protocol.ScreenRejectReasons.CAPTURE_FAILED -> stringResource(Res.string.screen_reason_capture_failed)
    com.kapp.tidings.core.protocol.ScreenRejectReasons.TIMEOUT -> stringResource(Res.string.screen_reason_timeout)
    com.kapp.tidings.core.protocol.ScreenEndReasons.DISCONNECTED -> stringResource(Res.string.screen_reason_disconnected)
    com.kapp.tidings.core.protocol.ScreenEndReasons.USER -> stringResource(Res.string.screen_reason_user)
    com.kapp.tidings.core.protocol.ScreenEndReasons.CANCELED -> stringResource(Res.string.screen_reason_canceled)
    com.kapp.tidings.core.protocol.ScreenEndReasons.LOCKED -> stringResource(Res.string.screen_reason_locked)
    com.kapp.tidings.core.protocol.ScreenEndReasons.SUSPENDED -> stringResource(Res.string.screen_reason_suspended)
    com.kapp.tidings.core.protocol.ScreenEndReasons.CAPTURE_ENDED -> stringResource(Res.string.screen_reason_capture_ended)
    com.kapp.tidings.core.protocol.ScreenEndReasons.PROTOCOL_ERROR -> stringResource(Res.string.screen_reason_protocol_error)
    com.kapp.tidings.core.protocol.ScreenEndReasons.APP_EXIT -> stringResource(Res.string.screen_reason_app_exit)
    else -> stringResource(Res.string.screen_reason_unknown)
}


/**
 * 顶部横幅：离线积压、绑定失败、保存结果都走这里。
 *
 * 三种色调都给了明确的"下一步"，因为一个只说"出错了"的横幅在移动端等于没有出路。
 */
@Composable
fun AppBanner(banner: BannerUi, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val sp = LocalAppSpacing.current
    val container = when (banner.tone) {
        // Info 横幅是"一块次级表面"，不是小控件 → `--surface-2`。
        BannerTone.Info -> SurfaceTiers.Raised
        BannerTone.Warn -> MaterialTheme.colorScheme.secondaryContainer
        BannerTone.Error -> MaterialTheme.colorScheme.errorContainer
    }
    val content = when (banner.tone) {
        BannerTone.Info -> MaterialTheme.colorScheme.onSurfaceVariant
        BannerTone.Warn -> MaterialTheme.colorScheme.onSecondaryContainer
        BannerTone.Error -> MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(
        color = container,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
        tonalElevation = RelayElevations.Card,
    ) {
        Row(
            modifier = Modifier.padding(start = sp.lg, end = sp.sm, top = sp.sm, bottom = sp.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = bannerText(banner.message),
                color = content,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                // 右边给「关闭」留一道水槽：横幅文案最长三行，没有这道间距时
                // 长句会贴着按钮走，换个语种就直接压上去。
                modifier = Modifier.weight(1f).padding(end = sp.md, top = sp.sm, bottom = sp.sm),
            )
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.common_close), color = content)
            }
        }
    }
}

/**
 * 带标题的输入框。
 *
 * 每个设置字段都必须是能直接改的控件 —— 用户硬约束是"凭据/配置要有录入界面，
 * 不能让用户手写配置文件"。
 */
@Composable
fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    supporting: String? = null,
    isError: Boolean = false,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    maxLength: Int? = null,
) {
    val sp = LocalAppSpacing.current
    Column(modifier.fillMaxWidth().padding(vertical = sp.xs)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = value,
            onValueChange = { next ->
                onValueChange(if (maxLength != null) next.take(maxLength) else next)
            },
            modifier = Modifier.fillMaxWidth().padding(top = sp.xs),
            placeholder = hint?.let { { Text(it) } },
            singleLine = singleLine,
            isError = isError,
            enabled = enabled,
        )
        if (supporting != null) {
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = sp.xxs),
            )
        }
    }
}

/**
 * 只读的一行「标签 — 值」，诊断信息用。
 *
 * **值那一侧也必须带 weight**。`Row` 里没有 weight 的子项是按**整条剩余宽度**测量的，
 * 所以一个长值（数据目录那种一长串路径、`协议` 那行一长串能力名）会吃掉整行，把带
 * weight 的标签挤到只剩一个字宽 —— 标签于是竖着一个字一行地折下去（实测
 * `settings-more-long-text` 帧里 `数据目录` 渲染成了 `数`/`据`/`目`/`录` 四行）。
 * 值加权 + [TextAlign.End] 之后，短值仍然贴着右边缘（观感不变），长值改成自己省略，
 * 标签不再被挤。比例 1 : 2.5 是按本页最长的标签 `状态栏 inset` 定的。
 *
 * 纵向内边距是 `sp.sm`（8）：原型 `.kv{padding:9px 14px}` 是 9px，8 是声明档里最近的一档。
 * 原来写 `3.dp` —— 既不是原型值也不是声明档，且会在差集里恒挂一条「只代码 3」。
 * `feature/settings/SettingsPageChrome.kt` 的 `KvRow` 与本函数刻意同形，改动要同步。
 */
@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    val sp = LocalAppSpacing.current
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = sp.sm),
        horizontalArrangement = Arrangement.spacedBy(sp.md),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(2.5f),
        )
    }
}

/** 相对时间：刚刚 / N 分钟前 / N 小时前 / N 天前。不引入日期库，纯算术。 */
@Composable
fun relativeTime(atMillis: Long, nowMillis: Long): String {
    val delta = nowMillis - atMillis
    return when {
        delta < 60_000 -> stringResource(Res.string.time_just_now)
        delta < 3_600_000 -> stringResource(Res.string.time_minutes_ago, (delta / 60_000).toInt())
        delta < 86_400_000 -> stringResource(Res.string.time_hours_ago, (delta / 3_600_000).toInt())
        else -> stringResource(Res.string.time_days_ago, (delta / 86_400_000).toInt())
    }
}


/**
 * 本仓显示时间用的固定 UTC 偏移（+8）。
 *
 * ## 为什么是一个常量而不是到处写 `8`
 *
 * 时间/日期显示有三处（气泡时钟、日期分隔、相对日期的天界）：它们必须**同一套口径**，
 * 否则会出现「气泡说今天 08:30、分隔线说昨天」这种自相矛盾的读数。
 * 各写一遍 `+ 8 * …` 就是三处可以各自漂的真相来源 —— 量具已经把这件事记成
 * 「时区硬编码处数」，这条就是把它收敛到一处。
 *
 * 注：协议里的时间一律是毫秒时间戳（UTC），偏移只用于**显示**，不参与任何判定。
 */
internal const val DISPLAY_UTC_OFFSET_MILLIS: Long = 8L * 3_600_000L

/** 把一个瞬时换算成「本机显示用的」毫秒数（已加显示偏移）。 */
internal fun displayMillis(atMillis: Long): Long = atMillis + DISPLAY_UTC_OFFSET_MILLIS
/** 「年-月-日」形态的日期，与 [formatClock] 同一套显示偏移（纯算术，不引日期库）。 */
internal fun formatDay(atMillis: Long): String {
    val days = displayMillis(atMillis) / 86_400_000L
    // 从「自纪元第几天」反解年月日（Howard Hinnant 的 civil_from_days 算法）。
    // 不引 kotlinx-datetime 的理由见 localDayKey 的 KDoc：同一屏不能有两套时间口径。
    val z = days + 719_468
    val era = (if (z >= 0) z else z - 146_096) / 146_097
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    val year = if (m <= 2) y + 1 else y
    return "${year}-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
}
/**
 * 聊天流的日期分隔条：「今天 / 昨天 / 年-月-日」。
 *
 * 与 `SessionDivider`（会话分段）是同族组件、不同维度 —— 那个回答「换了一段任务」，
 * 这个回答「换了一天」。翻历史时后者才是用户真正在找的锚点。
 *
 * 日期口径与 [formatClock] **同一套**（+8 粗算，见 `localDayKey`），
 * 所以不会出现「气泡时间说今天、分隔线说昨天」这种自相矛盾。
 */
@Composable
internal fun DayDivider(at: Long, now: Long, modifier: Modifier = Modifier) {
    val todayKey = localDayKey(now)
    val dayKey = localDayKey(at)
    val label = when {
        dayKey == todayKey -> stringResource(Res.string.chat_day_divider_today)
        // 「昨天」= 日历上的前一天。用 key 的差而不是 86_400_000 的窗口：
        // 后者在跨月/跨年边界上会说错，而 key 是「自纪元第几天」，差 1 就是昨天。
        dayKey.toLongOrNull() == todayKey.toLongOrNull()?.minus(1) ->
            stringResource(Res.string.chat_day_divider_yesterday)
        else -> formatDay(at)
    }
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = LocalAppSpacing.current.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(percent = 50),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = LocalAppSpacing.current.sm, vertical = 3.dp),
            )
        }
    }
}
/** 消息气泡上的时间：HH:mm（本地时区，按 UTC 偏移粗算即可，不需要秒级精度）。 */
fun formatClock(atMillis: Long): String {
    val minutesOfDay = (displayMillis(atMillis) / 60_000L) % (24 * 60)
    val hour = (minutesOfDay / 60).toInt()
    val minute = (minutesOfDay % 60).toInt()
    return "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
}

/**
 * 人类可读的字节数。
 *
 * **进制保持 1024**：原型全稿**没有**格式化函数（只有 `avCircle` / `avatarOf` /
 * `device` / `win`），所有 `2.4 MB` / `812 KB` 都是**手写的演示字符串** ⇒ 从它们
 * 推不出进制，改进制就是拿演示数据反推实现。原型柜底栏那句「已选 2 项 · 4.4 MB」
 * 同样是写死的（`prototype/relay.html:4150` 与 `:4208` 逐字相同），不是算出来的。
 *
 * **MB / GB 保留一位小数、整数不补 `.0`** —— 这条规则是**折中**，不是从原型推出来的
 * 唯一解：9 个 MB 样本里 8 个带一位小数（`2.4` / `45.2` / `12.4` / `8.2` / `3.1` /
 * `18.6` / `31.8` / `86.2 MB`），`640 MB` 是整数 —— **但第 10 个样本是反例**：
 * `prototype/relay.html:5289`（桌面「数据与存储」页）逐字写 `86.2 MB · 其中缓存 12.0 MB`，
 * 那里**整数也补了 `.0`**。⇒ 原型自己两处不一致（`12.0` 补、`640` 不补），推不出唯一规则；
 * 取"整数不省 `.0`"是因为它把 `12.0 MB` 印成 `12 MB` 只是少一位小数，而反过来会把
 * `640 MB` 印成 `640.0 MB`。这一例登记在 `design/fidelity-deviations.yaml`
 * （`dimension: 字节数格式`），**改动要人批**。
 * KB 原型一律整数（`812` / `316` / `842` / `38 KB`），故 KB 保持整数。
 */
/**
 * 计数（「共 1280 项」这种）按千位加分隔符：`1280` → `1,280`。
 *
 * 为什么要有它：原型把 4 位以上的计数写成 `共 1,280 项`、`2,000`（`prototype/relay.html`，
 * `screenDPeerCabinet` 那张卡与「一次最多 2,000 个文件」那句），而 `%1$d` 画不出分隔符。
 * 与 [formatBytes] 同一条理由 —— 原型里那些数字是手写的演示字符串，量具看不见，
 * 只有人能看出「代码少了一个逗号」。
 *
 * 用整数算术，不碰 `Double`：这是要逐字节比对的截图文案。
 */
fun formatCount(value: Int): String = formatCount(value.toLong())

fun formatCount(value: Long): String {
    val negative = value < 0
    val digits = value.toString().removePrefix("-")
    val grouped = buildString {
        digits.forEachIndexed { i, c ->
            if (i > 0 && (digits.length - i) % 3 == 0) append(',')
            append(c)
        }
    }
    return if (negative) "-$grouped" else grouped
}

fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> tenths(bytes, 1024L * 1024) + " MB"
    else -> tenths(bytes, 1024L * 1024 * 1024) + " GB"
}

/**
 * 一位小数（四舍五入到十分位），整数不补 `.0`。
 *
 * 用**整数算术**而不是浮点格式化：`Double.toString()` 在不同平台上对 `45.2` 这类值
 * 可能给出 `45.199999999999996`，而这里是显示文案，必须逐字稳定（截图门禁要逐字节比对）。
 */
private fun tenths(bytes: Long, unit: Long): String {
    val scaled = (bytes * 10 + unit / 2) / unit
    val whole = scaled / 10
    val frac = scaled % 10
    return if (frac == 0L) "$whole" else "$whole.$frac"
}

/**
 * 徽标数字的显示文案。
 *
 * 超过两位数就不再报准确值：徽标回答的是「有没有新东西」，不是「精确到个位的计数器」，
 * 而三位数会把页签图标或标签挤变形。页签徽标与会话标签徽标共用这一条规则，
 * 否则同一个未读数会在两处显示成两个样子。
 */
@Composable
fun badgeCountText(count: Int): String =
    if (count > BADGE_MAX) stringResource(Res.string.nav_badge_overflow) else count.toString()

/** 徽标还能容得下的最大数字，再大就退化成「99+」。 */
private const val BADGE_MAX = 99

/**
 * 转发目标选择对话框。列出当前可用会话（单聊好友与群聊），点击后触发 [onSelectTarget]。
 */
@Composable
fun ForwardTargetDialog(
    sessions: List<ConversationUi>,
    onSelectTarget: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sp = LocalAppSpacing.current
    // 骨架（底色 / 20dp 圆角 / 16sp·700 标题 / 描边按钮）见同目录 `RelayDialog.kt`。
    RelayDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.chat_forward_title)) },
        content = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(sp.xs),
            ) {
                items(sessions, key = { it.convId }) { conv ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectTarget(conv.convId) }
                            .padding(horizontal = sp.sm, vertical = sp.md10),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(sp.md10),
                    ) {
                        PeerAvatar(conv.title, isAgent = conv.isAgent, size = 36.dp, isGroup = conv.isGroup)
                        Text(
                            text = conv.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
        dismissButton = {
            RelayDialogButton(text = stringResource(Res.string.common_cancel), onClick = onDismiss)
        },
    )
}
