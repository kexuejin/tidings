// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.ui.theme

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
    /** 气泡内头像边长（原型 `.msg .mav` 26px，**桌面档**）。跟随行文字的缩进也必须是这个值。 */
    val MessageAvatar: Dp = 26.dp

    /**
     * 群聊气泡内头像的**手机档**边长。
     *
     * 26dp（[MessageAvatar]）是桌面 Web 原型的取值，桌面继续用；手机上首字模式下
     * 字号只剩 26 × 0.42 ≈ 11sp，群里认人费劲 —— 提到 32dp。随
     * `ChatScreenChrome.messageAvatarSize` 由手机壳传入，与折叠占位 Spacer 共用
     * 同一个常量，「必须相等」在类型上成立。
     */
    val MessageAvatarPhone: Dp = 32.dp

    /**
     * 会话行头像的**手机档**边长（原型手机屏 `.row.conv .av{width:48px;height:48px}`）。
     *
     * 这个档在原型里一直存在，但此前代码把间距 token（`sp.xxl`=32）当头像尺寸用 ——
     * 间距回答「元素隔多远」，头像回答「元素自身多大」，两类不能混（见本文件头注释）。
     * 收进这里后，行组件不再从间距语境里借尺寸。
     */
    val RowAvatar: Dp = 48.dp

    /**
     * 会话行头像的**桌面密集档**边长。原型 `.winset .li .fi2` 是 26px，取最接近的整档 24。
     *
     * 此前这里写的是 `IconSizes.Hero - IconSizes.Badge` 的减法：实算 30−11=19dp，
     * 而注释声称 24 —— 两端从写下去那天就没对上过（守卫只钉接线不钉值，没接住）。
     * 图标档相减拼不出头像尺寸：头像属于组件尺寸，直接在这里给值。
     */
    val RowAvatarDense: Dp = 24.dp

    /**
     * 空态图形头的圆底直径（`AppStates.EmptyGlyph`）。圆底/图标是「元素自身多大」，
     * 属于组件尺寸而不是间距；收进这里让空态头的两处尺寸（圆底 + 内嵌图标）
     * 有同一份口径可引。
     */
    val EmptyGlyphCircle: Dp = 64.dp
}
