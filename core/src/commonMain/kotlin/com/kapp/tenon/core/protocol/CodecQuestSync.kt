// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import com.kapp.tenon.core.protocol.CodecWire.hasOnlyKeys
import com.kapp.tenon.core.protocol.CodecWire.intOf
import com.kapp.tenon.core.protocol.CodecWire.isStr
import com.kapp.tenon.core.protocol.CodecWire.utf8ByteLength
import com.kapp.tenon.core.protocol.CodecWire.wireJson

/**
 * 多端状态增量对账两帧（`quest-sync-req` / `quest-sync-resp`，Phase 2）的编解码
 * 与**分页装箱**。先例即 [CodecStream]：校验器（收端）、构造器（发端）引用同一份
 * 键集与取值域；预算判据（1 KiB / 32 KiB）只有这一份。
 */
object CodecQuestSync {

    internal val QUEST_SYNC_REQ_EXPECTED_KEYS = setOf("quests", "cursor")
    internal val QUEST_SYNC_RESP_EXPECTED_KEYS = setOf("snapshots", "hasMore", "cursor")

    internal fun validateQuestSyncReq(o: JsonObject): Boolean {
        if (!hasOnlyKeys(o, QUEST_SYNC_REQ_EXPECTED_KEYS)) return false
        val quests = o["quests"] as? kotlinx.serialization.json.JsonArray ?: return false
        if (quests.size > Limits.QUEST_SYNC_MAX_QUESTS) return false
        if (utf8ByteLength(o.toString()) > Limits.QUEST_SYNC_REQ_BUDGET) return false
        for (item in quests) {
            val pair = item as? JsonObject ?: return false
            if (!hasOnlyKeys(pair, setOf("questId", "rev"))) return false
            if (!isStr(pair["questId"], Limits.ID)) return false
            val rev = intOf(pair["rev"]) ?: return false
            if (rev < 0) return false
        }
        if (o.containsKey("cursor")) {
            // 游标 = 上一页最后一张快照的 "questId@rev"（同 rev 的 quest 不会互吞）。
            val cursor = (o["cursor"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            if (!isCursorShape(kotlinx.serialization.json.JsonPrimitive(cursor ?: return false))) return false
        }
        return true
    }

    private fun isCursorShape(v: kotlinx.serialization.json.JsonElement): Boolean {
        val s = (v as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
        val at = s.lastIndexOf('@')
        if (at <= 0 || at == s.length - 1) return false
        if (utf8ByteLength(s) > Limits.CARD_BYTES) return false
        return s.substring(at + 1).toIntOrNull()?.let { it >= 0 } == true
    }

    internal fun validateQuestSyncResp(o: JsonObject): Boolean {
        if (!hasOnlyKeys(o, QUEST_SYNC_RESP_EXPECTED_KEYS)) return false
        if (utf8ByteLength(o.toString()) > Limits.QUEST_SYNC_RESP_BUDGET) return false
        val snapshots = o["snapshots"] as? kotlinx.serialization.json.JsonArray ?: return false
        for (item in snapshots) {
            val snap = item as? JsonObject ?: return false
            if (!hasOnlyKeys(snap, setOf("questId", "rev", "cardPayload", "agentState"))) return false
            if (!isStr(snap["questId"], Limits.ID)) return false
            val rev = intOf(snap["rev"]) ?: return false
            if (rev < 1) return false
            val payload = CodecWire.strOrNull(snap["cardPayload"], Limits.CARD_BYTES) ?: return false
            if (utf8ByteLength(payload) > Limits.CARD_BYTES) return false
            if (CodecWire.strAllowEmptyOrNull(snap["agentState"], Limits.AGENT_STATE) == null) return false
        }
        if (o.containsKey("hasMore") && CodecWire.boolOf(o["hasMore"]) == null) return false
        val hasMore = CodecWire.boolOf(o["hasMore"]) ?: false
        if (hasMore) {
            // 有下一页就必须带游标；游标 = 本页最后一张快照的 "questId@rev"
            // （rev 是各 quest 独立计数，全局 rev 游标在同 rev 边界会吞掉别的 quest）。
            if (!isCursorShape(o["cursor"] ?: return false)) return false
        }
        return true
    }

    /** 构造 `quest-sync-req` 负载；条数/预算在发送侧先过同一份谓词（超了去分页）。 */
    fun questSyncReqPayload(quests: List<QuestRevPair>, cursor: String? = null): JsonElement {
        require(quests.isNotEmpty()) { "quest-sync-req 不发空清单（没有可对账的档位）" }
        require(quests.size <= Limits.QUEST_SYNC_MAX_QUESTS) { "quest-sync-req 超条数上限（发送端先分页）" }
        require(quests.all { it.questId.isNotBlank() && utf8ByteLength(it.questId) <= Limits.ID && it.rev >= 0 }) {
            "quest-sync-req 档位形状不合法"
        }
        val payload = wireJson.encodeToJsonElement(
            QuestSyncReqPayload.serializer(),
            QuestSyncReqPayload(quests = quests, cursor = cursor),
        )
        require(utf8ByteLength(payload.toString()) <= Limits.QUEST_SYNC_REQ_BUDGET) {
            "quest-sync-req 超帧预算（发送端先分页）"
        }
        return payload
    }

    /**
     * 构造 `quest-sync-resp` 负载；**只装得进单页的快照** —— 超预算在构造期就崩，
     * 分页是调用方（宿主打包处）用 [packSnapshots] 做的，不是靠这个构造器兜底。
     */
    fun questSyncRespPayload(snapshots: List<QuestSnapshotPayload>, hasMore: Boolean, cursor: String?): JsonElement {
        require(snapshots.isNotEmpty() || !hasMore) { "空页不许宣称还有下一页" }
        if (hasMore) {
            require(!cursor.isNullOrBlank()) { "hasMore 必须带游标" }
            require(cursor == snapshots.lastOrNull()?.let { "${it.questId}@${it.rev}" }) {
                "游标必须是本页最后一张快照的 questId@rev"
            }
        }
        val payload = wireJson.encodeToJsonElement(
            QuestSyncRespPayload.serializer(),
            QuestSyncRespPayload(snapshots = snapshots, hasMore = hasMore, cursor = cursor),
        )
        require(utf8ByteLength(payload.toString()) <= Limits.QUEST_SYNC_RESP_BUDGET) {
            "quest-sync-resp 超帧预算（调用方先用 packSnapshots 分页）"
        }
        return payload
    }

    /**
     * 按 `rev` 升序装箱：装进单页预算的进 [Packed.page]（连同下一页标记与游标），
     * 装不下的留在 [Packed.rest]（调用方对下一页请求再装箱）。判据 = **逐张实测
     * 编码长**（JSON 转义膨胀照算），不是字符数估算。
     */
    class Packed(
        val page: List<QuestSnapshotPayload>,
        val hasMore: Boolean,
        val cursor: String?,
        val rest: List<QuestSnapshotPayload>,
    )

    fun packSnapshots(candidates: List<QuestSnapshotPayload>): Packed {
        // 排序 = (rev, questId) 确定性序：rev 是各 quest 独立计数，同 rev 的快照
        // 必须有稳定的次序，续页游标（questId@rev）才能在下一请求里精确续位
        // （全局 rev 游标在同 rev 边界会吞掉别的 quest，code review #7）。
        val ordered = candidates.sortedWith(compareBy({ it.rev }, { it.questId }))
        val page = ArrayList<QuestSnapshotPayload>()
        val rest = ArrayList<QuestSnapshotPayload>()
        var used = 0
        // 信封余量：v/type/id/from/ts + quests/snapshots/hasMore/cursor 外壳。
        val headroom = Limits.ENVELOPE_SHELL_HEADROOM
        for (snap in ordered) {
            val single = wireJson.encodeToJsonElement(QuestSnapshotPayload.serializer(), snap)
            val cost = utf8ByteLength(single.toString())
            if (used + cost > Limits.QUEST_SYNC_RESP_BUDGET - headroom) {
                rest.add(snap)
            } else {
                page.add(snap)
                used += cost
            }
        }
        if (rest.isNotEmpty()) {
            // 单张快照自己就超预算（cardPayload ≤ 24 KiB 理论上装得下，这里是构造期
            // 兜底）：不产出「永远装不下」的页，直接响亮失败逼调用方修载荷。
            require(page.isNotEmpty()) { "单张快照超 QUEST_SYNC_RESP_BUDGET —— 卡片载荷本身越界了" }
        }
        return if (rest.isEmpty()) {
            Packed(page, hasMore = false, cursor = null, rest = rest)
        } else {
            val last = page.last()
            Packed(page, hasMore = true, cursor = "${last.questId}@${last.rev}", rest = rest)
        }
    }
}
