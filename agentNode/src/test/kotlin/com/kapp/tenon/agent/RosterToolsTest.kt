// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

import com.kapp.tenon.core.protocol.AgentDuties
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 工具面的两个纯件：[RosterTools]（动作表 + 参数校验 + 用法简报）与
 * [RosterWritePolicy]（写档位）。
 *
 * 它们被单测而不是只被路由覆盖，理由是**错在这里的代价不对称**：
 * 参数校验松一格 = agent 能发一条什么都没改的调用（看起来成功了），
 * 档位读错一格 = 默认放行。两条都必须在几毫秒内被钉住。
 */
class RosterToolsTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun args(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

    private fun spec(name: String) = RosterTools.specOf(name)!!

    @Test
    fun theCatalogHasUniqueNamesAndAReadableArgumentTable() {
        val names = RosterTools.ALL.map { it.name }
        assertEquals(names.distinct(), names, "动作名重复 = GET /v1/agent/tools 与分发会各说一套")
        assertTrue(RosterTools.ALL.all { it.args.isNotEmpty() }, "参数表就是 400 文案的来源，不能空")
        assertEquals(
            setOf(
                RosterTools.EMPLOYEE_UPDATE,
                RosterTools.TEAM_READ,
                RosterTools.TEAM_CREATE,
                RosterTools.TEAM_UPDATE,
            ),
            names.toSet(),
        )
        // duty 的取值域必须与领域层同一份（抄一份就会漂）。
        assertEquals(
            AgentDuties.ALL,
            spec(RosterTools.EMPLOYEE_UPDATE).args.first { it.name == "duty" }.values,
        )
        // 读动作不许标记成写：否则它会白弹一张审批卡（读不改变世界）。
        assertEquals(false, spec(RosterTools.TEAM_READ).mutates)
        assertTrue(spec(RosterTools.TEAM_CREATE).mutates)
        assertTrue(spec(RosterTools.TEAM_UPDATE).mutates)
    }

    @Test
    fun theBriefMentionsEveryActionSoThePromptCannotDrift() {
        val brief = RosterTools.brief()
        RosterTools.ALL.forEach { spec ->
            assertTrue(spec.name in brief, "简报里漏了动作 " + spec.name + "（agent 会不知道它能调）")
        }
        assertTrue("审批" in brief || "同意" in brief, "写动作要等机主这件事必须写在简报里：" + brief)
    }

    @Test
    fun aMissingEmployeeIdIsRejectedWithAPointerToTheListRoute() {
        val error = RosterTools.parseCall(
            spec(RosterTools.EMPLOYEE_UPDATE),
            args("""{"name":"小王"}"""),
        ).exceptionOrNull()
        assertTrue(error?.message?.contains("employeeId") == true, error?.message ?: "应当报缺 employeeId")
    }

    @Test
    fun aCallThatChangesNothingIsRejected() {
        // 只给 employeeId：写路径那边会以 ARGUMENT_INVALID 拒，但工具面先挡一层，
        // 回执里才能说清「你这条调用什么都没改」而不是抛一个枚举名。
        val error = RosterTools.parseCall(
            spec(RosterTools.EMPLOYEE_UPDATE),
            args("""{"employeeId":"emp-1"}"""),
        ).exceptionOrNull()
        assertTrue(error?.message?.contains("至少") == true, error?.message ?: "")
    }

    @Test
    fun aDutyOutsideTheDomainIsRejectedNotSilentlyNormalized() {
        // `AgentDuties.normalize` 会把域外值折回 assistant —— 对 agent 来说那就是「设了没生效」。
        val error = RosterTools.parseCall(
            spec(RosterTools.EMPLOYEE_UPDATE),
            args("""{"employeeId":"emp-1","duty":"Coder"}"""),
        ).exceptionOrNull()
        assertTrue(error?.message?.contains("duty") == true, error?.message ?: "")
    }

    @Test
    fun aWellFormedCallKeepsOnlyTheFieldsItWasGiven() {
        val call = assertIs<RosterTools.Call.EmployeeUpdate>(
            RosterTools.parseCall(
                spec(RosterTools.EMPLOYEE_UPDATE),
                args("""{"employeeId":" emp-1 ","name":"  后端小王  "}"""),
            ).getOrThrow(),
        )
        assertEquals("emp-1", call.employeeId, "首尾空白要归一（否则查不到档案）")
        assertEquals("后端小王", call.name)
        assertNull(call.duty, "没给就不该带上 —— null = 这次不改它")
    }

    @Test
    fun readingTeamsAsksForNoArgumentsAtAll() {
        val call = assertIs<RosterTools.Call.TeamRead>(
            RosterTools.parseCall(spec(RosterTools.TEAM_READ), null).getOrThrow(),
        )
        assertNull(call.teamId, "不给 teamId = 读全部")
        val one = assertIs<RosterTools.Call.TeamRead>(
            RosterTools.parseCall(spec(RosterTools.TEAM_READ), args("""{"teamId":" tm-1 "}""")).getOrThrow(),
        )
        assertEquals("tm-1", one.teamId)
    }

    @Test
    fun creatingATeamNeedsANameALeaderAndARoster() {
        val ok = assertIs<RosterTools.Call.TeamCreate>(
            RosterTools.parseCall(
                spec(RosterTools.TEAM_CREATE),
                args(
                    """
                    {"name":"开发一组","leaderAgentId":"dsh-lead",
                     "members":[{"agentId":"m-1","name":"coder","role":"编码"}],
                     "groupIds":["g1"," g2 "]}
                    """.trimIndent(),
                ),
            ).getOrThrow(),
        )
        assertEquals("开发一组", ok.draft.name)
        assertEquals(listOf("g1", "g2"), ok.draft.groupIds, "群 id 要去空白")
        assertEquals(1, ok.draft.members.size)

        val noRoster = RosterTools.parseCall(
            spec(RosterTools.TEAM_CREATE),
            args("""{"name":"x","leaderAgentId":"dsh-lead","members":[]}"""),
        ).exceptionOrNull()
        assertTrue(noRoster?.message?.contains("members") == true, noRoster?.message ?: "")

        val badShape = RosterTools.parseCall(
            spec(RosterTools.TEAM_CREATE),
            args("""{"name":"x","leaderAgentId":"dsh-lead","members":"coder"}"""),
        ).exceptionOrNull()
        assertTrue(badShape?.message?.contains("数组") == true, badShape?.message ?: "形状不对要当场说")

        val missingField = RosterTools.parseCall(
            spec(RosterTools.TEAM_CREATE),
            args("""{"name":"x","leaderAgentId":"l","members":[{"agentId":"m-1","name":"coder"}]}"""),
        ).exceptionOrNull()
        assertTrue(missingField?.message?.contains("role") == true, missingField?.message ?: "")
    }

    @Test
    fun updatingATeamWithoutAnyFieldIsRejected() {
        val error = RosterTools.parseCall(
            spec(RosterTools.TEAM_UPDATE),
            args("""{"teamId":"tm-1"}"""),
        ).exceptionOrNull()
        assertTrue(error?.message?.contains("至少") == true, error?.message ?: "")
        val ok = assertIs<RosterTools.Call.TeamUpdate>(
            RosterTools.parseCall(spec(RosterTools.TEAM_UPDATE), args("""{"teamId":"tm-1","name":"新名字"}""")).getOrThrow(),
        )
        assertEquals("新名字", ok.name)
        assertNull(ok.members, "没给 = 名册不动")
    }

    @Test
    fun aMalformedGroupListIsRejectedNotReadAsNoGroups() {
        // `"groupIds":"g1"` 若被读成「没给」，agent 以为队伍进了群、其实群里看不见它。
        val error = RosterTools.parseCall(
            spec(RosterTools.TEAM_CREATE),
            args("""{"name":"x","leaderAgentId":"l","members":[{"agentId":"m-1","name":"coder","role":"编码"}],"groupIds":"g1"}"""),
        ).exceptionOrNull()
        assertTrue(error?.message?.contains("数组") == true, error?.message ?: "")
    }

    @Test
    fun theTierDefaultsToRequestAndNeverReadsAForeignValue() {
        val dir = kotlin.io.path.createTempDirectory("roster-policy").toFile()
        val file = File(dir, "roster-write.json")

        val fresh = RosterWritePolicy(file)
        assertEquals(RosterWritePolicy.Tiers.REQUEST, fresh.tier(), "没配过 = request（fail-closed）")
        assertNull(fresh.error(), "文件不存在不是错误，只是默认档")

        assertTrue(fresh.setTier(RosterWritePolicy.Tiers.AUTO))
        assertEquals(RosterWritePolicy.Tiers.AUTO, RosterWritePolicy(file).tier(), "档位要落盘并读得回来")

        file.writeText("""{"tier":"whatever"}""")
        val corrupt = RosterWritePolicy(file)
        assertEquals(RosterWritePolicy.Tiers.REQUEST, corrupt.tier(), "不认识的取值按 request 跑")
        assertTrue(corrupt.error()?.contains("whatever") == true, "坏档位要能说出来：" + corrupt.error())

        file.writeText("{ not json")
        val broken = RosterWritePolicy(file)
        assertEquals(RosterWritePolicy.Tiers.REQUEST, broken.tier())
        assertTrue(broken.error() != null, "坏文件必须留痕，否则「我明明配了 auto」查不出来")
        assertTrue(!broken.setTier("sometimes"), "非法档位不许写进去")
    }
}
