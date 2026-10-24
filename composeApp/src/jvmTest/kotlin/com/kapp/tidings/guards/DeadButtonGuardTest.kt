// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.guards

import com.kapp.tidings.testsupport.GuardSourceScan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 守卫「界面不说假话」这一类里的三条（task-3）：
 *
 * - **A-1 死按钮**：文件柜那颗「打开」以前挂着空实现，两个宿主各一颗（手机 `CabinetScreen`、
 *   桌面 `CabinetPane`）。纪律：有真实动作就接上，没有就**整颗不画**（可空槽 = 不画）。
 * - **A-2 恒「刚刚」**：`CabinetScreen` 的 `now` 默认值是 `0L`，调用点不传时
 *   `relativeTime(ts, 0)` 的 delta 恒为负，永远落进"刚刚"那一档。
 * - **A-5 恒灰按钮**：诊断页「运行自检」是那一页唯一的动作，却因为宿主不传
 *   `onRunSelfCheck` 永远 `enabled = false`。
 *
 * ## 为什么是源码级守卫
 *
 * 本仓没有 Compose UI 测试基建（没有 `ui-test` 依赖），"这颗按钮画不画"只能在源码层钉住 ——
 * 与 [PairingEntryPointsGuardTest] 同一取舍。**先剥注释**（见 [GuardSourceScan.stripComments]），
 * 否则注释里正当提到的旧写法会把守卫绊倒。
 *
 * ## 为什么空 onClick 是**允许清单**而不是绝对零
 *
 * 生产源码里还有三处**有意**的空实现，它们不是"死入口"：一张图的点击穿透层、
 * 一个只认长按的气泡、一颗 `enabled = false` 的菜单项。绝对零会让守卫一开始就假红，
 * 所以这里把三处理由写死，并要求它们**各自只剩那一处** —— 同一个文件里再多一处就红。
 *
 * **这张白名单只收"有理由的空实现"，不收"还没修的欠账"**：建群对话框里那颗「浏览」
 * 按钮（`workspace_create_group_browse`）也曾是空实现，它被**删掉**之后就从这里撤了账 ——
 * 留着一条已不存在的欠账，会让后来读代码的人以为那颗按钮还在。任何新的空实现要么
 * 接上真实动作，要么整颗不画，别往这里加名字。
 */
class DeadButtonGuardTest {

    private val cabinetScreen =
        "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/cabinet/CabinetScreen.kt"
    private val cabinetPane =
        "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/CabinetPane.kt"
    private val app =
        "composeApp/src/commonMain/kotlin/com/kapp/tidings/App.kt"
    private val diag =
        "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/settings/SettingsDiagSubpage.kt"
    private val shell =
        "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopShell.kt"
    private val workspaceDialog =
        "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/components/WorkspaceGroupCreateDialog.kt"
    private val desktopWindow =
        "desktopApp/src/main/kotlin/com/kapp/tidings/desktop/DesktopWindow.kt"
    private val peerWindow =
        "desktopApp/src/main/kotlin/com/kapp/tidings/desktop/DesktopPeerWindow.kt"

    private val emptyOnClick = Regex("""onClick\s*=\s*\{\s*\}""")

    /**
     * 文件路径 → 这一处空实现**为什么**成立（取它附近必须出现的标记）。
     *
     * 三个都是"这个手势/菜单项本来就没有单击动作"，不是"按钮点不动"。
     */
    private val allowedEmptyOnClick = mapOf(
        // 图片预览：空 onClick 是点击穿透层，压住背景的"点外面关闭"。
        "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/components/ImageViewerDialog.kt" to "indication = null",
        // 气泡：单击不做事，长按才开菜单（combinedClickable 的两个槽）。
        "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/chat/ChatBubbles.kt" to "onLongClick",
        // 会话列表 ⋮ 里那颗灰着的「最近活跃」：enabled = false，M3 仍要求一个 onClick。
        "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/conversations/ConversationsOverflowItems.kt" to "enabled = false",
    )

