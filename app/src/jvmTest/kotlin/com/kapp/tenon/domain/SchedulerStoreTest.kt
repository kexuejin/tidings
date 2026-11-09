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
 * 参数被传了是本仓反复出现的假绿形态。触发事件队列的持久幂等
 * （(source_id, event_id) 复合主键）是 webhook 内存幂等表重启丢失后的最后防线，
 * 单独钉一条；「同 id 不同源」也必须各留一行。
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

    /** 持久幂等：同 (source, id) 重放不再入队（内存幂等表重启丢失后的最后防线）。 */
    @Test
    fun duplicateEventIdIsAbsorbedByPrimaryKey() {
        assertTrue(store.enqueueEvent(event("evt-1", 1000)))
        assertFalse(store.enqueueEvent(event("evt-1", 2000)), "同 id 重放必须被主键吸收")
        assertEquals(1, store.pendingEvents(limit = 10).size, "重复入队不许产生第二行")
    }

    /**
     * **复合主键**：两个源各自投递同一个 event.id，必须各留一行。
     *
     * 旧表把 event_id 单独当主键，第二条被 INSERT OR IGNORE 静默丢掉；而内存
     * 幂等表已经按 sourceId 分域放过行了 —— 于是 HTTP 回了 200、两个源的事件
     * 却只剩一个。这条用例在旧表定义下必然红（enqueueEvent 第二条返回 false，
     * 且 pendingEvents 只有一行）。
     */
    @Test
    fun sameEventIdFromDifferentSourcesEachGetTheirOwnRow() {
        assertTrue(store.enqueueEvent(event("evt-shared", 1000, sourceId = "mail")))
        assertTrue(
            store.enqueueEvent(event("evt-shared", 2000, sourceId = "calendar")),
            "同 id 但不同源不是重放——旧的单列主键会在这里静默吞掉 calendar 源",
        )
        assertEquals(
            setOf("mail" to "evt-shared", "calendar" to "evt-shared"),
            store.pendingEvents(limit = 10).map { it.sourceId to it.eventId }.toSet(),
        )
        assertTrue(store.eventExists("mail", "evt-shared"))
        assertTrue(store.eventExists("calendar", "evt-shared"))
        assertFalse(store.eventExists("calendar", "evt-nope"), "不存在的行不许被误报为已持久化")
        assertFalse(
            store.eventExists("mail", "evt-only-calendar"),
            "eventExists 必须按复合键查，不能只看 event_id",
        )
    }

    /**
     * 认领与翻态都必须带上 sourceId：复合主键下只给 eventId 会认错行。
     *
     * 旧实现 `claimEvent(eventId)` 在这里把 mail 的行翻成 running，calendar
     * 那条同 id 的行却永远停在 pending —— 事件要么双派要么永久卡住。
     */
    @Test
    fun claimAndStateUpdateAreScopedBySourceId() {
        store.enqueueEvent(event("evt-shared", 1000, sourceId = "mail"))
        store.enqueueEvent(event("evt-shared", 2000, sourceId = "calendar"))

        assertTrue(store.claimEvent("mail", "evt-shared"))
        assertEquals(
            TriggerEventStates.PENDING,
            store.pendingEvents(limit = 10).single { it.sourceId == "calendar" }.state,
            "认领 mail 不许动 calendar 的行",
        )
        assertTrue(store.updateEventState("mail", "evt-shared", TriggerEventStates.DONE, null))
        assertEquals(
            TriggerEventStates.PENDING,
            store.pendingEvents(limit = 10).single().state,
            "翻终态只应命中 mail 那一行",
        )
        assertFalse(
            store.updateEventState("no-such-source", "evt-shared", TriggerEventStates.DONE, null),
            "源不匹配的更新必须返回 false，而不是改到别的行",
        )
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

        assertTrue(store.claimEvent("mail", "evt-1"), "首次认领应成功")
        assertEquals(0, store.pendingEvents(limit = 10).size, "认领后的事件不再出现在 pending 里")

        store.updateEventState("mail", "evt-1", TriggerEventStates.FAILED, "no executor for agent")
        // 终态行仍占幂等键：失败可见性（重放不许复活已完成的事件）。
        assertFalse(store.enqueueEvent(event("evt-1", 1000)), "终态行仍占幂等键，重放不许复活")

        assertFailsWith<IllegalArgumentException> {
            store.updateEventState("mail", "evt-1", "vanished", null)
        }
    }

    /**
     * 认领必须是**条件**更新：第二次认领同一行必须失败。
     *
     * 这条用例存在的理由：`claimEvent` 之前是 `updateEventState`（无判据、返回 Unit），
     * 于是「我没抢到」在类型上不可观测，消费循环照样派发 —— 双派。判据只能落在
     * 「第二次返回 false」上；断言 SQL 里带了 `AND state = 'pending'` 是假绿。
     */
    @Test
    fun claimEventIsConditionalAndSecondClaimLoses() {
        store.enqueueEvent(event("evt-1", 1000))

        assertTrue(store.claimEvent("mail", "evt-1"), "第一次认领成功")
        assertFalse(store.claimEvent("mail", "evt-1"), "已被认领的行第二次认领必须失败（否则就是双派）")
    }

    @Test
    fun claimEventOnTerminalOrMissingRowFails() {
        store.enqueueEvent(event("evt-1", 1000))
        store.updateEventState("mail", "evt-1", TriggerEventStates.DONE, null)

        assertFalse(store.claimEvent("mail", "evt-1"), "终态行不许被认领回去")
        assertFalse(store.claimEvent("mail", "evt-nope"), "不存在的行认领失败而不是抛异常")
    }

    /**
     * 崩溃恢复：running 行放回 pending，其余状态不动。
     *
     * 这是「进程被 kill 后事件永久丢失」的唯一出口 —— 遗留 running 行既不在
     * pending 里（拉不到），也不在终态里（永不 prune）。
     */
    @Test
    fun reclaimRunningEventsReturnsOnlyRunningRowsToPending() {
        store.enqueueEvent(event("evt-running", 1000))
        store.enqueueEvent(event("evt-pending", 2000))
        store.enqueueEvent(event("evt-done", 3000))
        store.claimEvent("mail", "evt-running")
        store.updateEventState("mail", "evt-done", TriggerEventStates.DONE, null)

        assertEquals(1, store.reclaimRunningEvents(), "只有 1 条 running 行该被放回")

        assertEquals(
            listOf("evt-running", "evt-pending"),
            store.pendingEvents(limit = 10).map { it.eventId },
            "放回后按 created_at 重新排队；已终态的行不许复活",
        )
        assertEquals(0, store.reclaimRunningEvents(), "第二次调用没有 running 行可放回")
    }

    @Test
    fun updateEventStateReportsMissingRow() {
        store.enqueueEvent(event("evt-1", 1000))

        assertTrue(store.updateEventState("mail", "evt-1", TriggerEventStates.DONE, null), "行存在时返回 true")
        assertFalse(store.updateEventState("mail", "evt-nope", TriggerEventStates.DONE, null), "行不存在时返回 false")
    }

    /** 台账不是归档库：终态行按 TTL 让位，pending / running 不受影响。 */
    @Test
    fun pruneRemovesOnlyTerminalRowsBeforeCutoff() {
        store.enqueueEvent(event("evt-done", 1000))
        store.enqueueEvent(event("evt-pending", 2000))
        store.updateEventState("mail", "evt-done", TriggerEventStates.DONE, null)

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
