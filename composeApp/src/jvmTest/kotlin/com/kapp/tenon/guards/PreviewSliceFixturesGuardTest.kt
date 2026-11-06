// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.guards

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/** 4.5 守卫 C：androidApp 预览只许用切片夹具构造，不许再传整状态。 */
class PreviewSliceFixturesGuardTest {

    private val previews get() = GuardSourceScan.kotlinFiles("androidApp/src/screenshotTest/kotlin")

    @Test
    fun previewsDoNotPassWholeStateToContentScreens() {
        val offenders = mutableListOf<String>()
        for (path in previews) {
            val live = GuardSourceScan.stripComments(GuardSourceScan.read(path))
            if (live.contains("ChatScreenState(") && live.contains("state =")) {
                // ChatScreenState 内部不许再出现 state = 实参（切片必须显式）
                var idx = live.indexOf("ChatScreenState(")
                while (idx >= 0) {
                    val close = GuardSourceScan.parenBlockAfter(live, "ChatScreenState(")
                    if (close.contains("state =")) offenders += path + " -> ChatScreenState(state=)"
                    idx = live.indexOf("ChatScreenState(", idx + 1)
                }
            }
        }
        assertTrue(offenders.isEmpty(), "预览仍在向 ChatScreenState 传整状态：" + offenders.joinToString(" | "))
    }

    @Test
    fun desktopShellPreviewsCarrySliceParameters() {
        val offenders = mutableListOf<String>()
        for (path in previews) {
            val live = GuardSourceScan.stripComments(GuardSourceScan.read(path))
            var idx = live.indexOf("DesktopShell(")
            while (idx >= 0) {
                val block = GuardSourceScan.parenBlockAfter(live, "DesktopShell(", idx)
                if (block.contains("state =")) offenders += path + " -> DesktopShell(state=)"
                idx = live.indexOf("DesktopShell(", idx + 1)
            }
        }
        assertTrue(offenders.isEmpty(), "DesktopShell 预览仍在传整状态：" + offenders.joinToString(" | "))
    }
}
