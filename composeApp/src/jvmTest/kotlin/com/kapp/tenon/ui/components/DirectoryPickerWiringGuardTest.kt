// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.ui.components

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「登记项目目录」这条路必须能**点出来**，而不是只靠手打绝对路径。
 *
 * ## 为什么需要这条守卫
 *
 * 三平台都实现了 `saveDirectoryPicker`，但全仓**零调用点** —— 于是建群弹窗里的
 * 「浏览」因为空实现被整颗删掉，用户只能手输路径（macOS 上基本打不对）。现在把
 * 系统选目录窗接到目录端口（`HostDirectoryPort.canPickDirectory` / `pickDirectory`），
 * 两处输入框（建群表单、群设置的「登记新目录」）都接上。
 *
 * 该接的不止一处，所以判据分两层：
 *  1. **能力位**决定画不画（`canPickDirectory`，不是「端口非空」）—— 无头宿主有端口
 *     却弹不出窗，只判非空就会画出一颗点不动的图标；
 *  2. **选到的路径真的落进输入框**（不是把返回值丢掉）。
 *
 * 本仓没有 Compose UI 测试基建，这些都在 Composable 内部，只能在剥掉注释的活代码上钉
 * （与 `WorkspaceGroupCreateDialogGuardTest` 同一取舍）。反向探针见
 * [guardDetectsEachRemovedPiece]：每条判据都要能在内存里被删掉并报红。
 */
class DirectoryPickerWiringGuardTest {

    private fun live(path: String): String = GuardSourceScan.stripComments(GuardSourceScan.read(path))

    private val dialog =
        "composeApp/src/commonMain/kotlin/com/kapp/tenon/ui/components/WorkspaceGroupCreateDialog.kt"
    private val groupAuth =
        "composeApp/src/commonMain/kotlin/com/kapp/tenon/ui/components/SetGroupAgentWorkspacesDialog.kt"
    private val helper =
        "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/conversations/WorkspaceGroupCreation.kt"
    private val app = "composeApp/src/commonMain/kotlin/com/kapp/tenon/App.kt"
    private val deskShell = "composeApp/src/commonMain/kotlin/com/kapp/tenon/ui/desktop/DesktopShell.kt"
    private val deskConversations =
        "composeApp/src/commonMain/kotlin/com/kapp/tenon/ui/desktop/DesktopConversationsPane.kt"

    /** 建群表单：能力为 null 时连回调都不构造 ⇒ 图标不画；选到的路径写回同一个状态。 */
    private fun dialogWiresThePicker(src: String): Boolean =
        src.contains("onPickDirectory: (suspend () -> String?)? = null") &&
            src.contains("onBrowse = onPickDirectory?.let") &&
            src.contains("trailingIcon = onBrowse?.let") &&
            src.contains("?.let { newPath = it }")

    /** 群设置的登记框：同一能力位、同一个落点。 */
    private fun groupAuthWiresThePicker(src: String): Boolean =
        src.contains("if (port.canPickDirectory)") &&
            src.contains("port.pickDirectory()") &&
            src.contains("newPath = it")

    /** 三处建群入口共用一份判据：判能力位而不是端口非空。 */
    private fun helperJudgesTheCapability(src: String): Boolean =
        src.contains("hostDirectoryPort?.takeIf { it.canPickDirectory }") &&
            src.contains("port.pickDirectory()")

    @Test
    fun createGroupFormOffersTheSystemPicker() {
        assertTrue(
            dialogWiresThePicker(live(dialog)),
            "建群弹窗的新目录表单没接系统选目录窗（或没把选中的路径写回输入框）—— " +
                "用户只能手打绝对路径，macOS 上等于没有入口",
        )
    }

    @Test
    fun groupAuthRegisterFieldOffersTheSystemPicker() {
        assertTrue(
            groupAuthWiresThePicker(live(groupAuth)),
            "群设置里「登记新目录」仍只有手输框 —— 同一件事两处入口必须同能力",
        )
    }

    @Test
    fun everyCreateEntryAsksTheCapabilityNotJustThePort() {
        assertTrue(
            helperJudgesTheCapability(live(helper)),
            "系统选目录回调没有按 canPickDirectory 求值 —— 无头宿主会画出一颗点不动的图标",
        )
        for (path in listOf(app, deskShell, deskConversations)) {
            assertTrue(
                live(path).contains("onPickDirectory = controller.systemDirectoryPicker()"),
                "$path 没有把系统选目录回调传给建群弹窗（三处入口各写一份判据必然漂移，必须走同一个 helper）",
            )
        }
    }

    /** 逐条摘掉关键片段，判据必须各自报一次红；在内存里做，不改生产文件。 */
    @Test
    fun guardDetectsEachRemovedPiece() {
        val dialogSrc = live(dialog)
        listOf(
            "onPickDirectory: (suspend () -> String?)? = null",
            "onBrowse = onPickDirectory?.let",
            "trailingIcon = onBrowse?.let",
            "?.let { newPath = it }",
        ).forEach { needle ->
            assertFalse(
                dialogWiresThePicker(dialogSrc.replace(needle, "")),
                "删掉 [$needle] 之后建群表单的判据仍然绿 —— 这条守卫没有钉住任何东西",
            )
        }

        val groupAuthSrc = live(groupAuth)
        listOf(
            "if (port.canPickDirectory)",
            "port.pickDirectory()",
            "newPath = it",
        ).forEach { needle ->
            assertFalse(
                groupAuthWiresThePicker(groupAuthSrc.replace(needle, "")),
                "删掉 [$needle] 之后群设置登记框的判据仍然绿",
            )
        }

        val helperSrc = live(helper)
        listOf(
            "hostDirectoryPort?.takeIf { it.canPickDirectory }",
            "port.pickDirectory()",
        ).forEach { needle ->
            assertFalse(
                helperJudgesTheCapability(helperSrc.replace(needle, "")),
                "删掉 [$needle] 之后 helper 的判据仍然绿",
            )
        }
    }
}
