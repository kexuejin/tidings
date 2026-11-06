// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.guards

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/** 4.5 守卫 B：生产 UI 不再持有整状态参数（切片必须显式传入）。 */
class SliceParamsRequiredGuardTest {

    private val roots = listOf(
        "composeApp/src/commonMain/kotlin",
        "desktopApp/src/main/kotlin",
    )

    @Test
    fun productionUiHasNoWholeStateParameter() {
        val offenders = mutableListOf<String>()
        for (path in GuardSourceScan.kotlinFiles(*roots.toTypedArray())) {
            val live = GuardSourceScan.stripComments(GuardSourceScan.read(path))
            if (live.contains("state: RelayUiState")) offenders += path + " -> state: RelayUiState"
            if (live.contains("state?.to") ) offenders += path + " -> state?.toXSlice"
        }
        assertTrue(offenders.isEmpty(), "生产 UI 不许再有整状态参数或默认派生：" + offenders.joinToString(" | "))
    }
}
