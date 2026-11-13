// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.nowMillis
import com.kapp.tenon.core.protocol.AgentDuties
import com.kapp.tenon.core.protocol.AgentMemberName
import com.kapp.tenon.core.protocol.ConvId
import com.kapp.tenon.core.protocol.EntityTypes
import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.core.protocol.GroupWorkspaceInfo
import com.kapp.tenon.core.protocol.TeamMemberDraft
import com.kapp.tenon.core.protocol.TeamMemberInfo
import com.kapp.tenon.core.protocol.TeamRosterValidation
import com.kapp.tenon.core.protocol.VirtualAgentMember
import com.kapp.tenon.core.protocol.declarationFor
import com.kapp.tenon.core.protocol.isTeam
import com.kapp.tenon.core.protocol.withPointerProjection

/**
 * 编制变更的**唯一写路径**：一条命令 + 固定顺序，三个入口只负责把界面输入翻译成命令。
 *
 * ## 为什么收
 *
 * 入驻（`handleAddGroupAgent`）、撤回（`handleRemoveGroupAgent`）、团队编制
 * （`handleSetGroupTeamDeclaration`）原先各写一遍 patch 与局部校验，于是校验规则
 * 已经分叉：团队编制查 token 池，入驻只查名字唯一 —— 同一个名字在两条路上一个能过、
 * 一个过不了。第二个入口（智能体页）与第三个入口（agent 工具面）再加上来，分叉会
 * 变成三份互不相同的真相。
 *
 * 所以命令只描述**意图**（谁、要改哪条席位的什么），校验与落盘顺序只在这里实现一次：
 *
 * 1. 群存在、`actor` 在群里（agent 还要**本机主权** + 必带 `baseRev`）；
 * 2. `baseRev` 与当前 rev 不符 ⇒ [DeclarationResult.Conflict]，不静默覆盖；
 * 3. 定位席位（本机 + `agentId`，大小写不敏感）；
 * 4. 校验（名字唯一 / token 池 / 每群一个团队）；
 * 5. 只改目标席位，其余席位逐字段原样保留；
 * 6. `rev + 1`、`updatedBy`、`updatedTs`；
 * 7. [RelayController.commitGroupMetaUpdate]（预算自检 → 落库 → need 游标 → 逐成员广播）；
 * 8. 审计一行（[DeclarationAuditLog]）。
 *
 * ## 判据（写进 `GroupDeclarationCommandTest`）
 *
 * 同一个字段经不同入口改动，落库后的 rev 增量、广播帧数、审计行数必须完全一致。
 * 不一致就是有旁路，按缺陷处理 —— 旁路由 `GroupDeclarationBypassGuardTest` 在源码层守。
 */

/** 谁在改编制。身份只用于审计与权限判定，不参与席位定位。 */
sealed interface DeclarationActor {

    /** 本机或对端的人（界面上的点按）。 */
    data class Human(val nodeId: String) : DeclarationActor {
        override val actorNodeId: String get() = nodeId
    }

    /**
     * 智能体自己发起（工具面）。
     *
     * `hostNodeId` 必须是本机 —— 只声明自己的席位是本机主权，替别的宿主声明等于两边各说各话。
     */
    data class Agent(val hostNodeId: String, val agentId: String) : DeclarationActor {
        override val actorNodeId: String get() = hostNodeId
        override val agentIdOrNull: String get() = agentId
    }

    /** 走协议的远端命令（对端广播过来的编制变更，经解码后再走同一条路径）。 */
    data class Remote(val nodeId: String) : DeclarationActor {
        override val actorNodeId: String get() = nodeId
    }

    /** 审计里的行为人节点。 */
    val actorNodeId: String

    /** agent 发起时的那条席位身份；人类与远端为 null。 */
    val agentIdOrNull: String? get() = null
}

/** 命令从哪个面发起（审计与回执用；不参与校验）。 */
enum class DeclarationOrigin { GROUP_INFO, AGENT_HUB, AGENT_TOOL, PROTOCOL }

/** 编制变更命令：四条覆盖全部变更，不再为每个入口写一套。 */
sealed interface GroupDeclarationCommand {

    val groupId: String

    /**
     * 乐观并发的基线 rev。
     *
     * `null` = 「以当前 rev 为准」（既有界面入口还没携带 rev，保持它们的行为不变）；
     * 非 null 时严格比对，不匹配即 [DeclarationResult.Conflict]。
     *
     * **agent 发起时必须是非 null**：智能体看不到界面、拿不到「用户看到的是哪一版」，
     * 不带基线的写入就是把并发冲突变成静默覆盖。
     */
    val baseRev: Int?

    val actor: DeclarationActor
    val origin: DeclarationOrigin

    /**
     * 入驻：给本机某 agent 在本群建一条席位。
     *
     * [agentKind] 只用于派生默认名（`kind-职责`），[duty] 域外值由 `AgentDuties` 归一。
     */
    data class JoinSeat(
        override val groupId: String,
        val agentId: String,
        val agentKind: String? = null,
        val duty: String? = null,
        override val baseRev: Int? = null,
        override val actor: DeclarationActor,
        override val origin: DeclarationOrigin = DeclarationOrigin.GROUP_INFO,
    ) : GroupDeclarationCommand

    /** 撤回：摘掉本机某 agent 在本群的席位（含它作为领队的团队声明）。 */
    data class LeaveSeat(
        override val groupId: String,
        val agentId: String,
        override val baseRev: Int? = null,
        override val actor: DeclarationActor,
        override val origin: DeclarationOrigin = DeclarationOrigin.GROUP_INFO,
    ) : GroupDeclarationCommand

