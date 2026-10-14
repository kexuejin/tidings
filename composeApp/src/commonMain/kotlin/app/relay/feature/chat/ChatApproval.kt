// SPDX-License-Identifier: GPL-3.0-only

package app.relay.feature.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.relay.core.protocol.GroupMeta
import app.relay.core.fs.readFileBytes
import app.relay.core.ioDispatcher
import app.relay.generated.resources.Res
import androidx.compose.material3.OutlinedButton
import app.relay.platform.RelayAudioPlayback
import app.relay.platform.decodeImageBytes
import app.relay.ui.theme.LocalAppSpacing
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.stringResource
import app.relay.app.model.AgentApprovalUi
import app.relay.app.model.ConversationUi
import app.relay.app.model.DeliveryState
import app.relay.generated.resources.agent_approval_allow
import app.relay.generated.resources.agent_approval_deny
import app.relay.generated.resources.chat_delivery_failed
import app.relay.generated.resources.chat_delivery_queued
import app.relay.generated.resources.chat_delivery_recalled
import app.relay.generated.resources.chat_delivery_sent
import app.relay.generated.resources.file_audio_unsupported
import app.relay.generated.resources.file_play_audio
import app.relay.generated.resources.ic_check_double
import app.relay.generated.resources.ic_clock_pending
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * 图片气泡里的缩略图。
 *
 * 读盘与解码都在 [ioDispatcher] 上，不占 Compose 的帧：一张 5MB 的 PNG 解码要几十毫秒，
 * 放在组合里做就是掉帧。`produceState` 以 [path] 为 key，同一条消息换了路径会重新解。
 *
 * 解不出来（后缀骗人、数据坏了、超过 [readFileBytes] 的上限）就**什么都不画** ——
 * 上面那张文件卡还在，名字和大小都还在，用户没有失去任何信息；弹一句"图片损坏"
 * 只会为一条本来能正常收发的文件消息制造噪音。
 *
 * 尺寸按位图的真实宽高比算，最长边不超过 [MAX_PREVIEW_SIDE]，且**不放大小图**
 * （一张 16×16 的图标不该被拉成 240dp）。contentDescription 给 null 是有意的：
 * 紧挨着它的那行文字就是文件名，再读一遍是重复。
 */
@Composable
internal fun InlineImagePreview(path: String, onClick: (() -> Unit)? = null) {
    val sp = LocalAppSpacing.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(ioDispatcher) {
            readFileBytes(path)?.let { decodeImageBytes(it) }
        }
    }
    val image = bitmap ?: return

    val density = LocalDensity.current
    val naturalWidth = with(density) { image.width.toDp() }
    val naturalHeight = with(density) { image.height.toDp() }
    val longest = maxOf(naturalWidth, naturalHeight)
    val scale = if (longest > MAX_PREVIEW_SIDE) MAX_PREVIEW_SIDE / longest else 1f

    Image(
        bitmap = image,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .padding(top = sp.sm6)
            .size(naturalWidth * scale, naturalHeight * scale)
            .clip(MaterialTheme.shapes.small)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
    )
}

/**
 * 音频气泡里的「播放」按钮。
 *
 * 没有「停止」、也没有任何播放状态，理由写在 `AudioPlayback` 的 KDoc 里：
 * 三个平台的播放器都报不了「播完了」，做一个不会复位的切换按钮就是"界面状态过期没自愈"。
 *
 * 播放失败必须说出来：桌面只有 WAV/AIFF/AU，一个 m4a 语音在桌面上按下去会失败。
 * 静默失败正是这个仓库记过的缺陷类型，所以这里把 [RelayAudioPlayback.play] 的 false
 * 变成一句可见的话。失败标记跟着 `transferId` 走：换一条消息就重新开始。
 */
@Composable
internal fun InlineAudioPreview(transferId: String, path: String) {
    val sp = LocalAppSpacing.current
    var unsupported by remember(transferId) { mutableStateOf(false) }
    Column {
        TextButton(
            onClick = { unsupported = !RelayAudioPlayback.play(path) },
            contentPadding = PaddingValues(),
            modifier = Modifier.padding(top = sp.xxs),
        ) {
            Text(
                text = stringResource(Res.string.file_play_audio),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (unsupported) {
            Text(
                text = stringResource(Res.string.file_audio_unsupported),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** 缩略图最长边上限。预览再大也只是聊天里的一小块。 */
internal val MAX_PREVIEW_SIDE = 240.dp

/** 只对**已知**的投递状态取文案；`null`（文件气泡）在调用点就被跳过了。 */
@Composable
internal fun DeliveryState.label(): String = when (this) {
    DeliveryState.Sent -> stringResource(Res.string.chat_delivery_sent)
    DeliveryState.Queued -> stringResource(Res.string.chat_delivery_queued)
    DeliveryState.Failed -> stringResource(Res.string.chat_delivery_failed)
    DeliveryState.Recalled -> stringResource(Res.string.chat_delivery_recalled)
}

/**
 * 投递状态图标，对应 design-spec 里的 `delivered`（ic_check_double）与 `queued`（ic_clock_pending）。
 * 失败和撤回没有对应素材，返回 null 走纯文案，不拿别的图标凑数。
 */
internal fun DeliveryState.stateIcon(): DrawableResource? = when (this) {
    DeliveryState.Sent -> Res.drawable.ic_check_double
    DeliveryState.Queued -> Res.drawable.ic_clock_pending
    else -> null
}

/**
 * 群里**除自己以外**的人数，用来和 `memberOnlineCount` 比。
 *
 * 两个数都来自同一份成员表，但一个含自己一个不含（见 `ConversationUi.memberOnlineCount`
 * 的 KDoc）。写成 `members.size` 就是拿"含自己"去比"不含自己"，永远少算一个 ——
 * 表现是所有人都在线时仍显示"有成员不在线"。自己理论上恒在成员表里（`GroupMerge`
 * 不接受自己不在的群），但这里仍做 `coerceAtLeast(0)`：一个空成员表不该让
 * 比较变成负数而永远显示提示。
 */
internal fun othersInGroup(group: GroupMeta): Int = (group.members.size - 1).coerceAtLeast(0)

@Composable
internal fun ChatApprovalBanner(
    approvals: List<AgentApprovalUi>,
    onRespond: (approvalId: String, allow: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = LocalAppSpacing.current.md, vertical = LocalAppSpacing.current.sm),
            verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.xs),
        ) {
            approvals.forEach { approval ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${approval.tool ?: "action"} · ${approval.sessionId}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.xs)) {
                        OutlinedButton(
                            onClick = { onRespond(approval.approvalId, false) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text(stringResource(Res.string.agent_approval_deny), style = MaterialTheme.typography.labelSmall)
                        }
                        Button(
                            onClick = { onRespond(approval.approvalId, true) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text(stringResource(Res.string.agent_approval_allow), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}
