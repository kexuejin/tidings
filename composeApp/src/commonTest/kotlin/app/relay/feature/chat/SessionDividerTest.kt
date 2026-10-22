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

    // ------------------------------------------- 协议字段优先于 id 前缀（task-16）

    /**
     * **协议字段优先**：消息带 `agentSessionId` 时，分段判据只看字段，不看 id 形状。
     *
     * 真身：手机端收到的群消息 id 是**信封 UUID**（不是 `agent-ev-…`），
     * 所以 `extractSessionId` 恒 null —— 桌面端有会话分段线、手机端一条都没有。
     * 修法是让协议携带会话归属，判据读字段。
     */
    @Test
    fun theProtocolFieldWinsOverTheIdPrefix() {
        val messages = listOf(
            MessageUi(id = "3f2a-uuid", peerId = "group:g", mine = false, text = "a", at = 1, agentSessionId = "s-1"),
            MessageUi(id = "9b31-uuid", peerId = "group:g", mine = false, text = "b", at = 2, agentSessionId = "s-1"),
            MessageUi(id = "c7e0-uuid", peerId = "group:g", mine = false, text = "c", at = 3, agentSessionId = "s-2"),
        )
        assertEquals(
            setOf("3f2a-uuid", "c7e0-uuid"),
            sessionTransitionIds(messages),
            "同一会话只出一条分隔；换会话再出一条 —— 全程不需要 id 前缀",
        )
    }

    /**
     * **旧数据回退**：字段为 null 时仍认 id 前缀。
     *
     * 升级前收到的 agent 消息 id 里仍带 `agent-ev-`；没有这条回退，
     * 老会话的分段线会在升级后凭空消失（用户会觉得历史被改坏了）。
     */
    @Test
    fun theIdPrefixStillWorksAsAFallbackForOldRows() {
        val messages = listOf(msg("agent-ev-s1-1", 1), msg("agent-ev-s2-1", 2))
        assertEquals(setOf("agent-ev-s1-1", "agent-ev-s2-1"), sessionTransitionIds(messages))
    }
}