    /**
     * 编辑：改显示名 / 职责。
     *
     * 身份键 `agentId` **不可改**（改名改的是 `@` token，不是员工）。`duty` 只影响
     * 「这条席位不存在时按入驻处理」的默认名，不改已有名字。项目目录与执行档分别住
     * 投影重建与执行绑定端口，不在这条命令上。
     */
    data class UpdateSeat(
        override val groupId: String,
        val agentId: String,
        val name: String? = null,
        val duty: String? = null,
        val agentKind: String? = null,
        override val baseRev: Int? = null,
        override val actor: DeclarationActor,
        override val origin: DeclarationOrigin = DeclarationOrigin.AGENT_HUB,
    ) : GroupDeclarationCommand

    /**
     * 团队编制：设 / 清某席位的组员名册。
     *
     * `roster` 为 null 或空 = **关闭团队**（`entityType` 回 single、组员清空），
     * 与入驻/撤回同一节奏：一次保存 = 一次 rev+1、一次广播。
     */
    data class SetTeamRoster(
        override val groupId: String,
        val agentId: String,
        val roster: List<TeamMemberDraft>?,
        override val baseRev: Int? = null,
        override val actor: DeclarationActor,
        override val origin: DeclarationOrigin = DeclarationOrigin.GROUP_INFO,
    ) : GroupDeclarationCommand

    /**
     * **挂上团队档案**：让这条席位属于团队 [teamId]，并把名册快照写给拿不到档案的对端。
     *
     * 只由 `TeamProfileWritePath` 发（档案是唯一真相，这里有快照是为了跨端可见）。
     * 席位不存在时就地入驻 —— 「群里添加一个团队」是一次动作，不该要求先逐个加人。
     *
     * [teamName] 是档案名字的快照；消费方按「档案 → 快照 → 群名」三段兜底显示。
     */
    data class AttachTeam(
        override val groupId: String,
        val agentId: String,
        val teamId: String,
        val teamName: String,
        val roster: List<TeamMemberDraft>,
        /** 新席位派生默认名用（`kind-职责`），与 [JoinSeat] 同一口径。 */
        val agentKind: String? = null,
        val duty: String? = null,
        override val baseRev: Int? = null,
        override val actor: DeclarationActor,
        override val origin: DeclarationOrigin = DeclarationOrigin.AGENT_HUB,
    ) : GroupDeclarationCommand

    /**
     * **摘掉档案引用**（解散团队 / 把团队移出某个群）：席位回单兵，`teamId`/`teamName` 清空。
     *
     * 席位本身保留 —— 撤席位是 [LeaveSeat] 的事，两件事不该混在一个命令里。
     */
    data class DetachTeam(
        override val groupId: String,
        val agentId: String,
        override val baseRev: Int? = null,
        override val actor: DeclarationActor,
        override val origin: DeclarationOrigin = DeclarationOrigin.AGENT_HUB,
    ) : GroupDeclarationCommand

    /**
     * **挂上员工档案**：让这条席位属于员工 [employeeId]，并写名字快照。
     *
     * 只由 `EmployeeProfileWritePath` 发（档案是唯一真相，快照是给拿不到档案的对端看的）。
     * 席位不存在时就地入驻 —— 「群里添加一个员工」是一次动作，不该要求先逐个加人。
     *
     * ## 席位名怎么定（允许本群别名）
     *
     * - 新席位：用 [token]（档案里定好的 `@` token）；本群已占用 → `unique` 追加序号，
     *   结果里如实标成「本群别名」，不静默。
     * - 已有席位：[forceToken] = false（默认）时**保留它现有的名字**（它可能就是本群别名，
     *   换掉会让群里的 `@` 习惯断掉）；true = 覆盖成 [token]（档案显式改 token 时才用）。
     */
    data class AttachEmployee(
        override val groupId: String,
        val agentId: String,
        val employeeId: String,
        /** 档案显示名（可含中文）的快照。 */
        val employeeName: String,
        /** 档案的 `@` 默认 token。 */
        val token: String,
        val forceToken: Boolean = false,
        val agentKind: String? = null,
        val duty: String? = null,
        override val baseRev: Int? = null,
        override val actor: DeclarationActor,
        override val origin: DeclarationOrigin = DeclarationOrigin.AGENT_HUB,
    ) : GroupDeclarationCommand

    /**
     * **摘掉员工档案引用**（删除员工 / 把员工移出某个群）：席位回「未登记 agent」。
     *
     * 席位本身保留（`name` 不变）—— 撤席位是 [LeaveSeat] 的事。这样「员工档案删了」
     * 不会把这个人静默踢出所有群。
     */
    data class DetachEmployee(
        override val groupId: String,
        val agentId: String,
        override val baseRev: Int? = null,
        override val actor: DeclarationActor,
        override val origin: DeclarationOrigin = DeclarationOrigin.AGENT_HUB,
    ) : GroupDeclarationCommand
}

/** 命令被拒的原因。每一个都对应界面上**可读**的一句话，不许沉默。 */
enum class DeclarationRejection {
    GROUP_UNKNOWN,
    NOT_MEMBER,

    /** agent 发起但没带 `baseRev`（见 [GroupDeclarationCommand.baseRev]）。 */
    BASE_REV_REQUIRED,

    /** 目标席位不存在（`UpdateSeat` 例外：它会按入驻处理）。 */
    SEAT_MISSING,

    /** 名字为空 / 形状不合法 / 撞群内既有 token。 */
    NAME_INVALID,

    /** 组员草稿过了不 [TeamRosterValidation]（撞 token、超上限、重名…）。 */
    ROSTER_INVALID,

    /** 参数自身不自洽（例如 `UpdateSeat` 一个字段都没给）。 */
    ARGUMENT_INVALID,

    /** 元数据没通过 `validateGroupMeta`，落盘被拒（什么都没改）。 */
    PERSIST_REJECTED,

    /** 团队档案不存在（`teamId` 指向的那份已解散 / 从没建过）。 */
    TEAM_UNKNOWN,

