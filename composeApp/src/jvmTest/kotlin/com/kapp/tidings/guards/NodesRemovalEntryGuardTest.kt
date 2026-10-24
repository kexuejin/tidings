// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.guards

import com.kapp.tidings.testsupport.GuardSourceScan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 节点页三条「接线完整性」纪律的源码级守卫 —— 本仓没有 Compose UI 测试基建，
 * "这颗按钮画不画、回调通没通"只能在源码层钉住（与 [DeadButtonGuardTest] 同一取舍）。
 *
 * - **移除节点必须有确认框、且确认后才调回调**：移除是破坏性动作，两段式（先弹框、
 *   确认框里才 `onRemovePeer`）—— 直连一次就把确认框绕过去了。
 * - **两个壳都要接到同一条回调**：`onRemovePeer` / `onCleanupUnreachable` 在
 *   `NodesScreen`（手机/Compact）与 `DesktopNodesPane`（宽档）各有一条接线，
 *   漏一条就是"另一个壳上没有移除入口"。
 * - **「连接」不再由调用方立刻关弹层**：上一版调用方同拍 `sheetOpen = false`，
 *   弹层里那套"3.2 秒超时红字 + 原地重试"在生产一次都走不到。
 * - **扫码段的「请切到填地址」横幅只在扫码入口都缺失时画**：手机端按钮明明在，
 *   横幅却写着让用户切走 —— 自相矛盾的 P0。
 *
 * ⚠️ 必须**先剥注释**：这些文件的注释里正当引用着同名回调与字符串键，
 * 裸 grep 会被自己的注释绊倒（AGENTS.md §4）。
 */
class NodesRemovalEntryGuardTest {

    private val nodeDetail =
        "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/nodes/NodeDetail.kt"
    private val nodesScreen =
        "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/nodes/NodesScreen.kt"
    private val nodeSheets =
        "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/nodes/NodeSheets.kt"
    private val desktopNodesPane =
        "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopNodesPane.kt"

    // ── 移除节点 ─────────────────────────────────────────────────────────────

    @Test
    fun removeActionIsGatedToManualEntries() {
        val text = GuardSourceScan.stripComments(GuardSourceScan.read(nodeDetail))
        assertTrue(
            text.contains("onRemovePeer?.takeIf { manualEntry != null }"),
            "「移除」只对有手动记录的行成立：移除的语义是删本机存的手动地址，" +
                "局域网扫到的对端没有可删的东西（没有这条闸会画出按了必炸的按钮）。",
        )
    }

    @Test
    fun removeGoesThroughTheConfirmDialog() {
        val text = GuardSourceScan.stripComments(GuardSourceScan.read(nodeDetail))
        val dialog = callSpanContaining(text, "RelayDialog(", "nodes_remove_confirm_title")
        assertTrue(
            dialog.contains("onRemoveConfirmed()"),
            "确认框里的确认按钮才是真正调 onRemovePeer 的地方；调用链断了按钮就只是弹个框。",
        )
        assertTrue(
            dialog.contains("danger = true"),
            "确认按钮必须是 danger 形态：破坏性动作与「取消」在形状上就要分开。",
        )
        assertTrue(
            dialog.contains("common_cancel"),
            "确认框必须给得出「取消」这条路。",
        )
    }

    @Test
    fun bothShellsWireRemovePeerIntoTheDetailBody() {
        val phone = GuardSourceScan.stripComments(GuardSourceScan.read(nodesScreen))
        assertTrue(
            callSpanContaining(phone, "NodeDetailBody(", "onRemovePeer = onRemovePeer").isNotEmpty(),
            "手机壳（NodesScreen）要把 onRemovePeer 穿进详情体 —— 漏了它详情页就没有移除入口。",
        )
        val desktop = GuardSourceScan.stripComments(GuardSourceScan.read(desktopNodesPane))
        assertTrue(
            callSpanContaining(desktop, "NodeDetailPane(", "onRemovePeer = controller::removeManualPeer").isNotEmpty(),
            "桌面宽档要把 controller::removeManualPeer 接进右栏详情。",
        )
        // NodeDetailPane 内部必须真的把它传给 NodeDetailBody（参数接了不传 = 空接线）。
        assertTrue(
            callSpanContaining(desktop, "NodeDetailBody(", "onRemovePeer = onRemovePeer").isNotEmpty(),
            "桌面详情面板内部要把 onRemovePeer 传给共用的 NodeDetailBody。",
        )
    }

