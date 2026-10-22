// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.relay.app.model.DeliveryState
import app.relay.app.model.MessageUi
import app.relay.db.RelayDatabase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 消息游标 seq 的语义。
 *
 * 为什么值得单测：seq 是分页的唯一排序依据，它错了的三种后果都不会抛异常，
 * 只会在真机上表现为「历史消失 / 顺序乱掉 / 同一页重复」。
 *
 * 三条不变量：
 * 1. 新消息取号 = 会话内 MAX(seq)+1，从 1 起；
 * 2. **重写同一 id 必须沿用原 seq** —— 否则改一次交付状态就把消息挪到队尾，
 *    聊天记录会自己乱序（这是最容易漏的一条：INSERT OR REPLACE 是 DELETE+INSERT）；
 * 3. seq 按会话独立编号，不跨会话累加。
 */
class MessageSeqCursorTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var store: SqlDelightMessageStore

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        RelayDatabase.Schema.create(driver)
        store = SqlDelightMessageStore(RelayDatabase(driver))
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    private fun msg(id: String, peer: String, at: Long, text: String = id, delivery: DeliveryState? = null) =
        MessageUi(id = id, peerId = peer, mine = true, text = text, at = at, delivery = delivery)

    private fun seqOf(id: String): Long? = driver.executeQuery(
        null,
        "SELECT seq FROM MessageEntity WHERE id = '$id'",
        { cursor -> app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null) },
        0,
    ).value

    @Test
    fun `新消息按会话内 MAX+1 取号`() {
        store.save(mapOf("peer-1" to listOf(msg("m1", "peer-1", 1000), msg("m2", "peer-1", 2000))))
        assertEquals(1L, seqOf("m1"))
        assertEquals(2L, seqOf("m2"))

        store.save(mapOf("peer-1" to listOf(msg("m1", "peer-1", 1000), msg("m2", "peer-1", 2000), msg("m3", "peer-1", 3000))))
        assertEquals(3L, seqOf("m3"))
    }

    @Test
    fun `重写同一 id 沿用原 seq 不会挪到队尾`() {
        // 这是聊天记录乱序的防线：改交付状态时 save 会整份重写，
        // 若不沿用原 seq，m1 会拿到 MAX+1=3，从第一条跳到第三条。
        store.save(mapOf("p" to listOf(msg("m1", "p", 1000), msg("m2", "p", 2000))))
        assertEquals(1L, seqOf("m1"))

        // 改 m1 的交付状态（模拟 Sending → Sent），at 不变。
        store.save(
            mapOf(
                "p" to listOf(
                    msg("m1", "p", 1000, delivery = DeliveryState.Sent),
                    msg("m2", "p", 2000),
                ),
            ),
        )
        assertEquals(1L, seqOf("m1"), "重写同一 id 必须沿用原 seq，否则消息会挪到队尾")
        assertEquals(2L, seqOf("m2"))
    }

    @Test
    fun `seq 按会话独立编号`() {
        store.save(
            mapOf(
                "peer-a" to listOf(msg("a1", "peer-a", 1000), msg("a2", "peer-a", 2000)),
                "peer-b" to listOf(msg("b1", "peer-b", 1500)),
            ),
        )
        assertEquals(1L, seqOf("a1"))
        assertEquals(2L, seqOf("a2"))
        assertEquals(1L, seqOf("b1"), "不同会话各自从 1 起，不跨会话累加")
    }

    @Test
    fun `删除后新消息不与残留号冲突`() {
        // 删掉中间那条后，MAX(seq)+1 会跳过空洞 —— 这正确：seq 只要求唯一与有序，
        // 不要求连续。若改成 COUNT+1 就会与现存行撞 UNIQUE 索引。
        store.save(mapOf("p" to listOf(msg("m1", "p", 1000), msg("m2", "p", 2000), msg("m3", "p", 3000))))
        store.save(mapOf("p" to listOf(msg("m1", "p", 1000), msg("m3", "p", 3000))))
        store.save(mapOf("p" to listOf(msg("m1", "p", 1000), msg("m3", "p", 3000), msg("m4", "p", 4000))))
        assertEquals(4L, seqOf("m4"), "取 MAX+1 而不是 COUNT+1，否则与现存 seq 撞唯一索引")
    }
}
