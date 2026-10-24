// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kapp.tidings.testsupport.GuardSourceScan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 顶栏动作行的**间距补偿**：48dp 触控框把视觉缝撑大这件事，必须由算术而不是注释来收尾。
 *
 * ## 现场
 *
 * `.ibtn` 的视觉砖是 34dp（聊天页 30dp），为了 48dp 无障碍触控下限，[IbtnBox] 把它包进
 * `spacing.xxxl`（48dp）的命中框。于是每颗砖左右各多出 (48 − 34) / 2 = 7dp 内缩：
 * 行 gap 若照抄原型的 `gap:6px`，**眼睛看到的**砖间缝是 6 + 7 + 7 = 20dp（聊天页 24dp）。
 * 之前那一版没算这笔账，KDoc 还写着「6dp 间距」—— 注释与像素各说各的。
 *
 * ## 三层验证（缺一层就不是「测到了」）
 *
 * 1. 纯函数：补偿值按算术给，且**夹到 0**（负 gap 会让相邻 48dp 命中区重叠、把目标压回
 *    48dp 以下）；同时有一条「足够大的原型 gap 仍原样保留差额」的正例，区分
 *    「真的按算术补偿」与「恒返回 0」。
 * 2. 常量对账：砖边长与触控 token 从源码里读出来，跟测试里的字面量对齐 ——
 *    有人改了 34/30 或 48 而没重算，这里就红。
 * 3. 接线：顶栏动作行必须真的走 [topBarActionRowGap]，不能退回 `spacedBy(6.dp)`。
 *    纯函数再对，调用点不用它也等于没修（本仓「测试绿 ≠ 测到了」的老形态）。
 *
 * 反向验证：把 `Arrangement.spacedBy(6.dp)` 放回 LargeTitleBar，第 3 条红；
 * 把 `coerceAtLeast(0.dp)` 改成 `+ 0.dp`（允许负值），第 1 条红。
 */
class TopBarActionsGapTest {

    private val topBarsPath =
        "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/components/TopBars.kt"
    private val spacingPath =
        "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/theme/Spacing.kt"

    /** 原型 `.topbar .acts{gap:6px}` 的直译值。 */
    private val prototypeGap = 6.dp

    @Test
    fun theCompensationIsClampedSoAdjacentTouchTargetsNeverOverlap() {
        // 34 砖：6 − (48 − 34) = −8 ⇒ 夹到 0（不是负值）。
        assertEquals(
            0.dp,
            topBarActionRowGap(touchTarget = touchTarget(), brick = ibtnSize(), prototypeGap = prototypeGap),
            "34dp 砖的补偿值必须是 0（夹住），负 gap 会让相邻 48dp 命中区互相覆盖",
        )
        // 聊天页 30 砖：6 − (48 − 30) = −12 ⇒ 同样夹到 0。
        assertEquals(
            0.dp,
            topBarActionRowGap(touchTarget = touchTarget(), brick = chatIbtnSize(), prototypeGap = prototypeGap),
            "30dp 砖的补偿值也必须是 0（夹住）",
        )
    }

    @Test
    fun aPrototypeGapWiderThanTheInsetKeepsItsDifference() {
        // 正例：区分「按算术补偿」与「恒返回 0」。原型 gap 够大时差额要保留。
        assertEquals(6.dp, topBarActionRowGap(touchTarget = 48.dp, brick = 34.dp, prototypeGap = 20.dp))
        assertEquals(16.dp, topBarActionRowGap(touchTarget = 48.dp, brick = 34.dp, prototypeGap = 30.dp))
    }

    @Test
    fun theVisualGapMatchesTheArithmeticInTheComment() {
        val rowGap = topBarActionRowGap(touchTarget(), ibtnSize(), prototypeGap)
        assertEquals(
            14.dp,
            rowGap + (touchTarget() - ibtnSize()),
            "补偿后 34dp 砖之间实际是 14dp（48dp 触控框的代价），注释里的数字必须与这条一致",
        )
        val chatRowGap = topBarActionRowGap(touchTarget(), chatIbtnSize(), prototypeGap)
        assertEquals(
            18.dp,
            chatRowGap + (touchTarget() - chatIbtnSize()),
            "补偿后聊天页 30dp 砖之间实际是 18dp",
        )
    }

