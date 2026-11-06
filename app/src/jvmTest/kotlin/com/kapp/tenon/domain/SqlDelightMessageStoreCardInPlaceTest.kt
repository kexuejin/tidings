// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.kapp.tenon.db.RelayDatabase
import com.kapp.tenon.domain.model.MessageUi
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * T-2d 卡片 (cardId, rev) 原位刷新的存储判据（升级方案 §6.2 / 不变量 3 / §7.2-2）：
 *
 * 一个任务恒占 1 个卡片槽位：rev 推进 = 同一行原位替换（card_payload 列携带最新载荷，
 * card_json 保持首帧原文），**表行数不涨** —— 「不刷屏」从 UI 语义下沉为存储语义。
 *
 * FTS docsize 恒等是本仓最贵的那条不变量（真机 21 GB 现场）：原位刷新会改 text
 * （状态行推进），FTS 由 AFTER UPDATE OF text 触发器自维护 —— 这里用 docsize 行数
 * 恒等把「写完 FTS 还平不平」钉死。
 */
class SqlDelightMessageStoreCardInPlaceTest {

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

    private fun cardMessage(id: String, cardId: String, rev: Int, state: String, at: Long) = MessageUi(
        id = id, peerId = "group:g1", mine = false,
        text = "任务 [$cardId] rev$rev · $state", at = at,
        card = buildJsonObject {
            put("cardId", JsonPrimitive(cardId))
            put("rev", JsonPrimitive(rev))
            put("state", JsonPrimitive(state))
        },
        agentState = state,
    )

    /** rev 推进帧：同槽位 id，card（首帧原文）不动，cardPayload = 最新载荷，text/状态推进。 */
    private fun refreshedMessage(slotId: String, firstFrame: MessageUi, cardId: String, rev: Int, state: String, at: Long) =
        firstFrame.copy(
            id = slotId,
            text = "任务 [$cardId] rev$rev · $state",
            at = at,
            cardPayload = buildJsonObject {
                put("cardId", JsonPrimitive(cardId))
                put("rev", JsonPrimitive(rev))
                put("state", JsonPrimitive(state))
            },
            agentState = state,
        )

    @Test
    fun revAdvanceRewritesSameRowRowCountStaysOne() {
        val conv = mapOf("group:g1" to listOf(cardMessage("m1", "card-A", 1, "running", 1000)))
        store.save(conv)
        // rev+1 帧：**同 cardId** 的更新到达。原位刷新 = 同一行替换（调用方解析槽位后
        // 用同一个 id 重写），不是追加第二行。
        // rev+1 帧：调用方解析槽位（cardId 相同）后用**同一个 id** 原位替换 ——
        // card 首帧原文不动，cardPayload 携带最新载荷（T-2d 的调用契约）。
        store.save(mapOf("group:g1" to listOf(refreshedMessage("m1", cardMessage("m1", "card-A", 1, "running", 1000), "card-A", 2, "completed", 2000))))

        val rows = store.load()["group:g1"].orEmpty()
        assertEquals(1, rows.size, "rev 推进后行数必须恒等 —— 刷屏是存储语义回归（§7.2-2）；实际 " + rows.size + " 条：" + rows.joinToString { it.id + "/" + (it.card?.get("rev")?.toString() ?: "?") })
        assertEquals("completed", rows.single().agentState)
        // 最新载荷在 cardPayload（cardPayload 列），首帧原文在 card（card_json 列）。
        val payload = rows.single().cardPayload
        assertEquals("2", (payload?.get("rev") as? JsonPrimitive)?.content, "cardPayload 必须是最新 rev 的载荷")
        assertEquals("1", (rows.single().card?.get("rev") as? JsonPrimitive)?.content, "card_json 首帧原文语义不动（D-021）")
    }

    @Test
    fun ftsDocsizeStaysEqualAfterInPlaceRefresh() {
        store.save(mapOf("group:g1" to listOf(cardMessage("m1", "card-A", 1, "running", 1000))))
        val docsizeBefore = docsizeRows()
        store.save(
            mapOf(
                "group:g1" to listOf(
                    refreshedMessage(
                        "m1",
                        cardMessage("m1", "card-A", 1, "running", 1000),
                        "card-A", 2, "completed · 已通过全部测试 · 产物 2 个", 2000,
                    ),
                ),
            ),
        )
        val docsizeAfter = docsizeRows()
        assertEquals(docsizeBefore, docsizeAfter, "FTS docsize 行数恒等 —— 真机 21 GB 现场的不变量")
        assertEquals(1, docsizeAfter, "一行消息一条 docsize")
    }

    /** 反向验证：摘掉原位刷新（rev+1 落成追加行）⇒ 行数 2 ≠ 1，本文件首条红。 */

    /**
     * 看板卡 rev 台账（root cause #3）：读写走的是 **BoardCardRev 表**，不是内存字段 ——
     * 同一个库上重建存储实例（= 重启）后台账必须还在。
     */
    @Test
    fun boardCardRevLedgerSurvivesAStoreRecreation() {
        store.saveBoardCardRev("card-A", 6)
        val reopened = SqlDelightMessageStore(database)
        assertEquals(6, reopened.loadBoardCardRev("card-A"), "台账必须落在表里，重启（重建实例）后还在")
        assertEquals(null, reopened.loadBoardCardRev("card-never-broadcast"), "没广播过的卡读回来是 null，不是 0")
        // 幂等 upsert：重复写同一张卡只留最高值语义由调用方保证，这里钉「同键覆盖不炸」。
        store.saveBoardCardRev("card-A", 7)
        assertEquals(7, reopened.loadBoardCardRev("card-A"))
    }

    private fun docsizeRows(): Int =
        driver.executeQuery(
            null,
            "SELECT count(*) FROM MessageFts_docsize",
            { cursor -> app.cash.sqldelight.db.QueryResult.Value(cursor.next().let { cursor.getLong(0)!!.toInt() }) },
            0,
        ).value
}
