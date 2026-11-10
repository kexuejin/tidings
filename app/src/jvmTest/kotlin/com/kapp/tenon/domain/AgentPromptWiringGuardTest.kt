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
 *
 * 团队执行方案 §4.2（P0-0.4）追加两钉：
 * 4. 群路径的两个触发点都必须**先解析目标**（`resolvePromptTarget`）再判路由 ——
 *    摘掉它，组员 token 就退回字母序 `firstOrNull()` 随机挑一个目标；
 * 5. `Ambiguous`（同帧多目标）**不执行**且必有一句可读提示 —— 入站侧不派单、
 *    发送侧出 `TeamMentionAmbiguous` notice。「随机挑一个」比不执行更糟。
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

    /** §4.2：群路径的两个触发点（发方自检 / 入站）必须先解析目标、再判路由。 */
    @Test
    fun bothGroupTriggerPointsResolveTheTargetBeforeTheRouteVerdict() {
        val source = live()
        val resolutions = positionsOf(source, "val promptTarget = resolvePromptTarget(")
        assertEquals(
            2, resolutions.size,
            "resolvePromptTarget 必须恰好出现在两个群路径触发点（handleSendToGroup / handleOnInbound）：" +
                "少了 = 有人退回 mentionedAgentKeys.firstOrNull() 的字母序随机挑目标；" +
                "多了 = 第三处旁路派单点",
        )
        val routes = positionsOf(source, "RelayController.agentPromptRoute(")
        resolutions.forEach { resolution ->
            assertTrue(
                routes.any { it > resolution },
                "目标解析（位置 $resolution）之后必须有路由判定 —— 先解析、再决定执不执行（§4.2）",
            )
        }
        // targetHostIsSelf 只能从解析结果来（§4.5）：解析被摘掉它就是恒 null，判据退化回旧口径。
        assertEquals(
            2, positionsOf(source, "targetHostIsSelf = when (promptTarget)").size,
            "两个群触发点都要按解析出的目标宿主判 Local/Remote（§4.5 行为变更的接线本体）",
        )
    }

    /** §4.2：Ambiguous 不执行且必有可读提示 —— 两半缺一不可。 */
    @Test
    fun anAmbiguousTargetIsNeverDispatchedAndAlwaysExplained() {
        val source = live()
        // 入站侧：本机不执行（歧义条件参与派单判据）。
        assertTrue(
            source.contains("val ambiguous = promptTarget is PromptTarget.Ambiguous"),
            "入站侧必须先把歧义落成布尔 —— 摘掉它，Ambiguous 帧会照常进派单分支",
        )
        assertTrue(
            source.contains("&& !ambiguous"),
            "入站派单条件必须排除 Ambiguous（!intentOwnsDispatch 旁边少了它 = 歧义照派）",
        )
        // 发送侧：发送者屏幕上那句可读提示（notice 不进协议，但它是发送侧唯一的反馈面）。
        assertEquals(
            1, positionsOf(source, "NoticeKind.TeamMentionAmbiguous").size,
            "发送侧 Ambiguous 必须出 TeamMentionAmbiguous notice —— 「随机挑一个」比不执行更糟，" +
                "不执行还不说明原因则更更糟",
        )
    }
}
