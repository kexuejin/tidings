// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kapp.tidings.ui.theme.IconSizes
import com.kapp.tidings.ui.theme.LocalAppSpacing
import com.kapp.tidings.ui.theme.accents

/**
 * 开关（全应用**唯一**一份实现）。
 *
 * 形状取自原型 `.sw`：42×24 的圆角轨 + 18dp 圆钮，选中时轨道用 primary、否则用
 * `--surface-3`（[com.kapp.tidings.ui.theme.RelayAccents.surface3]）。桌面设置页此前有一份
 * 自绘实现、手机设置页用的是 M3 `Switch`（52×32）—— 同一个开关在两个壳里两个形状，
 * 正是 AGENTS §2.6 点名的"同一元素两份定义"。这里收成一份：
 *
 * - **默认调用方**（桌面设置页）视觉不变；
 * - 手机设置页换过来会**变窄**（52×32 → 42×24）。那是朝原型靠拢，但要由一次设计帧
 *   对比来确认，不能顺手改 —— 所以手机侧的替换留给 lead 与其帧一起做。
 *
 * ## 为什么 `onCheckedChange` 可空
 *
 * 与 M3 `Switch` 同一个语义：传 `null` 表示**这一行**（外层 `toggleable` 的 Row）负责
 * 切换，开关自己只是状态显示。手机设置页的行就是这么组织的 —— 让共享组件支持这两种
 * 调用方式，替换时才不需要把行结构一起改。
 *
 * ## 触控
 *
 * 命中区由内部这层 `padding(md)` 在 `toggleable` **之内**撑开（轨 42×24 + 两侧/上下 12），
 * 得到 66×48 —— 48dp 是硬下限；视觉尺寸不跟着变大。
 */
@Composable
fun AppSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val sp = LocalAppSpacing.current
    val knobOffset by animateDpAsState(if (checked) 21.dp else 3.dp, label = "appSwitchKnob")
    val interactive = Modifier
        .toggleable(
            value = checked,
            enabled = enabled && onCheckedChange != null,
            role = Role.Switch,
            onValueChange = { onCheckedChange?.invoke(it) },
        )
        .padding(horizontal = sp.md, vertical = sp.md)
    Box(modifier = if (onCheckedChange != null) modifier.then(interactive) else modifier) {
        Box(
            modifier = Modifier
                .size(width = 42.dp, height = sp.xl)
                .clip(MaterialTheme.shapes.medium)
                .background(if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.accents.surface3)
                .alpha(if (enabled) 1f else 0.45f),
        ) {
            Box(
                modifier = Modifier
                    .padding(start = knobOffset, top = LocalAppSpacing.current.oxs3)
                    .size(IconSizes.Body)
                    .background(Color.White, CircleShape),
            )
        }
    }
}