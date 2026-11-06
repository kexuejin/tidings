// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.desktop

// 编排面（:orchestration）：从桌面壳下沉的工单/责任链/台账/卡片补丁。
import com.kapp.tenon.orchestration.BoardCardFlip

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 翻面的两条判据：**在原卡上改**（信息不许丢）+ **两套词表不许矛盾**（D6）。
 *
 * 这份测试之所以存在，是因为这段逻辑从前写在 `DesktopExtension` 的 lambda 里 ——
 * 没有它，「合并成功但 diff 明细消失」「已合并的卡上还写着待合流」这两种毛病
 * 都只能靠真机点一遍才发现。
 */
class BoardCardFlipTest {

    /** 真机上那张卡的形状：单棒工单卡，`items` 只有一棒。 */
    private fun reviewCard(itemStatus: String = "review_ready"): JsonObject = buildJsonObject {
        put("cardId", "chain-t1")
        put("rev", 3)
        put("type", "task-board")
        put("state", "review_ready")
        put("title", "teahouse-mobile 改好了，等你合并")
        put("progress", 100)
        putJsonArray("items") {
            add(buildJsonObject {
                put("id", "claude")
                put("label", "claude")
                put("status", itemStatus)
            })
            add(buildJsonObject {
                put("id", "codex")
                put("label", "codex")
                put("status", "done")
            })
        }
        putJsonObject("diff") {
            put("path", "teahouse-mobile")
            put("summary", "2 个文件")
            put("additions", 261)
            put("deletions", 0)
        }
        putJsonArray("actions") {
            add(buildJsonObject {
                put("actionId", "merge-patch")
                put("label", "merge-patch")
            })
        }
        put("decidedBy", "22222222-2222-4222-8222-222222222222")
        put("decidedActionId", "merge-patch")
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.content
    private fun JsonObject.obj(key: String) = this[key] as? JsonObject
    private fun JsonObject.itemStatuses() = (this["items"] as JsonArray).map {
        ((it as JsonObject)["status"] as JsonPrimitive).content
    }

    @Test
    fun mergedKeepsEverythingTheUserWasLookingAt() {
        val flipped = BoardCardFlip.merged("chain-t1", reviewCard())

        assertEquals("merged", flipped.str("state"))
        assertEquals("chain-t1", flipped.str("cardId"), "翻面之后还是这张卡")
        assertEquals("teahouse-mobile 改好了，等你合并", flipped.str("title"), "标题要留着，不许变成 Patch applied")
        assertEquals("261", flipped.obj("diff")?.get("additions")?.let { (it as JsonPrimitive).content })
        assertEquals("100", flipped.str("progress"))
        assertEquals("success", flipped.obj("banner")?.str("tone"))
        assertNull(flipped["actions"], "活已经干完，按钮不该还在")
    }

    /**
     * D6：`state` 说「已合并」，阶段项就不许还写着「待合流」。
     *
     * 渲染层把 `review_ready` 也画成已完成，所以肉眼看不出来 —— 但落库的卡是矛盾的，
     * 任何读 `items` 的地方（导出、二次派单、旧卡兜底反推）都会读到旧事实。
     */
    @Test
    fun mergedAdvancesTheItemThatWasWaitingForTheMerge() {
        val flipped = BoardCardFlip.merged("chain-t1", reviewCard(itemStatus = "review_ready"))

        assertEquals(
            listOf("done", "done"),
            flipped.itemStatuses(),
            "等合流的那棒合进去之后就是 done；不许留下 review_ready",
        )
        // 反证另一半：不是「全改成 done」。没跑的那棒保持原样。
        val withPending = reviewCard(itemStatus = "pending")
        assertEquals(
            listOf("pending", "done"),
            BoardCardFlip.merged("chain-t1", withPending).itemStatuses(),
            "没跑的那棒不许被写成完成 —— 那是把没发生的事写成发生过",
        )
    }

    @Test
    fun mergedStillWorksWhenTheOriginalCardIsGone() {
        // 卡片行被清过（重启后没回填）时的兜底：不能抛，也不能出一张没有身份的卡。
        val flipped = BoardCardFlip.merged("diff-t9", null)
        assertEquals("diff-t9", flipped.str("cardId"))
        assertEquals("merged", flipped.str("state"))
        assertEquals("diff-review", flipped.str("type"), "没有原卡就退回 diff-review")
        assertEquals("Patch applied", flipped.str("title"))
        assertEquals("1", flipped.str("rev"), "rev 交给 broadcastBoardCard 补")
    }

    @Test
    fun blockedKeepsTheDiffAndMakesTheButtonClickableAgain() {
        val flipped = BoardCardFlip.blocked(
            cardId = "chain-t1",
            base = reviewCard(),
            hint = "补丁与当前工作树冲突，请先处理这几处",
            title = "没能合并这次改动",
        )

        assertEquals("blocked", flipped.str("state"))
        assertEquals("没能合并这次改动", flipped.str("title"))
        assertEquals("error", flipped.obj("banner")?.str("tone"))
        // 冲突时用户最想看的就是「哪几行没合上」—— 明细必须留着。
        assertEquals("teahouse-mobile", flipped.obj("diff")?.str("path"))
        assertEquals(listOf("review_ready", "done"), flipped.itemStatuses(), "受阻不是合流，阶段项不许被推进")
        // 判定标记要清掉，否则按钮还是禁用态，这张卡就没法「再点一次」。
        assertNull(flipped["decidedBy"])
        assertNull(flipped["decidedActionId"])
        val actions = flipped["actions"] as JsonArray
        assertEquals(1, actions.size)
        assertEquals("merge-patch", ((actions.first() as JsonObject)["actionId"] as JsonPrimitive).content)
    }

    /** D-NEW-1：宿主不再认得的滞留卡，点了按钮之后要如实翻面，而不是挂着假状态。 */
    @Test
    fun staleKeepsTheFactsButStopsAcceptingActions() {
        val base = reviewCard().let { card ->
            buildJsonObject {
                card.forEach { (k, v) -> put(k, v) }
                put("expiresAt", 9999999999L)
            }
        }
        val flipped = BoardCardFlip.stale(
            cardId = "chain-t1",
            base = base,
            hint = "该工单已结束或宿主已重启，此操作未生效",
        )

        assertEquals("aborted", flipped.str("state"), "手机端词表里这是「已停止」")
        assertEquals("chain-t1", flipped.str("cardId"), "还是这张卡，不许另发新卡")
        assertEquals("teahouse-mobile 改好了，等你合并", flipped.str("title"), "标题留着，用户靠它认卡")
        assertEquals("261", flipped.obj("diff")?.get("additions")?.let { (it as JsonPrimitive).content })
        assertNull(flipped["actions"], "这张卡不会再接受任何动作")
        assertNull(flipped["expiresAt"], "过期标记随翻面清掉，别跟失效横幅打架")
        assertEquals("该工单已结束或宿主已重启，此操作未生效", flipped.obj("banner")?.str("text"))
        assertEquals("1", flipped.str("rev"), "rev 交给 broadcastBoardCard 补")
    }

    @Test
    fun staleStillWorksWhenTheOriginalCardIsGone() {
        val flipped = BoardCardFlip.stale("chain-t9", null, "hint")
        assertEquals("chain-t9", flipped.str("cardId"))
        assertEquals("aborted", flipped.str("state"))
        assertEquals("diff-review", flipped.str("type"))
        assertNull(flipped["actions"])
    }
}
