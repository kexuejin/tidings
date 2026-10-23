// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.settings

import com.kapp.tidings.testsupport.GuardSourceScan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 守卫设置首页「能力声明」那一行说的是**真话**。
 *
 * 它以前写死 `tw1 · mrec1`：用户在能力声明子页把两个开关关掉后，首页仍宣称开着。
 * 这种假话不会报错、不会崩，只有源码级断言能钉住（本仓没有 Compose UI 测试基建）。
 *
 * 反向验证：把 `valText = capsText` 改回 `valText = "tw1 · mrec1"`，
 * [theHomeRowNoLongerHardCodesBothCapabilitiesOn] 立刻变红。
 */
class SettingsHomeCapabilitiesTest {

    @Test
    fun declaredCapsFollowTheRealSwitches() {
        assertEquals(emptyList<String>(), declaredCapabilityCodes(false, false), "两个都关掉时必须一个都不声明")
        assertEquals(listOf("tw1"), declaredCapabilityCodes(true, false))
        assertEquals(listOf("mrec1"), declaredCapabilityCodes(false, true))
        assertEquals(listOf("tw1", "mrec1"), declaredCapabilityCodes(true, true))
    }

    @Test
    fun theHomeRowNoLongerHardCodesBothCapabilitiesOn() {
        val live = GuardSourceScan.stripComments(GuardSourceScan.read(SETTINGS_HOME))
        assertFalse(
            live.contains("tw1 · mrec1"),
            "首页能力声明行又写死了 tw1 · mrec1 —— 关掉开关后它会继续宣称开着（界面在说假话）",
        )
        assertTrue(
            live.contains("declaredCapabilityCodes("),
            "首页必须走 declaredCapabilityCodes(...) 读真实开关",
        )
        assertTrue(live.contains("waitFrameCapability"), "tw1 必须读 waitFrameCapability 开关")
        assertTrue(live.contains("recordMessageCapability"), "mrec1 必须读 recordMessageCapability 开关")
    }

    private companion object {
        const val SETTINGS_HOME =
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/settings/SettingsHomeScreen.kt"
    }
}
