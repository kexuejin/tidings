// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.drivers.AgentProfiles
import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.core.protocol.sameAgent
import com.kapp.tenon.domain.AgentWorkstationPort
import com.kapp.tenon.domain.AgentWorkstationUi
import com.kapp.tenon.domain.TeamExecutionPort

/**
 * [AgentWorkstationPort] 的桌面实现：把「这个 agent 用什么驱动干活」从**本机执行绑定**里读出来。
 *
 * ## 两个来源，一条优先级
 *
 * 1. **执行档的 worker**（用户显式指派过底座）—— 唯一能改变答案的那一处。
 *    `driverKey` 是执行档的一半，席位 `agentId` 是身份的一半；换底座只改前者。
 * 2. **席位 slug 归一**（没指派过时的既有口径）—— 与执行侧同一条解析：
 *    `DesktopWorkerOrchestrator` 对空 `driverKey` 的处理就是「按席位 slug 走」，
 *    这里用同一个 `AgentProfiles.resolveKey`，不另写一份归一。
 *
 * 顺序不能反：先看指派、再看 slug。反过来会出现「用户在群编制里换了底座，名录还报旧
 * 驱动」—— 那正是这类界面最容易出的静默错。
 *
 * ## 只读本机
 *
 * 执行档落在席位宿主进程里，别的设备的绑定本进程读不到。拿 `selfNodeId` 之外的主机
 * 来问，答案只能是 null —— **本机名册不是全网的**，这一点不因界面上「拉平显示」而改变。
 *
 * ## 多值不猜
 *
 * 同一个 agent 在多个群被绑了不同驱动时返回 null：一次只画一个工位格子，替人挑一个
 * 与 `TeamMention.Ambiguous` 违背同一条纪律（宁可少画，不可画错）。真要看清全貌，
 * 那是详情页逐群列执行点的事。
 */
class DesktopAgentWorkstationPort(
    /** 本机节点 id（只有它自己的执行绑定可读）。 */
    private val selfNodeId: () -> String,
    /** 编制来源（席位宿主 + agentId → 群）。与项目管理同一份 settings。 */
    private val groups: () -> List<GroupMeta>,
    /** 执行档读写口（与 UI 的是同一个实例，不新建第二份 store）。 */
    private val execution: TeamExecutionPort,
) : AgentWorkstationPort {

    override fun workstationOf(hostNodeId: String, agentKey: String): AgentWorkstationUi? {
        if (agentKey.isBlank() || hostNodeId.isBlank()) return null
        if (hostNodeId != selfNodeId()) return null

        // 这个 agent 在各群里被指派的底座（已归一）。同一驱动被多个群指派只记一次 ——
        // 集合大小就是「有没有分歧」的判据。
        val bound = LinkedHashSet<String>()
        groups().forEach { group ->
            group.virtualAgents.orEmpty()
                .filter { seat -> seat.hostNodeId == hostNodeId && sameAgent(seat.agentId, null, agentKey) }
                .forEach { seat ->
                    // 群没有项目目录指针 ⇒ 拿不到执行档主键（D16），这一条无从判断，
                    // 跳过 ≠ 没指派：它与「指派过」是两件事，所以不往集合里塞空串。
                    val dirId = execution.groupPointerDirId(group.groupId) ?: return@forEach
                    val workerDriver = execution.profileOf(dirId, seat.agentId)
                        ?.members?.firstOrNull { it.isWorker }?.driverKey
                    AgentProfiles.normalizeKey(workerDriver)?.let { bound += it }
                }
        }

        val resolved = when (bound.size) {
            1 -> bound.first()
            0 -> AgentProfiles.resolveKey(agentKey, AgentProfiles.knownKeys())
            // ≥2：多个群绑了不同底座 —— 不替人挑。
            else -> return null
        } ?: return null

        return AgentWorkstationUi(
            driverKey = resolved,
            driverLabel = AgentProfiles.profileOf(resolved)?.displayName,
        )
    }
}
