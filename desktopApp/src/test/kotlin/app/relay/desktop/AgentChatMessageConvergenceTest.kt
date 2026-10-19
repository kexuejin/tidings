// SPDX-License-Identifier: GPL-3.0-only

package app.relay.desktop

import app.relay.agent.AgentSessionRegistry
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 实时与补发**共用同一个构造**（task-59 a/b/c）。
 *
 * 背景：此前两条路各写一份 —— 补发不传会话标题、发送者硬编码 agent:dsh，于是同一条事件
 * 在「实时」与「补发」下**文案与发送者都不一样**（用户可见）；群匹配也是两份等价实现
 * （实时三键、补发只看 wsPath ⇒ 靠 wsTitle 绑定的群补发静默投不到）。
 *
 * 分两层：行为层（共用构造的规则）+ 结构层（源码守卫：两个调用点都必须走 chatMessageFor，
 * 且文件里不许再出现 agent:dsh 或 formatAgentEventForChat(rec, null)）。
 */
class AgentChatMessageConvergenceTest {

    private fun rec(type: String, body: String?) = AgentSessionRegistry.EventRecord(
        nodeId = "peer-1",
        sessionId = "s-1",
        cursor = 1L,
        eventType = type,
        summary = null,
        body = body,
        ref = null,
        at = 1_700_000_000_000L,
    )

    @Test
    fun theSharedConstructorReceivesTheSessionTitle() {
        var seenTitle: String? = "not-called"
        val message = agentChatMessageFor(
            rec = rec("assistant.text", "hello"),
            sessionTitle = "工作区 A",
            selfNodeId = "self-node",
            agentKind = "codex",
            format = { _, title ->
                seenTitle = title
                "hello"
            },
        )
        assertEquals("工作区 A", seenTitle, "标题必须一路传到格式化器（补发此前传的是 null）")
        assertEquals("agent:codex", message?.senderId, "agent 正文的发送者是 agent:<agentKind>，不是写死的 agent:dsh")
        assertEquals("hello", message?.text)
    }

    @Test
    fun userPromptUsesTheSelfNodeIdAsSender() {
        val message = agentChatMessageFor(
            rec = rec("user.message", "把门禁跑一遍"),
            sessionTitle = null,
            selfNodeId = "self-node",
            agentKind = "codex",
            format = { r, _ -> r.body },
        )
        assertEquals("self-node", message?.senderId)
        assertTrue(message?.isUserPrompt == true)
    }

    @Test
    fun invisibleTextProducesNoMessage() {
        val message = agentChatMessageFor(
            rec = rec("assistant.text", "\u200b"),
            sessionTitle = null,
            selfNodeId = "self-node",
            agentKind = "codex",
            format = { r, _ -> r.body },
        )
        assertNull(message, "只有零宽字符的事件不该产出气泡（空气泡那条）")
    }

    @Test
    fun bothPathsGoThroughTheSharedConstructor() {
        val source = File("src/main/kotlin/app/relay/desktop/DesktopExtension.kt")
            .takeIf { it.isFile }
            ?: File("desktopApp/src/main/kotlin/app/relay/desktop/DesktopExtension.kt")
        assertTrue(source.isFile, "找不到 DesktopExtension.kt（工作目录 " + File("").absolutePath + "）")
        val text = source.readText()
        // 定义 1 处 + 实时 1 处 + 补发 2 处（本地会话补发 / 群补发）
        val calls = text.split("chatMessageFor(").size - 1
        assertTrue(calls >= 4, "实时与两条补发都必须走共用构造，实测 " + calls + " 处")
        assertTrue(
            !text.contains("formatAgentEventForChat(rec, null)"),
            "补发不许再绕过共用构造直接格式化（会丢掉会话标题）",
        )
        assertTrue(text.contains("internal fun agentChatMessageFor("), "共用构造本身必须存在")
        // 发送者不许再写死：**只查代码行**（注释里提到 agent:dsh 是允许的 —— workspace-ia
        // 核对过 560/787/1041 三处都是注释，守卫不该被它们骗，也不该被它们绊倒）。
        val codeLines = text.lines().map { it.substringBefore("//") }
        assertTrue(
            codeLines.none { it.contains("\"agent:dsh\"") },
            "活代码里不许再写死 agent:dsh —— 发送者必须是 agent:<driverId>",
        )
    }
}

