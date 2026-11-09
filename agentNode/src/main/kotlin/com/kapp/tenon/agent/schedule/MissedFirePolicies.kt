// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.schedule

/**
 * 错过触发窗口的补偿策略（触发式编排提案 §2.2）——取值域唯一来源。
 *
 * 消费方：清单校验（PluginRegistration.validate 的 triggers 分支）与
 * DesktopHostScheduler 的补偿判定。两处各写一份字面量就是在制造
 * 「清单收了、补偿语义对不上」的静默不一致。
 */
object MissedFirePolicies {
    /** 默认：无论错过多少次触发窗口，立即仅补跑 1 次，并重置下一次触发时间。 */
    const val RUN_ONCE = "RUN_ONCE"

    /** 放弃错过的批次，等待下一次自然触发时间到达。 */
    const val SKIP = "SKIP"

    val VALUES: Set<String> = setOf(RUN_ONCE, SKIP)
    fun isKnown(policy: String): Boolean = policy in VALUES

    /** fail-closed 归一化：未知/空白回落默认 RUN_ONCE（与 AgentExecPolicies.normalize 同口径）。 */
    fun normalize(raw: String?): String {
        val v = raw?.trim()
        return if (v != null && v in VALUES) v else RUN_ONCE
    }
}
