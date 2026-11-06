// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.desktop

// 编排层（:orchestration）：DesktopExtension 下沉后的公开面。
import com.kapp.tenon.orchestration.agentChatMessageFor
import com.kapp.tenon.orchestration.sessionTitleFor

import com.kapp.tenon.agent.AgentSessionRegistry
import com.kapp.tenon.desktop.testutil.RepoSource
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

    /**
     * 路径常量：判据住在哪儿就读哪儿（轮 8 / ARP-5 把 `DesktopExtension.kt` 拆成
     * 装配 + 转发 + 文案三半）。守卫读的是**内容**，不是某个文件的行号，所以指着真文件即可；
     * 需要「整个编排层都没有」这种判据的地方用 [RepoSource.readDir] 读整包。
     */
    private val orchestrationPkg = "orchestration/src/main/kotlin/com/kapp/tenon/orchestration"
    private val desktopExtension = orchestrationPkg + "/DesktopExtension.kt"
    private val chatCopy = orchestrationPkg + "/ChatCopyFormatter.kt"
    private val forwarder = orchestrationPkg + "/AgentEventForwarder.kt"

    /**
     * 台账的事件回调必须**真的接到**转发器上。
     *
     * 这一跳没有类型层面的保障：`onRecordEvent` 只要求一个 `(EventRecord) -> Unit`，
     * 传个空 lambda 照样编译通过 —— 症状是「群里一条 agent 消息都没有」，而日志里
     * 一条错都不缺（静默失败那一族）。轮 8 把 200 行的分发实现搬进 [AgentEventForwarder]
     * 之后，这一跳只剩一行，所以要钉住「那一行确实在转」。
     */
    @Test
    fun theLedgerCallbackIsWiredToTheForwarder() {
        val text = RepoSource.read(desktopExtension)
        val wiring = Regex("""onRecordEvent\s*=\s*\{\s*\w+\s*->\s*eventForwarder\.handleRecordEvent\(\w+\)\s*}""")
        assertTrue(
            wiring.containsMatchIn(text),
            "会话台账的 onRecordEvent 必须转给 eventForwarder.handleRecordEvent —— " +
                "传空 lambda 编译得过，但群里一条消息都不会有",
        )
    }

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
     * 会话标题（divider）的消毒：prompt 不是标题。
     *
     * 真机两次翻车：divider 出现过整句系统前导「You are working strictly inside…」，
     * 也出现过带着 @ 提派 token 的「@codex-e2e create …」—— 那分别是编排器的注入
     * 与路由地址，都不是用户该读的任务名。
     */
    @Test
    fun sessionTitleIsSanitizedFromPrompt() {
        // @ token 剥掉，任务正文保留。
        assertEquals(
            "create hello.txt take2",
            sessionTitleFor("@codex-e2e create hello.txt take2"),
            "行首的 @ 提派 token 是路由地址，不许出现在 divider 上",
        )
        // 多个 @ token（改派场景）也全剥。
        assertEquals(
            "create hello.txt",
            sessionTitleFor("@a @b create hello.txt"),
        )
        // 空行打头：取首个非空行。
        assertEquals("real task", sessionTitleFor("\n  \nreal task"))
        // 全空：交回 ControlPlane 的缺省。
        assertNull(sessionTitleFor("  \n "))
        assertNull(sessionTitleFor(""))
        // 超长截断到 80（与台账标题预算一致）。
        val long = "x".repeat(200)
        assertEquals(80, sessionTitleFor(long)?.length)
    }

    @Test
    fun chatBubblesAreWhitelistedToDialogueEventsOnly() {
        // 白名单实现住在 ChatCopyFormatter.kt，且是该文件的**最后一个**声明 ⇒ 取到文件尾即函数体。
        val text = RepoSource.read(chatCopy)
        val fn = text.substringAfter("fun formatAgentEventForChat")
        // 切片自检：取不到函数体时 substringAfter 会把整份源码还回来，后面的断言就全在
        // 一个错误的文本上通过了 —— 先钉住「真的切到了函数体」。
        assertTrue(fn.contains("else -> null"), "切片没取到函数体（找不到 fun formatAgentEventForChat）")
        // 全新显示方案（「卡片即终态，气泡只承载对话」）：格式化器是**白名单** ——
        // 只有对话类型（assistant 文本 / turn 收束语 / user.message）允许进气泡，
        // 缺省分支必须沉默。
        // 终态与错误由任务卡表达（review_ready/blocked/aborted 都有终态卡）——
        // 「✅ …」庆祝气泡与「⚠️ Agent 异常: …」刷屏不允许回来。
        assertTrue(
            !fn.contains("\"finished\"") && !fn.contains("\"error\""),
            "finished/error 事件不再产生文本气泡：终态与错误的人话面是任务卡横幅，不是聊天气泡",
        )
        // 工具调用、产物与卡片是结构化协议，从白名单上就进不了气泡。
        assertTrue(
            !fn.contains("\"artifact\"") && !fn.contains("\"card\""),
            "artifact 与 card 事件不进气泡（裸 JSON 与底层对账路径不许污染聊天流）",
        )
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
        // 发送者标签的唯一来源住在 ChatCopyFormatter.kt（与文案构造在同一文件，正是这条纪律的落点）。
        val source = RepoSource.read(chatCopy)
        // 只看 `agentSenderIdFor` **定义之前**的代码：那个函数自己的实现里当然有 `"agent:" +`，
        // 它是唯一合法的现拼处；别的任何地方现拼都是同一个 bug 的复发。
        val beforeDefinition = source.substringBefore("fun agentSenderIdFor")
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
        // 三个调用点（实时 1 + 补发 2）与 `chatMessageFor` 的定义都在 AgentEventForwarder.kt；
        // 共用构造本身在 ChatCopyFormatter.kt。判据按文件取，别按「哪一版是哪个文件」取。
        val text = RepoSource.read(forwarder)
        // 定义 1 处 + 实时 1 处 + 补发 2 处（本地会话补发 / 群补发）
        val calls = text.split("chatMessageFor(").size - 1
        assertTrue(calls >= 4, "实时与两条补发都必须走共用构造，实测 " + calls + " 处")
        assertTrue(
            !text.contains("formatAgentEventForChat(rec, null)"),
            "补发不许再绕过共用构造直接格式化（会丢掉会话标题）",
        )
        assertTrue(
            RepoSource.read(chatCopy).contains("fun agentChatMessageFor("),
            "共用构造本身必须存在（ChatCopyFormatter.kt）",
        )
        // 发送者不许再写死：**只查代码行**（注释里提到 agent:dsh 是允许的）。
        // 这里读**整个编排包**：写死的搜索不该只在某一个文件里查 —— 判据是「编排层没有」。
        val codeLines = RepoSource.readDir(orchestrationPkg).lines().map { it.substringBefore("//") }
        assertTrue(
            codeLines.none { it.contains("\"agent:dsh\"") },
            "活代码里不许再写死 agent:dsh —— 发送者必须是 agent:<driverId>",
        )
    }
}

