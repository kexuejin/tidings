// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 多端状态增量对账两帧（Phase 2）的预算与分页守卫。
 *
 * 两条「发送侧不产出注定被拒的帧」不变量：
 * 1. req 清单 ≤ [Limits.QUEST_SYNC_MAX_QUESTS] 且整帧 ≤ [Limits.QUEST_SYNC_REQ_BUDGET]
 *    —— 超出的由发送端分页；
 * 2. resp 快照总载荷 ≤ [Limits.QUEST_SYNC_RESP_BUDGET] —— 宿主按 `rev` 升序装箱
 *    （[CodecQuestSync.packSnapshots]），装不下的留下一页（`hasMore` + 游标）。
 */
class QuestSyncBudgetGuardTest {

    private fun snap(questId: String, rev: Long, payloadBytes: Int = 64) = QuestSnapshotPayload(
        questId = questId,
        rev = rev,
        cardPayload = "x".repeat(payloadBytes),
        agentState = "RUNNING",
    )

    @Test
    fun reqOverTenQuestsIsRejectedAtSendSideAndOnWire() {
        val quests = (1..11).map { QuestRevPair("q-$it", 1) }
        // 发送侧：构造期就崩（分页是调用方的责任）。
        assertFailsWith<IllegalArgumentException> { Codec.questSyncReqPayload(quests) }
        // 接收侧：即便有人手工拼 11 对，整帧 bad-payload。
        val entries = quests.joinToString(",") { """{"questId":"${it.questId}","rev":1}""" }
        val json = """{"v":1,"type":"quest-sync-req","id":"33333333-3333-4333-8333-333333333333",
            "from":"11111111-1111-4111-8111-111111111111","ts":1700000000000,
            "payload":{"quests":[$entries]}}""".replace("\n", "")
        val result = Codec.decodeTcp(json)
        assertTrue(result is DecodeResult.Rejected, "11 对清单必须整帧拒收")
        assertEquals("bad-payload:quest-sync-req", result.reason)
    }

    @Test
    fun respOverBudgetIsRejectedButPackedPagesFit() {
        // 三张 ~14 KiB 载荷的快照：合计 ~42 KiB，超 32 KiB 预算。
        val fat = listOf(snap("q-1", 1, 14_000), snap("q-2", 2, 14_000), snap("q-3", 3, 14_000))
        assertFailsWith<IllegalArgumentException> {
            Codec.questSyncRespPayload(fat, hasMore = false, cursor = null)
        }
        // 打包器把它切成两页：第一页装两张，第二页一张，游标 = 第一页最后一张的 rev。
        val packed = CodecQuestSync.packSnapshots(fat)
        assertTrue(packed.hasMore)
        assertEquals(2, packed.page.size)
        assertEquals(listOf("q-1", "q-2"), packed.page.map { it.questId }, "rev 升序装箱")
        assertEquals("q-2@2", packed.cursor)
        assertEquals(listOf("q-3"), packed.rest.map { it.questId })
        // 第二页照构造器过一遍：必须在预算内。
        Codec.questSyncRespPayload(packed.rest, hasMore = false, cursor = null)
    }

    @Test
    fun singleSnapshotOverBudgetFailsLoudly() {
        val one = listOf(snap("q-1", 1, Limits.QUEST_SYNC_RESP_BUDGET))
        assertFailsWith<IllegalArgumentException> { CodecQuestSync.packSnapshots(one) }
    }

    @Test
    fun respHasMoreWithoutCursorIsRejected() {
        val json = """{"v":1,"type":"quest-sync-resp","id":"33333333-3333-4333-8333-333333333333",
            "from":"11111111-1111-4111-8111-111111111111","ts":1700000000000,
            "payload":{"snapshots":[{"questId":"q-1","rev":5,"cardPayload":"p","agentState":"RUNNING"}],"hasMore":true}}""".replace("\n", "")
        val result = Codec.decodeTcp(json)
        assertTrue(result is DecodeResult.Rejected, "hasMore 必须带游标")
        assertEquals("bad-payload:quest-sync-resp", result.reason)
    }

    /**
     * cursor per-quest（review #7）：游标 = "questId@rev"。同 rev 的 quest 不会被
     * 全局 rev 游标吞掉——packSnapshots 排序 (rev, questId) 确定性序 + 游标定位页尾。
     */
    @Test
    fun cursorIsQuestIdAtRevAndSameRevQuestsSurvivePagination() {
        val a = snap("q-a", 7, 300)
        val b = snap("q-b", 7, 300)
        val packed = CodecQuestSync.packSnapshots(listOf(a, b))
        // 两张 300B 全进一页（3×300 << 32KiB）→ 无游标（hasMore=false）。
        assertTrue(!packed.hasMore)
        assertEquals(null, packed.cursor)

        // 强制分页：6×20KiB 必然多页——游标 = 页尾 questId@rev。
        val fat = List(6) { i -> snap("q-$i", (i + 1).toLong(), 20_000) }
        val paged = CodecQuestSync.packSnapshots(fat)
        assertTrue(paged.hasMore, "6×20KiB 必然分页")
        val lastInPage = paged.page.last()
        assertEquals("${lastInPage.questId}@${lastInPage.rev}", paged.cursor, "游标 = 页尾 questId@rev")

        // 游标形状可过 REQ 校验（续拉请求带着它再拉一页）。
        val req = buildJsonObject {
            put("quests", kotlinx.serialization.json.JsonArray(listOf(buildJsonObject {
                put("questId", lastInPage.questId)
                put("rev", 0)
            })))
            put("cursor", paged.cursor)
        }
        val o = req as? kotlinx.serialization.json.JsonObject ?: error("not object")
        assertTrue(CodecQuestSync.validateQuestSyncReq(o), "cursor 形态必须可过 REQ 校验")
    }
}
