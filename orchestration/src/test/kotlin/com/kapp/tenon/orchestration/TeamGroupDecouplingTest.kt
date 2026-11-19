// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.core.protocol.AgentExecPolicies
import com.kapp.tenon.core.protocol.ConvId
import com.kapp.tenon.core.protocol.EntityTypes
import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.core.protocol.TeamMemberInfo
import com.kapp.tenon.core.protocol.VirtualAgentMember
import com.kapp.tenon.domain.DeclarationActor
import com.kapp.tenon.domain.EmployeeProfile
import com.kapp.tenon.domain.InMemoryAgentStore
import com.kapp.tenon.domain.InMemoryMessageStore
import com.kapp.tenon.domain.NoUiTexts
import com.kapp.tenon.domain.RelayController
import com.kapp.tenon.domain.RelaySettings
import com.kapp.tenon.domain.RelayUiTexts
import com.kapp.tenon.domain.TeamProfile
import com.kapp.tenon.domain.TeamProfileCommand
import com.kapp.tenon.domain.TeamProfileResult
import com.kapp.tenon.domain.applyTeamProfile
import com.kapp.tenon.platform.InMemoryAppPlatform
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * 团队与群解耦（方案 §3.4，P1b）的**写路径 + 派单路径**双面验收：
 *
 * 1. **建队不选群**：`Create` 空 `groupIds` = 只建档（一等路径），一条席位都不写；
 * 2. **`@队名` 在未入驻的群里** ⇒ blocked 卡带 `TEAM_NOT_IN_GROUP` 码 —— 不许被
 *    kind 路径误报成 `TARGET_MISSING`（互斥判据：先看本机团队档案再看席位）；
 * 3. **入驻后再派** ⇒ 派单期按目录**就地建出执行档初值**（第一名 worker、其余 reviewer），
 *    复用成员的 driverKey/model 从员工档案读、`TeamMemberInfo.prompt` 浇进 `customPrompt`；
 *    成员底座不齐时不落半份档（不猜底座）。
 */
class TeamGroupDecouplingTest {

    private val self = "dec-node-0000-4000-8000-0000000000"
    private val dirId = "ws-1"

    /** 测试装置五元组（kotlin 无内建 Quintuple）。 */
    private data class Rig(
        val controller: RelayController,
        val orch: DesktopWorkerOrchestrator,
        val store: TeamExecutionStore,
        val scope: CoroutineScope,
        val dir: File,
    )

    private class SeededSettingsStore(private val base: RelaySettings) : com.kapp.tenon.domain.SettingsStore {
        override fun load(): RelaySettings? = base
        override fun save(settings: RelaySettings) { /* orchestration 测试不需要回写 */ }
    }

    private fun teamSeat() = VirtualAgentMember(
        agentId = "dsh-lead",
        hostNodeId = self,
        name = "dev-team",
        entityType = EntityTypes.TEAM,
        teamId = "tm-1",
        teamName = "开发团队",
        teamMembers = listOf(
            TeamMemberInfo("emp-a", "coder-x", "编码", prompt = "先调研再动手，逐条报告"),
            TeamMemberInfo("emp-b", "tester-x", "测试"),
        ),
    )

    private fun harness(withTeamSeat: Boolean, stripDriverA: Boolean = false): Rig {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val group = GroupMeta.create(
            "g-dec", "解耦群", members = listOf(self, "peer-x"), selfId = self, updatedTs = 1L,
        )
        val withSeat = if (withTeamSeat) group.copy(virtualAgents = listOf(teamSeat())) else group
        val settings = RelaySettings.firstRun().copy(
            nodeId = self,
            udpPort = 0, tcpPort = 0,
            persistMessages = false, persistMessagesDecided = true,
            groups = listOf(withSeat),
            employeeProfiles = listOf(
                EmployeeProfile(
                    employeeId = "emp-a", agentId = "", name = "老周", token = "coder-x",
                    duty = "coder", driverKey = if (stripDriverA) "" else "dsh",
                ),
                EmployeeProfile(
                    employeeId = "emp-b", agentId = "", name = "小李", token = "tester-x",
                    duty = "tester", driverKey = "claude",
                ),
            ),
            teamProfiles = listOf(
                TeamProfile(
                    teamId = "tm-1", name = "开发团队", leaderAgentId = "dsh-lead",
                    members = listOf(
                        TeamMemberInfo("emp-a", "coder-x", "编码", prompt = "先调研再动手，逐条报告"),
                        TeamMemberInfo("emp-b", "tester-x", "测试"),
                    ),
                ),
            ),
        )
        val controller = RelayController(
            platform = InMemoryAppPlatform(),
            store = SeededSettingsStore(settings),
            messageStore = InMemoryMessageStore(),
            agentStore = InMemoryAgentStore(),
            scope = scope,
        )
        controller.uiTexts = object : RelayUiTexts by NoUiTexts {
            override suspend fun dispatchFailedNotice(title: String, agentName: String, reason: String) =
                "dispatch failed: $title / $agentName / $reason"
        }
        controller.invokeExecPolicyOf = { AgentExecPolicies.ANY_MEMBER }
        val dir = File(System.getProperty("java.io.tmpdir"), "team-decouple-" + System.nanoTime()).apply { mkdirs() }
        val store = TeamExecutionStore(File(dir, "team-execution.json"))
        val orch = DesktopWorkerOrchestrator(
            planeProvider = { null },
            controller = controller,
            scope = scope,
            teamExecutionStore = store,
            groupDirIdOf = { dirId },
        )
        return Rig(controller, orch, store, scope, dir)
    }

