// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.Limits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「AI 建队」规格解析与回落模板的判据（[TeamScaffoldParser] / [TeamScaffoldFallback]）。
 *
 * 生成侧的三条门（领队在位、恰 1 worker、reviewer 异 worker 驱动）在这里逐条钉住 ——
 * 它们是执行档保存校验（TeamExecutionValidation）与 TeamPlanCompiler 编译期硬约束的
 * **生成侧镜像**：生成时不修，保存/派单时才炸，等于让用户替 LLM 擦屁股。
 */
class TeamScaffoldTest {

    private val leaders = setOf("dsh", "codex")
    private val drivers = setOf("dsh", "codex", "claude")

    /** 固定 id 生成器：测试只关心形状（m-<slug>-xxxx）与唯一性，不关心随机值。 */
    private var seq = 0
    private val fixedId: (String) -> String = { slug -> "m-$slug-${(++seq).toString(16).padStart(4, '0')}" }

    private fun parse(
        raw: String,
        fallbackWorkerDriver: String? = "codex",
        fallbackReviewerDriver: String? = "claude",
    ): TeamScaffoldSpec? = TeamScaffoldParser.parse(
        raw = raw,
        leaderKeys = leaders,
        driverKeys = drivers,
        fallbackWorkerDriver = fallbackWorkerDriver,
        fallbackReviewerDriver = fallbackReviewerDriver,
        defaultTeamName = "New Team",
        defaultRole = "member",
        newAgentId = fixedId,
    )

    @Test
    fun happyPathParsesNamesRolesDriversAndKinds() {
        val spec = parse(
            """
            {"teamName":"Android Dev","leader":"dsh","members":[
              {"name":"android-coder","role":"coder","driver":"dsh","kind":"worker"},
              {"name":"android-qa","role":"tester","driver":"claude","kind":"reviewer"}
            ]}
            """.trimIndent(),
        )
        assertNotNull(spec)
        assertEquals("Android Dev", spec.teamTitle)
        assertEquals("dsh", spec.leaderAgentId)
        assertEquals(2, spec.members.size)
        val worker = spec.members.first { it.kind == TeamExecutionKindsUi.WORKER }
        assertEquals("android-coder", worker.name)
        assertEquals("dsh", worker.driverKey)
        assertTrue(worker.agentId.startsWith("m-android-coder-"))
    }

    @Test
    fun fencedOrNoisyReplyStillParses() {
        val spec = parse(
            "```json\n{\"teamName\":\"T\",\"leader\":\"codex\",\"members\":" +
                "[{\"name\":\"a\",\"role\":\"r\",\"driver\":\"dsh\",\"kind\":\"worker\"}]}\n```",
        )
        assertNotNull(spec)
        assertEquals(1, spec.members.size)
    }

    @Test
    fun unknownLeaderIsRejectedWholeSpec() {
        assertNull(
            parse("{\"teamName\":\"T\",\"leader\":\"ghost\",\"members\":[]}"),
            "领队不在位 = 整份拒绝回落模板（造一个永不执行的头没有宽容可言）",
        )
    }

    @Test
    fun unknownDriverFallsBackByKind() {
        val spec = parse(
            "{\"teamName\":\"T\",\"leader\":\"dsh\",\"members\":[" +
                "{\"name\":\"w\",\"role\":\"r\",\"driver\":\"nope\",\"kind\":\"worker\"}," +
                "{\"name\":\"v\",\"role\":\"r\",\"driver\":\"nope\",\"kind\":\"reviewer\"}]}",
        )
        assertNotNull(spec)
        assertEquals("codex", spec.members.first { it.name == "w" }.driverKey)
        assertEquals("claude", spec.members.first { it.name == "v" }.driverKey)
    }

    @Test
    fun workerCountIsEnforcedToExactlyOne() {
        val zero = parse(
            "{\"teamName\":\"T\",\"leader\":\"dsh\",\"members\":[" +
                "{\"name\":\"a\",\"role\":\"r\",\"driver\":\"claude\",\"kind\":\"reviewer\"}," +
                "{\"name\":\"b\",\"role\":\"r\",\"driver\":\"claude\",\"kind\":\"reviewer\"}]}",
        )
        assertNotNull(zero)
        assertEquals(listOf("a"), zero.members.filter { it.kind == TeamExecutionKindsUi.WORKER }.map { it.name })

        val many = parse(
            "{\"teamName\":\"T\",\"leader\":\"dsh\",\"members\":[" +
                "{\"name\":\"a\",\"role\":\"r\",\"driver\":\"dsh\",\"kind\":\"worker\"}," +
                "{\"name\":\"b\",\"role\":\"r\",\"driver\":\"codex\",\"kind\":\"worker\"}]}",
        )
        assertNotNull(many)
        assertEquals(listOf("a"), many.members.filter { it.kind == TeamExecutionKindsUi.WORKER }.map { it.name })
        assertEquals(TeamExecutionKindsUi.REVIEWER, many.members.first { it.name == "b" }.kind)
    }

