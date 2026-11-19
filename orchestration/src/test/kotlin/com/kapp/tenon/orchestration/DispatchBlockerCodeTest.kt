// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.core.protocol.AgentExecAuthorizer
import com.kapp.tenon.core.protocol.AgentExecPolicies
import com.kapp.tenon.core.protocol.EntityTypes
import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.core.protocol.TeamMemberInfo
import com.kapp.tenon.core.protocol.VirtualAgentMember
import com.kapp.tenon.domain.DispatchBlockedCode
import com.kapp.tenon.domain.TeamProfile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 派单受阻分类码全量契约测试（方案 §3.5 / §4，P2）：
 *
 * 验证分类码的生成判据与优先级顺序：
 * 1. [DispatchBlockedCode.TARGET_MISSING]
 * 2. [DispatchBlockedCode.TEAM_NOT_IN_GROUP]（与 TARGET_MISSING 严格互斥：先看团队档案再看席位）
 * 3. [DispatchBlockedCode.NOT_AUTHORIZED]
 * 4. [DispatchBlockedCode.NO_DIRECTORY]
 * 5. [DispatchBlockedCode.NO_DRIVER]
 * 6. [DispatchBlockedCode.DRIVER_MISSING]
 *
 * `DIR_NOT_ALLOWED` **不在本表**：产线上没有任何「这台电脑是否被授权访问这个目录」的
 * 事实源，注入口曾经存在但只有测试会传它 —— 那条用例证明的是一个不存在的通路。
 * 枚举值与界面映射保留（跨端兼容），等真有授权事实时再接线。
 */
class DispatchBlockerCodeTest {

    private val self = "blk-node-0000-4000-8000-0000000000"
    private val otherNode = "peer-remote-node-1"
    private val groupId = "g-blocker"
    private val dirId = "ws-blocker"

    private fun tempStore(): Pair<TeamExecutionStore, File> {
        val dir = File(System.getProperty("java.io.tmpdir"), "blk-test-" + System.nanoTime()).apply { mkdirs() }
        val file = File(dir, "exec.json")
        return TeamExecutionStore(file) to dir
    }

    private fun sampleTeamSeat(host: String = self) = VirtualAgentMember(
        agentId = "lead-seat",
        hostNodeId = host,
        name = "dev-squad",
        entityType = EntityTypes.TEAM,
        teamId = "tm-squad",
        teamName = "研发小分队",
        teamMembers = listOf(
            TeamMemberInfo("emp-1", "coder-squad", "编码"),
            TeamMemberInfo("emp-2", "tester-squad", "测试"),
        ),
    )

    // 1. TARGET_MISSING
    @Test
    fun targetMissingWhenSeatOnRemoteHostOrWorkerDangling() {
        val (store, dir) = tempStore()
        try {
            val remoteSeat = sampleTeamSeat(host = otherNode)
            val group = GroupMeta.create(groupId, "测试群", members = listOf(self), selfId = self, updatedTs = 1L)
                .copy(virtualAgents = listOf(remoteSeat))

            val resolver = TeamExecutionResolver(store, dirIdOfGroup = { dirId })
            val target = resolver.resolve(
                groupId = groupId,
                mentionedKey = "研发小分队",
                group = group,
                selfNodeId = self,
            )
            val notConfigured = assertIs<TeamExecutionResolver.Target.NotConfigured>(target)
            assertEquals(DispatchBlockedCode.TARGET_MISSING, notConfigured.code)
            assertTrue(notConfigured.reason.contains("another device"))
        } finally {
            dir.deleteRecursively()
        }
    }

    // 2. TEAM_NOT_IN_GROUP 与 TARGET_MISSING 互斥
    @Test
    fun teamNotInGroupWinsOverTargetMissingWhenTeamProfileExists() {
        val (store, dir) = tempStore()
        try {
            val group = GroupMeta.create(groupId, "空席位群", members = listOf(self), selfId = self, updatedTs = 1L)
            val localTeam = TeamProfile(
                teamId = "tm-local",
                name = "核心开发组",
                leaderAgentId = "lead-1",
                members = listOf(TeamMemberInfo("emp-1", "core-coder", "研发")),
            )

            val resolver = TeamExecutionResolver(store, dirIdOfGroup = { dirId })
            // (a) 命中团队名
            val byName = resolver.resolve(
                groupId = groupId,
                mentionedKey = "核心开发组",
                group = group,
                selfNodeId = self,
                teamProfiles = listOf(localTeam),
            )
            val notInGroup1 = assertIs<TeamExecutionResolver.Target.NotInGroup>(byName)
            assertEquals(DispatchBlockedCode.TEAM_NOT_IN_GROUP, notInGroup1.code)
            assertEquals("tm-local", notInGroup1.team.teamId)

            // (b) 命中未入驻队伍的组员 token
            val byMember = resolver.resolve(
                groupId = groupId,
                mentionedKey = "core-coder",
                group = group,
                selfNodeId = self,
                teamProfiles = listOf(localTeam),
            )
            val notInGroup2 = assertIs<TeamExecutionResolver.Target.NotInGroup>(byMember)
            assertEquals(DispatchBlockedCode.TEAM_NOT_IN_GROUP, notInGroup2.code)
            assertEquals("tm-local", notInGroup2.team.teamId)

            // (c) 真正未知名字流回 None（不抢 TEAM_NOT_IN_GROUP）
            val unknown = resolver.resolve(
                groupId = groupId,
                mentionedKey = "completely-unknown",
                group = group,
                selfNodeId = self,
                teamProfiles = listOf(localTeam),
            )
            assertIs<TeamExecutionResolver.Target.None>(unknown)
        } finally {
            dir.deleteRecursively()
        }
    }

