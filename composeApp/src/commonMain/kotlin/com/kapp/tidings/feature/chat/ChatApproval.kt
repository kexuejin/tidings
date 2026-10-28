// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.kapp.tidings.ui.components.AppButtonSize
import com.kapp.tidings.ui.components.AppTextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import com.kapp.tidings.core.fs.readFileBytes
import com.kapp.tidings.core.ioDispatcher
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.platform.RelayAudioPlayback
import com.kapp.tidings.platform.decodeImageBytes
import com.kapp.tidings.ui.theme.LocalAppSpacing
import com.kapp.tidings.ui.theme.RelayShapes
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.stringResource
import com.kapp.tidings.domain.model.AgentApprovalUi
import com.kapp.tidings.domain.model.DeliveryState
import com.kapp.tidings.feature.agents.AgentApprovalRow
import com.kapp.tidings.generated.resources.chat_delivery_failed
import com.kapp.tidings.generated.resources.chat_delivery_queued
import com.kapp.tidings.generated.resources.chat_delivery_recalled
import com.kapp.tidings.generated.resources.chat_delivery_sending
import com.kapp.tidings.generated.resources.chat_delivery_sent
import com.kapp.tidings.generated.resources.file_audio_unsupported
import com.kapp.tidings.generated.resources.file_play_audio
import com.kapp.tidings.generated.resources.ic_check_double
import com.kapp.tidings.generated.resources.ic_clock_pending
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
internal fun InlineImagePreview(
    path: String,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
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

    Surface(
        shape = RelayShapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .size(naturalWidth * scale, naturalHeight * scale)
            .clip(RelayShapes.medium)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
    ) {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Fit,
        )
    }
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
        AppTextButton(
            text = stringResource(Res.string.file_play_audio),
            onClick = { unsupported = !RelayAudioPlayback.play(path) },
            size = AppButtonSize.Small,
            modifier = Modifier.padding(top = sp.xxs),
        )
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
    DeliveryState.Sending -> stringResource(Res.string.chat_delivery_sending)
    DeliveryState.Sent -> stringResource(Res.string.chat_delivery_sent)
    DeliveryState.Queued -> stringResource(Res.string.chat_delivery_queued)
    DeliveryState.Failed -> stringResource(Res.string.chat_delivery_failed)
    DeliveryState.Recalled -> stringResource(Res.string.chat_delivery_recalled)
}

/**
 * 投递状态图标，对应 design-spec 里的 `delivered`（ic_check_double）与
 * `queued` / `sending`（ic_clock_pending —— 两者都是「还没到对方手里」的在途态，
 * 共用同一枚时钟图标；区分靠旁边的文案）。
 * 失败和撤回没有对应素材，返回 null 走纯文案，不拿别的图标凑数。
 */
internal fun DeliveryState.stateIcon(): DrawableResource? = when (this) {
    DeliveryState.Sent -> Res.drawable.ic_check_double
    DeliveryState.Queued -> Res.drawable.ic_clock_pending
    // 「发送中」同样是挂在发送侧的在途态：只有文字没有图标时，这一档读起来
    // 像「状态行少画了东西」。与 Queued 共用 ic_clock_pending（同一族语义）。
    DeliveryState.Sending -> Res.drawable.ic_clock_pending
    else -> null
}

/**
 * 会话内待审批条 —— **复用看板那一行**（[AgentApprovalRow]），不另画一份。
 *
 * ## 为什么必须同一行
 *
 * 修复前这里是"另一套审批 UI"：只显示工具名 + **原始 sessionId**、没有 summary、没有
 * L3 确认卡、没有「本会话免审」。于是同一条高危请求，在 Agent 看板里需要二次确认并说清
 * 要执行什么，在聊天里却是一颗按钮盲批 —— 安全档位随入口变化，用户根本不知道自己在批什么。
 *
 * ## 为什么不再是红底警告条
 *
 * 审批条出现时用户已经在会话里，红底横幅会把"待你处置"读成"出错了"。改为普通卡面 +
 * 发丝线：风险由行内的 L1/L2/L3 徽标表达（那才是分级该待的地方），而不是整块底色的情绪。
 */
@Composable
internal fun ChatApprovalBanner(
    approvals: List<AgentApprovalUi>,
    now: Long,
    onRespond: ((approvalId: String, allow: Boolean) -> Unit)?,
    onRespondChoice: ((approvalId: String, allow: Boolean, allowSession: Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier,
    isReadOnly: ((AgentApprovalUi) -> Boolean)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            approvals.forEachIndexed { index, approval ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                AgentApprovalRow(
                    approval = approval,
                    now = now,
                    onRespond = onRespond,
                    onRespondChoice = onRespondChoice,
                    isReadOnly = isReadOnly?.invoke(approval) == true,
                )
            }
        }
    }
}
