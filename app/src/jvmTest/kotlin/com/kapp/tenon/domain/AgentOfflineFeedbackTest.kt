// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.kapp.tenon.core.protocol.AgentSenderId
import com.kapp.tenon.core.protocol.ConvId
import com.kapp.tenon.domain.model.MessageUi
import com.kapp.tenon.domain.model.ScreenRecordUi
import com.kapp.tenon.platform.InMemoryAppPlatform

/**
 * 插件离线时的**群流反馈**（设计稿 v2 §4.8 / §5「失败如实」，M5 矩阵第 4 条的顺带发现）。
 *
 * ## 现场（真机）
 *
 * 宿主收到带 `agentIntent` 的群文本、而本机没有执行器（插件被 kill / 从没注册）时，
 * [RelayControllerAgentInvokeComponent.handleInboundAgentIntent] 直接 `?: return` ——
 * **群流里一条都没有**：没卡片、没横幅、没「不可用」。点 @ 的人盯着自己的屏幕，
 * 看到的和「已经派单成功、只是任务还在跑」完全一样。
 *
 * 这与本仓既有纪律冲突：M3 已经裁决「没有通路的按钮组整块不画」，理由就是
 * 「按下去没反应比没有更糟」。一条被静默吞掉的派单是同一类问题，只是入口不是按钮。
 *
 * ## 这一组用例钉什么
 *
 * 1. 没有执行器 ⇒ 群流里必须有一条 `agentState=failed` 的 agent 署名消息（不是 notice：
 *    notice 是纯本地数据、不进协议，别人看不到）；
 * 2. 执行器抛错 / 状态非 done 且没有卡片 ⇒ 同一条反馈（静默只换了个位置不算修）；
 * 3. **同一次派单只出 1 条**（重放/重复投递不许把群流刷成一片红）；
 * 4. 正常执行（done + 卡片）⇒ **不出**横幅（否则每条成功的派单下面都挂一条废话）。
 *
 * 文案本身不在这里断言 —— 它住在 strings.xml（CJK 门禁 + 多语言对账），这里只断言
 * 「有一条、是 failed、署名是 agent、正文来自 [RelayUiTexts]」。
 */
class AgentOfflineFeedbackTest {

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

    /** 只实现新加的那两个方法之外的都返回空串 —— 文案不是本用例的被测对象。 */
    private object OfflineTexts : RelayUiTexts {
        override suspend fun screenCardText(record: ScreenRecordUi): String = ""
        override suspend fun nudgeNotice(): String = ""
        override suspend fun workspaceGreeting(agentKind: String, workspaceTitle: String): String = ""
        override suspend fun workspaceSessionBrief(sessionTitle: String, busy: Boolean): String = ""
        override suspend fun invokeTimeoutTitle(agentName: String): String = ""
        override suspend fun invokeActionRetryLabel(): String = ""
        override suspend fun invokeActionFallbackLabel(): String = ""
        override suspend fun tagNoMatchTitle(tag: String): String = ""
        override suspend fun agentUnavailableNotice(agentName: String): String = "$agentName · offline"
        override suspend fun agentNotCompletedNotice(agentName: String): String = "$agentName · not completed"
        override suspend fun agentActionDeniedNotice(actor: String, agentName: String): String = "$actor · denied"
        override suspend fun agentEventThinkingOnlyFallback(): String = ""
    }

    private fun hostedGroup(host: RelayController): String {
        val groupId = host.createGroup("mesh", listOf(phoneNode))!!
        host.recordLocalWorkspace(
            wsId = "ws-1", wsTitle = "WS", wsPath = null,
            agentKey = null, agentKind = "gpu-runner", online = true,
        )
        host.handleAddGroupAgent(groupId, "gpu-runner", null)
        return groupId
    }

    private fun intentOf(agentId: String) = buildJsonObject {
        put("agentId", agentId)
        put("mode", "auto")
    }

