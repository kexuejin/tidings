// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.AgentSenderId
import com.kapp.tenon.core.protocol.ConvId
import com.kapp.tenon.domain.model.MessageUi
import com.kapp.tenon.platform.InMemoryAppPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 群意图派单**被授权门拒绝**时的回执（真机 T5「全静默」的收口）。
 *
 * ## 现场
 *
 * 手机在群里 @ 席位发一条带 `agentIntent` 的帧，宿主收到、dedup 增加、TCP ACK 回了，
 * 然后授权判据判「这台设备没有被逐 agent 授权」⇒ 落一条 `AgentNotAuthorized` 的
 * **本地 notice** ⇒ return。notice 是纯本地数据、不进协议（见 `NoticeKind` 的 KDoc），
 * 于是手机上零卡片、零横幅，连「被拒了」都不知道。
 *
 * 授权判据本身是对的（`forExecution=true` 要求逐 agent 显式授权，防新装 agent 自动
 * 继承执行权），**不该放宽**；要修的是「拒绝说不说得出口」。
 *
 * ## 这一组钉什么
 *
 * 1. 拒绝 ⇒ 群流必须有一条 `agentState=failed`、agent 署名、正文来自文案桥的行 ——
 *    它是 `broadcastAgentState` 先落库、再发帧的**协议形状**，与本地 notice 不是一回事；
 * 2. 本地 notice 仍在（宿主本机的解释，不删既有语义）；
 * 3. 同一帧重投只回一条（`messageId` 去重，别拿别人的拒绝刷屏）；
 * 4. 授权放行 ⇒ **不出**拒绝横幅（不能每条成功派单都挂一句废话）。
 *
 * 装置必须装文案桥：`broadcastAgentState` 在正文为空时抑制发帧，而 `NoUiTexts` 的
 * `agentActionDeniedNotice` 返回空串 —— 不装桥会看到「行=[]」，那是装置问题不是产品问题。
 */
class IntentDeniedFeedbackTest {

    private val scope = CoroutineScope(Job())

    private fun controller(): RelayController = RelayController(
        platform = InMemoryAppPlatform(),
        store = InMemorySettingsStore(
            RelaySettings.firstRun().copy(
                udpPort = 0, tcpPort = 0,
                persistMessages = false, persistMessagesDecided = true,
            ),
        ),
        messageStore = InMemoryMessageStore(),
        agentStore = InMemoryAgentStore(),
        scope = scope,
    )

    private val phoneNode = "22222222-2222-4222-8222-222222222222"

    private object DeniedTexts : RelayUiTexts by NoUiTexts {
        override suspend fun agentActionDeniedNotice(actor: String, agentName: String): String =
            "$actor has no grant to drive $agentName"
    }

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

    private fun RelayController.rows(groupId: String): List<MessageUi> =
        state.value.messages[ConvId.group(groupId)].orEmpty()

    private fun RelayController.stateRows(groupId: String): List<MessageUi> =
        rows(groupId).filter { it.agentState != null }

    /** 授权拒绝 + 已托管席位 + 装好文案桥：真机那次「零回音」的完整装置。 */
    private fun deniedHost(): Pair<RelayController, String> {
        val host = controller()
        val groupId = hostedGroup(host)
        host.uiTexts = DeniedTexts
        host.invokeGrantAllows = { _, _ -> false }
        // executor 在位但一次都不许被调到（拒绝发生在执行器之前）。
        host.invokeExecutor = object : AgentInvokeExecutor {
            override suspend fun execute(request: AgentInvokeTaskRequest): AgentInvokeExecution {
                error("授权拒绝后不许触碰执行器")
            }
        }
        return host to groupId
    }

    @Test
    fun aDeniedIntentAnswersInTheGroupStreamNotJustWithALocalNotice() = runBlocking {
        val (host, groupId) = deniedHost()
        host.invokeCtl.handleInboundAgentIntent(
            groupId, phoneNode, intentOf("gpu-runner"),
            messageText = "@gpu-runner run the regression suite",
            messageId = "denied-intent-1",
        )

        com.kapp.tenon.testutil.awaitUntil("授权拒绝的群流回执", timeoutMs = 5_000) {
            host.stateRows(groupId).isNotEmpty()
        }
        val states = host.stateRows(groupId)
        assertEquals(1, states.size, "拒绝只回一条 agentState 行")
        val row = states.single()
        assertEquals("failed", row.agentState, "拒绝是终局事实：执行状态只能是 failed")
        assertEquals(
            AgentSenderId.of(host.state.value.selfNodeId, "gpu-runner"), row.senderId,
            "署名必须是 agent —— 这是 broadcastAgentState 的协议形状，不是本地 notice",
        )
        assertTrue(row.text.isNotBlank(), "正文必须来自文案桥；空正文会被 broadcastAgentState 抑制发帧")
        assertTrue(row.text.contains("no grant"), "拒绝原因要说出口（找不到人批比静默好）")
        // 宿主本机那条解释仍在：不删既有语义，只是不再拿它当唯一回执。
        assertEquals(
            1, host.rows(groupId).count {
                it.notice == com.kapp.tenon.domain.model.NoticeKind.AgentNotAuthorized
            },
            "本地 AgentNotAuthorized 提示照旧保留（宿主自己看得懂为什么）",
        )
        host.stop()
    }

    @Test
    fun aReplayedDeniedIntentDoesNotFloodTheGroup() = runBlocking {
        val (host, groupId) = deniedHost()
        repeat(3) {
            host.invokeCtl.handleInboundAgentIntent(
                groupId, phoneNode, intentOf("gpu-runner"),
                messageText = "@gpu-runner run the regression suite",
                messageId = "denied-intent-replay",
            )
        }
        com.kapp.tenon.testutil.awaitUntil("首次拒绝回执", timeoutMs = 5_000) {
            host.stateRows(groupId).isNotEmpty()
        }
        assertEquals(
            1, host.stateRows(groupId).size,
            "同一帧重投只回一条 —— 否则任何人重放一次就能把群刷成一片红",
        )
        host.stop()
    }

    @Test
    fun aGrantedIntentDoesNotProduceADeniedBanner() = runBlocking {
        val host = controller()
        val groupId = hostedGroup(host)
        host.uiTexts = DeniedTexts
        host.invokeGrantAllows = { _, _ -> true }
        var executions = 0
        host.invokeExecutor = object : AgentInvokeExecutor {
            override suspend fun execute(request: AgentInvokeTaskRequest): AgentInvokeExecution {
                executions++
                return AgentInvokeExecution(status = InvokeLeaseStates.DONE)
            }
        }
        host.invokeCtl.handleInboundAgentIntent(
            groupId, phoneNode, intentOf("gpu-runner"),
            messageText = "@gpu-runner run the regression suite",
            messageId = "granted-intent-1",
        )
        com.kapp.tenon.testutil.awaitUntil("授权放行后执行器收到任务", timeoutMs = 5_000) {
            executions == 1
        }
        assertTrue(
            host.stateRows(groupId).isEmpty(),
            "放行时不许挂拒绝横幅 —— 成功派单下面挂一句『没权限』是假事实",
        )
        host.stop()
    }
}
