// SPDX-License-Identifier: GPL-3.0-only

package app.relay.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 图标尺寸档位（原型 → 代码的唯一落点）。
 *
 * ## 为什么要有这个文件
 *
 * 原型 `prototype/relay.html` 的 sprite 是**一套 stroke 语言**（`viewBox 0 0 24 24`、
 * `stroke-width:1.7`、`linecap/linejoin:round`），但每个使用点各给各的尺寸：
 * 实测 15 档（9/11/12/13/14/15/16/17/18/19/20/21/22/30/36），其中 16px 用了 12 次、
 * 18px 6 次、14/15 各 5 次。代码侧此前是 `Modifier.size(18.dp)` 这类**字面量**（图标语境
 * 实测 10 档），两边都对不上，于是"图标大小看着不对"没有任何地方可判。
 *
 * 收敛口径：
 * **按使用频次与相邻档合并**，而不是照搬原型的 15 档 —— 半像素/连号档（11/12/13、
 * 17/19/21）在手调时是"看着差不多就写一个"，归到最近档不损失可指认的差异。
 *
 * | 档 | 值 | 语义 | 原型侧来源（频次） |
 * |---|---|---|---|
 * | [Badge] | 11.dp | 角标/徽标内小图标 | 11px×2、12px×1 |
 * | [Inline] | 14.dp | 行内小图标（列表行、面包屑） | 14×5、13×3 |
 * | [Label] | 16.dp | 常规标签图标（按钮、横幅、工具栏） | **16×12**、15×5 |
 * | [Body] | 18.dp | 正文级图标（输入区、气泡附件） | **18×6**、17×4 |
 * | [Title] | 20.dp | 标题级图标（卡片头、空态小图） | 20×4、19×1、21×1、22×1 |
 * | [Hero] | 30.dp | 大图标（空态插图） | 30×1、36×1、9×1 |
 *
 * 用法：`Icon(painter, null, Modifier.size(IconSizes.Inline))` —— 不要写 `.size(14.dp)`。
 * 尺寸也要进消费率棘轮：新增字面量会被 `:designTokenUsageGate` 抓住。
 */
object IconSizes {
    /** 角标/徽标里的小图标（未读点、状态点内嵌）。 */
    val Badge: Dp = 11.dp

    /** 行内小图标：列表行尾、面包屑、次要动作。 */
    val Inline: Dp = 14.dp

    /** 常规标签图标：按钮、横幅、工具栏（原型最常用的一档）。 */
    val Label: Dp = 16.dp

    /** 正文级图标：输入区、气泡附件、卡片内主图标。 */
    val Body: Dp = 18.dp

    /** 标题级图标：卡片头、区块标题、空态小图。 */
    val Title: Dp = 20.dp

    /** 大图标：空态插图、引导页。 */
    val Hero: Dp = 30.dp
}
