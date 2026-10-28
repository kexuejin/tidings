// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui

import androidx.compose.ui.graphics.Color
import com.kapp.tidings.testsupport.GuardSourceScan
import com.kapp.tidings.ui.components.AvatarPalette
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 壳层 / 无障碍的源码级守卫（T25，对应 review 的 P1-①、P1-②、P1-③、P2-⑥、P2-⑧）。
 *
 * ## 为什么是源码守卫
 *
 * 对比度是**可算的**（见下面第一条，真算 WCAG）；但"某个 remember 有没有换成 Saveable"、
 * "平板上有没有接系统返回键"、"横幅有没有 liveRegion"都是结构事实，本仓没有 Compose UI
 * 测试基建（没有 createComposeRule），只能在源码文本上钉 —— 与 AppShellSaveableStateGuardTest
 * 同一取舍，每条都先剥注释再匹配。
 */
class ShellAccessibilityGuardTest {

    private val desktopShell = "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopShell.kt"
    private val common = "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/components/Common.kt"

    private fun src(path: String): String = GuardSourceScan.stripComments(GuardSourceScan.read(path))

    // ---------------------------------------------------------------- 头像首字对比度

    @Test
    fun everyAvatarBackgroundKeepsItsInitialLegible() {
        // 首字模式是**默认形态**（avatar = -1），底色按昵称哈希分配 —— 用户没得挑。
        // 判据用 WCAG：小字 4.5:1。改回固定白色前景这条立刻红（杏黄只有 2.95:1）。
        val bad = AvatarPalette.COLORS.mapNotNull { c ->
            val ratio = AvatarPalette.contrastRatio(AvatarPalette.foregroundOn(c.bg), c.bg)
            if (ratio < 4.5f) c.name + "=" + ratio else null
        }
        assertTrue(bad.isEmpty(), "以下底色上的首字不到 4.5:1：" + bad)
    }

    @Test
    fun theHuangAvatarThatUsedToFailIsTheReasonThisExists() {
        val huang = AvatarPalette.COLORS.first { it.name == "杏黄" }.bg
        // 旧前景（白）确实不合格 —— 证明这条测试测的是真问题，不是自我安慰。
        assertTrue(AvatarPalette.contrastRatio(Color.White, huang) < 3f, "杏黄配白字本来就该判红")
        assertTrue(AvatarPalette.contrastRatio(AvatarPalette.foregroundOn(huang), huang) >= 4.5f)
    }

    @Test
    fun theInkSwitchCoversTheDeadZoneBetweenBlackAndWhite() {
        // 合成用例：亮度跨过阈值上下两侧的灰都要 ≥4.5:1（不是只对 10 个品牌色成立）。
        // 用 Int 构造（0xAARRGGBB）：Color(ULong) 收的是**打包的 64 位色彩值**
        // （色彩空间 id + rgba），拿 0xFF3D3D3D 当 ULong 传会得到 NaN 分量 —— 那就变成
        // 测了个假输入（首字对比度 NaN）。这里显式用 Int 重载。
        listOf(0xFF3D3D3D, 0xFF6E6E6E, 0xFF808080, 0xFF9A9A9A, 0xFFC88A2D).forEach { argb ->
            val bg = Color(argb)
            val ratio = AvatarPalette.contrastRatio(AvatarPalette.foregroundOn(bg), bg)
            assertTrue(ratio >= 4.5f, "底色 " + argb.toString(16) + " 上首字只有 " + ratio)
        }
    }

    // ---------------------------------------------------------------- 平板转屏不丢状态

    @Test
    fun desktopShellNavStateSurvivesConfigChange() {
        val text = src(desktopShell)
        // 这六格是"用户在哪"：转屏后会话消失 / 视图跳回列表就是它们被扔回初值。
        listOf(
            "var view by rememberSaveable(stateSaver = DesktopViewSaver)",
            "var selectedPeerId by rememberSaveable",
            "var selectedWsId by rememberSaveable",
            "var selectedAgentSessionId by rememberSaveable",
            "var query by rememberSaveable",
            "var searching by rememberSaveable",
        ).forEach { line ->
            assertTrue(text.contains(line), "壳层导航状态退回裸 remember 了：缺 「" + line + "」")
        }
    }

