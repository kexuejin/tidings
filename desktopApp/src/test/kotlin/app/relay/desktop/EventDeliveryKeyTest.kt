// SPDX-License-Identifier: GPL-3.0-only

package app.relay.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * 钉住 [eventDeliveryKey]：**带 nodeId**（task-60 中段通读修）。
 *
 * 修之前键是 sessionId:cursor —— 少了身份维度。AgentSessionRegistry 的 KDoc 明写
 * 「cursor 只在同一个 (node, session) 内单调，去重键是 (nodeId, sessionId, cursor)」；
 * 少了 nodeId 时，两台设备同名 sessionId + 同游标会让后到那条被当重复丢掉（静默丢内容）。
 *
 * 把键改回不含 nodeId，第二条断言会立刻红。
 */
class EventDeliveryKeyTest {

    @Test
    fun theSameEventFromTheSameNodeKeepsTheSameKey() {
        assertEquals(
            eventDeliveryKey("node-a", "s-1", 42L),
            eventDeliveryKey("node-a", "s-1", 42L),
            "同一条事件的重复投递必须同键（这才叫去重）",
        )
    }

    @Test
    fun theSameSessionAndCursorOnAnotherNodeIsNotADuplicate() {
        assertNotEquals(
            eventDeliveryKey("node-a", "s-1", 42L),
            eventDeliveryKey("node-b", "s-1", 42L),
            "两台设备的同名会话 + 同游标不是重复：少了 nodeId 就会把后到那条丢掉",
        )
    }

    @Test
    fun aDifferentCursorIsADifferentEvent() {
        assertNotEquals(eventDeliveryKey("node-a", "s-1", 42L), eventDeliveryKey("node-a", "s-1", 43L))
    }
}
