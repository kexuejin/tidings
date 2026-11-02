// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import com.kapp.tidings.core.protocol.Limits
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P2P 大产物直投通道的**判据**测试（升级计划 §4.2-3）。
 *
 * 三组边界都刻意用合成输入钉死：
 * 1. 预算纯函数（正好 24 KiB / 超 1 B / 空产物）；
 * 2. 自描述条目组装（intent / delivered / mime 补齐，其余键原样保留）；
 * 3. 直投请求收集（文件不存在返回 null —— 宁缺毋假）。
 *
 * 反向验证记录：删掉 ArtifactPipe 里 needsPipeDelivery 的 `>` 边界（改成 `>=`），
 * 「正好等于预算放得下」变红；删掉 upgradeArtifactsSelfDescribing 的 intent/delivered
 * 组装，对应断言全红 —— 测试在测东西，不是恒真式。
 */
class ArtifactPipeTest {

    // ── 阈值纯函数：预算来源与边界 ──────────────────────────────────────────

    @Test
    fun cardBytesComesFromLimitsNotACopy() {
        assertEquals(
            Limits.CARD_BYTES.toLong(),
            ArtifactPipe.CARD_BYTES,
            "决策阈值必须引用 Limits.CARD_BYTES 本体，重抄数字会让上限改动时发送侧悄悄失配",
        )
    }

    @Test
    fun budgetAtExactLimitIsFull() {
        // 正好 24 KiB 的卡体：余量 = 0（预算被卡面内容占满）。
        assertEquals(0L, ArtifactPipe.inlineBudgetLeft(Limits.CARD_BYTES.toLong()))
    }

    @Test
    fun budgetOneByteOverLimitIsZeroNotNegative() {
        assertEquals(
            0L,
            ArtifactPipe.inlineBudgetLeft(Limits.CARD_BYTES.toLong() + 1L),
            "负余量没有意义，调用方只需要「还能不能放」",
        )
    }

    @Test
    fun emptyCardBodyLeavesWholeBudget() {
        assertEquals(Limits.CARD_BYTES.toLong(), ArtifactPipe.inlineBudgetLeft(0L))
    }

    @Test
    fun pipeNeededOnlyWhenStrictlyOverBudget() {
        // 正好等于余量：放得下，不走直投 —— 与协议侧 > 拒绝边界同一把尺。
        assertFalse(
            ArtifactPipe.needsPipeDelivery(artifactBytes = 100L, cardBodyBytes = Limits.CARD_BYTES - 100L),
            "产物恰好等于余量时放得下，不算超",
        )
        // 超 1 B：必须直投 —— 卡片 JSON 截断后解不了，发送侧不生产注定被拒的帧。
        assertTrue(
            ArtifactPipe.needsPipeDelivery(artifactBytes = 101L, cardBodyBytes = Limits.CARD_BYTES - 100L),
            "超 1 B 也必须走直投：塞进卡就是一帧注定被拒的帧",
        )
    }

    @Test
    fun oversizedPatchOverEmptyCardNeedsPipe() {
        // 空产物（0 B）永远不需要直投。
        assertFalse(ArtifactPipe.needsPipeDelivery(artifactBytes = 0L, cardBodyBytes = 0L))
        // 40 KiB 的全量补丁塞进空卡也超 24 KiB 预算：必须直投。
        assertTrue(ArtifactPipe.needsPipeDelivery(artifactBytes = 40L * 1024, cardBodyBytes = 0L))
    }

    // ── 意图键与 mime ────────────────────────────────────────────────────────

    @Test
    fun knownArtifactNamesMapToIntentKeys() {
        assertEquals(ArtifactPipe.INTENT_PATCH_FULL, ArtifactPipe.intentForArtifactName("patch.diff"))
        assertEquals(ArtifactPipe.INTENT_TEST_REPORT, ArtifactPipe.intentForArtifactName("report.json"))
        assertEquals(ArtifactPipe.INTENT_RUN_LOG, ArtifactPipe.intentForArtifactName("task.log"))
        assertEquals(ArtifactPipe.INTENT_PROTOTYPE, ArtifactPipe.intentForArtifactName("prototype.html"))
    }

    @Test
    fun unknownArtifactNameHasNoIntent() {
        assertNull(
            ArtifactPipe.intentForArtifactName("mystery.bin"),
            "认不出的产物不编通用词：渲染端对 null 的处置是「不画意图行」",
        )
    }

    @Test
    fun mimeFollowsExtension() {
        assertEquals("text/x-diff", ArtifactPipe.mimeForArtifactName("patch.diff"))
        assertEquals("text/markdown", ArtifactPipe.mimeForArtifactName("report.md"))
        assertEquals("application/json", ArtifactPipe.mimeForArtifactName("report.json"))
        assertEquals("text/plain", ArtifactPipe.mimeForArtifactName("task.log"))
        assertEquals("text/html", ArtifactPipe.mimeForArtifactName("prototype.html"))
    }

    // ── 自描述条目组装 ──────────────────────────────────────────────────────

    private fun cardWithArtifacts(entries: String): JsonObject =
        Json.parseToJsonElement(entries) as JsonObject

