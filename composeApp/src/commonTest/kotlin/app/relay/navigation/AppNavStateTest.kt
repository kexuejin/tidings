// SPDX-License-Identifier: GPL-3.0-only

package app.relay.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 导航状态机。
 *
 * 这些规则本身就是「详情页 + 顶级页签」这块功能的全部内容：返回键该不该被吃掉、
 * 未知页签 id 要不要接受 —— 全都是纯函数，所以全都钉在这里，不靠人肉点一遍应用来确认。
 *
 * 这里原来还钉着「并排打开的多个会话」那一套（关掉标签落到哪个邻居、一个不剩时退出
 * 详情）。聊天详情顶部的会话人标签条已按产品决策整体移除，那套规则连同状态字段一起
 * 消失：现在状态里只有「当前是哪一个会话」，切换会话就是回列表再点进去。
 * 见 AppNavState 的 KDoc。
 */
class AppNavStateTest {

    private val a = "node-aaaaaaaa"
    private val b = "node-bbbbbbbb"

    @Test
    fun freshStateStartsOnConversationsAndOutsideChat() {
        val nav = AppNavState()
        assertEquals(AppRoutes.CONVERSATIONS, nav.selectedTabId)
        assertNull(nav.activeSessionId)
        assertFalse(nav.inChatDetail)
    }

    @Test
    fun openingASessionEntersChatDetail() {
        val nav = AppNavState().openSession(a)
        assertEquals(a, nav.activeSessionId)
        assertTrue(nav.inChatDetail)
    }

    @Test
    fun reopeningTheSameSessionIsAPureNoOp() {
        // 连点同一个会话不该造出新状态（`===`）：状态里只有「当前是哪一个」，
        // 没有会被堆出一排的标签要防，但也没必要让每一下点击都触发一次重组。
        val nav = AppNavState().openSession(a)
        assertTrue(nav.openSession(a) === nav)
    }

    @Test
    fun switchingToAnotherSessionReplacesTheActiveOne() {
        val nav = AppNavState().openSession(a).openSession(b)
        assertEquals(b, nav.activeSessionId)
        // 换回去也一样：状态里不留"打开过的历史"。
        assertEquals(a, nav.openSession(a).activeSessionId)
    }

    @Test
    fun leavingChatDetailReturnsToList() {
        val nav = AppNavState().openSession(a).leaveChatDetail()
        assertNull(nav.activeSessionId)
        assertFalse(nav.inChatDetail)
        assertEquals(AppRoutes.CONVERSATIONS, nav.selectedTabId)
    }

    @Test
    fun leavingChatDetailWhenAlreadyOutsideIsAPureNoOp() {
        val fresh = AppNavState()
        assertTrue(fresh.leaveChatDetail() === fresh)
    }

    @Test
    fun switchingTabsLeavesChatDetail() {
        val nav = AppNavState().openSession(a).selectTab(AppRoutes.SETTINGS)
        assertEquals(AppRoutes.SETTINGS, nav.selectedTabId)
        assertNull(nav.activeSessionId)
    }

    @Test
    fun anUnknownTabIdIsIgnoredRatherThanRenderingNothing() {
        // 接受了不存在的页签 id，`when` 会走到 else 分支画出一个和「消息」重复的列表，
        // 用户看到的是"点了没反应"，比停在原地更难查。
        val nav = AppNavState().openSession(a)
        assertEquals(nav, nav.selectTab("chat"))
        assertEquals(nav, nav.selectTab(""))
    }

    @Test
    fun scanningStartsOffAndTogglesIdempotently() {
        val fresh = AppNavState()
        assertFalse(fresh.scanning)
        val on = fresh.startScanning()
        assertTrue(on.scanning)
        // 连点两下「扫码配对」不该叠出两层取景框。
        assertEquals(on, on.startScanning())
        assertFalse(on.stopScanning().scanning)
        assertEquals(fresh, fresh.stopScanning())
    }

