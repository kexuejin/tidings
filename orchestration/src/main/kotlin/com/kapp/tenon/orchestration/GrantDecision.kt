// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

/**
 * 面板上人按下的一个授权决定：**先写本机台账、再回帧**用的同一份事实。
 *
 * 抽成一个数据类而不是 8 个形参：它既是可信设备面板（TrustedDeviceDecisions）的入参，
 * 也是 agent-grant 帧的载荷形状 —— 两处用同一个值，就不会出现「台账记的和发出去的不是
 * 同一件事」。
 *
 * ## 为什么住在 :orchestration
 *
 * 它是**编排面与壳之间的交接模型**：产生的决定由编排侧（DesktopExtension.sendGrantReply）
 * 落成帧，展示与按钮语义留在壳层。原来它定义在桌面的可信设备面板文件里，于是编排层要
 * 反过来依赖壳里的类型 —— 下沉后这条反向依赖被切断，壳只 import 这个模型。
 */
data class GrantDecision(
    val nodeId: String,
    val state: String,
    val scope: String,
    val agentKey: String? = null,
    val wsId: String? = null,
    val access: String? = null,
    val isMyDevice: Boolean? = null,
    val delegatedApproval: Boolean? = null,
)
