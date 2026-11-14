// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.kapp.tenon.core.protocol.QuestStatus
import com.kapp.tenon.db.RelayDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class QuestInterventionHubConcurrencyTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: RelayDatabase

    @Before
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        RelayDatabase.Schema.create(driver)
        database = RelayDatabase(driver)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun concurrentReentryCancelsOldCoroutineAndNewCoroutineReceivesInputWithoutBeingRemovedByOldFinally(): Unit = runBlocking {
        val hub = QuestInterventionHub(database)
        val questId = "quest-concurrency-1"

        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()

        // 启动第一个挂起协程
        val firstJob = async {
            try {
                hub.awaitHumanInput(
                    questId = questId,
                    convId = "assistant",
                    prompt = "First prompt?",
                    timeoutMs = 60_000L,
                    onStarted = { firstStarted.complete(Unit) },
                )
            } catch (e: CancellationException) {
                "CANCELLED"
            }
        }

        firstStarted.await()
        assertTrue("第一个挂起已注册", hub.isPending(questId))

        // 重入：用相同 questId 发起第二次挂起
        val secondJob = async {
            hub.awaitHumanInput(
                questId = questId,
                convId = "assistant",
                prompt = "Second prompt?",
                timeoutMs = 60_000L,
                onStarted = { secondStarted.complete(Unit) },
            )
        }

        secondStarted.await()

        // 验证旧协程收到 CancellationException 并退出
        val firstResult = firstJob.await()
        assertEquals("CANCELLED", firstResult)

        // 验证旧协程退出后，数据库状态并未被旧协程 catch 误标为 CANCELLED，仍保持新协程的 WAITING_INPUT
        val dbState = database.relayDatabaseQueries.selectQuestSuspendedStateById(questId).executeAsOneOrNull()
        assertEquals("WAITING_INPUT", dbState?.status)

        // 关键断言：旧协程的 finally 块执行 CAS 移除，绝不能把新协程从 pendingInputs 误删
        assertTrue("新协程必须仍然在等待队列中，未被旧协程 finally 误删", hub.isPending(questId))

        // 人类提交输入，唤醒新协程
        val submitSuccess = hub.submitHumanInput(questId, "Human Answer to Second Prompt")
        assertTrue("提问成功唤醒挂起中的新协程", submitSuccess)

        val secondResult = secondJob.await()
        assertEquals("Human Answer to Second Prompt", secondResult)

        // 任务完成后已彻底清除
        assertFalse("协程完成后队列已清空", hub.isPending(questId))
    }

    @Test
    fun cancellationPersistsCancelledStateAndDispatchesCallback(): Unit = runBlocking {
        val updates = mutableListOf<Pair<String, QuestStatus>>()
        val hub = QuestInterventionHub(database) { qId, status ->
            updates.add(qId to status)
        }
        val questId = "quest-cancel-1"
        val started = CompletableDeferred<Unit>()

        val job = launch {
            hub.awaitHumanInput(
                questId = questId,
                convId = "assistant",
                prompt = "To be cancelled",
                timeoutMs = 60_000L,
                onStarted = { started.complete(Unit) },
            )
        }

        started.await()
        assertTrue(hub.isPending(questId))

        job.cancelAndJoin()

        assertFalse(hub.isPending(questId))
        val persisted = database.relayDatabaseQueries.selectQuestSuspendedStateById(questId).executeAsOneOrNull()
        assertEquals("CANCELLED", persisted?.status)
        assertTrue(updates.any { it.first == questId && it.second == QuestStatus.CANCELLED })
    }

    @Test
    fun resumePausedQuestRestoresWaitingInput(): Unit = runBlocking {
        val updates = mutableListOf<Pair<String, QuestStatus>>()
        val hub = QuestInterventionHub(database) { qId, status ->
            updates.add(qId to status)
        }
        val questId = "quest-paused-1"

        database.relayDatabaseQueries.insertOrReplaceQuestSuspendedState(
            quest_id = questId,
            conv_id = "assistant",
            prompt = "Paused quest",
            status = "PAUSED",
            rev = 1L,
            suspended_at = 1_000L,
        )

        val resumed = hub.resumePausedQuest(questId)
        assertTrue("恢复成功", resumed)

        val persisted = database.relayDatabaseQueries.selectQuestSuspendedStateById(questId).executeAsOneOrNull()
        assertEquals("WAITING_INPUT", persisted?.status)
        assertEquals(questId to QuestStatus.WAITING_INPUT, updates.last())
    }

    @Test
    fun startupRecoveryFlipsPendingStatesToInterrupted(): Unit = runBlocking {
        val recoveredQuests = mutableListOf<Pair<String, QuestStatus>>()
        val hub = QuestInterventionHub(database) { qId, status ->
            recoveredQuests.add(qId to status)
        }

        database.relayDatabaseQueries.insertOrReplaceQuestSuspendedState(
            quest_id = "quest-orphan-1",
            conv_id = "assistant",
            prompt = "Unanswered prompt",
            status = "WAITING_INPUT",
            rev = 1L,
            suspended_at = 1_000L,
        )

        hub.recoverInterruptedQuestsOnStartup()

        assertEquals(1, recoveredQuests.size)
        assertEquals("quest-orphan-1" to QuestStatus.INTERRUPTED, recoveredQuests[0])

        val persisted = database.relayDatabaseQueries.selectQuestSuspendedStateById("quest-orphan-1").executeAsOneOrNull()
        assertEquals("INTERRUPTED", persisted?.status)
    }

    @Test
    fun timeoutPathRemovesPendingBeforePausingSoLateSubmitIsHonestlyRejected(): Unit = runBlocking {
        // 反向验证钉子（§4）：旧实现「先写 PAUSED/通知、finally 才摘 deferred」的窗口里，
        // 迟到的 submitHumanInput 会 complete 成功（UI 以为投到了）而结果被丢弃 ——
        // §3 的直系形态。新实现 CAS 摘除先于 PAUSED 通知，迟到提交必须如实返回 false。
        // onStatusUpdate 的闸门把窗口撑开，让这个时序**确定性**可测。
        val gateEntered = CompletableDeferred<Unit>()
        val releaseGate = CompletableDeferred<Unit>()
        val hub = QuestInterventionHub(database) { _, status ->
            if (status == QuestStatus.PAUSED) {
                gateEntered.complete(Unit)
                runBlocking { releaseGate.await() }
            }
        }
        val questId = "quest-timeout-boundary-1"

        val job = async(Dispatchers.IO) {
            hub.awaitHumanInput(
                questId = questId,
                convId = "assistant",
                prompt = "Will time out",
                timeoutMs = 50,
            )
        }

        // 超时路径已走到 PAUSED 通知（窗口被闸门撑开）：此刻 submit 的返回值就是判据
        gateEntered.await()
        val lateSubmit = hub.submitHumanInput(questId, "too late")
        assertFalse("超时已判负后的迟到提交必须如实拒绝，不得谎报成功", lateSubmit)

        releaseGate.complete(Unit)
        assertNull("超时路径返回 null（未取到人类输入）", job.await())

        val persisted = database.relayDatabaseQueries.selectQuestSuspendedStateById(questId).executeAsOneOrNull()
        assertEquals("PAUSED", persisted?.status)
    }

    @Test
    fun resumePausedQuestMergesOwnerInputIntoPrompt(): Unit = runBlocking {
        // 机主恢复时补充的指令不能蒸发（§3）：并入挂起行 prompt，
        // 重拉的 runner 读行即拿到完整上下文。
        val hub = QuestInterventionHub(database)
        val questId = "quest-resume-input-1"

        database.relayDatabaseQueries.insertOrReplaceQuestSuspendedState(
            quest_id = questId,
            conv_id = "assistant",
            prompt = "Approve merging changes into main branch?",
            status = "PAUSED",
            rev = 1L,
            suspended_at = 1_000L,
        )

        val resumed = hub.resumePausedQuest(questId, additionalInput = "  放入 testFixtures  ")
        assertTrue("恢复成功", resumed)

        val persisted = database.relayDatabaseQueries.selectQuestSuspendedStateById(questId).executeAsOneOrNull()
        assertEquals("WAITING_INPUT", persisted?.status)
        assertTrue(
            "机主补充指令必须并进 prompt（供重拉的 runner 消费）",
            persisted?.prompt?.contains("[owner resume] 放入 testFixtures") == true,
        )
        assertTrue(
            "原始问题必须保留在 prompt 里",
            persisted?.prompt?.startsWith("Approve merging changes into main branch?") == true,
        )
    }
}
