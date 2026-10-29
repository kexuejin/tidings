// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.kapp.tidings.domain.model.MessageUi
import com.kapp.tidings.db.RelayDatabase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * **消息的会话归属必须跨重启存活。**
 *
 * ## 真机现场
 *
 * 聊天流里那条「任务 / 新会话」分段线，**实时看得到、重新打开就没了**；手机端更彻底，一条都没有。
 *
 * 根因：agentSessionId 只活在内存的 MessageUi 里，MessageEntity 没有对应的列，落库时直接丢掉。
 * 而手机端连 id 前缀那条回退也用不上：agent-ev- 是桌面端本地构造消息的副产物，
 * 转发进群时不带过去，手机落库用的是信封 UUID。
 *
 * 这里钉的是「存进去 -> 读回来还在」这一个闭环。判据必须在 toUi() 之后，
 * 而不是只断言 SQL 参数被传了 —— 那正是本仓反复出现的假绿形态。
 */
class MessageAgentSessionPersistsTest {

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

    /** **核心判据：落库再读回，agentSessionId 必须还在。** */
    @Test
    fun theAgentSessionIdSurvivesAPersistRoundTrip() {
        val sessionId = "session-622e8e71-7fc6-4076-9d88-a6b706e545d1"
        store.save(
            mapOf(
                "group:g1" to listOf(
                    MessageUi(
                        id = "agent-ev-session-622e8e71-7fc6-4076-9d88-a6b706e545d1-1-1",
                        peerId = "group:g1",
                        mine = false,
                        text = "这条属于某个 agent 会话",
                        at = 1000,
                        agentSessionId = sessionId,
                    ),
                ),
            ),
        )
        val back = store.load().values.flatten().single()
        assertEquals(
            sessionId,
            back.agentSessionId,
            "会话归属必须落库 —— 否则重新打开聊天时分段线全没了",
        )
    }

    /**
     * **普通消息的会话归属仍是 null**（不能凭空编一个）。
     *
     * 老库迁移补列后那一列就是 NULL，语义与「这条不属于任何会话」一致。
     */
    @Test
    fun aPlainMessageHasNoSession() {
        store.save(
            mapOf(
                "peer-1" to listOf(
                    MessageUi(id = "m1", peerId = "peer-1", mine = false, text = "随便聊两句", at = 1000),
                ),
            ),
        )
        assertNull(store.load().values.flatten().single().agentSessionId)
    }

    /**
     * **卡片与执行状态必须跨重启存活**（方案 §4.3 / §5.3）。
     *
     * 卡片是原位刷新的折叠键（cardId/rev 都在卡片 JSON 里），执行状态驱动徽标 ——
     * 两者只活在内存的话，重启后卡片退化为纯文本、状态徽标消失。落库是**追加式新行**
     * （同 cardId、rev+1 的新帧），不重写旧行，绕开 INSERT OR REPLACE / FTS 那套不变量。
     */
    @Test
    fun theCardAndAgentStateSurviveAPersistRoundTrip() {
        val card = kotlinx.serialization.json.buildJsonObject {
            put("type", kotlinx.serialization.json.JsonPrimitive("team-task-board"))
            put("cardId", kotlinx.serialization.json.JsonPrimitive("task-89"))
            put("rev", kotlinx.serialization.json.JsonPrimitive(3))
        }
        store.save(
            mapOf(
                "group:g2" to listOf(
                    MessageUi(
                        id = "agent-ev-card-1",
                        peerId = "group:g2",
                        mine = false,
                        text = "看板",
                        at = 2000,
                        agentState = "accept",
                        card = card,
                    ),
                ),
            ),
        )
        val back = store.load().values.flatten().single()
        assertEquals("accept", back.agentState, "执行状态必须落库")
        assertEquals(card, back.card, "卡片必须原样落库（cardId/rev 是原位刷新的折叠键）")
    }

    /** 普通消息没有卡片与状态：列为 NULL，读回来就是 null，不能编一个空对象。 */
    @Test
    fun aPlainMessageHasNoCardAndNoState() {
        store.save(
            mapOf(
                "peer-2" to listOf(
                    MessageUi(id = "m2", peerId = "peer-2", mine = false, text = "纯文本", at = 3000),
                ),
            ),
        )
        val back = store.load().values.flatten().single()
        assertNull(back.card)
        assertNull(back.agentState)
    }
}
