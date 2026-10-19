// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.relay.db.RelayDatabase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import app.relay.app.model.MessageUi

class SqlDelightMessageStoreFtsTest {

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

    @Test
    fun chineseFullTextSearchWithTrigram() {
        val messages = mapOf(
            "peer-1" to listOf(
                MessageUi(id = "m1", peerId = "peer-1", mine = false, text = "明天下午两点在三楼开会，讨论项目重构计划", at = 1000),
                MessageUi(id = "m2", peerId = "peer-1", mine = true, text = "收到，我会准时参加开会", at = 2000),
                MessageUi(id = "m3", peerId = "peer-1", mine = false, text = "记得带上笔记本电脑和测试机", at = 3000),
            ),
            "group:g1" to listOf(
                MessageUi(id = "m4", peerId = "group:g1", mine = false, text = "大家注意，今天代码审查已通过", at = 4000),
            ),
        )

        store.save(messages)

        // 1. 测试 2 字词匹配 ("开会")
        val searchKaiHui = store.search("开会")
        assertEquals(2, searchKaiHui.size, "应命中两条包含'开会'的消息")
        assertTrue(searchKaiHui.any { it.id == "m1" })
        assertTrue(searchKaiHui.any { it.id == "m2" })

        // 2. 测试 3 字词及以上短语匹配 ("项目重构")
        val searchChongGou = store.search("项目重构")
        assertEquals(1, searchChongGou.size)
        assertEquals("m1", searchChongGou.first().id)

        // 3. 测试群聊消息匹配 ("代码审查")
        val searchReview = store.search("代码审查")
        assertEquals(1, searchReview.size)
        assertEquals("m4", searchReview.first().id)
        assertEquals("group:g1", searchReview.first().convId)

        // 4. 测试未命中
        val searchNone = store.search("不存在的关键词")
        assertTrue(searchNone.isEmpty())
    }

    /**
     * **索引条目数必须等于消息数**（真机缺陷的回归门）。
     *
     * 现场：真实消息 941 条，`MessageFts` 里却有 **4,554,999** 条，库长到 **21 GB**。
     * 两个成因：① `INSERT OR REPLACE` 的隐式删除**不触发** DELETE 触发器 ⇒ 每次
     * 重写同一条消息就留一个孤儿；② `updateDelivery` 只改状态，但 AFTER UPDATE 触发器
     * 无条件重建索引 ⇒ 每次状态变化再留一个。
     *
     * 这里反复重写同一批消息（含正文变化与纯状态变化两种），要求索引条目数始终 = 消息数。
     * 这条如果变红，说明索引又在积累孤儿 —— 那正是把磁盘吃光的那个 bug。
     */
    @Test
    fun rewritingMessagesDoesNotAccumulateOrphanIndexRows() {
        fun indexCount(): Long = driver.executeQuery(
            null,
            "SELECT count(*) FROM MessageFts_docsize",
            { cursor -> app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getLong(0)!! else 0L) },
            0,
        ).value

        fun messageCount(): Long = driver.executeQuery(
            null,
            "SELECT count(*) FROM MessageEntity",
            { cursor -> app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getLong(0)!! else 0L) },
            0,
        ).value

        val base = MessageUi(id = "m1", peerId = "peer-1", mine = false, text = "第一版正文", at = 1000)
        store.save(mapOf("peer-1" to listOf(base)))
        assertEquals(1L, messageCount())
        assertEquals(1L, indexCount(), "首次插入：一条消息对应一条索引")

        // ① 同 id 重写 30 次（模拟会话每次落盘都会把整段线程重写一遍）。
        repeat(30) { i ->
            store.save(mapOf("peer-1" to listOf(base.copy(text = "第 " + i + " 版正文"))))
        }
        assertEquals(1L, messageCount())
        assertEquals(
            1L,
            indexCount(),
            "重写 30 次后仍必须只有 1 条索引（OR REPLACE 的隐式删除不触发触发器，这里靠显式清理）",
        )

        // ② 只改 delivery（正文没变）：不该产生任何新索引条目。
        repeat(10) {
            store.save(
                mapOf(
                    "peer-1" to listOf(
                        base.copy(text = "第 29 版正文", delivery = app.relay.app.model.DeliveryState.Sent),
                    ),
                ),
            )
        }
        assertEquals(1L, messageCount())
        assertEquals(1L, indexCount(), "只改发送状态不该重建索引")

        // 正文改了之后，检索必须命中**新**内容，且不命中旧内容。
        store.save(mapOf("peer-1" to listOf(base.copy(text = "全新的内容在这里"))))
        assertEquals(1, store.search("全新的内容").size, "改过正文后必须能搜到新内容")
        assertTrue(store.search("第 29 版正文").isEmpty(), "旧正文必须从索引里消失")
    }
    @Test
    fun smoothMigrationFromLegacyJsonStore() {
        val legacyData = mapOf(
            "peer-old" to listOf(
                MessageUi(id = "old-1", peerId = "peer-old", mine = false, text = "这是从旧版迁移过来的历史消息", at = 500),
            ),
        )

        val legacyMock = object : MessageStore {
            var cleared = false
            override fun load(): Map<String, List<MessageUi>> = legacyData
            override fun save(threads: Map<String, List<MessageUi>>) = Unit
            override fun clear() { cleared = true }
        }

        // 新 store 注入旧 legacyMock
        val migratingStore = SqlDelightMessageStore(database, legacyStore = legacyMock)

        // 首次 load 触发平滑迁移
        val loaded = migratingStore.load()
        assertEquals(1, loaded["peer-old"]?.size)
        assertEquals("这是从旧版迁移过来的历史消息", loaded["peer-old"]?.first()?.text)
        assertTrue(legacyMock.cleared, "导入完成后旧存储应被清理")

        // 迁移后即可享受全文检索能力
        val searchResults = migratingStore.search("历史消息")
        assertEquals(1, searchResults.size)
        assertEquals("old-1", searchResults.first().id)
    }
}
