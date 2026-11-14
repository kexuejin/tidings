// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.AssistantQuestNotice
import com.kapp.tenon.domain.model.MessageUi
import com.kapp.tenon.platform.InMemoryAppPlatform
import com.kapp.tenon.testutil.awaitUntil
import com.kapp.tenon.core.protocol.ConvId
import com.kapp.tenon.core.protocol.QuestStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 规格 §8.1「同一任务在主线只有唯一一条卡片消息，通过 quest_id 与 rev 乐观锁
 * 原位翻面」的守卫用例：同 questId 的后续通知必须翻既有行（内存 + 持久层同一把
 * rev 锁），绝不追加第二条卡把「一任务一卡」变成卡片流。
 */
class QuestCardFlipTest {

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

    private fun notice(
        questId: String,
        status: QuestStatus,
        rev: Long,
        summary: String,
    ) = AssistantQuestNotice(
        questId = questId,
        title = "Quest " + questId,
        status = status,
        summary = summary,
        convId = ConvId.ASSISTANT,
        rev = rev,
    )

    private fun RelayController.questThread(): List<MessageUi> =
        stateLock.withLock { core.threads[ConvId.ASSISTANT].orEmpty().filter { it.questId != null } }

    @Test
    fun sameQuestNoticesFlipOneCardInPlace(): Unit = runBlocking {
        val controller = createController()
        controller.handleAssistantQuestNotice(notice("q-flip", QuestStatus.RUNNING, 0L, "starting"))
        controller.handleAssistantQuestNotice(notice("q-flip", QuestStatus.WAITING_INPUT, 1L, "need decision"))
        controller.handleAssistantQuestNotice(notice("q-flip", QuestStatus.COMPLETED, 2L, "all done"))

        val rows = controller.questThread()
        assertEquals(1, rows.size, "三次通知必须只有一条卡片消息（§8.1 一任务一卡）")
        val card = rows.single()
        assertEquals(2L, card.questRev, "翻面推进 questRev")
        assertEquals("completed", card.agentState, "状态翻到 completed")
        assertEquals("starting", card.text, "text 保持首条摘要（翻面不重索引 FTS）")
        val state = card.renderCard
        assertNotNull(state)
        // 卡片 JSON 的 state 是**卡片词表**（toAgentCardState）：COMPLETED → review_ready。
        // agentState 列才是协议词表（completed）——两个词表各有其位，断言各查各的。
        assertEquals("review_ready", (state["state"] as? kotlinx.serialization.json.JsonPrimitive)?.content)
    }

    @Test
    fun staleNoticeWithOlderRevIsDropped(): Unit = runBlocking {
        val controller = createController()
        controller.handleAssistantQuestNotice(notice("q-stale", QuestStatus.RUNNING, 1L, "running"))
        controller.handleAssistantQuestNotice(notice("q-stale", QuestStatus.RUNNING, 0L, "late duplicate"))

        val rows = controller.questThread()
        assertEquals(1, rows.size)
        assertEquals(1L, rows.single().questRev, "旧 rev 不得回退翻面")
    }

    @Test
    fun separateQuestsGetSeparateCards(): Unit = runBlocking {
        val controller = createController()
        controller.handleAssistantQuestNotice(notice("q-a", QuestStatus.RUNNING, 0L, "quest a"))
        controller.handleAssistantQuestNotice(notice("q-b", QuestStatus.RUNNING, 0L, "quest b"))

        assertEquals(2, controller.questThread().size, "不同任务各占一张卡")
    }

    @Test
    fun persistedRowFlipsThroughStoreOptimisticLock(): Unit = runBlocking {
        // 持久层同锁：翻面走 MessageStore.updateCardContent（quest_rev < :rev 才推进），
        // 与内存翻面双层一致——重启后读回的是翻面后的载荷。
        val store = InMemoryMessageStore()
        val controller = RelayController(
            platform = InMemoryAppPlatform(),
            store = InMemorySettingsStore(
                RelaySettings.firstRun().copy(
                    udpPort = 0,
                    tcpPort = 0,
                    persistMessages = true,
                    persistMessagesDecided = true,
                ),
            ),
            messageStore = store,
            agentStore = InMemoryAgentStore(),
            scope = scope,
        )
        controller.handleAssistantQuestNotice(notice("q-persist", QuestStatus.RUNNING, 0L, "starting"))
        controller.handleAssistantQuestNotice(notice("q-persist", QuestStatus.COMPLETED, 1L, "all done"))

        // 落库是异步的（persistThreads 经 scope.launch + persistMutex）：用 awaitUntil
        // 有界探针等翻面后的行（questRev=1）真正落库——条件是「同 rev 再推被拒」，
        // 预算不是断言。updateCardContent 只在 rev 推进时写（幂等于重试）。
        awaitUntil(
            what = "翻面后的 quest 行落库（同 rev 再推被乐观锁拒绝）",
            timeoutMs = 5_000L,
        ) {
            val flipped = store.updateCardContent(
                questId = "q-persist",
                newJsonContent = "{}",
                status = "completed",
                expectedRev = 1L,
            )
            !flipped // 被拒 = 行已在 rev>=1：翻面已落库
        }
        // 锁在岗：同 rev 拒绝、更高 rev 推进——一拒一收证明行存在且乐观锁生效。
        assertEquals(false, store.updateCardContent("q-persist", "{}", "completed", 1L), "同 rev 再推必须被乐观锁拒绝")
        assertTrue(
            store.updateCardContent("q-persist", "{}", "completed", 2L),
            "更高 rev 必须推进（行存在且锁在岗）",
        )
    }
}
