// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.domain.model.WorkspaceKey
import com.kapp.tenon.domain.model.WorkspaceUi
import com.kapp.tenon.platform.InMemoryAppPlatform
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.update

/**
 * 建档来源两档的预校验（方案 §3.3 / §7）。
 *
 * 两档共用一个 [EmployeeProfileCommand.Create] 与一份档案结构，差别只在预校验：
 * - MINT 不看台账（台账非空也能建 —— 同底座多人的前提），但看底座在位；
 * - REGISTER_EXISTING 仍拒幽灵（台账非空但 agentId 不匹配）。
 * 两档失败都不留半份档案。
 */
class EmployeeCreationTest {

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

    private fun create(
        source: EmployeeCreationSource,
        employeeId: String,
        agentId: String = "dsh",
        driverKey: String = "",
    ) = EmployeeProfileCommand.Create(
        name = "员工-$employeeId",
        agentId = agentId,
        employeeId = employeeId,
        driverKey = driverKey,
        source = source,
        groupIds = emptyList(),
        actor = DeclarationActor.Human(self),
    )

    /** 造一条本机台账条目（agentWorkspace），让「台账非空」成立。 */
    private fun RelayController.seedLedger(agentId: String) {
        // agentWorkspaces 是运行时状态（RelayUiState），不是落盘设置 —— 直接更新 _state。
        _state.update { st ->
            st.copy(agentWorkspaces = st.agentWorkspaces + mapOf(
                WorkspaceKey(self, agentId, "ws-$agentId") to WorkspaceUi(
                    peerId = self, agentKey = agentId, agentId = agentId,
                    wsId = "ws-$agentId", wsTitle = null, wsPath = null,
                    agentKind = null, online = null, sessionCount = null, at = 0L,
                ),
            ))
        }
    }

    @Test
    fun mintIgnoresNonEmptyLedgerButChecksDriverIsAvailable() {
        val c = controller(catalog = setOf("dsh"))
        c.seedLedger("some-other-agent") // 台账非空，但没有 dsh —— REGISTER 会拒，MINT 不该拒
        val result = c.applyEmployeeProfile(
            // A1 形状：MINT 的 agentId 为空串（还没派过单），配置在 driverKey 字段里。
            create(EmployeeCreationSource.MINT, "emp-mint-1", agentId = "", driverKey = "dsh"),
        )
        assertTrue(result is EmployeeProfileResult.Applied, "MINT 不看台账：非空且不含 dsh 也能建：$result")
        val profile = c.state.value.settings.employeeProfiles.single()
        assertEquals("dsh", profile.driverKey, "MINT 的底座落在显式字段里")
        assertEquals("", profile.agentId, "MINT 建档 agentId 为空（A1），首次成功派单后才回填")
        assertNull(profile.model, "model 缺省跟随驱动默认")
    }

    @Test
    fun mintRejectsAnUnavailableDriver() {
        val c = controller(catalog = setOf("codex")) // dsh 不在位
        val result = c.applyEmployeeProfile(
            create(EmployeeCreationSource.MINT, "emp-mint-2", agentId = "", driverKey = "dsh"),
        )
        assertEquals(
            DeclarationRejection.DRIVER_UNAVAILABLE,
            (result as? EmployeeProfileResult.Rejected)?.reason,
            "MINT 底座不在位必须拒，防建出派不出去的员工",
        )
        assertEquals(0, c.state.value.settings.employeeProfiles.size, "失败不留半份档案")
    }

    @Test
    fun registerExistingStillRejectsGhostAgent() {
        val c = controller() // 无 catalog：REGISTER 不看 catalog
        c.seedLedger("codex") // 台账非空且不含 dsh
        val result = c.applyEmployeeProfile(create(EmployeeCreationSource.REGISTER_EXISTING, "emp-reg-1"))
        assertEquals(
            DeclarationRejection.EMPLOYEE_UNKNOWN,
            (result as? EmployeeProfileResult.Rejected)?.reason,
            "REGISTER_EXISTING 在非空台账里找不到 agentId 仍是幽灵",
        )
        assertEquals(0, c.state.value.settings.employeeProfiles.size, "失败不留半份档案")
    }

    @Test
    fun registerExistingAcceptsAgentInNonEmptyLedger() {
        val c = controller()
        c.seedLedger("dsh") // 台账非空且含 dsh
        val result = c.applyEmployeeProfile(create(EmployeeCreationSource.REGISTER_EXISTING, "emp-reg-2"))
        assertTrue(result is EmployeeProfileResult.Applied, "登记台账里已有的 agent 应成功：$result")
    }