    @Test
    fun duplicateNamesAreUniquifiedInsideTheTeam() {
        val spec = parse(
            "{\"teamName\":\"T\",\"leader\":\"dsh\",\"members\":[" +
                "{\"name\":\"dev\",\"role\":\"r\",\"driver\":\"dsh\",\"kind\":\"worker\"}," +
                "{\"name\":\"DEV\",\"role\":\"r\",\"driver\":\"claude\",\"kind\":\"reviewer\"}]}",
        )
        assertNotNull(spec)
        assertEquals(setOf("dev", "dev-2"), spec.members.map { it.name }.toSet())
    }

    @Test
    fun reviewerOnWorkerDriverSwapsToEscapeDriverWhenAvailable() {
        val swapped = parse(
            "{\"teamName\":\"T\",\"leader\":\"dsh\",\"members\":[" +
                "{\"name\":\"w\",\"role\":\"r\",\"driver\":\"dsh\",\"kind\":\"worker\"}," +
                "{\"name\":\"v\",\"role\":\"r\",\"driver\":\"dsh\",\"kind\":\"reviewer\"}]}",
        )
        assertNotNull(swapped)
        assertEquals("claude", swapped.members.first { it.name == "v" }.driverKey)

        // 兜底键与 worker 同驱动 = 无路可逃：如实保留，保存期的结构化原因说话。
        val stuck = parse(
            "{\"teamName\":\"T\",\"leader\":\"dsh\",\"members\":[" +
                "{\"name\":\"w\",\"role\":\"r\",\"driver\":\"dsh\",\"kind\":\"worker\"}," +
                "{\"name\":\"v\",\"role\":\"r\",\"driver\":\"dsh\",\"kind\":\"reviewer\"}]}",
            fallbackReviewerDriver = "dsh",
        )
        assertNotNull(stuck)
        assertEquals("dsh", stuck.members.first { it.name == "v" }.driverKey)
    }

    @Test
    fun rosterIsClampedToTheMemberLimit() {
        val members = (1..Limits.TEAM_MEMBERS_MAX + 3).joinToString(",") { i ->
            "{\"name\":\"m$i\",\"role\":\"r\",\"driver\":\"claude\",\"kind\":\"reviewer\"}"
        }
        val spec = parse("{\"teamName\":\"T\",\"leader\":\"dsh\",\"members\":[$members]}")
        assertNotNull(spec)
        assertEquals(Limits.TEAM_MEMBERS_MAX, spec.members.size)
    }

    /** 人设提示词：LLM 给了就随行解析，空白回落 null，超长截到名册弹窗同款预算。 */
    @Test
    fun memberPromptIsParsedClampedAndBlankFallsToNull() {
        val spec = assertNotNull(
            parse(
                """{"teamName":"t","leader":"dsh","members":[
                   {"name":"coder","role":"dev","driver":"codex","kind":"worker",
                    "prompt":" 先调研再实现，跑完测试再报告。 "},
                   {"name":"tester","role":"qa","driver":"claude","kind":"reviewer","prompt":"   "}]}""",
            ),
        )
        assertEquals("先调研再实现，跑完测试再报告。", spec.members[0].prompt, "trim 后保留")
        assertNull(spec.members[1].prompt, "空白人设回落 null（执行档用默认模板），不造空串人设")
        // 超长截断：上限与 TeamRosterDialog 手写输入同一预算（2000）
        val longPrompt = "x".repeat(2500)
        val clamped = assertNotNull(
            parse(
                """{"teamName":"t","leader":"dsh","members":[
                   {"name":"coder","role":"dev","driver":"codex","kind":"worker","prompt":"$longPrompt"}]}""",
            ),
        )
        assertEquals(2_000, clamped.members[0].prompt?.length)
    }

    /** 人设提示词必须真的落执行档：toExecutionMembers 把它带进 customPrompt。 */
    @Test
    fun scaffoldPromptLandsInExecutionProfileAsCustomPrompt() {
        val spec = assertNotNull(
            parse(
                """{"teamName":"t","leader":"dsh","members":[
                   {"name":"coder","role":"dev","driver":"codex","kind":"worker",
                    "prompt":" Investigate then implement. "}]}""",
            ),
        )
        val ui = spec.toExecutionMembers().single()
        assertEquals("Investigate then implement.", ui.customPrompt, "人设与名册行共用同一份成员 id，落执行档")
    }

    /** 回落模板也带内置人设：AI 与模板的差距在「聪明」，不在「有没有人设」。 */
    @Test
    fun fallbackTemplateCarriesBuiltInPersonaPrompts() {
        val spec = assertNotNull(
            TeamScaffoldFallback.template(
                need = "weather app prototype",
                leaderAgentId = "dsh",
                workerDriver = "codex",
                reviewerDriver = "claude",
                defaults = TeamScaffoldDefaults(teamTitle = "Team", role = "member"),
                newAgentId = fixedId,
            ),
        )
        assertTrue(spec.members.all { !it.prompt.isNullOrBlank() }, "回落模板每棒都要有人设")
        assertTrue(
            spec.members.first { it.kind == TeamExecutionKindsUi.WORKER }.prompt!!.contains("Investigate"),
            "worker 人设覆盖调研→实现→自测→报告的阶段化指引",
        )
    }

