// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui

import com.kapp.tidings.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T27 收尾批次的源码守卫：版本号注入 / 共享开关 / 按钮触达 / iOS 两处。
 *
 * 这几条都是"结构必须只有一处 / 必须真的接上"类纪律，类型系统表达不了，
 * 本机也没有 iOS 工具链与真机可跑 —— 与仓库既有守卫同一取舍（见 GuardSourceScan 的 KDoc）。
 */
class ShellChromeReuseGuardTest {

    private fun src(path: String): String = GuardSourceScan.stripComments(GuardSourceScan.read(path))

    private val contract = "app/src/commonMain/kotlin/com/kapp/tidings/platform/PlatformContracts.kt"
    private val iosPlatform = "composeApp/src/iosMain/kotlin/com/kapp/tidings/platform/IosPlatform.kt"
    private val iosBars = "composeApp/src/iosMain/kotlin/com/kapp/tidings/platform/CareSystemBars.ios.kt"
    private val desktopSettings = "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopSettingsChrome.kt"
    private val buttons = "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/components/AppButtons.kt"
    private val switch = "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/components/AppSwitch.kt"

    // ---------------------------------------------------------------- 版本号注入

    @Test
    fun thePlatformContractCarriesTheRealBuildVersion() {
        val text = src(contract)
        assertTrue(
            text.contains("val appVersion: String?"),
            "AppPlatform 没有 appVersion —— 共享层只能继续用常量冒充构建版本",
        )
        // 兜底必须是不存在的 null：任何"默认版本号"都会让漏接的壳永远显示那个值。
        assertTrue(
            text.contains("get() = null"),
            "appVersion 有兜底默认值：漏接的壳会一直显示编出来的版本号，而没人会发现",
        )
    }

    @Test
    fun theIosShellReadsItsBundleVersionInsteadOfGuessing() {
        val text = src(iosPlatform)
        assertTrue(text.contains("CFBundleShortVersionString"), "iOS 壳没从 Info.plist 读构建版本")
        assertTrue(text.contains("override val appVersion"), "iOS 壳没有覆盖 appVersion")
    }

    // ---------------------------------------------------------------- iOS 深色状态栏

    @Test
    fun theIosWindowFollowsTheInAppTheme() {
        val text = src(iosBars)
        assertTrue(
            text.contains("overrideUserInterfaceStyle"),
            "iOS 状态栏仍是 no-op：系统浅色 + 应用内深色时图标看不清",
        )
        assertTrue(text.contains("UIUserInterfaceStyleDark"), "深色分支缺失")
        // "注释许诺了一件不存在的事" 是这个缺陷的上一形态：守卫同时要求它真的写入窗口。
        assertTrue(text.contains("SideEffect"), "窗口外观刷新的时机必须是每次提交后（SideEffect）")
    }

    // ---------------------------------------------------------------- 开关只有一份

    @Test
    fun thereIsExactlyOneSwitchImplementation() {
        val switchText = src(switch)
        assertTrue(switchText.contains("fun AppSwitch("), "共享开关 AppSwitch 不存在")
        // 不许暴露样式自由度（AGENTS §2.6 第四步）：参数只接原语。
        assertFalse(
            switchText.contains("style:") || switchText.contains("contentPadding"),
            "AppSwitch 暴露了样式参数 —— 调用点会把两侧差异原样长回来",
        )
        val desktop = src(desktopSettings)
        assertFalse(
            desktop.contains("private fun RelaySwitch("),
            "桌面又长出了自己的开关实现：同一个开关两个壳两个形状",
        )
        assertTrue(desktop.contains("AppSwitch("), "桌面设置页没有换用共享开关")
    }

    // ---------------------------------------------------------------- 按钮触达

    @Test
    fun everyAppButtonEnforcesThe48dpTouchTarget() {
        val text = src(buttons)
        // 三颗文字/描边按钮各一处：小档视觉 26–28dp，命中区不得低于 48dp。
        assertEquals(
            3,
            Regex("modifier\\s*=\\s*modifier\\.minimumInteractiveComponentSize\\(\\)").findAll(text).count(),
            "三颗按钮里有的没钉住 48dp 触控下限（视觉可以小，命中区不行）",
        )
    }

    // ---------------------------------------------------------------- 去系统设置

    /**
     * 「去系统设置」只在**真有系统设置页**的两端实现；桌面保持契约默认 false。
     *
     * 返回 true 的语义是"这颗按钮按下去会有反应"：桌面没有应用权限页，返回 true 只会
     * 让界面画出一颗点了没反应的按钮（比没有按钮更糟）。
     */
    @Test
    fun theAppSettingsShortcutIsImplementedOnlyWhereASettingsPageExists() {
        val contractText = src(contract)
        assertTrue(
            contractText.contains("fun openAppSettings(): Boolean = false"),
            "契约默认值不再是 false —— 桌面会画出一颗点了没反应的「去系统设置」",
        )
        val android = src("composeApp/src/androidMain/kotlin/com/kapp/tidings/platform/AndroidPlatform.kt")
        assertTrue(android.contains("override fun openAppSettings(): Boolean"), "Android 没实现 openAppSettings")
        assertTrue(
            android.contains("ACTION_APPLICATION_DETAILS_SETTINGS"),
            "Android 没有走应用详情页",
        )
        assertTrue(android.contains("\"package:\""), "Android 的 intent 没有带 package: 自身")
        val ios = src(iosPlatform)
        assertTrue(ios.contains("override fun openAppSettings(): Boolean"), "iOS 没实现 openAppSettings")
        assertTrue(ios.contains("UIApplicationOpenSettingsURLString"), "iOS 没走系统设置 URL")
    }
}