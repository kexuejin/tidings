// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.core.protocol.ConvId
import com.kapp.tenon.domain.InMemoryAgentStore
import com.kapp.tenon.domain.InMemoryMessageStore
import com.kapp.tenon.domain.InMemorySettingsStore
import com.kapp.tenon.domain.NoUiTexts
import com.kapp.tenon.domain.RelayController
import com.kapp.tenon.domain.RelaySettings
import com.kapp.tenon.domain.RelayUiTexts
import com.kapp.tenon.platform.InMemoryAppPlatform
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * D18 的**行为**探针（不是源码守卫）：同一句群工单「create probe.txt」，
 * 带点名事实 ⇒ 落到工单派单管线；不带 ⇒ 留在只读讨论。
 *
 * `create` 不在 CognitiveFlow 的软动词表里，所以不带点名时必然被判成 Discuss；
 * 控制面不可用时，工单管线会走 `announceDispatchFailure` 在会话里留一条
 * `agentState=failed` 的可见行，而讨论流只会安静地返回 error —— 两者可区分。
 * 用 Unconfined 调度器让这条 launch 在调用返回前跑完，不用任何时间赌同步。
 *
 * 装置必须装**文案桥**：`broadcastAgentState` 的横幅正文经 `uiTexts` 取，没装桥时
 * 正文是空串，而写入侧 `hasRenderableContent` 会把零正文行判成空帧丢掉 —— 生产壳
 * 装了 `ResourceUiTexts`，不会有这个现象。这里装上桥才是与真机同款的失败可见性。
 */
class GroupMentionDispatchBehaviorTest {

    /** 只给「派单失败」一句非空正文；其余措辞不是本用例的被测对象。 */
    private object Texts : RelayUiTexts by NoUiTexts {
        override suspend fun dispatchFailedNotice(title: String, agentName: String, reason: String): String =
            "dispatch failed: $title / $agentName / $reason"
    }

    private fun harness(): Pair<RelayController, DesktopWorkerOrchestrator> {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val controller = RelayController(
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
        controller.uiTexts = Texts
        return controller to DesktopWorkerOrchestrator(
            planeProvider = { null },
            controller = controller,
            scope = scope,
        )
    }

    private data class Trace(val states: List<String>, val rows: List<String>)

    private suspend fun dispatch(mentionedAgentKey: String?): Trace {
        val (controller, orchestrator) = harness()
        val groupId = controller.createGroup("d18 群", listOf("peer-x"))!!
        val convId = ConvId.group(groupId)
        orchestrator.handlePrompt(
            convId = convId,
            senderPeerId = controller.state.value.selfNodeId,
            prompt = "create probe.txt",
            replyToId = null,
            wsId = null,
            mentionedAgentKey = mentionedAgentKey,
        )
        val rows = controller.state.value.messages[convId].orEmpty()
        return Trace(
            states = rows.mapNotNull { it.agentState },
            rows = rows.map { "state=${it.agentState} text=${it.text} card=${it.card != null}" },
        )
    }

    @Test
    fun anExplicitMentionReachesTheTicketPathInsteadOfTheDiscussion() = runBlocking {
        val trace = dispatch(mentionedAgentKey = "dsh")
        assertTrue(
            "failed" in trace.states,
            "显式点名的工单句必须走到派单出口（失败也要在会话里可见）；实际状态=${trace.states}，行=${trace.rows}",
        )
    }

    @Test
    fun withoutTheMentionFactTheSameSentenceStaysInTheDiscussion() = runBlocking {
        val trace = dispatch(mentionedAgentKey = null)
        assertTrue(
            trace.states.isEmpty(),
            "没点名的提问句必须留在只读讨论，不能被误派成工单；实际状态=${trace.states}，行=${trace.rows}",
        )
    }
}
