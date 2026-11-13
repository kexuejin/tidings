// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.core.protocol.EntityTypes
import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.core.protocol.VirtualAgentMember
import com.kapp.tenon.domain.TeamExecutionMemberUi
import com.kapp.tenon.domain.TeamExecutionKindsUi
import com.kapp.tenon.domain.TeamExecutionPort
import com.kapp.tenon.domain.TeamExecutionProfileUi
import com.kapp.tenon.domain.TeamExecutionSaveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 工位端口的四条判据：本机、指派优先、slug 回落、多值不猜。
 *
 * 每条都点名断言 `driverKey` 的具体取值（而不只是「非 null」）—— 「有值」与「值对」是
 * 两件事，只断言前者的话，把 codex 写成 claude 也照样绿。
 */
class DesktopAgentWorkstationPortTest {

    private val selfHost = "host-self"
    private val otherHost = "host-other"

    private fun seat(
        agentId: String = "dsh-lead",
        host: String = selfHost,
    ) = VirtualAgentMember(
        agentId = agentId,
        hostNodeId = host,
        name = agentId + "-seat",
        entityType = EntityTypes.SINGLE,
    )

    private fun group(id: String, vararg seats: VirtualAgentMember) = GroupMeta(
        groupId = id,
        name = id,
        members = listOf(selfHost, otherHost),
        rev = 1,
        updatedBy = selfHost,
        updatedTs = 1,
        ownerId = selfHost,
        adminIds = emptyList(),
        virtualAgents = seats.toList(),
    )

    private fun profile(
        groupId: String,
        dirId: String,
        leader: String,
        driver: String,
    ) = TeamExecutionProfileUi(
        groupId = groupId,
        dirId = dirId,
        leaderAgentId = leader,
        members = listOf(
            TeamExecutionMemberUi(agentId = leader, kind = TeamExecutionKindsUi.WORKER, driverKey = driver),
        ),
    )

    /** 只回答「哪份执行档」的最小假端口：工位只读它，不写、不校验、不存。 */
    private class FakeExecution(
        private val profiles: List<TeamExecutionProfileUi>,
        private val pointers: Map<String, String>,
    ) : TeamExecutionPort {
        override fun driverCatalog(): Set<String> =
            profiles.flatMap { p -> p.members.map { it.driverKey } }.toSet()

        override fun profileOf(groupId: String, dirId: String, leaderAgentId: String): TeamExecutionProfileUi? =
            profiles.firstOrNull {
                it.groupId == groupId && it.dirId == dirId && it.leaderAgentId == leaderAgentId
            }

        override fun validate(profile: TeamExecutionProfileUi, declaredMemberIds: Set<String>): List<String> =
            emptyList()

        override fun save(
            profile: TeamExecutionProfileUi,
            declaredMemberIds: Set<String>,
        ): TeamExecutionSaveResult = TeamExecutionSaveResult.Saved

        override fun groupPointerDirId(groupId: String): String? = pointers[groupId]
    }

    private fun port(
        groups: List<GroupMeta>,
        execution: TeamExecutionPort,
        self: String = selfHost,
    ) = DesktopAgentWorkstationPort(
        selfNodeId = { self },
        groups = { groups },
        execution = execution,
    )

    /** 指派过的底座优先：编制里写的 codex 就是名录上要报的那个。 */
    @Test
    fun assignedDriverWins() {
        val execution = FakeExecution(
            profiles = listOf(profile("g1", "ws-1", "dsh-lead", "codex")),
            pointers = mapOf("g1" to "ws-1"),
        )
        val ws = port(listOf(group("g1", seat())), execution).workstationOf(selfHost, "dsh-lead")
        val hit = assertNotNull(ws, "本机席位 + 执行档 ⇒ 必须读得到工位")
        assertEquals("codex", hit.driverKey, "指派的是 codex，不能被席位 slug 的 dsh 覆盖")
        assertEquals("Codex", hit.driverLabel)
    }