    @Test
    fun upgradeAddsIntentAndDeliveredKeepingOtherKeys() {
        val card = cardWithArtifacts(
            """{"cardId":"chain-t1","rev":7,"state":"review_ready",
                "artifacts":[{"name":"patch.diff","bytes":40000,"mime":"text/x-diff"}]}""",
        )
        val upgraded = ArtifactPipe.upgradeArtifactsSelfDescribing(
            card = card,
            deliveredNames = setOf("patch.diff"),
        )
        val entry = assertNotNull(upgraded["artifacts"]).let {
            (it as kotlinx.serialization.json.JsonArray).first() as JsonObject
        }
        assertEquals("patch.diff", (entry["name"] as JsonPrimitive).content)
        assertEquals(40000L, (entry["bytes"] as JsonPrimitive).content.toLong())
        assertEquals(ArtifactPipe.INTENT_PATCH_FULL, (entry["intent"] as JsonPrimitive).content)
        assertEquals(true, (entry["delivered"] as JsonPrimitive).content == "true" || entry["delivered"]!!.toString() == "true")
        // 其余卡键一个都不能动：渲染端的解析契约就认这些。
        assertEquals("chain-t1", (upgraded["cardId"] as JsonPrimitive).content)
        assertEquals(7, (upgraded["rev"] as JsonPrimitive).content.toInt())
    }

    @Test
    fun deliveredReflectsFactsNotWishes() {
        val card = cardWithArtifacts(
            """{"cardId":"chain-t2","rev":1,"artifacts":[
                {"name":"patch.diff","bytes":100},{"name":"task.log","bytes":300}]}""",
        )
        // 只有 patch.diff 真的投出去了：另一条必须如实标 false。
        val upgraded = ArtifactPipe.upgradeArtifactsSelfDescribing(card, setOf("patch.diff"))
        val entries = assertNotNull(upgraded["artifacts"]).let {
            (it as kotlinx.serialization.json.JsonArray).map { e -> e as JsonObject }
        }
        val patch = entries.first { (it["name"] as JsonPrimitive).content == "patch.diff" }
        val log = entries.first { (it["name"] as JsonPrimitive).content == "task.log" }
        assertTrue(patch["delivered"]!!.toString() == "true", "投递过的事实要标出来")
        assertTrue(log["delivered"]!!.toString() == "false", "没投出去的产物不许谎报 delivered")
        assertEquals(
            ArtifactPipe.INTENT_RUN_LOG,
            (log["intent"] as JsonPrimitive).content,
            "task.log 的意图键是 run-log（与 intentForArtifactName 的词表一致）",
        )
    }

    @Test
    fun missingMimeIsFilledFromName() {
        val card = cardWithArtifacts(
            """{"cardId":"chain-t3","rev":1,"artifacts":[{"name":"patch.diff","bytes":5}]}""",
        )
        val upgraded = ArtifactPipe.upgradeArtifactsSelfDescribing(card, emptySet())
        val entry = assertNotNull(upgraded["artifacts"]).let {
            (it as kotlinx.serialization.json.JsonArray).first() as JsonObject
        }
        assertEquals("text/x-diff", (entry["mime"] as JsonPrimitive).content)
    }

    @Test
    fun cardWithoutArtifactsPassesThroughUnchanged() {
        val card = cardWithArtifacts("""{"cardId":"chain-t4","rev":1,"state":"working"}""")
        assertEquals(
            card,
            ArtifactPipe.upgradeArtifactsSelfDescribing(card, emptySet()),
            "没有产物段的卡原样返回，不凭空造出空数组",
        )
    }

    // ── 直投请求收集 ────────────────────────────────────────────────────────

    @Test
    fun requestForExistingFileCarriesFacts() {
        val tmp = kotlin.io.path.createTempFile(prefix = "patch", suffix = ".diff").toFile()
        try {
            tmp.writeText("x".repeat(1024))
            val req = assertNotNull(ArtifactPipe.requestFor(tmp))
            assertEquals("text/x-diff", req.mime)
            assertEquals(ArtifactPipe.INTENT_PATCH_FULL, req.intent)
            assertEquals(tmp.length(), req.bytes)
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun requestForMissingFileIsNull() {
        assertNull(
            ArtifactPipe.requestFor(java.io.File("/nonexistent/patch.diff")),
            "文件不在盘上就不出请求：宁缺毋假，卡上不许留一行打不开的产物",
        )
    }

    @Test
    fun requestsCardBytesIsMeasurable() {
        val tmp = kotlin.io.path.createTempFile(prefix = "task", suffix = ".log").toFile()
        try {
            tmp.writeText("log")
            val req = assertNotNull(ArtifactPipe.requestFor(tmp))
            val single = ArtifactPipe.requestsCardBytes(listOf(req))
            val pair = ArtifactPipe.requestsCardBytes(listOf(req, req))
            assertTrue(single > 0L)
            // 单条序列化是 [X]（请求 + 双括号），两条是 [X,X]（请求 ×2 + 双括号 + 逗号）：
            // pair = single × 2 − 1 —— 差值必须**精确**可推导，这正是「预算数学可测」的锚。
            assertEquals(single * 2 - 1L, pair, "数组序列化的字节账必须逐字节可推导")
        } finally {
            tmp.delete()
        }
    }
}