    /** 员工档案不存在（`employeeId` 指向的那份已删除 / 从没建过）。 */
    EMPLOYEE_UNKNOWN,

    /**
     * 这个 agent 在本机已有员工档案（一个 agent 一份 —— 两份名字抢同一个身份）。
     *
     * 要改名走 `EmployeeProfileCommand.Update`，不要建第二份。
     */
    EMPLOYEE_EXISTS,

    /**
     * 这条席位是**档案化团队**（`teamId != null`）：名册归档案，群内整表替换一律拒绝。
     *
     * 放行它就是两个写入口写同一份名册 —— A 群改完 B 群不知道，同一个团队在两个群里
     * 显示两份组员。改这样的团队请走 `TeamProfileCommand`。
     */
    TEAM_PROFILE_BACKED,
}

sealed interface DeclarationResult {

    /** 已落盘并广播。[rev] = 落盘后的群 rev。 */
    data class Applied(
        val rev: Int,
        /** 入驻时解析出的执行目录 wsId；null = 未暴露/未设置（席位照常建立）。 */
        val resolvedWsId: String? = null,
    ) : DeclarationResult

    /** 什么都没改。[reason] 决定界面上提示哪句话。 */
    data class Rejected(val reason: DeclarationRejection) : DeclarationResult

    /** 并发冲突：调用方应提示「编制已被改成 rev=[actualRev]」并让人重新载入。 */
    data class Conflict(val baseRev: Int, val actualRev: Int) : DeclarationResult
}

/** 审计一行：谁、从哪、改了什么、rev 推到几。 */
data class DeclarationAuditEntry(
    val groupId: String,
    val command: String,
    val actorNodeId: String,
    val actorAgentId: String?,
    val origin: DeclarationOrigin,
    val before: String,
    val after: String,
    val rev: Int,
    val ts: Long,
)

/**
 * 编制审计环（有界、内存）。
 *
 * 落盘审计留给宿主侧（桌面壳有目录可写，手机没有）—— 在 `:app` 里造一条文件写路径
 * 就得先有端口，那是另一件事。这里先保证**每条成功命令恰好一行**，并在诊断与测试里
 * 可读；`agent` 写面上线时把这份环接到宿主的 jsonl 即可，判据与行数口径不用改。
 */
class DeclarationAuditLog(private val capacity: Int = 64) {

    private val entries = ArrayDeque<DeclarationAuditEntry>()

    fun record(entry: DeclarationAuditEntry) {
        entries.addLast(entry)
        while (entries.size > capacity) entries.removeFirst()
    }

    /** 最近 [limit] 条（默认全部），按时间正序。 */
    fun recent(limit: Int = capacity): List<DeclarationAuditEntry> =
        entries.toList().takeLast(limit.coerceAtLeast(0))

    fun clear() = entries.clear()
}

/**
 * 应用一条编制命令 —— 全部编制写入的唯一实现。
 *
 * @return [DeclarationResult]。**任何分支都不许**返回「成功但没落盘」：
 *   命令被接受的定义是 rev 已推进、广播已发、审计已记。
 */
internal fun RelayController.applyDeclaration(command: GroupDeclarationCommand): DeclarationResult {
    val state = _state.value
    val self = state.selfNodeId

    // 1. 群存在 + actor 有权改这个群。
    val meta = state.settings.groups.firstOrNull { it.groupId == command.groupId }
        ?: return DeclarationResult.Rejected(DeclarationRejection.GROUP_UNKNOWN)
    val actorNode = command.actor.actorNodeId
    if (actorNode !in meta.members) {
        return DeclarationResult.Rejected(DeclarationRejection.NOT_MEMBER)
    }
    val actorAgent = command.actor.agentIdOrNull
    if (command.actor is DeclarationActor.Agent) {
        // agent 只能代表**本机上自己那条席位**：替别的宿主/别的 agent 改编制 = 越权。
        if (actorNode != self) return DeclarationResult.Rejected(DeclarationRejection.NOT_MEMBER)
        val ownSeatPresent = meta.virtualAgents.orEmpty().any {
            it.hostNodeId == self && it.agentId.equals(actorAgent.orEmpty(), ignoreCase = true)
        }
        if (!ownSeatPresent) return DeclarationResult.Rejected(DeclarationRejection.NOT_MEMBER)
        if (command.baseRev == null) {
            return DeclarationResult.Rejected(DeclarationRejection.BASE_REV_REQUIRED)
        }
    }

    // 2. 并发：带了基线就严格比，不带就按当前 rev。
    val baseRev = command.baseRev
    if (baseRev != null && baseRev != meta.rev) {
        return DeclarationResult.Conflict(baseRev = baseRev, actualRev = meta.rev)
    }

    // 3~5. 按命令算新席位表。
    val outcome = when (command) {
        is GroupDeclarationCommand.JoinSeat -> planJoinSeat(meta, self, command)
        is GroupDeclarationCommand.LeaveSeat -> planLeaveSeat(meta, self, command)
        is GroupDeclarationCommand.UpdateSeat -> planUpdateSeat(meta, self, command)
        is GroupDeclarationCommand.SetTeamRoster -> planTeamRoster(meta, self, command)
        is GroupDeclarationCommand.AttachTeam -> planAttachTeam(meta, self, command)
        is GroupDeclarationCommand.DetachTeam -> planDetachTeam(meta, self, command)
        is GroupDeclarationCommand.AttachEmployee -> planAttachEmployee(meta, self, command)
        is GroupDeclarationCommand.DetachEmployee -> planDetachEmployee(meta, self, command)
    }
    val plan = when (outcome) {
        is DeclarationPlan.Rejected -> return DeclarationResult.Rejected(outcome.reason)
        is DeclarationPlan.Ready -> outcome
    }

    // 6. rev 推进。
    val updated = meta.copy(
        virtualAgents = plan.seats.distinct().ifEmpty { null },
        rev = meta.rev + 1,
        updatedBy = self,
        updatedTs = nowMillis(),
    )

    // 7. 预算自检 → 落库 → need 游标 → 逐成员广播（既有唯一入口）。
    commitGroupMetaUpdate(updated)

    // 落盘可能被 validateGroupMeta 挡下（commitGroupMetaUpdate 只 warn）——
    // 这里用状态回读确认 rev 真的推进了，不拿「调用过」当「落盘了」。
    val persisted = _state.value.settings.groups.firstOrNull { it.groupId == meta.groupId }
    if (persisted?.rev != updated.rev) {
        return DeclarationResult.Rejected(DeclarationRejection.PERSIST_REJECTED)
    }

    // 8. 审计。
    declarationAuditLog.record(
        DeclarationAuditEntry(
            groupId = meta.groupId,
            command = plan.commandName,
            actorNodeId = actorNode,
            actorAgentId = actorAgent,
            origin = command.origin,
            before = plan.before,
            after = plan.after,
            rev = updated.rev,
            ts = updated.updatedTs,
        ),
    )
    return DeclarationResult.Applied(rev = updated.rev, resolvedWsId = plan.resolvedWsId)
}