    // ---------------------------------------------------------------- 平板系统返回键

    @Test
    fun desktopShellHandlesTheSystemBackKey() {
        val text = src(desktopShell)
        // 返回键必须挂上，且只在自己的状态上启用（让位给更深一层的条件由
        // exactlyOneLayerConsumesASingleBackPress 单独钉，这里只管"有没有接"）。
        assertTrue(text.contains("BackHandler("), "宽屏壳没有接系统返回键 —— 平板上从聊天按返回会直接退出应用")
        assertTrue(
            text.contains("enabled = (selectedAgentSessionId != null || selectedPeerId != null)"),
            "宽屏壳的返回键不是按自己的选中态启用（那样会和 App.kt 那层重叠）",
        )
        assertTrue(text.contains("selectedPeerId = null"), "按返回要能清掉选中项（回列表）")
    }

    // ---------------------------------------------------------------- 横幅播报 / 窄窗搜索

    @Test
    fun theBannerIsAnnouncedToScreenReaders() {
        val text = src(common)
        assertTrue(text.contains("liveRegion"), "横幅没有 liveRegion：读屏用户听不到任何失败反馈")
        assertTrue(text.contains("LiveRegionMode.Assertive"), "错误档应当是 Assertive（其余 Polite）")
    }

    @Test
    fun theDesktopListShowsDraftPreviews() {
        // 桌面壳必须把**同一份**草稿表喂给会话列表：手机上有「草稿：…」而桌面没有，
        // 是同一应用两种行为（AGENTS §2.6）。空白草稿按"没有"处理 —— 否则列表上会
        // 出现一个只剩空格的「草稿：」。
        val text = src(desktopShell)
        assertTrue(
            text.contains("draftPreviewOf = { convId -> draftsByConv[convId]?.takeIf { it.isNotBlank() } }"),
            "桌面会话列表没接草稿预览：手机上能看出这行有草稿，桌面上看不出",
        )
    }

    @Test
    fun theNarrowWindowHasASearchEntry() {
        val text = src(desktopShell)
        assertTrue(
            text.contains("CompactSearchRow(query = query"),
            "窄窗（Compact）没有搜索入口 —— 旧 query 会静默过滤列表，用户没地方改",
        )
    }

    @Test
    fun theLandscapeRailCanScroll() {
        val scaffold = "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/components/CompactScaffold.kt"
        assertTrue(
            src(scaffold).contains("verticalScroll(rememberScrollState())"),
            "横屏竖导不可滚动：系统大字号下最底下的页签会被挤出屏幕且点不到",
        )
    }

    // ---------------------------------------------------------------- 一次返回只有一个消费方

    /**
     * 平板形态下有**两层**都能处理返回键：App.kt 的 BackKeyHandler 与宽屏壳自己的
     * BackHandler。两层同时 enabled 时事件交给后注册的那一个（宽屏壳），而 App 那层
     * 会一直亮着 —— 用户于是遇到「第一次返回像是生效了、再按一次才退出」。
     *
     * 分工：nav.* 是**手机壳**的状态（两栏分支既不读也不渲染它），所以 App 那几条导航
     * 返回键在平板必须保持 && !twoPane；宽屏壳只消费它自己的状态，并把更深的同事文件柜
     * （controller 状态、两壳共享）让给 App 那条。
     */
    @Test
    fun exactlyOneLayerConsumesASingleBackPress() {
        val text = src(desktopShell)
        assertTrue(
            text.contains("state.cabinet.activePeerId == null"),
            "宽屏壳的返回键没有让位给「同事文件柜」：文件柜开着时两层会各消费一次",
        )
        // 宽屏壳只写自己的状态：一旦它开始碰 nav / controller，就和 App.kt 那层重叠了。
        val body = GuardSourceScan.bracedBlockAfter(text, "state.cabinet.activePeerId == null,")
        assertFalse(body.contains("nav."), "宽屏壳的返回键改到了 App 的 nav 状态：同一次返回会被消费两次")
        assertFalse(body.contains("controller."), "宽屏壳的返回键改到了 controller 状态（那一条归 App.kt）")
    }

