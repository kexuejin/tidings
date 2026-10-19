// SPDX-License-Identifier: GPL-3.0-only

package app.relay.feature.agents

import app.relay.app.model.GrantRequestStatus
import app.relay.core.protocol.MsgTypes

/**
 * 「申请授权」入口的可用档 —— 纯函数产出，便于单测钉住「两种失败不合并成一句」。
 *
 * ## 为什么要有这一层
 *
 * `RelayController.sendAgentGrantRequest` 的 KDoc 明写：返回 null 有两种含义且必须分得开
 * ——「本机没有运行时」与「对端没声明 ag1」。可是**只看返回值分不出来**（`handleSendGrantRequest`
 * 里三道闸都 `return null`），所以判因只能靠调用方手里的输入：本机节点在不在跑、对端声明了哪些能力。
 * 这一层把判因从 UI 里抽出来，单测才能钉住「不许合并成一句」。
 *
 * 判序与 `RelayControllerAgentComponent.handleSendGrantRequest` 的闸门顺序**逐条一致**
 * （runtime → ag1 → agr1）：顺序反了会把「运行时没起来」说成「对端不支持」，那是编原因。
 */
internal enum class GrantRequestEntry {
    /** 可以点。 */
    Ready,

    /** 已经发过一条、还没等到宿主表态（`RelayUiState.pendingGrantRequests`）。 */
    Pending,

    /** 对端没声明 `ag1` —— 它不是 agent 节点，申请没有意义。 */
    PeerNotAgent,

    /**
     * 对端没声明 `agr1` —— 它不认识 `agent-grant-request`。
     *
     * 这一档必须**禁用**而不是「按了再说」：那条帧在 RELIABLE 里，对不声明 agr1 的旧宿主
     * 发它，ACK 永远等不到，core 会把一台在线电脑误标成离线（详见 handleSendGrantRequest）。
     */
    PeerNoGrantLoop,
}

/** 由「对端能力 + 申请状态」定出入口该是哪种档。 */
internal fun grantRequestEntry(caps: List<String>, status: GrantRequestStatus): GrantRequestEntry = when {
    status == GrantRequestStatus.PENDING -> GrantRequestEntry.Pending
    MsgTypes.CAP_AGENT !in caps -> GrantRequestEntry.PeerNotAgent
    MsgTypes.CAP_AGENT_GRANT !in caps -> GrantRequestEntry.PeerNoGrantLoop
    else -> GrantRequestEntry.Ready
}

/**
 * `sendAgentGrantRequest` 返回 null 时的**判因**（UI 文案分档）。
 *
 * 闸门顺序照抄 `handleSendGrantRequest`：runtime → ag1 → agr1。`nodeRunning` 是
 * `state.network.isRunning`（运行时不在时节点不可能是 Running）。
 */
internal enum class GrantRequestFailure {
    /** 本机运行时没起来（重启中 / 还没启动）—— 与对端无关。 */
    NoRuntime,

    /** 对端没声明 `ag1`。 */
    PeerNotAgent,

    /** 对端没声明 `agr1`。 */
    PeerNoGrantLoop,
}

internal fun grantRequestFailure(caps: List<String>, nodeRunning: Boolean): GrantRequestFailure = when {
    !nodeRunning -> GrantRequestFailure.NoRuntime
    MsgTypes.CAP_AGENT !in caps -> GrantRequestFailure.PeerNotAgent
    MsgTypes.CAP_AGENT_GRANT !in caps -> GrantRequestFailure.PeerNoGrantLoop
    // 三道闸都过了却还是 null：只剩运行时在这一瞬被拆掉这一种可能（重启窗口）。
    else -> GrantRequestFailure.NoRuntime
}
