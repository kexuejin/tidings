// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.AgentDuties
import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.core.protocol.VirtualAgentMember
import com.kapp.tenon.core.protocol.declarationFor
import com.kapp.tenon.platform.InMemoryAppPlatform
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel

/**
 * 员工档案写路径（[applyEmployeeProfile]）的判据。
 *
 * 钉的是「**员工先创建，群只负责添加**」这条语义 —— 少一条，员工就又退回
 * 「没有对象、名字按群派生、改名只能撤回重入驻」：
 *
 * 1. **可以先建员工、一个群都不进**：档案是独立的一份对象，不是席位的副产物；
 * 2. **一份档案多群复用**：同一员工进两个群，两群各一条席位，档案只有一份；
 * 3. **改名一次生效**：改档案名 ⇒ 所有引用群的显示名同时变，而 `@` token**不漂**
 *    （token 一漂，群里所有人的输入习惯就断）；
 * 4. **撞名是别名、不是拒绝**：员工 token 在某群被占用 ⇒ 该群用 `token-2`，
 *    结果里如实标出来（`aliasedGroupIds`），不静默；
 * 5. **删档案不踢人**：`Forget` 只摘员工引用，席位与名字都保留。
 */
class EmployeeProfileWritePathTest {

    private val scope = CoroutineScope(Job())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private val self = "abc12345-0000-4000-8000-000000000000"
    private val peer = "11111111-1111-4111-8111-111111111111"

    private fun controller(): RelayController = RelayController(
        platform = InMemoryAppPlatform(),
        store = MemorySettingsStore(self),
        scope = scope,
        version = "relay-test",
        agentStore = InMemoryAgentStore(),
    )

    private class MemorySettingsStore(private val nodeId: String) : SettingsStore {
        private var current: RelaySettings? = RelaySettings(
            nodeId = nodeId,
            nick = "我的桌面",
            udpPort = 0,
            tcpPort = 0,
        )

        override fun load(): RelaySettings? = current

        override fun save(settings: RelaySettings) {
            current = settings
        }
    }

    /**
     * 三个群：
     * - `g1` 本机已有 `dsh` 席位 `dsh-lead`（验证「已有席位的名字不被档案抢走」）；
     * - `g2` 干净、没有本机席位（验证就地入驻 + 新席位用档案 token）；
     * - `g3` 对端席位叫 `coder`（验证撞名走别名而不是拒绝）。
     */
    private fun seed(controller: RelayController) {
        val groups = listOf(
            GroupMeta(
                groupId = "g1",
                name = "项目研发群",
                members = listOf(self, peer),
                rev = 4,
                updatedBy = peer,
                updatedTs = 1_000L,
                virtualAgents = listOf(declarationFor("dsh", self, "dsh-lead", "ws-a", previous = null)),
            ),
            GroupMeta(
                groupId = "g2",
                name = "测试群",
                members = listOf(self),
                rev = 2,
                updatedBy = self,
                updatedTs = 1_000L,
                virtualAgents = null,
            ),
            GroupMeta(
                groupId = "g3",
                name = "已有 coder 的群",
                members = listOf(self, peer),
                rev = 1,
                updatedBy = peer,
                updatedTs = 1_000L,
                virtualAgents = listOf(
                    VirtualAgentMember(agentId = "codex", hostNodeId = peer, name = "coder"),
                ),
            ),
        )
        controller.handlePersist(controller.state.value.settings.copy(groups = groups))
    }

    private fun groupOf(controller: RelayController, groupId: String): GroupMeta =
        controller.state.value.settings.groups.first { it.groupId == groupId }

    private fun seatOf(controller: RelayController, groupId: String): VirtualAgentMember? =
        groupOf(controller, groupId).virtualAgents.orEmpty()
            .firstOrNull { it.hostNodeId == self && it.agentId == "dsh" }

    private fun createCommand(
        groupIds: List<String> = listOf("g1", "g2"),
        name: String = "老王",
        token: String? = null,
        employeeId: String = "emp-test",
        agentId: String = "dsh",
    ) = EmployeeProfileCommand.Create(
        name = name,
        agentId = agentId,
        groupIds = groupIds,
        token = token,
        employeeId = employeeId,
        actor = DeclarationActor.Human(self),
    )

    // ------------------------------------------------------------ 先建员工，再决定进哪个群

