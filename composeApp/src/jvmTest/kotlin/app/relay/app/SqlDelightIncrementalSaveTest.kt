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
        // 默认与被测会话一致。注意 save() 里 conv_id 取自**消息自身**的 peerId、
        // 而 peer_id 取自 map 的键 —— 两者不一致时那一行会自相矛盾，
        // 所以夹具必须让它们对上（真实调用点也是这么用的）。
        peer: String = "peer-1",
    ) = MessageUi(
        id = id,
        peerId = peer,
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
    fun loadRecent_只取每个会话最近的N条且保留全部会话() {
        // 这条钉两件事，它们直接决定会话列表会不会失真：
        // ① 每个有消息的会话都必须出现（ConversationUi 的 id 集合来自 messages.keys，
        //    漏一个就是列表少一行）；
        // ② 窗口必须是**尾部**，即最后一条等于真实最后一条（预览、排序、时间全靠它）。
        val a = (1..250).map { msg("a" + it, at = 1000L * it, peer = "a") }
        val b = (1..3).map { msg("b" + it, at = 1000L * it, peer = "b") }
        store.save(mapOf("a" to a, "b" to b))

        val loaded = store.loadRecent(10)
        assertEquals(setOf("a", "b"), loaded.keys, "有消息的会话一个都不能少")
        assertEquals(10, loaded.getValue("a").size, "超过窗口的会话只给最近 N 条")
        assertEquals(3, loaded.getValue("b").size, "不足 N 条时应当全给")
        assertEquals("a250", loaded.getValue("a").last().id, "窗口必须是尾部，否则会话预览与排序全歪")
        assertEquals("b3", loaded.getValue("b").last().id)
        assertEquals(
            loaded.getValue("a").map { it.at }.sorted(),
            loaded.getValue("a").map { it.at },
            "组内仍按时间升序 —— UI 直接按这个顺序画",
        )
    }

    @Test
    fun load_仍然是全量() {
        // 分页是**新增**能力，不是把 load() 改小：
        // 导出 / 迁移 / 测试夹具仍要能拿到全部历史。
        val all = (1..40).map { msg("m" + it, at = 1000L * it) }
        store.save(mapOf("peer-1" to all))
        assertEquals(40, store.load().getValue("peer-1").size)
        assertEquals(5, store.loadRecent(5).getValue("peer-1").size)
    }

    @Test
    fun loadOlder_按seq游标取更早的一页且不重复不遗漏() {
        // 游标必须无重无漏 —— 用时间戳当游标时，同毫秒的多条会重复或跳过。
        val all = (1..30).map { msg("m" + it, at = 1000L * it) }
        store.save(mapOf("peer-1" to all))

        val window = store.loadRecent(10).getValue("peer-1")
        assertEquals((21..30).map { "m" + it }, window.map { it.id }, "首屏应当是最近 10 条")

        val page1 = store.loadOlder("peer-1", window.first().seq!!, 10)
        assertEquals((11..20).map { "m" + it }, page1.map { it.id }, "上一页必须正好接在前面，不重不漏")

        val page2 = store.loadOlder("peer-1", page1.first().seq!!, 10)
        assertEquals((1..10).map { "m" + it }, page2.map { it.id })

        assertEquals(0, store.loadOlder("peer-1", page2.first().seq!!, 10).size, "到头时必须返回空，界面据此不再谎报还有更多")
    }

    @Test
    fun 摘要表始终等于从消息推导的结果() {
        // 不变量：ConversationSummary 与「拿 load() 全量消息自己算一遍」必须一致。
        // 这是摘要表唯一需要的正确性判据 —— 它错的表现是「会话预览停在旧消息」，
        // 而那类缺陷只在真机上、只在某个特定操作后才出现（漏写一条同步路径）。
        fun expected(): Map<String, Pair<String?, Long>> =
            store.load().mapValues { (_, list) ->
                val last = list.last()
                last.text.takeIf { it.isNotBlank() } to last.at
            }

        fun actual(): Map<String, Pair<String?, Long>> =
            store.loadSummaries().associate { it.convId to (it.lastTextPreview to it.lastMessageAt) }

        fun assertConsistent(step: String) {
            assertEquals(expected(), actual(), "摘要表与消息不一致（$step）")
        }

        // ① 新写入
        store.save(
            mapOf(
                "peer-1" to listOf(msg("m1", text = "第一条", at = 1000), msg("m2", text = "最后一条", at = 2000)),
                "peer-2" to listOf(msg("n1", text = "另一个会话", at = 1500, peer = "peer-2")),
            ),
        )
        assertConsistent("首次写入")

        // ② 改写最后一条（预览要跟着变）
        store.save(mapOf("peer-1" to listOf(msg("m1", text = "第一条", at = 1000), msg("m2", text = "改过的最后一条", at = 2000))))
        assertConsistent("改写最后一条")

        // ③ 删掉最后一条（预览必须退回上一条，而不是停在被删的那条）
        store.deleteMessage("m2")
        assertConsistent("删除最后一条")

        // ④ 删空整个会话（摘要行必须消失，否则列表里留一个空行）
        store.deleteConversation("peer-1")
        assertConsistent("删空会话")
        assertEquals(listOf("peer-2"), store.loadSummaries().map { it.convId }, "空会话不该留摘要行")
    }

    @Test
    fun save_不再按快照反推删除() {
        // 这条钉的是**契约变更**，不是实现细节：
        // 旧 save() 扫全表、把「内存里没有的 id」DELETE 掉。内存是全部历史时那是对的；
        // 一旦内存只保留一个窗口，它就会把窗口外的历史整批删掉 —— 数据丢失。
        // 所以删除改成显式 API，save() 只做 upsert。
        // 若有人把反推删除加回来，这条会红 —— 那时分页一上线就会丢历史。
        store.save(mapOf("peer-1" to listOf(msg("m1"), msg("m2", at = 2000))))
        assertEquals(2, rows().size)

        store.save(mapOf("peer-1" to listOf(msg("m1"))))
        assertEquals(
            listOf("m1", "m2"),
            rows().map { it.id },
            "save 不该从「快照里没有」反推删除 —— 分页之后那等于删掉窗口之外的全部历史",
        )
    }

    @Test
    fun 显式_deleteMessage_才真的删掉() {
        store.save(mapOf("peer-1" to listOf(msg("m1"), msg("m2", at = 2000))))
        store.deleteMessage("m2")
        assertEquals(listOf("m1"), rows().map { it.id }, "显式删除必须真的落到库里")
    }

    @Test
    fun 显式_deleteConversation_删干净整个会话() {
        store.save(
            mapOf(
                "peer-1" to listOf(msg("m1")),
                "peer-2" to listOf(msg("n1", at = 500, peer = "peer-2")),
            ),
        )
        store.deleteConversation("peer-1")
        assertEquals(0, rows().size, "整个会话的消息必须一次删干净")
        assertEquals(1, database.relayDatabaseQueries.selectAllMessages().executeAsList().size, "别的会话不能被连坐")
    }
}
