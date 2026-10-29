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

    private fun controller(): RelayController = RelayController(
        platform = InMemoryAppPlatform(),
        store = InMemorySettingsStore(RelaySettings.firstRun().copy(persistMessagesDecided = true)),
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
}
