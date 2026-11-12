// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.nowMillis
import com.kapp.tenon.core.protocol.AgentDuties
import com.kapp.tenon.core.protocol.AgentMemberName
import com.kapp.tenon.core.protocol.Limits
import com.kapp.tenon.core.protocol.sameAgent
import kotlin.random.Random

/**
 * 员工档案的**唯一写路径**（员工先创建、群只负责添加）。
 *
 * ## 两层，各守一件事
 *
 * 1. **档案层**（本文件）：名字、`@` token、职责的唯一真相，住 `RelaySettings.employeeProfiles`；
 * 2. **席位层**（[GroupDeclarationWritePath]）：每个群一条席位，靠 `AttachEmployee` /
 *    `DetachEmployee` 写引用与快照 —— 仍然是**那条**席位写路径（rev 推进、广播、审计一份不多）。
 *
 * 于是「改一次员工名」= 一次档案写入 + 每个引用群各一次 `AttachEmployee`：每群各自 rev+1、
 * 各自广播。**不是跨群事务** —— 某群失败不回滚别群，结果里如实带
 * [EmployeeProfileResult.Applied.failedGroupIds]，下次重建收敛。
 *
 * ## 「创建员工」不是「创建 agent」
 *
 * agent 是「这台机器上真的有一个进程在跑」，身份来自宣告；本文件只做**登记 + 命名**。
 * 所以 [Create] 会尽量确认 [EmployeeProfileCommand.Create.agentId] 在本机台账里：
 * 台账**有条目时**必须命中（防止在已知 agent 之外瞎造幽灵员工）；台账为空时放行
 * （手机、冷启动、测试都没有运行时条目 —— 那时拒绝只会让人连名字都存不下来），
 * 界面据此如实提示「该 agent 尚未上报」。
 */
sealed interface EmployeeProfileCommand {

    val actor: DeclarationActor
    val origin: DeclarationOrigin

    /**
     * 建一份员工档案，并**一次加进若干群**（每群各建/复用一条席位）。
     *
     * 不给 [groupIds] = 只建档、不进任何群（「先把人建好，之后再决定进哪个群」）。
     *
     * @param token `@` 默认 token；null = 由名字派生（`slug(名字)`，空则回落 `kind-职责`），
     *   再在本机档案池里取唯一。给了就必须已是归一形态（否则拒绝，不静默改写）。
     * @param employeeId 测试可注入；生产传 null 由本机分配（`emp-…`）。
     */
    data class Create(
        val name: String,
        val agentId: String,
        val duty: String? = null,
        val groupIds: List<String> = emptyList(),
        val agentKind: String? = null,
        val token: String? = null,
        val employeeId: String? = null,
        override val actor: DeclarationActor,
        override val origin: DeclarationOrigin = DeclarationOrigin.AGENT_HUB,
    ) : EmployeeProfileCommand

    /**
     * 改名 / 改 token / 改职责。改动**对所有引用群同时生效**。
     *
     * **改 [name] 默认不动 token**：token 是 `@` 后面要打的东西，一漂群里所有人的输入习惯就断。
     * 要改 token 必须显式给 —— 那是对所有引用群的显式重建（含本群别名）。
     */
    data class Update(
        val employeeId: String,
        val name: String? = null,
        val token: String? = null,
        val duty: String? = null,
        override val actor: DeclarationActor,
        override val origin: DeclarationOrigin = DeclarationOrigin.AGENT_HUB,
    ) : EmployeeProfileCommand

    /** 把**已有**员工加进更多群（不重建档案）。 */
    data class AddToGroups(
        val employeeId: String,
        val groupIds: List<String>,
        override val actor: DeclarationActor,
        override val origin: DeclarationOrigin = DeclarationOrigin.AGENT_HUB,
    ) : EmployeeProfileCommand

    /**
     * 删除档案（员工「下编」）：从所有引用群摘掉员工引用，**席位保留**。
     *
     * 席位保留是刻意的：删档案不等于把这个人踢出群。要踢人走 `LeaveSeat`。
     */
    data class Forget(
        val employeeId: String,
        override val actor: DeclarationActor,
        override val origin: DeclarationOrigin = DeclarationOrigin.AGENT_HUB,
    ) : EmployeeProfileCommand
}

