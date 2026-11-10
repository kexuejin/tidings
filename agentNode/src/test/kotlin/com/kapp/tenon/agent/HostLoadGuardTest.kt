// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

import com.kapp.tenon.core.protocol.Limits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.cancel
import kotlin.test.assertTrue
import com.kapp.tenon.agent.udpTestTransport

/** T-G7 自保门的判定纯函数 + 真探针的形状（负载值本身不进断言 —— 机器相关）。 */
class HostLoadGuardTest {

    @Test
    fun refusesOnlyAboveThresholdAndFailsOpenOnProbeMiss() {
        assertTrue(HostLoadGuard.shouldRefuse(0.99), "超阈值 = 拒")
        assertTrue(HostLoadGuard.shouldRefuse(Limits.HOST_CPU_REJECT + 0.01), "阈值上 = 拒")
        assertFalse(HostLoadGuard.shouldRefuse(0.5), "阈值下 = 接")
        assertFalse(HostLoadGuard.shouldRefuse(Limits.HOST_CPU_REJECT), "恰在阈值 = 接（严格大于）")
        assertFalse(HostLoadGuard.shouldRefuse(null), "探针失灵 = 不拒（fail-open 于探针）")
    }

    @Test
    fun realProbeReturnsAPlausibleShapeOrNull() {
        // 不断言具体数值（本机相关）；只断言形状：null 或 0..1。
        val load = HostLoadGuard.systemCpuLoad()
        if (load != null) {
            assertTrue(load in 0.0..1.0, "CPU 负载形状 0..1，实际 $load")
        }
    }

    @Test
    fun controlPlaneRefusesPolitelyWhenProbeSaysOverloaded() = kotlinx.coroutines.runBlocking {
        // ControlPlane 的拒单门：注入 0.99 探针 ⇒ executePluginTask 首句如实回因，
        // **执行器没被碰**（不许先开工再喊累）。
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val agent = com.kapp.tenon.core.session.NodeRuntime(
            messengerTransportFactory = udpTestTransport(),
            profile = com.kapp.tenon.core.protocol.Profile.mobile(
                nodeId = com.kapp.tenon.core.protocol.newId(), nick = "宿主", host = "h", ver = "relay-0.1.0",
            ),
            udpPort = 0, bindHost = "127.0.0.1",
        )
        agent.start(scope)
        val registry = AgentSessionRegistry()
        val plane = ControlPlane(
            agent, httpPort = 0, token = "", sessions = registry,
            loadProbe = { 0.99 },
        )
        val entry = com.kapp.tenon.agent.plugins.PluginRegistry.Entry(
            manifest = com.kapp.tenon.agent.plugins.PluginManifest(
                pluginId = "worker", name = "worker",
                leader = com.kapp.tenon.core.protocol.TeamMemberInfo(agentId = "w1", name = "worker", role = "leader"),
            ),
            baseUrl = "http://127.0.0.1:1", httpPort = 1, agentSenderIds = emptyList(), registeredAt = 0,
        )
        var executed = false
        val (sessionId, status, error) = plane.executePluginTask(
            entry, "do things", null, null,
        ) { _, _, _, _ -> executed = true }
        assertTrue(sessionId == null && status == "error", "过载 = 开会话之前就拒")
        assertTrue(error!!.contains("overloaded"), "拒单信息如实（$error）")
        assertTrue(!executed, "事件流一条都没有 —— 执行器没被碰")
        plane.stop()
        agent.stop()
        scope.cancel()
        assertEquals(Unit, Unit)
    }
}
