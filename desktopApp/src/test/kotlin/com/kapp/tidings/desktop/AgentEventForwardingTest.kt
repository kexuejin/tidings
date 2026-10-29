// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import com.kapp.tidings.desktop.testutil.RepoSource
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
     * 转发路径**不许再携带 reasoning**（规格 §4.1 铁律：CoT 禁止进群）。
     *
     * 历史上的守卫方向相反：曾要求每条 `sendGroupText(` 都带 `reasoning` —— 那是
     * 「过程只在桌面、手机只剩最后一句」的修法。但过程收敛的终局是**思考块根本不进群**
     * （协议白名单已把 `reasoning` 退场，带它的帧对端整帧拒收），于是这条守卫反转为
     * **负面清单**：谁把 `reasoning` 加回任何一条转发路径，这里照样会红。
     *
     * 源码级守卫在这类「多处必须一致」的纪律上是正当手段（类型系统表达不了
     * 「每条转发路径都不许带」），但它必须能区分对错 —— 带与不带就是可区分的。
     */
    @Test
    fun noGroupForwardPathCarriesReasoning() {
        val argLists = groupTextArgumentLists(
            RepoSource.read("desktopApp/src/main/kotlin/com/kapp/tidings/desktop/DesktopExtension.kt"),
        )
        assertTrue(argLists.size >= 2, "转发路径应当至少两条（实时 / 补发），实测 " + argLists.size)
        argLists.forEachIndexed { index, args ->
            // 只看**活代码**：解释这条纪律的注释里正当地提到那个键名，
            // 不剥掉的话守卫会被自己的说明文字绊倒。
            val code = args.lines().joinToString("\n") { it.substringBefore("//") }
            assertFalse(
                code.contains("reasoning"),
                "第 " + (index + 1) + " 条 sendGroupText 带了 reasoning（CoT 不进群，白名单也会整帧拒收）：" + args,
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
        val argLists = groupTextArgumentLists(RepoSource.read("desktopApp/src/main/kotlin/com/kapp/tidings/desktop/DesktopExtension.kt"))
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