/** 计划结果：要么给出新席位表，要么给出拒绝原因 —— 中间不落任何状态。 */
private sealed interface DeclarationPlan {
    val commandName: String
    val before: String
    val after: String
    val resolvedWsId: String?

    data class Ready(
        override val commandName: String,
        override val before: String,
        override val after: String,
        val seats: List<VirtualAgentMember>,
        override val resolvedWsId: String? = null,
    ) : DeclarationPlan

    data class Rejected(val reason: DeclarationRejection) : DeclarationPlan {
        override val commandName: String get() = ""
        override val before: String get() = ""
        override val after: String get() = ""
        override val resolvedWsId: String? get() = null
    }
}

/** 本机席位的定位键口径（大小写不敏感；`hostNodeId` 由 selfNodeId 隐含）。 */
private fun GroupMeta.localSeat(selfNodeId: String, agentId: String): VirtualAgentMember? =
    virtualAgents.orEmpty().firstOrNull {
        it.hostNodeId == selfNodeId && it.agentId.equals(agentId.trim(), ignoreCase = true)
    }

/**
 * **只校验、不落盘**：这份名册放进 [groupId] 有没有**必须改的错**
 * （空 / 超长 / 非 slug / 名册内重名 / 超员）。
 *
 * 跨群命令（[applyTeamProfile]）必须在**写档案之前**把每个目标群都过一遍 —— 否则
 * 第一群成功、第二群失败时档案已经落盘，用户看到的是「有的群改了、有的没改」，
 * 而档案的名字/名册又是全局的：两个群从此显示两份组员。
 *
 * **撞群里既有 token 不在这里拒**：那是 [TeamRosterValidation.resolveNames] 的事
 * （就地取本群别名 `coder-2`），与员工档案同一条纪律。所以本函数不需要 token 池 ——
 * 留着池参数只会让下一个调用方以为「传了池就会拦撞名」。
 *
 * @return null = 通过；非 null = 该群会拒这份名册的原因。
 */
internal fun RelayController.declarationRosterIssue(
    groupId: String,
    roster: List<TeamMemberDraft>,
): DeclarationRejection? {
    val state = _state.value
    val self = state.selfNodeId
    val meta = state.settings.groups.firstOrNull { it.groupId == groupId }
        ?: return DeclarationRejection.GROUP_UNKNOWN
    if (!meta.members.contains(self)) return DeclarationRejection.NOT_MEMBER
    return if (TeamRosterValidation.validate(roster).isEmpty()) null
    else DeclarationRejection.ROSTER_INVALID
}

/** 席位在审计里的一行摘要（名字 + 形态 + 团队名 + 员工名 + 组员），够定位「改了什么」。 */
private fun VirtualAgentMember.auditShape(): String = buildString {
    append(name).append('[').append(if (isTeam) EntityTypes.TEAM else EntityTypes.SINGLE).append(']')
    teamName?.takeIf { it.isNotBlank() }?.let { append('#').append(it) }
    employeeName?.takeIf { it.isNotBlank() }?.let { append('@').append(it) }
    if (teamMembers.isNotEmpty()) {
        append('{').append(teamMembers.joinToString(",") { "${it.name}:${it.role}" }).append('}')
    }
}

/**
 * token 池：群内**现在就能被 `@`** 的东西（席位名 + agentId + 其它团队的组员名 + 参与者键）。
 *
 * [excludeSeat] 的旧组员名要剔出去 —— 它们正被这份草稿整体替换，留着会让「沿用旧名」
 * 误报冲突；领队自己的名字留在池里（组员撞领队 = 影子领队，必须拦）。
 */
private fun RelayController.declarationTokenPool(
    meta: GroupMeta,
    selfNodeId: String,
    excludeSeat: VirtualAgentMember? = null,
): Set<String> {
    val staleMemberNames = excludeSeat?.teamMembers
        ?.flatMap { listOf(it.name, it.name.lowercase()) }
        ?.toSet()
        .orEmpty()
    val pool = buildSet {
        for (va in meta.virtualAgents.orEmpty()) {
            add(va.name)
            add(va.agentId)
            val replaced = excludeSeat != null && va.hostNodeId == selfNodeId &&
                va.agentId.equals(excludeSeat.agentId, ignoreCase = true)
            if (!replaced) va.teamMembers.forEach { add(it.name) }
        }
        addAll(
            groupParticipantRows(_state.value.senderContext(ConvId.group(meta.groupId))).mentionKeys,
        )
    }
    return pool - staleMemberNames
}

