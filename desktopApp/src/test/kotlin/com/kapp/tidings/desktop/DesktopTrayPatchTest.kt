// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 捎话 Tidings contributors

package com.kapp.tidings.desktop

import com.kapp.tidings.ui.desktop.TrayAction
import com.kapp.tidings.ui.desktop.TrayMenuItem
import com.kapp.tidings.ui.desktop.TrayMenuModel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「这一份渲染相对上一份，哪些原生写操作真的需要重做」。
 *
 * 为什么这条判据值得单测：DesktopTrayHandle.apply 里每一次 icon.popupMenu = 都会让
 * 系统重建一条原生菜单，而重建一次状态项菜单会让菜单栏重排几何 —— 代价由 WindowServer
 * 主线程付。真机上这条路的后果是卡死到重启。
 *
 * 所以「只变了未读角标」必须**不等于**「要重建菜单」。这里钉的就是这个区分。
 *
 * 这些断言能区分对错：把 trayPatch 改回「模型变了就全做」，第二条就会红。
 */
class DesktopTrayPatchTest {

    private fun model(title: String = "捎话 Tidings", items: List<TrayMenuItem> = itemsOf("打开主窗口")) =
        TrayMenuModel(title = title, items = items)

    private fun itemsOf(vararg labels: String) =
        labels.map { TrayMenuItem(it, enabled = true, action = TrayAction.ShowMain) }

    @Test
    fun `首次渲染三项全做`() {
        val patch = trayPatch(previous = null, next = TrayAppliedState(model(), hasUnread = false))
        assertTrue(patch.rebuildMenu, "装上托盘时必须先把菜单画出来")
        assertTrue(patch.updateToolTip, "装上托盘时必须先把提示语写出来")
        assertTrue(patch.updateImage, "装上托盘时必须先把图标画出来")
    }

    @Test
    fun `只有未读角标变了就只重画图标`() {
        // 这是本次最想守的一条：收了一条消息，菜单不该被重建。
        val before = TrayAppliedState(model(), hasUnread = false)
        val after = TrayAppliedState(model(), hasUnread = true)
        val patch = trayPatch(before, after)
        assertTrue(patch.updateImage, "角标该变")
        assertFalse(patch.rebuildMenu, "未读角标变化不该让系统重建菜单")
        assertFalse(patch.updateToolTip, "标题没变就不该重写 tooltip")
    }

    @Test
    fun `什么都没变就一项都不做`() {
        val state = TrayAppliedState(model(), hasUnread = true)
        val patch = trayPatch(state, state)
        assertFalse(patch.rebuildMenu)
        assertFalse(patch.updateToolTip)
        assertFalse(patch.updateImage)
    }

    @Test
    fun `条目表变了才重建菜单`() {
        val before = TrayAppliedState(model(items = itemsOf("打开主窗口")), hasUnread = false)
        val after = TrayAppliedState(model(items = itemsOf("打开主窗口", "退出")), hasUnread = false)
        val patch = trayPatch(before, after)
        assertTrue(patch.rebuildMenu, "条目表变了必须重建菜单，否则用户点不到新条目")
        assertFalse(patch.updateImage, "条目变化不顺带重画图标")
    }

    @Test
    fun `只有标题变了就只重写提示语`() {
        // 「收到 1 个文件（12 MB）」与「3 条新消息」都在 title 上，条目表不动。
        val before = TrayAppliedState(model(title = "捎话 Tidings"), hasUnread = false)
        val after = TrayAppliedState(model(title = "3 条新消息"), hasUnread = false)
        val patch = trayPatch(before, after)
        assertTrue(patch.updateToolTip)
        assertFalse(patch.rebuildMenu, "标题变化不该让系统重建菜单")
        assertFalse(patch.updateImage, "标题变化不顺带重画图标")
    }

    @Test
    fun `条目的置灰状态变化也算结构变化`() {
        // 「收到的文件」在 0 个文件时置灰、1 个文件时可点。这必须重建菜单，
        // 否则用户会看到一个永远点不动的条目。
        val dim = TrayMenuItem("收到的文件", enabled = false, action = TrayAction.OpenReceivedFiles)
        val live = TrayMenuItem("收到的文件", enabled = true, action = TrayAction.OpenReceivedFiles)
        val patch = trayPatch(
            TrayAppliedState(model(items = listOf(dim)), hasUnread = false),
            TrayAppliedState(model(items = listOf(live)), hasUnread = false),
        )
        assertTrue(patch.rebuildMenu)
    }
}
