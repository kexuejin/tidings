// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain.screenshot

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.kapp.tidings.ui.desktop.ImeSmokePane
import com.kapp.tidings.ui.theme.AppTheme
import com.android.tools.screenshot.PreviewTest

/**
 * 「输入法自检」页的参考图。
 *
 * ## 这一帧能证明什么，不能证明什么
 * 能证明：这一页**装得起来** —— 三个输入框、观测台六行、两个按钮、返回，都不溢出、
 * 不重叠、深浅两色都可读；文案都在（漏一条资源会在编译期就红，但排不下要到渲染才知道）。
 *
 * **不能证明输入法行为。** 输入法六项（候选窗跟随光标、内联预编辑、上屏进回调、
 * 中英切换、退格删拼音、失焦 commit 还是丢弃）全都依赖平台输入法框架，
 * 无头 Layoutlib 里没有输入法，这里渲染出来的是**空框 + 空观测台**。
 * 那六项只能人工判，清单在 `design/host-surface-checklist.md`。
 * 这一帧的作用是保证"仪器本身没坏"，免得人工去测时先被一个布局 bug 骗了。
 *
 * ## 尺寸取的是真窗口
 * `720×560` 来自 `DesktopMetrics.SETTINGS_WINDOW_WIDTH_PX / _HEIGHT_PX`，
 * 与 `DesktopSettingsWindow` 实际开的窗口一致。用别的尺寸会漏掉真实的纵向裁切 ——
 * 设置窗本来就不高，这一页内容比一屏多，滚动是它的常态。
 *
 * 但**真窗口那一帧只看得到上半页**（第三个输入框和两个按钮都在折叠线以下），
 * 所以另录一帧 `desk-ime-smoke-tall`：同一页、同一个宽度、窗口放高到 800，
 * 让整页落在一张图里。它不代表任何真实窗口尺寸，纯粹是给评审看全貌的。
 */
// 主题外壳在每一帧里就地展开，而不是抽成 `private fun ImeSmoke(...)` 中转一层：
// 配对门禁只认帧函数体里直接出现的被判定 Composable（`design-preview-pairing.py` 的
// `COMPOSABLE_RE`），中子层函数名不在词表里，会让这一屏整屏判「无法判定」。
// `ImeSmokePane` 的可空参数 `onBack` 也必须在实参里写出来，门禁才算这一支武装过。

@PreviewTest
@Preview(name = "desk-ime-smoke", widthDp = 720, heightDp = 560, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskImeSmokePreview() {
    AppTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            ImeSmokePane(onBack = {})
        }
    }
}

/** 深色。设置窗跟随主窗主题，所以深色下这一页也必须自己站得住。 */
@PreviewTest
@Preview(name = "desk-ime-smoke-dark", widthDp = 720, heightDp = 560, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskImeSmokeDarkPreview() {
    AppTheme(darkTheme = true) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            ImeSmokePane(onBack = {})
        }
    }
}

/**
 * 整页帧。真窗口 560 高只够上半页，第三个输入框（`BasicTextField`，产品另一种原语）
 * 和「重置/返回」两个按钮会落在折叠线以下，评审看不到。这一帧把它们拉进画面。
 *
 * **它不是任何真实窗口尺寸**，不要拿它当尺寸断言。
 */
@PreviewTest
@Preview(name = "desk-ime-smoke-tall", widthDp = 720, heightDp = 800, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskImeSmokeTallPreview() {
    AppTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            ImeSmokePane(onBack = {})
        }
    }
}