/**
 * 入驻：已有席位沿用旧名（`@` token 不漂），新席位用**档案 token**（没有档案才按
 * `kind-职责` 派生），两种都过群内唯一；席位同时挂上员工档案引用与名字快照。
 *
 * ## 入驻必须顺手带上员工档案
 *
 * 「群信息 → 邀请 agent 入驻」是**唯一**能把一个已有员工加进第二个群的入口（员工详情页
 * 只有名字/职责/档位，没有加群）。如果这条路上的席位不带档案引用，症状是**两端各说各话**：
 * 宿主本机的投影按 agentId 直接读档案 ⇒ 立刻显示员工名；手机只有席位快照 ⇒ 还是
 * `kind-职责`。更贵的是后续 —— `EmployeeProfileWritePath` 重建快照时按 `employeeId`
 * 找引用群，这条席位不在其中，于是**改名永远到不了它**，且没有任何报错。
 *
 * 所以这里补的是「席位一经产生就属于那个员工」，而不是让档案写路径去猜哪些席位是它的。
 * 名字的取法分三种，各有理由：
 * - 新席位：档案 token（撞名则本群别名，与 `AttachEmployee` 同一套）；没有档案才按
 *   `kind-职责` 派生（D13）；
 * - 已有席位：**保留它现在的名字**。它可能就是这一群正在用的 `@` token，改名等于让全群的
 *   输入习惯断掉（只有档案显式改 token 才 `forceToken`）；
 * - 显式 `nameOverride`（`UpdateSeat` / `AttachEmployee` 带名入驻）：以它为准。
 */
private fun RelayController.planJoinSeat(
    meta: GroupMeta,
    self: String,
    cmd: GroupDeclarationCommand.JoinSeat,
    /** 显式指定的名字（`UpdateSeat` 落到「按入驻处理」时带上）；null = 按 D13 派生。 */
    nameOverride: String? = null,
): DeclarationPlan {
    val canonicalAgentId = cmd.agentId.trim()
    if (canonicalAgentId.isEmpty()) return DeclarationPlan.Rejected(DeclarationRejection.ARGUMENT_INVALID)
    val previous = meta.localSeat(self, canonicalAgentId)
    // 本机档案（宿主才有；手机端 employeeProfiles 恒空 ⇒ 这条路对手机是空操作）。
    val employee = _state.value.settings.employeeProfiles.byAgentIdentity(canonicalAgentId)
    val taken = meta.virtualAgents.orEmpty().map { it.name }.toMutableSet()
    val name = when {
        nameOverride != null -> {
            if (nameOverride != AgentMemberName.slug(nameOverride)) {
                return DeclarationPlan.Rejected(DeclarationRejection.NAME_INVALID)
            }
            // 改名/带名入驻**不替换名册** ⇒ 全池都算数（组员名仍在用，不许被领队抢走）；
            // 只有「自己的旧名」可以复用。
            val pool = declarationTokenPool(meta, self).map { it.trim().lowercase() }.toSet()
            val own = previous?.name
            if (nameOverride.lowercase() in pool && !nameOverride.equals(own, ignoreCase = true)) {
                return DeclarationPlan.Rejected(DeclarationRejection.NAME_INVALID)
            }
            nameOverride
        }
        previous != null -> previous.name
        employee != null -> AgentMemberName.unique(employee.seatToken(cmd.agentKind, cmd.duty), taken)
        else -> AgentMemberName.unique(
            // D13：职责缺省/域外一律归一到单兵默认（`assistant`），不发明第四种拼写。
            AgentMemberName.base(cmd.agentKind, canonicalAgentId, AgentDuties.normalize(cmd.duty)),
            taken,
        )
    }
    val resolvedWsId = hostDirectoryPort?.resolveForGroup(meta.groupId, canonicalAgentId)
    val declared = declarationFor(
        agentId = canonicalAgentId,
        hostNodeId = self,
        name = name,
        defaultWsId = resolvedWsId,
        previous = previous,
    )
    // 档案是权威、席位只是引用 + 快照：入驻这条路一律按档案重写一次（值相同就是 no-op）。
    // 只补不猜 —— 没有档案就一个字都不写，席位仍是「未登记 agent」。
    val withEmployee = employee?.let {
        declared.copy(employeeId = it.employeeId, employeeName = it.name)
    } ?: declared
    val seat = withEmployee.withPointerProjection(
        defaultWsId = resolvedWsId,
        allowedWorkspaces = resolvedWsId?.let { wsId ->
            listOf(
                GroupWorkspaceInfo(
                    wsId = wsId,
                    title = _state.value.agentWorkspaces.values.firstOrNull { it.wsId == wsId }?.wsTitle
                        ?: hostDirectoryPort?.snapshot().pointedDirectoryOf(meta.groupId)?.displayName
                        ?: "",
                ),
            )
        } ?: emptyList(),
    )
    val seats = meta.virtualAgents.orEmpty()
        .filterNot { it.hostNodeId == self && it.agentId.equals(canonicalAgentId, ignoreCase = true) } + seat
    return DeclarationPlan.Ready(
        commandName = "join",
        before = previous?.auditShape() ?: "-",
        after = seat.auditShape(),
        seats = seats,
        resolvedWsId = resolvedWsId,
    )
}

/** 撤回：只摘本机那一条（机主主权），别的宿主的同名 agent 一条都不碰。 */
private fun RelayController.planLeaveSeat(
    meta: GroupMeta,
    self: String,
    cmd: GroupDeclarationCommand.LeaveSeat,
): DeclarationPlan {
    val seat = meta.localSeat(self, cmd.agentId)
        ?: return DeclarationPlan.Rejected(DeclarationRejection.SEAT_MISSING)
    val seats = meta.virtualAgents.orEmpty().filterNot {
        it.hostNodeId == self && it.agentId.equals(seat.agentId, ignoreCase = true)
    }
    return DeclarationPlan.Ready(
        commandName = "leave",
        before = seat.auditShape(),
        after = "-",
        seats = seats,
    )
}

