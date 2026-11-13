// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

/**
 * 员工**工位**的壳层端口：这个 agent 在哪台机器上、用哪个驱动干活。
 *
 * ## 为什么是端口而不是投影字段
 *
 * 驱动名册（`AgentProfiles`）住 `:agentNode`，执行档（`TeamExecutionStore`）住
 * `:orchestration` —— `:app` 一个都 import 不到（`:domainComposeFreeGate` 同一条边界）。
 * 与 `teamExecutionPort` / `hostDirectoryPort` 同一形状：**手机端端口为 null ⇒ 只画
 * 读得到的那半**（宿主名），工具名不画。
 *
 * ## 为什么不是纯函数投影
 *
 * `hubAgents` 的输入是三个切片（会话 / 节点 / 设置），执行绑定不在其中：它是宿主
 * 本机的落盘事实，不是同步过来的协议帧。把 `driverKey` 塞进 `AgentHubUi` 只能靠
 * 「投影时顺手问端口」，那会让一个纯函数依赖一个可变的壳对象 —— 投影的
 * 可测性（给三个切片就有确定输出）正是它现有的价值。
 */
interface AgentWorkstationPort {

    /**
     * 该实体的工位。null = 读不到 / 没有可如实上报的驱动。
     *
     * ## null 的三种成因（对 UI 是同一个动作：不画工具名，只画宿主名）
     *
     * 1. **不是本机**：执行档只落在席位宿主进程里，别的设备的绑定本进程读不到；
     * 2. **多值**：同一个 agent 在多个群被绑了不同驱动 —— 一次只画一个格子，不替人挑
     *    （与 `TeamMention.Ambiguous` 同一条纪律）；
     * 3. **解析不出**：席位 slug 不是已知驱动，执行档也没指派过。
     *
     * 不返回空串：空串会被当成一个真实取值（与 `AgentHubUi.hostName` 的 null 同纪律）。
     */
    fun workstationOf(hostNodeId: String, agentKey: String): AgentWorkstationUi?
}

/**
 * 一个工位：驱动键 + 它的展示名。
 *
 * 只有两项 —— **模型不在里面**。执行档的 `models` 首位是生效模型，但卡片上没有
 * 「模型」这一列可放；为一个画不出来的字段留一条通道，存下来就是死数据
 * （与 `validateExecutionProfileUi` 拒多模型同一条取舍）。
 */
data class AgentWorkstationUi(
    /** 驱动档案键（`codex` / `claude` / `dsh`…）—— 已归一，非空。 */
    val driverKey: String,
    /**
     * 驱动的展示名（`Codex` / `Claude Code`）；null = 档案表里没有这个名字。
     *
     * UI 侧回落成 [driverKey] 本身：键是事实，编一个名字不是。
     */
    val driverLabel: String? = null,
)
