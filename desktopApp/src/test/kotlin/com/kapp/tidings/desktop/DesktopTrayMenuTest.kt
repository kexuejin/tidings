// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 捎话 Tidings contributors

package com.kapp.tidings.desktop

import com.kapp.tidings.ui.desktop.*

import com.kapp.tidings.domain.model.FileCardUi
import com.kapp.tidings.domain.model.MessageUi
import com.kapp.tidings.domain.model.TransferState
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.desktop_app_title
import com.kapp.tidings.generated.resources.desktop_menu_new_conversation
import com.kapp.tidings.generated.resources.desktop_tray_agent_running
import com.kapp.tidings.generated.resources.desktop_tray_agent_stopped
import com.kapp.tidings.generated.resources.desktop_tray_headline_files
import com.kapp.tidings.generated.resources.desktop_tray_headline_unread
import com.kapp.tidings.generated.resources.desktop_tray_open_main
import com.kapp.tidings.generated.resources.desktop_tray_open_settings
import com.kapp.tidings.generated.resources.desktop_tray_quit
import com.kapp.tidings.generated.resources.desktop_tray_received_files
import com.kapp.tidings.generated.resources.desktop_tray_received_files_count
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 托盘菜单的形态。
 *
 * 托盘在 CI 里建不出来（没有 SystemTray），但菜单该长什么样完全是纯数据决定 ——
 * 把这部分抽出来就是为了能在这里钉住：标题报哪条新闻、哪些条目该灰、
 * 「收到的文件」数的是哪些文件。
 *
 * 断言比的是「哪条资源 + 哪些参数」而不是最终文本：解析要 composition
 * （`stringResource` 在 composable 里，`getString` 要资源环境），而这里刻意是纯 JVM 单测。
 * 资源本身的值由 strings.xml 审查 + `:cjkLiteralGate` 之外的 `.cvr` 逐字节核对保证。
 */
class DesktopTrayMenuTest {

    private fun menuOf(
        unread: Int = 0,
        files: Int = 0,
        bytes: Long = 0,
        agent: Boolean = false,
    ) = trayMenuSpec(unreadTotal = unread, receivedFiles = files, receivedBytes = bytes, agentRunning = agent)

    @Test
    fun `没有新闻时标题回落到产品名且不出现说明性条目`() {
        val model = menuOf()
        assertEquals(TrayText(Res.string.desktop_app_title), model.title)
        assertTrue(model.items.none { it.action == null && it.label == model.title })
        assertEquals(
            listOf(
                Res.string.desktop_tray_open_main,
                Res.string.desktop_menu_new_conversation,
                Res.string.desktop_tray_received_files,
                Res.string.desktop_tray_agent_stopped,
                Res.string.desktop_tray_open_settings,
                Res.string.desktop_tray_quit,
            ),
            model.items.map { it.label.resource },
        )
    }

    @Test
    fun `未读优先于新文件`() {
        // 两个都在时标题只能显示一个。消息是有人在对你说活，文件是东西到了 ——
        // 前者更该把人叫回来。
        val model = menuOf(unread = 3, files = 1, bytes = 12L * 1024 * 1024)
        val expected = TrayText(Res.string.desktop_tray_headline_unread, listOf(3))
        assertEquals(expected, model.title)
    }

    @Test
    fun `未读数不进条目表`() {
        // 未读数是每收一条消息就变的量。它要是进了条目表，AWT 那侧每收一条消息
        // 就得整份重建原生菜单 —— 而重建一次状态项菜单会让菜单栏重排几何，
        // 代价由 WindowServer 主线程付（真机上表现为系统卡死）。
        //
        // 判据：条目表在未读数不同时**完全一致**，只有标题跟着变。
        val none = menuOf(unread = 0, files = 1)
        val one = menuOf(unread = 1, files = 1)
        val many = menuOf(unread = 99, files = 1)
        assertEquals(none.items, one.items, "未读 0→1 不该改动条目表")
        assertEquals(none.items, many.items, "未读 1→99 不该改动条目表")
        // 头条仍然要说出来 —— 只是换到了 title（tooltip）上。
        assertEquals(TrayText(Res.string.desktop_tray_headline_unread, listOf(1)), one.title)
        assertEquals(TrayText(Res.string.desktop_tray_headline_unread, listOf(99)), many.title)
    }