/** 档案命令的结果。`Applied` = 档案已落盘；群级失败与别名群单独列出，不假装全绿。 */
sealed interface EmployeeProfileResult {

    /**
     * @param groupIds 实际写成功的群（顺序与请求一致）。
     * @param failedGroupIds 预校验通过但席位写入失败的群（并发/落盘被拒）；空 = 全绿。
     * @param aliasedGroupIds 席位名与档案 token 不同的群（本群撞名 ⇒ 别名）。
     *   界面要据此提示「它在 A 群叫 dsh-coder-2」，不许静默。
     */
    data class Applied(
        val employeeId: String,
        val name: String,
        val token: String,
        val groupIds: List<String>,
        val failedGroupIds: List<String> = emptyList(),
        val aliasedGroupIds: List<String> = emptyList(),
    ) : EmployeeProfileResult

    data class Rejected(val reason: DeclarationRejection) : EmployeeProfileResult
}

/** 应用一条档案命令 —— 员工档案写入的**唯一实现**。 */
public fun RelayController.applyEmployeeProfile(
    command: EmployeeProfileCommand,
): EmployeeProfileResult {
    val state = _state.value
    val self = state.selfNodeId
    val actorNode = command.actor.actorNodeId

    // agent 发起时只能代表本机（与 applyDeclaration / applyTeamProfile 同一条本机主权）。
    if (command.actor is DeclarationActor.Agent && actorNode != self) {
        return EmployeeProfileResult.Rejected(DeclarationRejection.NOT_MEMBER)
    }

    val profiles = state.settings.employeeProfiles
    return when (command) {
        is EmployeeProfileCommand.Create -> createEmployee(self, actorNode, command, profiles)
        is EmployeeProfileCommand.Update -> updateEmployee(self, actorNode, command, profiles)
        is EmployeeProfileCommand.AddToGroups -> addEmployeeToGroups(self, actorNode, command, profiles)
        is EmployeeProfileCommand.Forget -> forgetEmployee(self, actorNode, command, profiles)
    }
}

private fun RelayController.createEmployee(
    self: String,
    actorNode: String,
    cmd: EmployeeProfileCommand.Create,
    profiles: List<EmployeeProfile>,
): EmployeeProfileResult {
    val name = cmd.name.trim()
    if (name.isEmpty() || name.length > Limits.NICK) {
        return EmployeeProfileResult.Rejected(DeclarationRejection.NAME_INVALID)
    }
    val agentId = cmd.agentId.trim()
    if (agentId.isEmpty()) return EmployeeProfileResult.Rejected(DeclarationRejection.ARGUMENT_INVALID)
    // 一个 agent 在本机只有一份档案：再建就是两份名字抢同一个身份。
    // 用 sameAgent（前缀归一）而不是精确相等 —— 否则 `dsh` 与 `dsh-029bbbf3` 会落成两份，
    // 而投影会把它们并到同一张卡上，于是同一张卡出现两个员工名。
    if (profiles.any { sameAgent(it.agentId, null, agentId) }) {
        return EmployeeProfileResult.Rejected(DeclarationRejection.EMPLOYEE_EXISTS)
    }
    // 台账**有条目时**必须命中（防止幽灵员工）；空台账放行（手机/冷启动/测试）。
    if (!agentIsKnownOrUnknown(self, agentId)) {
        return EmployeeProfileResult.Rejected(DeclarationRejection.EMPLOYEE_UNKNOWN)
    }

    val taken = profiles.map { it.token }.toSet()
    val token = cmd.token?.trim()?.takeIf { it.isNotEmpty() }?.let { explicit ->
        if (explicit != AgentMemberName.slug(explicit)) {
            return EmployeeProfileResult.Rejected(DeclarationRejection.NAME_INVALID)
        }
        if (explicit in taken) return EmployeeProfileResult.Rejected(DeclarationRejection.NAME_INVALID)
        explicit
    } ?: defaultEmployeeToken(
        name = name,
        agentKind = cmd.agentKind ?: agentKindOf(self, agentId),
        agentId = agentId,
        duty = cmd.duty,
        taken = taken,
    )

    val employeeId = cmd.employeeId?.trim()?.takeIf { it.isNotEmpty() } ?: allocateEmployeeId()
    if (profiles.any { it.employeeId == employeeId }) {
        // 注入的 id 撞了既是测试写错，也说明调用方在乱造键：拒绝而不是覆盖别人的档案。
        return EmployeeProfileResult.Rejected(DeclarationRejection.ARGUMENT_INVALID)
    }
    val groups = cmd.groupIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    val denied = employeeTargetGroups(self, actorNode, groups)
    if (denied != null) return EmployeeProfileResult.Rejected(denied)

    val profile = EmployeeProfile(
        employeeId = employeeId,
        agentId = agentId,
        name = name,
        token = token,
        duty = AgentDuties.normalize(cmd.duty),
        rev = 1,
        updatedTs = nowMillis(),
    )
    // 先落档案、再逐群挂席位：反过来会让对端先看到指向不存在档案的引用。
    persistEmployeeProfiles(profiles + profile)
    val (applied, failed, aliased) = attachEmployeeToGroups(self, profile, groups, cmd.actor, cmd.origin, null)
    return EmployeeProfileResult.Applied(employeeId, name, token, applied, failed, aliased)
}