    // ---------------------------------------------------------------- 48dp 命中区下限

    @Test
    fun theShellEscapeClosesTheSearchPanel() {
        // 桌面 Esc 的收口点：M3 Dialog/DropdownMenu 各自处理 Esc，壳级自绘的搜索面板
        // 不会 —— 壳的 onPreviewKeyEvent 必须在 shortcut 判读之前吃掉 Esc（面板开着时）。
        // 反向验证：删掉 DesktopShell 里的 Esc 分支，这条立刻红。
        val text = src(desktopShell)
        assertTrue(
            text.contains("Key.Escape && searching") ||
                (text.contains("Key.Escape") && text.contains("DesktopModalLayer.Searching")),
            "壳级 Esc 没有关搜索面板：自绘浮层缺统一键盘出口",
        )
    }

    @Test
    fun clickingTheActiveConversationCollapsesIt() {
        // 宽窗侧栏「再点当前会话 = 收起」（对齐上游 #59）：再点一次回到欢迎页，
        // 草稿留在壳的 draftsByConv 里。反向验证：删掉 DesktopShell onSelect 里的
        // 相等分支，这条立刻红。
        val text = src(desktopShell)
        assertTrue(
            text.contains("if (id == selectedPeerId) {"),
            "宽窗侧栏再点当前会话没有收起：onSelect 缺相等分支",
        )
    }

    @Test
    fun cabinetPrimaryTouchTargetsKeepTheInteractiveFloor() {
        // 文件柜的权限分段（手机上主要的权限入口）与路径面包屑（目录导航唯一入口）
        // 自身 padding 只撑到 ~26dp/~22dp，拇指常点空 —— 同屏的动作小钮都补了
        // minimumInteractiveComponentSize，这两处漏网。反向验证：删掉对应
        // .minimumInteractiveComponentSize()，这条立刻红。
        val screen = src("composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/cabinet/CabinetScreen.kt")
        assertTrue(
            Regex("minimumInteractiveComponentSize[\\s\\S]{0,250}clickable \\{ onSetShareMode\\(m\\) \\}").containsMatchIn(screen),
            "权限分段控件缺 48dp 命中区（minimumInteractiveComponentSize）",
        )
        val peer = src("composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/cabinet/PeerCabinetView.kt")
        assertTrue(
            Regex("minimumInteractiveComponentSize[\\s\\S]{0,120}clickable\\(enabled = enabled, onClick = onClick\\)").containsMatchIn(peer),
            "路径面包屑缺 48dp 命中区（minimumInteractiveComponentSize）",
        )
    }

    @Test
    fun forwardTargetDialogSupportsSearchAndEmptyPrompt() {
        // 转发与文件拖放目标选择器支持实时搜索过滤与空态提示（对齐上游 #66）。
        // 会话数量较多时出现搜索框，过滤无结果时给出反馈。
        // 反向验证：删掉 ForwardTargetDialog 内的 query 或 OutlinedTextField，这条立刻红。
        val text = src(common)
        val dialog = GuardSourceScan.bracedBlockAfter(text, "fun ForwardTargetDialog(")
        assertTrue(
            dialog.contains("var query by remember") && dialog.contains("OutlinedTextField("),
            "ForwardTargetDialog 缺实时搜索过滤输入框",
        )
        assertTrue(
            dialog.contains("filtered.isEmpty()") && dialog.contains("hist_search_empty"),
            "ForwardTargetDialog 缺无搜索匹配时的空态提示",
        )
    }
}
