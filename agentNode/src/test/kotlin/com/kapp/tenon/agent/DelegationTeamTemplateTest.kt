// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §16 群模板的解析判据：模板命中 ⇒ 配置选棒 + `fromTemplate` 置位；
 * 模板不可信 ⇒ 逐字回落启发式保底；挡位 A 不受模板影响。
 *
 * 与 DelegationParserTest 同口径（判窄不判宽 / 无模板时既有行为零变更）。
 */
class DelegationTeamTemplateTest {

    private val available = setOf("codex", "dsh", "opencode")
    private val team = DelegationTeam("grp-1", workerKey = "claude", reviewerKey = "dsh", updatedAt = 0L)

    @Test
    fun templateWinsOverTheHeuristicRosterEvenWhenTheMemberIsAbsent() {
        // 成员不在位（claude 不在 available）⇒ 解析器**照样产出模板计划** ——
        // 在位裁决是门的职责（Reject 出卡点名模板），解析器不静默换人（§15 第 8 条）。
        val plan = assertNotNull(DelegationParser.parse("组队: 修复登录超时", available, team = team))
        assertTrue(plan.fromTemplate, "模板命中必须置位 fromTemplate（编排器据此跳过 planner）")
        assertEquals(listOf("claude", "dsh"), plan.stages.map { it.agentKey })
        assertEquals(DelegationPlan.Source.AUTO, plan.source)
        assertEquals("修复登录超时", plan.goal)
    }

    @Test
    fun templateAlsoWinsWhenTheConfiguredRosterIsHealthy() {
        // 对照：模板命中时启发式优先序表不参与 —— 配置了 claude 就不是 codex。
        val plan = assertNotNull(
            DelegationParser.parse("组队: 写个工具", setOf("codex", "claude", "dsh"), team = team),
        )
        assertEquals(listOf("claude", "dsh"), plan.stages.map { it.agentKey }, "配置选棒，不是优先序表")
    }

    @Test
    fun explicitGearIgnoresTheTemplate() {
        // §16.4：挡位 A 最高优先级，模板不介入 —— 点名两条就不该被模板改写。
        val plan = assertNotNull(DelegationParser.parse("@codex 实现 X，@dsh 审核", available, team = team))
        assertEquals(DelegationPlan.Source.EXPLICIT, plan.source)
        assertTrue(!plan.fromTemplate, "显式点名不是模板命中")
        assertEquals(listOf("codex", "dsh"), plan.stages.map { it.agentKey })
    }

    @Test
    fun untrustworthyTemplateKeysFallBackToTheHeuristicVerbatim() {
        // 文件里是档案表没有的名字 ⇒ 模板不可信 ⇒ 逐字回落保底（与无模板同结果，§16.3）。
        val broken = DelegationTeam("grp-1", workerKey = "no-such-agent", reviewerKey = "dsh", updatedAt = 0L)
        val plan = assertNotNull(DelegationParser.parse("组队: 修复登录超时", available, team = broken))
        assertTrue(!plan.fromTemplate, "回落保底不是模板命中")
        assertEquals(listOf("codex", "dsh"), plan.stages.map { it.agentKey }, "回落结果与无模板逐字一致")
    }

    @Test
    fun noTeamKeepsTheLegacyBehaviorVerbatim() {
        // 无模板文件 ⇒ 挡位 B 与改动前逐字一致（§16.5 验收第一条）。
        val plan = assertNotNull(DelegationParser.parse("组队: 修复登录超时", available))
        assertTrue(!plan.fromTemplate)
        assertEquals(listOf("codex", "dsh"), plan.stages.map { it.agentKey })
    }

    @Test
    fun configPhraseParserRecognisesTheFixedPattern() {
        // 入口 A 的句式（§16.6）：主形态、英文冒号、前导 @、句号收尾都认。
        assertEquals(
            "Claude" to "DSH",
            DelegationTeamConfigParser.parse("以后本群默认组队：Claude 开发，DSH 审核"),
        )
        assertEquals(
            "Claude" to "DSH",
            DelegationTeamConfigParser.parse("@Chief 以后本群默认组队: Claude 开发，DSH 审核"),
        )
        assertEquals(
            "Claude" to "DSH",
            DelegationTeamConfigParser.parse("以后本群默认组队：Claude 开发，DSH 审核。"),
        )
        // 不匹配 ⇒ null（不拦截、不消费）—— 抄错一个词就是普通聊天。
        assertNull(DelegationTeamConfigParser.parse("以后本群默认组队：Claude 开发"))
        assertNull(DelegationTeamConfigParser.parse("组队: 修复登录超时"))
        assertNull(DelegationTeamConfigParser.parse("今天天气不错"))
    }
}
