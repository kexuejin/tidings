// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.guards

import com.kapp.tidings.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 卡片动作真通路的**接线守卫**（M4 任务 5）。
 *
 * M3 交付时两个生产壳都传 null（没有通路 ⇒ 按钮组整块不画）。M4 接通之后，「两壳同源」
 * 这件事类型系统表达不了 —— 任何一壳把接线改回 null（或另起一套直连通道），编译照样绿，
 * 但那一壳的审批按钮就又变成「按下去没反应的按钮」（DeadButtonGuard 同一条），
 * 或「绕开宿主原子台账的野通路」。
 *
 * 所以按源码文本钉住**调用点**：两个壳的 ChatScreen 装配处必须出现 `onCardAction =`
 * 且都指向同一个域层入口 `controller.handleCardAction(`。剥掉注释再匹配
 * （[GuardSourceScan] 的纪律），避免被解释这件事的注释绊倒。
 *
 * 住在 `:composeApp` 的 guards 包（不在 `:app`）：这条守卫要读 UI 侧源码，
 * 而「下层测试不许反向读上层源码」是 GuardsLiveInTheUpperLayerTest 钉死的分层。
 */
class CardActionWiringGuardTest {

    private val appShell = "composeApp/src/commonMain/kotlin/com/kapp/tidings/App.kt"
    private val desktopShell =
        "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopChatPane.kt"

    private fun live(path: String): String = GuardSourceScan.stripComments(GuardSourceScan.read(path))

    @Test
    fun bothShellsWireCardActionsToTheSameDomainEntry() {
        for (path in listOf(appShell, desktopShell)) {
            val source = live(path)
            assertTrue(
                source.contains("onCardAction = {"),
                "生产壳必须接卡片动作通路（$path）：传 null = 按钮组整块不画，审批在那一端死路",
            )
            assertTrue(
                source.contains("controller.handleCardAction("),
                "卡片动作必须走同一个域层入口 handleCardAction（$path）：绕开它就是绕开宿主的原子台账与全群广播",
            )
        }
    }

    /** 桌面 pane 拿的是 DesktopPaneActions 接口 —— 它必须有这一员，否则桌面壳接不上。 */
    @Test
    fun paneActionsInterfaceExposesCardAction() {
        val source = live("app/src/commonMain/kotlin/com/kapp/tidings/domain/DesktopPaneActions.kt")
        assertTrue(
            source.contains("fun handleCardAction(groupId: String, cardId: String, cardRev: Int, actionId: String)"),
            "DesktopPaneActions 必须暴露 handleCardAction（原语参数）：缺了它桌面壳没有可接的成员",
        )
    }
}