    @Test
    fun anEmployeeCanExistBeforeJoiningAnyGroup() {
        val controller = controller().also { seed(it) }
        val result = controller.applyEmployeeProfile(createCommand(groupIds = emptyList()))
        assertTrue(result is EmployeeProfileResult.Applied, "建档应当成功，实际 $result")
        assertTrue(result.groupIds.isEmpty(), "没给群就不该进任何群")

        val profile = assertNotNull(controller.state.value.settings.employeeProfiles.singleOrNull())
        assertEquals("emp-test", profile.employeeId)
        assertEquals("dsh", profile.agentId)
        assertEquals("老王", profile.name)
        // 纯中文名 slug 之后是空串 ⇒ 必须回落 kind-职责，不能产出打不出来的空 token。
        assertEquals("dsh-assistant", profile.token, "中文名要回落 kind-职责，不是空 token")

        // 群里一条席位都没动 —— 建档与进群是两件事。
        assertNull(seatOf(controller, "g1")?.employeeId)
        assertEquals(4, groupOf(controller, "g1").rev, "建档不该改群 rev")
    }

    // ------------------------------------------------------------ 一份档案多群复用

    @Test
    fun oneProfileBacksTheSameEmployeeInSeveralGroups() {
        val controller = controller().also { seed(it) }
        val result = controller.applyEmployeeProfile(createCommand(groupIds = listOf("g1", "g2")))
        assertTrue(result is EmployeeProfileResult.Applied, "建档加群应当成功，实际 $result")
        assertEquals(listOf("g1", "g2"), result.groupIds)
        assertTrue(result.failedGroupIds.isEmpty(), "两个群都该写成功")

        // 档案只有一份。
        val profiles = controller.state.value.settings.employeeProfiles
        assertEquals(1, profiles.size, "多群复用靠一份档案，不是每个群一份")

        // 两个群各一条席位，指向同一份档案。
        for (groupId in listOf("g1", "g2")) {
            val seat = assertNotNull(seatOf(controller, groupId), "$groupId 里该有这条席位")
            assertEquals("emp-test", seat.employeeId, "$groupId 的席位没挂上档案引用")
            assertEquals("老王", seat.employeeName, "$groupId 的席位没写上员工名快照")
        }
        // 执行点是每群事实：g1 原有 ws-a 不被抹掉，g2 没有就是没有。
        assertEquals("ws-a", seatOf(controller, "g1")?.defaultWsId)
        assertNull(seatOf(controller, "g2")?.defaultWsId)
        // 已有席位保留它现在的名字（那可能就是用户在用的 @ token）。
        assertEquals("dsh-lead", seatOf(controller, "g1")?.name)
        // 新席位用档案 token。
        assertEquals("dsh-assistant", seatOf(controller, "g2")?.name)

        // g1 的席位名与档案 token 不同 ⇒ 本群别名，必须如实标出来。
        assertEquals(listOf("g1"), result.aliasedGroupIds)
    }

    // ------------------------------------------------------------ 撞名 = 本群别名

    @Test
    fun aTokenClashBecomesALocalAliasInsteadOfARejection() {
        val controller = controller().also { seed(it) }
        val result = controller.applyEmployeeProfile(
            createCommand(groupIds = listOf("g3"), token = "coder"),
        )
        assertTrue(result is EmployeeProfileResult.Applied, "撞名不该让整个建档失败，实际 $result")
        assertEquals(listOf("g3"), result.aliasedGroupIds, "别名群必须被标出来，不能静默")

        val seat = assertNotNull(seatOf(controller, "g3"))
        assertEquals("emp-test", seat.employeeId)
        assertEquals("coder-2", seat.name, "撞名时用别名（token-2），不是拒绝入驻")
        assertEquals("老王", seat.employeeName, "员工名与 @ token 是两件事")
        // 对端那条同名席位一条都不许动。
        assertEquals(
            "coder",
            groupOf(controller, "g3").virtualAgents.orEmpty().first { it.hostNodeId == peer }.name,
        )
    }

    // ------------------------------------------------------------ 改名一次生效、token 不漂

