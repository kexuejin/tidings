// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.platform.InMemoryAppPlatform
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel

/**
 * 席位 ↔ 员工档案的**精确定位**（方案 §2② 三段判据的行为面）。
 *
 * `seatAgentIdFor` 是私有函数，判据只能从**公开行为**钉：A4 修复后，同底座两人在同一群
 * 各占一条带自己 `employeeId` 的席位 —— 这里钉的是由此推出的两条必须成立的行为：
 *
 * 1. **注销摘对引用**：`forgetEmployeeProfileEntry` 走 `DetachEmployee`，带 `employeeId`
 *    精确定位 —— 摘 A 不能动 B 的席位（同键席位 agentId 都是 kind 级键，只按 agentId
 *    定位会摘错人）；
 * 2. **首次挂档案认唯一席位**：无引用 ∧ 恰一条 agentId 精确相等的席位 → 复用它（席位总数
 *    不变），而不是另立一条让同一个 agent 在一个群里出现两次。
 */
class SeatEmployeeResolutionTest {

    private val scope = CoroutineScope(Job())
    private val self = "abc12345-0000-4000-8000-000000000000"

    @AfterTest
    fun tearDown() { scope.cancel() }

    private class MemorySettingsStore(private val nodeId: String) : SettingsStore {
        var current: RelaySettings? = RelaySettings(nodeId = nodeId, nick = "本机", udpPort = 0, tcpPort = 0)
        override fun load(): RelaySettings? = current
        override fun save(settings: RelaySettings) { current = settings }
    }

    private fun controller(catalog: Set<String>? = null): RelayController = RelayController(
        platform = InMemoryAppPlatform(),
        store = MemorySettingsStore(self),
        scope = scope,
        version = "relay-test",
        agentStore = InMemoryAgentStore(),
    ).also { it.driverInCatalogOf = catalog?.let { set -> { id -> id in set } } }

    private fun mint(employeeId: String, groupIds: List<String>) = EmployeeProfileCommand.Create(
        name = "员工-$employeeId",
        agentId = "", // MINT：还没派过单，路由线索留白（A1）
        driverKey = "dsh",
        employeeId = employeeId,
        source = EmployeeCreationSource.MINT,
        groupIds = groupIds,
        actor = DeclarationActor.Human(self),
    )

    private fun seedGroup(controller: RelayController, groupId: String) {
        val group = GroupMeta.create(
            groupId, "共用群", members = listOf(self), selfId = self, updatedTs = 1L,
        )
        controller.handlePersist(controller.state.value.settings.copy(groups = listOf(group)))
    }

    private fun localSeats(controller: RelayController, groupId: String) =
        controller.state.value.settings.groups.first { it.groupId == groupId }
            .virtualAgents.orEmpty().filter { it.hostNodeId == self }

    @Test
    fun forgetRemovesOnlyItsOwnSeatReference() {
        val c = controller(catalog = setOf("dsh"))
        seedGroup(c, "g-shared")
        assertTrue(c.applyEmployeeProfile(mint("emp-a", listOf("g-shared"))) is EmployeeProfileResult.Applied)
        assertTrue(c.applyEmployeeProfile(mint("emp-b", listOf("g-shared"))) is EmployeeProfileResult.Applied)
        assertEquals(
            2, localSeats(c, "g-shared").size,
            "同底座两人各占一条席位（A4 前提，EmployeeCreationTest 已钉）",
        )

        c.forgetEmployeeProfileEntry("emp-a")

        val seats = localSeats(c, "g-shared")
        assertEquals(
            setOf("emp-b"),
            seats.mapNotNull { it.employeeId }.toSet(),
            "注销 emp-a 只摘它自己的引用，emp-b 的席位引用必须原样保留",
        )
        assertEquals(
            2, seats.size,
            "注销档案不等于踢人：席位本身保留（Forget 的既有语义），只是回「未登记 agent」",
        )
    }

    @Test
    fun firstAttachReusesTheExactlyOneUnclaimedSeatInsteadOfAddingAnother() {
        val c = controller(catalog = setOf("dsh"))
        // 群里先有一条**未认领**的本机席位（老数据形态：agentId 是实例键，employeeId 为空）。
        val group = GroupMeta.create(
            "g-legacy", "老数据群", members = listOf(self), selfId = self, updatedTs = 1L,
        ).copy(virtualAgents = listOf(
            com.kapp.tenon.core.protocol.VirtualAgentMember(
                agentId = "dsh-029bbbf3",
                hostNodeId = self,
                name = "dsh-coder",
            ),
        ))
        c.handlePersist(c.state.value.settings.copy(groups = listOf(group)))
        // 登记该实例为员工（不进群），再单独加群 —— 首次挂档案的正常路径。
        assertTrue(
            c.applyEmployeeProfile(
                EmployeeProfileCommand.Create(
                    name = "登记员工", agentId = "dsh-029bbbf3", driverKey = "dsh",
                    employeeId = "emp-reg", source = EmployeeCreationSource.REGISTER_EXISTING,
                    groupIds = emptyList(),
                    actor = DeclarationActor.Human(self),
                ),
            ) is EmployeeProfileResult.Applied,
        )
        val addResult = c.applyEmployeeProfile(
            EmployeeProfileCommand.AddToGroups(employeeId = "emp-reg", groupIds = listOf("g-legacy"), actor = DeclarationActor.Human(self)),
        )
        assertTrue(addResult is EmployeeProfileResult.Applied, "加群应成功：$addResult")

        val seats = localSeats(c, "g-legacy")
        assertEquals(
            1, seats.size,
            "agentId 精确相等的未认领席位恰有一条 → 必须复用它（三段判据②），而不是另立一条",
        )
        assertEquals(
            "emp-reg", seats.single().employeeId,
            "复用的席位要带上员工引用 —— 否则改名/注销永远到不了这个群",
        )
    }
}
