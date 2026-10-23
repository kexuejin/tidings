// SPDX-License-Identifier: GPL-3.0-only

// 组件族自身的门禁帧。
//
// 为什么需要它：`AppButtons.kt` 与 `PressableSurface.kt` 建好之后**零截图覆盖** ——
// 它们只在"被某个屏用到"时才会间接出现在别人的帧里，而 tone × size × 有无图标
// 的组合里大部分根本没有屏用到。没有帧就等于：**新组件层最该被看的东西没人看过**。
//
// ## 这一帧里**故意没有** AppIconButton
//
// 它需要 `DrawableResource`（图标），而 `androidApp` 的 screenshotTest 源集**看不见**
// `org.jetbrains.compose.resources.DrawableResource` ——
// `Cannot access class 'org.jetbrains.compose.resources.DrawableResource'`。
// 这是本仓既有的类路径缺口（`libs.versions.toml` 里那条「compose.ui 不随 platform
// 变体传递」的同一个坑），不是组件的问题。
//
// 与其为了让帧编译过去去动 build 配置，不如**如实留下这个缺口**：
// AppIconButton 的视觉覆盖改由"它被哪个屏用到"承担（目前 0 调用点），
// 已记进主计划 §0.5 的待办。**把缺口写下来，比用一张编不出来的帧假装覆盖了要好。**
//
// ## 名称前缀
//
// `Components` 必须在 `stateSlots.unattributablePrefixes` 里：这一帧**不渲染任何屏**，
// 硬按屏去归属只会得到一条假缺口。
package com.kapp.tidings.domain.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.kapp.tidings.ui.components.AppButton
import com.kapp.tidings.ui.components.AppButtonSize
import com.kapp.tidings.ui.components.AppButtonTone
import com.kapp.tidings.ui.components.AppOutlinedButton
import com.kapp.tidings.ui.components.AppTextButton
import com.kapp.tidings.ui.components.PressableSurface
import com.kapp.tidings.ui.theme.AppTheme
import com.kapp.tidings.ui.theme.LocalAppSpacing
import com.android.tools.screenshot.PreviewTest

@PreviewTest
@Preview(name = "components-buttons", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ComponentsButtonsPhonePreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().padding(LocalAppSpacing.current.lg),
                verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md),
            ) {
                // —— 实心按钮：两档尺寸 + 危险色 ——
                Text("AppButton / Medium", style = MaterialTheme.typography.labelSmall)
                AppButton(text = "Medium", onClick = {})

                Text("AppButton / Small", style = MaterialTheme.typography.labelSmall)
                AppButton(text = "Small", onClick = {}, size = AppButtonSize.Small)

                Text("AppButton / Danger", style = MaterialTheme.typography.labelSmall)
                AppButton(text = "Danger", onClick = {}, tone = AppButtonTone.Danger)

                Text("AppButton / 禁用", style = MaterialTheme.typography.labelSmall)
                AppButton(text = "Disabled", onClick = {}, enabled = false)

                // —— 描边按钮：中性 / 危险 / 禁用 ——
                Text("AppOutlinedButton / Neutral", style = MaterialTheme.typography.labelSmall)
                AppOutlinedButton(text = "Outlined", onClick = {})

                Text("AppOutlinedButton / Danger", style = MaterialTheme.typography.labelSmall)
                AppOutlinedButton(text = "Clear all", onClick = {}, tone = AppButtonTone.Danger)

                Text("AppOutlinedButton / 禁用", style = MaterialTheme.typography.labelSmall)
                AppOutlinedButton(text = "Disabled", onClick = {}, enabled = false)

                // —— 文字按钮 ——
                Text("AppTextButton", style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm)) {
                    AppTextButton(text = "Neutral", onClick = {})
                    AppTextButton(text = "Danger", onClick = {}, tone = AppButtonTone.Danger)
                }

                // —— 可点原语：这一帧是它第一个可见使用者 ——
                Text("PressableSurface（可按压表面，不是按钮）", style = MaterialTheme.typography.labelSmall)
                PressableSurface(
                    onClick = {},
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Row(
                        modifier = Modifier.padding(LocalAppSpacing.current.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm6),
                    ) {
                        Text("按下去缩到 0.97", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
