// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.domain.RelaySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentQuotaGateDailyResetTest {

    @Test
    fun externalPrivateChatBlockedWhenSettingDisabled() {
        val gate = AgentQuotaGate(limitPerDay = 10)
        val settings = RelaySettings(nodeId = "self", allowExternalPrivateChat = false)
        val convId = "agent:node-external-1/assistant"

        val allowed = gate.checkAndConsume(convId, settings, currentEpochDay = 100L)
        assertFalse("未开启 allowExternalPrivateChat 时必须一律拦截外部单聊", allowed)
        assertEquals(0, gate.getUsedQuota(convId, currentEpochDay = 100L))
    }

    @Test
    fun dailyQuotaResetsAcrossEpochDays() {
        val gate = AgentQuotaGate(limitPerDay = 10)
        val settings = RelaySettings(nodeId = "self", allowExternalPrivateChat = true)
        val convId = "agent:node-external-1/assistant"

        // Day 100: consume 10 times
        for (i in 1..10) {
            val allowed = gate.checkAndConsume(convId, settings, currentEpochDay = 100L)
            assertTrue("第 $i 次在配额内必须放行", allowed)
        }

        // 11th time on Day 100 should be rejected
        val overLimit = gate.checkAndConsume(convId, settings, currentEpochDay = 100L)
        assertFalse("当天第 11 次必须超限拒止", overLimit)
        assertEquals(10, gate.getUsedQuota(convId, currentEpochDay = 100L))

        // Day 101: automatically reset
        val nextDayFirst = gate.checkAndConsume(convId, settings, currentEpochDay = 101L)
        assertTrue("跨天到 101 后必须自动重置并放行", nextDayFirst)
        assertEquals(1, gate.getUsedQuota(convId, currentEpochDay = 101L))
    }

    @Test
    fun changingAgentIdFromSamePeerNodeDoesNotBypassQuota() {
        val gate = AgentQuotaGate(limitPerDay = 2)
        val settings = RelaySettings(nodeId = "self", allowExternalPrivateChat = true)

        // 对端 node-malicious 先呼叫 agent-alpha
        assertTrue(gate.checkAndConsume("agent:node-malicious/agent-alpha", settings, 200L))
        assertTrue(gate.checkAndConsume("agent:node-malicious/agent-alpha", settings, 200L))

        // 对端企图换成 agent-beta 绕过配额
        val bypassed = gate.checkAndConsume("agent:node-malicious/agent-beta", settings, 200L)
        assertFalse("对端节点变换 agentId 绝对不许绕过配额", bypassed)
    }

    @Test
    fun limitPerDayZeroBlocksAllRequests() {
        val gate = AgentQuotaGate(limitPerDay = 0)
        val settings = RelaySettings(nodeId = "self", allowExternalPrivateChat = true)

        val allowed = gate.checkAndConsume("agent:node-external-1/agent-1", settings, 100L)
        assertFalse("配额上限为 0 时首日必须直接拒止", allowed)
    }

    @Test
    fun nonExternalAgentConvIdDoesNotConsumeQuota() {
        val gate = AgentQuotaGate(limitPerDay = 10)
        val settings = RelaySettings(nodeId = "self", allowExternalPrivateChat = false)

        val allowed = gate.checkAndConsume("conv-local-1", settings, currentEpochDay = 100L)
        assertTrue("普通非外部 Agent 会话必须直接放行且不占用配额", allowed)
    }
}
