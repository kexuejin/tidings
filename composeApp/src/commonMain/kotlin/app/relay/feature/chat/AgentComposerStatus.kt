// SPDX-License-Identifier: GPL-3.0-only

package app.relay.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.relay.app.isStaleBusy
import app.relay.core.nowMillis
import app.relay.generated.resources.Res
import app.relay.generated.resources.chat_composer_status_awaiting_approval
import app.relay.generated.resources.chat_composer_status_stopped
import app.relay.generated.resources.chat_composer_status_thinking
import app.relay.generated.resources.chat_composer_status_unresponsive
import app.relay.generated.resources.ic_terminal
import app.relay.ui.theme.LocalAppSpacing
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 输入区上方那行 agent 状态（真机反馈 #3）。
 *
 * ## 为什么需要它
 *
 * agent 忙起来时聊天页与普通会话长得一模一样：没有「正在思考」，用户分不清
 * 「它在干活、只是还没回」和「它卡住了 / 根本没收到」。这个仓库反复抓的又是
 * 「不说假话」—— 所以这里**只翻译真实存在的信号**，取不到就什么都不画。
 *
 * ## 状态从哪来（全部是既有事实，没有一个是编的）
 *
 * - `AgentSessionUi.state`（PPE-1 §6.1 原样保留的字符串，`busy` / `error` / …）；
 * - `agentEvents` 的最近时刻与 `AgentSessionUi.sortActivityAt`：会话最后一次动过的时刻；
 * - `isStaleBusy(...)`（与工作区看板、控制器的自愈同一条判据）：busy 但已经超过僵死门限；
 * - `agentRuns` 里还没到终态的 run：宿主明确说了「有一条执行在跑」；
 * - `agentApprovals` 里 pending 的那条：用户此刻唯一能推进的事。
 *
 * ## 为什么抽成独立 composable
 *
 * 位置是产品决定（当前放在输入框正上方一行），不是这里的实现细节：整个聊天页只有
 * 一个调用点（`ChatScreen` 输入区之前），手机内置输入栏与桌面 `composerSlot` 共用它。
 * 以后要挪到头部或消息流里，只动那一行。
 */
internal enum class AgentComposerStatus {
    /** 会话 busy 或有一条在跑的 run，且最近有活动：真的在干活。 */
    Thinking,

    /** 这个会话有 pending 审批：agent 在等人，不是卡住。 */
    AwaitingApproval,

    /** busy / 有 run，但最近一次活动已经超过僵死门限：如实说「没反应」。 */
    Unresponsive,

    /** 宿主把会话报成了 error：执行已经停了。 */
    Stopped,
}

/** 输入区状态条的渲染资源；`null` = 沉默（没有证据，不画）。 */
internal fun agentComposerStatusOf(
    agentInvolved: Boolean,
    sessionState: String?,
    lastActivityAt: Long,
    hasPendingApproval: Boolean,
    hasActiveRun: Boolean,
    now: Long,
): AgentComposerStatus? {
    // 这个会话根本没有 agent（普通同事私聊、纯人类群）：这条线整条不出现。
    if (!agentInvolved) return null
    // 待审批优先：它比「busy」更具体，也是用户此刻唯一能推进的事。
    if (hasPendingApproval) return AgentComposerStatus.AwaitingApproval
    val working = sessionState == AGENT_STATE_BUSY || hasActiveRun
    if (working) {
        // 已经在干活、但太久没有任何新动作：说「无响应」而不是继续演「正在思考中」。
        // lastActivityAt 为 0（对端没报时间）时 isStaleBusy 返回 false —— 那只是
        // 「我们无法判断」，此时保留宿主自报的 busy，不凭空说它死了。
        return if (isStaleBusy(lastActivityAt, now)) {
            AgentComposerStatus.Unresponsive
        } else {
            AgentComposerStatus.Thinking
        }
    }
    if (sessionState == AGENT_STATE_ERROR) return AgentComposerStatus.Stopped
    // idle / done / 协议里将来新增的取值：沉默。没有证据时不编一个「思考中」。
    return null
}

/** 会话状态里表示「正在干活」的取值（PPE-1 §6.1；与看板同一条字面量）。 */
private const val AGENT_STATE_BUSY: String = "busy"

/** 会话状态里表示「执行出错」的取值。 */
private const val AGENT_STATE_ERROR: String = "error"

/**
 * 状态条自己的滴答间隔。
 *
 * 为什么需要它：`now` 是上一层**构造参数时**的时刻，不是一根会走的钟；一个会话保持
 * busy 却再没有任何事件时，界面上没有任何东西会触发重组，那句「无响应」就永远不会出现。
 * 这里按真实时钟推进 [liveNow]，只用于判断「多久没动了」，不用于显示时间。
 */
private const val ComposerStatusTickMs: Long = 15_000L

/**
 * 输入区正上方的 agent 状态行。
 *
 * @param agentInvolved 这个会话是不是 agent（或挂着 agent 的工作区群）。false 时整行不出现。
 * @param sessionState 当前会话的 `AgentSessionUi.state`；null = 这一屏没有会话。
 * @param lastActivityAt 会话最后一次动过的时刻（会话活动时刻与事件流最近时刻取大）。
 * @param hasPendingApproval 当前会话有没有 pending 审批。
 * @param hasActiveRun 当前会话有没有未终止的 run。
 * @param now 本帧的（可能是夹具固定的）当前时刻；内部会用真实时钟继续推进。
 */
@Composable
internal fun ComposerStatusLine(
    agentInvolved: Boolean,
    sessionState: String?,
    lastActivityAt: Long,
    hasPendingApproval: Boolean,
    hasActiveRun: Boolean,
    now: Long,
    modifier: Modifier = Modifier,
) {
    // 不是 agent 会话：这条线整条不出现，连下面的滴答协程都不必建。
    if (!agentInvolved) return
    // 上一层每次重组都会给一个新的 now；把它同步进来，其余时间由下面的滴答推进。
    var liveNow by remember { mutableStateOf(now) }
    LaunchedEffect(now) { liveNow = now }
    LaunchedEffect(Unit) {
        while (true) {
            delay(ComposerStatusTickMs)
            liveNow = nowMillis()
        }
    }
    val status = agentComposerStatusOf(
        agentInvolved = agentInvolved,
        sessionState = sessionState,
        lastActivityAt = lastActivityAt,
        hasPendingApproval = hasPendingApproval,
        hasActiveRun = hasActiveRun,
        now = liveNow,
    ) ?: return
    val textRes: StringResource = when (status) {
        AgentComposerStatus.Thinking -> Res.string.chat_composer_status_thinking
        AgentComposerStatus.AwaitingApproval -> Res.string.chat_composer_status_awaiting_approval
        AgentComposerStatus.Unresponsive -> Res.string.chat_composer_status_unresponsive
        AgentComposerStatus.Stopped -> Res.string.chat_composer_status_stopped
    }
    // 「无响应 / 已停止」用错误色：它是在告诉用户「别干等」；正常干活与等待审批用次级色。
    val alarming = status == AgentComposerStatus.Unresponsive || status == AgentComposerStatus.Stopped
    val tint = if (alarming) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = LocalAppSpacing.current.lg,
                end = LocalAppSpacing.current.lg,
                bottom = LocalAppSpacing.current.xs,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.xs),
    ) {
        Icon(
            painter = painterResource(Res.drawable.ic_terminal),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(13.dp),
        )
        Text(
            text = stringResource(textRes),
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
