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
 * 四条纪律类型系统表达不了（与 `TeamProfileWiringGuardTest` 同族）：
 *
 * 1. **桌面壳必须接**：档案只有宿主写得了。漏接 `onForgetEmployee` 的症状是
 *    「档案管理页上那颗垃圾桶不在」或「点了没反应」—— 编译过、测试绿，用户只是删不掉。
 *    而 `Forget` 命令本身早就有（`EmployeeProfileCommand.Forget`），缺的从来是这颗按钮。
 * 2. **手机壳不许接**：手机连的是别人的机器，本机设置里没有那份档案；接上等于给一颗
 *    「点了没反应」的删除键 —— 而删除键没反应，用户会以为人已经被注销了。
 * 3. **编辑入口两端都只通过档案面**：档案页的行点击必须落在同一个对话框上（不做第二份表单）。
 * 4. **建档入口的可见性来自写通路**：`onCreateEmployee != null` 不是常量，它由
 *    「壳接没接 mint 写通路」推出（`onCreateEmployeeMint = controller.employeeMintWriter`
 *    → `mintSubmit?.let { … }`）。写成恒 true 会给一颗提交不上去的按钮。
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
        // 建档的入口（MINT `Create`）同理：档案页的「新建档案」只接一半（选人）不算接上，
        // 落盘仍要走 mint 写通路。「有实现有测试」≠「有入口」。
        // 这条写通路不接 ⇒ `mintSubmit` 为 null ⇒ `onCreateEmployee` 为 null ⇒
        // 「新建档案」这颗按钮根本不画，于是「一个员工都没有」时连第一个都建不出来。
        assertTrue(
            "onCreateEmployeeMint = controller.employeeMintWriter" in shell,
            "桌面壳必须把 mint 写通路接到 controller.employeeMintWriter —— " +
                "不接这颗「新建档案」按钮就不画，第一个员工永远建不出来",
        )
    }

    @Test
    fun theProfileSectionOnlyOpensTheLedgerEntryWhenTheShellCanWrite() {
        // 登记簿小节（档案档并入员工视图后）的可见性判据：有孤儿档案 **或** 能建档。
        // 写成只看 `isNotEmpty()` 的话，「零孤儿档案」时小节永远没有入口（新建按钮
        // 就在小节里）；写成恒 true，手机端（读不到档案、也没有写通路）会多出一段
        // 永远为空的登记簿。判据本体 = 有孤儿 **或** 能建档（两半都要）——
        // 与员工卡对得上号的档案不在这一段里（它们活在员工卡上）。
        val cards = live("composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/agents/AgentTeamCards.kt")
        assertTrue(
            "orphanProfiles.isNotEmpty() || onCreateEmployee != null" in cards,
            "登记簿小节的可见性必须 = 有孤儿档案 或 能建档（两半都要）",
        )
        // 「能建档」这一半不是外部随手传的常量：它由控制台从壳接进来的 mint 写通路推出。
        // 少了这一条，卡片那边 `onCreateEmployee != null` 依然成立（有人传了常量），
        // 按钮画出来却提交不上去 —— 就是那颗「点了没反应」的按钮。
        val console = live(
            "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/agents/AgentConsoleScreen.kt",
        )
        assertTrue(
            "onCreateEmployee = mintSubmit?.let" in console,
            "「新建档案」的入口必须由 mint 写通路推出（mintSubmit?.let），" +
                "不许写死成恒非 null 的常量",
        )
        // 档案档已撤销（员工/档案对齐）：三档并列的切换器不再有「档案」格 ——
        // 回归钉子，防止有人把第三档加回来（登记簿已是员工视图的小节）。
        assertTrue(
            "AgentDirectoryView.PROFILES" !in cards,
            "「档案」不再作为平级档位存在：孤儿档案在员工视图底部的登记簿小节里",
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
            // `onCreateEmployee` 是 `onCreateEmployeeMint` 的前缀：一条断言同时挡住
            // 「接建档入口」与「接 mint 写通路」两种接法。
            "onCreateEmployee" !in app,
            "手机壳不许接「新建档案」（含 mint 写通路）：候选表来自宿主本机台账，" +
                "手机上选了也落不了盘",
        )
    }
}
