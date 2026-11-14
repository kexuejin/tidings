// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.AssistantQuestNotice
import com.kapp.tenon.core.protocol.ConvId
import com.kapp.tenon.core.protocol.QuestStatus
import com.kapp.tenon.domain.model.MessageUi
import com.kapp.tenon.platform.InMemoryAppPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 便签闭环（规格场景 8 的桌面本机半边）：机主在助理会话用派单模式发出的指令
 * 盖 clientToken 落便签 → 执行体派发 → 通知带 token 回来 → **便签原地翻面成支线卡**
 * （mine 保持 true），绝不追加第二条卡。
 */
class AssistantQuestNoteBindingTest {

    private val scope = CoroutineScope(Job())

    private fun createController(): RelayController = RelayController(
        platform = InMemoryAppPlatform(),
        store = InMemorySettingsStore(
            RelaySettings.firstRun().copy(
                udpPort = 0,
                tcpPort = 0,
                persistMessages = false,
                persistMessagesDecided = true,
            ),
        ),
        messageStore = InMemoryMessageStore(),
        agentStore = InMemoryAgentStore(),
        scope = scope,
    )

    private fun RelayController.assistantRows(): List<MessageUi> =
        stateLock.withLock { core.threads[ConvId.ASSISTANT].orEmpty() }

    @Test
    fun dispatchStampsNoteAndNoticeBindsItInPlace(): Unit = runBlocking {
        val controller = createController()
        val dispatched = mutableListOf<Pair<String, String>>() // prompt to token
        controller.onAssistantQuestDispatch = { prompt, token ->
            dispatched.add(prompt to token)
            true // 桌面：执行体在场
        }

        controller.dispatchAssistantQuest("优化 FTS 触发器")
        assertEquals(1, dispatched.size, "派发恰好一次")
        val token = dispatched.single().second

        // 便签在会话里：机主自己的消息，带 token，还不是卡
        val note = controller.assistantRows().single()
        assertTrue(note.mine, "便签是机主自己的消息")
        assertEquals(token, note.clientToken)
        assertEquals("优化 FTS 触发器", note.text)

        // 执行体的 RUNNING 通知带着同一 token 回来 → 便签原地翻面
        controller.handleAssistantQuestNotice(
            AssistantQuestNotice(
                questId = "quest-note-1",
                title = "优化 FTS 触发器",
                status = QuestStatus.RUNNING,
                summary = "starting",
                convId = ConvId.ASSISTANT,
                clientToken = token,
                rev = 0L,
            ),
        )

        val rows = controller.assistantRows()
        assertEquals(1, rows.size, "翻面不得追加第二条卡（§8.1 / 场景 8）")
        val flipped = rows.single()
        assertEquals("quest-note-1", flipped.questId, "quest 字段盖到便签上")
        assertEquals(0L, flipped.questRev)
        assertNotNull(flipped.renderCard, "便签翻成支线卡")
        assertTrue(flipped.mine, "翻面后仍是机主自己的条目")

        // 后续通知（rev 推进）沿 questId 路径继续翻同一条
        controller.handleAssistantQuestNotice(
            AssistantQuestNotice(
                questId = "quest-note-1",
                title = "优化 FTS 触发器",
                status = QuestStatus.WAITING_INPUT,
                summary = "need decision",
                convId = ConvId.ASSISTANT,
                rev = 1L,
            ),
        )
        assertEquals(1, controller.assistantRows().size, "questId 路径接管后续翻面")
        assertEquals(1L, controller.assistantRows().single().questRev)
    }

    @Test
    fun noticeWithUnknownTokenFallsBackToNewCard(): Unit = runBlocking {
        val controller = createController()
        // 跨设备通知（token 在本机没有对应便签）：落回普通 questId 路径，不丢通知
        controller.handleAssistantQuestNotice(
            AssistantQuestNotice(
                questId = "quest-remote-1",
                title = "Remote quest",
                status = QuestStatus.RUNNING,
                summary = "from elsewhere",
                convId = ConvId.ASSISTANT,
                clientToken = "note-from-other-device",
                rev = 0L,
            ),
        )
        val rows = controller.assistantRows()
        assertEquals(1, rows.size)
        assertEquals("quest-remote-1", rows.single().questId)
        assertEquals(false, rows.single().mine)
    }

    @Test
    fun noteWithoutExecutorStaysAsNote(): Unit = runBlocking {
        val controller = createController()
        controller.onAssistantQuestDispatch = { _, _ -> false } // 手机/离线：无执行体
        controller.dispatchAssistantQuest("记得买牛奶")
        val note = controller.assistantRows().single()
        assertTrue(note.mine)
        assertEquals("记得买牛奶", note.text)
        assertNull(note.questId, "没有执行体就没有 quest 字段——便签如实留存")
        assertNull(note.renderCard, "便签不装卡")
    }
}
