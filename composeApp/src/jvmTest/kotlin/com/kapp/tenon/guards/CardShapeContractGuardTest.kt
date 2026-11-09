// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.guards

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 源码级守卫：**凡是画边框的卡片，必须显式声明自己的形状**。
 *
 * ## 钉的是哪一类事故
 *
 * M3 1.9.0 的 `Surface` 私有 `surface()` 在 modifier 链末尾**无条件** `.clip(shape)`，
 * 而 `Surface` 不传 `shape` 时默认值是 `RectangleShape`。于是「写了 `border =` 却漏了
 * `shape =`」的卡片是**直角裁剪 + 直角描边**：注释里写着 12dp，屏幕上四个角全是断口。
 * `ScreenHistoryCard` 真机上就是这一例（KDoc 逐字写了 `RelayShapes.medium`，代码里
 * 一次都没用上）；`ChatBubbles` 的文件卡则是同一机理的另一种形态（父层形状与子层
 * 描边不一致，见 `FileCardStyleTest`）。
 *
 * 与 [DeadButtonGuardTest] 同一取舍：本仓没有 Compose UI 测试基建，形状/裁剪这类
 * 「两层路径是否重合」的判据只能在源码文本上钉住；先剥注释，再按平衡括号取调用参数块。
 *
 * ## 为什么是「全生产源码扫」而不是只盯那两个文件
 *
 * 用户要的是「全部 review 一轮并修复」，不是逐个补丁。把不变量写成全仓扫描，
 * 下一次谁再加一张带边框的卡、忘了传 shape，测试当场红，而不是等真机截图。
 * 扫描面必须非空且命中数必须够 —— 遍历空集合 / 正则失效都会让「没有 offender」恒真，
 * 这是本仓源码门禁最常见的假绿形态（见 [GuardSourceScan.resolveDir]）。
 */
class CardShapeContractGuardTest {

    private val sourceRoots = arrayOf(
        "composeApp/src/commonMain",
        "composeApp/src/androidMain",
        "composeApp/src/iosMain",
        "composeApp/src/jvmMain",
        "composeApp/src/jvmSharedMain",
        "androidApp/src/main",
        "desktopApp/src/main",
    )

    private val cardCall = Regex("""\b(?:Surface|Card|OutlinedCard|ElevatedCard)\s*\(""")
    private val borderArg = Regex("""\bborder\s*=""")
    private val shapeArg = Regex("""\bshape\s*=""")

    private data class BorderedCard(
        val path: String,
        val line: Int,
        val kind: String,
        val hasShape: Boolean,
        val shapeIsRectangle: Boolean,
    ) {
        override fun toString(): String = "$path:$line ($kind)"
    }

    /** 取 `open` 处那对平衡圆括号的内部；不成对时返回空串（守卫会在下面把空串当 offender）。 */
    private fun parenBlock(text: String, open: Int): String {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return text.substring(open + 1, i)
                }
            }
        }
        return ""
    }

    /**
     * 标出参数块里哪些下标处于**最外层**。
     *
     * 只看最外层，是为了不被嵌套调用里的 `border =` / `shape =` 骗到：
     * 例如 `modifier = Modifier.border(...)` 或参数里塞了个子 `Surface(...)`。
     * 取参数块而不是全文 contains，与 [GuardSourceScan.parenBlockAfter] 同一纪律。
     */
    private fun topLevelMask(block: String): BooleanArray {
        val mask = BooleanArray(block.length)
        var depth = 0
        for (i in block.indices) {
            mask[i] = depth == 0
            when (block[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
            }
        }
        return mask
    }

    private fun scan(): List<BorderedCard> {
        val files = GuardSourceScan.kotlinFiles(*sourceRoots)
        assertTrue(
            files.size > 200,
            "只扫到 ${files.size} 个生产源文件 —— 扫描根写错了？（空清单会让这条守卫永远绿）",
        )
        val hits = mutableListOf<BorderedCard>()
        for (path in files) {
            val text = GuardSourceScan.stripComments(GuardSourceScan.read(path))
            for (m in cardCall.findAll(text)) {
                val open = m.range.last
                if (text.getOrNull(open) != '(') continue
                val args = parenBlock(text, open)
                val mask = topLevelMask(args)
                if (!borderArg.findAll(args).any { mask[it.range.first] }) continue
                val shapeMatches = shapeArg.findAll(args).filter { mask[it.range.first] }.toList()
                val shapeExpr = if (shapeMatches.isEmpty()) {
                    ""
                } else {
                    val from = shapeMatches.first().range.last + 1
                    args.substring(from, (from + 90).coerceAtMost(args.length))
                }
                hits += BorderedCard(
                    path = path,
                    line = text.substring(0, m.range.first).count { it == '\n' } + 1,
                    kind = m.value.trim().removeSuffix("(").trim(),
                    hasShape = shapeMatches.isNotEmpty(),
                    shapeIsRectangle = shapeExpr.contains("RectangleShape"),
                )
            }
        }
        return hits
    }

    @Test
    fun everyBorderedCardDeclaresItsShape() {
        val hits = scan()
        // 阈值是「扫描没失效」的哨兵，不是棘轮：从 20 降到 19 是因为文件卡与 PK 卡
        // 撤掉了自己画的那条发丝边（气泡位不画描边，见 [BubbleCardChromeGuardTest]），
        // 带边框的卡少了一张。仍要求接近全仓真实命中数 —— 正则失效时会掉到个位数。
        assertTrue(
            hits.size >= 19,
            "只找到 ${hits.size} 处带边框的卡片 —— 正则/扫描根失效？命中数太少会让下面那条恒真",
        )
        val missing = hits.filter { !it.hasShape }
        assertTrue(
            missing.isEmpty(),
            "带 `border =` 却没有 `shape =` 的卡片会退化成 M3 的 RectangleShape" +
                "（直角裁剪 + 直角描边，真机上就是断角）：\n  " +
                missing.joinToString("\n  ") +
                "\n请显式传 `shape = RelayShapes.*` 或同形状的 token。",
        )
    }

    @Test
    fun noBorderedCardExplicitlyUsesRectangleShape() {
        // 光有 `shape =` 还不够：写 `shape = RectangleShape` 一样是直角卡片，
        // 只是把「忘了声明」变成了「声明成错的」。这类显式方形目前一处都不该有。
        val hits = scan()
        val square = hits.filter { it.shapeIsRectangle }
        assertTrue(
            square.isEmpty(),
            "带边框的卡片显式用了 RectangleShape：\n  " + square.joinToString("\n  "),
        )
    }
}
