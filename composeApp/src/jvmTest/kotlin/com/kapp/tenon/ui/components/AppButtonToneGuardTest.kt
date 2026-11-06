// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 按钮 tone / size 的分支必须穷尽（轮 18 / EXT-5 回潮守卫）。
 *
 * 改造前 `AppButtons.kt` 有四处 `else ->`：实心按钮的 colors、描边与纯文字按钮的
 * contentColor、以及紧凑档之外的圆角。新增一档 `AppButtonTone` 时，穷尽 `when`
 * 会编译红、带 `else` 的那几支却静默落到 Primary/Neutral 的颜色上 —— 一半靠编译器、
 * 一半靠人记得，正是「两把尺子」最便宜的一种。
 *
 * 第二条钉住 `when (tone)` 的处数：再加一处分派就要回来问是不是该复用同一张样式表。
 */
class AppButtonToneGuardTest {

    private val path = "composeApp/src/commonMain/kotlin/com/kapp/tenon/ui/components/AppButtons.kt"

    private fun live(): String {
        val file = listOf(
            java.io.File(path),
            java.io.File(path.removePrefix("composeApp/")),
        ).firstOrNull { it.exists() }
        checkNotNull(file) { "找不到 AppButtons.kt" }
        return file.readText()
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .lineSequence()
            .map { it.substringBefore("//") }
            .joinToString("\n")
    }

    @Test
    fun toneAndSizeBranchesAreExhaustive() {
        val hits = Regex("""\belse\s*->""").findAll(live()).count()
        assertEquals(0, hits, "tone/size 的 when 不许有 else 兜底：新增档位必须编译红，不能静默漂移")
    }

    @Test
    fun toneDispatchSitesStayCounted() {
        val hits = Regex("""when \(tone\) \{""").findAll(live()).count()
        assertEquals(6, hits, "tone 的分派点数量变了：新增请复用现有样式，别再抄一份 when")
    }
}
