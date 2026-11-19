// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.core.protocol.TeamMemberInfo
import com.kapp.tenon.core.protocol.VirtualAgentMember
import com.kapp.tenon.domain.EmployeeProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 单兵卡承办人的**人名解析**（真机 UX 轮 2026-11-19 的修复）：
 * 用户点的是 @员工 / @组员（人），卡面却只认得出引擎（「Codex（本机）」×3）——
 * 同底座两个员工的两张单在用户眼里无法区分。
 *
 * 判据顺序：组员名（@组员，团队席位 member 行）→ 员工档案名（跨群一致的事实）→
 * 本群别名（employeeName）→ 席位名。说不清是谁（裸驱动派单）返回 null，
 * 卡面回落驱动名 —— 与旧行为逐字一致。
 */
class TicketActorNamingTest {

    private val self = "11111111-1111-4111-8111-111111111111"

    private fun group() = GroupMeta(
        groupId = "g-1",
        name = "协同群",
        members = listOf(self),
        rev = 2,
        updatedBy = self,
        updatedTs = 0L,
        virtualAgents = listOf(
            VirtualAgentMember(
                agentId = "emp-e2e-01",
                hostNodeId = self,
                name = "e2e",
                entityType = "single",
                employeeId = "emp-e2e-01",
            ),
            VirtualAgentMember(
                agentId = "e2e-team-lead",
                hostNodeId = self,
                name = "e2e-team-lead",
                entityType = "team",
                teamName = "e2e-team",
                teamMembers = listOf(
                    TeamMemberInfo(agentId = "m-e2e-coder", name = "coder", role = "编码"),
                    TeamMemberInfo(agentId = "m-e2e-reviewer", name = "reviewer", role = "审查"),
                ),
            ),
        ),
    )

    private fun profile(name: String, employeeId: String = "emp-e2e-01") = EmployeeProfile(
        employeeId = employeeId,
        agentId = employeeId,
        name = name,
        token = "e2e",
        driverKey = "codex",
        rev = 1,
        updatedTs = 0L,
    )

    @Test
    fun aMemberTokenResolvesToTheMemberName() {
        assertEquals(
            "coder",
            TicketActorNaming.assigneeNameOf(group(), emptyList(), "coder"),
            "@组员 直派：卡面写组员名（与团队链卡的 assignee 同源）",
        )
    }

    @Test
    fun anEmployeeTokenResolvesToTheProfileName() {
        assertEquals(
            "UX 小工",
            TicketActorNaming.assigneeNameOf(group(), listOf(profile("UX 小工")), "e2e"),
            "员工席位：档案名优先（跨群一致的事实）",
        )
    }

    @Test
    fun theSeatFallsBackToItsOwnNameWhenNoProfile() {
        assertEquals(
            "e2e",
            TicketActorNaming.assigneeNameOf(group(), emptyList(), "e2e"),
            "老数据（席位没带员工档案）：席位名顶上，不是 null 也不是驱动名",
        )
    }

    @Test
    fun aBareDriverKeyResolvesToNullAndKeepsTheOldBehavior() {
        assertNull(
            TicketActorNaming.assigneeNameOf(group(), listOf(profile("UX 小工")), "codex"),
            "裸驱动派单说不清是谁：null = 卡面回落驱动名（旧行为）",
        )
        assertNull(TicketActorNaming.assigneeNameOf(null, emptyList(), "e2e"))
        assertNull(TicketActorNaming.assigneeNameOf(group(), emptyList(), ""))
    }

    @Test
    fun displayActorPrefersThePersonAndKeepsRoutingOnTheDriverKey() {
        val person = DesktopWorkerOrchestrator.ActiveTicket(
            ticketId = "t1", convId = "c", groupId = null, job = kotlinx.coroutines.Job(),
            title = "t", repoPath = "/tmp", stage = "working",
            role = "UX 小工", agentKey = "codex", assigneeName = "UX 小工",
        )
        assertEquals("UX 小工", person.displayActor(), "显示走人名")
        assertEquals("codex", person.agentKey, "广播与授权仍走驱动键")
        val engineOnly = person.copy(assigneeName = null)
        assertEquals("codex", engineOnly.displayActor(), "不知道是谁：回落驱动键（旧行为）")
    }
}
