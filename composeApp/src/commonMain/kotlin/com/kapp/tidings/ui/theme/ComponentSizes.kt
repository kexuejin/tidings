// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * **组件尺寸**（不是间距、也不是图标）：一个 UI 元素自身的宽高。
 *
 * ## 为什么单独一组
 *
 * 间距（[AppSpacing]）回答"元素之间隔多远"，图标（[IconSizes]）回答"图标画多大"。
 * 头像/缩略图这类"元素自身多大"两者都不属于 —— 塞进间距会让 `designScreenDiffGate`
 * 把组件尺寸当成一档间距去和原型比（原型侧只取 padding/margin/gap），于是报出
 * "代码多了 26" 这种**口径不对称**的假差集。
 *
 * ## 取值来源
 *
 * 原型 `prototype/relay.html` 的 `.msg .mav{width:26px;height:26px}` —— 气泡内头像。
 * 代码侧原来在 `ChatBubbles` 里直接写 `26.dp`，同一个数字出现两次（头像尺寸 + 对齐用
 * 的 Spacer 宽度）：两处必须相等，否则文字与头像不齐。收成一个常量，就是为了让"必须相等"
 * 变成类型上的事实而不是注释里的提醒。
 */
object ComponentSizes {
    /** 气泡内头像边长（原型 `.msg .mav` 26px）。跟随行文字的缩进也必须是这个值。 */
    val MessageAvatar: Dp = 26.dp
}
