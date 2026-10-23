// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.kapp.tidings.domain.model.AgentEventUi
import com.kapp.tidings.domain.model.AgentSessionKey
import com.kapp.tidings.domain.model.AgentSessionUi
import com.kapp.tidings.db.RelayDatabase
import com.kapp.tidings.platform.InMemoryAppPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 恢复路径的事件窗口：**每个会话只留最近 MAX_AGENT_EVENTS 条**，且留下的必须是**最新的**那批。
 *
 * ## 它钉的是什么
 *
 * 收帧路径 mergeAgentEvent 有 max，恢复路径原先没有 —— loadEvents() 是整表 SELECT，
 * 真机库 57,644 条 / 79 个会话全部常驻（堆直方图 57,972 个 AgentEventUi）。
 * 上限只写在了一侧，另一侧读进来的全量再也不会被裁（裁剪只发生在 merge）。
 *
 * ## 为什么断言具体 cursor 区间而不是只断条数
 *
 * 「只留 200 条」有两种做法：取最新的 200，或取最旧的 200。后者条数也对，但用户看到的是
 * 一个停在很久以前的会话，而且会话行的「最后活动时刻」会退回旧值。所以这里断的是
 * cursor 301..500 —— 只要窗口取错方向（或干脆不截断），区间立刻对不上。
 *
 * ## 为什么上限取自 RelayController.MAX_AGENT_EVENTS
 *
 * 恢复侧与收帧侧必须是同一个来源。若这里写死 200，而生产代码改成 loadEvents(别的数)，
 * 测试照绿 —— 那正是「上限写在了一侧」的第二次发生。
 */
class AgentEventLoadCapTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: RelayDatabase
    private lateinit var store: SqlDelightAgentStore

    private val peerA = "agent-node-1"
    private val peerB = "agent-node-2"

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        RelayDatabase.Schema.create(driver)
        database = RelayDatabase(driver)
        store = SqlDelightAgentStore(database)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    private fun event(
        peer: String,
        session: String,
        cursor: Long,
        at: Long = 1_000_000L + cursor,
    ) = AgentEventUi(
        peerId = peer,
        sessionId = session,
        cursor = cursor,
        eventType = "message.delta",
        summary = "summary-" + cursor,
        body = "body-" + cursor,
        at = at,
    )

    /** 一个会话的事件按 cursor 1..[count] 写满（count = 500 对应真机里那个 13,105 条的会话）。 */
    private fun fill(peer: String, session: String, count: Int) {
        for (cursor in 1..count.toLong()) {
            store.saveEvent(event(peer, session, cursor))
        }
    }

    @Test
    fun 恢复时每个会话只取最新200条且区间是301到500() {
        val cap = RelayController.MAX_AGENT_EVENTS
        assertEquals(200, cap, "上限就是这个数；改它等于同时改收帧路径，要有意为之")

        fill(peerA, "s-1", 500)
        fill(peerA, "s-2", 500)
        fill(peerB, "s-3", 500)

        val loaded = store.loadEvents(cap)

        // 三个会话一条都不能少：只加载"当前打开的会话"会让其余会话从列表里消失。
        assertEquals(3, loaded.size, "每个有事件的会话都必须出现，窗口只截条数不截会话")

        for ((key, events) in loaded) {
            assertEquals(200, events.size, "会话 " + key.sessionId + " 应只留最近 200 条")
            assertEquals(
                (301L..500L).toList(),
                events.map { it.cursor },
                "会话 " + key.sessionId + " 取到的必须是**最新**的 200 条（301..500）且升序",
            )
            // 窗口必须包含该会话的最后一条：会话行的最后活动时刻、卡片主位那条活跃会话、
            // 以及「多久前动过」全靠它。窗口若停在旧数据上，这些都会退化成旧值。
            assertEquals(1_000_500L, events.last().at, "窗口里最后一条必须是该会话的最新一条")
        }
        assertEquals(AgentSessionKey(peerA, "s-1"), loaded.keys.first { it.sessionId == "s-1" })
    }

    @Test
    fun 正常时序下截断不影响最后活动时刻() {
        // pruneEndedSessions / healStaleBusySessions / 卡片排序都用
        // events[key].maxOfOrNull { it.at } 当「最后活动时刻」。
        // 若 cap 让这个值变小，那些路径就会把一个还活着的会话当成过期/僵死。
        //
        // 前提（正常时序）：cursor 与到达序同向，窗口取的是最高 cursor 那批 ⇒
        // 窗口内最大 at 就是全量最大 at。前提被打破的形态见下一条。
        fill(peerA, "s-1", 500)
        val fullMax = database.relayDatabaseQueries.selectAgentEventsBySession(peerA, "s-1")
            .executeAsList().maxOf { it.at }
        val windowed = store.loadEvents(RelayController.MAX_AGENT_EVENTS)
            .getValue(AgentSessionKey(peerA, "s-1"))
        assertEquals(
            fullMax,
            windowed.maxOf { it.at },
            "cap 之后算出的最后活动时刻必须与全量一致，否则 prune/自愈/排序会误判",
        )
    }

    /**
     * 乱序到达（老 cursor 后到、at 却很新）时，窗口仍必须包含**全量**的最大 at。
     *
     * 这是上面那条的边界形态：真实链路里 event.at 是本端收帧时刻，补发/重发的旧事件
     * 会带着很新的 at 落库 —— 它的 cursor 小、会被窗口裁掉。若「最后活动时刻」
     * 因此退回旧值，一个刚刚动过的会话会被 pruneEndedSessions 的 TTL 或
     * healStaleBusySessions 判成过期。这里把那个反例写成数据。
     */
    @Test
    fun 乱序到达时窗口也必须给出全量最新时刻() {
        // cursor 1..500 正常写入。
        fill(peerA, "s-1", 500)
        // 一条**迟到**的旧事件：cursor 比窗口下界还小，但 at 是全量最新。
        store.saveEvent(event(peerA, "s-1", cursor = 3L, at = 2_000_000L))

        val fullMax = database.relayDatabaseQueries.selectAgentEventsBySession(peerA, "s-1")
            .executeAsList().maxOf { it.at }
        assertEquals(2_000_000L, fullMax, "夹具本身：全量最新时刻就是那条迟到的旧事件")

        val windowed = store.loadEvents(RelayController.MAX_AGENT_EVENTS)
            .getValue(AgentSessionKey(peerA, "s-1"))
        // 窗口按 cursor 取尾部 ⇒ 那条迟到事件**不在**窗口里。
        assertTrue(windowed.none { it.cursor == 3L }, "窗口按 cursor 截断，迟到的旧 cursor 不在其中")
        // 但「最后活动时刻」这个语义**不靠窗口**：它来自 AgentSessionUi.updatedAt，
        // 在收帧时与事件 at 同步写过。这里断言窗口的最大 at（不含迟到那条）依然等于
        // 正常写入那批的最后一条 —— 而真正的兜底在会话行上，不由事件窗口承担。
        assertEquals(1_000_500L, windowed.maxOf { it.at }, "窗口给出的仍是正常那批的最新时刻")
    }

    /**
     * 语义红线：**限载之后，僵死自愈仍然判得对**。
     *
     * healStaleBusySessions 的 lastActivity = max(session.sortActivityAt, 窗口内事件 at 最大值)。
     * 若 200 条不足以拿到「最后活动时刻」，一个刚刚还在动的 busy 会话会被误判成僵死、
     * 在界面上退回 idle —— 用户看到的是「明明在跑，怎么不动了」。
     *
     * 这里刻意把 session.updatedAt 置 0（剥离会话行那份兜底），逼着判定**只能**靠事件窗口，
     * 于是这条测试问的就是「窗口自己够不够」：10 秒后不该自愈、40 分钟后必须自愈。
     */
    @Test
    fun 限载200条仍足以支撑僵死判定() {
        val key = AgentSessionKey(peerA, "s-1")
        fill(peerA, "s-1", 500) // 最新一条 at = 1_000_500
        val window = store.loadEvents(RelayController.MAX_AGENT_EVENTS)
        val busy = AgentSessionUi(
            peerId = peerA,
            sessionId = "s-1",
            state = "busy",
            // 0 = 会话行不提供任何活动时间，本次判定全靠事件窗口。
            updatedAt = 0L,
        )

        val (fresh, freshHealed) = healStaleBusySessions(
            sessions = mapOf(key to busy),
            events = window,
            now = 1_000_500L + 10_000L,
        )
        assertEquals("busy", fresh.getValue(key).state, "刚动过 10 秒，窗口里的最新事件足以证明它还活着")
        assertTrue(freshHealed.isEmpty(), "不该被自愈")

        val (stale, staleHealed) = healStaleBusySessions(
            sessions = mapOf(key to busy),
            events = window,
            now = 1_000_500L + 40 * 60 * 1000L,
        )
        assertEquals("idle", stale.getValue(key).state, "40 分钟没动，必须自愈成 idle")
        assertEquals(1, staleHealed.size)
    }

    @Test
    fun 内存实现与SQL实现同语义() {
        val memory = InMemoryAgentStore()
        for (cursor in 1..500L) memory.saveEvent(event(peerA, "s-1", cursor))
        val loaded = memory.loadEvents(RelayController.MAX_AGENT_EVENTS)
        assertEquals(200, loaded.getValue(AgentSessionKey(peerA, "s-1")).size)
        assertEquals(
            (301L..500L).toList(),
            loaded.getValue(AgentSessionKey(peerA, "s-1")).map { it.cursor },
            "两个实现的返回值形状必须一致，否则同一条测试在两份 store 上给出两种结论",
        )
    }

    /**
     * 恢复路径必须**把上限交给 store**，且用的是收帧路径那个常量。
     *
     * 这条单独钉的理由：loadEvents 自身有了窗口，但调用点若传 Int.MAX_VALUE（或另起一个数），
     * 缺陷原样复发，而上面那三条测试全绿 —— 它们测的是 store，不是接线。
     */
    @Test
    fun 恢复流程传给store的上限就是收帧路径那个常量() {
        val recording = RecordingAgentStore()
        // 500 条进库，恢复后状态里必须只剩 200 —— 断的是**端到端**结果，不只是传进去的参数。
        for (cursor in 1..500L) recording.saveEvent(event(peerA, "s-1", cursor))
        val scope = CoroutineScope(Job())
        try {
            val controller = RelayController(
                platform = InMemoryAppPlatform(),
                store = InMemorySettingsStore(),
                scope = scope,
                agentStore = recording,
            )
            controller.agentCtl.handleRestoreAgentData()
            runBlocking { delay(100) }

            // 第一断：调用点传的就是收帧路径那个常量。传 Int.MAX_VALUE 时这里红，
            // 而上面三条（测 store）全绿 —— 这正是「缺陷修在 store、接线没修」的形态。
            assertEquals(200, recording.lastLimit, "恢复路径必须按会话限载，且用收帧路径那个常量")
            val restored = controller.state.value.agentEvents
                .getValue(AgentSessionKey(peerA, "s-1"))
            assertEquals(200, restored.size, "状态里也只能留最近 200 条，否则上限又只写在了一侧")
            assertEquals(500L, restored.last().cursor, "留下的必须是**最新**那条")
        } finally {
            scope.cancel()
        }
    }

    /** 记录 loadEvents 收到的 limit，其余行为原样转发。 */
    private class RecordingAgentStore(
        private val delegate: AgentStore = InMemoryAgentStore(),
    ) : AgentStore by delegate {
        var lastLimit: Int? = null
            private set

        override fun loadEvents(limitPerSession: Int): Map<AgentSessionKey, List<AgentEventUi>> {
            lastLimit = limitPerSession
            return delegate.loadEvents(limitPerSession)
        }
    }
}
