// SPDX-License-Identifier: GPL-3.0-only

package app.relay.db

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.relay.app.HistorySearchFilter
import app.relay.app.model.MessageUi
import app.relay.app.SqlDelightMessageStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `FtsTolerantSchema` 的存在理由只有一条：**没有 fts5 的机器上，消息表也必须建得出来。**
 *
 * 真机（Android，2026-10-09）上 `RelayDatabase.Schema.create` 在
 * `CREATE VIRTUAL TABLE MessageFts … USING fts5(…)` 上抛 `no such module: fts5`，
 * 整个建库事务随之回滚 —— 连 `MessageEntity` 都没有，于是聊天记录从不落盘、
 * 检索恒 0 条，而所有异常都被 `runCatching` 吞掉，外部完全看不到。
 *
 * 这里注入 `fts5Available = { false }` 复现那台机器的能力，断言：
 * 1. 消息表建出来了（而 `MessageFts` 没建）；
 * 2. 保存 / 读取 / 检索（3 字符以上，本来该走 FTS 的那条路）全都работает，走的是 LIKE 兜底；
 * 3. 默认探针在 JVM 上为真（xerial 带 fts5）—— 也就是桌面那条路一行都没少。
 */
class FtsTolerantSchemaTest {

    private fun names(driver: SqlDriver): List<String> {
        val names = mutableListOf<String>()
        driver.executeQuery(
            identifier = null,
            sql = "SELECT name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY name",
            mapper = { cursor ->
                while (cursor.next().value) names += cursor.getString(0).orEmpty()
                QueryResult.Value(Unit)
            },
            parameters = 0,
        ).value
        return names
    }

    private fun assertFtsObjectsAbsent(driver: SqlDriver) {
        val all = names(driver)
        assertFalse(all.contains(MessageFts.NAME), "没有 fts5 时不该建出 $all")
        assertTrue(all.contains("MessageEntity"), "消息表必须建出来，实际是 $all")
        assertTrue(all.contains("AgentSessionEntity"), "Agent 表也必须建出来，实际是 $all")
    }

    @Test
    fun createsMessageTablesWhenFts5IsUnavailable() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            FtsTolerantSchema(fts5Available = { false }).create(driver)
            assertFtsObjectsAbsent(driver)
        } finally {
            driver.close()
        }
    }

    @Test
    fun savesLoadsAndSearchesWithoutFts5() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            FtsTolerantSchema(fts5Available = { false }).create(driver)
            val store = SqlDelightMessageStore(RelayDatabase(driver))

            store.save(
                mapOf(
                    "peer-1" to listOf(
                        MessageUi(id = "m1", peerId = "peer-1", mine = false, text = "明天下午两点在三楼开会", at = 1000),
                        MessageUi(id = "m2", peerId = "peer-1", mine = true, text = "记得带上测试机", at = 2000),
                    ),
                ),
            )

            // 读取：这正是真机上"重启后历史全丢"的那一步。
            assertEquals(2, store.load()["peer-1"]?.size)

            // 检索：4 个字，本来是 FTS5 trigram 那条路；这台机器上必须落到 LIKE。
            val hits = store.search("三楼开会", limit = 20)
            assertEquals(listOf("m1"), hits.map { it.id })
            // FTS 的 snippet() 会给命中词包上【】；没有它说明确实走的 LIKE 兜底，
            // 而不是"碰巧 fts5 又在了"。
            assertFalse(hits.first().snippetText.contains("【"), "LIKE 路径不该带 FTS 高亮：${hits.first().snippetText}")

            // 全局分组检索（会话列表那一步）同样不许空。
            assertEquals(1, store.searchGrouped("测试机", limit = 20).size)
            // 会话内检索同样要走通。
            assertEquals(
                listOf("m1"),
                store.searchInConversation(
                    convId = "peer-1",
                    query = "三楼开会",
                    filter = HistorySearchFilter(),
                    limit = 20,
                ).map { it.id },
            )
        } finally {
            driver.close()
        }
    }

    @Test
    fun defaultProbeFindsFts5OnJvm() {
        // 不注入：走真探针。JVM 这份 sqlite-jdbc 带 fts5，所以虚拟表应当真的建出来 ——
        // 这同时证明"探针说有就原样建"这一半没退化。
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            FtsTolerantSchema().create(driver)
            val all = names(driver)
            assertTrue(all.contains(MessageFts.NAME), "JVM 上有 fts5，虚拟表应当建出来，实际是 $all")
            assertTrue(all.contains("MessageEntity"), "实际是 $all")
        } finally {
            driver.close()
        }
    }

    @Test
    fun skippingIsNarrowToTheFtsTable() {
        // 兜底逻辑不能宽到"凡是带 MessageFts 字样以外的错也吞"：
        // 建库失败时必须让异常照旧抛出去（否则又回到"静默什么都查不到"）。
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            val broken = object : SqlSchema<QueryResult.Value<Unit>> {
                override val version: Long = 1
                override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
                    driver.execute(null, "CREATE TABLE message_entity (id TEXT)", 0)
                    driver.execute(null, "CREATE TABLE totally_broken (", 0)
                    return QueryResult.Value(Unit)
                }

                override fun migrate(
                    driver: SqlDriver,
                    oldVersion: Long,
                    newVersion: Long,
                    vararg callbacks: AfterVersion,
                ): QueryResult.Value<Unit> = QueryResult.Value(Unit)
            }

            val failure = runCatching {
                FtsTolerantSchema(delegate = broken, fts5Available = { false }).create(driver)
            }.exceptionOrNull()
            assertTrue(failure != null, "非 MessageFts 的错误必须照旧抛出来")
        } finally {
            driver.close()
        }
    }
}