/**
 * 编辑：改名 / 改职责。
 *
 * 席位不存在时**按入驻处理**（`UpdateSeat` 是幂等的「让它变成这样」，不是「必须已存在」）；
 * 已有席位 `agentId` 与团队字段一概不动，`name` 过 token 池（剔掉自己的旧名）。
 */
private fun RelayController.planUpdateSeat(
    meta: GroupMeta,
    self: String,
    cmd: GroupDeclarationCommand.UpdateSeat,
): DeclarationPlan {
    val newName = cmd.name?.trim()
    if (newName != null && newName.isEmpty()) {
        return DeclarationPlan.Rejected(DeclarationRejection.ARGUMENT_INVALID)
    }
    if (newName == null && cmd.duty == null) {
        return DeclarationPlan.Rejected(DeclarationRejection.ARGUMENT_INVALID)
    }
    val seat = meta.localSeat(self, cmd.agentId)
        ?: return planJoinSeat(
            meta = meta,
            self = self,
            GroupDeclarationCommand.JoinSeat(
                groupId = meta.groupId,
                agentId = cmd.agentId,
                agentKind = cmd.agentKind,
                duty = cmd.duty,
                baseRev = cmd.baseRev,
                actor = cmd.actor,
                origin = cmd.origin,
            ),
            // 指定了名字就按指定的建：`UpdateSeat` 的语义是「让它变成这样」，不是「只在已有席位上生效」。
            nameOverride = newName,
        )
    if (newName == null) {
        // 只给了 duty：对已有席位是 no-op（职责只影响新建席位的默认名）。
        return DeclarationPlan.Ready(
            commandName = "update",
            before = seat.auditShape(),
            after = seat.auditShape(),
            seats = meta.virtualAgents.orEmpty(),
        )
    }
    if (newName == seat.name) {
        return DeclarationPlan.Ready(
            commandName = "update",
            before = seat.auditShape(),
            after = seat.auditShape(),
            seats = meta.virtualAgents.orEmpty(),
        )
    }
    if (newName != AgentMemberName.slug(newName)) {
        return DeclarationPlan.Rejected(DeclarationRejection.NAME_INVALID)
    }
    // 改名不替换名册 ⇒ 全池参与（含自己团队的组员名），只放行自己的旧名。
    val pool = declarationTokenPool(meta, self).map { it.trim().lowercase() }.toSet()
    if (newName.lowercase() in pool && !newName.equals(seat.name, ignoreCase = true)) {
        return DeclarationPlan.Rejected(DeclarationRejection.NAME_INVALID)
    }
    val renamed = seat.copy(name = newName)
    val seats = meta.virtualAgents.orEmpty().map { va ->
        val isTarget = va.hostNodeId == self && va.agentId.equals(seat.agentId, ignoreCase = true) &&
            va.defaultWsId == seat.defaultWsId
        if (isTarget) renamed else va
    }
    return DeclarationPlan.Ready(
        commandName = "update",
        before = seat.auditShape(),
        after = renamed.auditShape(),
        seats = seats,
    )
}

/**
 * 团队编制：整份替换组员清单。
 *
 * 与既有行为逐条对齐（`TeamDeclarationWriteTest` 是这批判据的钉子）：
 * - 名册过 [TeamRosterValidation.validate]（空 / 超长 / 非 slug / 名册内重名 / 超员）；
 * - 撞群里既有 token 就地取**本群别名**（`coder-2`），不减人不拒绝；
 * - 目标席位 `entityType`/`teamMembers` 改，`name`/白名单/`defaultWsId` 原样保留；
 * - 同一 agent 的其它席位收敛成单兵（旧数据的「一个 agent 多个团队」在这里收口）；
 * - null 或空清单 = 关闭。
 */
private fun RelayController.planTeamRoster(
    meta: GroupMeta,
    self: String,
    cmd: GroupDeclarationCommand.SetTeamRoster,
): DeclarationPlan {
    val seat = meta.localSeat(self, cmd.agentId)
        ?: return DeclarationPlan.Rejected(DeclarationRejection.SEAT_MISSING)
    // 档案化团队的席位：名册归档案，群内整表替换一律拒绝（否则两个入口写同一份名册，
    // A 群改完 B 群不知道 —— 同一个团队在两个群里显示两份组员）。
    if (seat.teamId != null) {
        return DeclarationPlan.Rejected(DeclarationRejection.TEAM_PROFILE_BACKED)
    }
    val roster = cmd.roster.orEmpty()
    if (TeamRosterValidation.validate(roster).isNotEmpty()) {
        return DeclarationPlan.Rejected(DeclarationRejection.ROSTER_INVALID)
    }
    // 名字的最终形态只由这里决定：UI 只预览，写路径说了算（预览与落盘同一份纯函数）。
    val names = TeamRosterValidation.resolveNames(
        roster,
        declarationTokenPool(meta, self, excludeSeat = seat),
    )
    val enabling = roster.isNotEmpty()
    val seats = meta.virtualAgents.orEmpty().map { va ->
        val sameAgent = va.hostNodeId == self && va.agentId.equals(seat.agentId, ignoreCase = true)
        when {
            // 目标席位 = 定位到的那一条（旧数据里同 agentId 的第二席位靠 defaultWsId 区分）。
            sameAgent && va.defaultWsId == seat.defaultWsId -> va.copy(
                entityType = if (enabling) EntityTypes.TEAM else EntityTypes.SINGLE,
                teamMembers = if (enabling) {
                    roster.mapIndexed { i, m -> TeamMemberInfo(m.agentId, names[i].name, m.role) }
                } else {
                    emptyList()
                },
            )
            sameAgent -> va.copy(entityType = EntityTypes.SINGLE, teamMembers = emptyList())
            else -> va
        }
    }
    val after = seats.firstOrNull {
        it.hostNodeId == self && it.agentId.equals(seat.agentId, ignoreCase = true) &&
            it.defaultWsId == seat.defaultWsId
    }?.auditShape() ?: "-"
    return DeclarationPlan.Ready(
        commandName = if (enabling) "roster.set" else "roster.clear",
        before = seat.auditShape(),
        after = after,
        seats = seats,
    )
}

