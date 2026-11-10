// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.AgentDuties
import com.kapp.tenon.core.protocol.AgentMemberName
import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.core.protocol.GroupWorkspaceInfo
import com.kapp.tenon.core.protocol.VirtualAgentMember
import com.kapp.tenon.core.protocol.declarationFor
import com.kapp.tenon.core.nowMillis
import com.kapp.tenon.core.protocol.resolveVirtualAgentKey
import com.kapp.tenon.core.protocol.withPointerProjection

/**
 * 群项目目录的**写路径**（项目目录决策稿 D5/D6/D14，P2）。
 *
 * 三个入口，全部收口到同一份投影重建：
 * - [handleSetGroupProjectDirectory]：群设置里设 / 换 / 清「本项目目录」（仅宿主可改）；
 * - [handleAddGroupAgent]：加智能体进群 —— **不再问目录**（D6），席位就地继承群项目目录；
 * - [handleRebuildGroupSeatProjections]：指针没变但解析结果可能变了（agent 刚宣告了目录、
 *   「去暴露」之后）—— 重新解析、重写投影。
 *
 * ## 为什么投影写回要经过群声明广播
 *
 * 指针与解析都在宿主本机（`HostDirectoryPort`），但「这个群干哪个目录」是**全群共识**：
 * 非宿主成员的显示（本项目目录行）、@ 派单的目标、执行前的 preflight 全都读席位声明
 * （`defaultWsId` / `allowedWorkspaces` 是协议既有字段，投影写入 = 零协议）。
 * 所以解析结果落回 `VirtualAgentMember`，经 `commitGroupMetaUpdate` 落库 + 广播 ——
 * 与工作区授权、团队编制同一条收口，不另开第三条写路径。
 *
 * ## 纪律
 *
 * - 名字 / 团队 / 实体形态**一概不碰**（D13：换目录不改名）——投影只走
 *   `withPointerProjection`，其余字段由 [declarationFor] 从 previous 原样带来。
 * - 解析不出（未暴露 / 群没设目录）⇒ `defaultWsId = null`、白名单清空 —— **不猜**，
 *   席位照常在群里（能对话），执行面显示「不可执行：未暴露本项目目录」。
 * - 手机端（无 `hostDirectoryPort`）：`handleSetGroupProjectDirectory` 直接拒绝
 *   （指针是宿主本机事实）；`handleAddGroupAgent` 允许，解析一律 null。
 */

/** [handleAddGroupAgent] 的结果：成功时带解析出的执行 wsId（null = 未暴露，席位仍然建立）。 */
sealed interface AddGroupAgentResult {
    /** 席位已写入并广播。[resolvedWsId] = 群项目目录解析出的执行点；null = 未暴露/未设置。 */
    data class Added(val resolvedWsId: String?) : AddGroupAgentResult

    /** 群不存在 / 本机不是成员 / 本机没有这个 agent（按本机工作区台账）/ 指针设置被拒。 */
    data class Rejected(val reason: Reason) : AddGroupAgentResult

    enum class Reason { GROUP_UNKNOWN, NOT_MEMBER, AGENT_UNKNOWN }
}

/**
 * 群设置「本项目目录」的写入口（D5：宿主本机指针 `groupId → dirId`，一个群一个项目现场）。
 *
 * @param dirId null = 清除指针（群回到「未设置」）。
 * @return false = 群不存在 / 本机不是成员 / 本机没有目录端口（手机）/ dirId 悬空被拒 ——
 *   什么都没改。true = 指针已写、席位投影已重建并广播。
 */
internal fun RelayController.handleSetGroupProjectDirectory(groupId: String, dirId: String?): Boolean {
    val meta = _state.value.settings.groups.firstOrNull { it.groupId == groupId } ?: return false
    if (_state.value.selfNodeId !in meta.members) return false
    val port = hostDirectoryPort ?: return false
    if (!port.setGroupPointer(groupId, dirId)) return false
    // 指针变了 ⇒ 每个本机席位的执行点都可能变（换目录 = 新会话，D8）。
    return handleRebuildGroupSeatProjections(groupId)
}

/**
 * 重新解析**本机席位**的执行投影并广播（幂等：没有变化就不产生新 rev）。
 *
 * @return false = 群不存在或没有目录端口（手机端没有解析点，投影只能由宿主广播过来）。
 */
internal fun RelayController.handleRebuildGroupSeatProjections(groupId: String): Boolean {
    val meta = _state.value.settings.groups.firstOrNull { it.groupId == groupId } ?: return false
    val port = hostDirectoryPort ?: return false
    val self = _state.value.selfNodeId
    if (self !in meta.members) return false
    val next = rebuildSelfSeatsForPointer(
        meta = meta,
        selfNodeId = self,
        resolveWsId = { agentId -> port.resolveForGroup(groupId, agentId) },
        titleOfWs = { wsId ->
            _state.value.agentWorkspaces.values.firstOrNull { it.wsId == wsId }?.wsTitle
                ?: port.snapshot().pointedDirectoryOf(groupId)?.displayName
        },
    ) ?: return true
    commitGroupMetaUpdate(
        meta.copy(
            virtualAgents = next.distinct().ifEmpty { null },
            rev = meta.rev + 1,
            updatedBy = self,
            updatedTs = nowMillis(),
        ),
    )
    return true
}

/**
 * 投影重建的**纯函数**（可单测）：对每个本机席位解析执行点、重写投影。
 *
 * 返回 null = 没有任何本机席位发生变化（调用方不广播）。
 * 非本机席位原样保留 —— 撤回是机主主权，覆盖不到别人。
 */
