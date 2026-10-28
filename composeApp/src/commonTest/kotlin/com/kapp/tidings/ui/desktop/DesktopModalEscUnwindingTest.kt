// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 守卫「桌面窗全局 Esc 键弹窗栈回退机制（Modal Dialog Stack Unwinding）」。
 *
 * ## 背景
 * 桌面环境下用户期望物理 Esc 键能够遵循层叠深度倒序（LIFO）自顶向下逐层收起打开的
 * 模态对话框、自绘浮层与搜索面板。
 *
 * ## 优先级契约
 * 1. [DesktopModalLayer.WsQr]：二维码弹窗（最高优先级浮层）；
 * 2. [DesktopModalLayer.GroupCreatePrompt]：建群提示框；
 * 3. [DesktopModalLayer.CreateWs]：协同工作区创建对话框；
 * 4. [DesktopModalLayer.SearchQuery]：若搜索框内有已键入的文字，Esc 先清空内容；
 * 5. [DesktopModalLayer.Searching]：若处于窄窗展开态搜索，Esc 收起搜索栏；
 * 6. [DesktopModalLayer.AgentSession]：若处于工作区子会话详情，Esc 回退至顶级会话；
 * 7. [DesktopModalLayer.None]：无活动浮层。
 */
class DesktopModalEscUnwindingTest {

    @Test
    fun allLayersActiveUnwindsFromTopToBottom() {
        var hasWsQr = true
        var hasGroupCreatePrompt = true
        var hasCreateWs = true
        var hasSearchQuery = true
        var isSearching = true
        var hasAgentSession = true

        assertEquals(
            DesktopModalLayer.WsQr,
            nextModalLayerToDismiss(hasWsQr, hasGroupCreatePrompt, hasCreateWs, hasSearchQuery, isSearching, hasAgentSession),
            "第 1 步优先关闭 WsQr",
        )
        hasWsQr = false

        assertEquals(
            DesktopModalLayer.GroupCreatePrompt,
            nextModalLayerToDismiss(hasWsQr, hasGroupCreatePrompt, hasCreateWs, hasSearchQuery, isSearching, hasAgentSession),
            "第 2 步关闭 GroupCreatePrompt",
        )
        hasGroupCreatePrompt = false

        assertEquals(
            DesktopModalLayer.CreateWs,
            nextModalLayerToDismiss(hasWsQr, hasGroupCreatePrompt, hasCreateWs, hasSearchQuery, isSearching, hasAgentSession),
            "第 3 步关闭 CreateWs",
        )
        hasCreateWs = false

        assertEquals(
            DesktopModalLayer.SearchQuery,
            nextModalLayerToDismiss(hasWsQr, hasGroupCreatePrompt, hasCreateWs, hasSearchQuery, isSearching, hasAgentSession),
            "第 4 步清空搜索文本",
        )
        hasSearchQuery = false

        assertEquals(
            DesktopModalLayer.Searching,
            nextModalLayerToDismiss(hasWsQr, hasGroupCreatePrompt, hasCreateWs, hasSearchQuery, isSearching, hasAgentSession),
            "第 5 步收起搜索面板",
        )
        isSearching = false

        assertEquals(
            DesktopModalLayer.AgentSession,
            nextModalLayerToDismiss(hasWsQr, hasGroupCreatePrompt, hasCreateWs, hasSearchQuery, isSearching, hasAgentSession),
            "第 6 步回退 AgentSession",
        )
        hasAgentSession = false

        assertEquals(
            DesktopModalLayer.None,
            nextModalLayerToDismiss(hasWsQr, hasGroupCreatePrompt, hasCreateWs, hasSearchQuery, isSearching, hasAgentSession),
            "无活动浮层时返回 None",
        )
    }

    @Test
    fun isolatedLayersEachDismissCorrectly() {
        assertEquals(
            DesktopModalLayer.WsQr,
            nextModalLayerToDismiss(hasWsQr = true, hasGroupCreatePrompt = false, hasCreateWs = false, hasSearchQuery = false, isSearching = false, hasAgentSession = false),
        )
        assertEquals(
            DesktopModalLayer.GroupCreatePrompt,
            nextModalLayerToDismiss(hasWsQr = false, hasGroupCreatePrompt = true, hasCreateWs = false, hasSearchQuery = false, isSearching = false, hasAgentSession = false),
        )
        assertEquals(
            DesktopModalLayer.CreateWs,
            nextModalLayerToDismiss(hasWsQr = false, hasGroupCreatePrompt = false, hasCreateWs = true, hasSearchQuery = false, isSearching = false, hasAgentSession = false),
        )
        assertEquals(
            DesktopModalLayer.SearchQuery,
            nextModalLayerToDismiss(hasWsQr = false, hasGroupCreatePrompt = false, hasCreateWs = false, hasSearchQuery = true, isSearching = false, hasAgentSession = false),
        )
        assertEquals(
            DesktopModalLayer.Searching,
            nextModalLayerToDismiss(hasWsQr = false, hasGroupCreatePrompt = false, hasCreateWs = false, hasSearchQuery = false, isSearching = true, hasAgentSession = false),
        )
        assertEquals(
            DesktopModalLayer.AgentSession,
            nextModalLayerToDismiss(hasWsQr = false, hasGroupCreatePrompt = false, hasCreateWs = false, hasSearchQuery = false, isSearching = false, hasAgentSession = true),
        )
        assertEquals(
            DesktopModalLayer.None,
            nextModalLayerToDismiss(hasWsQr = false, hasGroupCreatePrompt = false, hasCreateWs = false, hasSearchQuery = false, isSearching = false, hasAgentSession = false),
        )
    }
}