/**
 * 挂上团队档案：席位转团队形态、写快照（`teamId` / `teamName` / 组员），
 * 同 agent 的其它席位收敛成单兵（每群一支团队的既有不变量）。
 *
 * ## 为什么席位不存在时**就地入驻**
 *
 * 「群里添加一个团队」是**一次动作**：用户选的是「开发团队」这个人格化的整体，不是
 * 「先加一个 agent、再加四个组员」。要求先逐个加人，等于把档案的意义抹掉。
 * 入驻的名字派生沿用 [planJoinSeat]（`kind-职责` + 群内唯一），团队**名字**不占用 `@` token。
 */
private fun RelayController.planAttachTeam(
    meta: GroupMeta,
    self: String,
    cmd: GroupDeclarationCommand.AttachTeam,
): DeclarationPlan {
    val teamId = cmd.teamId.trim()
    val teamName = cmd.teamName.trim()
    if (teamId.isEmpty() || teamName.isEmpty() || cmd.roster.isEmpty()) {
        return DeclarationPlan.Rejected(DeclarationRejection.ARGUMENT_INVALID)
    }
    val existing = meta.localSeat(self, cmd.agentId)
    // 名册的「必须改的错」先拦；撞群里既有 token 不算错，就地取别名（见下）。
    if (TeamRosterValidation.validate(cmd.roster).isNotEmpty()) {
        return DeclarationPlan.Rejected(DeclarationRejection.ROSTER_INVALID)
    }
    // **同一份档案在不同群可以叫不同名字**：`name` 只是本群 `@` token，身份是 `agentId`。
    // 池里剔掉本席位的旧组员名（它们正被这份名册整体替换，留着会让「沿用旧名」误判成撞名）。
    val names = TeamRosterValidation.resolveNames(
        cmd.roster,
        declarationTokenPool(meta, self, excludeSeat = existing),
    )

    var resolvedWsId: String? = null
    val seatsBefore = if (existing == null) {
        val joined = planJoinSeat(
            meta = meta,
            self = self,
            GroupDeclarationCommand.JoinSeat(
                groupId = meta.groupId,
                agentId = cmd.agentId,
                agentKind = cmd.agentKind,
                duty = cmd.duty,
                baseRev = cmd.baseRev,
                actor = cmd.actor,
                origin = cmd.origin,
            ),
        )
        when (joined) {
            is DeclarationPlan.Rejected -> return joined
            is DeclarationPlan.Ready -> {
                resolvedWsId = joined.resolvedWsId
                joined.seats
            }
        }
    } else {
        meta.virtualAgents.orEmpty()
    }

    // 目标席位：既有席位按 `defaultWsId` 定位（旧数据里同 agent 可能有多条），
    // 新入驻的取刚追加的那一条。
    val target = if (existing != null) {
        seatsBefore.firstOrNull {
            it.hostNodeId == self && it.agentId.equals(existing.agentId, ignoreCase = true) &&
                it.defaultWsId == existing.defaultWsId
        }
    } else {
        seatsBefore.lastOrNull {
            it.hostNodeId == self && it.agentId.equals(cmd.agentId.trim(), ignoreCase = true)
        }
    } ?: return DeclarationPlan.Rejected(DeclarationRejection.SEAT_MISSING)

    val attached = target.copy(
        entityType = EntityTypes.TEAM,
        teamMembers = cmd.roster.mapIndexed { i, m -> TeamMemberInfo(m.agentId, names[i].name, m.role) },
        teamId = teamId,
        teamName = teamName,
    )
    val seats = seatsBefore.map { va ->
        when {
            va.hostNodeId != self -> va
            !va.agentId.equals(target.agentId, ignoreCase = true) -> va
            va.defaultWsId == target.defaultWsId -> attached
            // 同一 agent 的其它席位：收敛成单兵（每群最多一支团队，与 planTeamRoster 同一条）。
            else -> va.copy(
                entityType = EntityTypes.SINGLE,
                teamMembers = emptyList(),
                teamId = null,
                teamName = null,
            )
        }
    }
    return DeclarationPlan.Ready(
        commandName = "team.attach",
        before = existing?.auditShape() ?: "-",
        after = attached.auditShape(),
        seats = seats,
        resolvedWsId = resolvedWsId,
    )
}

/**
 * 摘掉档案引用（解散团队 / 把团队移出某个群）：席位回单兵，`teamId` / `teamName` 清空。
 *
 * 席位本身保留 —— 撤席位是 `LeaveSeat` 的事。这里不删席位，是因为「团队解散」之后
 * 那个人还在群里、还能被 `@`，把席位一起摘掉会顺手删掉一个成员关系。
 */
private fun RelayController.planDetachTeam(
    meta: GroupMeta,
    self: String,
    cmd: GroupDeclarationCommand.DetachTeam,
): DeclarationPlan {
    val seat = meta.localSeat(self, cmd.agentId)
        ?: return DeclarationPlan.Rejected(DeclarationRejection.SEAT_MISSING)
    val detached = seat.copy(
        entityType = EntityTypes.SINGLE,
        teamMembers = emptyList(),
        teamId = null,
        teamName = null,
    )
    val seats = meta.virtualAgents.orEmpty().map { va ->
        val sameSeat = va.hostNodeId == self &&
            va.agentId.equals(seat.agentId, ignoreCase = true) &&
            va.defaultWsId == seat.defaultWsId
        if (sameSeat) detached else va
    }
    return DeclarationPlan.Ready(
        commandName = "team.detach",
        before = seat.auditShape(),
        after = detached.auditShape(),
        seats = seats,
    )
}

