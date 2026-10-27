// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.chat

import com.kapp.tidings.domain.model.MessageUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **分割线的「判定」与「取 sid」必须用同一个判据。**
 *
 * ## 真机症状
 *
 * 「桌面端有分段线、移动端没有」。数据完全正确（库里 `agent_session_id` 有值、两个会话
 * 在同一群里交替），但手机上那条线就是不出现。
 *
 * ## 原因：两套判据
 *
 * - 判定（`sessionTransitionIds`）用 `sessionIdOf` —— **优先协议字段 `agentSessionId`**，
 *   取不到才退回 id 前缀；
 * - 渲染时却用 `extractSessionId(message.id)` —— **只认 id 前缀**。
 *
 * 两个端拿到的 id 形态不同：
 *
 * | 端 | 消息 id | `extractSessionId` |
 * |---|---|---|
 * | 桌面 | `agent-ev-session-xxx-12` | 能取到 ⇒ 画线 |
 * | 手机 | 信封 UUID（转发时换掉了） | null ⇒ **跳过不画** |
 *
 * 所以手机端是「判定说该画、渲染说取不到就跳过」。
 */
class SessionDividerUsesTheSameSourceTest {

    private fun msg(id: String, sid: String?, at: Long = 0L) = MessageUi(
        id = id,
        peerId = "group:g-1",
        mine = false,
        text = "正文",
        at = at,
        agentSessionId = sid,
    )

    /**
     * **手机形态：id 是 UUID、归属只在协议字段里 ⇒ 仍然是切换点，且 sid 取得到。**
     *
     * 这条同时断言两件事，因为它们必须一致：判定集里有它（`sessionTransitionIds`），
     * 且 `sessionIdOf` 对它返回非 null（渲染靠这个画）。
     */
    @Test
    fun aUuidIdWithAProtocolSessionStillProducesADivider() {
        val a = msg("06a68f84-cf99-4705", "session-aaaa", at = 3L)
        val b = msg("60e63fb6-c03d-414d", "session-bbbb", at = 2L)
        val c = msg("0281cf91-7b16-41a2", "session-aaaa", at = 1L)
        // 界面按时间倒序渲染，判定也按同一个顺序走。
        val list = listOf(a, b, c)
        val transitions = sessionTransitionIds(list)
        assertTrue(
            a.id in transitions && b.id in transitions,
            "两个会话交替 ⇒ 两次切换都该被判定出来；实得 " + transitions,
        )
        // 每一步的 sid 都取得到（这是渲染能画线的前提）。
        for (m in list) {
            assertNotNull(sessionIdOf(m), "id=" + m.id + " 取不到 sid")
        }
        // **渲染那一行必须也用 `sessionIdOf` —— 见下面那条源码级守卫。**
        // 这里只能测纯函数：渲染长在 Composable 里，单元测试进不去。只测函数的话，
        // 渲染改回 `extractSessionId(message.id)` 这条测试依然是绿的（假绿）。
    }

    /** 老形态（桌面：id 前缀里有会话）仍然要能取到。 */
    @Test
    fun theLegacyIdPrefixStillWorks() {
        val m = msg("agent-ev-session-cccc-7", null)
        assertNotNull(sessionIdOf(m), "id 前缀那条回退不能丢")
    }

    /** 同一会话连续多条 ⇒ 只有第一条是切换点，不重复画线。 */
    @Test
    fun onlyTheFirstMessageOfASessionIsATransition() {
        val a = msg("id-1", "session-a", at = 2L)
        val b = msg("id-2", "session-a", at = 1L)
        val transitions = sessionTransitionIds(listOf(a, b))
        assertTrue(a.id in transitions, "会话第一次出现要画线")
        assertTrue(b.id !in transitions, "同一个会话的第二条不该再画线")
        assertEquals(1, transitions.size)
    }

    /** 没有归属的消息不产生切换点（否则每条普通消息都会插一条线）。 */
    @Test
    fun messagesWithoutAttributionAreNotTransitions() {
        val a = msg("plain-uuid-1", null)
        val b = msg("plain-uuid-2", null)
        assertEquals(emptySet(), sessionTransitionIds(listOf(a, b)))
    }

    /**
     * **源码级守卫：渲染分割线时取 sid 必须用 `sessionIdOf`，不能用 `extractSessionId`。**
     *
     * 为什么需要它：这条判据住在 Composable 里，单元测试进不去。只测纯函数（`sessionIdOf` /
     * `sessionTransitionIds`）的话，渲染改回 `extractSessionId(message.id)` 依然是绿的 ——
     * 那是假绿，而真机症状是「手机端永远画不出那条线」。
     *
     * 为什么 `extractSessionId(message.id)` 在这里是错的：手机的消息 id 是**信封 UUID**
     * （转发时被换掉了），前缀里没有会话 —— 它返回 null，那一整块 `if` 就被跳过。
     * 桌面能画，只因它的 id 恰好是 `agent-ev-session-xxx-12`。
     */
    @Test
    fun theRenderPathResolvesTheSessionTheSameWayTheDetectorDoes() {
        val candidates = listOf(
            // 测试进程 cwd 是模块目录
            java.io.File("src/commonMain/kotlin/com/kapp/tidings/feature/chat/ChatScreen.kt"),
            java.io.File("composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/chat/ChatScreen.kt"),
        )
        val file = candidates.firstOrNull { it.exists() }
        assertNotNull(file, "找不到 ChatScreen.kt（cwd=" + java.io.File(".").absolutePath + "）")
        val src = file.readText()
        // 渲染分支的特征：`if (message.id in sessionTransitions) {`
        val at = src.indexOf("if (message.id in sessionTransitions) {")
        assertTrue(at >= 0, "没找到渲染分割线的分支 —— 这段代码被重构了，守卫要跟着改")
        val block = src.substring(at, minOf(at + 800, src.length))
        assertTrue(
            block.contains("val sid = sessionIdOf(message)"),
            "渲染取 sid 必须用 sessionIdOf（与判定同源）。用 extractSessionId(message.id) 时" +
                "手机端（id 是信封 UUID）取不到会话 ⇒ 分割线整块被跳过 ⇒ 只有桌面有那条线",
        )
        assertFalse(
            block.contains("val sid = extractSessionId(message.id)"),
            "不能退回只认 id 前缀那条路 —— 手机端没有前缀",
        )
    }
}
