// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.guards

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * EXT-8 源码守卫：「打开共享目录」画不画的判据只能有一份。
 *
 * 移动端 `CabinetScreen` 与桌面端 `CabinetPane` 共用 `MineCabinetView`，两端只透传
 * 宿主动作；判据收敛到 `CabinetAvailability.shouldShowShareRoot`，任何一端都不许
 * 再自己写 `onOpenShareRoot == null` 之类的裸比较。过去靠 KDoc 一句「同一个判据」
 * 人工同步，是会漂移的那种约定，所以钉在源码层。
 *
 * 与 `AgentApprovalReviewGuardsTest` 同一取舍：先剥注释再匹配，并在内存里删掉
 * 接线反向自检，保证守卫能区分对错（不是恒真）。
 */
class CabinetAvailabilitySingleSourceTest {

    private val cabinetScreen =
        "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/cabinet/CabinetScreen.kt"
    private val cabinetPane =
        "composeApp/src/commonMain/kotlin/com/kapp/tenon/ui/desktop/CabinetPane.kt"
    private val availability =
        "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/cabinet/CabinetAvailability.kt"

    private val bothEnds = listOf(cabinetScreen, cabinetPane)
    private val call = "shouldShowShareRoot("

    @Test
    fun bothEndsUseTheSharedPredicateInsteadOfAHandWrittenComparison() {
        for (path in bothEnds) {
            val text = GuardSourceScan.stripComments(GuardSourceScan.read(path))
            assertTrue(
                call in text,
                path + " 必须调用共享判据 shouldShowShareRoot —— 两端不许各自判能力缺失。",
            )
            assertFalse(
                "onOpenShareRoot == null" in text || "onOpenShareRoot != null" in text,
                path + " 又自己写了一份 onOpenShareRoot 的裸判据 —— 判据只能有一处。",
            )
        }
    }

    @Test
    fun predicateIsDefinedOnce() {
        val text = GuardSourceScan.stripComments(GuardSourceScan.read(availability))
        assertTrue(
            text.contains("internal fun shouldShowShareRoot(onOpenShareRoot: (() -> Unit)?): Boolean"),
            "CabinetAvailability 必须给出唯一一条共享判据（可空动作 → Boolean）。",
        )
        assertTrue(
            text.contains("onOpenShareRoot != null"),
            "判据语义必须与「能力缺失 = 不画」一致：只有非空动作才画。",
        )
    }

    /** 守卫必须能区分对错：内存里删掉任一端的调用，都要各报一次。 */
    @Test
    fun guardDetectsRemovedWiring() {
        for (path in bothEnds) {
            val missing = bothEnds.filter { end ->
                val raw = GuardSourceScan.stripComments(GuardSourceScan.read(end))
                val text = if (end == path) raw.replace(call, "") else raw
                call !in text
            }
            assertEquals(
                listOf(path),
                missing,
                "删掉 " + path + " 的共享判据调用后守卫却没报 —— 这条守卫守不住任何东西。",
            )
        }
    }
}
