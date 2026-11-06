// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.ui.desktop

import com.kapp.tenon.domain.AgentSlice
import com.kapp.tenon.domain.toChatSlice
import com.kapp.tenon.domain.toAgentSlice
import com.kapp.tenon.domain.ChatSlice
import com.kapp.tenon.domain.RelaySettings
import com.kapp.tenon.domain.RelayUiState
import com.kapp.tenon.domain.model.AgentApprovalUi
import com.kapp.tenon.domain.model.FileCardUi
import com.kapp.tenon.domain.model.MessageUi
import com.kapp.tenon.domain.model.TransferState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Phase 4 桌面壳徽标的**等价性**测试：切片版本必须与聚合版本逐字同义。
 *
 * 桌面壳的徽标规则与移动端不同（文件页签看的是「等你处置的入向文件邀约」），
 * 所以它单独有一份投影；这里把新旧两份钉在一起，防止两边漂移。
 */
@Suppress("DEPRECATION")
class DesktopNavBadgesProjectionTest {

    private fun messageWithOffer(id: String, mine: Boolean, state: TransferState) = MessageUi(
        id = id,
        peerId = "conv-1",
        mine = mine,
        text = "",
        at = 1L,
        file = FileCardUi(
            transferId = "t-" + id,
            name = "a.pdf",
            size = 10L,
            mine = mine,
            state = state,
        ),
    )

    @Test
    fun sliceBadgesMatchTheAggregateVersion() {
        val approvals = mapOf(
            "a-1" to AgentApprovalUi(approvalId = "a-1", sessionId = "s-1", peerId = "p-1", status = "pending"),
            "a-2" to AgentApprovalUi(approvalId = "a-2", sessionId = "s-1", peerId = "p-1", status = "allow"),
        )
        val state = RelayUiState(
            settings = RelaySettings.firstRun(),
            unreadByPeer = mapOf("conv-1" to 2, "group:g-1" to 1),
            messages = mapOf(
                "conv-1" to listOf(
                    messageWithOffer("m-1", mine = false, state = TransferState.Offered),
                    messageWithOffer("m-2", mine = true, state = TransferState.Offered),
                    messageWithOffer("m-3", mine = false, state = TransferState.Completed),
                ),
            ),
            agentApprovals = approvals,
        )
        val chatSlice = ChatSlice(
            messages = state.messages,
            unreadByPeer = state.unreadByPeer,
        )
        val agentSlice = AgentSlice(agentApprovals = state.agentApprovals)

        assertEquals(
            desktopNavBadges(state.toChatSlice(), state.toAgentSlice()),
            desktopNavBadges(chatSlice, agentSlice),
            "切片版桌面徽标必须与聚合版逐字同义（未读 / 待处置邀约 / 待审批）",
        )
    }

    @Test
    fun emptyStateHasNoBadges() {
        val state = RelayUiState(settings = RelaySettings.firstRun())
        assertEquals(
            desktopNavBadges(state.toChatSlice(), state.toAgentSlice()),
            desktopNavBadges(ChatSlice(), AgentSlice()),
            "空状态两边都必须是空 map（不画空徽标）",
        )
    }
}
