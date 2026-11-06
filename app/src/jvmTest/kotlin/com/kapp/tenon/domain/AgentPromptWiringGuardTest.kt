// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * N1 唤醒/静默的**接线守卫**（SPEC-ORCHESTRATION-2026-11-03 §7.4）。
 *
 * 「三不不变量」类型系统表达不了：派单出口、诊断出口都是普通调用，摘掉任何一环，
 * 编译照样绿 —— 「未唤醒不产生模型调用」就静默变假（AGENTS §4 的假绿形态）。
 * 按源码文本钉住三件事：
 * 1. 派单出口恰好三个触发点（收方 / 本机发方 / 群发方），全部经 `agentPromptRoute`；
 * 2. 每个触发点都伴一次 `logNoWakeIfNeeded` —— 第 4 个触发点漏诊断会红；
 * 3. 派单只发生在 `route == AgentPromptRoute.Local` 判定之后（不许绕过判据旁路派单）。
 */
class AgentPromptWiringGuardTest {

    private val domain = "app/src/commonMain/kotlin/com/kapp/tenon/domain"

    private fun live(): String = GuardSourceScan.livePackageOf(domain)

    private fun positionsOf(source: String, marker: String): List<Int> =
        Regex(Regex.escape(marker)).findAll(source).map { it.range.first }.toList()

    @Test
    fun dispatchHasExactlyThreeTriggerPointsAllThroughTheSingleCriterion() {
        val source = live()
        assertEquals(
            3,
            positionsOf(source, "RelayController.agentPromptRoute(").size,
            "agentPromptRoute 的调用点必须是三个触发点：多了 = 有人重写判据（第二事实源）；" +
                "少了 = 一个触发点丢了",
        )
    }

    @Test
    fun everyTriggerPointAlsoEmitsTheNoWakeDiagnostic() {
        val source = live()
        assertEquals(
            positionsOf(source, "RelayController.agentPromptRoute(").size,
            positionsOf(source, "RelayController.logNoWakeIfNeeded(").size,
            "每个 agentPromptRoute 触发点必须伴一次 logNoWakeIfNeeded —— 漏一个，那一面的「未唤醒」就静默",
        )
    }

    @Test
    fun dispatchOnlyHappensAfterTheLocalVerdict() {
        val source = live()
        val verdicts = positionsOf(source, "route == AgentPromptRoute.Local")
        val dispatches = positionsOf(source, "host.onAgentPrompt?.invoke")
        assertEquals(
            3, dispatches.size,
            "onAgentPrompt?.invoke 只许出现在三个触发点里：多一处 = 绕过判据的旁路派单",
        )
        assertEquals(
            verdicts.size, dispatches.size,
            "Local 判定块与派单点必须成对出现",
        )
        verdicts.zip(dispatches).forEach { (verdict, dispatch) ->
            assertTrue(
                dispatch > verdict,
                "派单点（位置 $dispatch）必须出现在它对应的 Local 判定（位置 $verdict）之后",
            )
        }
    }
}