internal fun rebuildSelfSeatsForPointer(
    meta: GroupMeta,
    selfNodeId: String,
    resolveWsId: (agentId: String) -> String?,
    titleOfWs: (wsId: String) -> String?,
): List<VirtualAgentMember>? {
    var changed = false
    val next = meta.virtualAgents.orEmpty().map { seat ->
        if (seat.hostNodeId != selfNodeId) return@map seat
        val wsId = resolveWsId(seat.agentId)
        val allowed = wsId?.let { listOf(GroupWorkspaceInfo(wsId, titleOfWs(it).orEmpty())) }
            ?: emptyList()
        if (seat.defaultWsId == wsId && seat.allowedWorkspaces == allowed) {
            seat
        } else {
            changed = true
            seat.withPointerProjection(wsId, allowed)
        }
    }
    return if (changed) next else null
}

/**
 * 加智能体进群（D6：**不再出现目录选择**；席位就地继承群项目目录）。
 *
 * - 名字：既有席位沿用（@token 不漂）；新席位走 D13 规则 `kind-职责`
 *   （[duty] 取值域在 [AgentDuties]，域外值归一）。
 * - 解析：群项目目录对该 agent 的 wsId（宿主端）；解析不出 = 允许加入、标「不可执行」。
 *
 * @param agentId 本机工作区台账里的 agent 身份（`resolveVirtualAgentKey` 口径，
 *   大小写不敏感匹配）。
 */
internal fun RelayController.handleAddGroupAgent(
    groupId: String,
    agentId: String,
    duty: String? = null,
): AddGroupAgentResult {
    val meta = _state.value.settings.groups.firstOrNull { it.groupId == groupId }
        ?: return AddGroupAgentResult.Rejected(AddGroupAgentResult.Reason.GROUP_UNKNOWN)
    val self = _state.value.selfNodeId
    if (self !in meta.members) {
        return AddGroupAgentResult.Rejected(AddGroupAgentResult.Reason.NOT_MEMBER)
    }
    // 本机必须是这个 agent 的宿主：席位声明的是「我的 agent」，不是别人的
    // （执行授权与解析都在宿主侧，替别人声明 = 两边各说各话）。
    val rows = _state.value.agentWorkspaces.values.filter { it.peerId == self }
    val row = rows.firstOrNull {
        resolveVirtualAgentKey(null, it.agentId, it.agentKind)?.equals(agentId.trim(), ignoreCase = true) == true
    } ?: return AddGroupAgentResult.Rejected(AddGroupAgentResult.Reason.AGENT_UNKNOWN)
    val canonicalAgentId = resolveVirtualAgentKey(null, row.agentId, row.agentKind) ?: agentId.trim()

    val previous = meta.virtualAgents.orEmpty().firstOrNull {
        it.hostNodeId == self && it.agentId.equals(canonicalAgentId, ignoreCase = true)
    }
    val taken = meta.virtualAgents.orEmpty().map { it.name }.toMutableSet()
    val name = previous?.name
        ?: AgentMemberName.unique(
            AgentMemberName.base(row.agentKind, canonicalAgentId, AgentDuties.normalize(duty)),
            taken,
        )
    val resolvedWsId = hostDirectoryPort?.resolveForGroup(groupId, canonicalAgentId)
    val seat = declarationFor(
        agentId = canonicalAgentId,
        hostNodeId = self,
        name = name,
        defaultWsId = resolvedWsId,
        previous = previous,
    ).withPointerProjection(
        defaultWsId = resolvedWsId,
        allowedWorkspaces = resolvedWsId?.let { wsId ->
            listOf(
                GroupWorkspaceInfo(
                    wsId = wsId,
                    title = _state.value.agentWorkspaces.values.firstOrNull { it.wsId == wsId }?.wsTitle
                        ?: hostDirectoryPort?.snapshot().pointedDirectoryOf(groupId)?.displayName
                        ?: "",
                ),
            )
        } ?: emptyList(),
    )
    val next = meta.virtualAgents.orEmpty()
        .filterNot { it.hostNodeId == self && it.agentId.equals(canonicalAgentId, ignoreCase = true) } + seat
    commitGroupMetaUpdate(
        meta.copy(
            virtualAgents = next.distinct().ifEmpty { null },
            rev = meta.rev + 1,
            updatedBy = self,
            updatedTs = nowMillis(),
        ),
    )
    return AddGroupAgentResult.Added(resolvedWsId)
}

/**
 * 移除本机的一个席位（撤回入驻）—— D6 的另一半：机主主权，只覆盖自己的声明。
 *
 * 与 [handleAddGroupAgent] 对称：入驻是显式动作，撤回也是。旧「工作区授权面板」的
 * 撤回靠「把声明集合整体改写为不含它的列表」，那条路随勾选流一起删了；席位身份
 * （D4：`(groupId, hostNodeId, agentId)`）现在是显式定位键，按 agentId 摘除一条。
 *
 * @return false = 群不存在 / 本机不是成员 / 本机没有这个 agentId 的席位 —— 什么都没改。
 */
internal fun RelayController.handleRemoveGroupAgent(groupId: String, agentId: String): Boolean {
    val meta = _state.value.settings.groups.firstOrNull { it.groupId == groupId } ?: return false
    val self = _state.value.selfNodeId
    if (self !in meta.members) return false
    val seat = meta.virtualAgents.orEmpty().firstOrNull {
        it.hostNodeId == self && it.agentId.equals(agentId.trim(), ignoreCase = true)
    } ?: return false
    val next = meta.virtualAgents.orEmpty().filterNot {
        it.hostNodeId == self && it.agentId.equals(seat.agentId, ignoreCase = true)
    }
    commitGroupMetaUpdate(
        meta.copy(
            virtualAgents = next.distinct().ifEmpty { null },
            rev = meta.rev + 1,
            updatedBy = self,
            updatedTs = nowMillis(),
        ),
    )
    return true
}