    @Test
    fun noNewDeadButtonHandlersInProductionSources() {
        val offenders = mutableListOf<String>()
        val scanned = GuardSourceScan.kotlinFiles(
            "composeApp/src/commonMain",
            "composeApp/src/androidMain",
            "composeApp/src/iosMain",
            "composeApp/src/jvmMain",
            "composeApp/src/jvmSharedMain",
            "androidApp/src/main",
            "desktopApp/src/main",
            "core/src/commonMain",
        )
        // 扫描面必须非空：[GuardSourceScan.kotlinFiles] 对**不存在的目录**返回空列表，
        // 于是「没有 offender」恒真 —— 本仓源码门禁最常见的假绿形态。
        assertTrue(
            scanned.size > 200,
            "只扫到 ${scanned.size} 个生产源文件 —— 扫描根写错了？（空清单会让这条守卫永远绿）",
        )
        scanned.forEach { path ->
            val text = GuardSourceScan.stripComments(GuardSourceScan.read(path))
            val hits = emptyOnClick.findAll(text).count()
            if (hits == 0) return@forEach
            val allowedMarker = allowedEmptyOnClick[path]
            if (allowedMarker == null) {
                offenders += path + "（" + hits + " 处）"
                return@forEach
            }
            assertEquals(
                1,
                hits,
                path + " 的空 onClick 从 1 处变成了 " + hits + " 处 —— 这个文件只允许那一处有理由的空实现。",
            )
            val match = emptyOnClick.find(text)!!
            val from = (match.range.first - 250).coerceAtLeast(0)
            val to = (match.range.last + 250).coerceAtMost(text.length)
            val window = text.substring(from, to)
            assertTrue(
                window.contains(allowedMarker),
                path + " 的空 onClick 不再是「" + allowedMarker + "」那个有理由的用法 —— " +
                    "如果这是一颗新的按钮，请给它真实动作，或整颗不画。",
            )
        }
        assertTrue(
            offenders.isEmpty(),
            "这些生产文件里有按下去什么都不发生的 onClick：" + offenders + " —— " +
                "「一个按下去没反应的入口比没有更糟」：要么接上真实动作，要么传 null 让按钮不画。",
        )
    }

    @Test
    fun cabinetOpenButtonIsGatedOnARealActionInExactlyOnePlace() {
        // 两个壳现在**共用** `MineCabinetView`（见 AGENTS.md §2.6）：「打开」那颗按钮的定义
        // 只剩一处。所以断言从"每个文件都有"改成"**恰好一处**"。
        //
        // 这不是放宽：后者在合并之后会假红，而且它守不住真正要守的东西 ——
        // "有人又照着复制了一份"。数出来的 1 同时守住了这两件。
        var gatedDefinitions = 0
        for (path in listOf(cabinetScreen, cabinetPane)) {
            val text = GuardSourceScan.stripComments(GuardSourceScan.read(path))
            assertFalse(
                emptyOnClick.containsMatchIn(text),
                path + " 里还有空实现的「打开」按钮（cabinet_open）—— 按下去什么都不发生的入口比没有更糟。",
            )
            // bracedBlockAfter 在**找不到锚点时会抛异常**（它不允许"锚点没了"这种沉默），
            // 所以这里必须先判存在 —— 另一个壳本来就不该有这段，不是错误。
            if (text.contains("if (openShareRoot != null)")) {
                val gated = GuardSourceScan.bracedBlockAfter(text, "if (openShareRoot != null)")
                if (gated.contains("cabinet_open")) gatedDefinitions++
            }
        }
        assertEquals(
            1,
            gatedDefinitions,
            "「打开共享目录」按钮必须**恰好定义一处**（两个壳共用同一个实现）；" +
                "0 = 按钮丢了，2 = 又复制了一份。",
        )
        // 可空槽这道判据在**定义处**依然成立：null = 本平台没有这条出口，按钮不画。
        val screenText = GuardSourceScan.stripComments(GuardSourceScan.read(cabinetScreen))
        assertTrue(
            screenText.contains("onOpenShareRoot: (() -> Unit)? = null"),
            "MineCabinetView 的「打开」动作必须是一个可空槽：null = 本平台没有这条出口，按钮不画。",
        )
        assertTrue(
            GuardSourceScan.bracedBlockAfter(screenText, "if (openShareRoot != null)").contains("cabinet_open"),
            "MineCabinetView 的「打开」按钮没有被 openShareRoot != null 包住 —— 它又会在没有动作的平台上画出来。",
        )
    }

