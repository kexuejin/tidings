// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.core.protocol.AgentExecPolicies
import com.kapp.tenon.core.protocol.ConvId
import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.core.protocol.VirtualAgentMember
import com.kapp.tenon.domain.EmployeeProfile
import com.kapp.tenon.domain.InMemoryAgentStore
import com.kapp.tenon.domain.InMemoryMessageStore
import com.kapp.tenon.domain.InMemorySettingsStore
import com.kapp.tenon.domain.NoUiTexts
import com.kapp.tenon.domain.RelayController
import com.kapp.tenon.domain.RelaySettings
import com.kapp.tenon.domain.RelayUiTexts
import com.kapp.tenon.platform.InMemoryAppPlatform
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * A5 的**行为**探针（方案 §7）：派单取值顺序 = 执行档 > 员工档案 > 显式点名 > **拒绝**，
 * `?: "codex"` 兜底已删除（不变量 1「不猜底座」）。
 *
 * 三条用例各钉一段：
 * 1. **裸 kind 点名**（`@dsh`）：归一后的驱动键进派单 —— 卡面挂真名，不是 Codex；
 *    旧实现里 `resolveKey` 解析不出时传 null、落到 `?: "codex"`，本条在旧代码上红。
 * 2. **员工档案供底座**：席位带 `employeeId` 引用 → 档案 `driverKey` 优先于键反解 ——
 *    「配置是档案里的显式字段」的全部意义。
 * 3. **三者皆无 ⇒ 拒绝**：来历不明的点名不透传给 executeCliTask（旧代码 `?: raw` 透传），
 *    群里落 blocked 卡并说明，不静默、不猜。
 *
 * 控制面都不可用（planeProvider = null），派单在「起飞前」的解析段就能被观察 ——
 * 不需要真 CLI，也不赌任何时序（Unconfined 让 launch 内联跑完）。
 */
class DispatchDriverResolutionTest {

    /** 只给「派单失败」一句非空正文（与本仓既有行为探针同一装置纪律）。 */
    private object Texts : RelayUiTexts by NoUiTexts {
        override suspend fun dispatchFailedNotice(title: String, agentName: String, reason: String): String =
            "dispatch failed: $title / $agentName / $reason"
    }

    private fun harness(seat: VirtualAgentMember? = null, profile: EmployeeProfile? = null):
        Pair<RelayController, DesktopWorkerOrchestrator> {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        // nodeId 固定：席位/档案要预先写进 settings（orchestration 侧没有 :app 的
        // handlePersist 可用 —— 预置 store 是唯一与本仓装配一致的种法）。
        val self = "res-node-0000-4000-8000-0000000000"
        val group = GroupMeta.create(
            "g-res", "解析群", members = listOf(self, "peer-x"), selfId = self, updatedTs = 1L,
        )
        val withSeat = if (seat != null) group.copy(virtualAgents = listOf(seat.copy(hostNodeId = self))) else group
        val settings = RelaySettings.firstRun().copy(
            nodeId = self,
            udpPort = 0, tcpPort = 0,
            persistMessages = false, persistMessagesDecided = true,
            groups = listOf(if (seat != null || profile != null) withSeat else group),
            employeeProfiles = listOfNotNull(profile),
        )
        val controller = RelayController(
            platform = InMemoryAppPlatform(),
            store = InMemorySettingsStore(settings),
            messageStore = InMemoryMessageStore(),
            agentStore = InMemoryAgentStore(),
            scope = scope,
        )
        controller.uiTexts = Texts
        // 席位派单授权放行（本用例的被测对象是底座解析，不是授权门）。
        controller.invokeExecPolicyOf = { AgentExecPolicies.ANY_MEMBER }
        return controller to DesktopWorkerOrchestrator(
            planeProvider = { null },
            controller = controller,
            scope = scope,
        )
    }

    private suspend fun dispatch(
        orchestrator: DesktopWorkerOrchestrator,
        controller: RelayController,
        mention: String?,
    ): List<String> {
        val groupId = controller.state.value.settings.groups.first().groupId
        val convId = ConvId.group(groupId)
        orchestrator.handlePrompt(
            convId = convId,
            senderPeerId = controller.state.value.selfNodeId,
            prompt = "create probe.txt",
            replyToId = null,
            wsId = null,
            mentionedAgentKey = mention,
        )
        val rows = controller.state.value.messages[convId].orEmpty()
        return rows.map { "state=${it.agentState} text=${it.text} card=${it.card ?: ""}" }
    }

    @Test
    fun aKindMentionDispatchesItsOwnDriverNotADefault() = runBlocking {
        val (controller, orchestrator) = harness()
        val rows = dispatch(orchestrator, controller, mention = "dsh")
        val failed = rows.firstOrNull { it.startsWith("state=failed") }
        assertTrue(failed != null, "显式点名的工单句必须走到派单出口（失败也要可见）：$rows")
        assertTrue(
            failed.contains("Dsh"),
            "卡面必须挂点名解析出的真名（dsh → Dsh）；实际：$failed",
        )
        assertTrue(
            !failed.contains("Codex"),
            "`?: \"codex\"` 兜底已删除 —— 解析结果不得冒充 Codex：$failed",
        )
    }

    @Test
    fun theSeatEmployeeProfileSuppliesTheDriver() = runBlocking {
        val (controller, orchestrator) = harness(
            seat = VirtualAgentMember(
                agentId = "dsh", hostNodeId = "res-node-0000-4000-8000-0000000000",
                name = "coder", employeeId = "emp-x", employeeName = "员工X",
            ),
            profile = EmployeeProfile(
                employeeId = "emp-x", agentId = "", name = "员工X", token = "coder",
                driverKey = "claude", model = "claude-sonnet-4",
            ),
        )
        val rows = dispatch(orchestrator, controller, mention = "coder")
        val failed = rows.firstOrNull { it.startsWith("state=failed") }
        assertTrue(failed != null, "带档案的席位 @ 必须走到派单出口：$rows")
        assertTrue(
            failed.contains("Claude"),
            "员工档案的 driverKey 必须优先于键反解（执行档 > 员工档案 > 拒绝）；实际：$failed",
        )
    }

    @Test
    fun anUnresolvableMentionIsRefusedNotDefaulted() = runBlocking {
        val (controller, orchestrator) = harness()
        val rows = dispatch(orchestrator, controller, mention = "ghost-x")
        assertTrue(
            rows.any { it.contains("no driver configured") },
            "三者皆无的点名必须拒绝并说明（blocked 卡），绝不透传给 executeCliTask、绝不回落默认驱动：$rows",
        )
        assertTrue(
            rows.none { it.startsWith("state=failed") },
            "拒绝发生在解析段 —— 不该再出现「已起飞后失败」的工单行：$rows",
        )
    }
}
