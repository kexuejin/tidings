// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.kapp.tidings.domain.model.FileCardUi
import com.kapp.tidings.domain.model.MessageUi
import com.kapp.tidings.db.RelayDatabase
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
    fun 空正文又没有文件时预览保持null() {
        // null 的契约：UI 据此画「还没聊过」。空串不能折成任何文案 ——
        // 「没聊过」与「聊了但这条没有正文」是两件事。
        store.save(
            mapOf(
                "peer-1" to listOf(
                    MessageUi(id = "e1", peerId = "peer-1", mine = true, text = "", at = 3_000L),
                ),
            ),
        )

        assertEquals(null, store.loadSummaries().single().lastTextPreview)
    }
}
