// SPDX-License-Identifier: GPL-3.0-only

package app.relay.feature.chat

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Markdown 正文的两条底线。
 *
 * 这里**不渲染 Compose**（本仓没有 compose ui-test 依赖），测的是「把正文交给渲染器之后，
 * 用户看到的字是什么」—— 走渲染器自己的纯函数路径（inline 注解），所以能断言到
 * "星号有没有被吃掉""换行还在不在"这一层。
 */
class MarkdownMessageTest {

    @Test
    fun stripsInlineMarkers() {
        val out = render("**bold** and `code` and ~~gone~~")
        // 行内代码两侧各带一个空格（渲染器给代码底色留的呼吸位），所以只断言"标记没了"。
        assertEquals("bold and code and gone", out.text.trim().replace("  ", " "))
        assertFalse(out.text.contains('*'))
        assertFalse(out.text.contains('`'))
    }

    @Test
    fun singleLineBreakSurvivesAsLineBreak() {
        // agent 常靠单换行分行（两件事两行）。CommonMark 的 soft break 会折成空格，
        // 所以正文要先过 withChatLineBreaks 再交给渲染器。
        val out = render(withChatLineBreaks("run the vectors\nsend me the log"))
        assertEquals("run the vectors\nsend me the log", out.text)
    }

    @Test
    fun leavesStructureAlone() {
        // 块级结构自己就表示换行，不许被塞硬换行：围栏里的空格外加一个字节都是改代码。
        val fenced = "```\nval a = 1\nval b = 2\n```"
        assertEquals(fenced, withChatLineBreaks(fenced))
        val list = "- one\n- two"
        assertEquals(list, withChatLineBreaks(list))
        val heading = "# Title\nbody"
        assertEquals(heading, withChatLineBreaks(heading))
        val paragraphs = "para one\n\npara two"
        assertEquals(paragraphs, withChatLineBreaks(paragraphs))
    }

    /**
     * 渲染器那条纯函数路径。
     *
     * 这里用的是它标了 deprecated 的那个重载：新的 `annotatorSettings` 入口是 @Composable，
     * 而本仓没有 compose ui-test（见 DesktopChrome 的说明），测试跑不了 composition。
     * 升级渲染器时这个测试会跟着报弃用 —— 那是提醒，不是噪音。
     */
    @Suppress("DEPRECATION")
    private fun render(text: String): AnnotatedString =
        text.buildMarkdownAnnotatedString(TextStyle.Default)

    @Test
    fun rendersMarkdownOnlyForAgentMessages() {
        assertTrue(shouldRenderMarkdown(mine = false, senderIsAgent = true))
        assertFalse(shouldRenderMarkdown(mine = false, senderIsAgent = false))
        assertFalse(shouldRenderMarkdown(mine = true, senderIsAgent = true))
    }
}