/**
 * 挂上员工档案：写引用与名字快照，席位名保持「本群 `@` token」。
 *
 * ## 为什么新席位要 `unique` 而旧席位不改名
 *
 * 员工档案的 token 是**跨群一致**的默认值；但 `@` token 必须群内唯一。撞名时给本群一个
 * 别名（`dsh-coder-2`）而不是拒绝入驻 —— 拒绝会让「员工先建好、加进群」这件事卡在
 * 一个纯粹由别人占名造成的原因上。已存在的席位则**保留它现在的名字**：它可能就是用户
 * 正在用的别名，换掉等于让群里的 `@` 习惯断掉（只有档案显式改 token 才 `forceToken`）。
 *
 * 员工名（可含中文）只进快照，不占用 token 池 —— 与团队名同一条纪律。
 */
private fun RelayController.planAttachEmployee(
    meta: GroupMeta,
    self: String,
    cmd: GroupDeclarationCommand.AttachEmployee,
): DeclarationPlan {
    val employeeId = cmd.employeeId.trim()
    val employeeName = cmd.employeeName.trim()
    val token = cmd.token.trim()
    if (employeeId.isEmpty() || employeeName.isEmpty() || token.isEmpty()) {
        return DeclarationPlan.Rejected(DeclarationRejection.ARGUMENT_INVALID)
    }
    // 档案 token 必须是已归一的形态：库里的脏 token 会产出「@ 了却叫不醒」的席位。
    if (token != AgentMemberName.slug(token)) {
        return DeclarationPlan.Rejected(DeclarationRejection.NAME_INVALID)
    }
    val canonicalAgentId = cmd.agentId.trim()
    if (canonicalAgentId.isEmpty()) {
        return DeclarationPlan.Rejected(DeclarationRejection.ARGUMENT_INVALID)
    }

    val existing = meta.localSeat(self, canonicalAgentId)
    if (existing == null) {
        // 就地入驻：名字用档案 token，撞名则本群别名（不拒绝）。
        val taken = meta.virtualAgents.orEmpty().map { it.name }.toSet()
        val seatName = AgentMemberName.unique(token, taken)
        val joined = planJoinSeat(
            meta = meta,
            self = self,
            GroupDeclarationCommand.JoinSeat(
                groupId = meta.groupId,
                agentId = canonicalAgentId,
                agentKind = cmd.agentKind,
                duty = cmd.duty,
                baseRev = cmd.baseRev,
                actor = cmd.actor,
                origin = cmd.origin,
            ),
            nameOverride = seatName,
        )
        val seats = when (joined) {
            is DeclarationPlan.Rejected -> return joined
            is DeclarationPlan.Ready -> joined.seats
        }
        val seat = seats.lastOrNull {
            it.hostNodeId == self && it.agentId.equals(canonicalAgentId, ignoreCase = true)
        } ?: return DeclarationPlan.Rejected(DeclarationRejection.SEAT_MISSING)
        val attached = seat.copy(employeeId = employeeId, employeeName = employeeName)
        return DeclarationPlan.Ready(
            commandName = "employee.attach",
            before = "-",
            after = attached.auditShape(),
            seats = seats.map {
                if (it.hostNodeId == self && it.agentId.equals(canonicalAgentId, ignoreCase = true) &&
                    it.defaultWsId == seat.defaultWsId
                ) {
                    attached
                } else {
                    it
                }
            },
            resolvedWsId = joined.resolvedWsId,
        )
    }

    val seatName = if (cmd.forceToken) {
        val others = meta.virtualAgents.orEmpty()
            .filterNot {
                it.hostNodeId == self && it.agentId.equals(existing.agentId, ignoreCase = true) &&
                    it.defaultWsId == existing.defaultWsId
            }
            .map { it.name }
            .toSet()
        AgentMemberName.unique(token, others)
    } else {
        existing.name
    }
    val attached = existing.copy(name = seatName, employeeId = employeeId, employeeName = employeeName)
    val seats = meta.virtualAgents.orEmpty().map { va ->
        val sameSeat = va.hostNodeId == self &&
            va.agentId.equals(existing.agentId, ignoreCase = true) &&
            va.defaultWsId == existing.defaultWsId
        if (sameSeat) attached else va
    }
    return DeclarationPlan.Ready(
        commandName = "employee.attach",
        before = existing.auditShape(),
        after = attached.auditShape(),
        seats = seats,
    )
}

/** 摘掉员工档案引用：席位与名字都保留，只回「未登记 agent」。 */
private fun RelayController.planDetachEmployee(
    meta: GroupMeta,
    self: String,
    cmd: GroupDeclarationCommand.DetachEmployee,
): DeclarationPlan {
    val seat = meta.localSeat(self, cmd.agentId)
        ?: return DeclarationPlan.Rejected(DeclarationRejection.SEAT_MISSING)
    val detached = seat.copy(employeeId = null, employeeName = null)
    val seats = meta.virtualAgents.orEmpty().map { va ->
        val sameSeat = va.hostNodeId == self &&
            va.agentId.equals(seat.agentId, ignoreCase = true) &&
            va.defaultWsId == seat.defaultWsId
        if (sameSeat) detached else va
    }
    return DeclarationPlan.Ready(
        commandName = "employee.detach",
        before = seat.auditShape(),
        after = detached.auditShape(),
        seats = seats,
    )
}
