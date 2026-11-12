// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

/**
 * 「这条消息 @ 到了 agent，但**投不出去**」的稳定成因 token。
 *
 * ## 谁在消费这些 token
 *
 * 不是三处都用同一份：
 *
 * - **随 `agentHint.requestRejectReason` 出到桥接侧的**只有两档点选被拒
 *   （`requested-session-gone` / `requested-session-outside-workspace`）—— 桥接侧据此
 *   在群里说人话，所以这两档是**对外**协议面；
 * - 其余几档只住在宿主台账与页面（`InboxInjectionTrace` 的成因统计），不出协议帧。
 *
 * 但词表仍然只有这一份：**抄两处的词表必错一处** —— 桥接侧认 `requested-session-gone`
 * 而台账写成 `requested-session-missing` 时，页面会安静地显示一个没人认识的 token，
 * 用户除了重装什么都做不了。
 *
 * token 本身是协议事实（跨进程、跨语言），**不要翻译、不要改大小写**；中文文案在
 * `composeResources` 里按 token 查表。
 */
object InboxUnresolved {

    /** 用户点选的那条会话已经不在了（会话结束了）。 */
    const val REQUESTED_SESSION_GONE: String = "requested-session-gone"

    /** 用户点选的那条会话属于**另一个工作区**（不换投、不照跑）。 */
    const val REQUESTED_SESSION_OUTSIDE_WORKSPACE: String = "requested-session-outside-workspace"

    /**
     * 这个 agent **未绑定工作区，而且连「不带工作区的会话」也没有** —— 所以它投不出去。
     *
     * ## 为什么不是「没绑定工作区」
     *
     * 「未绑定工作区」本身**不是失败原因**：未绑定的 agent 照样能在不带工作区的会话里干活
     * （`AgentSessionPick` 的语义，也是用户明确说过的）。原先这里报的是
     * `no-bound-workspace`，于是页面把最常见的「还没开会话」说成「去绑目录」——
     * 用户照做之后仍然投不出去，因为他的会话本来就不在那个目录里。
     *
     * 用户要做的动作只有一个：去开一个不带工作区的会话。
     */
    const val NO_UNBOUND_SESSION: String = "no-unbound-session"

    /** 有工作区，但该 agent 在这个工作区下**没有可投的会话**。 */
    const val NO_SESSION_IN_WORKSPACE: String = "no-session-in-workspace"

    /** 发送者没有执行权（授权表或执行范围拒了）。 */
    const val SENDER_NOT_PERMITTED: String = "sender-not-permitted"

    /** 全部取值（顺序即页面排序的兜底次序；新增一项要同时补两份 strings）。 */
    val ALL: List<String> = listOf(
        SENDER_NOT_PERMITTED,
        REQUESTED_SESSION_GONE,
        REQUESTED_SESSION_OUTSIDE_WORKSPACE,
        NO_UNBOUND_SESSION,
        NO_SESSION_IN_WORKSPACE,
    )
}