    // 3. NOT_AUTHORIZED（只测判据本身）
    //
    // 这条**不**覆盖「判据 → 卡面 code」那一跳：那条路上要一个真的编排器 + 一次群派单，
    // 由 `DesktopWorkerOrchestrator` 的 `publishTeamDispatchBlocked(code = NOT_AUTHORIZED)`
    // 完成，本轮没有夹具。别把这条读成「NOT_AUTHORIZED 已经到卡上了」。
    @Test
    fun notAuthorizedWhenPolicyRestrictsCaller() {
        val group = GroupMeta.create(groupId, "测试群", members = listOf(self, otherNode), selfId = self, updatedTs = 1L)
        val verdict = AgentExecAuthorizer.authorize(
            senderId = otherNode,
            group = group,
            grantAllows = { false },
            policy = AgentExecPolicies.LIST,
            selfNodeId = self,
        )
        assertTrue(with(AgentExecAuthorizer) { !verdict.permitsExecution() })
    }

    // 4. NO_DIRECTORY
    @Test
    fun noDirectoryWhenGroupHasNoDirectoryBound() {
        val (store, dir) = tempStore()
        try {
            val seat = sampleTeamSeat(host = self)
            val group = GroupMeta.create(groupId, "无目录群", members = listOf(self), selfId = self, updatedTs = 1L)
                .copy(virtualAgents = listOf(seat))

            val resolver = TeamExecutionResolver(store, dirIdOfGroup = { null })
            val target = resolver.resolve(
                groupId = groupId,
                mentionedKey = "研发小分队",
                group = group,
                selfNodeId = self,
            )
            val notConfigured = assertIs<TeamExecutionResolver.Target.NotConfigured>(target)
            assertEquals(DispatchBlockedCode.NO_DIRECTORY, notConfigured.code)
        } finally {
            dir.deleteRecursively()
        }
    }

    // 5. NO_DRIVER
    @Test
    fun noDriverWhenTeamExecutionProfileNotConfigured() {
        val (store, dir) = tempStore()
        try {
            val seat = sampleTeamSeat(host = self)
            val group = GroupMeta.create(groupId, "无底座配置群", members = listOf(self), selfId = self, updatedTs = 1L)
                .copy(virtualAgents = listOf(seat))

            val resolver = TeamExecutionResolver(store, dirIdOfGroup = { dirId })
            val target = resolver.resolve(
                groupId = groupId,
                mentionedKey = "研发小分队",
                group = group,
                selfNodeId = self,
            )
            val notConfigured = assertIs<TeamExecutionResolver.Target.NotConfigured>(target)
            assertEquals(DispatchBlockedCode.NO_DRIVER, notConfigured.code)
        } finally {
            dir.deleteRecursively()
        }
    }

    // 6. DRIVER_MISSING
    @Test
    fun driverMissingWhenAssignedDriverNotInstalledInAvailableDrivers() {
        val profile = TeamExecutionProfile(
            dirId = dirId,
            leaderAgentId = "lead-seat",
            updatedAt = 0L,
            members = listOf(
                TeamExecutionMemberSpec(agentId = "emp-1", kind = "worker", driverKey = "claude"),
            ),
        )
        val seatMembers = listOf(TeamMemberInfo("emp-1", "coder", "编码"))
        val hitMember = seatMembers.single()

        val compiled = TeamPlanCompiler.compile(
            profile = profile,
            declaredMembers = seatMembers,
            hitMember = hitMember,
            goal = "实现登录",
            availableDrivers = setOf("codex", "dsh"), // 只有 codex 和 dsh，缺 claude
        )
        val rejected = assertIs<TeamPlanCompiler.Compiled.Rejected>(compiled)
        assertEquals(DispatchBlockedCode.DRIVER_MISSING, rejected.code)
        assertTrue(rejected.reason.contains("claude"))
    }

    // 7. 非驱动类拒绝**不带码**
    //
    // 这条守的是 `Rejected.code` 的默认值：它从前是 `DRIVER_MISSING`，于是「执行档一个人
    // 都不能干活」这类编制错误会在卡面上被翻成「这个运行时不在这台电脑上」——
    // chip 与那句人话都指错方向。默认值必须是 null，只有真·驱动缺失才带码。
    @Test
    fun nonDriverRejectionCarriesNoCode() {
        val profile = TeamExecutionProfile(
            dirId = dirId,
            leaderAgentId = "lead-seat",
            updatedAt = 0L,
            members = listOf(
                TeamExecutionMemberSpec(agentId = "emp-1", kind = "reviewer", driverKey = "claude"),
            ),
        )
        val seatMembers = listOf(
            TeamMemberInfo("emp-1", "coder", "编码"),
        )
        val compiled = TeamPlanCompiler.compile(
            profile = profile,
            declaredMembers = seatMembers,
            hitMember = seatMembers.first(),
            goal = "实现登录",
            availableDrivers = setOf("codex", "claude"),
        )
        val rejected = assertIs<TeamPlanCompiler.Compiled.Rejected>(compiled)
        assertNull(rejected.code, "「执行档至少要有一个人能干活」不是运行时不在这台电脑上")
        assertTrue(rejected.reason.contains("at least one worker"), rejected.reason)
    }
}
