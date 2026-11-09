// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.guards

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 团队编制的 UI 侧接线守卫（住在 :composeApp —— 它要读 UI 侧源码，
 * 「跨层守卫住在上层」的既定规矩，见 GuardsLiveInTheUpperLayerTest）。
 *
 * 两条纪律的类型系统都表达不了，只能在源码层钉：
 *
 * 1. **对话框默认席位与显示/写入同判据**：GroupInfoDialog 给 TeamRosterDialog 的
 *    默认选中席位必须由 core 的 `representativeSeat` 算出 —— 多席位时
 *    「行显示名、撤回按钮、对话框默认选中」三处不得各取各的（方案 §3.5）。
 * 2. **两端同挂**（AGENTS §2.6 / 方案 §7.3：桌面不特殊化）：手机壳、桌面壳、
 *    手机壳的装配点都必须接团队编制的保存通路。过去桌面壳漏接共享能力的回调，
    手机能用、桌面点了没反应 —— 源码层钉住三个壳。
 */
class TeamRosterWiringGuardTest {

    @Test
    fun theDialogsDefaultSeatComesFromTheRepresentativeSeatCriterion() {
        val groupInfo = GuardSourceScan.read(
            "composeApp/src/commonMain/kotlin/com/kapp/tenon/ui/components/GroupInfoDialog.kt",
        )
        val live = GuardSourceScan.stripComments(groupInfo)
        assertTrue(
            live.contains("representativeSeat("),
            "团队编制对话框的默认选中席位必须由 GroupInfoDialog 用 representativeSeat 算出 " +
                "—— 它是「显示与写入共用同一判据」的第三处调用点（方案 §3.5）",
        )
    }

    @Test
    fun teamRosterWiringIsPresentOnBothShells() {
        val shells = listOf(
            "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/chat/ChatDialogs.kt" to "onSetTeamRoster",
            "composeApp/src/commonMain/kotlin/com/kapp/tenon/ui/desktop/DesktopChatPane.kt" to "setGroupTeamDeclaration",
            "composeApp/src/commonMain/kotlin/com/kapp/tenon/App.kt" to "onSetTeamRoster",
        )
        for ((path, marker) in shells) {
            val live = GuardSourceScan.stripComments(GuardSourceScan.read(path))
            assertTrue(
                live.contains(marker),
                "$path 缺少团队编制接线（$marker）—— 桌面与手机必须共用同一份入口，" +
                    "漏一端就是「手机能编队、桌面点了没反应」的分叉",
            )
        }
    }
}
