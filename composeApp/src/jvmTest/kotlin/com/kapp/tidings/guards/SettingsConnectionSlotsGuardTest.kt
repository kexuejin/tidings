// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.guards

import com.kapp.tidings.testsupport.GuardSourceScan

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 守卫 P0（连接页「网段探测」整卡死按钮）：连接页的三个 CIDR 回调必须是**可空槽**，
 * null 时整张卡不画；桌面宿主必须把控制器的真实方法接上。
 *
 * 现场旧态（本测试要拦住的）：`onAddScanRange: (String) -> Unit = {}` 等三个空 lambda
 * 默认值 + 桌面宿主不传 ⇒ 桌面点「添加网段」输入框被清空、看似成功实际什么都没发生。
 *
 * 反向验证：把三个默认值放回 `= {}`（或删掉 DesktopHosting 的接线），下面的测试立刻变红。
 *
 * 为什么是源码级守卫：与 [DeadButtonGuardTest] 同一取舍 —— 本仓没有 Compose UI 测试基建，
 * 「宿主没接回调时这颗按钮画不画」只能在源码文本上钉住。
 */
class SettingsConnectionSlotsGuardTest {

    private val conn = "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/settings/SettingsConnectionSubpage.kt"
    private val screen = "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/settings/SettingsScreen.kt"
    private val desktopHosting = "desktopApp/src/main/kotlin/com/kapp/tidings/desktop/DesktopHosting.kt"

    @Test
    fun cidrCallbacksAreNullableSlotsNotEmptyLambdas() {
        val text = GuardSourceScan.stripComments(GuardSourceScan.read(conn))
        // 可空槽：null = 本平台没有这条出口。
        assertTrue(
            text.contains("onAddScanRange: ((String) -> Unit)? = null"),
            "onAddScanRange 必须是可空槽（null = 不画），不能是空 lambda 默认值。",
        )
        assertTrue(
            text.contains("onRemoveScanRange: ((String) -> Unit)? = null"),
            "onRemoveScanRange 必须是可空槽。",
        )
        assertTrue(
            text.contains("onTriggerCidrScan: ((String?) -> Unit)? = null"),
            "onTriggerCidrScan 必须是可空槽。",
        )
        // 旧形态：空 lambda 默认值 —— 一颗按下去没反应的按钮。
        assertFalse(
            text.contains("onAddScanRange: (String) -> Unit = {}"),
            "连接页又出现了空 lambda 默认值的 CIDR 回调 —— 它会在没接回调的宿主上画一排死按钮。",
        )
        assertFalse(
            text.contains("onTriggerCidrScan: (String?) -> Unit = {}"),
            "连接页又出现了空 lambda 默认值的 CIDR 回调。",
        )
    }

    @Test
    fun cidrCardIsGatedOnAllThreeCallbacksBeingWired() {
        val text = GuardSourceScan.stripComments(GuardSourceScan.read(conn))
        val gated = GuardSourceScan.bracedBlockAfter(
            text,
            "if (addRange != null && removeRange != null && triggerScan != null)",
        )
        assertTrue(
            gated.contains("cidr_section_title"),
            "CIDR 卡必须整体包在「三个回调都已接上」的判据里：null = 整张卡不画，" +
                "而不是画出来让它按下去没反应。",
        )
        assertTrue(
            gated.contains("addRange(target)"),
            "「添加网段」按钮必须调用宿主递进来的真实动作。",
        )
        assertTrue(
            gated.contains("removeRange(entry.cidr)") && gated.contains("triggerScan("),
            "「删除 / 立即探测」必须走接好的回调，不是空实现。",
        )
    }

    @Test
    fun mobileShellForwardsTheSlotsUnchanged() {
        val text = GuardSourceScan.stripComments(GuardSourceScan.read(screen))
        assertTrue(
            text.contains("onAddScanRange: ((String) -> Unit)? = null"),
            "SettingsScreen 的转发参数也必须是可空槽（它把空 lambda 默认值顶到连接页就是死按钮）。",
        )
        val connCall = GuardSourceScan.parenBlockAfter(text, "SettingsConnectionSubpage(")
        assertTrue(
            connCall.contains("onAddScanRange = onAddScanRange") &&
                connCall.contains("onTriggerCidrScan = onTriggerCidrScan"),
            "SettingsScreen 必须把三个槽原样转发给连接子页。",
        )
    }

    @Test
    fun desktopHostWiresTheRealControllerMethods() {
        val text = GuardSourceScan.read(desktopHosting)
        val connCall = GuardSourceScan.parenBlockAfter(text, "SettingsConnectionSubpage(")
        assertTrue(
            connCall.contains("onAddScanRange = controller::addScanRange"),
            "桌面连接页必须接上 controller::addScanRange —— 不传的话桌面上那排按钮又按下去没反应。",
        )
        assertTrue(
            connCall.contains("onRemoveScanRange = controller::removeScanRange"),
            "桌面连接页必须接上 controller::removeScanRange。",
        )
        assertTrue(
            connCall.contains("onTriggerCidrScan = controller::triggerCidrScan"),
            "桌面连接页必须接上 controller::triggerCidrScan。",
        )
    }
}
