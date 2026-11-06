// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** T-G6：派单 prompt 预算裁剪（§7.7 第一层）—— 预算内原样，超限头尾保留 + 如实标注。 */
class TaskPromptBudgetTest {

    @Test
    fun withinBudgetPassesThroughByteForByte() {
        val prompt = "重构网络层，跑回归"
        assertEquals(prompt, TaskPromptBudget.clamp(prompt, 100))
    }

    @Test
    fun overBudgetKeepsHeadAndTailWithHonestMarker() {
        val prompt = "H".repeat(600) + "MIDDLE" + "T".repeat(600)
        val clamped = TaskPromptBudget.clamp(prompt, 400)
        assertTrue(clamped.length <= 400 + TaskPromptBudget.TRUNCATION_MARKER.length, "预算约束")
        assertTrue(clamped.startsWith("HHH"), "头部保留")
        assertTrue(clamped.endsWith("TTT"), "尾部保留")
        assertTrue(clamped.contains("已截断"), "截断标注如实（不假装全文）")
        assertTrue(!clamped.contains("MIDDLE"), "中段确实被裁掉")
    }
}
