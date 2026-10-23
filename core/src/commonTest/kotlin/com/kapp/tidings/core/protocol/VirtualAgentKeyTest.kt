// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 「这个工作区的 agent 身份键是什么」——入驻进群时要写进 `VirtualAgentMember.agentId`。
 *
 * 真机现场：工作区表的 `agent_id` **是空的**（只有 `agent_kind = DSH`）。
 * 原先那条规则在 agentId 为空时直接放弃入驻，界面上点得动、台账不落东西、没有提示。
 */
class VirtualAgentKeyTest {

    /** 显式传入优先（调用方最清楚）。 */
    @Test
    fun anExplicitAgentIdWins() {
        assertEquals(
            "dsh-029bbbf3",
            resolveVirtualAgentKey("dsh-029bbbf3", "ws-agent", "DSH"),
        )
    }

    /**
     * **工作区 agentId 为空时要退回 agentKind** —— 这正是真机数据的形态。
     */
    @Test
    fun aBlankWorkspaceAgentIdFallsBackToTheKind() {
        assertEquals("DSH", resolveVirtualAgentKey(null, "", "DSH"))
        assertEquals("DSH", resolveVirtualAgentKey(null, null, "DSH"))
        assertEquals("DSH", resolveVirtualAgentKey(null, "   ", "DSH"))
    }

    /** 工作区自报的 agentId 优先于 kind（更精确的那一条）。 */
    @Test
    fun theWorkspaceAgentIdBeatsTheKind() {
        assertEquals("dsh-abc", resolveVirtualAgentKey(null, "dsh-abc", "DSH"))
    }

    /** 三条都空 ⇒ null（这个工作区没有可用身份，不该入驻）。 */
    @Test
    fun nothingUsableYieldsNull() {
        assertNull(resolveVirtualAgentKey(null, null, null))
        assertNull(resolveVirtualAgentKey("  ", "", "   "))
    }

    /** 前后空白要去掉（否则会写出一个带空格的 token）。 */
    @Test
    fun surroundingWhitespaceIsTrimmed() {
        assertEquals("DSH", resolveVirtualAgentKey(null, null, "  DSH  "))
    }
}