    private suspend fun dispatch(controller: RelayController, orch: DesktopWorkerOrchestrator): List<String> {
        val convId = ConvId.group("g-dec")
        orch.handlePrompt(
            convId = convId,
            senderPeerId = self,
            prompt = "把登录修好",
            replyToId = null,
            wsId = null,
            mentionedAgentKey = "开发团队",
        )
        return controller.state.value.messages[convId].orEmpty().map {
            "state=${it.agentState} text=${it.text} card=${it.card ?: ""}"
        }
    }

    // ------------------------------------------------------------- 建队不选群

    @Test
    fun creatingATeamWithNoGroupsWritesOnlyTheProfile() {
        val rig = harness(withTeamSeat = false)
        try {
            val result = rig.controller.applyTeamProfile(
                TeamProfileCommand.Create(
                    name = "零群团队",
                    leaderAgentId = "dsh-lead",
                    members = listOf(
                        com.kapp.tenon.core.protocol.TeamMemberDraft("emp-a", "coder-x", "编码", prompt = "人设"),
                    ),
                    groupIds = emptyList(),
                    actor = DeclarationActor.Human(self),
                ),
            )
            assertTrue(result is TeamProfileResult.Applied, "空群 = 只建档，一等路径（§3.4）：$result")
            // harness 预置了 tm-1，加上新建的这份 = 2。
            assertEquals(2, rig.controller.state.value.settings.teamProfiles.size)
            val created = rig.controller.state.value.settings.teamProfiles
                .single { it.teamId == result.teamId }
            assertEquals(
                "人设", created.members.single().prompt,
                "名册草稿的人设随行落档（TeamMemberInfo.prompt）",
            )
            // 没有群 ⇒ 没有席位：任何群的 virtualAgents 都不引用这份新档案。
            assertTrue(
                rig.controller.state.value.settings.groups.all { g ->
                    g.virtualAgents.orEmpty().none { it.teamId == result.teamId }
                },
                "建队不选群 ⇒ 不写任何执行席位",
            )
        } finally {
            rig.scope.cancel()
            rig.dir.deleteRecursively()
        }
    }

    // ------------------------------------------------- @队名 未入驻 → TEAM_NOT_IN_GROUP

    @Test
    fun aTeamMentionInAnUnattachedGroupIsBlockedAsTeamNotInGroup() = runBlocking {
        val rig = harness(withTeamSeat = false)
        try {
            val rows = dispatch(rig.controller, rig.orch)
            val blockedCard = rows.firstOrNull { it.contains("\"blockedCode\":\"TEAM_NOT_IN_GROUP\"") }
            assertNotNull(blockedCard, "未入驻群的 @队名 必须落 TEAM_NOT_IN_GROUP 码（不许误报 TARGET_MISSING）：$rows")
            assertTrue(
                blockedCard.contains("\"attachTeamId\":\"tm-1\""),
                "卡要带主行动路由（attachTeamId）：把队加进这个群",
            )
        } finally {
            rig.scope.cancel()
            rig.dir.deleteRecursively()
        }
    }

    // -------------------------------- 入驻后派单 → 派单期按目录建执行档初值 + prompt 浇入

    @Test
    fun dispatchingAfterAttachBootstrapsTheExecutionProfileWithPrompts() = runBlocking {
        val rig = harness(withTeamSeat = true)
        try {
            dispatch(rig.controller, rig.orch)
            val profile = rig.store.profileFor(dirId, "dsh-lead")
            assertNotNull(profile, "派单期必须按目录建出执行档初值（档案在、席位在、目录在）")
            assertEquals(
                listOf(TeamExecutionKinds.WORKER, TeamExecutionKinds.REVIEWER),
                profile.members.map { it.kind },
                "初值：名册第一名 worker，其余 reviewer（§3.4 P1b-4）",
            )
            assertEquals("dsh", profile.members[0].driverKey, "复用成员的底座从员工档案读")
            assertEquals("claude", profile.members[1].driverKey)
            assertEquals(
                "先调研再动手，逐条报告", profile.members[0].customPrompt,
                "TeamMemberInfo.prompt 必须浇进执行档 customPrompt 初值（人设属于名册的一行）",
            )
            assertNull(profile.members[1].customPrompt, "没写人设的行 = null（回落默认模板），不造空串")
        } finally {
            rig.scope.cancel()
            rig.dir.deleteRecursively()
        }
    }

    // ------------------------------------------------- 派单期不落「半份档」(不猜底座)

    @Test
    fun aMemberWithoutAnEmployeeProfileKeepsTheProfileUnbootstrapped() = runBlocking {
        // emp-a 的底座抹掉（模拟新建行 m-… / 老数据无 driverKey）：settings 预置时就抹
        //（orchestration 侧没有 :app 的 handlePersist 可用，预置 store 是本仓装配口径）。
        val rig = harness(withTeamSeat = true, stripDriverA = true)
        try {
            val rows = dispatch(rig.controller, rig.orch)
            assertNull(
                rig.store.profileFor(dirId, "dsh-lead"),
                "成员底座不齐 ⇒ 不落半份执行档（不猜底座）；blocked 卡说明缺什么",
            )
            assertTrue(
                rows.any { it.contains("\"blockedCode\":\"NO_DRIVER\"") },
                "blocked 卡如实报 NO_DRIVER（去群编制指派）：$rows",
            )
        } finally {
            rig.scope.cancel()
            rig.dir.deleteRecursively()
        }
    }
}
