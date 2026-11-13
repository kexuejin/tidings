// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.core.protocol.VirtualAgentMember

/**
 * 「执行配置」矩阵的**一行**：`团队 × 员工`（不是「员工」）。
 *
 * 行粒度必须是团队 × 员工：执行档本身就是团队粒度的（键 `(groupId, dirId, seatName)`），
 * 同一个人在两个团队各占一行，正好让「只在本团队换个底座」成为一次本地操作。
 */
public data class ExecutionMatrixRowUi(
    val groupId: String,
    val groupName: String,
    /** 席位键（执行档主键的第三段就是它 —— 席位身份，不随底座变）。 */
    val seatAgentId: String,
    val memberAgentId: String,
    /** 本团队内的名字；空 = 声明里没写（UI 翻译，不拿 agentId 顶替）。 */
    val memberName: String,
    /** 本团队内的角色；null = 领队（席位没有 role 字段）或声明没写。 */
    val role: String?,
    /** 生效的底座键；空串 = **未指派**（合法一等状态，不猜一个默认值）。 */
    val driverKey: String,
    /** 生效的模型（本期只消费第一条）；空 = 继承驱动默认。 */
    val model: String?,
    /**
     * 这一行是**本团队落过盘**的值（`profileOf` 非空）还是「继承」。
     *
     * 用户改过就必须看得见「本团队覆盖」：不标的话他永远不知道这一行到底生效的是哪份配置。
     */
    val overridden: Boolean,
    /** 底座是否**此刻在位**（`driverCatalog()` 是宿主当下的事实，不是保存时的事实）。 */
    val installed: Boolean,
    /**
     * 这个群有没有可用的项目目录指针（执行档主键的一半）。
     *
     * false ⇒ 这一行**改不了**：执行档挂在一个解析不出来的目录上没有意义，UI 只读并说明。
     */
    val editable: Boolean,
)

/**
 * 执行配置矩阵的投影（规范 §4.0）：把所有「本机席位」摊成一张表。
 *
 * ## 为什么只摊本机席位
 *
 * `TeamExecution.resolve` 要求 `seat.hostNodeId == selfNodeId` —— 别的宿主上的席位由那台
 * 机器执行，本机既读不到也写不了它的执行档。把别人的席位画进这张表，用户会以为能在本机
 * 改别人的执行器。
 *
 * ## 为什么 `profileOf` 为 null 不等于「继承」
 *
 * 没落过盘时按声明推导一份初值（[initialExecutionProfileUi]：单兵也有一条 worker，组员
 * 首名 worker、其余 reviewer），这样矩阵永远有行可看；但 `overridden = false` 必须如实
 * 告诉用户「这是推导值，不是本团队配过的值」。
 *
 * @param catalog 宿主在位驱动键集合（`TeamExecutionPort.driverCatalog()`）。
 */
public fun executionMatrixRows(
    groups: List<GroupMeta>,
    selfNodeId: String,
    port: TeamExecutionPort,
    catalog: Set<String> = port.driverCatalog(),
): List<ExecutionMatrixRowUi> {
    val out = mutableListOf<ExecutionMatrixRowUi>()
    for (group in groups) {
        val dirId = port.groupPointerDirId(group.groupId)
        val editable = dirId != null
        for (seat in group.virtualAgents.orEmpty()) {
            if (seat.hostNodeId != selfNodeId) continue
            val saved = dirId?.let { port.profileOf(it, seat.agentId) }
            // 群还没有目录指针时**照样出行**：执行档键的一半缺失只说明「没法改」，
            // 不说明「这个席位不存在」。早退会让整支团队从矩阵里消失，用户以为它没了。
            val profile = saved
                ?: dirId?.let { initialExecutionProfileUi(port, group, seat) }
                ?: declaredProfileUi(seat)
            val nameByAgent: (String) -> String? = { id ->
                if (id == seat.agentId) seat.name else seat.teamMembers.firstOrNull { it.agentId == id }?.name
            }
            val roleByAgent: (String) -> String? = { id ->
                seat.teamMembers.firstOrNull { it.agentId == id }?.role?.takeIf { it.isNotBlank() }
            }
            profile.members.forEach { member ->
                out += ExecutionMatrixRowUi(
                    groupId = group.groupId,
                    groupName = group.name,
                    seatAgentId = seat.agentId,
                    memberAgentId = member.agentId,
                    memberName = nameByAgent(member.agentId).orEmpty(),
                    role = roleByAgent(member.agentId),
                    driverKey = member.driverKey,
                    model = member.models.firstOrNull()?.takeIf { it.isNotBlank() },
                    overridden = saved != null,
                    installed = member.driverKey.isNotBlank() && member.driverKey in catalog,
                    editable = editable,
                )
            }
        }
    }
    return out
}

/**
 * 矩阵按团队分组（保持 [executionMatrixRows] 的顺序：群序 → 席位序 → 成员序）。
 *
 * 分组只做「连续同群合并」，不重排 —— 重排会让同一个群的两次出现分开。
 */
public fun executionMatrixByGroup(
    rows: List<ExecutionMatrixRowUi>,
): List<Pair<String, List<ExecutionMatrixRowUi>>> {
    val out = mutableListOf<Pair<String, MutableList<ExecutionMatrixRowUi>>>()
    rows.forEach { row ->
        val last = out.lastOrNull()
        if (last != null && last.first == row.groupId) {
            last.second += row
        } else {
            out += row.groupId to mutableListOf(row)
        }
    }
    return out.map { it.first to it.second.toList() }
}

/**
 * 没有执行档（也没法推导初值）时的**声明层**行：只报「声明里有哪些成员」。
 *
 * 此时 `driverKey` 恒空 —— 没有落盘的配置就没有生效的底座，写个默认值才是编数据。
 */
private fun declaredProfileUi(seat: VirtualAgentMember): TeamExecutionProfileUi =
    TeamExecutionProfileUi(
        dirId = "",
        leaderAgentId = seat.agentId,
        members = if (seat.teamMembers.isEmpty()) {
            listOf(
                TeamExecutionMemberUi(
                    agentId = seat.agentId,
                    kind = TeamExecutionKindsUi.WORKER,
                    driverKey = "",
                ),
            )
        } else {
            seat.teamMembers.mapIndexed { index, member ->
                TeamExecutionMemberUi(
                    agentId = member.agentId,
                    kind = if (index == 0) TeamExecutionKindsUi.WORKER else TeamExecutionKindsUi.REVIEWER,
                    driverKey = "",
                )
            }
        },
    )
