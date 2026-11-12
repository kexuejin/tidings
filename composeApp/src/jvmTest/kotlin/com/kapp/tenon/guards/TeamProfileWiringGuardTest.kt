// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.guards

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 团队档案的**界面接线**守卫（住在 :composeApp —— 它读 UI 侧源码，见
 * `GuardsLiveInTheUpperLayerTest`）。
 *
 * ## 守的是什么
 *
 * 团队档案（`TeamProfile`）住**领队宿主本机**的 `RelaySettings`：它是本机事实，
 * 不是协议对象。三条纪律类型系统表达不了：
 *
 * 1. **桌面壳必须接**：档案只有宿主写得了。桌面壳漏接 `onSaveTeam`，症状是
 *    「团队视角里没有新建入口」——编译过、测试绿，用户只是找不到。
 * 2. **手机壳不许接**：手机连的是别人的机器，本机设置里没有那份档案；接了等于给
 *    一颗「点了没反应」的按钮（`DeadButtonGuardTest` 的同族形态）。
 * 3. **群侧「添加团队」两端同挂**：手机与桌面都必须能对**自己**的团队档案发起
 *    `attachTeamToGroup` —— 漏一端就是「这端能整队加群、那端只能一个个加人」。
 */
class TeamProfileWiringGuardTest {

    @Test
    fun theDesktopShellWiresTheTeamProfileWritePath() {
        val live = GuardSourceScan.stripComments(
            GuardSourceScan.read("composeApp/src/commonMain/kotlin/com/kapp/tenon/ui/desktop/DesktopShell.kt"),
        )
        assertTrue(
            "controller.saveTeamProfile(" in live,
            "桌面壳必须把「新建 / 编辑团队」接到 controller.saveTeamProfile —— " +
                "团队档案只住宿主本机，桌面壳不接就等于没有入口",
        )
    }

    @Test
    fun thePhoneShellDoesNotPretendToOwnTeamProfiles() {
        val live = GuardSourceScan.stripComments(
            GuardSourceScan.read("composeApp/src/commonMain/kotlin/com/kapp/tenon/App.kt"),
        )
        assertTrue(
            "onSaveTeam" !in live,
            "手机壳不许接团队档案的保存通路：档案只住领队宿主本机，" +
                "手机接上就等于画一颗点了没反应的按钮",
        )
    }

    @Test
    fun bothShellsCanAddAWholeTeamToAGroup() {
        val shells = listOf(
            "composeApp/src/commonMain/kotlin/com/kapp/tenon/App.kt" to "attachTeamToGroup",
            "composeApp/src/commonMain/kotlin/com/kapp/tenon/ui/desktop/DesktopChatPane.kt" to "attachTeamToGroup",
        )
        for ((path, marker) in shells) {
            val live = GuardSourceScan.stripComments(GuardSourceScan.read(path))
            assertTrue(
                marker in live,
                "$path 缺少「添加团队」接线（$marker）—— 群里必须能一次加进一整支队，" +
                    "而不是把组员一个个重新加一遍",
            )
        }
    }
}
