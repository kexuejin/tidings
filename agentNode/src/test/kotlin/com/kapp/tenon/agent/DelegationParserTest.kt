// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 委派计划解析的判据：**双挡同管线、判窄不判宽**。
 *
 * 解析器是委派双挡的入口闸门 —— 误吞一条普通派单（把「注意兼容」当成第二棒）
 * 的代价远高于漏掉一次委派，所以这里一半的测试在钉「**不**触发」。
 */
class DelegationParserTest {

    private val available = setOf("codex", "dsh", "opencode")

    // ---------------------------------------------------------------- 挡位 B：组队（自动）

    @Test
    fun autoModeBuildsAWorkerAndACrossVendorReviewer() {
        val plan = assertNotNull(DelegationParser.parse("组队: 修复登录超时", available))
        assertEquals(DelegationPlan.Source.AUTO, plan.source)
        assertEquals("修复登录超时", plan.goal)
        assertEquals(2, plan.stages.size)
        // dev(codex) 打头（真机验证过的组合），qa(dsh) 收尾（deepseek ≠ openai）
        assertEquals("codex", plan.stages[0].agentKey)
        assertEquals("修复登录超时", plan.stages[0].instruction)
        assertEquals("dsh", plan.stages[1].agentKey)
        assertTrue(
            plan.stages[1].instruction.contains("修复登录超时"),
            "审核棒任务书必须复述目标，不能只有「审核」两个字",
        )
    }

    @Test
    fun autoModeWithNoSecondVendorStillProducesAWorkerStageForTheGateToDowngrade() {
        val plan = assertNotNull(DelegationParser.parse("组队: 修一修", setOf("codex")))
        assertEquals(listOf("codex"), plan.stages.map { it.agentKey })
        // 降级是门的裁决（Downgrade），解析器不吞单
    }

    @Test
    fun autoSelectionAvoidsUnhealthyAgentsButExplicitMentionsStillWork() {
        // G1（真机 2026-11-03）：planner 把审核棒指给了未登录的 claude —— 二进制在位 ≠ 健康。
        // 自动选棒只看 autoKeys（健康名册）；显式点名不受它管。
        val roster = setOf("codex", "claude", "dsh")
        val healthy = setOf("codex", "dsh")
        val auto = assertNotNull(DelegationParser.parse("组队: 修登录", roster, healthy))
        assertEquals(listOf("codex", "dsh"), auto.stages.map { it.agentKey }, "自动选棒回避不健康的 claude")
        val explicit = assertNotNull(DelegationParser.parse("@codex 实现 X，@claude 审核", roster, healthy))
        assertEquals(listOf("codex", "claude"), explicit.stages.map { it.agentKey }, "指名 claude 就派 claude")
    }

    @Test
    fun autoModePrefixSurvivesMentionStripping() {
        // 真机文本路径：`@codex 组队: 目标` 的 @ 提及被域层剥掉，到这只剩前缀
        val plan = assertNotNull(DelegationParser.parse("组队：实现导出功能", available))
        assertEquals("实现导出功能", plan.goal)
    }

    @Test
    fun englishTeamAliasIsEquivalentToTheChinesePrefix() {
        // adb 派单打不出中文字：`team:` 必须与 `组队:` 语义零差异
        val plan = assertNotNull(DelegationParser.parse("team: add an e2e marker to README", available))
        assertEquals(DelegationPlan.Source.AUTO, plan.source)
        assertEquals("add an e2e marker to README", plan.goal)
        assertEquals(listOf("codex", "dsh"), plan.stages.map { it.agentKey })
    }

    @Test
    fun autoModeWithoutGoalIsNotADelegation() {
        assertNull(DelegationParser.parse("组队:", available))
        assertNull(DelegationParser.parse("组队： ", available))
        assertNull(DelegationParser.parse("组队一下这个东西", available), "没有冒号前缀就不是组队指令")
    }

    // ---------------------------------------------------------------- 挡位 A：点名（显式）

    @Test
    fun explicitMentionsCompileIntoWorkerFirstReviewerLast() {
        val plan = assertNotNull(DelegationParser.parse("@codex 实现暗色主题，@dsh 审核", available))
        assertEquals(DelegationPlan.Source.EXPLICIT, plan.source)
        assertEquals(listOf("codex", "dsh"), plan.stages.map { it.agentKey })
        assertEquals("实现暗色主题", plan.stages[0].instruction)
        assertTrue(plan.stages[1].instruction.contains("暗色主题"), "薄审核指令要被补上目标复述")
    }

    @Test
    fun explicitColonFormAndCliSuffixBothNormalize() {
        val plan = assertNotNull(DelegationParser.parse("codex: 重构存储层；dsh-cli: 检查", available))
        assertEquals(listOf("codex", "dsh"), plan.stages.map { it.agentKey })
    }

    @Test
    fun englishReviewKeywordAlsoClassifiesAsReviewer() {
        val plan = assertNotNull(DelegationParser.parse("@codex write the parser, @opencode review it", available))
        assertEquals(listOf("codex", "opencode"), plan.stages.map { it.agentKey })
        assertEquals("审核", plan.stages[1].role)
    }

    @Test
    fun reviewerClauseIsMovedLastRegardlessOfMentionOrder() {
        val plan = assertNotNull(DelegationParser.parse("@dsh 审核，@codex 实现 X", available))
        assertEquals(listOf("codex", "dsh"), plan.stages.map { it.agentKey }, "A 干 B 审核：审核棒必须收尾")
    }

    @Test
    fun allWorkerClausesAreNotADelegationShape() {
        // 两个 agent 都是干活、没有审核棒：不是「A 干 B 审核」，交回旧路径
        assertNull(DelegationParser.parse("@codex 写前端，@opencode 写后端", available))
    }

    // ---------------------------------------------------------------- 判窄：不触发

    @Test
    fun aSingleAgentPromptIsNotAPlanEvenWithTrailingClauses() {
        assertNull(
            DelegationParser.parse("@codex 修复登录 bug，注意兼容旧版本", available),
            "只有一个 agent 被点名 → 普通派单，分句只是句读",
        )
    }

    @Test
    fun midSentenceAgentMentionsDoNotTrigger() {
        assertNull(
            DelegationParser.parse("把 codex 昨天的输出贴给 dsh 看看", available),
            "句首不是键 token：这是在谈论 agent，不是在派单",
        )
    }

    @Test
    fun legacyStageSyntaxIsNotHijacked() {
        assertNull(
            DelegationParser.parse("dev(codex): 实现 X；qa(dsh): 审核", available),
            "旧阶段语法走 parseStages，委派解析不许抢",
        )
    }

    @Test
    fun unknownOrUnavailableAgentsDoNotTrigger() {
        assertNull(DelegationParser.parse("@codex 实现 X，@claude 审核", available), "claude 不在位 → 不是委派")
        assertNull(DelegationParser.parse("@codex 实现 X，@chatgpt 审核", available), "未知键 → 不是委派")
    }

    @Test
    fun emptyInputOrEmptyRosterIsNull() {
        assertNull(DelegationParser.parse("", available))
        assertNull(DelegationParser.parse("组队: 修复 X", emptySet()))
    }
}
