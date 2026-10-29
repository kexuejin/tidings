// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.chat

import com.kapp.tidings.domain.model.MessageUi
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
 *
 * 债#1 之后判据只有**一个来源**：协议字段 `agentSessionId`。id 前缀（`agent-ev-`）
 * 是桌面端本地构造消息的副产物，不是协议事实 —— 解析它的 `extractSessionId` 已删，
 * 谁把它加回来，`anIdPrefixAloneIsNoLongerASessionSource` 会红。
 */
class SessionDividerTest {

    private fun msg(id: String, at: Long, sid: String? = null) =
        MessageUi(id = id, peerId = "agent-1", mine = false, text = "x", at = at, agentSessionId = sid)

    /** id 前缀**不再是会话来源**：归属只认协议字段，字段缺失就是没有会话。 */
    @Test
    fun anIdPrefixAloneIsNoLongerASessionSource() {
        val messages = listOf(msg("agent-ev-s1-1", 1), msg("agent-ev-s2-1", 2))
        assertEquals(
            emptySet(),
            sessionTransitionIds(messages),
            "只有 id 前缀、没有 agentSessionId ⇒ 不是会话切换（前缀解析已随债#1 删除）",
        )
        assertNull(sessionIdOf(msg("agent-ev-abc-123-7-1700000000000", 0)), "id 前缀不许再被解析")
    }

    @Test
    fun everySessionRunGetsExactlyOneTransition() {
        val messages = listOf(
            msg("m-1", 1, "s1"),
            msg("m-2", 2, "s1"),
            msg("m-3", 3, "s2"),
            msg("m-4", 4, "s2"),
            msg("m-5", 5, "s3"),
        )
        assertEquals(
            setOf("m-1", "m-3", "m-5"),
            sessionTransitionIds(messages),
        )
    }

    @Test
    fun aNewSessionWithNoTitleStillProducesADivider() {
        // ④ 的核心回归：标题通常**后到**（在 agent-session 帧里），甚至一直没到。
        // 分隔点与分隔线都不能依赖标题。
        val messages = listOf(msg("m-1", 1, "old"), msg("m-2", 2, "new"))
        assertTrue("m-2" in sessionTransitionIds(messages), "新会话没有产生分隔点")
        assertNull(sessionDividerTitle(null), "标题没到 = 用中性文案，而不是退回身份键")
        assertNull(sessionDividerTitle("   "), "空白串与没有标题是同一档")
        assertEquals("refactor runner", sessionDividerTitle("refactor runner"))
    }

    // ------------------------------------------- 协议字段是唯一来源（task-16 / 债#1）

    /**
     * **协议字段是唯一来源**：分段判据只看 `agentSessionId`。
     *
     * 真身：手机端收到的群消息 id 是**信封 UUID**（不是 `agent-ev-…`），
     * 所以 `extractSessionId` 恒 null —— 桌面端有会话分段线、手机端一条都没有。
     * 修法是让协议携带会话归属，判据读字段；id 前缀回退已随债#1 删除
     * （旧落库行的分段线不迁移 —— 兼容约束已按裁决解除）。
     */
    @Test
    fun theProtocolFieldIsTheOnlySource() {
        val messages = listOf(
            MessageUi(id = "3f2a-uuid", peerId = "group:g", mine = false, text = "a", at = 1, agentSessionId = "s-1"),
            MessageUi(id = "9b31-uuid", peerId = "group:g", mine = false, text = "b", at = 2, agentSessionId = "s-1"),
            MessageUi(id = "c7e0-uuid", peerId = "group:g", mine = false, text = "c", at = 3, agentSessionId = "s-2"),
        )
        assertEquals(
            setOf("3f2a-uuid", "c7e0-uuid"),
            sessionTransitionIds(messages),
            "同一会话只出一条分隔；换会话再出一条",
        )
    }
}
