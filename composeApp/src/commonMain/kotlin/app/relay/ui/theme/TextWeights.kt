// SPDX-License-Identifier: GPL-3.0-only

package app.relay.ui.theme

import androidx.compose.ui.text.font.FontWeight

/**
 * 字重语义角色定义。
 *
 * 原型强调层用 700（渲染真值 weight 700×514：.mav×111、.tt×93、.dot×88、.ell×83 ——
 * 名字与条目标题族），但代码侧 M3 Typography 的 15 个角色里**没有一个**是 Bold，
 * 且这些角色已上线：把现有角色改粗 = 静默重排已上线字阶，禁。
 * 与 IconSizes 同一先例：给独立命名 token，不动 shipped 体系。
 *
 * 用法：style = MaterialTheme.typography.titleSmall.copy(fontWeight = TextWeights.Emphasis)
 * 900×30（.title 演示孤值）与 650×2 按裁决 D 收敛到本档/600，不配新角色。
 */
object TextWeights {
    /** 名字/条目标题的强调层（= Bold 700）。 */
    val Emphasis: FontWeight = FontWeight.Bold
}
