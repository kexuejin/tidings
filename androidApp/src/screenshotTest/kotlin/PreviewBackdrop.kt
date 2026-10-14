// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import app.relay.ui.theme.AppTheme
import app.relay.ui.theme.LocalCardShape

/**
 * 直调帧的底衬：补一层主题与背景。
 *
 * 这四层原来在四个文件里各写一遍（`PaneBackdrop` / `HostBackdrop` /
 * `DialogBackdrop` / `BareScreen`），每份还都带着一段解释「为什么复制而不复用
 * 另一个」的注释 —— 那个注释本身就是重复的代价：一旦几份里有一份改了，
 * 另几份的注释就开始说谎，而门禁看不出注释在撒谎。
 *
 * **第一行必须是 [AppTheme]**：被拍的屏**假设自己被主题包着**。漏掉这一层，
 * 整屏落到 M3 基线紫（头像、徽标、按钮全变默认紫），而 `--precheck`、`--dup`、
 * 参考图配对判定**全部是绿的** —— 只有开图才看得见。这不是假想：本仓
 * `AgentsPhoneShell` 与 `ScreenshotPreviewsDesktopPanes` 各栽过一次。
 *
 * @param cardShape 桌面窗里的面板要用 12dp 圆角（原型 `.win{--r-card:12px}`，
 *   `prototype/relay.html:584`），不是 `LocalCardShape` 的默认 16dp —— 生产里
 *   这些面板永远在 `DesktopShell` 之下、已经被钉成 12dp。传 `null` 表示原样继承
 *   （手机/平板形态）。
 * @param innerModifier 给内容的那层 Modifier（`BareScreen` 要一个 `fillMaxSize`；
 *   传 `null` 表示只保证背景铺满，不替内容决定尺寸）。
 */
@Composable
internal fun PreviewBackdrop(
    darkTheme: Boolean = false,
    cardShape: Shape? = null,
    innerModifier: Modifier? = null,
    content: @Composable () -> Unit,
) {
    AppTheme(darkTheme = darkTheme) {
        val body: @Composable () -> Unit = {
            Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
                if (innerModifier != null) {
                    Box(modifier = innerModifier) { content() }
                } else {
                    content()
                }
            }
        }
        if (cardShape != null) {
            CompositionLocalProvider(LocalCardShape provides cardShape) { body() }
        } else {
            body()
        }
    }
}
