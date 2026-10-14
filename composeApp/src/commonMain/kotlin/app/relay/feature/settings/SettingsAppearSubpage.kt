// SPDX-License-Identifier: GPL-3.0-only

package app.relay.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.relay.generated.resources.Res
import app.relay.generated.resources.ic_moon
import app.relay.generated.resources.set_appear_card_title
import app.relay.generated.resources.set_appear_subtitle
import app.relay.generated.resources.set_more_appearance_hint
import app.relay.generated.resources.set_more_appearance_title
import app.relay.generated.resources.r2_set_appear_hint
import androidx.compose.foundation.layout.padding
import app.relay.ui.SectionCard
import app.relay.ui.theme.LocalAppSpacing
import org.jetbrains.compose.resources.stringResource

/**
 * 设置 · 外观（原型 `screenSetAppear()`，`prototype/relay.html:3888`）。
 *
 * 原型这一页只有一张卡「主题」＋ `.seg` 三档（跟随系统 / 浅色 / 深色，浅色带
 * `aria-pressed="true"`）＋ 卡下一句 hint，标题栏是它自己的「外观 / 主题」。
 *
 * 没跟「通知」合成一页的理由与所有子页相同：一张 `@Preview` 拍不出两张原型页，
 * 合起来就等于那两行台账**没有可拍的对象**（不是缺图）。
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
            Hint(stringResource(Res.string.set_more_appearance_hint))
        }
        // 原型 `screenSetAppear()` 的**页级** `.hint`（`prototype/relay.html:3898-3899`）。
        Hint(
            text = stringResource(Res.string.r2_set_appear_hint),
            modifier = Modifier.padding(horizontal = LocalAppSpacing.current.xs),
        )
    }
}
