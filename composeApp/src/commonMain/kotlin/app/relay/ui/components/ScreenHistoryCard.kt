// SPDX-License-Identifier: GPL-3.0-only

package app.relay.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.relay.app.SCREEN_INTERRUPTED_REASON
import app.relay.core.nowMillis
import app.relay.core.protocol.ScreenEndReasons
import app.relay.core.protocol.ScreenPhase
import app.relay.core.protocol.ScreenRejectReasons
import app.relay.core.protocol.ScreenRole
import app.relay.generated.resources.Res
import app.relay.generated.resources.ic_screen_share
import app.relay.generated.resources.screen_card_duration
import app.relay.generated.resources.screen_card_duration_at
import app.relay.generated.resources.screen_card_duration_unknown
import app.relay.generated.resources.screen_card_ended_at
import app.relay.generated.resources.screen_card_requested_at
import app.relay.generated.resources.screen_card_role_sharer
import app.relay.generated.resources.screen_card_role_viewer
import app.relay.generated.resources.screen_card_started_at
import app.relay.generated.resources.screen_card_state_active
import app.relay.generated.resources.screen_card_state_awaiting_consent
import app.relay.generated.resources.screen_card_state_busy_peer
import app.relay.generated.resources.screen_card_state_busy_self
import app.relay.generated.resources.screen_card_state_canceled
import app.relay.generated.resources.screen_card_state_capture_stopped
import app.relay.generated.resources.screen_card_state_connecting
import app.relay.generated.resources.screen_card_state_declined_peer
import app.relay.generated.resources.screen_card_state_declined_self
import app.relay.generated.resources.screen_card_state_disconnected
import app.relay.generated.resources.screen_card_state_ended
import app.relay.generated.resources.screen_card_state_interrupted
import app.relay.generated.resources.screen_card_state_locked
import app.relay.generated.resources.screen_card_state_permission_denied
import app.relay.generated.resources.screen_card_state_protocol_error
import app.relay.generated.resources.screen_card_state_requesting
import app.relay.generated.resources.screen_card_state_suspended
import app.relay.generated.resources.screen_card_state_timeout
import app.relay.generated.resources.screen_card_state_unsupported
import app.relay.generated.resources.screen_card_text_active
import app.relay.generated.resources.screen_card_text_awaiting_consent
import app.relay.generated.resources.screen_card_text_busy_peer
import app.relay.generated.resources.screen_card_text_busy_self
import app.relay.generated.resources.screen_card_text_canceled
import app.relay.generated.resources.screen_card_text_capture_failed
import app.relay.generated.resources.screen_card_text_connecting
import app.relay.generated.resources.screen_card_text_declined_peer
import app.relay.generated.resources.screen_card_text_declined_self
import app.relay.generated.resources.screen_card_text_disconnected
import app.relay.generated.resources.screen_card_text_ended
import app.relay.generated.resources.screen_card_text_interrupted
import app.relay.generated.resources.screen_card_text_locked
import app.relay.generated.resources.screen_card_text_permission_denied
import app.relay.generated.resources.screen_card_text_protocol_error
import app.relay.generated.resources.screen_card_text_requesting
import app.relay.generated.resources.screen_card_text_suspended
import app.relay.generated.resources.screen_card_text_timeout
import app.relay.generated.resources.screen_card_text_unsupported
import app.relay.generated.resources.screen_card_title
import app.relay.generated.resources.screen_card_view_only
import app.relay.ui.theme.IconSizes
import app.relay.ui.theme.LocalAppSpacing
import app.relay.ui.theme.accents
import app.relay.app.model.MessageUi
import app.relay.app.model.ScreenRecordUi
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 聊天流里那条**屏幕协助历史卡**（原型 `m-screencard`，对齐设计规范）。
 *
 * 它是一条**独立的消息**，不是气泡的附件：它被渲染成消息流里的一行
 * （`MessageRow.vue:143`，`<ScreenHistoryCard>` 是 `.message-stack` 的第一个孩子），
 * 左右由 `isMine`（= 本侧是不是 viewer）决定，与普通消息同一条规矩。
 * 原型里它画在一条文字气泡下面，那是演示稿的摆法 —— 真实产品里它自己占一行，
 * 因为数据模型上它就是一条消息（见 `MessageUi.screen`）。
 *
 * 全文**没有按钮**、不接长按菜单：原型 annot 逐字「长按卡片没有菜单：代码里那条卡片
 * 全文没有按钮，也不接右键菜单」。
 *
 * [now] 只用于「这是不是今天」那一档判断（时间分割线规则）。做成参数是因为
 * 截图帧必须**逐字节可复现**：如果卡片自己去读系统时钟，参考图就会在跨日之后失效
 * （今天的记录写「14:22」，明天同一条写成「2026-10-12 14:22」）。生产侧用默认值。
 */
