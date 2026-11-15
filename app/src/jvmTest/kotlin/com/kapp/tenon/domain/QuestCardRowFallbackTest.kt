// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.kapp.tenon.db.RelayDatabase
import com.kapp.tenon.domain.model.MessageUi
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * quest 卡读取的**载荷列回退**（schema 注释的既定契约：card_payload NULL（老消息 /
 * 未刷新）时消费方回退读 card_json）。
 *
 * ## 真机现场
 *
 * quest-sync 的宿主应答把 candidates 全判成空 → "no newer snapshots" → 派单端的
 * 卡永远停在「进行中」。根因：**宿主侧的 quest 卡走通用持久化建卡（写 card_json）**，
 * quest 专属列 card_payload 只有翻面路径（bindPendingNoteToQuest / updateCardContent）
 * 才写 —— 宿主的卡恰好是 card_json 有值、card_payload NULL 的形态，而 questCardRow
 * 读 card_payload 不回退，宿主自己的卡全被对账漏掉。
 *
 * **反向验证**：把回退摘掉（只读 card_payload），第一条用例立刻红。
 */
class QuestCardRowFallbackTest {

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

    /** 宿主形态：通用持久化建卡（card_json 有值、card_payload NULL）→ 回退读 card_json。 */
    @Test
    fun hostShapedCardFallsBackToCardJson() {
        store.save(
            mapOf(
                "assistant" to listOf(
                    MessageUi(
                        id = "local-1-1000",
                        peerId = "assistant",
                        mine = false,
                        text = "quesprobe3",
                        at = 1000,
                        card = buildJsonObject {
                            put("cardId", "q3")
                            put("title", "quesprobe3")
                        },
                        questId = "q3",
                        questRev = 1,
                        agentState = "failed",
                    ),
                ),
            ),
        )
        val row = assertNotNull(store.questCardRow("q3"), "quest 行存在，必须读得到卡")
        assertEquals(1L, row.questRev)
        assertEquals("failed", row.agentState)
        val payload = assertNotNull(row.cardPayload, "card_payload NULL 必须回退读 card_json")
        assertTrue(payload.contains("\"cardId\""), "回退读到的必须是 card_json 的原文载荷")
    }

    /** 翻面写过的卡：card_payload（原位刷新列）优先，不回退。 */
    @Test
    fun flipWrittenPayloadWinsOverCardJson() {
        store.save(
            mapOf(
                "assistant" to listOf(
                    MessageUi(
                        id = "local-1-2000",
                        peerId = "assistant",
                        mine = true,
                        text = "quesprobe4",
                        at = 2000,
                        card = buildJsonObject {
                            put("cardId", "q4")
                            put("title", "quesprobe4")
                        },
                        questId = "q4",
                        questRev = 0,
                        clientToken = "note-abc",
                    ),
                ),
            ),
        )
        store.bindPendingNoteToQuest(
            clientToken = "note-abc",
            questId = "q4",
            newJsonContent = "{\"cardId\":\"q4\",\"title\":\"quesprobe4 v2\"}",
            status = "failed",
            rev = 1,
        )
        val row = assertNotNull(store.questCardRow("q4"))
        assertEquals(1L, row.questRev)
        assertEquals("failed", row.agentState)
        val payload = assertNotNull(row.cardPayload)
        assertTrue(payload.contains("v2"), "原位刷新列有值时优先于 card_json")
    }

    /** 没有 questId 的普通消息不进 questRows/questCardRow（对账清册只收 quest 行）。 */
    @Test
    fun plainMessagesAreNotQuestRows() {
        store.save(
            mapOf(
                "assistant" to listOf(
                    MessageUi(
                        id = "local-1-3000",
                        peerId = "assistant",
                        mine = true,
                        text = "questpob2",
                        at = 3000,
                    ),
                ),
            ),
        )
        assertTrue(store.questRows().isEmpty(), "普通消息不得进对账清册")
    }
}

