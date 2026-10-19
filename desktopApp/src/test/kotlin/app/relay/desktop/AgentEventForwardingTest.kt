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
     * 三条转发路径（实时 / 本地驱动 / 补发）必须**都**把思考过程带上。
     *
     * 只发正文的后果是用户可见的：同一个群里桌面端的气泡有「思考」折叠块、手机端没有，
     * 看起来就是「过程只在桌面，手机只剩最后一句」。三处各写一遍参数，漏一处就少一截，
     * 而单测各自只覆盖自己那条 —— 所以这里做**源码级守卫**：把每个 `sendGroupText(`
     * 的参数表切出来，逐个要求里面有 `reasoning`。
     */
    @Test
    fun everyGroupForwardPathCarriesTheReasoning() {
        val source = File("src/main/kotlin/app/relay/desktop/DesktopExtension.kt")
            .takeIf { it.isFile }
            ?: File("desktopApp/src/main/kotlin/app/relay/desktop/DesktopExtension.kt")
        assertTrue(source.isFile, "找不到 DesktopExtension.kt（工作目录 " + File("").absolutePath + "）")
        val argLists = groupTextArgumentLists(source.readText())
        assertTrue(argLists.size >= 3, "转发路径应当至少三条（实时 / 本地驱动 / 补发），实测 " + argLists.size)
        argLists.forEachIndexed { index, args ->
            assertTrue(
                args.contains("reasoning"),
                "第 " + (index + 1) + " 条 sendGroupText 没带 reasoning（手机端会少掉思考块）：" + args,
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