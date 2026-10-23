// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * 形状语义角色（原型 → 代码的统一落点）。
 *
 * ## 为什么要独立成组而不是塞进 M3 Shapes
 *
 * M3 的 Shapes 只有 5 个槽（extraSmall..extraLarge），值域固定；原型渲染真值里
 * 圆角档外大户各有自己的**家族集中度**（tools/design-render-truth.py --offladder-md 附录）：
 *
 * | 角色 | 值 | 原型侧来源（渲染槽位） | 为什么不是一个偶数档 |
 * |---|---|---|---|
 * | [Avatar] | 9.dp | radius 9×1980：.fi2×504、.mav×444、.sm×436、.pri×168…（8 个头像/徽标族共用） | snap 到 8 会压平头像与按钮的层次；代码侧已存在 13 处 9.dp 字面量（census 下界），扩档=承认既成事实 |
 * | [ListCard] | 11.dp | radius 11×672：**620 处集中在 .ri 一个行卡族** | 行卡比 controls(12) 小一档是刻意的嵌套层次（列表里套按钮）|
 * | [DayCell] | 7.dp | radius 7×660：.hsdays×420（历史搜索日期格） | 日期格是密排小格，8dp 在 24px 格上会糊成圆 |
 *
 * 2/6/5/13/20 等低频值**不在这里**：裁决把它们留给并排截图后就近归并（D 组），
 * 逐值扩档会重蹈「25 种取值灌档位」的老路。
 *
 * 用法：Modifier.clip(ShapeRoles.Avatar) —— 不要写 RoundedCornerShape(9.dp)。
 * 消费率进 :designTokenUsageGate 棘轮；新增档外字面量会被 :designContractDriftGate 抓住。
 */
object ShapeRoles {
    /** 头像/徽标类圆角。 */
    val Avatar: Shape = RoundedCornerShape(9.dp)

    /** 行卡（列表条目里的嵌套卡）圆角。 */
    val ListCard: Shape = RoundedCornerShape(11.dp)

    /** 密排日期格圆角。 */
    val DayCell: Shape = RoundedCornerShape(7.dp)
}
