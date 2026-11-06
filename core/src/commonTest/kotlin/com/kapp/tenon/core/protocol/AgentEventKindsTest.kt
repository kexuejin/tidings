// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** T-G3：瞬态事件判定的唯一入口（DB 过滤 / UI 过滤 / 实时管道共用一份语义）。 */
class AgentEventKindsTest {

    @Test
    fun deltasAreEphemeralAndEverythingElsePersists() {
        assertTrue(!AgentEventKinds.shouldPersist("token.delta"))
        assertTrue(!AgentEventKinds.shouldPersist("thought.delta"))
        assertTrue(AgentEventKinds.shouldPersist("finished"), "终局事实留台账")
        assertTrue(AgentEventKinds.shouldPersist("card"), "卡片留台账")
        assertTrue(AgentEventKinds.shouldPersist("tool.started"), "工具事实留台账")
        assertEquals(setOf("token.delta", "thought.delta"), AgentEventKinds.EPHEMERAL, "瞬态集合就这两类")
    }
}