    @Test
    fun switchingTabsClosesTheScanner() {
        // 扫码页盖在页签之上：不关掉它，用户点了别的页签**什么也看不到**，
        // 而相机还在后面开着（隐私指示灯亮着）。
        val nav = AppNavState().startScanning()
        val after = nav.selectTab(AppRoutes.SETTINGS)
        assertFalse(after.scanning)
        assertEquals(AppRoutes.SETTINGS, after.selectedTabId)
    }

    @Test
    fun peerDetailStartsOffAndOpensIdempotently() {
        val fresh = AppNavState()
        assertFalse(fresh.inPeerDetail)
        val open = fresh.openPeerDetail(a)
        assertTrue(open.inPeerDetail)
        assertEquals(a, open.detailPeerId)
        // 连点同一个节点不该叠出两层详情。
        assertEquals(open, open.openPeerDetail(a))
        // 点另一个节点是换目标，不是叠层。
        assertEquals("node-bbbbbbbb", open.openPeerDetail("node-bbbbbbbb").detailPeerId)
        assertFalse(open.leavePeerDetail().inPeerDetail)
        assertEquals(fresh, fresh.leavePeerDetail())
    }

    @Test
    fun switchingTabsClosesThePeerDetail() {
        // 节点详情和扫码页一样盖在页签之上：不关掉它，用户点了别的页签**什么也看不到**。
        val nav = AppNavState().openPeerDetail(a)
        val after = nav.selectTab(AppRoutes.CABINET)
        assertFalse(after.inPeerDetail)
        assertEquals(AppRoutes.CABINET, after.selectedTabId)
    }

    @Test
    fun peerDetailAndChatDetailAreIndependent() {
        // 两个详情各自管一层，`openSession` 不该顺手关掉节点详情 ——
        // 会话与节点详情互斥这件事由「点节点先切回会话页签」在 UI 层保证。
        val nav = AppNavState().openPeerDetail(a).openSession(a)
        assertEquals(a, nav.detailPeerId)
        assertEquals(a, nav.activeSessionId)
    }

    @Test
    fun explicitAgentSessionWinsWhileItStillExists() {
        assertEquals("s2", resolveAgentSessionId("s2", listOf("s1", "s2", "s3")))
    }

    @Test
    fun removedAgentSessionFallsBackToTheFirst() {
        // session 被宿主移除、或用户换了 agent 节点，都落到这条：面板不能停在空处。
        assertEquals("s1", resolveAgentSessionId("gone", listOf("s1", "s2")))
    }

    @Test
    fun noPickYetFallsBackToTheFirst() {
        // 「默认选中最近更新的那个」由 `RelayUiState.agentSessionsOf` 的 `updatedAt`
        // 降序给定，这里只取首个、不重排 —— 所以那条规则只写在那一处。
        assertEquals("s1", resolveAgentSessionId(null, listOf("s1", "s2")))
    }

    @Test
    fun noSessionsAtAllResolvesToNull() {
        assertNull(resolveAgentSessionId("s1", emptyList()))
        assertNull(resolveAgentSessionId(null, emptyList()))
    }

    @Test
    fun pickingAnAgentSessionLeavesTheChatDetailAlone() {
        // 两个 `active*` 是彼此独立的两件事，别互相顶开。
        val nav = AppNavState().openSession(a).selectAgentSession("s1")
        assertEquals("s1", nav.activeAgentSessionId)
        assertEquals(a, nav.activeSessionId)

        // 切到别的顶级页签再回来，选择还在（有意不在 `selectTab` 里清）。
        assertEquals("s1", nav.selectTab(AppRoutes.AGENTS).activeAgentSessionId)
    }