@Composable
fun ScreenHistoryCard(
    record: ScreenRecordUi,
    modifier: Modifier = Modifier,
    now: Long = nowMillis(),
) {
    // 底色按**角色**分，与聊天气泡的左右归属是同一套语言：
    // 卡片样式 `.screen-card{background:var(--bubble-peer)}` + `.mine{background:var(--bubble-mine)}`；
    // 原型 `prototype/relay.html:1064` 那句自述也逐字写着「宽度 260、**viewer 用 --bubble-mine**」
    // （它下面 :1067 的 `.screencard.active{...}` 只是把这条规则写在演示里那唯一一张卡上 ——
    //  演示卡恰好既是 viewer 又是 active，两条规则在那一张上重合，靠那一行分不出来）。
    // 所以 viewer（我看别人的屏幕）⇒ `--bubble-mine`，对端 ⇒ `--bubble-peer`。
    val mine = record.role == ScreenRole.Viewer
    val active = record.phase == ScreenPhase.Active
    val details = screenCardDetails(record)
    Surface(
        color = if (mine) {
            // `--bubble-mine:#B7E4C7` = 本仓 `primaryContainer`（与己方气泡同一个 token）。
            MaterialTheme.colorScheme.primaryContainer
        } else {
            // `--bubble-peer:#F0EFE9` = 本仓 `surfaceVariant`（与对方气泡同一个 token）。
            MaterialTheme.colorScheme.surfaceVariant
        },
        // 原型 `.screencard{border-radius:12px}` = `RelayShapes.medium`（12dp）—— 走槽位，
        // 不写裸 `RoundedCornerShape(12.dp)`（那会被 `:designTokenUsageGate` 记成欠债）。
        // `.screencard{border:1px solid var(--hair)}`；`outlineVariant` 即
        // `--hair` 的真值（半透明黑，见 ui/theme/Color.kt）。
        // **边框随角色、不随 active/Ended**：`.screen-card` 的基础样式就带这条边框、
        // `.mine` 只覆盖底色，所以己方（viewer）那张与己方气泡一样不描边。原型
        // `.screencard.active{border-color:transparent}` 之所以也抹掉边框，是因为演示里那张卡
        // 恰好既 viewer 又 active —— 两条规则重合，光看原型分不出是谁在起作用（真机取证时
        // 独立验证者指出这条注释原先写成「Ended 才画边框」，与代码矛盾）。
        border = if (mine) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            // 原型 `.screencard{width:260px;max-width:100%}`：`widthIn` 在前把可用宽度
            // 夹到 260，`fillMaxWidth` 再吃满这个夹过的上限 ⇒ 窄屏上自动缩到可用宽度。
            .widthIn(max = 260.dp)
            .fillMaxWidth()
            .semantics { contentDescription = details },
    ) {
        // `.screencard{padding:9px 11px;display:flex;gap:9px;align-items:flex-start}`
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // `.si{width:20px;height:20px;border-radius:6px;background:var(--surface-3);
            //      color:var(--on-2)}` + `.si svg{width:14px;height:14px}`
            Box(
                modifier = Modifier
                    .size(IconSizes.Title)
                    // `border-radius:6px` 是原型真值，而 `RelayShapes` 只有 4/8/12/16/28 ⇒
                    // 档外取值，按简报 §4 政策①保留并登记为保真档（不就近 snap 到 4 或 8）。
                    .clip(RoundedCornerShape(6.dp))
                    // `--surface-3` 是填充 token，不是做描边的 `--hair`。
                    .background(MaterialTheme.accents.surface3),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_screen_share),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(IconSizes.Inline),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                // `.st1{font-size:12px;font-weight:600;display:flex;align-items:center;gap:6px}`
                // —— 状态词**紧挨**着标题，不是被推到右端（规范中 `header{justify-content:space-between}`
                // 是另一套排版；原型这一条是设计侧明确的：`gap:6px` 且没有 `margin-left:auto`）。
                Row(
                    horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm6),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(Res.string.screen_card_title),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    // `.st1 .stt{font-weight:500;font-size:11px}` + `.active header span{color:var(--primary)}`
                    Text(
                        text = screenCardStatus(record),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                // `.st2{font-size:11px;color:var(--on-2);margin-top:2px}`
                Text(
                    text = screenCardSecondLine(record, now),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = LocalAppSpacing.current.xxs),
                )
            }
        }
    }
}