    private fun RelayController.agentMessages(groupId: String): List<MessageUi> =
        state.value.messages[ConvId.group(groupId)].orEmpty()
            .filter { it.agentState != null }

    @Test
    fun aMissingExecutorStillAnswersInTheGroupStream() = runBlocking {
        val host = controller()
        val groupId = hostedGroup(host)
        host.uiTexts = OfflineTexts
        host.invokeGrantAllows = { _, _ -> true }
        // 没有执行器 = 插件离线 / 从没注册（真机 kill 掉插件后的形态）。
        assertTrue(host.invokeExecutor == null)

        host.invokeCtl.handleInboundAgentIntent(
            groupId, phoneNode, intentOf("gpu-runner"),
            messageText = "@gpu-runner run the regression suite",
            messageId = "env-offline-1",
        )

        val rows = run {
            com.kapp.tenon.testutil.awaitUntil("群流里出现失败反馈") { host.agentMessages(groupId).isNotEmpty() }
            host.agentMessages(groupId)
        }
        assertEquals(1, rows.size, "没有执行器时群流必须有一条反馈，不是零条")
        val row = rows.single()
        assertEquals("failed", row.agentState, "执行状态是协议事实：这里只能是 failed")
        assertEquals(
            AgentSenderId.of(host.state.value.selfNodeId, "gpu-runner"), row.senderId,
            "署名必须是 agent（不是宿主自己）—— 群里认的是「谁没执行」，不是「谁报告」",
        )
        assertTrue(row.text.isNotBlank(), "正文来自 RelayUiTexts，空正文等于什么都没说")
        host.stop()
    }

    @Test
    fun anExecutorThatThrowsAlsoAnswersInTheGroupStream() = runBlocking {
        val host = controller()
        val groupId = hostedGroup(host)
        host.uiTexts = OfflineTexts
        host.invokeGrantAllows = { _, _ -> true }
        host.invokeExecutor = object : AgentInvokeExecutor {
            override suspend fun execute(request: AgentInvokeTaskRequest): AgentInvokeExecution =
                throw IllegalStateException("plugin died mid-task")
        }

        host.invokeCtl.handleInboundAgentIntent(
            groupId, phoneNode, intentOf("gpu-runner"), messageText = "@gpu-runner go", messageId = "env-throw-1",
        )

        com.kapp.tenon.testutil.awaitUntil("执行失败也要有反馈") {
            host.agentMessages(groupId).isNotEmpty()
        }
        val row = host.agentMessages(groupId).single()
        assertEquals("failed", row.agentState)
        assertTrue(row.text.isNotBlank())
        host.stop()
    }

    @Test
    fun theSameDispatchNeverProducesTwoBanners() = runBlocking {
        val host = controller()
        val groupId = hostedGroup(host)
        host.uiTexts = OfflineTexts
        host.invokeGrantAllows = { _, _ -> true }

        // 同一个信封被投递两次（队列重放 / 去重表边界）：反馈只许有一条。
        repeat(2) {
            host.invokeCtl.handleInboundAgentIntent(
                groupId, phoneNode, intentOf("gpu-runner"), messageText = "@gpu-runner go", messageId = "env-dup-1",
            )
        }

        com.kapp.tenon.testutil.awaitUntil("失败反馈出现") { host.agentMessages(groupId).isNotEmpty() }
        assertEquals(
            1, host.agentMessages(groupId).size,
            "同一次派单只出 1 条 —— 否则队列重放会把群流刷成一片红",
        )
        host.stop()
    }

