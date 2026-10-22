// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.protocol

/**
 * 「这条消息该交给哪个 agent 会话」的判定。
 *
 * ## 用户的规则（原话，两次一致）
 *
 * 「如果添加这个 agent 的时候，就绑定了工作区，那就在这个 agent 的工作区做；
 * 如果没有绑定，那就是未绑定工作区的会话做。本身 agent 比如 dsh、codex 这些，
 * 也不是必须绑定工作区才能对话。」
 *
 * 所以工作区是**可选**的，两种都是一等状态：
 * - 绑定 ⇒ 在该工作区的会话里干活；
 * - 未绑定 ⇒ 在**不带工作区**的会话里干活（agent 本身照样能对话）。
 *
 * ## 为什么这个判定必须是纯函数、且只有一份
 *
 * 它决定「命令在哪个目录里执行」。判错的后果不是显示错，而是**在错误的项目里跑命令** ——
 * 用户看到 agent 动了，却不知道动的是哪个仓库。所以：
 * - 不猜「最近的会话」；
 * - 两侧（宿主 agentNode / 界面 composeApp）用同一份实现，否则必然漂。
 */
/**
 * 公开（不是 internal）：`agentNode` 是独立模块，注入判定要在那里调用。
 * 判据本身仍是纯函数，可见性放宽不改变它只有一份这件事。
 */
object AgentSessionPick {

    /** 判定结果。带原因而不是可空返回：拒绝时说得出为什么。 */
    sealed interface Result {

        /** 选到了一个会话。 */
        data class Pick(val sessionId: String) : Result

        /** 该 agent 在这个工作区下没有任何会话 —— **不跨工作区借用**。 */
        data object NoSessionInWorkspace : Result

        /** 该 agent 没有未绑工作区的会话 —— 不硬塞进某个工作区的会话。 */
        data object NoUnboundSession : Result

        /**
         * 用户**显式指定**了一个会话，但它不在这份候选里（已结束 / 不属于该 agent / 归属变了）。
         *
         * 与 [NoSessionInWorkspace] 分开：那是「本来就没有」，这是「你点的那个没了」，
         * 用户要做的动作不同（前者是去开一个，后者是重新选）。
         */
        data object RequestedSessionGone : Result
    }

    /**
     * 从候选里挑一个会话。
     *
     * @param boundWsId 这个 agent 绑定的工作区 id；null = **未绑定**（一等状态，不是缺失）。
     * @param candidates 该 agent 的会话，每条带自己的 `wsId`（null = 未绑工作区）与**已按活跃度排好的顺序**。
     *
     * 调用方负责排序（通常是 `updated_at` 倒序）：这里只做**筛选**，不做「挑最近的」以外的假设。
     * 之所以要求调用方排序：排序口径属于数据层，不同来源（DB / 内存快照）可能不同，
     * 藏在判定里会让「为什么选中这条」变得不可解释。
     */
    fun pick(
        boundWsId: String?,
        candidates: List<Candidate>,
        /**
         * 用户**显式指定**的会话 id；null = 没指定，走默认（见 [pick] 的默认规则）。
         *
         * 「可以指定，也可以不指定，不指定就按默认的来」—— 指定优先，且**只在候选里找**：
         * 指定的会话若已不在（结束了、换了归属），明确报 [Result.RequestedSessionGone]，
         * **不回退到默认**。为什么：用户点了一个具体的会话，静默改成另一个比报错更糟 ——
         * 他会以为命令进了 A，实际进了 B。
         */
        requestedSessionId: String? = null,
    ): Result {
        // **指定优先**：在候选里精确找。找不到就如实说它没了，不偷偷换成默认那条。
        val requested = requestedSessionId?.trim()?.takeIf { it.isNotEmpty() }
        if (requested != null) {
            val hit = candidates.firstOrNull { it.sessionId == requested }
                ?: return Result.RequestedSessionGone
            return Result.Pick(hit.sessionId)
        }
        if (candidates.isEmpty()) {
            return if (boundWsId == null) Result.NoUnboundSession else Result.NoSessionInWorkspace
        }
        val ws = boundWsId?.trim()?.takeIf { it.isNotEmpty() }
        return if (ws == null) {
            // **未绑定**：只在不带工作区的会话里选。
            // 为什么不去借一个有工作区的会话：那会让「没绑定」在执行时**变成**「绑定到别人挑的那个」，
            // 而用户明确说过未绑定的 agent 照样能对话 —— 那它就该在自己那种会话里对话。
            val hit = candidates.firstOrNull { it.wsId.isNullOrBlank() }
                ?: return Result.NoUnboundSession
            Result.Pick(hit.sessionId)
        } else {
            // **绑定了**：只在该工作区的会话里选，不跨工作区。
            val hit = candidates.firstOrNull { it.wsId == ws }
                ?: return Result.NoSessionInWorkspace
            Result.Pick(hit.sessionId)
        }
    }

    /** 一个候选会话：id + 它自己归属的工作区（null = 未绑）。 */
    data class Candidate(val sessionId: String, val wsId: String?)
}
