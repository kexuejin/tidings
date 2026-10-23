// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.domain

import com.kapp.tidings.domain.model.GrantUi
import com.kapp.tidings.domain.model.WorkspaceUi
import com.kapp.tidings.core.nowMillis
import com.kapp.tidings.core.protocol.Codec
import com.kapp.tidings.core.protocol.makeEnvelope
import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.platform.InMemoryAppPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RelayControllerAgentP1Test {
    private val scope = CoroutineScope(Job())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun controller() = RelayController(
        platform = InMemoryAppPlatform(),
        store = InMemorySettingsStore(),
        scope = scope,
    )

    @Test
    fun testInboundAgentApprovalAndResponse() {
        val controller = controller()
        val envelope = makeEnvelope(
            type = MsgTypes.AGENT_APPROVAL,
            id = "env-appr-1",
            from = "agent-node-1",
            ts = nowMillis(),
            payload = Codec.agentApprovalPayload(
                approvalId = "appr-123",
                sessionId = "sess-456",
                tool = "run_command",
                summary = "Executing bash script",
                expiresAt = nowMillis() + 60_000L,
            ),
        )

        controller.messagesCtl.handleOnInbound(envelope)

        val approval = controller.state.value.agentApprovals["appr-123"]
        assertNotNull(approval)
        assertEquals("run_command", approval.tool)
        assertEquals("Executing bash script", approval.summary)
        assertEquals("pending", approval.status)

        // Respond with approval
        controller.respondApproval("appr-123", true)
        assertEquals("approved", controller.state.value.agentApprovals["appr-123"]?.status)

        // Inbound ACK update
        val ackEnvelope = makeEnvelope(
            type = MsgTypes.AGENT_ACK,
            id = "env-ack-1",
            from = "agent-node-1",
            ts = nowMillis(),
            payload = Codec.agentAckPayload(
                ackOf = "appr-123",
                kind = "denied",
            ),
        )
        controller.messagesCtl.handleOnInbound(ackEnvelope)
        assertEquals("denied", controller.state.value.agentApprovals["appr-123"]?.status)
    }

    @Test
    fun testInboundAgentQueueAndCancel() {
        val controller = controller()
        val addEnvelope = makeEnvelope(
            type = MsgTypes.AGENT_QUEUE,
            id = "env-q-1",
            from = "agent-node-1",
            ts = nowMillis(),
            payload = Codec.agentQueuePayload(
                taskId = "task-1",
                sessionId = "sess-1",
                op = "enqueue",
                prompt = "Do some work",
            ),
        )

        controller.messagesCtl.handleOnInbound(addEnvelope)

        val task = controller.state.value.agentTasks["task-1"]
        assertNotNull(task)
        assertEquals("enqueue", task.op)
        assertEquals("Do some work", task.prompt)

        val cancelEnvelope = makeEnvelope(
            type = MsgTypes.AGENT_QUEUE,
            id = "env-q-2",
            from = "agent-node-1",
            ts = nowMillis(),
            payload = Codec.agentQueuePayload(
                taskId = "task-1",
                op = "dequeue",
            ),
        )
        controller.messagesCtl.handleOnInbound(cancelEnvelope)
        assertNull(controller.state.value.agentTasks["task-1"])
    }

    /**
     * 恢复必须把**工作区与授权**也接上（2026-10-14 修的真缺口）。
     *
     * 为什么单独钉一条：\`handleRestoreAgentData()\` 是一串并列的 load，漏掉其中一两张表
     * 编译器不会说、界面也不会崩 —— 症状只在重启后出现（「列表空了 / 授权退回未申请」），
     * 而每次手工验证都要重启一次真机。真机观察到的原话：
     * 「目前看起来工作区列表没有缓存吗？每次都得等同步？」
     */
    @Test
    fun testRestoreAgentDataIncludesWorkspacesAndGrants() {
        val store = InMemoryAgentStore()
        store.saveWorkspace(
            WorkspaceUi(
                peerId = "agent-node-1",
                agentKey = "dsh-cli",
                agentId = "dsh-cli",
                wsId = "ws-test-1",
                wsTitle = "某项目",
                wsPath = "/tmp/proj",
                agentKind = "DSH",
                online = true,
                sessionCount = 0,
                at = nowMillis(),
            ),
        )
        store.saveGrant(
            GrantUi(
                peerId = "agent-node-1",
                agentKey = "dsh-cli",
                scope = "agent",
                state = "approved",
                at = nowMillis(),
            ),
        )
        val controller = RelayController(
            platform = InMemoryAppPlatform(),
            store = InMemorySettingsStore(),
            scope = scope,
            agentStore = store,
        )
 
        controller.agentCtl.handleRestoreAgentData()
        // 恢复走 scope.launch，让出一拍再断言。
        runBlocking { delay(50) }
 
        val state = controller.state.value
        assertEquals(1, state.agentWorkspaces.size, "工作区必须从落盘恢复，否则重启后要等宿主重发")
        assertEquals(1, state.agentGrants.size, "授权同样必须恢复")
    }

    /**
     * PPE-1 增补 D8：某工作区会话帧到达时必须摘除其对应的 sessionPageLoading 标记，
     * 确保「加载更多」按钮恢复为可交互状态。
     */
    @Test
    fun testAgentSessionClearsSessionPageLoading() {
        val controller = controller()
        val key = sessionPageKeyOf("agent-node-1", "ws-page-1")
        val otherKey = sessionPageKeyOf("agent-node-1", "ws-page-2")

        // 模拟已发出分页拉取请求
        controller._state.value = controller.state.value.copy(
            sessionPageLoading = setOf(key, otherKey),
        )
        assertEquals(2, controller.state.value.sessionPageLoading.size)

        // 收到匹配该 wsId 的会话帧
        val envelope = makeEnvelope(
            type = MsgTypes.AGENT_SESSION,
            id = "env-sess-1",
            from = "agent-node-1",
            ts = nowMillis(),
            payload = Codec.agentSessionPayload(
                sessionId = "sess-100",
                agentKind = "DSH",
                title = "Session Title",
                wsId = "ws-page-1",
            ),
        )
        controller.messagesCtl.handleOnInbound(envelope)

        val nextLoading = controller.state.value.sessionPageLoading
        assertEquals(false, key in nextLoading, "匹配工作区的 sessionPageLoading 标记必须被清除")
        assertEquals(true, otherKey in nextLoading, "未匹配工作区的加载状态必须被保留")
    }

    @Test
    fun testApprovalAndFailedRunTriggerLocalNotifications() {
        val notifications = mutableListOf<Triple<String, String, String?>>()
        val testHandler = object : com.kapp.tidings.platform.PushNotificationHandler {
            override fun showNotification(title: String, body: String, focusPeerId: String?) {
                notifications.add(Triple(title, body, focusPeerId))
            }
            override fun setOnNotificationTap(callback: ((focusPeerId: String) -> Unit)?) {}
            override suspend fun requestPermission(): Boolean = true
        }
        val platform = InMemoryAppPlatform(pushNotificationHandler = testHandler)
        val controller = RelayController(
            platform = platform,
            store = InMemorySettingsStore(),
            scope = scope,
        )

        // 收到待审批帧 -> 应触发本地通知
        val approvalEnv = makeEnvelope(
            type = MsgTypes.AGENT_APPROVAL,
            id = "env-appr-notif-1",
            from = "agent-node-x",
            ts = nowMillis(),
            payload = Codec.agentApprovalPayload(
                approvalId = "appr-n-1",
                sessionId = "sess-n-1",
                tool = "delete_file",
                summary = "Delete build directory",
                expiresAt = nowMillis() + 60_000L,
            ),
        )
        controller.messagesCtl.handleOnInbound(approvalEnv)

        assertEquals(1, notifications.size)
        assertEquals("agent-no", notifications[0].first) // take(8)
        assertEquals("delete_file: Delete build directory", notifications[0].second)

        // 重复收到相同 approvalId -> 幂等去重，不应再弹通知
        controller.messagesCtl.handleOnInbound(approvalEnv)
        assertEquals(1, notifications.size)

        // 收到 agent-run 失败帧 -> 应触发本地告警通知
        val failedRunEnv = makeEnvelope(
            type = MsgTypes.AGENT_RUN,
            id = "env-run-fail-1",
            from = "agent-node-x",
            ts = nowMillis(),
            payload = Codec.agentRunPayload(
                sessionId = "sess-n-1",
                runId = "run-f-1",
                state = "failed",
                note = "Compilation syntax error",
            ),
        )
        controller.messagesCtl.handleOnInbound(failedRunEnv)

        assertEquals(2, notifications.size)
        assertEquals("Compilation syntax error", notifications[1].second)

        // 重复收到相同 run-f-1 -> 幂等去重
        controller.messagesCtl.handleOnInbound(failedRunEnv)
        assertEquals(2, notifications.size)
    }
}