private fun RelayController.updateEmployee(
    self: String,
    actorNode: String,
    cmd: EmployeeProfileCommand.Update,
    profiles: List<EmployeeProfile>,
): EmployeeProfileResult {
    val profile = profiles.byEmployeeId(cmd.employeeId)
        ?: return EmployeeProfileResult.Rejected(DeclarationRejection.EMPLOYEE_UNKNOWN)
    if (cmd.name == null && cmd.token == null && cmd.duty == null) {
        return EmployeeProfileResult.Rejected(DeclarationRejection.ARGUMENT_INVALID)
    }
    val newName = cmd.name?.trim()
    if (newName != null && (newName.isEmpty() || newName.length > Limits.NICK)) {
        return EmployeeProfileResult.Rejected(DeclarationRejection.NAME_INVALID)
    }
    val newToken = cmd.token?.trim()?.takeIf { it.isNotEmpty() }
    if (newToken != null) {
        if (newToken != AgentMemberName.slug(newToken)) {
            return EmployeeProfileResult.Rejected(DeclarationRejection.NAME_INVALID)
        }
        // 档案池里撞别人的 token ⇒ 拒绝（不替用户改名）。
        if (profiles.any { it.employeeId != profile.employeeId && it.token == newToken }) {
            return EmployeeProfileResult.Rejected(DeclarationRejection.NAME_INVALID)
        }
    }
    val updated = profile.copy(
        name = newName ?: profile.name,
        token = newToken ?: profile.token,
        duty = cmd.duty?.let { AgentDuties.normalize(it) } ?: profile.duty,
        rev = profile.rev + 1,
        updatedTs = nowMillis(),
    )
    val groups = groupsReferencingEmployee(updated.employeeId)
    val denied = employeeTargetGroups(self, actorNode, groups)
    if (denied != null) return EmployeeProfileResult.Rejected(denied)

    persistEmployeeProfiles(profiles.map { if (it.employeeId == updated.employeeId) updated else it })
    // 显式改 token 才覆盖本群席位名（forceToken）：否则保留既有别名，只刷新显示名快照。
    val (applied, failed, aliased) = attachEmployeeToGroups(
        self = self,
        profile = updated,
        groupIds = groups,
        actor = cmd.actor,
        origin = cmd.origin,
        baseRev = null,
        forceToken = newToken != null,
    )
    return EmployeeProfileResult.Applied(updated.employeeId, updated.name, updated.token, applied, failed, aliased)
}

private fun RelayController.addEmployeeToGroups(
    self: String,
    actorNode: String,
    cmd: EmployeeProfileCommand.AddToGroups,
    profiles: List<EmployeeProfile>,
): EmployeeProfileResult {
    val profile = profiles.byEmployeeId(cmd.employeeId)
        ?: return EmployeeProfileResult.Rejected(DeclarationRejection.EMPLOYEE_UNKNOWN)
    val groups = cmd.groupIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    if (groups.isEmpty()) return EmployeeProfileResult.Rejected(DeclarationRejection.ARGUMENT_INVALID)
    val denied = employeeTargetGroups(self, actorNode, groups)
    if (denied != null) return EmployeeProfileResult.Rejected(denied)
    val (applied, failed, aliased) = attachEmployeeToGroups(self, profile, groups, cmd.actor, cmd.origin, null)
    return EmployeeProfileResult.Applied(profile.employeeId, profile.name, profile.token, applied, failed, aliased)
}

