// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.core.protocol.Limits
import com.kapp.tenon.testsupport.GuardSourceScan
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P2 规格 2 用例 2.1/2.2：全生命周期只占 1 个卡槽，长文本外置成 Artifact，
 * 序列化卡严格 ≤ 5 KiB（独立于 24 KiB 协议红线），超限不截断。
 */
class TicketCardBudgetTest {

    private fun cardWithHugePatch(): String = buildJsonObject {
        put("cardId", "chain-c1")
        put("rev", 7)
        put("type", "task-board")
        put("state", "blocked")
        put("title", "重构持久层接口")
        put("progress", 40)
        put("banner", buildJsonObject {
            put("text", "x".repeat(2000 * 40))
            put("tone", "error")
        })
        put("items", buildJsonArray {
            add(buildJsonObject {
                put("id", "开发")
                put("label", "开发")
                put("status", "failed")
                put("detail", "patch line\n".repeat(2000))
            })
        })
    }.toString()

    @Test
    fun aHugePatchIsExternalizedAndTheCardStaysUnderFiveKiB() {
        val sink = mutableListOf<Pair<String, String>>()
        val outcome = TicketCardBudget.enforce(cardWithHugePatch()) { name, text ->
            sink += name to text
            true
        }
        assertTrue(outcome is TicketCardBudget.Outcome.Sent, "外置后应正常发出，实际 $outcome")
        assertTrue(FrameBudgetInspector.withinSoftLimit(outcome.json),
            "卡必须 ≤ 5 KiB，实测 ${outcome.bytes} B")
        assertTrue(sink.size >= 2, "banner 与棒详情两块长文本都要落盘，实际 ${sink.size} 块")
        assertTrue(sink.any { it.second.length > 2000 }, "落盘的必须是全文，不是截断后的尾巴")
        assertTrue(outcome.json.contains("[artifact "), "卡内只留 Artifact 指针，不能夹带长文本")
        assertTrue(outcome.json.contains("chain-c1"), "外置不许换 cardId（单卡槽）")
    }

    @Test
    fun anOverBudgetStructureGetsAMinimalCardInsteadOfTruncation() {
        val huge = buildJsonObject {
            put("cardId", "chain-c2")
            put("rev", 3)
            put("type", "task-board")
            put("state", "executing")
            put("title", "多棒流水线")
            put("items", buildJsonArray {
                repeat(300) { i ->
                    add(buildJsonObject {
                        put("id", "step-$i")
                        put("label", "reviewer stage number $i")
                        put("status", "done")
                    })
                }
            })
        }.toString()
        val outcome = TicketCardBudget.enforce(huge) { _, _ -> true }
        assertTrue(outcome is TicketCardBudget.Outcome.Minimal,
            "结构性超预算要换最小卡，不许把原 JSON 截断，实际 $outcome")
        assertTrue(FrameBudgetInspector.withinSoftLimit(outcome.json), "最小卡也必须 ≤ 5 KiB")
        assertTrue(outcome.json.contains("chain-c2"), "最小卡沿用同一 cardId：群消息增量恒 1")
        assertTrue(outcome.json.contains("artifact") || outcome.json.contains("Artifact"),
            "要说明详情去了哪里，不能静默丢信息")
    }

    @Test
    fun aFailedArtifactSinkKeepsTheOriginalTextInsteadOfDroppingIt() {
        val original = cardWithHugePatch()
        val outcome = TicketCardBudget.enforce(original) { _, _ -> false }
        assertTrue(outcome is TicketCardBudget.Outcome.Sent)
        assertTrue(outcome.json.contains("patch line"), "落盘失败时不外置、不丢原文，交给 24 KiB 硬闸门裁决")
    }

    @Test
    fun aNormalCardPassesThroughByteForByte() {
        val normal = buildJsonObject {
            put("cardId", "chain-c3")
            put("rev", 1)
            put("state", "working")
            put("title", "小工单")
        }.toString()
        val outcome = TicketCardBudget.enforce(normal) { _, _ -> true }
        assertEquals(normal, outcome.json, "不超预算的卡一个字节都不改")
    }

    @Test
    fun theSoftBudgetIsIndependentOfTheProtocolRedLine() {
        assertEquals(5 * 1024, TicketCardBudget.SOFT_LIMIT_BYTES)
        assertTrue(Limits.CARD_BYTES > TicketCardBudget.SOFT_LIMIT_BYTES,
            "5 KiB 是编排层软预算，24 KiB 仍是协议硬红线，两者不许互相顶替")
        assertEquals(5120, FrameBudgetInspector.measure("x".repeat(5120)))
    }

    @Test
    fun theBudgetIsWiredIntoTheTicketCardPublishPath() {
        val orchestrator = GuardSourceScan.stripComments(
            GuardSourceScan.read("orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopWorkerOrchestrator.kt"),
        )
        assertTrue(orchestrator.contains("TicketCardBudget.enforce("),
            "5 KiB 软预算必须真的挂在发卡前，不能只有一个纯函数")
        assertTrue(orchestrator.contains("File(dir, name).writeText(text)"),
            "外置的长文本要真的落盘，不是只在内存里换个指针")
        assertTrue(orchestrator.contains("val json: String = budget.json"),
            "发出去的必须是预算裁决后的卡，不是原卡")
    }

    @Test
    fun theSingleCardSlotReusesOneMessageRow() {
        val actions = GuardSourceScan.stripComments(
            GuardSourceScan.read("app/src/commonMain/kotlin/com/kapp/tenon/domain/RelayControllerAgentCardActions.kt"),
        )
        assertTrue(actions.contains("cardBroadcastMutex.withLock"),
            "同 cardId 的读改写必须串行，否则 rev 会撞")
        assertTrue(actions.contains("host.handleReplaceMessage("),
            "后续帧走原位替换：群消息增量恒 1，不是每步追加一条")
        assertTrue(actions.contains("existingSlot"), "有槽位才走替换；没槽位才 append 首帧")
    }
}
