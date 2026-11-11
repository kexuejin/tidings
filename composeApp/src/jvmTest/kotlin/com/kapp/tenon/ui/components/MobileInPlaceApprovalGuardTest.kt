// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.ui.components

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 守卫 UX 规范 V1 §2.2②「移动端原位审批」。
 *
 * ## 病灶
 *
 * 手机主页的置顶审批卡（`PinnedApprovalFocusCard`）写着一句与真实待批项无关的固定副标
 * （「涉及 Git 远程推送与敏感文件修改」—— 那是**编造**的内容：真实的待批项可能是
 * `rmdir /tmp/cache`），按钮「立即审批」又把人**甩进聊天页**。用户为了批一条审批，
 * 要先离开主页、进群、再找那条卡 —— 就是规范说的「迷宫横跳」。
 *
 * ## 修法（纯 UI，零协议键）
 *
 * - 副标改成**真实事实**：可归因时是「[群名] 待审批：<命令/摘要>」，归因不了才退回
 *   那句通用文案（不编造归属与命令）。
 * - 手机壳（`inPlaceApprovals = true`）点置顶卡**原地**弹出处置面板；面板复用
 *   `AgentApprovalsSection`（逐条处置只有一份实现，面板不另抄批准/拒绝按钮），
 *   处置后由同一份 `agentSlice` 状态自动更新。
 * - 面板打开时主屏那段队列暂时不画：同一个屏幕上不出现两份一模一样的审批列表
 *   （与 §2.2① 桌面两栏去重同一条理由）。
 *
 * 桌面壳保持默认 false：宽屏右栏 inspector 已经常驻审批列表，再弹面板是重复。
 *
 * 反向验证：守卫先于实现落地，实现前判红；实现后转绿。
 */
class MobileInPlaceApprovalGuardTest {

    private val boardPath =
        "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/agents/AgentWorkspaceBoard.kt"
    private val sheetPath =
        "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/agents/AgentApprovalSheet.kt"
    private val consolePath =
        "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/agents/AgentConsoleScreen.kt"
    private val appPath = "composeApp/src/commonMain/kotlin/com/kapp/tenon/App.kt"
    private val zhPath = "composeApp/src/commonMain/composeResources/values/strings.xml"
    private val enPath = "composeApp/src/commonMain/composeResources/values-en/strings.xml"

    private fun live(path: String): String =
        GuardSourceScan.stripComments(GuardSourceScan.read(path))

    private val xmlComment = Regex("""<!--[\s\S]*?-->""")
    private val declaredEntry = Regex("""<string\s+name=""")
    private val entry = Regex("""<string\s+name="([^"]+)"\s*>([\s\S]*?)</string>""")

    private fun values(xml: String): Map<String, String> {
        val stripped = xmlComment.replace(xml, "")
        val declared = declaredEntry.findAll(stripped).count()
        val parsed = entry.findAll(stripped).associate { it.groupValues[1] to it.groupValues[2].trim() }
        return if (declared == parsed.size) parsed else emptyMap()
    }

    private val keys = listOf("approval_sheet_title", "approval_focus_subtitle_detail")

    private fun wired(
        board: String,
        sheet: String,
        console: String,
        app: String,
        zh: String,
        en: String,
    ): Boolean {
        val zhValues = values(zh)
        val enValues = values(en)
        if (zhValues.isEmpty() || enValues.isEmpty()) return false
        if (keys.any { zhValues[it].isNullOrBlank() || enValues[it].isNullOrBlank() }) return false
        return listOf(
            // ① 面板只复用既有队列，不另抄一套逐条处置。
            "internal fun AgentApprovalSheet(",
            "ModalBottomSheet(",
            "AgentApprovalsSection(",
            "Res.string.approval_sheet_title",
        ).all { sheet.contains(it) } && listOf(
            // ② 手机壳参数默认关（桌面不弹面板），置顶卡按它分流。
            "inPlaceApprovals: Boolean = false",
            "var approvalSheetOpen by remember",
            "if (inPlaceApprovals)",
            "AgentApprovalSheet(",
            // ③ 面板打开时主屏队列不画：同屏两份一样的列表是重复渲染。
            "showApprovalQueue && !approvalSheetOpen",
            // ④ 副标是真实事实：群名走席位绑定（单一来源），命令走 summary/tool。
            "Res.string.approval_focus_subtitle_detail",
            "groupBoundToWorkspace(",
            "val approvalAttribution",
        ).all { board.contains(it) } && listOf(
            // ⑤ 参数从屏透传到看板，手机组合根置 true。
            "inPlaceApprovals: Boolean = false",
            "inPlaceApprovals = inPlaceApprovals",
        ).all { console.contains(it) } && app.contains("inPlaceApprovals = true")
    }

