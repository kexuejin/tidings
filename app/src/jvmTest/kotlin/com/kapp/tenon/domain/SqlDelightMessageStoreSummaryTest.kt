// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.kapp.tenon.domain.model.FileCardUi
import com.kapp.tenon.domain.model.MessageUi
import com.kapp.tenon.db.RelayDatabase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * 会话列表预览（摘要）的派生纪律 —— [P0]「重启后预览两条派生不同源」的落点。
 *
 * 摘要有两个来源：落库时的 `refreshSummary`（重启后靠它）与内存路径
 * （RelayStateWriter / RelayUiState 兜底）。过去库里那份取**原始 text**，于是：
 * ① 带 reasoning 的消息落库成 `<think>…</think> + 正文`，重启后列表预览漏出推理原文；
 * ② 文件消息正文是空串、文件名在 file_json 列，不取它就落成「还没聊过」。
 * 两条都用「真身怎么进库、列表怎么读」的端到端路径钉住：反向验证时把旧派生
 * （`latest.text.takeIf { it.isNotBlank() }`）放回去，下面两条都必须变红。
 */
class SqlDelightMessageStoreSummaryTest {

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

    @Test
    fun 带推理的消息重启后预览只留正文不漏推理原文() {
        // 与生产同一条进库路径：MessageUi.text 是正文、reasoning 单独放，
        // toRowValues 把两者包成 `<think>\n…\n</think>\n正文` 落库。
        store.save(
            mapOf(
                "peer-1" to listOf(
                    MessageUi(
                        id = "m1",
                        peerId = "peer-1",
                        mine = false,
                        text = "最终回答正文",
                        at = 1_000L,
                        reasoning = "这一段是模型推理草稿，绝不能出现在会话列表里",
                    ),
                ),
            ),
        )

        val preview = store.loadSummaries().single().lastTextPreview

        assertEquals("最终回答正文", preview, "列表预览必须只留正文")
        assertFalse(
            preview?.contains("推理草稿") == true,
            "列表预览漏出了 <think> 推理原文 —— 摘要派生没走统一的清洗",
        )
    }

    @Test
    fun 只有文件的消息重启后预览是文件名而不是还没聊过() {
        // 文件消息：正文空串、文件卡随消息落库（file_json 列）。
        // 旧派生不读 file_json ⇒ 预览落成 null ⇒ 列表画「还没聊过，打个招呼」。
        store.save(
            mapOf(
                "peer-1" to listOf(
                    MessageUi(
                        id = "f1",
                        peerId = "peer-1",
                        mine = false,
                        text = "",
                        at = 2_000L,
                        file = FileCardUi(transferId = "t-1", name = "季度报表.pdf", size = 123L),
                    ),
                ),
            ),
        )

        val preview = store.loadSummaries().single().lastTextPreview

        assertEquals("季度报表.pdf", preview, "文件会话的预览必须是文件名，不能退回「还没聊过」")
    }

    @Test
    fun 最新一条是空帧时预览退回更早的真消息() {
        // 真机 A22：agent 事件流在库里留下一串零正文的行，最新一条正好是空帧 ⇒
        // 旧摘要取"最新一行"得 null ⇒ 会话列表写「还没聊过，打个招呼」，
        // 而上面躺着上百条真消息。摘要必须跳过画不出东西的行，继续往更早找。
        store.save(
            mapOf(
                "peer-1" to listOf(
                    MessageUi(id = "r1", peerId = "peer-1", mine = false, text = "改动已就绪：1 个文件（+1 −0）", at = 5_000L),
                    MessageUi(id = "e1", peerId = "peer-1", mine = false, text = "", at = 5_001L),
                    MessageUi(id = "e2", peerId = "peer-1", mine = false, text = "", at = 5_002L),
                ),
            ),
        )

        val preview = store.loadSummaries().single().lastTextPreview

        assertEquals(
            "改动已就绪：1 个文件（+1 −0）",
            preview,
            "最新一帧是空帧时预览必须退回更早的真消息，不能落成「还没聊过」",
        )
    }

    @Test
    fun 只有空帧的会话不产生摘要行() {
        // 「没聊过」与「聊了但每条都没有正文」是两件事：前者由列表的空态文案表达，
        // 后者不该再往摘要表里写一条 preview=null 的行（那正是「还没聊过」被印在
        // 有消息的会话上的来源）。写入侧现在也会丢弃空帧（`hasRenderableContent`），
        // 这里钉的是**老库**：历史空行读回来时摘要仍然不落 null 预览。
        store.save(
            mapOf(
                "peer-1" to listOf(
                    MessageUi(id = "e1", peerId = "peer-1", mine = true, text = "", at = 3_000L),
                ),
            ),
        )

        assertEquals(0, store.loadSummaries().size, "只有空帧的会话不该有摘要行")
    }

    @Test
    fun 历史库里预览为空的摘要行在读取时自愈() {
        // 老库形态：agent 事件流的零正文帧占住"最新一条"，摘要被写成了 preview=NULL
        // ⇒ 会话列表画「还没聊过，打个招呼」，而上面有真消息（宿主库里 14 条 11 条空帧）。
        // 新写入侧已经不再产生这种行，但**已经躺在库里的**必须能自己修好 ——
        // 否则用户升级后仍然看到那句错误的空态文案，直到那个会话再来一条新消息。
        store.save(
            mapOf(
                "peer-1" to listOf(
                    MessageUi(id = "r1", peerId = "peer-1", mine = false, text = "真消息", at = 5_000L),
                ),
            ),
        )
        driver.execute(null, "UPDATE ConversationSummary SET last_text_preview = NULL", 0)

        assertEquals(
            "真消息",
            store.loadSummaries().single().lastTextPreview,
            "预览为空的历史摘要行必须在读的时候重算，不能把「还没聊过」一直挂着",
        )
    }
}
