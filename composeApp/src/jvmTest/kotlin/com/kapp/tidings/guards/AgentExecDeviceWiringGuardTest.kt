// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.guards

import com.kapp.tidings.testsupport.GuardSourceScan

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 「每个 agent 谁能执行」这条链路的**接线守卫**。
 *
 * ## 为什么需要源码级守卫
 *
 * 这个功能跨了四层：台账（desktopApp）→ 控制器桥（composeApp）→ 台面动作 → 详情页组件。
 * 任何一层漏接，表现都是**同一个**：界面画出来了、勾选框点了没反应。
 * 而那不是崩溃、没有日志、单测也测不到（本仓没有 Compose UI 测试基建）。
 *
 * 所以这里钉的是「这条链上每一段都在」，而不是渲染结果 ——
 * 与 `PairingEntryPointsGuardTest` / `SystemBarsInsetGuardTest` 同一取舍。
 */
class AgentExecDeviceWiringGuardTest {

    private fun read(path: String): String = GuardSourceScan.read(path)

    private fun live(path: String): String = GuardSourceScan.stripComments(read(path))

    /**
     * **宿主侧真的读写了台账**。
     *
     * 只连界面不连台账，是这个功能最容易出现的假完成：
     * 勾选框画得出来、点得动、也会保留在内存里，但重启就没了、也不影响执行判定。
     */
    @Test
    fun theDesktopBridgeActuallyReadsAndWritesTheLedger() {
        val src = live("desktopApp/src/main/kotlin/com/kapp/tidings/desktop/DesktopExtension.kt")
        assertTrue(
            src.contains("controller.onExecDevices"),
            "桌面端没有接『读执行名单』的桥 —— 界面会拿到空列表，配置区永远空着",
        )
        assertTrue(
            src.contains("controller.onSetExecDeviceAllowed"),
            "桌面端没有接『写执行名单』的桥 —— 勾选框点了不会有任何效果",
        )
        assertTrue(
            src.contains("grants.grant(") && src.contains("grants.revoke("),
            "桥没有真的动台账（grant/revoke 都没调）—— 那只是把状态留在内存里",
        )
    }

    /**
     * **只动 `scope=AGENT`，不碰可信设备那条记录**。
     *
     * 取消一个勾如果连带撤销了 `scope=ALL`，「不许用这个 agent」会变成「不再信任这台设备」，
     * 而后者会连带影响查看与其它 agent —— 用户点的是一个小勾，失去的是一整台设备的授权。
     */
    @Test
    fun uncheckingOnlyTouchesTheAgentScopedGrant() {
        val src = live("desktopApp/src/main/kotlin/com/kapp/tidings/desktop/DesktopExtension.kt")
        val revokeCall = src.substringAfter("grants.revoke(", "")
        assertTrue(revokeCall.isNotBlank(), "撤销调用不在")
        val revokeBlock = revokeCall.take(240)
        assertTrue(
            revokeBlock.contains("AgentGrantScopes.AGENT"),
            "撤销没有限定在 scope=AGENT —— 会连带撤掉可信设备记录",
        )
    }

    /**
     * **控制器把桥转给界面**（不是自己吞掉）。
     */
    @Test
    fun theControllerForwardsTheBridge() {
        val src = live("app/src/commonMain/kotlin/com/kapp/tidings/domain/RelayController.kt")
        assertTrue(src.contains("onExecDevices?.invoke"), "控制器没有把读请求转给宿主桥")
        assertTrue(src.contains("onSetExecDeviceAllowed?.invoke"), "控制器没有把写请求转给宿主桥")
    }

    /**
     * **详情页真的把勾选列表画出来**，且「添加设备」有出口。
     */
    @Test
    fun theDetailViewRendersTheChecklistAndTheAddEntry() {
        val src = live("composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/agents/WorkspaceDetailView.kt")
        assertTrue(src.contains("AgentExecDeviceChecklist("), "详情页没有画勾选列表")
        assertTrue(src.contains("AgentExecAddDeviceDialog("), "详情页没有『添加设备』的弹层")
        assertTrue(
            src.contains("onToggleExecDevice?.invoke"),
            "勾选动作没有接到上层 —— 点得动但改不了",
        )
    }

    /**
     * **桌面壳把三个回调都传进详情页**。
     *
     * 少传任何一个都会退化成「只读展示」：列表画得出来、勾选框在，但点了没反应。
     */
    @Test
    fun theDesktopShellPassesAllThreeCallbacks() {
        val src = live("composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopShell.kt")
        assertTrue(src.contains("execDevicesOf ="), "壳没有把『读名单』传下去")
        assertTrue(src.contains("onToggleExecDevice ="), "壳没有把『勾选』传下去")
        // 这三个回调是**三个独立的出口**，少任何一个都是一块功能静默消失：
        // 少「读」→ 配置区不画；少「勾选」→ 点了没反应；少「添加」→ 没有添加入口。
        //
        // 真机上漏的正是第三个：这条测试本名叫 AllThree，却只断言了两个 ——
        // 名字比内容诚实，而它照样绿了。所以这里三个都要断言。
        assertTrue(
            src.contains("onAddExecDevice ="),
            "壳没有把『添加设备』传下去 —— 界面上会没有那个入口",
        )
    }

    /**
     * **手机壳不接这个桥**。
     *
     * 手机连的是别人的机器 —— 那里的台账是对方的主权。
     * 如果哪天有人「顺手」在 `App.kt` 里接上它，就等于允许从手机改别人机器的授权。
     */
    /**
     * **渲染条件不会被静默跳过。**
     *
     * 这一块是 `if (... && isHost)` 包着的。条件只要有一个不成立，整块就不画 ——
     * 没有报错、没有空态、用户**看不到任何提示**，只会觉得「这个功能没做」。
     *
     * 已核对：`peer` 来自 `agents.firstOrNull{...}`，其 `nodeId` 恒非空；
     * 所以 `peer.nodeId == state.selfNodeId` 是本机唯一可判据 ——
     * 这条守卫钉住它别被改成某个拿不到值的字段。
     */
    @Test
    fun theRenderGateUsesAPreconditionThatCanActuallyHold() {
        val src = live("composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/agents/WorkspaceDetailView.kt")
        // 渲染门槛必须只依赖两个可判量：名单非空 与 isHost。
        val gate = src.substringAfter("if (execDevices != null", "").take(120)
        assertTrue(gate.isNotBlank(), "找不到渲染门槛")
        assertTrue(
            gate.contains("onToggleExecDevice != null") && gate.contains("isHost"),
            "渲染门槛缺条件 —— 那会让这一整块在某些路径上静默消失",
        )
        // isHost 必须由本机 nodeId 判（而不是某个可能为空的展示字段）。
        val isHostLine = src.substringAfter("val isHost = ", "").take(200)
        assertTrue(
            isHostLine.contains("selfNodeId"),
            "isHost 没有用 selfNodeId 判 —— 那可能永远为 false，整块静默不画",
        )
    }

    @Test
    fun thePhoneShellDoesNotWireTheBridge() {
        val src = live("composeApp/src/commonMain/kotlin/com/kapp/tidings/App.kt")
        assertTrue(
            !src.contains("onExecDevices"),
            "手机壳接了执行名单的桥 —— 那会让手机改别人机器上的授权",
        )
    }
}
