// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.guards

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 源码级守卫：**聊天流里「占气泡位」的卡片，外壳必须与文字气泡同口径**。
 *
 * ## 钉的是哪一类事故
 *
 * 「气泡去边框」是一条全屏裁决（正文气泡撤掉发丝边，分离改由色块对比承担），
 * 但它只改了 `ChatBubbles` 外层的 `bubbleBorder`。文件卡 / PK 卡 / 屏幕协助卡
 * 属于 `isCustomCard`：外层容器透明、`bubblePad = 0`，**边框是它们自己那一层
 * `Surface` 画的** —— 于是同一屏里文字气泡没有边、文件卡还有一条 0.5dp 发丝边，
 * 圆角也停在 12dp（`--r-bubble` 是 16dp）。这不是某一处漏改，而是"没人管"的角落：
 * `fidelity-deviations.yaml` 里也没有登记。
 *
 * 更贵的同类：`PkCardView` 与 `FileCardView` 的容器参数是**逐字复制**的
 * （白底 / 0.5dp 发丝边 / 12dp）。只改一处，同一会话里两张自定义卡就是两种语言。
 *
 * ## 为什么只能在源码层守
 *
 * 本仓没有 Compose UI 测试基建，`Surface` 的 `border` / `shape` 是编译期参数，
 * 单测读不到；而截图门禁的基线里**本来就没有**"收到的文件卡"这一帧（那一轮提交
 * 自己记了这个量具缺口）。所以判据写在这里：每个气泡位卡片的**外壳 `Surface`**
 * 必须是 `RelayShapes.large` 且不传边框。
 *
 * 扫描非空、锚点必须全部命中 —— 遍历空集合会让「没有 offender」恒真，
 * 这是本仓源码门禁最常见的假绿形态（见 `GuardSourceScan.resolve`）。
 */
class BubbleCardChromeGuardTest {

    /** 锚点：函数名 → 它所在文件。四个组件各自只有一个外壳 `Surface`。 */
    private val bubbleCards = listOf(
        "fun FileCardView(" to "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/chat/FileCardView.kt",
        "fun PkCardView(" to "composeApp/src/commonMain/kotlin/com/kapp/tenon/ui/components/PkPalette.kt",
        "fun ScreenHistoryCard(" to "composeApp/src/commonMain/kotlin/com/kapp/tenon/ui/components/ScreenHistoryCard.kt",
        "fun InlineImagePreview(" to "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/chat/ChatApproval.kt",
    )

    private val callOpen = Regex("""\bSurface\s*\(""")

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

    /** 只标记最外层参数：嵌套调用（比如参数里塞了个子 `Surface`）里的键不算。 */
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

    private data class Shell(
        val anchor: String,
        val path: String,
        val line: Int,
        val args: String,
    )

    private fun shellOf(anchor: String, path: String): Shell {
        val text = GuardSourceScan.stripComments(GuardSourceScan.read(path))
        val anchorAt = text.indexOf(anchor)
        assertTrue(
            anchorAt >= 0,
            "锚点找不到：`$anchor` 不在 $path —— 函数改名/搬文件了？守卫必须跟着改，" +
                "静默跳过等于这条纪律没人守。",
        )
        val m = callOpen.find(text, anchorAt)
        assertTrue(m != null, "`$anchor` 之后再也找不到 `Surface(` —— 外壳被删或换了别的容器？")
        val open = m!!.range.last
        val args = parenBlock(text, open)
        assertTrue(args.isNotBlank(), "`$anchor` 的外壳参数块取不到（括号不成对），守卫无法判定")
        return Shell(
            anchor = anchor,
            path = path,
            line = text.substring(0, m.range.first).count { it == '\n' } + 1,
            args = args,
        )
    }

    @Test
    fun bubbleSlotCardsShareTheBubbleChrome() {
        val offenders = mutableListOf<String>()
        for ((anchor, path) in bubbleCards) {
            val shell = shellOf(anchor, path)
            val mask = topLevelMask(shell.args)
            val borderHits = Regex("""\bborder\s*=""").findAll(shell.args)
                .filter { mask[it.range.first] }
                .filterNot { hit ->
                    // `border = null` 是**显式声明「这张卡不画边」**，合规且值得留着
                    // （它把"忘了传"和"刻意不传"区分开）。只有真的给了 BorderStroke 才算越界。
                    shell.args.substring(hit.range.last + 1).trimStart().startsWith("null")
                }
                .toList()
            if (borderHits.isNotEmpty()) {
                offenders += "${shell.path}:${shell.line} ${shell.anchor} 仍传 `border =`（气泡位不画描边）"
            }
            val shapeHit = Regex("""\bshape\s*=""").findAll(shell.args)
                .firstOrNull { mask[it.range.first] }
            if (shapeHit == null) {
                offenders += "${shell.path}:${shell.line} ${shell.anchor} 外壳没有显式 `shape =`"
            } else {
                val from = shapeHit.range.last + 1
                val expr = shell.args.substring(from, (from + 60).coerceAtMost(shell.args.length))
                if (!expr.contains("RelayShapes.large")) {
                    offenders +=
                        "${shell.path}:${shell.line} ${shell.anchor} 外壳形状不是 RelayShapes.large" +
                            "（取到：${expr.substringBefore(',').trim().take(40).ifEmpty { "<空>" }}）"
                }
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "聊天流里的气泡位卡片必须与文字气泡同口径（--bubble-peer / 16dp / 无描边）：\n  " +
                offenders.joinToString("\n  ") +
                "\n注意：内外两层形状要一起改 —— 只改内层会立刻复现「只有左上角是圆的」断角" +
                "（见 FileCardStyleTest）。",
        )
    }

    @Test
    fun theScanActuallySeesAllFourCards() {
        // 防「锚点全部找不到 ⇒ 上面那条的循环体一次都没进 ⇒ 恒绿」。
        for ((anchor, path) in bubbleCards) {
            val shell = shellOf(anchor, path)
            assertTrue(
                shell.args.contains("Surface") || shell.args.isNotBlank(),
                "读不到 ${shell.anchor} 的外壳参数块",
            )
        }
        assertTrue(bubbleCards.size >= 4, "气泡位卡片的清单被删到 ${bubbleCards.size} 个 —— 这是漏守的信号")
    }
}
