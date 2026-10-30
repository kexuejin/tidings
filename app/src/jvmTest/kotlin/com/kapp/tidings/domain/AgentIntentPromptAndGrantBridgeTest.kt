// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.kapp.tidings.platform.InMemoryAppPlatform

/**
 * @agent 意图回路的两个真机抓到的分叉（M4 收尾冒烟）：
 *
 * 1. **prompt 丢失**：意图帧只带目标（agentId/wsId/mode），任务话在消息正文里 ——
 *    第一版组件不收正文，插件收到的是**空串**：执行了、卡片也进了群，任务话丢了。
 *    修后：正文随调用进来，剥掉 @ token 的剩余部分就是 prompt（与 inbox 桥同一份
 *    [RelayController.extractAgentPrompt]，不另写一份剥离）。
 * 2. **提示与执行各说各话**：`unathorizedAgentMention` 的授权判定硬编码
 *    `grantAllows = null` —— 台账桥放行了执行（卡片进群），这条提示却说
 *    「没有执行：你不在授权名单里」，卡片与否认同屏。修后：装配了台账桥时，
 *    提示消费的就是执行时消费的那一份（授权台账只有一个事实源）。
 */
class AgentIntentPromptAndGrantBridgeTest {

    private val scope = CoroutineScope(Job())

    private fun controller(udpPort: Int = 0, tcpPort: Int = 0): RelayController = RelayController(
        platform = InMemoryAppPlatform(),
        store = InMemorySettingsStore(
            RelaySettings.firstRun().copy(
                udpPort = udpPort, tcpPort = tcpPort,
                persistMessages = false, persistMessagesDecided = true,
            ),
        ),
        messageStore = InMemoryMessageStore(),
        agentStore = InMemoryAgentStore(),
        scope = scope,
    )

    private val hostNode = "11111111-1111-4111-8111-111111111111"
    private val phoneNode = "22222222-2222-4222-8222-222222222222"

    /** 宿主（本控制器）托管 agent 的群；phone 是普通成员。 */
    private fun hostedGroup(host: RelayController): String {
        val groupId = host.createGroup("mesh", listOf(phoneNode))!!
        host.recordLocalWorkspace(
            wsId = "ws-1", wsTitle = "WS", wsPath = null,
            agentKey = null, agentKind = "gpu-runner", online = true,
        )
        host.handleSetGroupAgentWorkspaces(groupId, listOf("ws-1"))
        return groupId
    }

    private fun intentOf(agentId: String) = buildJsonObject {
        put("agentId", agentId)
        put("mode", "auto")
    }

    @Test
    fun theMessageTextBecomesTheExecutorPromptWithTheMentionStripped() = runBlocking {
        val host = controller()
        val groupId = hostedGroup(host)
        val seen = mutableListOf<String>()
        host.invokeExecutor = object : AgentInvokeExecutor {
            override suspend fun execute(request: AgentInvokeTaskRequest): AgentInvokeExecution {
                seen += request.prompt
                return AgentInvokeExecution(status = InvokeLeaseStates.DONE)
            }
        }
        // 发送者是本机用户（AgentHost 档）——只看 prompt 派生，不掺授权。
        host.invokeCtl.handleInboundAgentIntent(
            groupId, host.state.value.selfNodeId, intentOf("gpu-runner"),
            messageText = "@gpu-runner run the regression suite",
        )
        // 执行在 host.scope 的协程里：轮询到它发生（awaitUntil 的同一条纪律，不睡固定时长）。
        com.kapp.tidings.testutil.awaitUntil("执行器收到 prompt") { seen.isNotEmpty() }
        assertEquals(
            "run the regression suite", seen.single(),
            "任务话必须剥掉 @ token 后到达执行器 —— 空串等于把用户的任务丢了",
        )
        host.stop()
    }

    @Test
    fun theUnauthorizedNoticeConsumesTheSameGrantLedgerAsTheExecutor() = runBlocking {
        val host = controller()
        val groupId = hostedGroup(host)
        // 台账放行 phone 执行 gpu-runner —— 与执行桥同一份事实。
        host.invokeGrantAllows = { agentId, node -> agentId == "gpu-runner" && node == phoneNode }
        // 提示判据：放行 ⇒ 不出「没有执行」的横幅（执行路径会真的跑）。
        assertNull(
            host.unathorizedAgentMention("@gpu-runner go", groupId, phoneNode),
            "台账已放行：提示路径不许再说「你不在授权名单里」",
        )
        // 台账不放行 ⇒ 横幅照出（fail-closed 方向不变）。
        host.invokeGrantAllows = { _, _ -> false }
        val seatName = host.state.value.settings.groups.single().virtualAgents!!.single().name
        assertEquals(
            seatName, host.unathorizedAgentMention("@gpu-runner go", groupId, phoneNode),
            "未授权时横幅要指名 agent（席位显示名），用户才知道找谁批",
        )
        host.stop()
    }

