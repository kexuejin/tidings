// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.guards

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/** 4.5 守卫 D：toXSlice() 只许在测试/预览里出现（生产壳不得借它把整状态喂回去）。 */
class ToXSliceOnlyInTestsGuardTest {

    private val roots = listOf(
        "composeApp/src/commonMain/kotlin",
        "desktopApp/src/main/kotlin",
    )

    @Test
    fun productionUiDoesNotProjectTheWholeState() {
        val offenders = mutableListOf<String>()
        for (path in GuardSourceScan.kotlinFiles(*roots.toTypedArray())) {
            val live = GuardSourceScan.stripComments(GuardSourceScan.read(path))
            for (fn in listOf("toChatSlice(", "toAgentSlice(", "toNodeSlice(", "toSettingsSlice(", "toChromeSlice(", "toTransferSlice(", "toAvatarSlice(")) {
                if (live.contains(fn)) offenders += path + " -> " + fn
            }
        }
        assertTrue(offenders.isEmpty(), "生产 UI 出现整状态投影（toXSlice 只许测试/预览使用）：" + offenders.joinToString(" | "))
    }
}