    @Test
    fun agentSessionDetailOpensAndLeavesCorrectly() {
        val fresh = AppNavState()
        assertFalse(fresh.inAgentSessionDetail)
        assertNull(fresh.detailAgentSessionId)

        val open = fresh.openAgentSessionDetail("s1")
        assertTrue(open.inAgentSessionDetail)
        assertEquals("s1", open.detailAgentSessionId)
        assertEquals("s1", open.activeAgentSessionId)

        // 幂等性
        assertEquals(open, open.openAgentSessionDetail("s1"))

        // 切会话原地更新
        val switched = open.selectAgentSession("s2")
        assertEquals("s2", switched.detailAgentSessionId)
        assertEquals("s2", switched.activeAgentSessionId)

        // 离开详情回到看板
        val left = switched.leaveAgentSessionDetail()
        assertFalse(left.inAgentSessionDetail)
        assertNull(left.detailAgentSessionId)
        assertEquals("s2", left.activeAgentSessionId) // 仍记住选中的 session
    }

    @Test
    fun switchingTabsClosesAgentSessionDetail() {
        val nav = AppNavState().openAgentSessionDetail("s1")
        assertTrue(nav.inAgentSessionDetail)
        val after = nav.selectTab(AppRoutes.CONVERSATIONS)
        assertFalse(after.inAgentSessionDetail)
        assertNull(after.detailAgentSessionId)
    }

    @Test
    fun agentWorkspaceDetailOpensAndLeavesCorrectly() {
        val fresh = AppNavState()
        assertFalse(fresh.inAgentWorkspaceDetail)
        assertFalse(fresh.inAgentSubpage)
        assertNull(fresh.detailWorkspaceId)

        val openWs = fresh.openAgentWorkspaceDetail("node-1", "ws-1")
        assertTrue(openWs.inAgentWorkspaceDetail)
        assertFalse(openWs.inAgentSessionDetail)
        assertTrue(openWs.inAgentSubpage)
        assertEquals("ws-1", openWs.detailWorkspaceId)
        assertEquals("node-1", openWs.detailPeerId)

        // 幂等性
        assertEquals(openWs, openWs.openAgentWorkspaceDetail("node-1", "ws-1"))

        // 在工作区详情中进入会话详情（从 L2 进 L3）
        val openSession = openWs.openAgentSessionDetail("s1")
        assertTrue(openSession.inAgentSessionDetail)
        assertTrue(openSession.inAgentWorkspaceDetail) // 仍然保留处于工作区详情的上下文
        assertTrue(openSession.inAgentSubpage)
        assertEquals("ws-1", openSession.detailWorkspaceId)
        assertEquals("s1", openSession.detailAgentSessionId)

        // 退出会话详情回到工作区详情（从 L3 退回 L2）
        val backToWs = openSession.leaveAgentSessionDetail()
        assertFalse(backToWs.inAgentSessionDetail)
        assertTrue(backToWs.inAgentWorkspaceDetail)
        assertTrue(backToWs.inAgentSubpage)
        assertEquals("ws-1", backToWs.detailWorkspaceId)
        assertNull(backToWs.detailAgentSessionId)

        // 退出工作区详情回到看板（从 L2 退回 L1）
        val backToBoard = backToWs.leaveAgentWorkspaceDetail()
        assertFalse(backToBoard.inAgentWorkspaceDetail)
        assertFalse(backToBoard.inAgentSessionDetail)
        assertFalse(backToBoard.inAgentSubpage)
        assertNull(backToBoard.detailWorkspaceId)
        assertNull(backToBoard.detailAgentSessionId)
    }

    @Test
    fun switchingTabsClosesAgentWorkspaceDetail() {
        val nav = AppNavState().openAgentWorkspaceDetail("node-1", "ws-1")
        assertTrue(nav.inAgentWorkspaceDetail)
        assertTrue(nav.inAgentSubpage)
        val after = nav.selectTab(AppRoutes.CABINET)
        assertFalse(after.inAgentWorkspaceDetail)
        assertFalse(after.inAgentSubpage)
        assertNull(after.detailWorkspaceId)
    }
}
