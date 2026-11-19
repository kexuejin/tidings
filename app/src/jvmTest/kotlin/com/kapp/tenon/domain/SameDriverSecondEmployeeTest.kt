// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.platform.InMemoryAppPlatform
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel

/**
 * 洞 E 探针：同一底座必须能建第二个员工（三层正交模型，方案 §2②）。
 *
 * 终案：身份锚是 `employeeId`（本机分配、天然唯一），`agentId` 只是路由线索。
 * 所以撞名检查必须按 `employeeId`，不能按 `agentId` 的 [sameAgent] 前缀归一 ——
 * 后者把同底座的第二个人判成 `EMPLOYEE_EXISTS`，也让两张同底座会话卡并进第一份档案。
 *
 * 这两条**用现有 API 构造、编译通过，只红在断言**：
 *  - [aSecondEmployeeOnTheSameDriverCanBeCreated]：现状 `createEmployee` 撞名检查用
 *    `sameAgent(档案.agentId, agentId)`，第二个 `dsh` 撞第一个 ⇒ Rejected。
 *  - [twoSameDriverCardsGoToTheirOwnProfiles]：现状 `employeeProfileOf` 用 `firstOrNull`
 *    + `sameAgent`，两张同底座卡都归第一份档案。
 *
 * 四段改造（employeeId 入帧 + 配对按 employeeId 精确）落地后，两条自动转绿。
 */
class SameDriverSecondEmployeeTest {

    private val scope = CoroutineScope(Job())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private val self = "abc12345-0000-4000-8000-000000000000"

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

    private fun create(employeeId: String, name: String, token: String) = EmployeeProfileCommand.Create(
        name = name,
        agentId = "dsh", // 同一个底座 —— 这是本探针的全部要点
        token = token,
        employeeId = employeeId,
        groupIds = emptyList(),
        actor = DeclarationActor.Human(self),
    )

    @Test
    fun aSecondEmployeeOnTheSameDriverCanBeCreated() {
        val controller = controller()
        val first = controller.applyEmployeeProfile(create("emp-a", "老王", "wang"))
        val second = controller.applyEmployeeProfile(create("emp-b", "老李", "li"))

        assertTrue(first is EmployeeProfileResult.Applied, "第一个员工应建成：$first")
        assertTrue(
            second is EmployeeProfileResult.Applied,
            "同底座 dsh 建第二个员工必须成功；现状撞名检查用 sameAgent 抹平 head，会回 EMPLOYEE_EXISTS：$second",
        )
        assertEquals(2, controller.state.value.settings.employeeProfiles.size, "两个员工是两份独立档案")
    }

    @Test
    fun twoSameDriverCardsGoToTheirOwnProfiles() {
        val profiles = listOf(
            AgentEmployeeProfileUi(
                employeeId = "emp-a", agentId = "dsh", name = "老王", token = "wang",
                duty = "coder", groupNames = emptyList(),
            ),
            AgentEmployeeProfileUi(
                employeeId = "emp-b", agentId = "dsh", name = "老李", token = "li",
                duty = "coder", groupNames = emptyList(),
            ),
        )
        val cardA = card(agentKey = "dsh", employeeId = "emp-a")
        val cardB = card(agentKey = "dsh", employeeId = "emp-b")

        assertEquals("emp-a", employeeProfileOf(cardA, profiles, self)?.employeeId)
        assertEquals(
            "emp-b",
            employeeProfileOf(cardB, profiles, self)?.employeeId,
            "两张同底座卡必须各归各档；现状 firstOrNull + sameAgent 把两张都归第一份 emp-a",
        )
    }

    /** 卡由 [AgentHubUi] 现成字段直接构造（employeeId 字段已存在，终案只是把它的来源从反查改成透传）。 */
    private fun card(agentKey: String?, employeeId: String?) = AgentHubUi(
        hostNodeId = self,
        hostName = "本机",
        agentKey = agentKey,
        displayName = agentKey,
        employeeId = employeeId,
        employeeName = null,
        isTeam = false,
        teamSize = 0,
        groupCount = 0,
        workspaceCount = 0,
        online = true,
        pendingCount = 0,
        rows = emptyList(),
        primaryWsId = null,
        primaryWsTitle = null,
        lastActivityAt = 0,
    )
}