    @Test
    fun inPlaceApprovalIsWiredEndToEnd() {
        assertTrue(
            wired(
                live(boardPath),
                live(sheetPath),
                live(consolePath),
                live(appPath),
                GuardSourceScan.read(zhPath),
                GuardSourceScan.read(enPath),
            ),
            "§2.2② 的原位审批面板 / 置顶卡分流 / 手机组合根没有全链路接线",
        )
    }

    @Test
    fun bothLocalesCarryTheSheetAndSubtitleKeys() {
        val zhValues = values(GuardSourceScan.read(zhPath))
        val enValues = values(GuardSourceScan.read(enPath))
        assertTrue(zhValues.isNotEmpty() && enValues.isNotEmpty(), "资源解析完整性")
        keys.forEach { key ->
            assertTrue(zhValues.containsKey(key), "中文资源缺 $key")
            assertTrue(enValues.containsKey(key), "英文资源缺 $key")
            assertFalse(zhValues[key].orEmpty().contains("工作区"), "$key 的中文值带回了工程词汇")
            assertFalse(
                enValues[key].orEmpty().contains("Workspace", ignoreCase = true),
                "$key 的英文值带回了工程词汇",
            )
        }
        assertEquals(zhValues.size, enValues.size, "双语逐键对账")
    }

    @Test
    fun everyCriticalFragmentIsRemovalSensitive() {
        val board = live(boardPath)
        val sheet = live(sheetPath)
        val console = live(consolePath)
        val app = live(appPath)
        val zh = GuardSourceScan.read(zhPath)
        val en = GuardSourceScan.read(enPath)
        assertTrue(wired(board, sheet, console, app, zh, en), "变异自检的起点必须是绿")

        listOf(
            "internal fun AgentApprovalSheet(",
            "ModalBottomSheet(",
            "AgentApprovalsSection(",
            "Res.string.approval_sheet_title",
        ).forEach { fragment ->
            assertFalse(
                wired(board, sheet.replace(fragment, ""), console, app, zh, en),
                "面板里摘掉「$fragment」后判据仍为真 ⇒ 这段是摆设",
            )
        }
        listOf(
            "inPlaceApprovals: Boolean = false",
            "var approvalSheetOpen by remember",
            "if (inPlaceApprovals)",
            "AgentApprovalSheet(",
            "showApprovalQueue && !approvalSheetOpen",
            "Res.string.approval_focus_subtitle_detail",
            "groupBoundToWorkspace(",
            "val approvalAttribution",
        ).forEach { fragment ->
            assertFalse(
                wired(board.replace(fragment, ""), sheet, console, app, zh, en),
                "看板里摘掉「$fragment」后判据仍为真 ⇒ 这段是摆设",
            )
        }
        assertFalse(
            wired(board, sheet, console.replace("inPlaceApprovals = inPlaceApprovals", ""), app, zh, en),
            "屏没有把参数透传给看板时判据必须判红",
        )
        assertFalse(
            wired(board, sheet, console, app.replace("inPlaceApprovals = true", ""), zh, en),
            "手机组合根没有打开原位审批时判据必须判红",
        )
        keys.forEach { key ->
            val mutatedZh = zh.replace(Regex("""<string name="$key">[\s\S]*?</string>"""), "")
            assertFalse(wired(board, sheet, console, app, mutatedZh, en), "$key 从中文资源摘掉后判据仍为真")
            val mutatedEn = en.replace(Regex("""<string name="$key">[\s\S]*?</string>"""), "")
            assertFalse(wired(board, sheet, console, app, zh, mutatedEn), "$key 从英文资源摘掉后判据仍为真")
        }
    }

    @Test
    fun guardIsNotFooledByCommentsOrEmptySources() {
        val zh = GuardSourceScan.read(zhPath)
        val en = GuardSourceScan.read(enPath)
        assertFalse(wired("", "", "", "", zh, en), "空源码必须判红，不能恒真")
        assertFalse(
            wired(
                "// var approvalSheetOpen by remember\n/* AgentApprovalSheet( */",
                "/* AgentApprovalsSection( */",
                "// inPlaceApprovals = inPlaceApprovals",
                "// inPlaceApprovals = true",
                zh,
                en,
            ),
            "只出现在注释里的片段不能算接线",
        )
    }
}
