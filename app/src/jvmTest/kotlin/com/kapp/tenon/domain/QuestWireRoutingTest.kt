
package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.AssistantQuestNotice
import com.kapp.tenon.core.protocol.ConvId
import com.kapp.tenon.core.protocol.Envelope
import com.kapp.tenon.core.protocol.MsgTypes
import com.kapp.tenon.core.protocol.QuestStatus
import com.kapp.tenon.domain.model.MessageUi
import com.kapp.tenon.platform.InMemoryAppPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Phase 2 跨设备帧的入站路由守卫：配对校验（fail-closed）+ 通知消费进既有翻面链。
 * 发送侧（makeEnvelope→transport）由 core/transport 测试覆盖；这里钉的是**接收判定**。
 */
class QuestWireRoutingTest {

    private val scope = CoroutineScope(Job())

    private fun createController(paired: List<String>): RelayController = RelayController(
        platform = InMemoryAppPlatform(),
        store = InMemorySettingsStore(
            RelaySettings.firstRun().copy(
                udpPort = 0, tcpPort = 0,
                persistMessages = false, persistMessagesDecided = true,
                questPairNodes = paired,
            ),
        ),
        messageStore = InMemoryMessageStore(),
        agentStore = InMemoryAgentStore(),
        scope = scope,
    )

    private fun RelayController.assistantRows(): List<MessageUi> =
        stateLock.withLock { core.threads[ConvId.ASSISTANT].orEmpty() }

    private fun questDispatchEnvelope(from: String, token: String, prompt: String): Envelope {
        val payload = com.kapp.tenon.core.protocol.CodecQuest.encodeQuestDispatch(token, prompt)
        return Envelope(v = com.kapp.tenon.core.protocol.Protocol.VERSION, type = MsgTypes.QUEST_DISPATCH,
            id = "id-1", from = from, ts = 1_000L, payload = payload)
    }

    @Test
    fun pairedSenderDispatchReachesExecutorPort(): Unit = runBlocking {
        val controller = createController(paired = listOf("peer-desktop-1"))
        val received = mutableListOf<Pair<String, String>>()
        controller.onAssistantQuestDispatch = { prompt, token, replyTo ->
            received.add(prompt to token)
            assertEquals("peer-desktop-1", replyTo, "wire 派发必须带发端（回执目标）")
            true
        }
        controller.handleOnQuestDispatch(questDispatchEnvelope("peer-desktop-1", "note-tok-1", "do it"))
        assertEquals(1, received.size)
        assertEquals("note-tok-1", received.single().second)
    }

    @Test
    fun unpairedSenderDispatchIsRejectedFailClosed(): Unit = runBlocking {
        val controller = createController(paired = emptyList())
        var portFired = false
        controller.onAssistantQuestDispatch = { _, _, _ -> portFired = true; true }
        controller.handleOnQuestDispatch(questDispatchEnvelope("stranger-peer", "note-tok-2", "malicious"))
        assertEquals(false, portFired, "未配对发端不得触达执行体端口（fail-closed）")
    }

    @Test
    fun questNoticeBindsNoteByClientToken(): Unit = runBlocking {
        val controller = createController(paired = emptyList())
        controller.dispatchAssistantQuest("the original note")
        val note = controller.assistantRows().single()
        assertNotNull(note.clientToken)

        // 执行端回执（token 匹配）→ 便签原地翻面（QuestCardFlipTest 同款判据的 wire 版）
        val notice = AssistantQuestNotice(
            questId = "quest-wire-1", title = "note", status = QuestStatus.RUNNING,
            summary = "starting", convId = ConvId.ASSISTANT, clientToken = note.clientToken, rev = 0L,
        )
        val payload = com.kapp.tenon.core.protocol.CodecQuest.encodeQuestNotice(notice)
        controller.handleOnQuestNotice(
            Envelope(v = com.kapp.tenon.core.protocol.Protocol.VERSION, type = MsgTypes.QUEST_NOTICE,
                id = "id-2", from = "peer-desktop-1", ts = 1_000L, payload = payload),
        )
        val rows = controller.assistantRows()
        assertEquals(1, rows.size, "通知必须翻便签而不是追加")
        assertEquals("quest-wire-1", rows.single().questId)
    }
}