    @Test
    fun garbageOrEmptyRostersAreRejected() {
        assertNull(parse("not json at all"))
        assertNull(parse("{\"teamName\":\"T\",\"leader\":\"dsh\"}"), "缺 members = 拒")
        assertNull(parse("{\"teamName\":\"T\",\"leader\":\"dsh\",\"members\":[]}"), "空名册 = 拒")
        assertNull(
            TeamScaffoldParser.parse(
                raw = "{\"teamName\":\"\",\"leader\":\"dsh\",\"members\":" +
                    "[{\"name\":\"a\",\"role\":\"r\",\"driver\":\"dsh\",\"kind\":\"worker\"}]}",
                leaderKeys = leaders,
                driverKeys = drivers,
                fallbackWorkerDriver = "codex",
                fallbackReviewerDriver = "claude",
                defaultTeamName = "  ",
                defaultRole = "member",
                newAgentId = fixedId,
            ),
            "队名与缺省队名都为空 = 拒",
        )
    }

    @Test
    fun workerFlagBooleanIsAcceptedBesidesKind() {
        val spec = parse(
            "{\"teamName\":\"T\",\"leader\":\"dsh\",\"members\":[" +
                "{\"name\":\"a\",\"role\":\"r\",\"driver\":\"dsh\",\"worker\":true}," +
                "{\"name\":\"b\",\"role\":\"r\",\"driver\":\"claude\",\"worker\":false}]}",
        )
        assertNotNull(spec)
        assertEquals(TeamExecutionKindsUi.WORKER, spec.members.first { it.name == "a" }.kind)
        assertEquals(TeamExecutionKindsUi.REVIEWER, spec.members.first { it.name == "b" }.kind)
    }

    @Test
    fun fallbackTemplateIsDeterministicAndExecutable() {
        val defaults = TeamScaffoldDefaults(
            teamTitle = "New Team",
            role = "member",
            roles = listOf("code", "test", "review"),
        )
        val spec = TeamScaffoldFallback.template(
            need = "make an android app",
            leaderAgentId = "dsh",
            workerDriver = "codex",
            reviewerDriver = "claude",
            defaults = defaults,
            newAgentId = fixedId,
        )
        assertNotNull(spec)
        assertEquals("dsh", spec.leaderAgentId)
        assertEquals(3, spec.members.size)
        assertEquals(1, spec.members.count { it.kind == TeamExecutionKindsUi.WORKER })
        assertTrue(
            spec.members.none { it.driverKey == "codex" && it.kind == TeamExecutionKindsUi.REVIEWER },
            "reviewer 不许与 worker 同驱动（异厂商纪律）",
        )
        assertEquals(spec.members.map { it.name }.toSet().size, spec.members.size, "团队内名字唯一")
        assertEquals(listOf("code", "test", "review"), spec.members.map { it.role }, "角色标签来自本地化缺省")

        // 同一输入 ⇒ 同一输出（回落的意义在稳定可用，不在聪明）。
        seq = 0
        val again = TeamScaffoldFallback.template(
            need = "make an android app",
            leaderAgentId = "dsh",
            workerDriver = "codex",
            reviewerDriver = "claude",
            defaults = defaults,
            newAgentId = fixedId,
        )
        assertEquals(spec.members.map { it.name }, again?.members?.map { it.name })
    }

    @Test
    fun fallbackWithoutUsableDriverPairRefuses() {
        assertNull(
            TeamScaffoldFallback.template(
                need = "n", leaderAgentId = "dsh", workerDriver = "",
                reviewerDriver = "claude",
                defaults = TeamScaffoldDefaults(teamTitle = "T", role = "member"),
                newAgentId = fixedId,
            ),
        )
    }

    @Test
    fun specProjectsToRosterRowsAndExecutionMembersWithSharedIds() {
        val spec = assertNotNull(
            parse(
                "{\"teamName\":\"T\",\"leader\":\"dsh\",\"members\":[" +
                    "{\"name\":\"w\",\"role\":\"r\",\"driver\":\"dsh\",\"kind\":\"worker\"}," +
                    "{\"name\":\"v\",\"role\":\"r\",\"driver\":\"claude\",\"kind\":\"reviewer\"}]}",
            ),
        )
        val drafts = spec.toMemberDrafts()
        val exec = spec.toExecutionMembers()
        assertEquals(drafts.map { it.agentId }, exec.map { it.agentId }, "名册与执行档共用同一份成员 id")
        assertTrue(drafts.all { it.isNew })
        assertEquals(spec.members.map { it.name }, drafts.map { it.name })
        assertEquals(
            spec.members.map { it.kind },
            exec.map { it.kind },
        )
    }
}
