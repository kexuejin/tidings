// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.chat

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * EXT-4 状态词表的「单源」守卫。
 *
 * 三条断言分别钉住三件事：
 * 1. 卡片解析的 status 映射只剩一份，且 steps / checks 两处都调用它；
 * 2. 这唯一一份映射的真值表，两条解析路径得到同一档（含未知值退 Pending）；
 * 3. 审批状态的文案 / 底色 / 字色三张表已合成一处，不再各自 when(status)。
 *
 * 为什么守源码而不只测行为：同义词表抄两遍时行为完全正确，只有"抄了几遍"
 * 这件事类型系统与运行时都表达不出来 —— 只能在源码文本上钉住。
 */
class AgentCardStatusSingleSourceTest {

    private val parseSource =
        "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/chat/AgentCardParse.kt"
    private val approvalSource =
        "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/agents/AgentApprovalCards.kt"

    /** steps 与 checks 曾经各抄一份映射；合并后形状只该剩一份，且两处都调同一个函数。 */
    @Test
    fun stepStatusMappingIsWrittenOnlyOnce() {
        val live = GuardSourceScan.stripComments(GuardSourceScan.read(parseSource))
        val marker = "\"done\", \"review_ready\" ->"
        assertEquals(
            1,
            Regex(Regex.escape(marker)).findAll(live).count(),
            "steps 与 checks 的 status 映射必须来自同一个 stepStatusOf，形状 $marker 只允许出现一次",
        )
        val call = "status = stepStatusOf(obj.str(\"status\"))"
        assertEquals(
            2,
            Regex(Regex.escape(call)).findAll(live).count(),
            "steps 与 checks 两处解析都必须调用同一个 stepStatusOf",
        )
    }

    /** 唯一映射的真值表；items（steps）与 checks 两条路径都必须得到同一档。 */
    @Test
    fun stepStatusTruthTableHoldsForBothParsePaths() {
        val cases = listOf(
            "done" to AgentCardStepStatus.Done,
            "review_ready" to AgentCardStepStatus.Done,
            "running" to AgentCardStepStatus.Running,
            "working" to AgentCardStepStatus.Running,
            "failed" to AgentCardStepStatus.Failed,
            "error" to AgentCardStepStatus.Failed,
            "blocked" to AgentCardStepStatus.Blocked,
            "brand_new_state" to AgentCardStepStatus.Pending,
            null to AgentCardStepStatus.Pending,
        )
        for ((raw, expected) in cases) {
            assertEquals(expected, statusFromItems(raw), "items 路径：$raw")
            assertEquals(expected, statusFromChecks(raw), "checks 路径：$raw")
        }
    }

    /**
     * statusText / badgeColor / textColor 曾经是三张各自 when(status) 的表；
     * 合并后不许再出现这三处局部变量，也不许再有 when(approval.status) 分表。
     */
    @Test
    fun approvalStatusTablesAreFoldedIntoOneMapping() {
        val live = GuardSourceScan.stripComments(GuardSourceScan.read(approvalSource))
        for (leftover in listOf("val statusText", "val badgeColor", "val textColor")) {
            assertTrue(
                leftover !in live,
                "$leftover 必须并入 approvalStatusStyleOf，不许再各自成表",
            )
        }
        val perStatusWhen = Regex("when\\s*\\((?:approval\\.)?status\\)")
        assertTrue(
            perStatusWhen.findAll(live).none(),
            "审批状态不允许再各自 when(status)：文案 / 底色 / 字色必须来自同一个纯数据映射",
        )
    }

    // ---- 两条解析路径：都构造一张最小合法卡（cardId + rev + 一个带 label 的项） ----

    private fun statusFromItems(raw: String?): AgentCardStepStatus {
        val item = if (raw == null) """{"label":"x"}""" else """{"label":"x","status":"$raw"}"""
        val json = Json.parseToJsonElement("""{"cardId":"c","rev":1,"items":[$item]}""") as JsonObject
        return parseAgentCard(json)!!.steps.single().status
    }

    private fun statusFromChecks(raw: String?): AgentCardStepStatus {
        val item = if (raw == null) """{"label":"x"}""" else """{"label":"x","status":"$raw"}"""
        val json = Json.parseToJsonElement("""{"cardId":"c","rev":1,"checks":[$item]}""") as JsonObject
        return parseAgentCard(json)!!.checks.single().status
    }
}