    @Test
    fun renamingTheEmployeeUpdatesEverySnapshotWithoutTouchingTokens() {
        val controller = controller().also { seed(it) }
        controller.applyEmployeeProfile(createCommand(groupIds = listOf("g1", "g2")))
        val tokenBefore = controller.state.value.settings.employeeProfiles.single().token
        val seatNamesBefore = listOf("g1", "g2").associateWith { seatOf(controller, it)?.name }

        val renamed = controller.applyEmployeeProfile(
            EmployeeProfileCommand.Update(
                employeeId = "emp-test",
                name = "前端小王",
                actor = DeclarationActor.Human(self),
            ),
        )
        assertTrue(renamed is EmployeeProfileResult.Applied, "改名应当成功，实际 $renamed")
        assertEquals("前端小王", renamed.name)

        for (groupId in listOf("g1", "g2")) {
            assertEquals("前端小王", seatOf(controller, groupId)?.employeeName, "$groupId 没跟着改名")
            assertEquals(seatNamesBefore[groupId], seatOf(controller, groupId)?.name, "@ token 不许跟着显示名漂")
        }
        // 档案 rev 只 +1（名字改一次，不是每群各一次）。
        assertEquals(2, controller.state.value.settings.employeeProfiles.single().rev)
        assertEquals(tokenBefore, controller.state.value.settings.employeeProfiles.single().token)
        assertEquals("dsh", seatOf(controller, "g1")?.agentId, "身份键不受改名影响")
    }

    @Test
    fun changingTheTokenExplicitlyRewritesEverySeatName() {
        val controller = controller().also { seed(it) }
        controller.applyEmployeeProfile(createCommand(groupIds = listOf("g1", "g2")))

        val result = controller.applyEmployeeProfile(
            EmployeeProfileCommand.Update(
                employeeId = "emp-test",
                token = "coder-x",
                actor = DeclarationActor.Human(self),
            ),
        )
        assertTrue(result is EmployeeProfileResult.Applied, "改 token 应当成功，实际 $result")
        assertEquals("coder-x", controller.state.value.settings.employeeProfiles.single().token)
        // 显式改 token 是对所有引用群的显式重建：席位名跟着改（这与「改名」相反）。
        for (groupId in listOf("g1", "g2")) {
            assertEquals("coder-x", seatOf(controller, groupId)?.name, "$groupId 的席位名没跟着 token 改")
        }
    }

    @Test
    fun aDutyChangeDoesNotTouchSeats() {
        val controller = controller().also { seed(it) }
        controller.applyEmployeeProfile(createCommand(groupIds = listOf("g1")))
        val nameBefore = seatOf(controller, "g1")?.name
        val revBefore = groupOf(controller, "g1").rev

        val result = controller.applyEmployeeProfile(
            EmployeeProfileCommand.Update(
                employeeId = "emp-test",
                duty = "reviewer",
                actor = DeclarationActor.Human(self),
            ),
        )
        assertTrue(result is EmployeeProfileResult.Applied, "改职责应当成功，实际 $result")
        assertEquals("reviewer", controller.state.value.settings.employeeProfiles.single().duty)
        assertEquals(nameBefore, seatOf(controller, "g1")?.name, "改职责不该动席位名")
        // 职责进档案就要重建快照（对端要能显示），所以 rev 仍会推进 —— 但只推一次。
        assertEquals(revBefore + 1, groupOf(controller, "g1").rev)
    }

    @Test
    fun dutyFallsBackToTheSingleDefaultInsteadOfNull() {
        val controller = controller().also { seed(it) }
        controller.applyEmployeeProfile(createCommand(groupIds = emptyList()))
        assertEquals(
            AgentDuties.DEFAULT_SINGLE,
            controller.state.value.settings.employeeProfiles.single().duty,
            "职责缺省必须归一成单兵默认，不能留 null 让下游各猜一遍",
        )
    }

    // ------------------------------------------------------------ 加群 / 删档案

    @Test
    fun anExistingProfileCanBeAddedToMoreGroupsLater() {
        val controller = controller().also { seed(it) }
        controller.applyEmployeeProfile(createCommand(groupIds = listOf("g1")))
        assertNull(seatOf(controller, "g2"), "前提：g2 还没有席位")

        val added = controller.applyEmployeeProfile(
            EmployeeProfileCommand.AddToGroups(
                employeeId = "emp-test",
                groupIds = listOf("g2"),
                actor = DeclarationActor.Human(self),
            ),
        )
        assertTrue(added is EmployeeProfileResult.Applied, "加群应当成功，实际 $added")
        assertEquals(listOf("g2"), added.groupIds)
        assertEquals("emp-test", seatOf(controller, "g2")?.employeeId, "后加的群也要挂上同一个档案")
        assertEquals(1, controller.state.value.settings.employeeProfiles.size, "加群不该复制档案")
    }

