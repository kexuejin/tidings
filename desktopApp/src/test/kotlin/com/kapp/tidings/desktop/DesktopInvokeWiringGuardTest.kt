// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import com.kapp.tidings.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * M4 跨端派单的**桌面装配守卫**。
 *
 * 派单网道的三段住在三个模块：协议帧（core）、租约/看板卡/审批防重（:app 域层）、
 * HTTP 入口与插件执行（agentNode 的 ControlPlane）。desktopApp 是把它们接成通路的
 * 唯一一处 —— 而「接没接上」类型系统表达不了：任何一桥被摘掉（attach 里删一行、
 * 或 detach 忘了清），编译照样绿，但那一端的派单就静默变成 no-executor / 野通路。
 *
 * 所以按源码文本钉住**装配点**：五个桥必须在 attach 里赋值、必须在 detach 里清空。
 * 剥掉注释再匹配（[GuardSourceScan] 的纪律），避免被解释这件事的注释绊倒。
 */
class DesktopInvokeWiringGuardTest {

    private val extension = "desktopApp/src/main/kotlin/com/kapp/tidings/desktop/DesktopExtension.kt"

    private fun live(): String = GuardSourceScan.stripComments(GuardSourceScan.read(extension))

    @Test
    fun dispatchIsDelegatedToTheDomainMeshNotARouteLocalImplementation() {
        val source = live()
        assertTrue(
            source.contains("created.invokeDispatcher = "),
            "ControlPlane 的 invokeDispatcher 必须接到域层派单（dispatchCrossHostInvoke）：HTTP 入口只解包，网道在域层",
        )
        assertTrue(
            source.contains("controller.dispatchCrossHostInvoke("),
            "派发必须委托域层网道：在装配层另写一份派发，租约与看板卡就分叉了",
        )
    }

    @Test
    fun executorAndAuthorizationBridgesAreInstalled() {
        val source = live()
        assertTrue(
            source.contains("controller.invokeExecutor = object : com.kapp.tidings.domain.AgentInvokeExecutor"),
            "执行桥必须装上：不装 = 每条入站派单都答 no-executor，跨端派单整条死路",
        )
        assertTrue(
            source.contains("created.pluginForAgent("),
            "执行必须先解析本机承接插件：没有它就没有可派单的对象",
        )
        assertTrue(
            source.contains("created.executePluginTask("),
            "执行必须走 executePluginTask 唯一实现：另写一份，台账就漂成两本",
        )
        assertTrue(source.contains("controller.invokeGrantAllows = "), "可信设备判据桥必须装上（§6.3）")
        assertTrue(source.contains("controller.invokeExecPolicyOf = "), "每 agent 执行范围桥必须装上（§6.3）")
        assertTrue(source.contains("controller.invokeRecordGrantRequest = "), "未授权申请桥必须装上（面板要能冒出待批项）")
        assertTrue(source.contains("controller.onHostCardAction = "), "宿主侧卡片动作出口必须装上（§7.3-2 的插件转发段）")
    }

    @Test
    fun detachClearsEveryInvokeBridge() {
        val source = live()
        for (bridge in listOf(
            "controller.invokeExecutor = null",
            "controller.invokeGrantAllows = null",
            "controller.invokeExecPolicyOf = null",
            "controller.invokeRecordGrantRequest = null",
            "controller.onHostCardAction = null",
        )) {
            assertTrue(
                source.contains(bridge),
                "detach 必须清掉 $bridge：留着它，域层会把派单交给一个已停止的控制面",
            )
        }
    }
}