    @Test
    fun `没有未读时报新文件并带上人类可读体积`() {
        val model = menuOf(files = 1, bytes = 12L * 1024 * 1024)
        assertEquals(TrayText(Res.string.desktop_tray_headline_files, listOf(1, "12 MB")), model.title)
        assertTrue(
            model.items.any { it.label == TrayText(Res.string.desktop_tray_received_files_count, listOf(1)) },
        )
    }

    @Test
    fun `一个文件都没有时收到的文件条目置灰但仍然存在`() {
        val item = menuOf().items.single { it.action == TrayAction.OpenReceivedFiles }
        assertEquals(TrayText(Res.string.desktop_tray_received_files), item.label)
        assertFalse(item.enabled)
        // 条目留着：否则用户不知道这台机器能不能收文件。
        assertEquals(TrayAction.OpenReceivedFiles, item.action)
    }

    @Test
    fun `Agent 运行态如实反映控制面`() {
        assertEquals(TrayText(Res.string.desktop_tray_agent_stopped), menuOf(agent = false).items[3].label)
        assertEquals(TrayText(Res.string.desktop_tray_agent_running), menuOf(agent = true).items[3].label)
        // 两个方向都不可点：它是状态，不是命令。
        assertFalse(menuOf(agent = true).items[3].enabled)
    }

    @Test
    fun `每个可点条目都带动作`() {
        val model = menuOf(unread = 1, files = 2, bytes = 300, agent = true)
        model.items.filter { it.enabled }.forEach { item ->
            assertTrue(item.action != null, "「${item.label.resource.key}」可点却没有动作")
        }
        // 动作各出现一次：说明性条目出现两次不算漏。
        //
        // **`OpenAgentGrants` 是条件项**：只在「有待批准的授权申请」时才出现 ——
        // 常驻一条会让人以为「这里总有东西」，与本仓「能力不存在就不给一个按下去没反应
        // 的入口」同一条纪律。所以这里把它排除，并单独由 `TrayPendingGrantsTest` 钉住两侧。
        assertEquals(
            TrayAction.entries.toSet() - TrayAction.OpenAgentGrants,
            model.items.mapNotNull { it.action }.toSet(),
        )
    }

    @Test
    fun `收到的文件只数已完成的入站文件`() {
        val messages = mapOf(
            "peer-a" to listOf(
                fileMsg("m1", mine = false, state = TransferState.Completed, size = 100),
                // 传输中不算"收到"：角标会在传输过程中一直跳，点进去还是半个文件。
                fileMsg("m2", mine = false, state = TransferState.Active, size = 200),
                // 出站不算"收到"。
                fileMsg("m3", mine = true, state = TransferState.Completed, size = 400),
                // 纯文本消息没有 file，不能把整条 thread 当成文件。
                MessageUi(id = "m4", peerId = "peer-a", mine = false, text = "在吗", at = 1L),
            ),
            "peer-b" to listOf(
                fileMsg("m5", mine = false, state = TransferState.Completed, size = 50),
                fileMsg("m6", mine = false, state = TransferState.Failed, size = 800),
            ),
        )
        assertEquals(2 to 150L, receivedFileStats(messages))
    }

    @Test
    fun `没有消息时是零而不是崩`() {
        assertEquals(0 to 0L, receivedFileStats(emptyMap()))
    }

    private fun fileMsg(id: String, mine: Boolean, state: TransferState, size: Long) = MessageUi(
        id = id,
        peerId = "p",
        mine = mine,
        text = "",
        at = 1L,
        file = FileCardUi(transferId = id, name = "$id.pdf", size = size, mine = mine, state = state),
    )
}
