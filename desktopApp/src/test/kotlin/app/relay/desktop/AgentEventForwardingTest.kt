// SPDX-License-Identifier: GPL-3.0-only

package app.relay.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 事件转发判据：**用户自己发的话也要进群**。
 *
 * 守的是真机缺陷（2026-10-18）：转发条件以前写成 `!isUserPrompt && …`，于是宿主用户在
 * DSH 里打的字只落在桌面本地、从不进群 —— 手机端只从群里收消息，那条用户消息在手机上
 * 永远不出现（桌面看得见、手机看不见，用户描述成「思考中没收新消息」）。
 *
 * 回声保护是另一半：手机自己发的那条会先落进本地会话，事件回来时同文本 + 120s 内
 * 判成重复 ⇒ 不能重复转发（否则用户自己的话在群里出现两遍）。
 */
class AgentEventForwardingTest {

    @Test
    fun hostUserPromptIsForwardedToTheGroup() {
        assertTrue(
            shouldForwardAgentEventToGroup(isUserAuthoredPrompt = true, alreadyInChat = false),
            "宿主用户在 DSH 里打的字必须进群：那是手机端看到它的唯一通道",
        )
    }

    @Test
    fun echoOfThePeerOwnPromptIsNotForwardedTwice() {
        assertFalse(
            shouldForwardAgentEventToGroup(isUserAuthoredPrompt = true, alreadyInChat = true),
            "手机自己发的那条已经在本地会话里了 ⇒ 不能再往群里回一遍",
        )
    }

    @Test
    fun agentTextIsStillForwarded() {
        assertTrue(shouldForwardAgentEventToGroup(isUserAuthoredPrompt = false, alreadyInChat = false))
        assertFalse(
            shouldForwardAgentEventToGroup(isUserAuthoredPrompt = false, alreadyInChat = true),
            "agent 的话同样不该重复进群",
        )
    }

    /**
     * 所有转发路径（实时 / 补发）必须**都**把思考过程带上。
     *
     * 只发正文的后果是用户可见的：同一个群里桌面端的气泡有「思考」折叠块、手机端没有，
     * 看起来就是「过程只在桌面，手机只剩最后一句」。每处各写一遍参数，漏一处就少一截，
     * 而单测各自只覆盖自己那条 —— 所以这里做**源码级守卫**：把每个 `sendGroupText(`
     * 的参数表切出来，逐个要求里面有 `reasoning`。
     *
     * ## 为什么门槛从三条降到两条
     *
     * 原先第三条是 **native 驱动的回复**。那个驱动自己 KDoc 写着「没有挂载执行运行时：
     * 这次不会执行命令、不读写文件、不产出结果」—— 它发进群的是那句
     * 「本机 native 驱动没有挂载执行运行时」的兜底话术，不是 agent 的回答。
     *
     * 真机上这条表现为群里冒出一个 `NATIVE` 发送者、内容全是「我没有运行时」。
     * 已删除：一个**永远成功但什么都不做**的执行器比没有执行器更糟 ——
     * 它让用户以为 agent 收到了、只是干不了活。真正的执行路径是 dsh-tidings 的 inbox 注入。
     *
     * 门槛因此是 **2**。但不要因为「反正只剩两条」就把它当摆设：
     * 谁再加一条 `sendGroupText(` 而不带 `reasoning`，这里照样会红。
     */
    @Test
    fun everyGroupForwardPathCarriesTheReasoning() {
        val source = File("src/main/kotlin/app/relay/desktop/DesktopExtension.kt")
            .takeIf { it.isFile }
            ?: File("desktopApp/src/main/kotlin/app/relay/desktop/DesktopExtension.kt")
        assertTrue(source.isFile, "找不到 DesktopExtension.kt（工作目录 " + File("").absolutePath + "）")
        val argLists = groupTextArgumentLists(source.readText())
        assertTrue(argLists.size >= 2, "转发路径应当至少两条（实时 / 补发），实测 " + argLists.size)
        argLists.forEachIndexed { index, args ->
            assertTrue(
                args.contains("reasoning"),
                "第 " + (index + 1) + " 条 sendGroupText 没带 reasoning（手机端会少掉思考块）：" + args,
            )
        }
    }

    /**
     * 转发**不许另算发送者**：本机落库用什么，线上就必须用什么。
     *
     * 原来实时那条无条件写 `agentSenderIdFor(agentKind)` ⇒ 用户自己在群里打的字，
     * 在别人设备上显示成「agent 说的」（真机红米库里就是这么存的）。
     */
    @Test
    fun everyGroupForwardPathUsesTheSameSenderAsTheLocalRecord() {
        val source = File("src/main/kotlin/app/relay/desktop/DesktopExtension.kt")
            .takeIf { it.isFile }
            ?: File("desktopApp/src/main/kotlin/app/relay/desktop/DesktopExtension.kt")
        val argLists = groupTextArgumentLists(source.readText())
        // 门槛 2：第三条（native 驱动的回复）已删 —— 那个驱动没有执行运行时，
        // 它发进群的是「本机没有运行时」的兜底话术。见上一条守卫的 KDoc。
        assertTrue(argLists.size >= 2, "转发路径应当至少两条（实时 / 补发），实测 " + argLists.size)
        argLists.forEachIndexed { index, args ->
            // 只看**活代码**：解释这条纪律的注释里正当地提到了那个函数名，
            // 不剥掉的话守卫会被自己的说明文字绊倒。
            val code = args.lines().joinToString("\n") { it.substringBefore("//") }
            assertTrue(
                !code.contains("agentSenderIdFor("),
                "第 " + (index + 1) + " 条 sendGroupText 又自己算了一遍发送者（用户的话会被记成 agent）：" + args,
            )
        }
    }

    /** 切出每个 `sendGroupText(` 的完整参数表（按括号配平，能跨多行）。 */
    private fun groupTextArgumentLists(src: String): List<String> {
        val needle = "sendGroupText("
        val out = ArrayList<String>()
        var from = 0
        while (true) {
            val at = src.indexOf(needle, from)
            if (at < 0) break
            var depth = 0
            var i = at + needle.length - 1
            while (i < src.length) {
                when (src[i]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) {
                            out.add(src.substring(at, i + 1))
                            break
                        }
                    }
                }
                i++
            }
            from = i + 1
        }
        return out
    }
}