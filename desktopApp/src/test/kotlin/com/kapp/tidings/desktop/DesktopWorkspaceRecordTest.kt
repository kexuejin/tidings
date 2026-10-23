// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import com.kapp.tidings.agent.AgentWorkspaceRegistry
import com.kapp.tidings.domain.InMemorySettingsStore
import com.kapp.tidings.domain.RelayController
import com.kapp.tidings.platform.InMemoryAppPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * DesktopExtension 的工作区台账 → RelayController 的透传。
 *
 * 这一层是 order 真机缺陷的**最后一跳**：AgentWorkspaceRegistry 记了 order 之后，
 * recordLocalWorkspaceFrom 必须把它交给 controller.recordLocalWorkspace(order = ...) ——
 * 漏掉这一个具名实参，桌面库里的 host_order 就整列 NULL（真机 15/15），
 * 而手机库同一批工作区有 0/1/2/3/5/6/7。
 *
 * 用 InMemoryAppPlatform + InMemorySettingsStore 起一个真控制器（不起节点、不碰网络）：
 * 这条链路的正确性只在"状态里到底存了什么"上，不需要真的绑定端口。
 */
class DesktopWorkspaceRecordTest {

    @Test
    fun recordLocalWorkspaceFromPassesHostOrderThrough() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = RelayController(
                platform = InMemoryAppPlatform(),
                store = InMemorySettingsStore(),
                scope = scope,
            )
            val rec = AgentWorkspaceRegistry.WorkspaceRecord(
                nodeId = controller.state.value.selfNodeId,
                agentKey = "dsh",
                wsId = "ws-order-1",
                wsTitle = "teahouse-mobile",
                wsPath = "/workspace/src/teahouse-mobile",
                agentKind = "dsh",
                online = true,
                sessionCount = 2L,
                order = 7L,
                updatedAt = 1_000L,
            )

            recordLocalWorkspaceFrom(controller, rec)

            val ws = controller.state.value.agentWorkspaces.values.single()
            assertEquals("ws-order-1", ws.wsId)
            assertEquals(7L, ws.hostOrder, "order 必须在桌面端这一层透传（真机缺陷：host_order 15/15 NULL）")
            assertEquals(2L, ws.sessionCount)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun recordLocalWorkspaceFromKeepsNullOrderAsNull() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = RelayController(
                platform = InMemoryAppPlatform(),
                store = InMemorySettingsStore(),
                scope = scope,
            )
            val rec = AgentWorkspaceRegistry.WorkspaceRecord(
                nodeId = controller.state.value.selfNodeId,
                agentKey = "dsh",
                wsId = "ws-order-2",
                wsTitle = null,
                wsPath = null,
                agentKind = "dsh",
                online = null,
                sessionCount = null,
                order = null,
                updatedAt = 2_000L,
            )

            recordLocalWorkspaceFrom(controller, rec)

            assertEquals(
                null,
                controller.state.value.agentWorkspaces.values.single().hostOrder,
                "对端没报 order 时是 null，不许补一个 0 —— 0 是一个真实存在的顺序",
            )
        } finally {
            scope.cancel()
        }
    }
}