/**
 * 卡片主行那个**短状态词**（对应设计规范状态词表）。
 */
@Composable
fun screenCardStatus(record: ScreenRecordUi): String = stringResource(screenCardStatusKey(record))

/**
 * 同一件事的**完整句子**（对应设计规范完整句子）。
 *
 * 这句话是这条消息的正文：写进库、进会话列表预览、也是卡片的无障碍说明。
 * 挂起版本给**控制器**用 —— 状态机不在组合里，只能用 `getString`。
 */
suspend fun screenCardText(record: ScreenRecordUi): String = getString(screenCardTextKey(record))

/** 短状态词的资源键。纯函数（不碰组合），便于单测逐个码验证分叉。 */
fun screenCardStatusKey(record: ScreenRecordUi): StringResource = when (screenCardCase(record)) {
    ScreenCardCase.Requesting -> Res.string.screen_card_state_requesting
    ScreenCardCase.AwaitingConsent -> Res.string.screen_card_state_awaiting_consent
    ScreenCardCase.Connecting -> Res.string.screen_card_state_connecting
    ScreenCardCase.Active -> Res.string.screen_card_state_active
    ScreenCardCase.DeclinedSelf -> Res.string.screen_card_state_declined_self
    ScreenCardCase.DeclinedPeer -> Res.string.screen_card_state_declined_peer
    ScreenCardCase.BusySelf -> Res.string.screen_card_state_busy_self
    ScreenCardCase.BusyPeer -> Res.string.screen_card_state_busy_peer
    ScreenCardCase.Unsupported -> Res.string.screen_card_state_unsupported
    ScreenCardCase.PermissionDenied -> Res.string.screen_card_state_permission_denied
    ScreenCardCase.CaptureStopped -> Res.string.screen_card_state_capture_stopped
    ScreenCardCase.Timeout -> Res.string.screen_card_state_timeout
    ScreenCardCase.Disconnected -> Res.string.screen_card_state_disconnected
    ScreenCardCase.Locked -> Res.string.screen_card_state_locked
    ScreenCardCase.Suspended -> Res.string.screen_card_state_suspended
    ScreenCardCase.ProtocolError -> Res.string.screen_card_state_protocol_error
    ScreenCardCase.Interrupted -> Res.string.screen_card_state_interrupted
    ScreenCardCase.Canceled -> Res.string.screen_card_state_canceled
    ScreenCardCase.Ended -> Res.string.screen_card_state_ended
}

/** 完整句子的资源键。纯函数，同上。 */
fun screenCardTextKey(record: ScreenRecordUi): StringResource = when (screenCardCase(record)) {
    ScreenCardCase.Requesting -> Res.string.screen_card_text_requesting
    ScreenCardCase.AwaitingConsent -> Res.string.screen_card_text_awaiting_consent
    ScreenCardCase.Connecting -> Res.string.screen_card_text_connecting
    ScreenCardCase.Active -> Res.string.screen_card_text_active
    ScreenCardCase.DeclinedSelf -> Res.string.screen_card_text_declined_self
    ScreenCardCase.DeclinedPeer -> Res.string.screen_card_text_declined_peer
    ScreenCardCase.BusySelf -> Res.string.screen_card_text_busy_self
    ScreenCardCase.BusyPeer -> Res.string.screen_card_text_busy_peer
    ScreenCardCase.Unsupported -> Res.string.screen_card_text_unsupported
    ScreenCardCase.PermissionDenied -> Res.string.screen_card_text_permission_denied
    ScreenCardCase.CaptureStopped -> Res.string.screen_card_text_capture_failed
    ScreenCardCase.Timeout -> Res.string.screen_card_text_timeout
    ScreenCardCase.Disconnected -> Res.string.screen_card_text_disconnected
    ScreenCardCase.Locked -> Res.string.screen_card_text_locked
    ScreenCardCase.Suspended -> Res.string.screen_card_text_suspended
    ScreenCardCase.ProtocolError -> Res.string.screen_card_text_protocol_error
    ScreenCardCase.Interrupted -> Res.string.screen_card_text_interrupted
    ScreenCardCase.Canceled -> Res.string.screen_card_text_canceled
    ScreenCardCase.Ended -> Res.string.screen_card_text_ended
}

/**
 * 一条记录落在哪一档文案上。
 *
 * 单列成一个枚举而不是写两份 `when`：状态词与完整句子必须**永远同步**（同一个原因下
 * 短词写"已超时"、长句写"对方拒绝"就是自相矛盾），两处各写一遍 `when` 迟早会漂。
 *
 * 状态转换规则：**先看相位、再看原因**。
 * `declined`/`busy` 按 role 分叉，其余码两侧同义（状态机规则一致）。
 */
