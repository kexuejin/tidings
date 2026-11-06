// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 状态词表的钉子：EXT-3 收口后，任何一处改词表都先在这里显式变红。
 *
 * 断言刻意写死字面量 —— 引用被测常量去等于常量自己没有意义。
 * 5 / 2 / 4 分别对应 OfferDelivery.state 的 KDoc 取值域、agent-queue 的 op 取值域、
 * agent-run 的 state 取值域。
 */
class AgentStateWordsTest {

    @Test
    fun offerDeliveryStatesMatchKDocDomain() {
        assertEquals(
            setOf("offering", "offered", "queued-awaiting-reconnect", "gave-up", "ack-timeout"),
            OfferDeliveryStates.ALL,
        )
        assertEquals("offering", OfferDeliveryStates.OFFERING)
        assertEquals("offered", OfferDeliveryStates.OFFERED)
        assertEquals("queued-awaiting-reconnect", OfferDeliveryStates.QUEUED_AWAITING_RECONNECT)
        assertEquals("gave-up", OfferDeliveryStates.GAVE_UP)
        assertEquals("ack-timeout", OfferDeliveryStates.ACK_TIMEOUT)
    }

    @Test
    fun agentQueueOpsMatchDomain() {
        assertEquals(setOf("enqueue", "dequeue"), AgentQueueOps.ALL)
        assertEquals("enqueue", AgentQueueOps.ENQUEUE)
        assertEquals("dequeue", AgentQueueOps.DEQUEUE)
    }

    @Test
    fun agentRunStatesMatchDomain() {
        assertEquals(setOf("running", "succeeded", "failed", "cancelled"), AgentRunStates.ALL)
        assertEquals(setOf("succeeded", "failed", "cancelled"), AgentRunStates.TERMINAL)
        assertEquals("running", AgentRunStates.RUNNING)
        assertEquals("succeeded", AgentRunStates.SUCCEEDED)
        assertEquals("failed", AgentRunStates.FAILED)
        assertEquals("cancelled", AgentRunStates.CANCELLED)
    }

    @Test
    fun terminalPlusRunningIsAll() {
        assertEquals(AgentRunStates.ALL, AgentRunStates.TERMINAL + AgentRunStates.RUNNING)
        assertTrue(AgentRunStates.TERMINAL.all { it in AgentRunStates.ALL })
        assertTrue(AgentRunStates.RUNNING !in AgentRunStates.TERMINAL)
    }
}
