// SPDX-License-Identifier: GPL-3.0-only

package app.relay.feature.chat

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import app.relay.ui.theme.TextWeights
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography

/**
 * agent 消息正文的 Markdown 渲染。
 *
 * **只给 agent 的消息用，不给人发的消息用**（判据见 [shouldRenderMarkdown]）：agent 的回复
 * 天然是 Markdown（标题、列表、代码块、表格、链接、提示块），纯文本渲染会让用户看到
 * `**加粗**` 与 `- 列表` 的字面量；人发的消息反过来是「打到什么就是什么」，
 * 把 `*星号*` 当强调渲染会让用户以为自己打错了。
 *
 * 颜色一律从主题取，不写字面色：
 * - 正文跟气泡的 content color 走（[LocalContentColor]）—— 自己的气泡是
 *   `primaryContainer`、别人的是 `surfaceVariant`，两者的 content color 不同，
 *   写死 `onSurface` 会在自己的气泡上偏低对比度。
 * - 分隔线用 `outlineVariant`：本仓把这一槽定义为发丝线（对应原型的 `--hair`），
 *   与 Markdown 的水平线 / 表格边框是同一件事。
 * - 代码块与行内代码的底色不覆盖：库的默认值本来就是从 `MaterialTheme.colorScheme`
 *   读的，覆盖成别的槽等于在气泡里另发明一套「第几级容器」。
 *
 * 字号也跟气泡对齐：正文是 `bodyMedium`（与原来的纯文本一致），标题收成 title 档
 * 而不是库默认的 display 档 —— 聊天气泡里一行 36sp 的标题会把整屏吃掉。
 */
@Composable
internal fun MarkdownMessage(
    text: String,
    modifier: Modifier = Modifier,
) {
    val type = MaterialTheme.typography
    val body = type.bodyMedium
    val colors = markdownColor(
        text = LocalContentColor.current,
        dividerColor = MaterialTheme.colorScheme.outlineVariant,
    )
    val typography = markdownTypography(
        text = body,
        paragraph = body,
        ordered = body,
        bullet = body,
        list = body,
        quote = body,
        // 标题在聊天气泡里要有层级，但不能用库默认的 display 档（一行 36sp 会吃掉整屏）。
        // 每级都加粗：只靠字号差 1sp 的 titleSmall 在气泡里根本看不出来。
        h1 = type.titleLarge.copy(fontWeight = TextWeights.Emphasis),
        h2 = type.titleMedium.copy(fontWeight = TextWeights.Emphasis),
        h3 = type.titleSmall.copy(fontWeight = TextWeights.Emphasis),
        h4 = body.copy(fontWeight = TextWeights.Emphasis),
        h5 = body.copy(fontWeight = TextWeights.Emphasis),
        h6 = body.copy(fontWeight = TextWeights.Emphasis),
        table = type.bodySmall,
    )
    Markdown(
        remember(text) { withChatLineBreaks(text) },
        colors = colors,
        typography = typography,
        modifier = modifier,
    )
}

/**
 * 这条正文该不该按 Markdown 渲染。
 *
 * 判据只有一条：**agent 说的算 Markdown，人说的不算**。
 * - 自己的消息恒为 false：自己打出去的原文就是自己看到的，来回对照才不会以为发错了。
 * - 群聊里 agent 只是成员之一，所以要按**发送者**判，而不是按会话。
 */
internal fun shouldRenderMarkdown(mine: Boolean, senderIsAgent: Boolean): Boolean = !mine && senderIsAgent

/**
 * 把这些换行变成**硬换行**（行尾两个空格），只动「普通段落里的续行」。
 *
 * 为什么要做这件事：CommonMark 里单换行是 soft break，渲染出来是**空格**。
 * 在文档里这是对的（一段话就该接着排），在聊天气泡里是错的 —— agent 常这样回：
 *
 * ```
 * 先跑一遍全量向量
 * 再把日志贴我
 * ```
 *
 * 折成一行之后用户读到的是「先跑一遍全量向量 再把日志贴我」，两件事挤成一件。
 * 而人发的消息是纯文本、换行天然保留 —— 不处理的话，同一屏里两种换行行为。
 *
 * 边界刻意收得很窄，**只碰两端都是普通段落行的那条缝**：
 * - 代码围栏里面一个字都不改（行尾加空格会改代码本身）；
 * - 空行（段落分隔）不动；
 * - 任何一端是块级起始（标题 / 列表 / 引用 / 表格 / 围栏）就不动 ——
 *   那些块的语法已经在表达「这一行结束了」，再加硬换行只会和它们的解析打架。
 */
internal fun withChatLineBreaks(text: String): String {
    if (!text.contains('\n')) return text
    val lines = text.split('\n')
    val out = StringBuilder(text.length + lines.size)
    var inFence = false
    for (index in lines.indices) {
        val line = lines[index]
        val fence = line.trimStart()
        val isFenceLine = fence.startsWith("```") || fence.startsWith("~~~")
        if (isFenceLine) inFence = !inFence
        out.append(line)
        if (index == lines.lastIndex) break
        val next = lines[index + 1]
        val keepBreak = !inFence &&
            !isFenceLine &&
            line.isNotBlank() &&
            next.isNotBlank() &&
            !startsBlock(line) &&
            !startsBlock(next)
        if (keepBreak) out.append("  ")
        out.append('\n')
    }
    return out.toString()
}

/** 这一行是不是块级结构的开头（这些块自己就表示换行了，不要再加硬换行）。 */
private fun startsBlock(line: String): Boolean {
    val t = line.trimStart()
    if (t.isEmpty()) return true
    if (t.startsWith("#") || t.startsWith(">") || t.startsWith("|")) return true
    if (t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ")) return true
    if (t.startsWith("- [") || t.startsWith("* [")) return true
    val dot = t.indexOf('.')
    if (dot in 1..9 && t.take(dot).all { it.isDigit() }) return true
    return false
}