    @Test
    fun theGeometryConstantsStillMatchTheDesignContract() {
        // 这些字面量是设计契约（原型 34/30），测试自己也拿它们算 —— 有人改常量就会两边对不上。
        assertEquals(34.dp, ibtnSize(), "IbtnSize 又变了 —— 顶栏 .ibtn 是 34dp 方块")
        assertEquals(30.dp, chatIbtnSize(), "ChatIbtnSize 又变了 —— 聊天页 .ibtn 是 30dp 方块")
        assertEquals(48.dp, touchTarget(), "触控目标 token 又变了 —— 48dp 是无障碍下限的唯一样本")
    }

    @Test
    fun theActionsRowActuallyGoesThroughTheCompensation() {
        val live = GuardSourceScan.stripComments(GuardSourceScan.read(topBarsPath))
        // 不能直接用 bracedBlockAfter：LargeTitleBar 的形参默认值里有 `actions = {}`，
        // 朴素的「marker 之后第一个 {」会把它当成函数体，取出一对空花括号。
        // 这里先跳过平衡的形参列表，再取函数体。
        val bar = bodyAfterSignature(live, "fun LargeTitleBar(")
        assertTrue(
            bar.contains("topBarActionRowGap("),
            "顶栏动作行没有走 topBarActionRowGap：48dp 触控框会把视觉缝撑到 20dp（聊天页 24dp），" +
                "而纯函数测试仍全绿 —— 这正是「测试绿 ≠ 测到了」",
        )
        assertFalse(
            bar.contains("Arrangement.spacedBy(6.dp)"),
            "顶栏动作行又写死成 spacedBy(6.dp) 了 —— 那是把 48dp 触控框的内缩当成不存在",
        )
    }

    /* ===== 源码切片小工具 ===== */

    /** 跳过形参列表（其中可能有 `{}` 默认值），再取函数体的一对花括号内容。 */
    private fun bodyAfterSignature(text: String, marker: String): String {
        val at = text.indexOf(marker)
        if (at < 0) throw AssertionError("源码里找不到：$marker")
        var i = text.indexOf('(', at)
        var depth = 0
        while (i < text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) break
                }
            }
            i++
        }
        val open = text.indexOf('{', i)
        if (open < 0) throw AssertionError("$marker 后面没有函数体：$marker")
        var bodyDepth = 0
        for (j in open until text.length) {
            when (text[j]) {
                '{' -> bodyDepth++
                '}' -> {
                    bodyDepth--
                    if (bodyDepth == 0) return text.substring(open + 1, j)
                }
            }
        }
        throw AssertionError("$marker 的函数体花括号没有闭合")
    }

    /* ===== 从源码读常量：测试与生产用同一个数 ===== */

    private fun dpConstant(name: String): Dp {
        val text = GuardSourceScan.stripComments(GuardSourceScan.read(topBarsPath))
        // 前置负向断言：`IbtnSize` 不能命中 `ChatIbtnSize` 的尾巴。
        val match = Regex("(?<![A-Za-z0-9_])" + Regex.escape(name) + "\\s*=\\s*([0-9]+(?:\\.[0-9]+)?)\\.dp").find(text)
            ?: throw AssertionError("TopBars.kt 里找不到常量 $name = <N>.dp")
        return match.groupValues[1].toFloat().dp
    }

    private fun ibtnSize() = dpConstant("IbtnSize")

    private fun chatIbtnSize() = dpConstant("ChatIbtnSize")

    private fun touchTarget(): Dp {
        val text = GuardSourceScan.stripComments(GuardSourceScan.read(spacingPath))
        val match = Regex("val xxxl: Dp = ([0-9]+(?:\\.[0-9]+)?)\\.dp").find(text)
            ?: throw AssertionError("Spacing.kt 里找不到 xxxl token")
        return match.groupValues[1].toFloat().dp
    }
}