fun screenCardCase(record: ScreenRecordUi): ScreenCardCase = when (record.phase) {
    ScreenPhase.Requesting -> ScreenCardCase.Requesting
    ScreenPhase.AwaitingConsent -> ScreenCardCase.AwaitingConsent
    ScreenPhase.Preparing, ScreenPhase.Connecting -> ScreenCardCase.Connecting
    ScreenPhase.Active -> ScreenCardCase.Active
    // Ended / Error：相位已经说不出为什么，只能看原因码。
    ScreenPhase.Ended, ScreenPhase.Error -> when (record.reason) {
        // `declined` 与 `busy` 两侧语义相反：**本侧是 sharer** 时这两个码说的是
        // "我拒绝了/我这台机器忙着"，本侧是 viewer 时说的是"对方拒绝了/对方忙着"。
        // 同一个字符串推不出同一句话，所以必须带 role 分叉（按角色分流）。
        ScreenRejectReasons.DECLINED ->
            if (record.role == ScreenRole.Sharer) ScreenCardCase.DeclinedSelf else ScreenCardCase.DeclinedPeer
        ScreenRejectReasons.BUSY ->
            if (record.role == ScreenRole.Sharer) ScreenCardCase.BusySelf else ScreenCardCase.BusyPeer
        ScreenRejectReasons.UNSUPPORTED -> ScreenCardCase.Unsupported
        ScreenRejectReasons.PERMISSION_DENIED -> ScreenCardCase.PermissionDenied
        // `capture-failed`（我这边采集起不来）与 `capture-ended`（采集被系统收了）
        // 在此处共用同一种文案。
        ScreenRejectReasons.CAPTURE_FAILED, ScreenEndReasons.CAPTURE_ENDED -> ScreenCardCase.CaptureStopped
        // 只列一次：`ScreenRejectReasons.TIMEOUT` 与 `ScreenEndReasons.TIMEOUT` 同值
        // （都是 "timeout"），列两次是同一个分支写两遍。
        ScreenRejectReasons.TIMEOUT -> ScreenCardCase.Timeout
        ScreenEndReasons.DISCONNECTED -> ScreenCardCase.Disconnected
        ScreenEndReasons.LOCKED -> ScreenCardCase.Locked
        ScreenEndReasons.SUSPENDED -> ScreenCardCase.Suspended
        ScreenEndReasons.PROTOCOL_ERROR -> ScreenCardCase.ProtocolError
        // 本地卫生逻辑写的码，不在协议取值域里（见 SCREEN_INTERRUPTED_REASON）。
        SCREEN_INTERRUPTED_REASON -> ScreenCardCase.Interrupted
        // 兜底按"有没有真的开始过"分：没开始 = 请求被取消，开始过 = 结束了
        // （默认：startedAt 未设定时为已取消，否则为已结束）。
        else -> if (record.startedAt == null) ScreenCardCase.Canceled else ScreenCardCase.Ended
    }
}

/** [screenCardCase] 的取值。 */
enum class ScreenCardCase {
    Requesting,
    AwaitingConsent,
    Connecting,
    Active,
    DeclinedSelf,
    DeclinedPeer,
    BusySelf,
    BusyPeer,
    Unsupported,
    PermissionDenied,
    CaptureStopped,
    Timeout,
    Disconnected,
    Locked,
    Suspended,
    ProtocolError,
    Interrupted,
    Canceled,
    Ended,
}

/**
 * 副行：「时间 · 时长/仅查看」。
 *
 * 历史解析需要注意的边界情况：
 * - **时间取 `startedAt ?? requestedAt`**（`ScreenHistoryCard.vue:32`）：没开始过的记录
 *   只能显示"什么时候请求的"；
 * - **后半段不是时长**（`:33`）：只有 `phase === 'ended'` 且 `startedAt` 有值时才写「时长」，
 *   其余一律「仅查看」。所以**正在协助中的那一条写的是「14:22 · 仅查看」**，
 *   不是「14:22 · 03:41」—— 原型 annot 专门强调过这一点。
 *   我们的终态有两个（`Ended` 与 `Error`），上游旧协议通常只有一个 `ended`，所以这里判 `isTerminal`。
 */
@Composable
private fun screenCardSecondLine(record: ScreenRecordUi, now: Long): String {
    val clock = screenCardClock(record.startedAt ?: record.requestedAt, now)
    val tail = if (record.isTerminal && record.startedAt != null) {
        val duration = record.durationMs
            ?.let { screenDurationText(it) }
            ?: stringResource(Res.string.screen_card_duration_unknown)
        stringResource(Res.string.screen_card_duration, duration)
    } else {
        stringResource(Res.string.screen_card_view_only)
    }
    return "$clock · $tail"
}

