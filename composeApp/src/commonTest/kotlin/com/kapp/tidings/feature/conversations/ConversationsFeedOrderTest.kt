// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.conversations

import com.kapp.tidings.domain.WorkspaceUnitGroupUi
import com.kapp.tidings.domain.WorkspaceUnitUi
import com.kapp.tidings.domain.model.ConversationUi
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * feed 混排的排序纪律（真机反馈 #1）。
 *
 * 会话列表曾经在**两处**排序：`RelayUiState.conversations` 按"未读优先 + 混着对端
 * lastSeenAt 的时间戳"，这里再按"待发消息优先 + conv.at"排一次。两处都用同一个
 * 混了两种语义的时间戳，所以"没聊过的在线对端"在哪一处都会冒到最前。
 *
 * 修完之后会话顺序的**唯一真源**是 `RelayUiState.conversations`；这个文件守的是
 * "混排这一步不会把它改回去"：会话项的时间键是 `lastMessageAt`（没聊过是 0），
 * 配合稳定排序，会话之间只保留派生顺序。工作区段仍按它自己的两把尺子排。
 */
class ConversationsFeedOrderTest {

    private fun conv(
        id: String,
        lastMessageAt: Long = 0L,
        presenceAt: Long = 0L,
        online: Boolean = true,
        isPinned: Boolean = false,
    ) = ConversationUi(
        convId = id,
        title = id,
        preview = if (lastMessageAt > 0) "消息" else null,
        // at 的兼容口径：有消息用消息时间，没有用在线时间（与 RelayUiState 一致）。
        at = if (lastMessageAt > 0) lastMessageAt else presenceAt,
        unread = 0,
        isAgent = false,
        online = online,
        pendingCount = 0,
        lastMessageAt = lastMessageAt,
        presenceAt = presenceAt,
        isPinned = isPinned,
    )

    private fun workspace(
        wsId: String,
        lastActivityAt: Long,
        pendingCount: Int,
    ) = InboxFeedItem.Workspace(
        unit = WorkspaceUnitUi(
            peerId = "peer",
            agentKind = "dsh",
            wsId = wsId,
            lastActivityAt = lastActivityAt,
            pendingCount = pendingCount,
        ),
        group = WorkspaceUnitGroupUi(
            peerId = "peer",
            nodeName = "书房的电脑",
            platform = "",
            online = true,
            lastSeenAt = lastActivityAt,
            agentCount = 1,
            workspaceCount = 1,
            units = emptyList(),
        ),
    )

    @Test
    fun aConversationThatNeverTalkedSinksBelowOneWithMessages() {
        // 这是反馈 #1 的核心：对端"刚刚在线"（presenceAt 很新）不能压过一条真实消息。
        // 输入特意把没聊过的放在前面，模拟"派生顺序万一被别的路径打乱"。
        val fresh = InboxFeedItem.Conversation(
            conv("fresh", lastMessageAt = 0L, presenceAt = 99_000L, online = true),
        )
        val talked = InboxFeedItem.Conversation(conv("talked", lastMessageAt = 1_000L))

        val out = orderInboxFeed(listOf(fresh, talked))

        assertEquals(listOf("conv_talked", "conv_fresh"), out.map { it.key })
    }

    @Test
    fun conversationsWithMessagesKeepFallingByLastMessageTime() {
        val newer = InboxFeedItem.Conversation(conv("newer", lastMessageAt = 5_000L))
        val older = InboxFeedItem.Conversation(conv("older", lastMessageAt = 500L))

        val out = orderInboxFeed(listOf(older, newer))

        assertEquals(listOf("conv_newer", "conv_older"), out.map { it.key })
    }

    @Test
    fun equalTimestampsKeepTheDerivedRelativeOrder() {
        // 同毫秒的消息不能因为 map 插入序就翻过来：稳定排序必须保留派生顺序。
        val first = InboxFeedItem.Conversation(conv("first", lastMessageAt = 1_000L))
        val second = InboxFeedItem.Conversation(conv("second", lastMessageAt = 1_000L))

        val out = orderInboxFeed(listOf(first, second))

        assertEquals(listOf("conv_first", "conv_second"), out.map { it.key })
    }

    @Test
    fun theConversationPendingCountIsNeverUsedAsAFeedPriority() {
        // 会话的"没发出去的消息数"（ConversationUi.pendingCount，ConversationRow 画徽标）
        // 不参与置顶 —— 否则用户自己发的一条离线消息就会把会话顶到最上面。
        // InboxFeedItem.Conversation.pendingCount 因此恒 0，而不是漏了一个赋值。
        val queued = InboxFeedItem.Conversation(
            conv("queued", lastMessageAt = 1L).copy(pendingCount = 7),
        )
        val other = InboxFeedItem.Conversation(conv("other", lastMessageAt = 9_999L))

        assertEquals(0, queued.pendingCount, "混排键必须恒 0")
        val out = orderInboxFeed(listOf(queued, other))

        assertEquals(listOf("conv_other", "conv_queued"), out.map { it.key })
    }

