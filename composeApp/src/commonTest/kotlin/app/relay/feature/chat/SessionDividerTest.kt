// SPDX-License-Identifier: GPL-3.0-only

package app.relay.feature.chat

import app.relay.app.model.MessageUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 会话分割线（task-42 ④）的两条判据。
 *
 * 用户原话：「在 dsh 新建会话，relay 这边看到新消息了，但没有显示分割线」。根因是
 * `SessionDivider` 早先「标题为空就 return」—— 而新建会话的标题通常还没到（甚至一直没到），
 * 于是整条分隔线被吞掉。这里把两个纯判据钉住：**新会话一定产生分隔点**，
 * 且**没有标题时用中性文案而不是退回身份键**。
 */
class SessionDividerTest {

    private fun msg(id: String, at: Long) =
        MessageUi(id = id, peerId = "agent-1", mine = false, text = "x", at = at)

    @Test
    fun extractSessionIdReadsBothIdShapes() {
        // 桌面 DSH 桥发出来的两种 id 形状：带 at 与不带 at。
        assertEquals("abc-123", extractSessionId("agent-ev-abc-123-7"))
        assertEquals("abc-123", extractSessionId("agent-ev-abc-123-7-1700000000000"))
        assertNull(extractSessionId("m-1"), "普通消息不属于任何 agent 会话")
    }

    @Test
    fun everySessionRunGetsExactlyOneTransition() {
        val messages = listOf(
            msg("agent-ev-s1-1", 1),
            msg("agent-ev-s1-2", 2),
            msg("agent-ev-s2-1", 3),
            msg("agent-ev-s2-2", 4),
            msg("agent-ev-s3-1", 5),
        )
        assertEquals(
            setOf("agent-ev-s1-1", "agent-ev-s2-1", "agent-ev-s3-1"),
            sessionTransitionIds(messages),
        )
    }

    @Test
    fun aNewSessionWithNoTitleStillProducesADivider() {
        // ④ 的核心回归：标题通常**后到**（在 agent-session 帧里），甚至一直没到。
        // 分隔点与分隔线都不能依赖标题。
        val messages = listOf(msg("agent-ev-old-1", 1), msg("agent-ev-new-1", 2))
        assertTrue("agent-ev-new-1" in sessionTransitionIds(messages), "新会话没有产生分隔点")
        assertNull(sessionDividerTitle(null), "标题没到 = 用中性文案，而不是退回身份键")
        assertNull(sessionDividerTitle("   "), "空白串与没有标题是同一档")
        assertEquals("refactor runner", sessionDividerTitle("refactor runner"))
    }
}