private fun RelayController.forgetEmployee(
    self: String,
    actorNode: String,
    cmd: EmployeeProfileCommand.Forget,
    profiles: List<EmployeeProfile>,
): EmployeeProfileResult {
    val profile = profiles.byEmployeeId(cmd.employeeId)
        ?: return EmployeeProfileResult.Rejected(DeclarationRejection.EMPLOYEE_UNKNOWN)
    val groups = groupsReferencingEmployee(profile.employeeId)
    val denied = employeeTargetGroups(self, actorNode, groups)
    if (denied != null) return EmployeeProfileResult.Rejected(denied)
    // 先摘席位引用、再删档案：反过来的中间态是「席位指着一份不存在的档案」。
    val applied = mutableListOf<String>()
    val failed = mutableListOf<String>()
    for (groupId in groups) {
        when (applyDeclaration(
            GroupDeclarationCommand.DetachEmployee(
                groupId = groupId,
                agentId = profile.agentId,
                actor = cmd.actor,
                origin = cmd.origin,
            ),
        )) {
            is DeclarationResult.Applied -> applied += groupId
            is DeclarationResult.Rejected -> failed += groupId
            is DeclarationResult.Conflict -> failed += groupId
        }
    }
    persistEmployeeProfiles(profiles.filterNot { it.employeeId == profile.employeeId })
    return EmployeeProfileResult.Applied(profile.employeeId, profile.name, profile.token, applied, failed)
}

/**
 * 界面入口：登记或编辑一名员工，结果用**横幅**回报（不许沉默）。
 *
 * ## 为什么登记时要带上「它已经在的群」
 *
 * 详情页点「登记为员工」时，这个 agent 通常已经入驻了若干群。只写档案、不动席位的话，
 * 宿主本机因为投影直接读档案而立刻显示新名字，**手机上却还是旧 token** ——
 * 两端不一致，且没有任何报错。所以这里把「本机该 agent 已有席位的群」一并交给写路径
 * 重建快照；没进过群就传空表（只建档，符合「先建员工、再决定进哪个群」）。
 *
 * @param employeeId null = 登记；非 null = 编辑该档案（按键定位，不按名字）。
 */
public fun RelayController.saveEmployeeProfileEntry(
    agentId: String,
    employeeId: String?,
    name: String,
    duty: String,
    origin: DeclarationOrigin = DeclarationOrigin.AGENT_HUB,
) {
    val state = _state.value
    val self = state.selfNodeId
    val actor = DeclarationActor.Human(self)
    val result = if (employeeId == null) {
        val existingGroups = state.settings.groups
            .filter { group ->
                group.virtualAgents.orEmpty().any {
                    it.hostNodeId == self && it.agentId.equals(agentId.trim(), ignoreCase = true)
                }
            }
            .map { it.groupId }
        applyEmployeeProfile(
            EmployeeProfileCommand.Create(
                name = name,
                agentId = agentId,
                duty = duty,
                groupIds = existingGroups,
                agentKind = agentKindOf(self, agentId),
                actor = actor,
                origin = origin,
            ),
        )
    } else {
        applyEmployeeProfile(
            EmployeeProfileCommand.Update(
                employeeId = employeeId,
                name = name,
                duty = duty,
                actor = actor,
                origin = origin,
            ),
        )
    }
    when (result) {
        is EmployeeProfileResult.Applied -> {
            // 别名必须点名：用户在 A 群看到的是 `@dsh-coder-2`，而他刚才设的名字不是它 ——
            // 不说出来就会被当成「档案没生效」（其实落了，只是那一群早有人占了这个 token）。
            val aliasedNames = state.settings.groups
                .filter { it.groupId in result.aliasedGroupIds }
                .map { it.name }
            info(BannerMessage.EmployeeProfileSaved(result.name, aliasedNames))
        }
        is EmployeeProfileResult.Rejected -> warn(BannerMessage.EmployeeProfileRejected)
    }
}