    @Test
    fun desktopShellThreadsTheShareRootActionToTheCabinet() {
        val shellText = GuardSourceScan.read(shell)
        assertTrue(
            shellText.contains("val onOpenShareRoot: (() -> Unit)? = null"),
            "DesktopShellActions 必须有一条可缺席的「打开共享目录」动作（缺席 = 那颗按钮不画）。",
        )
        val cabinetCall = GuardSourceScan.parenBlockAfter(shellText, "CabinetPane(")
        assertTrue(
            cabinetCall.contains("onOpenShareRoot = actions.onOpenShareRoot"),
            "桌面壳必须把这条动作传给文件柜 —— 不传的话桌面那颗「打开」又变回不存在。",
        )
        for (path in listOf(desktopWindow, peerWindow)) {
            val text = GuardSourceScan.read(path)
            assertTrue(
                text.contains("shareRoot = state.settings.shareRoot?.takeIf { it.isNotBlank() }"),
                path + " 必须把「没设共享目录」落成 null（null ⇒ 按钮不画），" +
                    "而不是给一个点开一个空路径的动作。",
            )
            assertTrue(
                text.contains("onOpenShareRoot = shareRoot?.let"),
                path + " 必须只在真的设了共享目录时才递出这条出口。",
            )
        }
    }

    @Test
    fun workspaceCreateBrowseButtonStaysGone() {
        val text = GuardSourceScan.stripComments(GuardSourceScan.read(workspaceDialog))
        assertFalse(
            emptyOnClick.containsMatchIn(text),
            "建群对话框里又出现了一颗按下去什么都不发生的按钮 —— " +
                "目录选择能力接不上时，那颗按钮应当整颗不画（路径输入框仍在）。",
        )
    }

    @Test
    fun cabinetRelativeTimeUsesARealClock() {
        for (path in listOf(cabinetScreen, cabinetPane)) {
            val text = GuardSourceScan.read(path)
            assertTrue(
                text.contains("now: Long = nowMillis()"),
                path + " 的 now 必须默认成真实时钟；默认 0L 时 relativeTime(ts, 0) 的 delta " +
                    "恒为负，界面上永远是「刚刚」。",
            )
            assertFalse(
                text.contains("now: Long = 0L"),
                path + " 又出现了 now: Long = 0L 这个假基准。",
            )
        }
        val appCall = GuardSourceScan.parenBlockAfter(
            GuardSourceScan.stripComments(GuardSourceScan.read(app)),
            "CabinetScreen(",
        )
        assertTrue(
            appCall.contains("now = nowMillis()"),
            "App.kt 的 CabinetScreen 调用点必须显式传真实时钟（组合根这一层要能一眼看出它读的是墙上时钟）。",
        )
    }

    @Test
    fun diagSelfCheckButtonIsOnlyDrawnWhenItCanRun() {
        val text = GuardSourceScan.stripComments(GuardSourceScan.read(diag))
        assertFalse(
            text.contains("enabled = onRunSelfCheck"),
            "「运行自检」又变成了一颗永远灰着的按钮 —— 它是那一页唯一的动作，灰着读起来像页面坏了。",
        )
        val runnable = GuardSourceScan.bracedBlockAfter(text, "if (runSelfCheck != null)")
        assertTrue(
            runnable.contains("onClick = runSelfCheck"),
            "「运行自检」按钮的 onClick 必须是宿主递进来的真实动作。",
        )
        assertTrue(
            runnable.contains("Res.string.set_diag_selfcheck)"),
            "「运行自检」按钮必须画在 runSelfCheck != null 的分支里（没有动作就不画）。",
        )
        assertEquals(
            1,
            Regex("""Res\.string\.set_diag_selfcheck\)""").findAll(text).count(),
            "「运行自检」按钮只能画一处 —— 两处意味着它又在别处无条件画了出来。",
        )
        assertTrue(
            text.substringAfter(runnable).contains("set_diag_selfcheck_unavailable"),
            "没有真实动作时必须留一句说明（null 分支），不能让那一页只剩一排实时状态。",
        )
    }
}
