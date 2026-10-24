// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.navigation

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
    fun openingASessionClosesThePeerDetail() {
        // 「会话与节点详情互斥」这件事由 open 函数保证，不由调用方自觉：从节点详情的
        // 「发消息」进聊天时，detailPeerId 若还挂着，返回键会先弹这层**看不见的**
        // 详情（画面一动不动），第二次才退聊天 —— 现场正是「第一次返回没反应」。
        // 旧实现里 openSession 保留 detailPeerId，这条测试在旧实现下是红的。
        val nav = AppNavState().openPeerDetail(a).openSession(a)
        assertEquals(a, nav.activeSessionId)
        assertNull(nav.detailPeerId)
        assertFalse(nav.inPeerDetail)
    }

    @Test
    fun openingASessionClosesTheAgentDetailLayers() {
        // 同一条纪律的另一头：从工作区详情 / 会话详情点绑定群进聊天（openSession），
        // 两个 Agent 详情层也必须一起退 —— 否则 Agent 子页的返回键处理（注册在
        // 聊天那条之后）会先吃掉返回键，聊天页上按返回画面不动。
        val nav = AppNavState()
            .openAgentWorkspaceDetail(a, "ws-1")
            .openAgentSessionDetail("s1")
            .openSession(a)
        assertEquals(a, nav.activeSessionId)
        assertNull(nav.detailWorkspaceId)
        assertNull(nav.detailAgentSessionId)
        assertFalse(nav.inAgentSubpage)
    }

    @Test
    fun reopeningTheSameSessionAfterDetailsClearedIsStillAPureNoOp() {
        // 幂等守卫随新语义一起收紧：目标状态（含详情层已清）完全一致时仍返回 `this`，
        // 免得连点两次「发消息」造成两次重组。
        val nav = AppNavState().openSession(a)
        assertTrue(nav.openSession(a) === nav)
        // 详情层没清干净时不是 no-op：再点一次要清掉它。
        val withDetail = AppNavState().openSession(a).openPeerDetail(a)
        assertFalse(withDetail.openSession(a) === withDetail)
        assertNull(withDetail.openSession(a).detailPeerId)
    }

    @Test
    fun openingWorkspaceChatClosesAllDetailLayers() {
        // 「没有绑定协同群 → 提示建群 → 直接进会话」这条路从工作区详情里出发：
        // openWorkspaceChat 进聊天时同样要清掉详情层（理由同 openSession），
        // 同时保住工作区收窄的语义（chatWorkspaceId = wsId）。
        val nav = AppNavState()
            .openAgentWorkspaceDetail(a, "ws-1")
            .openAgentSessionDetail("s1")
            .openWorkspaceChat(a, "ws-1")
        assertEquals(a, nav.activeSessionId)
        assertEquals("ws-1", nav.chatWorkspaceId)
        assertNull(nav.detailPeerId)
        assertNull(nav.detailWorkspaceId)
        assertNull(nav.detailAgentSessionId)
        assertFalse(nav.inAgentSubpage)
    }

    @Test
    fun workspaceChatIdempotencyStillHoldsAfterDetailClearing() {
        // 工作区看板点一行会走两次（选中 + 进聊天），第二次必须仍是 no-op。
        val nav = AppNavState().openWorkspaceChat(a, "ws-1")
        assertTrue(nav.openWorkspaceChat(a, "ws-1") === nav)
        // 详情层没清时再点一次要清（返回新实例）。
        val withDetail = AppNavState().openWorkspaceChat(a, "ws-1").openAgentWorkspaceDetail(a, "ws-1")
        assertFalse(withDetail.openWorkspaceChat(a, "ws-1") === withDetail)
        assertNull(withDetail.openWorkspaceChat(a, "ws-1").detailWorkspaceId)
    }

    @Test
    fun saverRoundtripPreservesEveryField() {
        // 全字段非默认的状态走一遍存档映射，必须原样回来 —— 转屏后用户该停在哪
        // 还停在哪。任何一字段漏存，这条就会红。
        val nav = AppNavState(
            selectedTabId = AppRoutes.AGENTS,
            activeSessionId = a,
            activeAgentSessionId = "s1",
            scanning = false,
            detailPeerId = b,
            detailWorkspaceId = "ws-1",
            detailAgentSessionId = "s2",
            chatWorkspaceId = "ws-2",
        )
        assertEquals(nav, appNavStateFromSaverValue(nav.toSaverValue()))
    }

    @Test
    fun saverRoundtripPreservesNullFields() {
        // null 与「哨兵空串」必须是两种东西：全 null 的状态 roundtrip 后还是全 null。
        val nav = AppNavState()
        assertEquals(nav, appNavStateFromSaverValue(nav.toSaverValue()))
        // 半 null：有的字段有值、有的是 null —— 哨兵只认空串，恢复时逐字段还原。
        val half = AppNavState(selectedTabId = AppRoutes.NODES, activeSessionId = a)
        assertEquals(half, appNavStateFromSaverValue(half.toSaverValue()))
    }

    @Test
    fun saverValueIsAFixedLengthTableOfStringsAndBooleans() {
        // 存档格式本身就是契约：定长 8 项，除了 Boolean 的那一位全是 String。
        // 集合里出现别的类型（或 null）就是编码破了 —— 各平台对 Bundle 集合项的
        // 校验口径不一，纯 String + Boolean 是我们给过的承诺。
        val saved = AppNavState(activeSessionId = a, scanning = true).toSaverValue()
        assertEquals(APP_NAV_STATE_SAVER_FIELDS, saved.size)
        assertTrue(saved.all { it is String || it is Boolean })
    }

    @Test
    fun saverRejectsAMalformedArchiveRatherThanGuessing() {
        // 项数不对 = 格式不合身：返回 null（= 放弃恢复、回到初值），不抛、不猜。
        assertNull(appNavStateFromSaverValue(emptyList<Any>()))
        assertNull(appNavStateFromSaverValue(List(APP_NAV_STATE_SAVER_FIELDS - 1) { "" }))
        assertNull(appNavStateFromSaverValue(List(APP_NAV_STATE_SAVER_FIELDS + 1) { "" }))
        // 类型错也一样：Boolean 位上来了个 String，宁可放弃恢复。
        val wrongType: List<Any> = MutableList(APP_NAV_STATE_SAVER_FIELDS) { "" }.also {
            it[3] = "not-a-boolean"
        }
        assertNull(appNavStateFromSaverValue(wrongType))
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