    /** 别的设备的执行档不在本进程里：读不到就如实 null，不拿本机同 slug 顶替。 */
    @Test
    fun anotherHostHasNoWorkstationHere() {
        val execution = FakeExecution(
            profiles = listOf(profile("g1", "ws-1", "dsh-lead", "codex")),
            pointers = mapOf("g1" to "ws-1"),
        )
        val ws = port(listOf(group("g1", seat())), execution).workstationOf(otherHost, "dsh-lead")
        assertNull(ws, "别的宿主的执行绑定本进程读不到 —— 不能猜一个")
    }

    /**
     * 没指派过时按席位 slug 归一（与执行侧「空 driverKey 按席位 slug 走」同一条解析）。
     *
     * 卡片上的身份键可能来自会话自报的短名（`dsh`），而席位是长 id（`dsh-029bbbf3`）——
     * 前缀归一必须命中，否则同一张卡在「有会话」与「只有席位」两种状态下工位时有时无。
     */
    @Test
    fun unassignedFallsBackToSeatSlug() {
        val execution = FakeExecution(profiles = emptyList(), pointers = mapOf("g1" to "ws-1"))
        val ws = assertNotNull(
            port(listOf(group("g1", seat("dsh-029bbbf3"))), execution).workstationOf(selfHost, "dsh"),
        )
        assertEquals("dsh", ws.driverKey)
        assertEquals("DeepSeek Harness", ws.driverLabel)
    }

    /** 群没设项目目录指针 ⇒ 执行档主键缺一半，这一条无从判断 —— 但不影响 slug 回落。 */
    @Test
    fun groupWithoutDirectoryPointerStillFallsBackToSlug() {
        val execution = FakeExecution(
            profiles = listOf(profile("g1", "ws-1", "claude-seat", "codex")),
            // 指针缺失：拿不到 dirId，执行档读不到（与上面 profile 存在与否无关）
            pointers = emptyMap(),
        )
        val ws = assertNotNull(
            port(listOf(group("g1", seat("claude-seat"))), execution).workstationOf(selfHost, "claude-seat"),
        )
        assertEquals("claude", ws.driverKey, "没有指针 ⇒ 走 slug 归一，不是「读不到就不画」")
    }

    /** 两个群绑了不同底座：一次只画一个格子，不替人挑。 */
    @Test
    fun conflictingDriversAcrossGroupsAreNotGuessed() {
        val execution = FakeExecution(
            profiles = listOf(
                profile("g1", "ws-1", "dsh-lead", "codex"),
                profile("g2", "ws-2", "dsh-lead", "claude"),
            ),
            pointers = mapOf("g1" to "ws-1", "g2" to "ws-2"),
        )
        val groups = listOf(group("g1", seat()), group("g2", seat()))
        assertNull(
            port(groups, execution).workstationOf(selfHost, "dsh-lead"),
            "两处指派不同 ⇒ 不猜；挑一个就是画错",
        )
    }

    /** 同一个底座被多个群指派不是冲突 —— 集合去重之后仍是单值。 */
    @Test
    fun sameDriverInSeveralGroupsIsNotAConflict() {
        val execution = FakeExecution(
            profiles = listOf(
                profile("g1", "ws-1", "dsh-lead", "codex"),
                profile("g2", "ws-2", "dsh-lead", "codex-cli"),
            ),
            pointers = mapOf("g1" to "ws-1", "g2" to "ws-2"),
        )
        val groups = listOf(group("g1", seat()), group("g2", seat()))
        val ws = assertNotNull(port(groups, execution).workstationOf(selfHost, "dsh-lead"))
        assertEquals("codex", ws.driverKey, "`codex-cli` 与 `codex` 是同一个底座，归一到档案键")
    }

    /** 身份键缺失（「未上报类型」那一档）没有可解析的驱动。 */
    @Test
    fun blankAgentKeyHasNoWorkstation() {
        val execution = FakeExecution(profiles = emptyList(), pointers = emptyMap())
        assertNull(port(listOf(group("g1", seat())), execution).workstationOf(selfHost, ""))
    }
}
