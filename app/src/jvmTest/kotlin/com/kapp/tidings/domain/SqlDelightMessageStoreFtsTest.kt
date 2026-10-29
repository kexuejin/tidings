// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.kapp.tidings.db.RelayDatabase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.kapp.tidings.domain.model.MessageUi

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
                        base.copy(text = "第 29 版正文", delivery = com.kapp.tidings.domain.model.DeliveryState.Sent),
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
    /**
     * **迁移必须把修好的触发器与索引带给已存在的库。**
     *
     * 为什么单独钉这一条：`.sq` 里的建表语句只在新建库时执行，而 3.sqm 建的是
     * **无条件**版 `AFTER UPDATE`（任何 UPDATE 都重建一遍索引）并用 `IF NOT EXISTS` ——
     * 已存在的库永远不会换成修好的那一版。真机上这条把索引堆到 4,554,999 行 / 21 GB，
     * 而**装新版本只换了代码、没换库**。
     */
    @Test
    fun theMigrationReplacesTheOldTriggerAndRebuildsTheIndex() {
        fun scalar(sql: String): Long = driver.executeQuery(
            null,
            sql,
            { cursor -> app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getLong(0)!! else 0L) },
            0,
        ).value

        // ① 把库伪装成「还带着 3.sqm 那条无条件触发器」的旧库。
        driver.execute(null, "DROP TRIGGER IF EXISTS message_after_update", 0)
        // **还要把后续迁移加的列删掉**：`Schema.create` 用的是**最新** .sq，
        // 里面已经有 `agent_session_id`（11.sqm 补的）。而下面只跑最后一条迁移，
        // 那条是 `ALTER TABLE ADD COLUMN` —— 列已存在就撞 duplicate column。
        //
        // 这是夹具的假设问题，不是迁移的缺陷：生产路径按 `user_version` 只跑一次，
        // 老库上那一列确实不存在。夹具要模拟的是**老库**，所以得先退回旧形状。
        // 每加一条 `ALTER TABLE ADD COLUMN` 迁移，这里就要多退一列 —— 否则下面只跑
        // 「最后一条迁移之后」的那一段时，列已存在 ⇒ duplicate column name。
        runCatching { driver.execute(null, "ALTER TABLE MessageEntity DROP COLUMN agent_session_id", 0) }
        runCatching { driver.execute(null, "ALTER TABLE MessageEntity DROP COLUMN agent_session_title", 0) }
        runCatching { driver.execute(null, "ALTER TABLE MessageEntity DROP COLUMN notice_detail", 0) }
        // 16.sqm 的 card_json / agent_state 同理（声明式卡片与执行状态，方案 §4.3）。
        runCatching { driver.execute(null, "ALTER TABLE MessageEntity DROP COLUMN card_json", 0) }
        runCatching { driver.execute(null, "ALTER TABLE MessageEntity DROP COLUMN agent_state", 0) }
        // 14.sqm 的 seq 同理：最新 .sq 建表已含它，退回旧形状时也要退掉。
        // 索引必须先删 —— SQLite 不允许删一个被索引引用的列。
        runCatching { driver.execute(null, "DROP INDEX IF EXISTS message_conv_seq", 0) }
        runCatching { driver.execute(null, "ALTER TABLE MessageEntity DROP COLUMN seq", 0) }
        driver.execute(
            null,
            """
            CREATE TRIGGER message_after_update AFTER UPDATE ON MessageEntity BEGIN
                INSERT INTO MessageFts(MessageFts, rowid, text) VALUES ('delete', old.rowid, old.text);
                INSERT INTO MessageFts(rowid, text) VALUES (new.rowid, new.text);
            END
            """.trimIndent(),
            0,
        )

        // ② 制造孤儿：用**旧 app 的写法**（裸 `INSERT OR REPLACE`，没有显式清理）。
        //    走 store.save 是抓不到的 —— 它现在会先执行 insertMessageDropStaleIndex，
        //    那正是这次修复的另一半；要复现历史形态就必须绕开它。
        val insert = "INSERT OR REPLACE INTO MessageEntity " +
            "(id, conv_id, peer_id, sender_id, mine, text, timestamp, delivery, notice, file_json, screen_json) " +
            "VALUES ('m1','peer-1','peer-1',NULL,0,?,1000,NULL,NULL,NULL,NULL)"
        repeat(20) { i ->
            driver.execute(null, insert, 1) { bindString(0, "第 " + i + " 版正文") }
        }
        val messages = scalar("SELECT count(*) FROM MessageEntity")
        val orphans = scalar("SELECT count(*) FROM MessageFts_docsize")
        assertTrue(
            orphans > messages,
            "旧触发器必须真的堆出孤儿，否则这条测试什么也没证明（消息 " + messages + " / 索引 " + orphans + "）",
        )

        // ③ 从**修触发器的那条迁移之前**跑到最新。
        //
        // 这里原本写的是 `migrate(latest - 1, latest)` —— 假设「最后一条迁移就是修触发器
        // 的那条（10.sqm）」。那条注释还特意提醒过写死数字会测到别的东西，但没防住
        // 「最后一条换了职责」：11.sqm（给 MessageEntity 补 agent_session_id）加进来之后，
        // 这段就只跑 11.sqm，触发器根本没被重建，索引停在 20 条 —— 测试红得莫名其妙。
        //
        // 改成从 10 起：**覆盖 10.sqm 与它之后的每一条**，无论后面再加多少条。
        // 10 是「修触发器」那条迁移的源版本（9 个迁移 ⇒ 版本 10），写死它是有意的：
        // 这一条测试关心的就是那一次修复，往前的历史迁移与它无关。
        RelayDatabase.Schema.migrate(driver, 10, RelayDatabase.Schema.version)

        assertEquals(
            messages,
            scalar("SELECT count(*) FROM MessageFts_docsize"),
            "迁移必须把索引修回「一条消息一条索引」",
        )
        val triggerSql = driver.executeQuery(
            null,
            "SELECT sql FROM sqlite_master WHERE name='message_after_update'",
            { cursor -> app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getString(0).orEmpty() else "") },
            0,
        ).value
        assertTrue(
            triggerSql.contains("WHEN old.text IS NOT new.text"),
            "迁移必须换掉触发器，实际是：" + triggerSql,
        )
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

    /**
     * **删掉一条消息后，FTS 索引必须同步缩小。**
     *
     * 这是「单条本地删除」（`RelayControllerMessagesComponent.handleDeleteMessage`）的
     * 存储侧不变量。AGENTS.md §2.3 记着真机事故：索引与实体行数不一致把库撑到 21 GB。
     * 删除入口是**显式的** `deleteMessage`（`save()` 已不再按快照反推删除 ——
     * 那在分页之后等于把窗口之外的历史整批删掉）。它靠 `message_after_delete`
     * 触发器清索引，这里钉住"它真的清了"，而不是"我们相信它会清"。
     */
    @Test
    fun deletingAMessageShrinksTheFtsIndex() {
        store.save(
            mapOf(
                "peer-1" to listOf(
                    MessageUi(id = "d1", peerId = "peer-1", mine = false, text = "这条要被删掉的会议纪要", at = 1000),
                    MessageUi(id = "d2", peerId = "peer-1", mine = false, text = "这条要留下来", at = 2000),
                ),
            ),
        )
        assertEquals(1, store.search("会议纪要").size, "删除前应当搜得到")

        // 显式删除 d1 —— 这就是 handleDeleteMessage 现在走的路径。
        store.deleteMessage("d1")

        assertEquals(0, store.search("会议纪要").size, "删掉的那条不能再被搜到（索引里有残留就是孤儿）")
        assertEquals(1, store.search("留下来").size, "没删的那条仍要搜得到")

        // **行数不变量**：实体行数 ≡ 索引 docsize 行数。
        // 这条断言能区分"真删了索引"与"只是搜不到"（后者可能仍留着 docsize 行）。
        val entityRows = entityRowIds()
        assertEquals(1, entityRows.size, "实体表应当只剩一条")
        assertEquals(entityRows, ftsRowIds(), "FTS 里必须只剩同一条 —— 行数不等就是 AGENTS.md §2.3 那类孤儿索引")
    }

    /** 实体表的 rowid 集合。 */
    private fun entityRowIds(): Set<Long> =
        driver.executeQuery(null, "SELECT rowid FROM MessageEntity", { cursor ->
            app.cash.sqldelight.db.QueryResult.Value(buildSet(cursor))
        }, 0).value

    /** FTS 索引的 rowid 集合（`MessageFts` 是外部内容表，行数就是索引行数）。 */
    private fun ftsRowIds(): Set<Long> =
        driver.executeQuery(null, "SELECT rowid FROM MessageFts", { cursor ->
            app.cash.sqldelight.db.QueryResult.Value(buildSet(cursor))
        }, 0).value

    private fun buildSet(cursor: app.cash.sqldelight.db.SqlCursor): Set<Long> {
        val out = mutableSetOf<Long>()
        while (cursor.next().value) out += cursor.getLong(0)!!
        return out
    }
}