    // ── 清理没有回应的 ───────────────────────────────────────────────────────

    @Test
    fun cleanupUnreachableIsWiredOnBothShells() {
        val phone = GuardSourceScan.stripComments(GuardSourceScan.read(nodesScreen))
        val phoneButton = callSpanContaining(phone, "AppTextButton(", "disc_cleanup_unreachable")
        assertTrue(
            phoneButton.contains("onClick = onCleanupUnreachable"),
            "手机列表的「清理没有回应的」必须真的调 onCleanupUnreachable。",
        )
        val desktop = GuardSourceScan.stripComments(GuardSourceScan.read(desktopNodesPane))
        assertTrue(
            callSpanContaining(desktop, "NodeList(", "onCleanupUnreachable = controller::removeUnreachableManualPeers").isNotEmpty(),
            "桌面宽档的左栏也要有「清理没有回应的」接线。",
        )
        assertTrue(
            callSpanContaining(desktop, "AppTextButton(", "disc_cleanup_unreachable").contains("onCleanupUnreachable"),
            "桌面左栏那颗清理按钮必须真的调 onCleanupUnreachable。",
        )
    }

    // ── 复制地址 ─────────────────────────────────────────────────────────────

    @Test
    fun copyAddressActionActuallyWritesClipboard() {
        val text = GuardSourceScan.stripComments(GuardSourceScan.read(nodeDetail))
        val button = callSpanContaining(text, "AppTextButton(", "nodes_menu_copy")
        assertTrue(
            button.contains("clipboard.setText"),
            "「复制地址」按下要真的写剪贴板 —— 只换文案不写剪贴板就是死按钮。",
        )
        assertTrue(
            text.contains("nodes_menu_copied"),
            "复制成功要有行内反馈（nodes_menu_copied）。",
        )
    }

    // ── 「连接」不再被调用方立刻关弹层 ───────────────────────────────────────

    @Test
    fun callersDoNotCloseTheAddNodeSheetOnConnect() {
        val phone = GuardSourceScan.stripComments(GuardSourceScan.read(nodesScreen))
        val phoneSheet = callSpanContaining(phone, "AddNodeSheet(", "onConnect =")
        val phoneOnConnect = GuardSourceScan.bracedBlockAfter(phoneSheet, "onConnect =")
        assertFalse(
            phoneOnConnect.contains("sheetOpen = false"),
            "手机壳点「连接」不再立刻关弹层：关早了超时红字与原地重试在生产一次都走不到。" +
                "成功关层由弹层自己判（节点表出现目标即 onDismiss）。",
        )
        val desktop = GuardSourceScan.stripComments(GuardSourceScan.read(desktopNodesPane))
        val desktopSheet = callSpanContaining(desktop, "AddNodeSheet(", "onConnect =")
        val desktopOnConnect = GuardSourceScan.bracedBlockAfter(desktopSheet, "onConnect =")
        assertFalse(
            desktopOnConnect.contains("addNodeOpen = false"),
            "桌面宽档点「连接」同理不关弹层。",
        )
    }

    // ── 移除回执：横幅宿主只有一处，且在选择分支里 ───────────────────────────

    @Test
    fun removalReceiptBannerHostIsInsideTheSelectedBranch() {
        val pane = GuardSourceScan.stripComments(GuardSourceScan.read(desktopNodesPane))
        // 一个宿主就够：两处都画 = 同一条提示两个 ✕，用户关掉一个还有一个。
        assertEquals(
            1,
            Regex("state\\.banner\\?\\.let").findAll(pane).count(),
            "节点页（宽档）只许有一条横幅宿主；多画一条就是「同一件事两个来源」。",
        )
        val tierIf = "if (tier == DesktopWindowSizeClass.Compact)"
        val compact = GuardSourceScan.bracedBlockAfter(pane, tierIf)
        assertTrue(
            compact.contains("NodesScreen("),
            "窄档必须把整页委托给 NodesScreen —— 横幅归它画，本文件不重复画。",
        )
        assertFalse(
            compact.contains("AppBanner("),
            "窄档里再画一次横幅就是双横幅：NodesScreen 已经画了一条同源的。",
        )
        // 关键：宿主必须落在 `} else {` 之后（宽档分支里）。抬到 if 之上时，窄档会同时
        // 显示本文件的一条与 NodesScreen 的一条。
        val elseAt = pane.indexOf("} else {", pane.indexOf(tierIf))
        val hostAt = pane.indexOf("state.banner?.let")
        assertTrue(
            elseAt >= 0,
            "找不到 tier if 的 else 分支 —— 结构变了，这条守卫需要跟着改。",
        )
        assertTrue(
            hostAt > elseAt,
            "横幅宿主必须写在宽档分支（else）里：抬到 if 之上，窄档就会与 NodesScreen 各画一条。",
        )
        // 另一个宿主：手机屏自己只画一条。
        val screen = GuardSourceScan.stripComments(GuardSourceScan.read(nodesScreen))
        assertEquals(
            1,
            Regex("state\\.banner\\?\\.let").findAll(screen).count(),
            "NodesScreen 只许有一条横幅宿主（手机壳与桌面窄档共用它）。",
        )
    }

