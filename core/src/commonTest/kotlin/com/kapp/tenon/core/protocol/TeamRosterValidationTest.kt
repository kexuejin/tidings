// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 团队编制校验（[TeamRosterValidation]）的行为钉子。
 *
 * 冲突域是这里最容易写错的：组员名要避开**群内所有现在就能被 @ 的东西**
 * （大小写不敏感），但**不含**被编辑这条团队自己的既有组员名（正在被整体替换），
 * 人类昵称也不在池里（昵称不是 token）。
 */
class TeamRosterValidationTest {

    private fun draft(name: String, role: String = "编码", agentId: String = "m-$name") =
        TeamMemberDraft(agentId = agentId, name = name, role = role)

    /** 群里的 @ token 池：领队名/agentId/kind + 其它声明 + 其它团队的组员名。 */
    private val pool = setOf(
        "dsh-teahouse-mobile", // 领队的 @ token
        "dsh",                 // 领队的 agentId（kind）
        "codex",               // 别的 agent 的 agentId
        "codex-workspace",     // 别的 agent 的 name
        "reviewer-other",      // 别的团队的组员名
    )

    @Test
    fun aWellFormedRosterPasses() {
        val issues = TeamRosterValidation.validate(
            listOf(draft("coder", "编码"), draft("reviewer", "审查"), draft("tester", "测试")),
            pool,
        )
        assertTrue(issues.isEmpty(), "合法名册零问题：${issues}")
    }

    @Test
    fun anEmptyRosterIsValidItMeansSwitchingTheTeamOff() {
        assertTrue(TeamRosterValidation.validate(emptyList(), pool).isEmpty())
    }

    @Test
    fun tooManyMembersIsOneStructuredIssue() {
        val roster = (1..(Limits.TEAM_MEMBERS_MAX + 1)).map { draft("m$it") }
        val issues = TeamRosterValidation.validate(roster, pool)
        assertEquals(1, issues.size)
        assertEquals(TeamRosterIssueKind.TOO_MANY, issues.single().kind)
        assertEquals(-1, issues.single().index, "数量问题没有行可指")
        assertEquals(Limits.TEAM_MEMBERS_MAX, issues.single().limit)
    }

    @Test
    fun emptyAndOverlongFieldsAreLocatedByRowAndField() {
        val issues = TeamRosterValidation.validate(
            listOf(draft("", role = ""), draft("r".repeat(Limits.NICK + 1))),
            pool,
        )
        assertTrue(issues.any { it.index == 0 && it.field == TeamRosterField.NAME && it.kind == TeamRosterIssueKind.EMPTY })
        assertTrue(issues.any { it.index == 0 && it.field == TeamRosterField.ROLE && it.kind == TeamRosterIssueKind.EMPTY })
        assertTrue(issues.any { it.index == 1 && it.field == TeamRosterField.NAME && it.kind == TeamRosterIssueKind.TOO_LONG })
        assertEquals(Limits.NICK, issues.first { it.kind == TeamRosterIssueKind.TOO_LONG }.limit)
    }

    @Test
    fun memberNamesMustBeAsciiSlugs() {
        val issues = TeamRosterValidation.validate(listOf(draft("Coder")), pool)
        // slug 归一后不相等（大写）→ BAD_SLUG。UI 保存前先 slug 化，所以这条拦的是漏网。
        assertTrue(issues.any { it.field == TeamRosterField.NAME && it.kind == TeamRosterIssueKind.BAD_SLUG })
        // 中文不能当 name（那是 role 的事）
        val chinese = TeamRosterValidation.validate(listOf(draft("编码员")), pool)
        assertTrue(chinese.any { it.field == TeamRosterField.NAME && it.kind == TeamRosterIssueKind.BAD_SLUG })
    }

    @Test
    fun duplicatesInsideTheTeamAreFlaggedCaseInsensitively() {
        val issues = TeamRosterValidation.validate(listOf(draft("coder"), draft("Coder")), pool)
        assertTrue(issues.any { it.index == 1 && it.field == TeamRosterField.NAME && it.kind == TeamRosterIssueKind.DUPLICATE_IN_TEAM })
        // agentId 同理
        val ids = TeamRosterValidation.validate(
            listOf(draft("a1", agentId = "m-x"), draft("a2", agentId = "M-X")),
            pool,
        )
        assertTrue(ids.any { it.field == TeamRosterField.AGENT_ID && it.kind == TeamRosterIssueKind.DUPLICATE_IN_TEAM })
    }

    @Test
    fun aNameCollidingWithAGroupTokenIsFlaggedWithTheConflictingToken() {
        val issues = TeamRosterValidation.validate(listOf(draft("CODEx")), pool)
        val taken = issues.filter { it.kind == TeamRosterIssueKind.TAKEN }
        assertEquals(1, taken.size)
        assertEquals(TeamRosterField.NAME, taken.single().field)
        assertEquals("codex", taken.single().conflictWith, "冲突对象要给到 UI（大小写归一后）")
    }

    @Test
    fun theLeaderTokenIsInTheConflictDomain() {
        val issues = TeamRosterValidation.validate(listOf(draft("dsh-teahouse-mobile")), pool)
        assertTrue(issues.any { it.kind == TeamRosterIssueKind.TAKEN }, "组员撞领队 token = 影子领队，必须拦")
    }

    /**
     * 池的契约（由调用方执行）：被编辑团队自己的**既有组员名**不在池里 —— 沿用旧名不是
     * 冲突；同一个名字若还在池里（说明它是别人的 token）则照拦。校验函数本身不知道
     * 「哪几个 token 属于被编辑的团队」，这条钉的是两边的分工。
     */
    @Test
    fun theEditedTeamsPreviousMemberNamesAreNotInThePool() {
        // 调用方把旧组员名剔出池之后：沿用旧名放行。
        assertTrue(TeamRosterValidation.validate(listOf(draft("reviewer")), setOf("dsh")).isEmpty())
        // 对照：它还在池里（是别的团队的组员名）→ 照拦。
        assertTrue(TeamRosterValidation.validate(listOf(draft("reviewer")), setOf("reviewer", "dsh")).isNotEmpty())
    }

    /** 人类昵称不是 token —— 与群成员同名不拦（展示数据，没有 @ 特权）。 */
    @Test
    fun humanNicknamesAreNotTokens() {
        val issues = TeamRosterValidation.validate(listOf(draft("lao-zhou")), pool + "老周")
        assertTrue(issues.isEmpty())
    }

    /** 反向探针对照面：注释掉 TAKEN 分支，这条应当红。 */
    @Test
    fun theTakenCheckReallyRuns() {
        val issues = TeamRosterValidation.validate(listOf(draft("codex-workspace")), pool)
        assertTrue(issues.isNotEmpty(), "撞既有 token 必须报 TAKEN（反向验证时删掉 takenLower 检查它会红）")
    }
}
