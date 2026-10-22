// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.relay.app.model.DeliveryState
import app.relay.app.model.MessageUi
import app.relay.app.model.NoticeKind
import app.relay.db.RelayDatabase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * save() 的增量性：没变的消息**一次写都不该发生**。
 *
 * 为什么这条测试必须存在：save() 是「整份快照」调用，而 handlePersistThreads 在
 * **每次消息变化**（含 Sending 到 Sent 这种只改一个枚举的）之后都会调它。
 * 真机库 6656 条，无条件重写就是 6656 次「删旧 FTS 索引 + INSERT + 触发器」，
 * 其中 6655 次是白写 —— 这是磁盘写入与界面卡顿的直接来源。
 *
 * 判据用 SQLite 的 total_changes()（连接级累计改行数），不用业务层计数器：
 * 它数的是**真的动了几行**，含触发器。若 save() 退化成全量重写，
 * 「没变化时增量为 0」这条立刻变红。
 */
class SqlDelightIncrementalSaveTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: RelayDatabase
    private lateinit var store: SqlDelightMessageStore

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        RelayDatabase.Schema.create(driver)
        database = RelayDatabase(driver)
        store = SqlDelightMessageStore(database)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    /** 连接级累计改行数（含触发器改的行）。 */
    private fun totalChanges(): Long = driver.executeQuery(
        identifier = null,
        sql = "SELECT total_changes()",
        mapper = { cursor ->
            QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L)
        },
        parameters = 0,
    ).value

    private fun msg(
        id: String,
        text: String = "正文",
        at: Long = 1000,
        delivery: DeliveryState? = null,
    ) = MessageUi(
        id = id,
        peerId = "peer-1",
        mine = true,
        text = text,
        at = at,
        delivery = delivery,
    )

    private fun rows() = database.relayDatabaseQueries.selectMessagesByConv("peer-1").executeAsList()

    @Test
    fun 整份快照没变化时一次写入都不发生() {
        val snapshot = listOf(msg("m1"), msg("m2", at = 2000), msg("m3", at = 3000))
        store.save(mapOf("peer-1" to snapshot))

        val before = totalChanges()
        // 一字不改地再存一次 —— 这就是「收到一条无关消息后整份重存」的形态。
        store.save(mapOf("peer-1" to snapshot))
        val delta = totalChanges() - before

        assertEquals(0L, delta, "没有一条消息变化时不该产生任何写入（含 FTS 触发器），实测多写 " + delta + " 行")
    }

    @Test
    fun 只改一条交付状态就只动那一条() {
        // 量具说明：total_changes() 数的是**改了多少行**，而写一条消息会连带触发
        // FTS5 的 delete 命令（动 data/idx/docsize 三张影子表）+ 实体行 REPLACE
        // + message_after_insert 触发器，所以单条成本是个小常数（实测约 7），
        // 不是 1。因此这里**不比绝对值，比相对量**：改 1 条 与 改 5 条 的比值。
        // 全量重写时两者相等；增量成立时前者应显著小于后者。
        val snapshot = (1..5).map { msg("m" + it, at = 1000L * it) }
        store.save(mapOf("peer-1" to snapshot))

        val base = totalChanges()
        store.save(
            mapOf(
                "peer-1" to snapshot.map { if (it.id == "m3") it.copy(delivery = DeliveryState.Sent) else it },
            ),
        )
        val oneChanged = totalChanges() - base

        val base2 = totalChanges()
        store.save(
            mapOf(
                "peer-1" to snapshot.map { it.copy(delivery = DeliveryState.Sent) },
            ),
        )
        val allChanged = totalChanges() - base2

        assertTrue(oneChanged > 0L, "交付状态变了必须真的落库，否则界面一直显示旧徽标")
        assertTrue(allChanged > 0L, "5 条全改时必须都落库")
        assertTrue(
            oneChanged * 2 < allChanged,
            "改 1 条动了 " + oneChanged + " 行、改 5 条动了 " + allChanged + " 行 —— 两者不成比例，增量失效了",
        )
    }

    @Test
    fun 每个会被写入的字段都参与比对() {
        // 防的是 matches() 漏比某一列：那种缺陷的症状是「改了这个字段永远存不进去」，
        // 而且只在真机上、只在改那个特定字段时才出现。下面逐列改一次，每次都必须落库。
        val base = msg("m1")
        store.save(mapOf("peer-1" to listOf(base)))

        val mutations: List<Pair<String, MessageUi>> = listOf(
            "text" to base.copy(text = "改过的正文"),
            "at" to base.copy(at = 9999),
            "delivery" to base.copy(delivery = DeliveryState.Sent),
            "senderId" to base.copy(senderId = "sender-1"),
            "notice" to base.copy(notice = NoticeKind.GroupRenamed),
            "agentSessionId" to base.copy(agentSessionId = "sess-1"),
            "agentSessionTitle" to base.copy(agentSessionTitle = "任务：改过的标题"),
        )
        for ((field, mutated) in mutations) {
            val before = totalChanges()
            store.save(mapOf("peer-1" to listOf(mutated)))
            val delta = totalChanges() - before
            assertTrue(delta > 0L, "改动字段 " + field + " 之后没有任何写入 —— matches 漏比了这一列")
            // 再存一次同样的内容必须归零（证明上一步写的是「已是最新」的状态）。
            val steady = totalChanges()
            store.save(mapOf("peer-1" to listOf(mutated)))
            assertEquals(0L, totalChanges() - steady, "字段 " + field + " 写进去后仍判为有变化，比对没有收敛")
        }
    }

    @Test
    fun 删除的消息真的从库里消失() {
        // 跳过逻辑不能顺手把「删除」也吞掉：那会让用户「删了还在」（本仓真机上出现过）。
        store.save(mapOf("peer-1" to listOf(msg("m1"), msg("m2", at = 2000))))
        assertEquals(2, rows().size)

        store.save(mapOf("peer-1" to listOf(msg("m1"))))
        assertEquals(listOf("m1"), rows().map { it.id }, "快照里没有的那条必须被删掉")
    }
}