    @Test
    // 曾以 @Ignore 钉住「同底座两人同群顶席位」的架构缺口（席位键只认 (host, agentId)）。
    // A4 修复落地：席位归属带 employeeId（planAttachEmployee 先认引用、planJoinSeat
    // 只替换自己的/未认领的同键席位），本条解封为回归守卫。
    fun twoMintEmployeesOnSameDriverKeepTheirOwnSeatsInOneGroup() {
        // 三层模型：同一底座可以建两个员工。两人都挂进同一个群时，各自席位应带各自的 employeeId，
        // 不能让第二个人把第一个人的席位引用顶掉（seatAgentIdFor 的 sameAgent 兜底风险）。
        val c = controller(catalog = setOf("dsh"))
        val group = GroupMeta.create(
            "g-shared", "共用群", members = listOf(self), selfId = self, updatedTs = 1L,
        )
        val s = c.state.value
        c.handlePersist(s.settings.copy(groups = listOf(group)))

        val first = c.applyEmployeeProfile(
            create(EmployeeCreationSource.MINT, "emp-a", agentId = "", driverKey = "dsh")
                .copy(groupIds = listOf("g-shared")),
        )
        val second = c.applyEmployeeProfile(
            create(EmployeeCreationSource.MINT, "emp-b", agentId = "", driverKey = "dsh")
                .copy(groupIds = listOf("g-shared")),
        )
        assertTrue(first is EmployeeProfileResult.Applied, "第一个应建成：$first")
        assertTrue(second is EmployeeProfileResult.Applied, "同底座第二个也应建成：$second")

        val seats = c.state.value.settings.groups.first { it.groupId == "g-shared" }
            .virtualAgents.orEmpty().filter { it.hostNodeId == self }
        val refs = seats.mapNotNull { it.employeeId }.toSet()
        assertEquals(
            setOf("emp-a", "emp-b"),
            refs,
            "同底座两人在同一群应各占一条带自己 employeeId 的席位；现状 second 的 sameAgent 兜底会把 emp-a 顶成 emp-b",
        )
    }

    /**
     * A6（方案 §7）：进程上报**不同实例键**后，档案 `employeeId` / `driverKey` / `agentId`
     * 一个字都不变 —— 洞 A 不许复发（hex 是路由线索，不是身份锚）。
     * 现状没有回填写路径（回填是首次成功派单后的宿主侧可选动作，且只准动 agentId），
     * 这里把「会话上报不碰档案」钉成守卫：谁在会话登记路径里写 employeeProfiles，本条就红。
     */
    @Test
    fun aSessionReportingADifferentInstanceKeyLeavesTheProfileUntouched() {
        val c = controller(catalog = setOf("dsh"))
        assertTrue(
            c.applyEmployeeProfile(create(EmployeeCreationSource.MINT, "emp-a6", agentId = "", driverKey = "dsh"))
                is EmployeeProfileResult.Applied,
        )
        val before = c.state.value.settings.employeeProfiles.single()

        c.recordLocalSession(sessionId = "s-a6", agentKind = "dsh", agentId = "dsh-fedcb987", state = "busy")

        val after = c.state.value.settings.employeeProfiles.single()
        assertEquals(before, after, "会话上报不同实例键（dsh-fedcb987）不得改动档案的任何字段")
    }

    /**
     * A7（方案 §7）：老数据兼容 —— REGISTER_EXISTING 没给 `driverKey` 时由实例键反解一次；
     * 反解不出落空串（派单期如实报 NO_DRIVER），**绝不猜**。
     */
    @Test
    fun registerExistingBackfillsDriverKeyFromTheInstanceKey() {
        val c = controller(catalog = setOf("dsh"))
        c.driverKeyResolveOf = { raw -> if (raw.startsWith("dsh")) "dsh" else null }
        c.seedLedger("dsh-029bbbf3") // 台账非空且含该实例
        val result = c.applyEmployeeProfile(
            create(EmployeeCreationSource.REGISTER_EXISTING, "emp-a7", agentId = "dsh-029bbbf3"),
        )
        assertTrue(result is EmployeeProfileResult.Applied, "登记台账里已有的实例应成功：$result")
        assertEquals("dsh", c.state.value.settings.employeeProfiles.single().driverKey, "实例键反解出的底座要落档")
    }

    @Test
    fun registerExistingWithoutAResolvableDriverLeavesItBlank() {
        val c = controller(catalog = setOf("dsh"))
        // 不装 driverKeyResolveOf（手机/老装配形态）：反解不了 ⇒ 档案落空串，不猜。
        c.seedLedger("dsh-029bbbf3")
        val result = c.applyEmployeeProfile(
            create(EmployeeCreationSource.REGISTER_EXISTING, "emp-a7b", agentId = "dsh-029bbbf3"),
        )
        assertTrue(result is EmployeeProfileResult.Applied, "反解不出不该拦建档（A7 只反解一次，不猜）")
        assertEquals("", c.state.value.settings.employeeProfiles.single().driverKey, "解不出落空串，派单期如实拒绝")
    }
}
