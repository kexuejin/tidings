// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.kapp.tidings.db.RelayDatabase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.kapp.tidings.domain.model.FileCardUi
import com.kapp.tidings.domain.model.MessageUi

/**
 * 会话内检索、按会话聚合、按文件名检索这三条**新**路径。
 *
 * 与 `SqlDelightMessageStoreFtsTest`（同一装置，验的是既有那条全局全文检索）分开，
 * 因为这里验的每一件事都有一条"在 Kotlin 里顺手筛一下"的等价写法，而那种写法
 * 在**小数据上完全正确**、只在数据量大时错：
 *
 * - 日期/类型条件若在 Kotlin 里筛，`LIMIT` 会先作用在**未筛**的结果上，
 *   于是「筛图片」实际是「最近 50 条里恰好是图片的那几条」。用例
 *   [类型筛选发生在LIMIT之前] 就是造 60 条文本 + 1 条老图片来钉这一条。
 * - 「聊天记录」段的条数若靠取回明细再数，一个几百条的会话永远显示「50 条相关」，
 *   而 50 是 LIMIT 的值。用例 [聚合的条数来自全部命中而不是被截断的那一段] 钉这一条。
 * - 文件名若只靠 `file_json LIKE`，`savedPaths` 里的路径也会命中。
 *   用例 [按文件名检索不会命中落在路径里的关键词] 钉这一条。
 */
class SqlDelightMessageStoreSearchTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var store: SqlDelightMessageStore

    private val lin = "peer-lin"
    private val zhou = "peer-zhou"

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

    private fun text(id: String, conv: String, body: String, at: Long) =
        MessageUi(id = id, peerId = conv, mine = false, text = body, at = at)

    private fun file(id: String, conv: String, name: String, at: Long, purpose: String? = null, savedPaths: List<String> = emptyList()) =
        MessageUi(
            id = id,
            peerId = conv,
            mine = false,
            text = "",
            at = at,
            file = FileCardUi(
                transferId = "t-$id",
                name = name,
                size = 1024L,
                purpose = purpose,
                savedPaths = savedPaths,
            ),
        )

    // ---------------------------------------------------------------- 会话内：圈定会话

    @Test
    fun 会话内检索不跨会话() {
        store.save(
            mapOf(
                lin to listOf(text("m1", lin, "协议 v2 定稿了", 1000)),
                zhou to listOf(text("m2", zhou, "协议 v3 我再看看", 2000)),
            ),
        )

        val inLin = store.searchInConversation(lin, "协议")

        assertEquals(1, inLin.size, "会话内检索只应该看到本会话的消息")
        assertEquals("m1", inLin.single().id)
    }

    @Test
    fun 会话内检索带附件时退回文件名当摘要() {
        // 文件消息的 text 是空串（见 upsertTransferCard），FTS 的 snippet 因此也是空 ——
        // 不退回文件名的话，会话内搜到一个文件会显示成一行空白。
        store.save(mapOf(lin to listOf(file("m1", lin, "需求文档-协议-v2.pdf", 1000))))

        val hit = store.searchInConversation(lin, "需求文档").single()

        assertEquals("需求文档-协议-v2.pdf", hit.snippetText)
        assertEquals("需求文档-协议-v2.pdf", hit.fileName)
        assertFalse(hit.isImage, "pdf 不是图片")
    }

    @Test
    fun 类型筛选发生在LIMIT之前() {
        // 60 条含关键词的文本消息 + 一条很老的图片。
        // 若类型条件写在 Kotlin 里（先按 LIMIT 取 50 条、再筛图片），那条图片永远取不到 ——
        // 库里明明有图，用户搜出来却是空列表。
        val many = (1..60).map { text("t$it", lin, "协议相关第 $it 条", 1000L + it) }
        val old = file("img", lin, "协议草图.png", 10L)
        store.save(mapOf(lin to (many + old)))

        val images = store.searchInConversation(lin, "协议", HistorySearchFilter(kind = HistorySearchKind.Image))

        assertEquals(1, images.size, "条件必须进 SQL，否则会被 LIMIT 截掉")
        assertEquals("img", images.single().id)
        assertTrue(images.single().isImage)
    }

    @Test
    fun 文件档排除图片() {
        store.save(
            mapOf(
                lin to listOf(
                    file("img", lin, "协议草图.png", 3000),
                    file("pdf", lin, "协议文档.pdf", 2000),
                ),
            ),
        )

        val docs = store.searchInConversation(lin, "协议", HistorySearchFilter(kind = HistorySearchKind.File))

        assertEquals(listOf("pdf"), docs.map { it.id }, "图片有自己那一档，不能两边都算")
    }

    @Test
    fun 日期区间是闭的且只筛日期() {
        store.save(
            mapOf(
                lin to listOf(
                    text("m1", lin, "协议 第一天", 1000),
                    text("m2", lin, "协议 第二天", 2000),
                    text("m3", lin, "协议 第三天", 3000),
                ),
            ),
        )

        val mid = store.searchInConversation(
            lin,
            "协议",
            HistorySearchFilter(fromAt = 2000, toAt = 3000),
        )

        assertEquals(listOf("m3", "m2"), mid.map { it.id }, "两端都算在内，且按时间倒序")
    }

    @Test
    fun 两个字的词走LIKE也能在会话内命中() {
        // < 3 字符时 FTS5 的 trigram 分词器匹配不到任何东西（trigram 是三个字符一组的），
        // 所以必须降级成 LIKE —— 这一条在全局那条路径上已经验过，会话内是新写的分支。
        store.save(mapOf(lin to listOf(text("m1", lin, "开会", 1000))))

        assertEquals(listOf("m1"), store.searchInConversation(lin, "开会").map { it.id })
    }

    // ---------------------------------------------------------------- 全局：按会话聚合

    @Test
    fun 聚合的条数来自全部命中而不是被截断的那一段() {
        // 一个会话里 80 条命中。若实现是"取回明细再数"，这里会得到 LIMIT（比如 50），
        // 而用户看到的是「50 条相关」—— 那个数是假的。
        val many = (1..80).map { text("t$it", lin, "协议 $it", 1000L + it) }
        store.save(mapOf(lin to many, zhou to listOf(text("z1", zhou, "协议 也算我一份", 5000))))

        val groups = store.searchGrouped("协议", limit = 10)

        assertEquals(2, groups.size)
        val linGroup = groups.single { it.convId == lin }
        assertEquals(80, linGroup.matchCount, "条数必须来自全部命中")
        assertEquals(1080L, linGroup.latestAt)
        // 按最新命中倒序：zhou 的 5000 比 lin 的 1080 新。
        assertEquals(listOf(zhou, lin), groups.map { it.convId })
    }

    @Test
    fun 聚合带上最新那条命中的正文() {
        // 裸列 text 取 MAX(timestamp) 那一行 —— SQLite 的成文行为，但很反直觉，
        // 所以钉一条：拿到的是**最新**那条的正文，不是第一行、也不是最后一行。
        store.save(
            mapOf(
                lin to listOf(
                    text("old", lin, "协议 旧的那版", 1000),
                    text("new", lin, "协议 最新这版改了超时", 9000),
                    text("mid", lin, "协议 中间那条", 5000),
                ),
            ),
        )

        val group = store.searchGrouped("协议").single()

        assertEquals(3, group.matchCount)
        assertEquals("协议 最新这版改了超时", group.latestText)
    }

    @Test
    fun 聚合按会话分组而不是按人() {
        // 群会话的键是 group:<id>，与私聊的 nodeId 是两个会话，
        // 不能因为都是"同一个人的消息"就并成一组。
        store.save(
            mapOf(
                lin to listOf(text("m1", lin, "协议 私聊", 1000)),
                "group:g1" to listOf(text("m2", "group:g1", "协议 群里", 2000)),
            ),
        )

        val groups = store.searchGrouped("协议")

        assertEquals(setOf(lin, "group:g1"), groups.map { it.convId }.toSet())
    }

    // ---------------------------------------------------------------- 全局：文件段

    @Test
    fun 按文件名检索命中的是文件名() {
        store.save(
            mapOf(
                lin to listOf(file("f1", lin, "需求文档-协议-v2.pdf", 1000)),
                zhou to listOf(file("f2", zhou, "insets-复核.png", 2000, purpose = "image")),
            ),
        )

        val hits = store.searchFiles("协议")

        assertEquals(listOf("f1"), hits.map { it.id })
        assertEquals("需求文档-协议-v2.pdf", hits.single().fileName)
    }

    @Test
    fun 按文件名检索不会命中落在路径里的关键词() {
        // file_json 是整块 JSON，LIKE 会连 savedPaths 一起匹配 —— 所以 Kotlin 侧必须再核一遍
        // 文件名。不核的话，一个存到「协议/」目录下的无关文件会被搜出来，
        // 而用户看到的文件名里根本没有那个词。
        store.save(
            mapOf(
                lin to listOf(
                    file("unrelated", lin, "随手拍.jpg", 1000, savedPaths = listOf("/下载/协议/随手拍.jpg")),
                ),
            ),
        )

        assertTrue(store.searchFiles("协议").isEmpty(), "文件名不含关键词就不算命中")
    }

    @Test
    fun 按文件名检索区分图片与文件() {
        store.save(
            mapOf(
                lin to listOf(
                    file("img", lin, "协议草图.png", 1000, purpose = "image"),
                    file("pdf", lin, "协议正文.pdf", 2000),
                ),
            ),
        )

        val hits = store.searchFiles("协议")

        assertTrue(hits.single { it.id == "img" }.isImage, "声明了 image 的图要画图片图标")
        assertFalse(hits.single { it.id == "pdf" }.isImage)
    }

    @Test
    fun 按文件名检索不碰纯文本消息() {
        // 「文件」段只列文件：一条正文里提到「协议」的文本消息不该出现在这里，
        // 它属于「聊天记录」段。
        store.save(mapOf(lin to listOf(text("m1", lin, "协议 我们下次再聊", 1000))))

        assertTrue(store.searchFiles("协议").isEmpty())
    }

    // ---------------------------------------------------------------- 边界

    @Test
    fun 空词与纯空格返回空() {
        store.save(mapOf(lin to listOf(text("m1", lin, "协议", 1000))))

        assertTrue(store.searchInConversation(lin, "   ").isEmpty())
        assertTrue(store.searchGrouped("  ").isEmpty())
        assertTrue(store.searchFiles("").isEmpty())
    }

    @Test
    fun 搜不到时返回空而不是抛异常() {
        store.save(mapOf(lin to listOf(text("m1", lin, "协议", 1000))))

        // 关键词里的双引号会被转义成 FTS 的短语边界；一个只有引号的词曾经能让
        // FTS5 语法错（getOrElse 兜底降级 LIKE 就是为它写的）。这里钉住它不抛。
        assertTrue(store.searchInConversation(lin, "\"").isEmpty())
        assertTrue(store.searchInConversation(lin, "\"\"\"").isEmpty())
    }
}