    @Test
    fun aPendingWorkspaceStillTakesTheTopSlot() {
        // 工作区段的两把尺子不变：待审批的置顶，即使它的活动时间更早。
        val pending = workspace("ws-1", lastActivityAt = 1L, pendingCount = 2)
        val talked = InboxFeedItem.Conversation(conv("talked", lastMessageAt = 9_999L))

        val out = orderInboxFeed(listOf(talked, pending))

        assertEquals(listOf("ws_peer_dsh_ws-1", "conv_talked"), out.map { it.key })
    }

    @Test
    fun duplicatedKeysAreCollapsedToTheFirstOccurrence() {
        val first = InboxFeedItem.Conversation(conv("a", lastMessageAt = 1L))
        val duplicate = InboxFeedItem.Conversation(conv("a", lastMessageAt = 2L))

        val out = orderInboxFeed(listOf(first, duplicate))

        assertEquals(listOf("conv_a"), out.map { it.key })
        assertEquals(1L, (out.single() as InboxFeedItem.Conversation).conv.lastMessageAt)
    }

    // ------------------------------------------------------ 置顶（task-18）

    /**
     * **置顶的行必须排在最前，哪怕它很旧、而且别的行有未处理。**
     *
     * 用户钉它上去的意思就是「不管多旧、多安静，都留在最上面」，
     * 所以置顶优先于「有未处理」那一档 —— 否则一条待审批就能把置顶会话挤下去。
     */
    @Test
    fun pinnedConversationsSortAboveEverythingElse() {
        val oldPinned = InboxFeedItem.Conversation(conv("pinned", lastMessageAt = 1L, isPinned = true))
        val newer = InboxFeedItem.Conversation(conv("newer", lastMessageAt = 9_999L))
        val pendingWs = workspace("ws-1", lastActivityAt = 0L, pendingCount = 3)

        val out = orderInboxFeed(listOf(newer, pendingWs, oldPinned))

        assertEquals("conv_pinned", out.first().key, "置顶必须第一 —— 比新鲜度与待处理都优先")
    }

    /** 没有置顶时行为**完全不变**（回归保护：老用户升级后列表顺序不该动）。 */
    @Test
    fun withoutPinsTheOldOrderIsUntouched() {
        val newer = InboxFeedItem.Conversation(conv("newer", lastMessageAt = 9_999L))
        val older = InboxFeedItem.Conversation(conv("older", lastMessageAt = 1L))

        val out = orderInboxFeed(listOf(older, newer))

        assertEquals(listOf("conv_newer", "conv_older"), out.map { it.key })
    }

    // ------------------------------------------------------ 桌面侧栏同一把尺子（[P1]）

    /**
     * 桌面壳过去对 `state.conversations` 只做查询词过滤、原样 map 进行的容器 ——
     * 置顶的第一排序键只在 [orderInboxFeed] 里，于是同一个置顶动作在手机上生效、
     * 在桌面上点了没反应。现在桌面走 [orderConversationList]，两条用例钉住：
     * 置顶必须最前；无置顶时顺序与派生顺序逐条一致（稳定排序回归保护）。
     */
    @Test
    fun desktopListSortsPinnedFirstEvenWhenItIsTheOldest() {
        val oldPinned = conv("pinned", lastMessageAt = 1L, isPinned = true)
        val newer = conv("newer", lastMessageAt = 9_999L)
        val quiet = conv("quiet", lastMessageAt = 0L, presenceAt = 5_000L)

        val out = orderConversationList(listOf(newer, quiet, oldPinned))

        assertEquals(
            listOf("pinned", "newer", "quiet"),
            out.map { it.convId },
            "桌面列表的第一排序键也必须是置顶，其次才轮到最近消息与在线时间",
        )
    }

    @Test
    fun desktopListWithoutPinsKeepsTheDerivedOrder() {
        val newer = conv("newer", lastMessageAt = 9_999L)
        val older = conv("older", lastMessageAt = 1L)
        val fresh = conv("fresh", lastMessageAt = 0L, presenceAt = 99_000L)

        val out = orderConversationList(listOf(older, newer, fresh))

        assertEquals(
            listOf("newer", "older", "fresh"),
            out.map { it.convId },
            "无置顶时桌面顺序必须与 RelayUiState.conversations 的派生顺序一致",
        )
    }

    @Test
    fun desktopListStillFiltersByQuery() {
        // 桌面壳原来的查询词过滤（标题或预览命中）必须原样保留 —— 排序的修复不许动它。
        val hit = conv("老周", lastMessageAt = 1L).copy(preview = "周五聚餐")
        val miss = conv("老王", lastMessageAt = 2L).copy(preview = "下周再说")

        val out = orderConversationList(listOf(hit, miss), query = "聚餐")

        assertEquals(listOf("老周"), out.map { it.convId })
    }
}