    // ── 双壳的手动地址装配（移除入口的数据来源） ─────────────────────────────

    @Test
    fun bothShellsShareTheManualEntryAssembly() {
        val phone = GuardSourceScan.stripComments(GuardSourceScan.read(nodesScreen))
        assertTrue(
            phone.contains("manualEntriesByNodeId(manualStatuses)"),
            "手机壳要用共用的手动地址装配（manualEntriesByNodeId），别各写一份 mapNotNull。",
        )
        val desktop = GuardSourceScan.stripComments(GuardSourceScan.read(desktopNodesPane))
        assertTrue(
            desktop.contains("manualEntriesByNodeId(statuses)"),
            "桌面宽档也要用同一份装配 —— 「已握过手的手动地址」在两边都得带着 manualEntry，" +
                "否则同一行手机上能移除、桌面上不能。",
        )
        assertFalse(
            desktop.contains("manualEntry = null"),
            "桌面宽档不许再把每个对端的 manualEntry 硬置 null：那正是「桌面没有移除入口」的现场。",
        )
    }

    // ── 扫码段的「请切到填地址」横幅只在入口都缺失时画 ───────────────────────

    @Test
    fun scanUnavailableBannerOnlyWhenBothScanEntriesAreMissing() {
        val text = GuardSourceScan.stripComments(GuardSourceScan.read(nodeSheets))
        // 用 `Res.string.` 前缀定位**消费点**，跳过文件头那行 import ——
        // 否则 indexOf 命中的是 import 行，substring 直接越界。
        val at = text.indexOf("Res.string.nodes_scan_unavailable")
        assertTrue(at >= 0, "找不到 nodes_scan_unavailable 的消费点。")
        // 往前找包住它的 if：必须是「两个扫码入口都是 null」这道闸。
        val prefix = text.substring(0, at)
        val ifAt = prefix.lastIndexOf("if (onScanQr == null && onImportQrFromImage == null)")
        assertTrue(
            ifAt >= 0,
            "「请切到填地址」横幅必须收进 if (onScanQr == null && onImportQrFromImage == null)：" +
                "手机端按钮明明在，横幅却叫人切走 —— 同一屏自相矛盾。",
        )
        // 且这层 if 与横幅之间没有提前收口（`}` 更近于 `if` 说明横幅在闸外）。
        val between = text.substring(ifAt, at)
        assertFalse(
            between.contains("} else {") || between.contains("}\n            }"),
            "横幅要真的画在那道闸的分支里，而不是闸外又画了一份。",
        )
    }

    // ── 复用 ClearAllConfirmationGuardTest 的取块手法 ─────────────────────────

    private fun callSpanContaining(text: String, marker: String, needle: String): String {
        var from = 0
        while (true) {
            val at = text.indexOf(marker, from)
            if (at < 0) throw AssertionError("源码里找不到包含「" + needle + "」的 " + marker)
            val span = callSpan(text, marker, at)
            if (span.contains(needle)) return span
            from = at + marker.length
        }
    }

    private fun callSpan(text: String, marker: String, fromIndex: Int): String {
        val at = text.indexOf(marker, fromIndex)
        if (at < 0) throw AssertionError("源码里找不到调用：" + marker)
        var depth = 0
        var i = at + marker.length - 1
        while (i < text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return text.substring(at, i + 1)
                }
            }
            i++
        }
        throw AssertionError("括号不配对：" + text.substring(at, minOf(text.length, at + 120)))
    }
}