    @Test
    fun anOfflinePluginIsAnsweredImmediatelyInsteadOfLeasingIntoTheVoid() = runBlocking {
        // S4-4b（矩阵 §7.6 真机读数：插件判离线后 @ 派单，14 分钟内无卡、无横幅、无超时卡）。
        // 派单前先查插件在线状态：offline ⇒ 立刻一条 failed 横幅，且**不再派进租约**。
        val host = controller()
        val groupId = hostedGroup(host)
        host.uiTexts = OfflineTexts
        host.invokeGrantAllows = { _, _ -> true }
        val dispatched = java.util.concurrent.atomic.AtomicInteger()
        host.invokeExecutor = object : AgentInvokeExecutor {
            override suspend fun execute(request: AgentInvokeTaskRequest): AgentInvokeExecution {
                dispatched.incrementAndGet()
                throw IllegalStateException("判离线就不许把活派进租约 —— 那正是 14 分钟沉默的来源")
            }
        }
        host.invokePluginPresenceOf = { agentId -> if (agentId == "gpu-runner") PluginPresence.OFFLINE else null }

        host.invokeCtl.handleInboundAgentIntent(
            groupId, phoneNode, intentOf("gpu-runner"), messageText = "@gpu-runner go", messageId = "s4-4b-off-1",
        )

        com.kapp.tenon.testutil.awaitUntil("离线插件也要立刻有回声") { host.agentMessages(groupId).isNotEmpty() }
        val row = host.agentMessages(groupId).single()
        assertEquals("failed", row.agentState, "插件离线 = 宿主接不了这个活：协议事实是 failed")
        assertTrue(row.text.isNotBlank(), "正文非空 —— 空正文等于什么都没说")
        assertEquals(0, dispatched.get(), "判离线就不许把活派进租约")
        host.stop()
    }

    @Test
    fun onlineOrUnknownPresenceKeepsDispatchingWithoutBanner() = runBlocking {
        // online 与「状态拿不到」都必须走原路（派单进租约）—— 查不到不拒派，否则
        // 一个状态桥的抖动会把还能用的插件整条封死。
        val host = controller()
        val groupId = hostedGroup(host)
        host.uiTexts = OfflineTexts
        host.invokeGrantAllows = { _, _ -> true }
        val executed = java.util.concurrent.atomic.AtomicInteger()
        host.invokeExecutor = object : AgentInvokeExecutor {
            override suspend fun execute(request: AgentInvokeTaskRequest): AgentInvokeExecution {
                executed.incrementAndGet()
                return AgentInvokeExecution(status = InvokeLeaseStates.DONE)
            }
        }
        host.invokePluginPresenceOf = { agentId -> if (agentId == "gpu-runner") PluginPresence.ONLINE else null }

        host.invokeCtl.handleInboundAgentIntent(
            groupId, phoneNode, intentOf("gpu-runner"), messageText = "@gpu-runner go", messageId = "s4-4b-on-1",
        )

        com.kapp.tenon.testutil.awaitUntil("online 照常派单") { executed.get() == 1 }
        assertEquals(0, host.agentMessages(groupId).size, "online 正常执行（done 无卡片）不许出横幅")
        host.stop()
    }

    @Test
    fun aSuccessfulDispatchWithACardAddsNoBanner() = runBlocking {
        val host = controller()
        val groupId = hostedGroup(host)
        host.uiTexts = OfflineTexts
        host.invokeGrantAllows = { _, _ -> true }
        val executed = java.util.concurrent.atomic.AtomicInteger()
        host.invokeExecutor = object : AgentInvokeExecutor {
            override suspend fun execute(request: AgentInvokeTaskRequest): AgentInvokeExecution {
                executed.incrementAndGet()
                return AgentInvokeExecution(status = InvokeLeaseStates.DONE)
            }
        }

        host.invokeCtl.handleInboundAgentIntent(
            groupId, phoneNode, intentOf("gpu-runner"), messageText = "@gpu-runner go", messageId = "env-ok-1",
        )

        com.kapp.tenon.testutil.awaitUntil("执行器被调用") { executed.get() == 1 }
        // 没有卡片时 done 也不出横幅：成功的派单本来就该静默（卡片才是它的回执）。
        assertTrue(
            host.agentMessages(groupId).none { it.agentState == "failed" },
            "成功的派单不许挂一条「失败」横幅",
        )
        host.stop()
    }
}
