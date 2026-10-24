// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import com.kapp.tidings.agent.AgentSessionRegistry
import com.kapp.tidings.desktop.testutil.RepoSource
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
        // agent 的身份是 **(设备, agent)**：`agent:<hostNodeId>:<agentId>`。
        // 只写 `agent:<kind>` 时，两台设备上同名的 agent 在 senderId 里完全一样，
        // 会被合并成同一个人（AgentSenderId 的 KDoc 记了这条来历）。
        assertEquals(
            "agent:self-node:codex",
            message?.senderId,
            "agent 正文的发送者必须是完整身份（设备 + agent），不是裸 kind",
        )
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

    /**
     * agent 的发送者标签**只有一个来源**（`agentSenderIdFor`）。
     *
     * 这里原来写的是 `"agent:" + agentKind`，而线上那条路用的是 `agentSenderIdFor`（会转小写）——
     * 同一个说话者在桌面端落库成 `agent:DSH`、在手机端落库成 `agent:dsh`，
     * 一个 agent 在群里显示成两个人（真机库里两种拼写各 801 / 113 条）。
     */
    @Test
    fun theAgentLabelHasASingleSourceOfTruth() {
        val source = RepoSource.read("desktopApp/src/main/kotlin/com/kapp/tidings/desktop/DesktopExtension.kt")
        // 只看 `agentSenderIdFor` **定义之前**的代码：那个函数自己的实现里当然有 `"agent:" +`，
        // 它是唯一合法的现拼处；别的任何地方现拼都是同一个 bug 的复发。
        val beforeDefinition = source.substringBefore("internal fun agentSenderIdFor")
        val codeLines = beforeDefinition.lines().map { it.substringBefore("//") }
        // 唯一允许的第二处现拼是**本地驱动**那条：它答的就是它自己（`agent:<driverId>`，
        // 与事件转发那条不是同一个说话者），这是有意的、也写明了理由。
        val offenders = codeLines.filter { it.contains("\"agent:\" +") && !it.contains("driver.driverId") }
        assertTrue(
            offenders.isEmpty(),
            "agent 标签只许由 agentSenderIdFor 生成（本地驱动那处除外）；现拼会得到两种拼写，" +
                "同一个 agent 显示成两个人：" + offenders,
        )
    }

    @Test
    fun bothPathsGoThroughTheSharedConstructor() {
        val text = RepoSource.read("desktopApp/src/main/kotlin/com/kapp/tidings/desktop/DesktopExtension.kt")
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

