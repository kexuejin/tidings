// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain.orchestrator

/**
 * 协同工单生命周期阶段推导器（参考 DeepSeek dsh-superagent 的 `ticketStage` 纯函数式设计）。
 *
 * 核心分类准则：以【当前在等谁】为唯一维度：
 * - [PersonWaiting]：机器停下，当前必须等待人类输入（问答澄清 / 终审合并 / 严重受阻求助）；
 * - [Working]：机器正在独立推进中（排队中 / 沙箱编码 / 单测构建）；
 * - [Idle]：本轮次完成，在会话中静默待命；
 * - [Ended]：任务已终结（已合并入主干 / 已丢弃）。
 */
sealed class OrchestrationStage {

    sealed class PersonWaiting : OrchestrationStage() {
        /** 等待意图澄清决策（反向多选卡）。 */
        data object Clarification : PersonWaiting()

        /** 等待人类审查 Diff 产物并决定是否合并。 */
        data object Review : PersonWaiting()

        /** 遭遇确定性物理阻断或 3 轮自愈失败，等待人类接管。 */
        data class Blocked(val reason: String) : PersonWaiting()
    }

    sealed class Working : OrchestrationStage() {
        /** 等待空闲沙箱或算力配对。 */
        data object Queued : Working()

        /** 沙箱内编码或构建执行中。 */
        data class Executing(val phase: String, val progressPercent: Int = 0) : Working()
    }

    /** 本轮次产出交付完毕，等待新的增量追问。 */
    data object Idle : OrchestrationStage()

    sealed class Ended : OrchestrationStage() {
        /** 已成功合流入宿主主干。 */
        data object Merged : Ended()

        /** 用户或超时放弃修改。 */
        data class Discarded(val reason: String) : Ended()
    }

    companion object {
        /**
         * 基于工单快照纯函数推导当前生命周期阶段（自上而下匹配，首条命中生效）。
         */
        fun derive(
            isEnded: Boolean,
            isMerged: Boolean = false,
            discardReason: String? = null,
            hasPendingClarification: Boolean = false,
            isReadyForReview: Boolean = false,
            blockedReason: String? = null,
            isExecuting: Boolean = false,
            currentPhase: String? = null,
            progressPercent: Int = 0,
        ): OrchestrationStage {
            if (isEnded) {
                return if (isMerged) Ended.Merged else Ended.Discarded(discardReason ?: "task finished")
            }
            if (hasPendingClarification) return PersonWaiting.Clarification
            if (isReadyForReview) return PersonWaiting.Review
            if (!blockedReason.isNullOrBlank()) return PersonWaiting.Blocked(blockedReason)
            if (isExecuting) return Working.Executing(currentPhase ?: "executing", progressPercent)
            return Idle
        }
    }
}