    @Test
    fun forgettingAnEmployeeKeepsTheSeatsAndTheirNames() {
        val controller = controller().also { seed(it) }
        controller.applyEmployeeProfile(createCommand(groupIds = listOf("g1", "g2")))
        val g2Name = seatOf(controller, "g2")?.name

        val result = controller.applyEmployeeProfile(
            EmployeeProfileCommand.Forget(
                employeeId = "emp-test",
                actor = DeclarationActor.Human(self),
            ),
        )
        assertTrue(result is EmployeeProfileResult.Applied, "删档案应当成功，实际 $result")
        assertEquals(listOf("g1", "g2"), result.groupIds)

        assertTrue(controller.state.value.settings.employeeProfiles.isEmpty(), "档案该没了")
        // 席位与名字都保留 —— 删档案不等于把人踢出群。
        for (groupId in listOf("g1", "g2")) {
            val seat = assertNotNull(seatOf(controller, groupId), "$groupId 的席位不该被删")
            assertNull(seat.employeeId, "$groupId 的档案引用该摘掉")
            assertNull(seat.employeeName, "$groupId 的员工名快照该摘掉")
        }
        assertEquals(g2Name, seatOf(controller, "g2")?.name, "名字不跟着档案一起消失")
    }

    // ------------------------------------------------------------ 拒绝面

    @Test
    fun aSecondProfileForTheSameAgentIsRejected() {
        val controller = controller().also { seed(it) }
        controller.applyEmployeeProfile(createCommand(groupIds = emptyList()))
        val second = controller.applyEmployeeProfile(
            createCommand(groupIds = emptyList(), name = "小李", employeeId = "emp-2"),
        )
        assertEquals(
            DeclarationRejection.EMPLOYEE_EXISTS,
            (second as? EmployeeProfileResult.Rejected)?.reason,
            "一个 agent 只能有一份档案；要改名走 Update",
        )
    }

    @Test
    fun updatingAnUnknownProfileIsRejected() {
        val controller = controller().also { seed(it) }
        val result = controller.applyEmployeeProfile(
            EmployeeProfileCommand.Update(
                employeeId = "emp-nope",
                name = "谁",
                actor = DeclarationActor.Human(self),
            ),
        )
        assertEquals(
            DeclarationRejection.EMPLOYEE_UNKNOWN,
            (result as? EmployeeProfileResult.Rejected)?.reason,
        )
    }

    @Test
    fun anExplicitTokenClashingWithAnotherProfileIsRejected() {
        val controller = controller().also { seed(it) }
        controller.applyEmployeeProfile(
            createCommand(groupIds = emptyList(), token = "dsh-a", employeeId = "emp-a"),
        )
        val clash = controller.applyEmployeeProfile(
            createCommand(groupIds = emptyList(), name = "小李", token = "dsh-a", employeeId = "emp-b", agentId = "codex"),
        )
        assertEquals(
            DeclarationRejection.NAME_INVALID,
            (clash as? EmployeeProfileResult.Rejected)?.reason,
            "本机两份档案不能共用一个 @ token",
        )
    }

    @Test
    fun anEmptyNameIsRejected() {
        val controller = controller().also { seed(it) }
        val result = controller.applyEmployeeProfile(
            createCommand(groupIds = emptyList(), name = "   "),
        )
        assertEquals(
            DeclarationRejection.NAME_INVALID,
            (result as? EmployeeProfileResult.Rejected)?.reason,
        )
    }

    @Test
    fun anEmptyUpdateIsRejected() {
        val controller = controller().also { seed(it) }
        controller.applyEmployeeProfile(createCommand(groupIds = emptyList()))
        val result = controller.applyEmployeeProfile(
            EmployeeProfileCommand.Update(
                employeeId = "emp-test",
                actor = DeclarationActor.Human(self),
            ),
        )
        assertEquals(
            DeclarationRejection.ARGUMENT_INVALID,
            (result as? EmployeeProfileResult.Rejected)?.reason,
            "一个字段都没给的更新是调用方拼错了，不许静默成功",
        )
    }
}