    @Test
    fun aNonHostSenderStillSeesTheBannerWhenNoLedgerIsWired() = runBlocking {
        // 未装配台账桥（手机等）＝ 只有宿主用户能执行 —— fail-closed 原样保留。
        val host = controller()
        val groupId = hostedGroup(host)
        val seatName = host.state.value.settings.groups.single().virtualAgents!!.single().name
        assertEquals(
            seatName, host.unathorizedAgentMention("@gpu-runner go", groupId, phoneNode),
            "没有台账可用时，非宿主发送者的 @ 一律按未授权提示",
        )
        assertTrue(host.invokeGrantAllows == null)
        host.stop()
    }

    /**
     * 发送侧自检与意图帧的关系（真机抓到的第三处自相矛盾）：带意图的消息由**宿主**
     * 授权（谁托管谁执行），发送者的本地台账对远端宿主没有发言权 —— 横幅说
     * 「没有执行」而宿主跑完任务回了卡片。修后：带 agentIntent 的发送不出本地横幅
     * （授权与横幅由入站意图路径一处出）；不带意图的普通 @ 仍按本地判据出横幅。
     */
    @Test
    fun anIntentCarryingSendSkipsTheLocalSenderSideBanner() = runBlocking {
        // 两个真节点（照 TwoNodeDeliveryTest 的形状）：sender 发，host 授权与执行。
        val portS = java.net.ServerSocket(0).use { it.localPort }
        val portH = java.net.ServerSocket(0).use { it.localPort }
        val sender = controller(portS, portS + 1)
        val host = controller(portH, portH + 1)
        try {
            sender.start()
            host.start()
            com.kapp.tidings.testutil.awaitUntil("两节点起来") {
                sender.state.value.network.phase.name == "Running" &&
                    host.state.value.network.phase.name == "Running"
            }
            val selfH = host.state.value.selfNodeId
            val selfS = sender.state.value.selfNodeId
            sender.addManualPeer("127.0.0.1", portH.toString())
            host.addManualPeer("127.0.0.1", portS.toString())
            com.kapp.tidings.testutil.awaitUntil("互认") {
                sender.state.value.peers.any { it.nodeId == selfH } &&
                    host.state.value.peers.any { it.nodeId == selfS }
            }
            // host 建群 + 声明座位（宿主 = 群主），sender 收到元数据。
            val groupId = host.createGroup("mesh", listOf(selfS))!!
            host.recordLocalWorkspace(
                wsId = "ws-1", wsTitle = "WS", wsPath = null,
                agentKey = null, agentKind = "gpu-runner", online = true,
            )
            host.handleSetGroupAgentWorkspaces(groupId, listOf("ws-1"))
            com.kapp.tidings.testutil.awaitUntil("sender 收到座位") {
                sender.state.value.settings.groups.firstOrNull { it.groupId == groupId }
                    ?.virtualAgents.orEmpty().isNotEmpty()
            }
            val seatName = sender.state.value.settings.groups.single().virtualAgents!!.single().name

            // 带意图的发送：本地不出横幅（授权归宿主）。
            sender.sendToGroup(
                groupId, "@" + seatName + " go", agentIntent = intentOf("gpu-runner"),
            )
            // 不带意图的普通 @：本地横幅照出（fail-closed 原样）。
            sender.sendToGroup(groupId, "@" + seatName + " go again")
            val conv = com.kapp.tidings.core.protocol.ConvId.group(groupId)
            com.kapp.tidings.testutil.awaitUntil("普通 @ 的横幅出现") {
                sender.state.value.messages[conv].orEmpty()
                    .any { it.notice == com.kapp.tidings.domain.model.NoticeKind.AgentNotAuthorized }
            }
            val notices = sender.state.value.messages[conv].orEmpty()
                .filter { it.notice == com.kapp.tidings.domain.model.NoticeKind.AgentNotAuthorized }
            assertEquals(
                1, notices.size,
                "带意图的发送不许出本地横幅（宿主会回答）——只有不带意图的那条出",
            )
        } finally {
            sender.stop()
            host.stop()
        }
    }
}
