// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.ic_moon
import com.kapp.tenon.generated.resources.set_appear_card_title
import com.kapp.tenon.generated.resources.set_appear_subtitle
import com.kapp.tenon.generated.resources.set_more_appearance_title
import com.kapp.tenon.ui.SectionCard
import org.jetbrains.compose.resources.stringResource

/**
 * 设置 · 外观（原型 `screenSetAppear()`，`prototype/relay.html:3888`）。
 */
@Composable
fun SettingsAppearSubpage(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    themeMode: String = THEME_MODE_SYSTEM,
    onThemeModeChange: ((String) -> Unit)? = null,
) {
    var theme by rememberSaveable(themeMode) { mutableStateOf(themeMode) }

    SettingsPageScaffold(
        title = stringResource(Res.string.set_more_appearance_title),
        subtitle = stringResource(Res.string.set_appear_subtitle),
        onBack = onBack,
        modifier = modifier,
    ) {
        SectionCard(
            title = stringResource(Res.string.set_appear_card_title),
            icon = Res.drawable.ic_moon,
        ) {
            ThemeSegments(selected = theme, onSelect = { mode ->
                theme = mode
                onThemeModeChange?.invoke(mode)
            })
        }
    }
}
