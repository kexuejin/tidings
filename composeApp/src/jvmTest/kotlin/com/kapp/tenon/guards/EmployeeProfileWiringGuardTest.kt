// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.guards

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 员工档案的**界面接线**守卫（住在 :composeApp —— 它读 UI 侧源码，见
 * `GuardsLiveInTheUpperLayerTest`）。
 *
 * ## 守的是什么
 *
 * 员工档案（`EmployeeProfile`）住**宿主本机**的 `RelaySettings`，不是协议对象。
 * 三条纪律类型系统表达不了（与 `TeamProfileWiringGuardTest` 同族）：
 *
 * 1. **桌面壳必须接**：档案只有宿主写得了。漏接 `onForgetEmployee` 的症状是
 *    「档案管理页上那颗垃圾桶不在」或「点了没反应」—— 编译过、测试绿，用户只是删不掉。
 *    而 `Forget` 命令本身早就有（`EmployeeProfileCommand.Forget`），缺的从来是这颗按钮。
 * 2. **手机壳不许接**：手机连的是别人的机器，本机设置里没有那份档案；接上等于给一颗
 *    「点了没反应」的删除键 —— 而删除键没反应，用户会以为人已经被注销了。
 * 3. **编辑入口两端都只通过档案面**：档案页的行点击必须落在同一个对话框上（不做第二份表单）。
 */
class EmployeeProfileWiringGuardTest {

    private fun live(path: String): String =
        GuardSourceScan.stripComments(GuardSourceScan.read(path))

    @Test
    fun theDesktopShellWiresTheEmployeeProfileWritePath() {
        val shell = live("composeApp/src/commonMain/kotlin/com/kapp/tenon/ui/desktop/DesktopShell.kt")
        assertTrue(
            "controller.saveEmployeeProfile(" in shell,
            "桌面壳必须把「登记 / 编辑员工」接到 controller.saveEmployeeProfile —— " +
                "档案只住宿主机，桌面壳不接就等于没有入口",
        )
        // 注销是同一个档案写通路的另一半：只接保存不接注销，档案建起来就删不掉
        // （`Forget` 一直在，缺的一直是这颗按钮；档案还未必出现在任何列表里）。
        assertTrue(
            "controller.forgetEmployeeProfile(" in shell,
            "桌面壳必须把「注销档案」接到 controller.forgetEmployeeProfile —— " +
                "漏了它，员工档案只能建、不能删",
        )
        // 建档的入口（`Create`）同理：档案页的「新建档案」只接一半（选人）不算接上，
        // 落盘仍要走 saveEmployeeProfile。「有实现有测试」≠「有入口」。
        assertTrue(
            "onNewEmployeeProfile = " in shell,
            "桌面壳必须接档案页的「新建档案」—— 不接这颗按钮就不画，" +
                "于是「一个档案都没有」时连第一个都建不出来",
        )
    }

    @Test
    fun theProfileSectionOnlyOpensTheLedgerEntryWhenTheShellCanWrite() {
        // 档案那一档的可见性判据：有档案 **或** 能建档。写成只看 `isNotEmpty()` 的话，
        // 「零档案」这一档永远没有入口（新建按钮就在那一档里）；写成恒 true，手机端
        // （读不到档案、也没有写通路）会多出一档永远为空的登记簿。
        val cards = live("composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/agents/AgentTeamCards.kt")
        assertTrue(
            "employeeProfiles.isNotEmpty() || onNewEmployeeProfile != null" in cards,
            "「档案」这一档的可见性必须 = 有档案 或 能建档（两半都要）",
        )
    }

    @Test
    fun thePhoneShellDoesNotPretendToOwnEmployeeProfiles() {
        val app = live("composeApp/src/commonMain/kotlin/com/kapp/tenon/App.kt")
        assertTrue(
            "onForgetEmployee" !in app,
            "手机壳不许接「注销档案」：档案只住宿主机，接了等于一颗点了没反应的删除键",
        )
        assertTrue(
            "onEditEmployeeProfile" !in app,
            "手机壳同样不许接档案页的编辑入口：那份档案不在手机上，点了也存不下",
        )
        assertTrue(
            "onNewEmployeeProfile" !in app,
            "手机壳不许接「新建档案」：候选表来自宿主本机台账，手机上选了也落不了盘",
        )
    }
}
