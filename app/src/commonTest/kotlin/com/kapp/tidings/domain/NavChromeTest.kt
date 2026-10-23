// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.navigation.AppRoutes
import kotlin.test.Test
import kotlin.test.assertEquals
import com.kapp.tidings.domain.model.PeerUi

/**
 * 导航外壳的聚合：底栏徽标。
 *
 * 它住在 `App.kt` 之外（见 `NavChrome.kt` 的说明）就是为了能被这里盯住 ——
 * 截图门禁渲染的是同一份数字，"门禁上的徽标"和"应用上的徽标"不能是两个算法。
 *
 * 这里原来还验证过会话标签条的模型（`sessionTabsOf`）。那条标签条已按产品决策
 * 整体移除；
 * 其中「群不能显示成 `group:3a`」那条守卫搬去了 [RelayUiStateTest]，盯的是同一个
 * `convTitle`。
 */
class NavChromeTest {

    private fun peer(
        nodeId: String,
        nick: String,
        online: Boolean = true,
        caps: List<String> = emptyList(),
    ) = PeerUi(nodeId = nodeId, nick = nick, online = online, caps = caps)

    @Test
    fun conversationsBadgeIsTheUnreadTotal() {
        val state = RelayUiState(
            peers = listOf(peer("a", "甲"), peer("b", "乙")),
            unreadByPeer = mapOf("a" to 2, "b" to 3),
        )
        assertEquals(5, navBadges(state)[AppRoutes.CONVERSATIONS])
    }

    @Test
    fun agentBadgeCountsOnlyAgentsNotPeople() {
        // 这是这条规则的全部意义：真人同事的待处理数挂在 Agent 页签上就是虚高。
        val state = RelayUiState(
            peers = listOf(
                peer("human", "小林", caps = listOf("fd1")),
                peer("agent", "codex · 终端", caps = listOf(MsgTypes.CAP_AGENT)),
            ),
            pendingByPeer = mapOf("human" to 9, "agent" to 2),
        )
        assertEquals(2, navBadges(state)[AppRoutes.AGENTS])
    }

    @Test
    fun zeroCountsAreLeftOutRatherThanDrawnAsAnEmptyBadge() {
        val state = RelayUiState(
            peers = listOf(peer("agent", "codex · 终端", caps = listOf(MsgTypes.CAP_AGENT))),
        )
        // 空 map，而不是 {conversations: 0, agents: 0}：0 会让 Badge 画出一个小圆点。
        assertEquals(emptyMap(), navBadges(state))
    }
}
