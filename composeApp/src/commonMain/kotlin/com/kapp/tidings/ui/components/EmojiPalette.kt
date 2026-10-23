// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import com.kapp.tidings.ui.theme.LocalAppSpacing

/**
 * 原型 `prototype/relay.html:2056` 的 `EMOJI`：39 个常用表情，**顺序也一致**。
 *
 * 为什么照抄而不是自己挑：表情不走协议，它就是普通 `text` 里的字符，所以「选哪些」
 * 没有任何技术约束，纯属设计决定；设计稿已经决定了，这里再挑一遍只会让两份稿子漂移。
 *
 * 放在 `ui/components` 而不是留在桌面输入区里：手机的表情面板和桌面的浮层是两种布局
 * （鼠标格子小、手指格子大），但**列哪些表情**必须是同一份 —— 两边各存一份列表，
 * 早晚会一边加了一个、另一边没有，而没人会注意到。
 */
public val EMOJI_PALETTE = listOf(
    "😀", "😄", "😅", "😂", "🙂", "😉", "😊", "😍",
    "🤔", "😐", "😴", "😢", "😭", "😤", "😳", "🥺",
    "👍", "👎", "🙏", "👏", "🎉", "💪", "🤝", "👌",
    "🔥", "✨", "💡", "📌", "✅", "❌", "⏰", "📎",
    "☕", "🍜", "🌙", "☀️", "🐛", "🚀", "🎯",
)

/** 网格列数。抽出来是为了让测试能钉住"最后一行只有 7 个、必须补空位"这件事。 */
internal const val EMOJI_COLUMNS: Int = 8

private val EmojiCellHeight = 44.dp
private val EmojiPanelMaxHeight = 260.dp

/**
 * 手机上的表情面板：贴在输入栏下面的一块托盘，8 列网格。
 *
 * 与桌面的 [com.kapp.tidings.ui.desktop.EmojiPopup] 有三处刻意的不同，都是手指与鼠标的差别：
 * 单格 44dp 而不是 30dp（触控目标下限）、字号 22sp 而不是 17sp、不画
 * 「表情按 UTF-8 计」那行页脚 —— 手机输入栏的状态行本来就实时显示字节数，
 * 再说一遍是同一件事说两次。
 *
 * 高度是**固定上限**而不是跟着键盘走：根层 `WindowInsets.safeDrawing` 已经把 IME
 * 算进去了，组件内部再读 `WindowInsets.ime` 只会读到 0，做不出"贴着键盘顶"的效果。
 * 39 个表情在 8 列下正好 5 行（约 220dp），上限之内一次放得下，滚动只是给将来加表情留的余地。
 */
@Composable
internal fun EmojiPalettePanel(
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        // 与输入药丸同一个容器色，看起来是同一块区域长出来的。
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = EmojiPanelMaxHeight)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LocalAppSpacing.current.xs, vertical = LocalAppSpacing.current.sm6),
        ) {
            EMOJI_PALETTE.chunked(EMOJI_COLUMNS).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.xxs),
                ) {
                    row.forEach { emoji ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(EmojiCellHeight)
                                .clickable { onPick(emoji) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(emoji, fontSize = 22.sp)
                        }
                    }
                    // 最后一行只有 7 个。不补空位的话，那 7 个会被拉得比上面每格更宽，
                    // 网格看起来就散了。
                    repeat(EMOJI_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
