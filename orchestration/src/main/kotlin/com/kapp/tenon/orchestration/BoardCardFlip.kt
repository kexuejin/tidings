// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.StageChain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 卡片的**翻面**：把宿主已经做过的事写回原卡，而不是另发一张新卡。
 *
 * ## 为什么单独一个文件
 *
 * 这段逻辑从前写在 `DesktopExtension` 的一个回调 lambda 里 —— 于是它**没法被测**，
 * 而它恰恰是「用户点了按钮之后看到什么」的唯一出处。真机上出过两次同一类毛病：
 *
 * 1. 合并成功后从零 build 一张 `{type=diff-review, title="Patch applied"}`，翻面瞬间
 *    `diff` 明细整块消失，用户看不出「合进去的到底是哪几行」；
 * 2. 只改了 `state`，`items[].status` 原样留着 —— 卡上写着「已合并」，阶段项还写着
 *    「待合流」（D6）。渲染层把 `review_ready` 也画成已完成，所以肉眼看不出来，
 *    但数据自相矛盾：任何读 items 的地方（导出、二次派单、旧卡的兜底反推）都会读到旧事实。
 *
 * 抽成纯函数之后这两条都能用普通断言钉住（[BoardCardFlipTest]），不需要起节点。
 */
object BoardCardFlip {

    /**
     * 合并成功：同一张卡翻成 `merged`。
     *
     * 保留原卡的 `title` / `progress` / `diff` / `items` / `artifacts` / `type`，
     * 只换 `state` / `rev` / `banner` / `title`（有就用原的），并丢掉 `actions`
     * —— 活已经干完，按钮不该还在。
     */
    fun merged(cardId: String, base: JsonObject?): JsonObject = buildJsonObject {
        copyWithout(base, DROPPED_ON_MERGED, advanceItems = true)
        put("cardId", JsonPrimitive(cardId))
        // rev 交给 broadcastBoardCard 自己补（不比既有大时 +1）。
        put("rev", JsonPrimitive(1))
        if (base?.get("type") == null) put("type", JsonPrimitive("diff-review"))
        put("state", JsonPrimitive("merged"))
        put(
            "title",
            JsonPrimitive((base?.get("title") as? JsonPrimitive)?.contentOrNull ?: "Patch applied"),
        )
        putJsonObject("banner") {
            put("text", JsonPrimitive("Changes merged into workspace"))
            put("tone", JsonPrimitive("success"))
        }
    }

    /**
     * 合并失败：同一张卡翻成 `blocked`，并给出下一步（[hint]）。
     *
     * 与 [merged] 同一条纪律：**在原卡上改**。冲突时用户最想看的就是「哪几行没合上」，
     * 从零 build 一张只有标题和横幅的卡，等于把唯一的线索扔掉。
     *
     * 额外丢掉 `decidedBy` / `decidedActionId`：那两个键是「宿主已判定」的标记，
     * 留着会让合并按钮保持禁用 —— 而这张卡的用途正是「处理掉冲突再点一次」。
     */
    fun blocked(cardId: String, base: JsonObject?, hint: String, title: String): JsonObject =
        buildJsonObject {
            copyWithout(base, DROPPED_ON_BLOCKED)
            put("cardId", JsonPrimitive(cardId))
            put("rev", JsonPrimitive(1))
            if (base?.get("type") == null) put("type", JsonPrimitive("diff-review"))
            put("state", JsonPrimitive("blocked"))
            put("title", JsonPrimitive(title))
            putJsonObject("banner") {
                put("text", JsonPrimitive(hint))
                put("tone", JsonPrimitive("error"))
            }
            put("actions", buildJsonArray {
                add(buildJsonObject {
                    put("actionId", JsonPrimitive("merge-patch"))
                    put("label", JsonPrimitive("merge-patch"))
                })
            })
        }

    /**
     * 滞留卡如实翻面：宿主已经**不认得**这张卡的工单（宿主重启丢了内存态，或工单
     * 早已收尾被移出表），可卡还在手机上挂着按钮和进行中的状态 —— 点了之后宿主
     * 一个字节都没动，手机却显示「已由 <设备> 处理」。比无反应更误导（D-NEW-1）。
     *
     * 与 [blocked] 同一条纪律：在原卡上改，保留用户还需要看的事实（标题、产物、
     * diff 明细）；翻成 `aborted`（手机端词表是「已停止」）并**丢掉 actions**
     * —— 这张卡不会再接受任何动作了。
     */
    fun stale(cardId: String, base: JsonObject?, hint: String): JsonObject = buildJsonObject {
        copyWithout(base, DROPPED_ON_STALE)
        put("cardId", JsonPrimitive(cardId))
        put("rev", JsonPrimitive(1))
        if (base?.get("type") == null) put("type", JsonPrimitive("diff-review"))
        put("state", JsonPrimitive("aborted"))
        putJsonObject("banner") {
            put("text", JsonPrimitive(hint))
            put("tone", JsonPrimitive("info"))
        }
    }

    /** 合并成功后要重写的键；`items` 单独处理（见下）。 */
    private val DROPPED_ON_MERGED = setOf("state", "rev", "banner", "title", "actions")

    /** 合并失败除了上面那几个，还要让按钮回到可点状态。 */
    private val DROPPED_ON_BLOCKED =
        DROPPED_ON_MERGED + setOf("decidedBy", "decidedActionId")

    /** 滞留翻面：标题保留原样（用户靠它认卡），只换状态、横幅并摘掉按钮。 */
    private val DROPPED_ON_STALE =
        (DROPPED_ON_BLOCKED - setOf("title")) + setOf("expiresAt")

    /**
     * 把原卡上「不属于本次翻面」的键抄过来。
     *
     * `advanceItems` 为真时 `items` 走一次 [StageChain.itemsAfterMerge]：还在等合流的那棒，
     * 合流之后就是 done（落过库的旧卡只能在这一刻被洗干净）。**受阻不许开这个开关** ——
     * 没合进去的补丁，它的阶段项就该停在原地。
     */
    private fun JsonObjectBuilder.copyWithout(
        base: JsonObject?,
        dropped: Set<String>,
        advanceItems: Boolean = false,
    ) {
        base?.forEach { (key, value) ->
            if (key in dropped) return@forEach
            if (key == "items" && advanceItems) {
                val advanced = StageChain.itemsAfterMerge(value)
                if (advanced != null) put(key, advanced) else put(key, value)
            } else {
                put(key, value)
            }
        }
    }
}
