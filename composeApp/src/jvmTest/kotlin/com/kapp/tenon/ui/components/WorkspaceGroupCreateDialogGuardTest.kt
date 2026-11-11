// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.ui.components

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 智能体与跨平台体验规范 §2.1 / §2.2 的源码级守卫。
 *
 * 本仓没有 Compose UI 测试基建，而这两条纪律全在 Composable 内部：
 * 「手机端会不会画出一条自己根本没有的目录输入」「宽屏左右两栏会不会画同一批审批」。
 * 单测够不到，只能在剥掉注释的活代码上钉住 —— 与
 * `DesktopInspectorContentGuardTest` / `AgentBoardContainerGuardTest` 同一取舍。
 *
 * 反向验证见 `guardDetectsEachRemovedPiece`：每条判据都要能在内存里被删掉并报红，
 * 否则它只是「文件里出现过这个词」，不是判据。
 */
class WorkspaceGroupCreateDialogGuardTest {

    private fun live(path: String): String = GuardSourceScan.stripComments(GuardSourceScan.read(path))

    private val dialog = "composeApp/src/commonMain/kotlin/com/kapp/tenon/ui/components/WorkspaceGroupCreateDialog.kt"
    private val app = "composeApp/src/commonMain/kotlin/com/kapp/tenon/App.kt"
    private val deskShell = "composeApp/src/commonMain/kotlin/com/kapp/tenon/ui/desktop/DesktopShell.kt"
    private val deskConversations = "composeApp/src/commonMain/kotlin/com/kapp/tenon/ui/desktop/DesktopConversationsPane.kt"
    private val previews = "androidApp/src/screenshotTest/kotlin/com/kapp/tenon/domain/screenshot/ScreenshotPreviewsM3Shared.kt"
    private val board = "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/agents/AgentWorkspaceBoard.kt"
    private val console = "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/agents/AgentConsoleScreen.kt"

    /** 「新目录」这一支必须由能力开关闸门，且默认关（漏传的调用点不许拿到伪输入框）。 */
    private fun dialogIsCapabilityGated(src: String): Boolean =
        src.contains("allowNewDirectory: Boolean = false") &&
            src.contains("val activeTab = if (allowNewDirectory) selectedTab else CreateGroupTab.Existing") &&
            src.contains("CreateGroupTab.NewDirectory ->") &&
            !src.contains("workspace_create_group_or_new")

    /** 三段审批去重：看板有闸门、控制台有透传、桌面壳按 inspector 是否激活求值。 */
    private fun approvalDedupIsWired(boardSrc: String, consoleSrc: String, shellSrc: String): Boolean =
        boardSrc.contains("showApprovalQueue: Boolean = true") &&
            boardSrc.contains("approvalRows.isNotEmpty() && showApprovalQueue") &&
            consoleSrc.contains("showBoardApprovalQueue: Boolean = true") &&
            consoleSrc.contains("showApprovalQueue = showBoardApprovalQueue") &&
            shellSrc.contains("val inspectorShowsApprovals = sizeClass.hasInspector && view == DesktopView.Agents") &&
            shellSrc.contains("showBoardApprovalQueue = !inspectorShowsApprovals")

    @Test
    fun newDirectoryBranchIsCapabilityGated() {
        assertTrue(
            dialogIsCapabilityGated(live(dialog)),
            "建群弹窗没有把「关联本地新目录」收成能力开关的分段分支 —— 手机端（无宿主目录端口）" +
                "会继续看到手输绝对路径的伪表单，且默认必须是关的",
        )
    }

    @Test
    fun everyCallSiteDerivesCapabilityFromHostDirectoryPort() {
        for (path in listOf(app, deskShell, deskConversations)) {
            val src = live(path)
            assertTrue(
                src.contains("allowNewDirectory = controller.hostDirectoryPort != null"),
                "$path 没有按宿主目录端口传 allowNewDirectory —— 用「是不是桌面」猜会在" +
                    "没有端口的宿主上画出无效表单（一个事实只有一个来源）",
            )
        }
    }

    @Test
    fun everyPreviewPicksItsOwnSide() {
        val src = live(previews)
        val calls = src.split("WorkspaceGroupCreateDialog(").size - 1
        val armed = src.split("allowNewDirectory =").size - 1
        assertTrue(calls >= 2, "建群弹窗的两张预览帧不见了 —— 守卫不能在空集合上恒真")
        assertTrue(
            armed == calls,
            "预览有 $calls 处调用却只有 $armed 处显式传 allowNewDirectory —— " +
                "手机帧与桌面帧必须各自选边，默认值会让其中一张帧画错形态",
        )
        assertTrue(src.contains("allowNewDirectory = false"), "缺手机帧（无目录端口）")
        assertTrue(src.contains("allowNewDirectory = true"), "缺桌面帧（有目录端口）")
    }

    @Test
    fun wideInspectorDoesNotDuplicateApprovalQueue() {
        assertTrue(
            approvalDedupIsWired(live(board), live(console), live(deskShell)),
            "Wide + Agents 时右侧 inspector 与主屏看板仍会画同一批审批卡 —— " +
                "闸门必须从「inspector 是否激活」求值，不是从窗口档位猜",
        )
    }

    /** 逐条摘掉关键片段，判据必须各自报一次红；在内存里做，不改生产文件。 */
    @Test
    fun guardDetectsEachRemovedPiece() {
        val dialogSrc = live(dialog)
        listOf(
            "allowNewDirectory: Boolean = false",
            "val activeTab = if (allowNewDirectory) selectedTab else CreateGroupTab.Existing",
            "CreateGroupTab.NewDirectory ->",
        ).forEach { needle ->
            assertFalse(
                dialogIsCapabilityGated(dialogSrc.replace(needle, "")),
                "删掉 [$needle] 之后判据仍然绿 —— 这条守卫没有钉住任何东西",
            )
        }

        val boardSrc = live(board)
        val consoleSrc = live(console)
        val shellSrc = live(deskShell)
        listOf(
            Triple(boardSrc, "showApprovalQueue: Boolean = true", 0),
            Triple(boardSrc, "approvalRows.isNotEmpty() && showApprovalQueue", 0),
            Triple(consoleSrc, "showBoardApprovalQueue: Boolean = true", 1),
            Triple(consoleSrc, "showApprovalQueue = showBoardApprovalQueue", 1),
            Triple(shellSrc, "val inspectorShowsApprovals = sizeClass.hasInspector && view == DesktopView.Agents", 2),
            Triple(shellSrc, "showBoardApprovalQueue = !inspectorShowsApprovals", 2),
        ).forEach { (original, needle, side) ->
            val patched = original.replace(needle, "")
            val b = if (side == 0) patched else boardSrc
            val c = if (side == 1) patched else consoleSrc
            val s = if (side == 2) patched else shellSrc
            assertFalse(
                approvalDedupIsWired(b, c, s),
                "删掉 [$needle] 之后审批去重的判据仍然绿 —— 这条接线没被钉住",
            )
        }
    }
}
