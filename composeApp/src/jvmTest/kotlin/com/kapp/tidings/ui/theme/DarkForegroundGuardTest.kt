// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui.theme

import com.kapp.tidings.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D8（WP-D6④）的源码级反向通道：`Color.White` 曾在暗色侧造成 2.04:1 的白钮/白勾
 * （AppSwitch 旋钮、PeerCabinetView 勾与头像首字），修法是 onPrimary / foregroundOn。
 * 本守卫钉住「这两处不再写死白」——渲染证据由暗色帧承担（V6 批），接线由这里守。
 */
class DarkForegroundGuardTest {

    private fun live(path: String): String = GuardSourceScan.stripComments(GuardSourceScan.read(path))

    /** 回退方式：把 \`Color.White\` 放回旋钮 ⇒ 本条红。 */
    @Test
    fun switchKnobUsesOnPrimary() {
        val sw = live("composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/components/AppSwitch.kt")
        assertTrue(sw.contains("onPrimary"), "AppSwitch 旋钮没有走 onPrimary —— D8 的暗色白钮回来了")
        assertFalse(
            sw.contains("Color.White"),
            "AppSwitch 仍引用 Color.White —— 它在亮色凑巧对、在暗色 2.04:1（D8 病灶）",
        )
    }

    /** 回退方式：把 \`tint = Color.White\` 放回勾选、首字前景放回写死白 ⇒ 本条红。 */
    @Test
    fun cabinetCheckAndAvatarForegroundAreThemeAware() {
        val pc = live("composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/cabinet/PeerCabinetView.kt")
        assertTrue(
            pc.contains("tint = MaterialTheme.colorScheme.onPrimary"),
            "文件柜勾选没有走 onPrimary（GroupCreateDialog 同动作早已是对的 —— 两份实现必须一份）",
        )
        assertTrue(
            pc.contains("AvatarPalette.foregroundOn"),
            "头像首字前景没有过亮度判断 —— 杏黄底白字 2.95:1（D8 附带）",
        )
        assertFalse(
            pc.contains("Color.White"),
            "PeerCabinetView 仍引用 Color.White —— 两处 D8 病灶的残留",
        )
    }
}
