// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.kapp.tenon.db.RelayDatabase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 调度中枢台账（21.sqm）的存取闭环。
 *
 * 判据全部落在「存进去 → 读回来还在」与「翻态后读回来是终态」上——只断言 SQL
 * 参数被传了是本仓反复出现的假绿形态。触发事件队列的持久幂等（event_id 主键）
 * 是 webhook 内存幂等表重启丢失后的最后防线，单独钉一条。
 */
class SchedulerStoreTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var store: SqlDelightSchedulerStore

    private var now = 10_000L

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        RelayDatabase.Schema.create(driver)
        store = SqlDelightSchedulerStore(RelayDatabase(driver), nowMillis = { now })
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    private fun event(
        id: String,
        createdAt: Long,
        state: String = TriggerEventStates.PENDING,
        sourceId: String = "mail",
    ) = TriggerEventRecord(
        eventId = id,
        sourceId = sourceId,
        type = "mail.inbound",
        subject = "owner/repo#12",
        summary = "磁盘将满告警",
        detail = "usage at 97%",
        link = "https://example.com/evt/" + id,
        state = state,
        error = null,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    @Test
    fun eventRoundTripsAllFields() {
        assertTrue(store.enqueueEvent(event("evt-1", 1000)), "首次入队应成功")

        val back = store.pendingEvents(limit = 10).single()
        assertEquals("evt-1", back.eventId)
        assertEquals("mail", back.sourceId)
        assertEquals("mail.inbound", back.type)
        assertEquals("owner/repo#12", back.subject)
        assertEquals("磁盘将满告警", back.summary)
        assertEquals("usage at 97%", back.detail)
        assertEquals("https://example.com/evt/evt-1", back.link)
        assertEquals(TriggerEventStates.PENDING, back.state)
        assertEquals(1000L, back.createdAt)
    }

    /** 持久幂等：同 id 重放不再入队（内存幂等表重启丢失后的最后防线）。 */
    @Test
    fun duplicateEventIdIsAbsorbedByPrimaryKey() {
        assertTrue(store.enqueueEvent(event("evt-1", 1000)))
        assertFalse(store.enqueueEvent(event("evt-1", 2000)), "同 id 重放必须被主键吸收")
        assertEquals(1, store.pendingEvents(limit = 10).size, "重复入队不许产生第二行")
    }

    @Test
    fun pendingEventsOrderByIdCreatedAtAndRespectLimit() {
        store.enqueueEvent(event("evt-c", 3000))
        store.enqueueEvent(event("evt-a", 1000))
        store.enqueueEvent(event("evt-b", 2000))

        val pending = store.pendingEvents(limit = 10)
        assertEquals(listOf("evt-a", "evt-b", "evt-c"), pending.map { it.eventId }, "按 created_at 先进先出")

        assertEquals(listOf("evt-a", "evt-b"), store.pendingEvents(limit = 2).map { it.eventId })
    }

    @Test
    fun stateTransitionsAndUnknownStateAreGuarded() {
        store.enqueueEvent(event("evt-1", 1000))

        store.updateEventState("evt-1", TriggerEventStates.RUNNING, null)
        assertEquals(0, store.pendingEvents(limit = 10).size, "取走的事件不再出现在 pending 里")

        store.updateEventState("evt-1", TriggerEventStates.FAILED, "no executor for agent")
        // 终态行仍占幂等键：失败可见性（重放不许复活已完成的事件）。
        assertFalse(store.enqueueEvent(event("evt-1", 1000)), "终态行仍占幂等键，重放不许复活")

        assertFailsWith<IllegalArgumentException> {
            store.updateEventState("evt-1", "vanished", null)
        }
    }

    /** 台账不是归档库：终态行按 TTL 让位，pending / running 不受影响。 */
    @Test
    fun pruneRemovesOnlyTerminalRowsBeforeCutoff() {
        store.enqueueEvent(event("evt-done", 1000))
        store.enqueueEvent(event("evt-pending", 2000))
        store.updateEventState("evt-done", TriggerEventStates.DONE, null)

        // 时钟走到 30s：done 行 updated_at（=10s，注入时钟）早于 cutoff 20s 且为终态
        // ⇒ 被清；pending 行不是终态 ⇒ 原样保留。
        now = 30_000L
        store.pruneTerminalEventsBefore(epochMs = 20_000L)

        // done 行的主键已让位：同 id 重放按新事件受理。
        assertTrue(store.enqueueEvent(event("evt-done", 25_000)), "被 prune 的终态行主键已让位，重放按新事件受理")
        assertEquals(
            listOf("evt-pending", "evt-done"),
            store.pendingEvents(limit = 10).map { it.eventId },
            "终态行被 prune，pending 行原样保留（按 created_at 先进先出）",
        )
    }

    @Test
    fun taskRunStateRoundTripsAndUpserts() {
        store.saveTaskRunState(
            TaskRunState(
                taskId = "mail-assistant/morning-triage",
                lastRunAt = null,
                nextRunAt = 8000L,
                jitterOffsetSec = -17,
                missedRuns = 0,
                updatedAt = 1000L,
            ),
        )

        val loaded = store.taskRunStates().single()
        assertEquals("mail-assistant/morning-triage", loaded.taskId)
        assertEquals(null, loaded.lastRunAt)
        assertEquals(8000L, loaded.nextRunAt)
        assertEquals(-17, loaded.jitterOffsetSec, "负偏移要原样存取（Jitter 区间是 [-N, +N]）")

        // 跑过一轮：lastRunAt 落账、missed 补偿记账被覆盖写。
        store.saveTaskRunState(loaded.copy(lastRunAt = 8000L, nextRunAt = 9000L, missedRuns = 2, updatedAt = 8100L))
        val after = store.taskRunStates().single()
        assertEquals(8000L, after.lastRunAt)
        assertEquals(9000L, after.nextRunAt)
        assertEquals(2, after.missedRuns)
        assertEquals(-17, after.jitterOffsetSec, "偏移是逐任务固定的，覆盖写不许漂移")
    }
}
