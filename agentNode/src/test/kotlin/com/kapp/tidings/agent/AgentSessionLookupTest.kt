// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 钉住带 nodeId 的严格查找（task-60 审查裁决）。
 *
 * 为什么必须带 nodeId：台账的键是 (nodeId, sessionId)。只按 sessionId 查时，两台设备若**同名**
 * （sessionId 是宿主生成的短串，撞名概率低但非零），查出来的是**另一台设备**的会话 —— 调用方
 * 拿它的 wsPath 去匹配群，甲仓库的活就会干进乙仓库的群。修之前这正是 DesktopExtension 里
 * 那条跨设备查找的形状。
 *
 * 这条测试同时钉住「查不认识的设备必须 null」：不许退化成「随便挑一条同名会话」。
 */
class AgentSessionLookupTest {

    @Test
    fun theStrictLookupSeparatesTwoDevicesWithTheSameSessionId() {
        val registry = AgentSessionRegistry()
        registry.recordSession(
            nodeId = "phone-a",
            sessionId = "s-1",
            wsId = "ws-a",
            wsPath = "/repo/a",
            outbound = false,
            at = 1L,
        )
        registry.recordSession(
            nodeId = "phone-b",
            sessionId = "s-1",
            wsId = "ws-b",
            wsPath = "/repo/b",
            outbound = false,
            at = 1L,
        )

        assertEquals("/repo/a", registry.findSession("phone-a", "s-1")?.wsPath)
        assertEquals("/repo/b", registry.findSession("phone-b", "s-1")?.wsPath)
        assertNull(
            registry.findSession("phone-c", "s-1"),
            "查不认识的设备必须 null：不许退化成「随便挑一条同名会话」",
        )
    }

    @Test
    fun theStrictLookupPrefersTheRecordThatCarriesAWorkspacePath() {
        val registry = AgentSessionRegistry()
        // 同一 (node, session) 先来一条不带 wsPath 的，再来一条带的 —— 查找要拿到更全的那条。
        registry.recordSession(nodeId = "host", sessionId = "s-2", outbound = false, at = 1L)
        registry.recordSession(
            nodeId = "host",
            sessionId = "s-2",
            wsPath = "/repo/full",
            outbound = false,
            at = 2L,
        )
        assertEquals("/repo/full", registry.findSession("host", "s-2")?.wsPath)
    }
}
