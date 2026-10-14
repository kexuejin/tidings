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