// ------------------------------------------------------------------ 内部工具
/**
 * agent 在本机台账里吗？台账**为空**时返回 true（见文件头：手机/冷启动/测试没有运行时条目，
 * 那时拒绝等于让人连名字都存不下来）；台账有条目但都不匹配 ⇒ false（幽灵员工）。
 */
private fun RelayController.agentIsKnownOrUnknown(self: String, agentId: String): Boolean {
    val local = _state.value.agentWorkspaces.values.filter { it.peerId == self }
    if (local.isEmpty()) return true
    return local.any { it.agentId.equals(agentId.trim(), ignoreCase = true) }
}

/** 目标群预校验：群存在 + actor 与本机都是成员。返回 null = 全过。 */
private fun RelayController.employeeTargetGroups(
    self: String,
    actorNode: String,
    groupIds: List<String>,
): DeclarationRejection? {
    val groups = _state.value.settings.groups
    for (groupId in groupIds) {
        val meta = groups.firstOrNull { it.groupId == groupId }
            ?: return DeclarationRejection.GROUP_UNKNOWN
        if (actorNode !in meta.members) return DeclarationRejection.NOT_MEMBER
        if (self !in meta.members) return DeclarationRejection.NOT_MEMBER
    }
    return null
}

/** 哪些群正在引用这份档案（席位 `employeeId` 相等）。 */
private fun RelayController.groupsReferencingEmployee(employeeId: String): List<String> =
    _state.value.settings.groups
        .filter { group -> group.virtualAgents.orEmpty().any { it.employeeId == employeeId } }
        .map { it.groupId }

/**
 * 逐群挂席位，返回 (成功群, 失败群, 别名群)。单群失败不影响其它群 —— 跨群不是事务。
 *
 * 「别名群」从**落盘后**的席位读实际名字再比对 token：命令参数里的 token 不等于最终席位名，
 * 唯一可信的判据是落库结果（这也顺带证明写入真的发生了）。
 */
private fun RelayController.attachEmployeeToGroups(
    self: String,
    profile: EmployeeProfile,
    groupIds: List<String>,
    actor: DeclarationActor,
    origin: DeclarationOrigin,
    baseRev: Int?,
    forceToken: Boolean = false,
): Triple<List<String>, List<String>, List<String>> {
    val applied = mutableListOf<String>()
    val failed = mutableListOf<String>()
    val aliased = mutableListOf<String>()
    for (groupId in groupIds) {
        val result = applyDeclaration(
            GroupDeclarationCommand.AttachEmployee(
                groupId = groupId,
                agentId = profile.agentId,
                employeeId = profile.employeeId,
                employeeName = profile.name,
                token = profile.token,
                forceToken = forceToken,
                agentKind = agentKindOf(self, profile.agentId),
                duty = profile.duty,
                baseRev = if (actor is DeclarationActor.Agent) baseRev else null,
                actor = actor,
                origin = origin,
            ),
        )
        when (result) {
            is DeclarationResult.Applied -> {
                applied += groupId
                val seatName = _state.value.settings.groups
                    .firstOrNull { it.groupId == groupId }
                    ?.virtualAgents.orEmpty()
                    .firstOrNull {
                        it.hostNodeId == self &&
                            it.agentId.equals(profile.agentId, ignoreCase = true) &&
                            it.employeeId == profile.employeeId
                    }
                    ?.name
                if (profile.isAliasedIn(seatName)) aliased += groupId
            }
            is DeclarationResult.Rejected -> failed += groupId
            is DeclarationResult.Conflict -> failed += groupId
        }
    }
    return Triple(applied, failed, aliased)
}

/** 档案落盘：设置里只此一处写 `employeeProfiles`（守卫 `EmployeeProfileWriteGuardTest` 钉住）。 */
private fun RelayController.persistEmployeeProfiles(next: List<EmployeeProfile>) {
    handlePersist(_state.value.settings.copy(employeeProfiles = next))
    publish()
}

/** 分配档案键：`emp-` + 时间基 36 进制 + 4 位随机，够本机唯一（不跨机，不需要全局唯一）。 */
private fun allocateEmployeeId(): String =
    "emp-" + nowMillis().toString(36) + "-" + Random.nextInt(0x1000, 0x10000).toString(16)
