// SPDX-License-Identifier: GPL-3.0-only

package app.relay.feature.chat

import app.relay.app.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 群聊输入区**不再显示**「有成员不在线，他们上线后会收到」。
 *
 * ## 为什么需要一条源码级守卫
 *
 * 这条提示被用户否定过**两次**：第一次我把它降级成「agent 状态线出现时让位」——
 * 那只是让它少出现，没有解决「它本身没有信息量」这件事；第二次才整条删掉。
 * 一条被删掉又被改回来的文案，光靠 review 是拦不住的：它长得像一句贴心的提示。
 *
 * ## 为什么它确实没用（不是审美问题）
 *
 * - 群消息**本来就**为离线成员入队并补发，这是协议保证的**正常行为**，不是异常；
 * - 把它当提示说，等于每次有人不在线都告诉用户「你的消息会送达」——
 *   用户不该为一件必然发生的事被通知；
 * - 群头部已经有「1/4 人在线」这个更精确、随时可看的读数，重复表达同一件事；
 * - 它占着输入区上方那一行，而那正是「Agent 正在思考 / 等审批」要用的位置。
 *
 * ## 但单聊那条要留着（差一点就一起删了）
 *
 * 单聊的「对方离线，消息会排队」说的是**另一种状态**：这条现在发不出去、
 * 要等对方上线（群是补发、单聊是排队）。那是用户需要知道的状态差异。
 * 所以本测试同时钉住两侧：群文案必须消失，单聊文案必须还在。
 */
class GroupOfflineHintRemovalTest {

    private fun read(path: String): String = GuardSourceScan.read(path)

    private val chatScreen =
        "composeApp/src/commonMain/kotlin/app/relay/feature/chat/ChatScreen.kt"

    /**
     * 群文案不许再出现在生产代码里 —— **资源键与中文字面量两种形态都要拦**。
     *
     * 为什么是两种：反向验证时我把 `stringResource(Res.string.chat_group_some_offline)`
     * 加回去，编译期就红了（资源已删）；但换成裸中文字面量 `"有成员不在线"` 时，
     * 守卫**照样通过** —— 说明只守资源键会漏掉「有人直接写死文案」这条更常见的退化路径。
     * 两种都拦，守卫才真的在测东西。
     */
    @Test
    fun theGroupOfflineHintIsGone() {
        // **必须先剥注释**：这个文件里正当提到这条文案的注释有好几处
        // （记录「为什么删掉它」）。直接 contains 会被自己的注释绊倒 ——
        // AGENTS §4 记的「源码级守卫被自己的注释绊倒」正是这个形态。
        val live = GuardSourceScan.stripComments(read(chatScreen))
        assertFalse(
            live.contains("chat_group_some_offline"),
            "群聊的「有成员不在线」提示（资源键形式）被加回来了",
        )
        assertFalse(
            live.contains("有成员不在线"),
            "群聊的「有成员不在线」提示（硬编码文案形式）被加回来了 —— 这条用户否定过两次",
        )
    }

    /** 资源文件里也不许留（留着就迟早有人再引用它）。 */
    @Test
    fun theStringResourceIsDeleted() {
        for (locale in listOf("values", "values-en")) {
            val xml = read("composeApp/src/commonMain/composeResources/$locale/strings.xml")
            assertFalse(
                xml.contains("chat_group_some_offline"),
                "$locale 里还留着 chat_group_some_offline",
            )
        }
    }

    /**
     * **单聊那条必须还在**：它是另一种状态（排队等对方上线），不是同一条噪音。
     *
     * 这条断言存在的意义：防止下一个人「顺手把离线提示都删了」。
     */
    @Test
    fun theDirectChatOfflineHintSurvives() {
        val live = GuardSourceScan.stripComments(read(chatScreen))
        assertTrue(
            live.contains("chat_peer_offline"),
            "单聊的「对方离线」提示被误删了 —— 那一条说的是排队，不是群补发",
        )
    }
}
