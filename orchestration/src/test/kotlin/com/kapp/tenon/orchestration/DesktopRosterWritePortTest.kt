// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.RosterDenial
import com.kapp.tenon.agent.RosterMember
import com.kapp.tenon.agent.RosterTeamDraft
import com.kapp.tenon.agent.RosterWriteResult
import com.kapp.tenon.core.protocol.AgentDuties
import com.kapp.tenon.core.protocol.TeamMemberInfo
import com.kapp.tenon.domain.EmployeeProfile
import com.kapp.tenon.domain.InMemoryAgentStore
import com.kapp.tenon.domain.InMemorySettingsStore
import com.kapp.tenon.domain.RelayController
import com.kapp.tenon.domain.RelaySettings
import com.kapp.tenon.domain.TeamProfile
import com.kapp.tenon.platform.InMemoryAppPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 工具面写面的宿主实现（[DesktopRosterWritePort]）：把「agent 改员工名字」接到
 * `applyEmployeeProfile` 那条唯一写路径上。
 *
 * 钉三件事：
 *
 * 1. **只走那条写路径**：改完设置里真的变了，且没旁路（不自己 `copy(employeeProfiles=)`）；
 * 2. **拒绝要有人话**：档案不存在、名字非法都给可读原因 —— 工具调用方是 agent，
 *    它拿不到人话来就没法重试或停下（沉默是这套系统最贵的故障形态）；
 * 3. **token 与 agentId 不在开口之列**：工具面签名里根本没有这两个参数，
 *    这里断言改完 name 之后 token / agentId 一字不动。
 */
class DesktopRosterWritePortTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun controller(profiles: List<EmployeeProfile>): RelayController {
        val controller = RelayController(
            platform = InMemoryAppPlatform(),
            store = InMemorySettingsStore(
                RelaySettings.firstRun().copy(
                    udpPort = 0,
                    tcpPort = 0,
                    persistMessages = false,
                    employeeProfiles = profiles,
                ),
            ),
            agentStore = InMemoryAgentStore(),
            scope = scope,
        )
        return controller
    }

    private fun controllerWithTeams(teams: List<TeamProfile>): RelayController {
        val controller = RelayController(
            platform = InMemoryAppPlatform(),
            store = InMemorySettingsStore(
                RelaySettings.firstRun().copy(
                    udpPort = 0,
                    tcpPort = 0,
                    persistMessages = false,
                    teamProfiles = teams,
                ),
            ),
            agentStore = InMemoryAgentStore(),
            scope = scope,
        )
        return controller
    }

    private fun team(teamId: String = "tm-1", name: String = "开发一组") = TeamProfile(
        teamId = teamId,
        name = name,
        leaderAgentId = "dsh-lead",
        members = listOf(TeamMemberInfo("m-1", "coder", "编码")),
        rev = 2,
    )

    private fun profile(
        employeeId: String = "emp-1",
        name: String = "前端小王",
        token: String = "dsh-coder",
        duty: String = AgentDuties.CODER,
    ) = EmployeeProfile(
        employeeId = employeeId,
        agentId = "dsh",
        name = name,
        token = token,
        duty = duty,
        rev = 1,
    )

    @Test
    fun employeesExposeWhatTheToolNeedsToPickATarget() {
        val port = DesktopRosterWritePort(controller(listOf(profile())))
        val snapshot = port.employees().single()
        assertEquals("emp-1", snapshot.employeeId)
        assertEquals("dsh", snapshot.agentId)
        assertEquals("前端小王", snapshot.name)
        assertEquals(AgentDuties.CODER, snapshot.duty)
    }

    @Test
    fun renamingThroughThePortLandsOnTheOneWritePath() {
        val controller = controller(listOf(profile()))
        val port = DesktopRosterWritePort(controller)

        val result = assertIs<RosterWriteResult.Applied>(
            port.updateEmployee(agentKey = "dsh", employeeId = "emp-1", name = "后端小王", duty = null),
        )
        assertEquals("前端小王", result.before.name)
        assertEquals("后端小王", result.after.name)

        val stored = controller.state.value.settings.employeeProfiles.single()
        assertEquals("后端小王", stored.name)
        // 身份与 @ token 一字不动：它们不在工具面的开口之列。
        assertEquals("dsh", stored.agentId, "agentId 是身份，换底座都不许动")
        assertEquals("dsh-coder", stored.token, "token 一漂，群里所有人的 @ 习惯就断")
        assertEquals(AgentDuties.CODER, stored.duty, "没给 duty 就不该改它")
        assertTrue(stored.rev > 1, "revision 必须推进（否则界面与对端都读不出「变了」）")
    }

    @Test
    fun changingOnlyTheDutyKeepsTheName() {
        val controller = controller(listOf(profile()))
        val port = DesktopRosterWritePort(controller)

        assertIs<RosterWriteResult.Applied>(
            port.updateEmployee(agentKey = null, employeeId = "emp-1", name = null, duty = AgentDuties.REVIEWER),
        )
        val stored = controller.state.value.settings.employeeProfiles.single()
        assertEquals("前端小王", stored.name)
        assertEquals(AgentDuties.REVIEWER, stored.duty)
    }

    @Test
    fun anUnknownEmployeeIsDeniedWithAReadableReason() {
        val port = DesktopRosterWritePort(controller(emptyList()))
        val denied = assertIs<RosterWriteResult.Denied>(
            port.updateEmployee(agentKey = "dsh", employeeId = "emp-missing", name = "x", duty = null),
        )
        assertEquals(RosterDenial.UNKNOWN_EMPLOYEE, denied.reason)
        assertEquals("emp-missing", denied.detail, "细节要点名是哪一份档案")
    }

    @Test
    fun ablankNameIsDeniedNotSilentlyIgnored() {
        val port = DesktopRosterWritePort(controller(listOf(profile())))
        // 工具面已经把空串折成 null，所以这里只剩「非法短名」这一档能被写路径拒
        // —— 用超长名走同一分支（`NAME_INVALID` 的两种形态共用一条拒绝）。
        val denied = assertIs<RosterWriteResult.Denied>(
            port.updateEmployee(agentKey = "dsh", employeeId = "emp-1", name = "x".repeat(200), duty = null),
        )
        assertEquals(RosterDenial.NAME_INVALID, denied.reason, "拒绝要分类成「名字不合法」")
    }

    // ------------------------------------------------------------------ 团队

    @Test
    fun teamsExposeTheirGroupsBecauseAGroupLessTeamCannotBeMentioned() {
        val port = DesktopRosterWritePort(controllerWithTeams(listOf(team())))
        val snapshot = port.teams().single()
        assertEquals("tm-1", snapshot.teamId)
        assertEquals("开发一组", snapshot.name)
        assertEquals("dsh-lead", snapshot.leaderAgentId)
        assertEquals(listOf("coder"), snapshot.members.map { it.name })
        assertEquals(2, snapshot.rev)
        assertEquals(emptyList(), snapshot.groupIds, "没进群的团队必须先看到「不在任何群」")
    }

    @Test
    fun creatingATeamThroughThePortLandsOnTheOneWritePath() {
        val controller = controllerWithTeams(emptyList())
        val port = DesktopRosterWritePort(controller)

        val result = assertIs<RosterWriteResult.TeamApplied>(
            port.createTeam(
                agentKey = "dsh",
                draft = RosterTeamDraft(
                    name = "开发二组",
                    leaderAgentId = "dsh-lead",
                    members = listOf(RosterMember("m-2", "coder2", "编码")),
                    groupIds = emptyList(),
                ),
            ),
        )
        assertNull(result.before, "新建没有 before")
        assertEquals("开发二组", result.after.name)
        // 真的落到设置里（走的是 applyTeamProfile，不是这里自己 copy 一份）。
        val stored = controller.state.value.settings.teamProfiles.single()
        assertEquals(result.after.teamId, stored.teamId)
        assertEquals(listOf("coder2"), stored.members.map { it.name })
        assertEquals(emptyList(), result.after.groupIds, "空 groupIds = 只建档")
    }

    @Test
    fun renamingATeamKeepsItsIdAndBumpsTheRevision() {
        val controller = controllerWithTeams(listOf(team()))
        val port = DesktopRosterWritePort(controller)
        val before = port.teams().single()

        val result = assertIs<RosterWriteResult.TeamApplied>(
            port.updateTeam(agentKey = "dsh", teamId = "tm-1", name = "开发三组", members = null),
        )
        assertEquals("开发一组", result.before?.name)
        assertEquals("开发三组", result.after.name)
        assertEquals("tm-1", result.after.teamId, "teamId 是身份，改名不许换键")
        assertTrue(result.after.rev > before.rev, "rev 要推进（快照重建靠它判「真的变了」）")
        assertEquals(listOf("coder"), result.after.members.map { it.name }, "没给名册就不动它")
    }

    @Test
    fun anUnknownTeamIsDeniedWithAClassifiedReason() {
        val port = DesktopRosterWritePort(controllerWithTeams(emptyList()))
        val denied = assertIs<RosterWriteResult.Denied>(
            port.updateTeam(agentKey = "dsh", teamId = "tm-missing", name = "x", members = null),
        )
        assertEquals(RosterDenial.UNKNOWN_TEAM, denied.reason)
        assertEquals("tm-missing", denied.detail)
    }

    @Test
    fun anInvalidRosterIsDeniedAsARosterProblemNotAsAFieldProblem() {
        val port = DesktopRosterWritePort(controllerWithTeams(listOf(team())))
        // 两行同名：`TeamRosterValidation` 的 DUPLICATE_IN_TEAM。分类必须是 ROSTER_INVALID，
        // 否则回执会说「没有要改的字段」—— 那是另一件事，会把人带偏。
        val denied = assertIs<RosterWriteResult.Denied>(
            port.updateTeam(
                agentKey = "dsh",
                teamId = "tm-1",
                name = null,
                members = listOf(RosterMember("m-1", "coder", "编码"), RosterMember("m-2", "coder", "测试")),
            ),
        )
        assertEquals(RosterDenial.ROSTER_INVALID, denied.reason)
    }
}
