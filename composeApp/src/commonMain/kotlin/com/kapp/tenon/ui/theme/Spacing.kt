// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// 档位 = 设计契约（design-spec.json 的 tokens.spacing 由代码投影而来，代码是唯一权威源）。
// 曾按一张裁决表扩入三个「事实档位」：原型里 6px×62 / 10px×61 / 14px×35 处全部落在旧档位之间，
// 就近 snap 会在同一选择器里与邻档合流（层次消失），所以是扩、不是收。
// 命名 = 相邻语义名 + 数值（sm6 在 xs4 与 sm8 之间）。
//
// ## 冻结令（审计 B4 裁决，见 ADR-022）
//
// 17 档到本行为止，**不再新增档位**。相邻 1dp 的连号档（9/10/11、12/13/14）是
// 历史手调的快照，不是可以继续生长的序列——任何人再写「就差 1dp」的新档，
// 得到的不是更贴合的间距，而是第三个人写布局时「olg11 还是 lg14」的无据可依。
// 新代码的选档顺序：先用语义名（xxs/xs/sm/md/lg/xl/xxl/xxxl 八档主力），
// 原型逐值对齐确需连号档时才用带数字的别名，且**只消费、不扩张**。
// 棘轮（designTokenUsageGate）管住「绕开 token 写字面量」，这条注释管住「发明新档」。
@Immutable
data class AppSpacing(
    val xxs: Dp = 2.dp,
    val oxs3: Dp = 3.dp,
    val xs: Dp = 4.dp,
    val oxs5: Dp = 5.dp,
    val sm6: Dp = 6.dp,
    val osm7: Dp = 7.dp,
    val sm: Dp = 8.dp,
    val osm9: Dp = 9.dp,
    val md10: Dp = 10.dp,
    val olg11: Dp = 11.dp,
    val md: Dp = 12.dp,
    val olg13: Dp = 13.dp,
    val lg14: Dp = 14.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    val xxxl: Dp = 48.dp,
)

val LocalAppSpacing = staticCompositionLocalOf { AppSpacing() }