/**
 * 悬停/无障碍说明（对应设计规范细节描述）。
 *
 * 移动端没有悬停，所以这份说明只有读屏用户听得到 —— 但那正是它存在的理由：
 * 卡片上只有"屏幕协助 + 状态词 + 时间"，完整原因（谁请求的、什么时候、多久）
 * 只能从这里出来。
 */
@Composable
private fun screenCardDetails(record: ScreenRecordUi): String {
    val withSeconds: (Long) -> String = { at -> "${screenCardClock(at, nowMillis())}:${secondsOf(at)}" }
    val lines = mutableListOf(
        "${stringResource(Res.string.screen_card_title)} · ${stringResource(Res.string.screen_card_view_only)}",
        stringResource(
            if (record.role == ScreenRole.Viewer) {
                Res.string.screen_card_role_viewer
            } else {
                Res.string.screen_card_role_sharer
            },
        ),
        stringResource(screenCardTextKey(record)),
        stringResource(Res.string.screen_card_requested_at, withSeconds(record.requestedAt)),
    )
    record.startedAt?.let { lines += stringResource(Res.string.screen_card_started_at, withSeconds(it)) }
    if (record.isTerminal) {
        val ended = record.endedAt
            ?.let { withSeconds(it) }
            ?: stringResource(Res.string.screen_card_duration_unknown)
        lines += stringResource(Res.string.screen_card_ended_at, ended)
        record.startedAt?.let {
            val duration = record.durationMs
                ?.let { ms -> screenDurationText(ms) }
                ?: stringResource(Res.string.screen_card_duration_unknown)
            lines += stringResource(Res.string.screen_card_duration_at, duration)
        }
    }
    return lines.joinToString("\n")
}

/**
 * 时长文案：`mm:ss`，满一小时才带小时段（时长格式化规范）。
 *
 * 与 `formatBytes` 同一条纪律：**整数算术**，不碰浮点 —— 这是要逐字节比对的截图文案。
 */
fun screenDurationText(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    val minutes = total / 60L
    val hours = minutes / 60L
    val mm = (minutes % 60L).toString().padStart(2, '0')
    val ss = (total % 60L).toString().padStart(2, '0')
    return if (hours > 0L) "$hours:$mm:$ss" else "$mm:$ss"
}

/**
 * 卡片上的时间：**今天只写时分，跨天带上年月日**（时间分割线规则）。
 *
 * 时区约定跟 `formatClock` 走（同一个硬编码 UTC+8 口径，见那边的注释）—— 两者必须在
 * 同一个时区里判"是不是今天"，否则会出现"时钟显示 23:50、日期却算成第二天"。
 * 日期换算用 Hinnant 的 days→civil 算法（无依赖、纯整数、负数年份也对）。
 */
fun screenCardClock(at: Long, now: Long): String {
    val clock = formatClock(at)
    val day = (at + CLOCK_OFFSET_MILLIS).floorDiv(DAY_MILLIS)
    val today = (now + CLOCK_OFFSET_MILLIS).floorDiv(DAY_MILLIS)
    if (day == today) return clock
    val (year, month, dayOfMonth) = civilDate(day)
    return "$year-${month.toString().padStart(2, '0')}-${dayOfMonth.toString().padStart(2, '0')} $clock"
}

/** 秒（两位）。只有无障碍说明用得上（时间拼装规则）。 */
private fun secondsOf(at: Long): String = ((at / 1000L) % 60L).toString().padStart(2, '0')

private const val DAY_MILLIS: Long = 86_400_000L

/** 与 `formatClock` 相同的时区偏移（UTC+8）。 */
private const val CLOCK_OFFSET_MILLIS: Long = 8L * 60L * 60L * 1000L

/** days-since-1970 → (year, month, day)。Hinnant `civil_from_days`。 */
private fun civilDate(days: Long): Triple<Int, Int, Int> {
    val z = days + 719_468L
    val era = (if (z >= 0L) z else z - 146_096L) / 146_097L
    val doe = z - era * 146_097L
    val yoe = (doe - doe / 1_460L + doe / 36_524L - doe / 146_096L) / 365L
    val y = yoe + era * 400L
    val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)
    val mp = (5L * doy + 2L) / 153L
    val d = doy - (153L * mp + 2L) / 5L + 1L
    val m = if (mp < 10L) mp + 3L else mp - 9L
    return Triple((if (m <= 2L) y + 1L else y).toInt(), m.toInt(), d.toInt())
}
