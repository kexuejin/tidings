// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.core.protocol.AgentExecAuthorizer

/**
 * 团队派单的**参与人授权**（纯函数）。
 *
 * ## 为什么不能只判 `@` 锚点
 *
 * 锚点是「谁被点名」，参与人是「谁真的会跑」。两者在执行档只有一个 worker、主理人
 * 没有点将权时恰好重合，所以按锚点判一直看不出问题。一旦主理人能换人干活，锚点就
 * 退化成一个入口：用户 `@` 了 A，实际跑的是 B —— 按锚点判等于**拿 A 的授权跑 B**。
 *
 * ## 为什么逐个判、且只报第一个
 *
 * 每个组员各自设了「仅自己可用」是产品口径（mulica 式），一条链上有几个人就有几个
 * 独立的授权问题，不能用一个总判据替他们表态。一次只暴露第一个阻塞点：审批回流
 * （`recordApprovalRequest`）按 agentId 记，用户批完再发一次就能推进到下一个 ——
 * 一次列出三条待批，只会让面板里堆满同一个动作的碎片。
 */
object TeamDispatchAuthorization {

    /**
     * 第一个不被允许的参与人；null = 全部放行（含「没有人会被调起」：拒绝编译时
     * `participants` 为空，此时没有授权对象可言）。
     *
     * @param verdictOf 单个 agentId 的授权判定（调用方注入，便于纯函数测试）。
     */
    fun firstDenied(
        participants: List<String>,
        verdictOf: (String) -> AgentExecAuthorizer.Verdict,
    ): Denial? = participants.distinct().firstNotNullOfOrNull { agentId ->
        val verdict = verdictOf(agentId)
        if (with(AgentExecAuthorizer) { verdict.permitsExecution() }) null
        else Denial(agentId, verdict)
    }

    /**
     * **入口闸**：这次派单值不值得继续往下走（编译 / 问主理人 / 跑链）。
     *
     * 两档语义必须分开，混在一起就是两种错误各占一半：
     *
     * - [DispatchEntry.MEMBER]（`@某个组员`）= **严格锚点**：用户点的就是他，他没授权
     *   就立即拒。这里不许"换个人顶上"—— 用户要的是这个人，不是这件事。
     * - [DispatchEntry.TEAM] / [DispatchEntry.LEADER]（`@团队名` / `@主理人`）= **队里
     *   有没有人能干活**：锚点只是编制首位，是「没有点将建议时的默认落点」，不是
     *   用户的指定。他一个人没授权就拒整队，会把「另一位已被授权的同事能干的活」
     *   一起挡掉 —— 那是把授权判据当成了人员限制。
     *
     * 两档都排在**编译之前**：一次必然被拒的派单不该先读到「本机没装这个底座」。
     *
     * @param anchorVerdict 锚点本人的判定（调用方已经算过一次，不再重复问台账）。
     * @param workers 编制里全部**可干活的人**（含锚点）。
     */
    fun entryPermits(
        entry: DispatchEntry,
        anchorVerdict: AgentExecAuthorizer.Verdict,
        workers: List<String>,
        verdictOf: (String) -> AgentExecAuthorizer.Verdict,
    ): Boolean {
        if (with(AgentExecAuthorizer) { anchorVerdict.permitsExecution() }) return true
        if (entry == DispatchEntry.MEMBER) return false
        return workers.any { agentId ->
            with(AgentExecAuthorizer) { verdictOf(agentId).permitsExecution() }
        }
    }

    /** 阻塞点：谁 + 为什么。卡面与审批回流都要能指到具体的人。 */
    data class Denial(
        val agentId: String,
        val verdict: AgentExecAuthorizer.Verdict,
    )
}
