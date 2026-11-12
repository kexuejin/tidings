// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ErrorDistillerTest {

    @Test
    fun shortErrorsArePreservedVerbatim() {
        val short = "Build failed: syntax error on line 4"
        assertEquals(short, ErrorDistiller.distill(short))
    }

    @Test
    fun emptyStringProducesEmptyOutput() {
        assertEquals("", ErrorDistiller.distill(""))
        assertEquals("", ErrorDistiller.distill("   \n\t  "))
    }

    @Test
    fun extractsKeyErrorLinesAndFollowUpContext() {
        val raw = buildString {
            repeat(20) { appendLine("INFO: Preparing and scanning classpath step $it...") }
            appendLine("src/Main.kt:42:15: error: unresolved reference 'foo'")
            appendLine("    val x = foo()")
            appendLine("            ^")
            repeat(10) { appendLine("INFO: Compiling other files $it...") }
            appendLine("src/Other.kt:10:5: error: cannot find symbol")
            appendLine("    bar.baz()")
            appendLine("    ^")
            repeat(10) { appendLine("INFO: Finishing up $it...") }
            appendLine("FAILURE: Build failed with an exception.")
            appendLine("BUILD FAILED in 3s")
        }

        val distilled = ErrorDistiller.distill(raw)

        assertTrue(distilled.contains("error: unresolved reference 'foo'"))
        assertTrue(distilled.contains("val x = foo()"))
        assertTrue(distilled.contains("error: cannot find symbol"))
        assertTrue(distilled.contains("FAILURE: Build failed with an exception."))
        // Unrelated info lines at the beginning shouldn't bloat the distilled text if keyword lines are matched
        assertFalse(distilled.contains("INFO: Preparing and scanning classpath step 0..."))
    }

    @Test
    fun fallbackToTailLinesWhenNoKeywordsMatch() {
        val lines = (1..30).map { "Log step $it" }.joinToString("\n")
        val distilled = ErrorDistiller.distill(lines)
        assertTrue(distilled.contains("Log step 30"))
        assertTrue(distilled.contains("Log step 20"))
        assertFalse(distilled.contains("Log step 1\n"))
    }

    @Test
    fun withinLineBudgetButOverCharBudgetIsStillDistilled() {
        // 15 行以内、但总量超 1500 字符 —— 行数预算放行不了它，必须走蒸馏
        val raw = buildString {
            appendLine("src/Big.kt:1:1: error: something is broken")
            appendLine("    " + "x".repeat(1600))
        }

        val distilled = ErrorDistiller.distill(raw)

        assertTrue(distilled.contains("error: something is broken"), "超字符预算时关键词行仍要被抽出来")
        assertTrue(
            distilled.length <= ErrorDistillerBudget.CHARS + 3,
            "蒸馏产物不得超过字符上限（含省略号），实际 " + distilled.length,
        )
    }

    @Test
    fun beyondLineBudgetWithNoKeywordsFallsBackToTailFifteen() {
        // 16 行纯噪音：行数超预算、无关键词 —— 回退取末尾 15 行
        val raw = (1..16).joinToString("\n") { "plain line $it" }

        val distilled = ErrorDistiller.distill(raw)

        assertTrue(distilled.contains("plain line 16"))
        assertTrue(distilled.contains("plain line 2"))
        assertFalse(distilled.contains("plain line 1\n"), "末尾 15 行意味着第一行被挤掉")
    }

    @Test
    fun charCapAppendsEllipsisMarker() {
        // 无关键词的长尾回退，拼出一条超过 1500 字符的输出，验证硬截断带 "..."
        val raw = (1..30).joinToString("\n") { "row $it " + "p".repeat(100) }

        val distilled = ErrorDistiller.distill(raw)

        assertTrue(distilled.endsWith("..."), "硬截断必须以 ... 收尾")
        assertEquals(
            ErrorDistillerBudget.CHARS + 3,
            distilled.length,
            "截断长度应为 1500 + 3 个省略号字符",
        )
    }

    @Test
    fun keywordHitsKeepTheFirstCauseAndTheLastFailure() {
        // 20 行全是命中行：既不能只取前 15（会丢掉末尾的 Caused by/断言），
        // 也不能只取末尾（会丢掉首因）。判据是首因 + 末因都在，行数仍 <= 15。
        val raw = (1..20).joinToString("\n") { "step $it: error: boom $it" }

        val distilled = ErrorDistiller.distill(raw)

        assertTrue(distilled.lines().size <= 15, "蒸馏产物行数上限是 15，实际 ${distilled.lines().size}")
        assertTrue(distilled.contains("boom 1"), "首因必须保留")
        assertTrue(distilled.contains("boom 20"), "末因必须保留")
    }

    /** 镜像 [ErrorDistiller] 的私有上限，供断言引用（改主源常量时这里必须跟着改）。 */
    private object ErrorDistillerBudget {
        const val CHARS = 1500
        const val LINES = 15
    }
}
