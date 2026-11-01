// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import com.kapp.tidings.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertTrue(
            source.contains("controller.invokePluginPresenceOf = "),
            "插件在线状态桥必须装上（S4-4b）：没有它，插件判离线后派单照样干等租约 —— 真机读数是 14 分钟零反馈",
        )
        // T-3b 产物投递：沙箱 patch.diff 经结构化回调收下、成功后投进派单来源的群。
        // 摘掉任何一环（不接回调 / 不发群）⇒ patch 只躺在宿主 /tmp 里，群里永远看不到。
        assertTrue(
            source.contains("onArtifact = { path, name, bytes, _, _ -> artifacts.add(Triple(path, name, bytes)) }"),
            "执行桥没有接 onArtifact 回调 —— 沙箱产物到不了投递环节（T-3b 断链）",
        )
        assertTrue(
            source.contains("controller.sendFiles(") && source.contains("ConvId.group(deliverGroupId)"),
            "产物没有投进派单来源的群（sendFiles + ConvId.group 群路径）—— patch 只躺在宿主本机",
        )
        // T-G1 审查合流：merge-patch 动作在宿主本机落地（git apply），冲突落台账。
        assertTrue(
            source.contains("actionId == \"merge-patch\"") && source.contains("DesktopPatchMerge.apply("),
            "合流动作没有接 DesktopPatchMerge —— diff 卡上的 [合并此补丁] 点了没反应",
        )
        assertTrue(
            source.contains("latestPatchRefFor(") && source.contains("eventType = \"merge-result\""),
            "合流没有按会话找回 patch（latestPatchRefFor）或没有把冲突落台账（merge-result）",
        )
        // 动作帧（op=action）里没有会话位，这一跳拿到的 sessionId 恒为 null —— 只按会话
        // 找 patch 的判据在真机上永远不成立（2026-11-01 幕 6：卡面「已由 Pixel 6 处理」，
        // 宿主仓零提交）。按卡片反查这条路必须同时在，否则「合并」是个假按钮。
        assertTrue(
            source.contains("latestPatchRefForCard("),
            "合流没有按卡片找回 patch（latestPatchRefForCard）—— 动作帧不带会话，合并分支永远进不去",
        )
        // T-G2 标签路由：两桥装配 + 接收侧持标签校验（缺一整条链就黑）。
        assertTrue(
            source.contains("controller.peerTagRouter = ") && source.contains("controller.channelDispatch = "),
            "发起侧标签路由两桥没装配 —— requiredTag 消费端永远走失败横幅（T-2c 悬空账）",
        )
        assertTrue(
            source.contains("PeerTagRouter.resolve(") && source.contains("nodeIdsWithTag("),
            "路由语义没接 PeerTagRouter（在线优先 + 字典序）或没读标签 store",
        )
        assertTrue(
            source.contains("node does not hold tag"),
            "接收侧没有持标签校验 —— 不持标签的节点不该接活（防御门）",
        )
        // E1：CLI 驱动执行桥 —— @antigravity/@claude/@codex 的本机子进程通路。
        assertTrue(
            source.contains("created.resolveCliDriver(request.agentId) != null") &&
                source.contains("created.executeCliTask("),
            "CLI 驱动回退没接 —— @antigravity 派单永远 no-plugin（E2E 幕 2 起全黑）",
        )
        assertTrue(
            source.contains("cliDrivers = driverRegistry"),
            "CLI 注册表没注入 ControlPlane —— executeCliTask 无驱动可解析",
        )
        // T-G9/T-G10：transfer 宣告（原主可见「已移交」）+ 结果有界重投 + 域层首赢去重。
        assertTrue(
            source.contains("origin = \"transferred\""),
            "transfer 没有向原宿主宣告接管 —— 原主的看板永远不知道任务去了哪",
        )
        assertTrue(
            source.contains("result delivery failed after"),
            "结果投递没有有界重试 —— 对端离线丢一次 = 发起方白等租约超时",
        )
        val domain = GuardSourceScan.stripComments(
            GuardSourceScan.read("app/src/commonMain/kotlin/com/kapp/tidings/domain/RelayController.kt"),
        )
        assertTrue(
            domain.contains("seenChannelResults.add(channelId)"),
            "结果消费没有按 channelId 首赢去重 —— 重投会复制群流消息",
        )
        assertTrue(source.contains("controller.invokeGrantAllows = "), "可信设备判据桥必须装上（§6.3）")
        assertTrue(source.contains("controller.invokeExecPolicyOf = "), "每 agent 执行范围桥必须装上（§6.3）")
        assertTrue(source.contains("controller.invokeRecordGrantRequest = "), "未授权申请桥必须装上（面板要能冒出待批项）")
        assertTrue(source.contains("controller.onHostCardAction = "), "宿主侧卡片动作出口必须装上（§7.3-2 的插件转发段）")
    }

    @Test
    fun presenceVocabularyMatchesThePluginRegistry() {
        // 域层的两个词必须与 agentNode `/v1/plugins` 的 status 同形 —— 映射错一个字母，
        // S4-4b 的「离线即回声」就永远不触发。两套常量的同值关系在这里钉死。
        assertEquals(
            com.kapp.tidings.agent.plugins.PluginRegistry.STATUS_ONLINE,
            com.kapp.tidings.domain.PluginPresence.ONLINE,
        )
        assertEquals(
            com.kapp.tidings.agent.plugins.PluginRegistry.STATUS_OFFLINE,
            com.kapp.tidings.domain.PluginPresence.OFFLINE,
        )
    }

    @Test
    fun detachClearsEveryInvokeBridge() {
        val source = live()
        for (bridge in listOf(
            "controller.invokeExecutor = null",
            "controller.invokePluginPresenceOf = null",
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
