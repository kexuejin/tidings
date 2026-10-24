// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.domain

import com.kapp.tidings.domain.model.GrantUi
import com.kapp.tidings.domain.model.WorkspaceUi
import com.kapp.tidings.core.nowMillis
import com.kapp.tidings.core.protocol.Codec
import com.kapp.tidings.core.protocol.Profile
import com.kapp.tidings.core.protocol.makeEnvelope
import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.session.NodeRuntime
import com.kapp.tidings.platform.InMemoryAppPlatform
import com.kapp.tidings.testutil.awaitUntil
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

    /**
     * 让本端在"我能不能批这个 agent 的请求"这件事上有权：
     * 注入一条宿主发来的 `agent-grant`（scope=all + OWNER）。
     *
     * 为什么夹具必须先做这一步：审批权判据现在只有一份（:core 的 canApproveAgentRequest），
     * 客户端没有授权记录时**就是只读** —— 不给授权，下面任何"点了批准"的断言都走不到发送那一步。
     */
    private fun grantOwner(controller: RelayController, from: String = "agent-node-1") {
        controller.messagesCtl.handleOnInbound(
            makeEnvelope(
                type = MsgTypes.AGENT_GRANT,
                id = "env-grant-owner",
                from = from,
                ts = nowMillis(),
                payload = Codec.agentGrantPayload(state = "approved", scope = "all", access = "owner"),
            ),
        )
        assertNotNull(
            controller.state.value.agentGrants.values.firstOrNull(),
            "夹具前提：宿主已把 OWNER 授权发给本端",
        )
    }

    @Test
    fun testInboundAgentApprovalAndResponse() {
        val controller = controller()
        grantOwner(controller)
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

        // 没有运行时时处置审批：决定送不出去（连离线队列都不存在），
        // 本地**不得**翻成「已批准」假装成功 —— 必须退回待审批并如实提示。
        // （旧行为是直接翻 approved：对端永远不知道的假账，正是「发送成功≠对方收到」。）
        controller.respondApproval("appr-123", true)
        assertEquals("pending", controller.state.value.agentApprovals["appr-123"]?.status)
        assertEquals(
            BannerMessage.AgentAckSendFailed,
            controller.state.value.banner?.message,
            "无运行时时必须出现「审批结果没有送出」横幅",
        )

        // Inbound ACK update（对端那边的独立事实照常推进本地状态）
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

    /**
     * 对端不声明审批回执（`agq1`，[MsgTypes.CAP_AGENT_QUEUE]）时：ack 无处可发是已知事实，
     * 本地**仍然翻状态**（决定是本端产生的），但必须横幅说明「结果以对方 Agent 实际执行为准」。
     * 运行时存在但对端不可知（registry 为空）⇒ peerDeclares 恒 false，正好钉住这一分支。
     */
    @Test
    fun testRespondApprovalWithoutQueueCapFlipsStateWithBanner() {
        val controller = controller()
        // 客户端预检已 fail-closed：没有授权记录就是只读。这条测的是"发送/回执"分支，
        // 所以先把审批权夹具摆好（否则 respondApproval 在预检就返回，走不到横幅）。
        grantOwner(controller)
        val envelope = makeEnvelope(
            type = MsgTypes.AGENT_APPROVAL,
            id = "env-appr-2",
            from = "agent-node-1",
            ts = nowMillis(),
            payload = Codec.agentApprovalPayload(
                approvalId = "appr-no-cap",
                sessionId = "sess-1",
                tool = "bash",
                summary = "rm -rf /tmp/cache",
                expiresAt = nowMillis() + 60_000L,
            ),
        )
        controller.messagesCtl.handleOnInbound(envelope)

        // 运行时存在、但没有它的任何对端记录 ⇒ 不声明 agq1（也不必起真实 socket）。
        controller.runtime = NodeRuntime(
            profile = Profile.mobile(
                nodeId = "11111111-0000-4000-8000-000000009999",
                nick = "测试本机",
                host = "127.0.0.1",
                ver = "relay-test",
            ),
            udpPort = 0,
        )

        controller.respondApproval("appr-no-cap", true)
        assertEquals(
            "approved",
            controller.state.value.agentApprovals["appr-no-cap"]?.status,
            "决定本身是本端产生的：无回执也要记账",
        )
        assertEquals(BannerMessage.AgentAckUnconfirmed, controller.state.value.banner?.message)
    }

    /**
     * **无权处置 ⇒ 三件事都没发生**：没发 ack、状态没翻、也没有任何横幅。
     *
     * 为什么三件都要断：只断"状态没翻"分不清它是被预检挡住，还是翻转后又被回滚了；
     * 只断"没横幅"更弱 —— 真正要防的是**谎报成功**（界面显示已批准，宿主根本没收到）。
     * 那条"没送出"的横幅在这里也必须是**没有**：它的语义是"你批了但送不出去"，
     * 而这里的处境是"你根本无权批"，拿它充数同样是误导。
     */
    @Test
    fun testRespondApprovalWithoutAuthorityChangesNothing() {
        val controller = controller()
        val acked = mutableListOf<String>()
        controller.agentCtl.ackSenderForTest = { _, ackOf, _ -> acked += ackOf }
        val envelope = makeEnvelope(
            type = MsgTypes.AGENT_APPROVAL,
            id = "env-appr-noauth",
            from = "agent-node-1",
            ts = nowMillis(),
            payload = Codec.agentApprovalPayload(
                approvalId = "appr-noauth",
                sessionId = "sess-1",
                tool = "bash",
                summary = "rm -rf /",
                expiresAt = nowMillis() + 60_000L,
            ),
        )
        controller.messagesCtl.handleOnInbound(envelope)
        // 运行时也在：用来证明"没发 ack"不是"没有运行时可发"。
        controller.runtime = NodeRuntime(
            profile = Profile.mobile(
                nodeId = "11111111-0000-4000-8000-000000009998",
                nick = "测试本机",
                host = "127.0.0.1",
                ver = "relay-test",
            ),
            udpPort = 0,
        )
        assertEquals("pending", controller.state.value.agentApprovals["appr-noauth"]?.status)

        controller.respondApproval("appr-noauth", true)

        assertEquals("pending", controller.state.value.agentApprovals["appr-noauth"]?.status, "状态不许翻（翻了就是假账）")
        assertEquals(0, acked.size, "无权时不许发 agent-ack（宿主也会拒，但不该先发出去）")
        assertNull(controller.state.value.banner, "更不许拿「没送出」的横幅冒充失败原因")
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
        // 恢复走 scope.launch：轮询等两样都落地再断言。过去是 "让出一拍"（delay(50)）——
        // 慢机器上那一拍不够，断言会随机红；这里是等条件，不是等时间（助手是模块共享那份）。
        runBlocking {
            awaitUntil("工作区与授权都从落盘恢复") {
                controller.state.value.agentWorkspaces.isNotEmpty() &&
                    controller.state.value.agentGrants.isNotEmpty()
            }
        }
 
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

