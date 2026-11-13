// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 团队编制的**两个判据**（[TeamRosterValidation]）的行为钉子。
 *
 * 分工是这组测试的主线：
 *
 * - [TeamRosterValidation.validate] 只报**必须改的错**（用户自己能改、不改就是脏数据）；
 * - [TeamRosterValidation.resolveNames] 处理**撞别人**（名字被群里别的东西占了）——
 *   就地取本群别名，**不拒绝**，与员工档案同一条纪律。
 *
 * 冲突域（谁算「别人」）在这里最容易写错：组员名要避开**群内所有现在就能被 @ 的东西**
 * （大小写不敏感），但**不含**被编辑这条团队自己的既有组员名（正在被整体替换）；
 * 人类昵称不在池里（昵称不是 token）。
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

    // ------------------------------------------------------------------ validate

    @Test
    fun aWellFormedRosterPasses() {
        val issues = TeamRosterValidation.validate(
            listOf(draft("coder", "编码"), draft("reviewer", "审查"), draft("tester", "测试")),
        )
        assertTrue(issues.isEmpty(), "合法名册零问题：${issues}")
    }

    @Test
    fun anEmptyRosterIsValidItMeansSwitchingTheTeamOff() {
        assertTrue(TeamRosterValidation.validate(emptyList()).isEmpty())
    }

    @Test
    fun tooManyMembersIsOneStructuredIssue() {
        val roster = (1..(Limits.TEAM_MEMBERS_MAX + 1)).map { draft("m$it") }
        val issues = TeamRosterValidation.validate(roster)
        assertEquals(1, issues.size)
        assertEquals(TeamRosterIssueKind.TOO_MANY, issues.single().kind)
        assertEquals(-1, issues.single().index, "数量问题没有行可指")
        assertEquals(Limits.TEAM_MEMBERS_MAX, issues.single().limit)
    }

    @Test
    fun emptyAndOverlongFieldsAreLocatedByRowAndField() {
        val issues = TeamRosterValidation.validate(
            listOf(draft("", role = ""), draft("r".repeat(Limits.NICK + 1))),
        )
        assertTrue(issues.any { it.index == 0 && it.field == TeamRosterField.NAME && it.kind == TeamRosterIssueKind.EMPTY })
        assertTrue(issues.any { it.index == 0 && it.field == TeamRosterField.ROLE && it.kind == TeamRosterIssueKind.EMPTY })
        assertTrue(issues.any { it.index == 1 && it.field == TeamRosterField.NAME && it.kind == TeamRosterIssueKind.TOO_LONG })
        assertEquals(Limits.NICK, issues.first { it.kind == TeamRosterIssueKind.TOO_LONG }.limit)
    }

    @Test
    fun memberNamesMustBeAsciiSlugs() {
        val issues = TeamRosterValidation.validate(listOf(draft("Coder")))
        // slug 归一后不相等（大写）→ BAD_SLUG。UI 保存前先 slug 化，所以这条拦的是漏网。
        assertTrue(issues.any { it.field == TeamRosterField.NAME && it.kind == TeamRosterIssueKind.BAD_SLUG })
        // 中文不能当 name（那是 role 的事）
        val chinese = TeamRosterValidation.validate(listOf(draft("编码员")))
        assertTrue(chinese.any { it.field == TeamRosterField.NAME && it.kind == TeamRosterIssueKind.BAD_SLUG })
    }

    @Test
    fun duplicatesInsideTheTeamAreFlaggedCaseInsensitively() {
        val issues = TeamRosterValidation.validate(listOf(draft("coder"), draft("Coder")))
        assertTrue(issues.any { it.index == 1 && it.field == TeamRosterField.NAME && it.kind == TeamRosterIssueKind.DUPLICATE_IN_TEAM })
        // agentId 同理
        val ids = TeamRosterValidation.validate(
            listOf(draft("a1", agentId = "m-x"), draft("a2", agentId = "M-X")),
        )
        assertTrue(ids.any { it.field == TeamRosterField.AGENT_ID && it.kind == TeamRosterIssueKind.DUPLICATE_IN_TEAM })
    }

    /**
     * **撞名不再是一道错**（与改写前相反的判据）。
     *
     * 反向探针：谁把「和池里撞了就报 TAKEN」加回 validate，这条立刻红 —— 那等于
     * 又让「别人先占了名字」把整支团队挡在门外。
     */
    @Test
    fun aNameCollidingWithAGroupTokenIsNotAnError() {
        // 用小写形态：大写本身会触发 BAD_SLUG，那就测不出「撞名不是错」这件事了。
        assertTrue(
            TeamRosterValidation.validate(listOf(draft("codex"))).isEmpty(),
            "撞群里既有 token 不是错，是别名（resolveNames 的事）",
        )
        assertTrue(TeamRosterValidation.validate(listOf(draft("dsh-teahouse-mobile"))).isEmpty())
    }

    // -------------------------------------------------------------- resolveNames

    @Test
    fun aCollisionWithAGroupTokenBecomesALocalAlias() {
        val names = TeamRosterValidation.resolveNames(listOf(draft("CODEx")), pool)
        assertEquals(1, names.size)
        assertEquals("codex-2", names.single().name, "撞了就用下一个没被占的编号")
        assertEquals("codex", names.single().aliasedFrom, "UI 要说出「原本叫这个名字」")
        assertTrue(names.single().isAliased)
    }

    @Test
    fun aliasesSkipEveryAlreadyTakenSuffix() {
        val taken = setOf("coder", "coder-2", "coder-3")
        assertEquals("coder-4", TeamRosterValidation.resolveNames(listOf(draft("coder")), taken).single().name)
    }

    @Test
    fun aFreeNameKeepsItselfAndReportsNoAlias() {
        val names = TeamRosterValidation.resolveNames(listOf(draft("coder")), pool)
        assertEquals("coder", names.single().name)
        assertNull(names.single().aliasedFrom, "没撞就不许报别名（否则 UI 会一直提示）")
    }

    /** 领队 token 也在冲突域里：组员撞领队名 = 影子领队，处理方式同样是别名而不是拒绝。 */
    @Test
    fun theLeaderTokenCollidesIntoAnAlias() {
        val names = TeamRosterValidation.resolveNames(listOf(draft("dsh-teahouse-mobile")), pool)
        assertEquals("dsh-teahouse-mobile-2", names.single().name)
    }

    /**
     * 别名本身也可能被占：`coder` 被占 ⇒ 第一行拿 `coder-2`；而名册里正好还有一行
     * 就叫 `coder-2` ⇒ 它得继续往后让。名字的分配是**按顺序、边分配边登记**的。
     *
     * （名册内**完全同名**是 validate 的错，走不到这里。）
     */
    @Test
    fun aliasesAccumulateWithinTheSameRoster() {
        val names = TeamRosterValidation.resolveNames(
            listOf(draft("coder"), draft("coder-2")),
            setOf("coder"),
        )
        assertEquals(listOf("coder-2", "coder-2-2"), names.map { it.name })
        assertEquals(listOf("coder", "coder-2"), names.map { it.aliasedFrom })
    }

    /**
     * 池的契约（由调用方执行）：被编辑团队自己的**既有组员名**不在池里 —— 沿用旧名不会
     * 变成别名；同一个名字若还在池里（说明它是别人的 token）则照常别名。
     */
    @Test
    fun theEditedTeamsPreviousMemberNamesAreNotInThePool() {
        val roster = listOf(draft("reviewer"))
        // 调用方把旧组员名剔出池之后：沿用旧名。
        assertEquals("reviewer", TeamRosterValidation.resolveNames(roster, setOf("dsh")).single().name)
        // 对照：它还在池里（是别的团队的组员名）→ 别名。
        assertEquals("reviewer-2", TeamRosterValidation.resolveNames(roster, setOf("reviewer", "dsh")).single().name)
    }

    /** 人类昵称不是 token —— 与群成员同名不产生别名（展示数据，没有 @ 特权）。 */
    @Test
    fun humanNicknamesAreNotTokens() {
        val names = TeamRosterValidation.resolveNames(listOf(draft("lao-zhou")), pool + "老周")
        assertEquals("lao-zhou", names.single().name)
        assertNull(names.single().aliasedFrom)
    }

    /** 池里的**大写**写法也要算占位：`unique` 是精确比较，归一化漏了就会漏过去。 */
    @Test
    fun thePoolIsComparedCaseInsensitively() {
        val names = TeamRosterValidation.resolveNames(listOf(draft("coder")), setOf("CODER"))
        assertEquals("coder-2", names.single().name)
    }
}
