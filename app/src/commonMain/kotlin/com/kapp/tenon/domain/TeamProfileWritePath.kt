// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.nowMillis
import com.kapp.tenon.core.protocol.Limits
import com.kapp.tenon.core.protocol.TeamMemberInfo
import kotlin.random.Random

/**
 * 团队档案的**唯一写路径**（B 档：团队有名字、一份名册多群复用、群能整体添加一个团队）。
 *
 * ## 为什么档案要单独一条写路径
 *
 * [GroupDeclarationCommand] 改的是**一个群**的席位表；档案是**跨群**的一份对象。
 * 把它硬塞进 `applyDeclaration`（那条命令的 `groupId` / `baseRev` 都是单群语义）只会
 * 让并发与审计口径同时失真。所以这里分成两层，各守一件事：
 *
 * 1. **档案层**（本文件）：名字与名册的唯一真相，`RelaySettings.teamProfiles`；
 * 2. **席位层**（[GroupDeclarationWritePath]）：每个群一条席位，靠 `AttachTeam` /
 *    `DetachTeam` 写快照 —— 仍然是**那条**席位写路径，rev 推进、广播、审计一份不多。
 *
 * 于是「一次改名」= 一次档案写入 + 每个引用群各一次 `AttachTeam`：
 * 每个群各自 rev+1、各自广播、各自一行审计。**不是**跨群事务 —— 某群失败不回滚别群，
 * 结果里如实带 [TeamProfileResult.Applied.failedGroupIds]，下次重建收敛。
 *
 * ## 派单不在这条线上
 *
 * 派单只认 leader（由它内部协调）或某个组员的 token，**不存在「派给团队」这个目标**。
 * 本文件不新增任何派单目标类型，`TeamMention` 的解析面一个字都不动。
 */

/** 谁来改档案（与编制命令同一个 actor 口径：审计与越权判定共用）。 */
sealed interface TeamProfileCommand {

    val actor: DeclarationActor
    val origin: DeclarationOrigin

    /**
     * 建一份档案，并**一次加进若干群**（每群各建/复用一条领队席位）。
     *
     * 「群里添加一个团队」= 只给一个 groupId；「建一支开发团队并让它进 A/B 两个群」=
     * 给两个 —— 同一份名册，两群各自一条席位、各自执行点。
     *
     * @param teamId 测试可注入；生产传 null 由本机分配（`tm-…`）。
     */
    data class Create(
        val name: String,
        val leaderAgentId: String,
        val members: List<com.kapp.tenon.core.protocol.TeamMemberDraft>,
        val groupIds: List<String>,
        val teamId: String? = null,
        override val actor: DeclarationActor,
        override val origin: DeclarationOrigin = DeclarationOrigin.AGENT_HUB,
    ) : TeamProfileCommand

    /**
     * 改名 / 换名册。改动**对所有引用群同时生效**（每群一次 `AttachTeam` 重建快照）。
     *
     * 两个参数都可空，但至少要给一个；`members` 给空表 = 非法（团队至少要有一个组员，
     * 否则它就是单兵 —— 该走 `DetachTeam` 或 `LeaveSeat`）。
     */
    data class Update(
        val teamId: String,
        val name: String? = null,
        val members: List<com.kapp.tenon.core.protocol.TeamMemberDraft>? = null,
        override val actor: DeclarationActor,
        override val origin: DeclarationOrigin = DeclarationOrigin.AGENT_HUB,
    ) : TeamProfileCommand

    /** 把**已有**团队加进更多群（名册不重填）。 */
    data class AddToGroups(
        val teamId: String,
        val groupIds: List<String>,
        override val actor: DeclarationActor,
        override val origin: DeclarationOrigin = DeclarationOrigin.AGENT_HUB,
    ) : TeamProfileCommand

    /** 解散：从所有引用群摘掉团队形态（席位保留成单兵），并删除档案。 */
    data class Dissolve(
        val teamId: String,
        override val actor: DeclarationActor,
        override val origin: DeclarationOrigin = DeclarationOrigin.AGENT_HUB,
    ) : TeamProfileCommand
}

/** 档案命令的结果。`Applied` 的定义是「档案已落盘」；群级失败单独列出，不假装全绿。 */
sealed interface TeamProfileResult {

    /**
     * @param groupIds 实际写成功的群（顺序与请求一致）。
     * @param failedGroupIds 预校验通过但席位写入失败的群（并发/落盘被拒）；空 = 全绿。
     * @param aliasedGroupIds 用了**本群别名**的群（名册里至少一个组员在本群叫别的名字）。
     *   与 [failedGroupIds] 分开：别名**不是失败**，是「这个词本群已经被别人占了」；
     *   用户需要知道的是「我在 A 群 @coder，在 B 群要 @coder-2」，所以要点名到群。
     */
    data class Applied(
        val teamId: String,
        val name: String,
        val groupIds: List<String>,
        val failedGroupIds: List<String> = emptyList(),
        val aliasedGroupIds: List<String> = emptyList(),
    ) : TeamProfileResult

    data class Rejected(val reason: DeclarationRejection) : TeamProfileResult
}

/** 应用一条档案命令 —— 档案写入的**唯一实现**。 */
internal fun RelayController.applyTeamProfile(command: TeamProfileCommand): TeamProfileResult {
    val state = _state.value
    val self = state.selfNodeId
    val actorNode = command.actor.actorNodeId

    // agent 发起时：只能代表本机自己的席位（与 applyDeclaration 同一条本机主权）。
    if (command.actor is DeclarationActor.Agent && actorNode != self) {
        return TeamProfileResult.Rejected(DeclarationRejection.NOT_MEMBER)
    }

    val profiles = state.settings.teamProfiles
    return when (command) {
        is TeamProfileCommand.Create -> createTeam(self, actorNode, command)
        is TeamProfileCommand.Update -> updateTeam(self, actorNode, command, profiles)
        is TeamProfileCommand.AddToGroups -> addTeamToGroups(self, actorNode, command, profiles)
        is TeamProfileCommand.Dissolve -> dissolveTeam(self, actorNode, command, profiles)
    }
}

private fun RelayController.createTeam(
    self: String,
    actorNode: String,
    cmd: TeamProfileCommand.Create,
): TeamProfileResult {
    val name = cmd.name.trim()
    if (name.isEmpty() || name.length > Limits.TEAM) {
        return TeamProfileResult.Rejected(DeclarationRejection.NAME_INVALID)
    }
    val leader = cmd.leaderAgentId.trim()
    if (leader.isEmpty()) return TeamProfileResult.Rejected(DeclarationRejection.ARGUMENT_INVALID)
    if (cmd.members.isEmpty()) return TeamProfileResult.Rejected(DeclarationRejection.ARGUMENT_INVALID)
    val groups = cmd.groupIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    if (groups.isEmpty()) return TeamProfileResult.Rejected(DeclarationRejection.ARGUMENT_INVALID)
    val denied = teamTargetGroups(self, actorNode, groups)
    if (denied != null) return TeamProfileResult.Rejected(denied)
    // **先全量预校验再落档案**：跨群不是事务，先写档案后校验会让「第一群成功、第二群撞名」
    // 留下一个改了一半的世界。
    preflightRoster(groups, cmd.members)?.let {
        return TeamProfileResult.Rejected(it)
    }

    val teamId = cmd.teamId?.trim()?.takeIf { it.isNotEmpty() } ?: allocateTeamId()
    if (_state.value.settings.teamProfiles.any { it.teamId == teamId }) {
        // 注入的 id 撞了既是测试写错，也说明调用方在乱造键：拒绝而不是覆盖别人的档案。
        return TeamProfileResult.Rejected(DeclarationRejection.ARGUMENT_INVALID)
    }
    val profile = TeamProfile(
        teamId = teamId,
        name = name,
        leaderAgentId = leader,
        members = cmd.members.map { TeamMemberInfo(it.agentId, it.name, it.role) },
        rev = 1,
        updatedTs = nowMillis(),
    )
    // 顺序：**先落档案，再逐群挂席位**。反过来（先席位后档案）会让对端先看到一条指向
    // 不存在档案的引用；而档案先落、席位失败最坏是「这一群还没有这支队伍」，界面可重试。
    persistTeamProfiles(_state.value.settings.teamProfiles + profile)
    val outcome = attachToGroups(self, profile, groups, cmd.actor, cmd.origin, null)
    return TeamProfileResult.Applied(teamId, name, outcome.applied, outcome.failed, outcome.aliased)
}

private fun RelayController.updateTeam(
    self: String,
    actorNode: String,
    cmd: TeamProfileCommand.Update,
    profiles: List<TeamProfile>,
): TeamProfileResult {
    val profile = profiles.byTeamId(cmd.teamId)
        ?: return TeamProfileResult.Rejected(DeclarationRejection.TEAM_UNKNOWN)
    val newName = cmd.name?.trim()
    if (newName != null && (newName.isEmpty() || newName.length > Limits.TEAM)) {
        return TeamProfileResult.Rejected(DeclarationRejection.NAME_INVALID)
    }
    if (newName == null && cmd.members == null) {
        return TeamProfileResult.Rejected(DeclarationRejection.ARGUMENT_INVALID)
    }
    val members = cmd.members?.map { TeamMemberInfo(it.agentId, it.name, it.role) } ?: profile.members
    if (members.isEmpty()) return TeamProfileResult.Rejected(DeclarationRejection.ARGUMENT_INVALID)
    val updated = profile.copy(
        name = newName ?: profile.name,
        members = members,
        rev = profile.rev + 1,
        updatedTs = nowMillis(),
    )
    val groups = groupsReferencing(updated.teamId)
    val denied = teamTargetGroups(self, actorNode, groups)
    if (denied != null) return TeamProfileResult.Rejected(denied)
    preflightRoster(groups, updated.rosterDrafts())?.let {
        return TeamProfileResult.Rejected(it)
    }
    // 先档案后席位：与 create 同一顺序 —— 快照重建拿到的永远是这一版的档案。
    persistTeamProfiles(profiles.map { if (it.teamId == updated.teamId) updated else it })
    val outcome = attachToGroups(self, updated, groups, cmd.actor, cmd.origin, null)
    return TeamProfileResult.Applied(updated.teamId, updated.name, outcome.applied, outcome.failed, outcome.aliased)
}

private fun RelayController.addTeamToGroups(
    self: String,
    actorNode: String,
    cmd: TeamProfileCommand.AddToGroups,
    profiles: List<TeamProfile>,
): TeamProfileResult {
    val profile = profiles.byTeamId(cmd.teamId)
        ?: return TeamProfileResult.Rejected(DeclarationRejection.TEAM_UNKNOWN)
    val groups = cmd.groupIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    if (groups.isEmpty()) return TeamProfileResult.Rejected(DeclarationRejection.ARGUMENT_INVALID)
    val denied = teamTargetGroups(self, actorNode, groups)
    if (denied != null) return TeamProfileResult.Rejected(denied)
    preflightRoster(groups, profile.rosterDrafts())?.let {
        return TeamProfileResult.Rejected(it)
    }
    val outcome = attachToGroups(self, profile, groups, cmd.actor, cmd.origin, null)
    return TeamProfileResult.Applied(profile.teamId, profile.name, outcome.applied, outcome.failed, outcome.aliased)
}

private fun RelayController.dissolveTeam(
    self: String,
    actorNode: String,
    cmd: TeamProfileCommand.Dissolve,
    profiles: List<TeamProfile>,
): TeamProfileResult {
    val profile = profiles.byTeamId(cmd.teamId)
        ?: return TeamProfileResult.Rejected(DeclarationRejection.TEAM_UNKNOWN)
    val groups = groupsReferencing(profile.teamId)
    val denied = teamTargetGroups(self, actorNode, groups)
    if (denied != null) return TeamProfileResult.Rejected(denied)
    // 先摘席位引用，再删档案：反过来的话中间态是「席位指着一份不存在的档案」。
    val applied = mutableListOf<String>()
    val failed = mutableListOf<String>()
    for (groupId in groups) {
        when (val result = applyDeclaration(GroupDeclarationCommand.DetachTeam(
            groupId = groupId,
            agentId = profile.leaderAgentId,
            actor = cmd.actor,
            origin = cmd.origin,
        ))) {
            is DeclarationResult.Applied -> {
                applied += groupId
                forgetExecutionProfile(groupId, profile.leaderAgentId)
            }
            is DeclarationResult.Rejected -> failed += groupId
            is DeclarationResult.Conflict -> failed += groupId
        }
    }
    persistTeamProfiles(profiles.filterNot { it.teamId == profile.teamId })
    return TeamProfileResult.Applied(profile.teamId, profile.name, applied, failed)
}

/**
 * 解散收尾：把这一群对应的执行档从宿主磁盘上删掉。
 *
 * ## 为什么必须删
 *
 * 执行档的主键是 `(目录, 领队)` —— 它不随团队档案消失。不删的后果不是「文件里多几行」：
 * 用户在同一个群、同一个目录里**重建**一支领队相同的队伍时，键完全命中旧档 ⇒ 新的编制
 * 直接继承上一次的驱动/模型/人设，而界面上一个提示都没有（旧档里的成员名册还可能已经
 * 不是这批人了，读取期的悬空检查只兜住「档里的人不在声明里」，兜不住「这次没打算继承」）。
 *
 * ## 为什么只在 DetachTeam 成功的那一群删
 *
 * 席位还在就一定还在用它 —— 那一群没摘干净（并发/落盘被拒）时删掉执行档，等于把一个
 * 还能跑的团队变成「没配过」。用户在横幅里已经看到「N 个群没摘干净」，会去重试。
 *
 * ## 与「单群撤回」的区别
 *
 * 从群信息里把团队移到别的群、或只是这一个群不带它了，**不清**执行档：那不是解散，
 * 加回来时配置还在才是用户预期。清的理由只成立于「这支队没有了」。
 */
private fun RelayController.forgetExecutionProfile(groupId: String, leaderAgentId: String) {
    val port = teamExecutionPort ?: return
    // 群没有目录指针 ⇒ 这份档本来就不按这个群存过（主键缺一半），无从删也不该猜。
    val dirId = port.groupPointerDirId(groupId)?.trim().orEmpty()
    if (dirId.isEmpty()) return
    port.forget(dirId, leaderAgentId)
}

// ------------------------------------------------------------------ 界面入口

/**
 * 界面入口：新建（[teamId] 为 null）或编辑一份**团队档案**，结果用横幅回报。
 *
 * ## 编辑为什么是「先 Update 再加群」而不是一条命令
 *
 * `Update` 的语义是「这份档案变了」（名字 / 名册），`AddToGroups` 的语义是「这支队
 * 去了新地方」。合成一条会让「只改名」与「只加群」的拒绝原因混在一个返回值里，
 * 用户看到「保存失败」却不知道是名字非法还是某个群不让进。分两步则各自如实：
 * 改名失败 ⇒ 整次不继续（档案没变，加群也无意义）；改名成功但加群失败 ⇒ 横幅
 * 带出失败群数，档案本身仍是新的。
 *
 * ## 为什么群列表只增不减
 *
 * 从群里移除一支队伍是**那个群**的决定（`DetachTeam` 是单群席位命令），
 * 不该从「编辑团队」这一屏顺手完成 —— 那会让「改个名字」有把一个群清空的副作用。
 *
 * @param teamId null = 新建；非 null = 编辑该档案（按键定位，不按名字）。
 * @param groupIds 新建 = 要加入的群（可空 = 只建档）；编辑 = 要**新增**加入的群。
 */
public fun RelayController.saveTeamProfileEntry(
    teamId: String?,
    name: String,
    leaderAgentId: String,
    members: List<com.kapp.tenon.core.protocol.TeamMemberDraft>,
    groupIds: List<String>,
    origin: DeclarationOrigin = DeclarationOrigin.AGENT_HUB,
) {
    val self = _state.value.selfNodeId
    val actor = DeclarationActor.Human(self)
    val result = if (teamId == null) {
        applyTeamProfile(
            TeamProfileCommand.Create(
                name = name,
                leaderAgentId = leaderAgentId,
                members = members,
                groupIds = groupIds,
                actor = actor,
                origin = origin,
            ),
        )
    } else {
        applyTeamProfile(
            TeamProfileCommand.Update(
                teamId = teamId,
                name = name,
                members = members,
                actor = actor,
                origin = origin,
            ),
        ).let { updated ->
            if (updated !is TeamProfileResult.Applied || groupIds.isEmpty()) {
                updated
            } else {
                // 档案已经变了：加群失败不回滚改名（跨群不是事务，见文件头）。
                when (val added = applyTeamProfile(
                    TeamProfileCommand.AddToGroups(teamId, groupIds, actor = actor, origin = origin),
                )) {
                    is TeamProfileResult.Applied -> updated.copy(
                        groupIds = updated.groupIds + added.groupIds,
                        failedGroupIds = updated.failedGroupIds + added.failedGroupIds,
                    )
                    is TeamProfileResult.Rejected -> updated.copy(
                        failedGroupIds = updated.failedGroupIds + groupIds,
                    )
                }
            }
        }
    }
    when (result) {
        is TeamProfileResult.Applied ->
            // 有群没写进去就报到警告位：绿色横幅会把「有一个群没成功」盖掉，
            // 用户下次打开那一群发现队伍不在，却查不到任何痕迹。
            // 本群别名不算失败（席位写进去了），但要带出群数 —— 用户得知道去那一群
            // 要按别名 @，否则他会以为团队坏了。
            if (result.failedGroupIds.isEmpty()) {
                info(
                    BannerMessage.TeamProfileSaved(
                        name = result.name,
                        groupCount = result.groupIds.size,
                        aliasedGroupCount = result.aliasedGroupIds.size,
                    ),
                )
            } else {
                warn(
                    BannerMessage.TeamProfileSaved(
                        name = result.name,
                        groupCount = result.groupIds.size,
                        failedGroupCount = result.failedGroupIds.size,
                        aliasedGroupCount = result.aliasedGroupIds.size,
                    ),
                )
            }
        is TeamProfileResult.Rejected -> warn(BannerMessage.TeamProfileRejected)
    }
}

/**
 * 界面入口：**解散**一支团队（团队详情页置顶栏那颗「解散」的落点）。
 *
 * ## 这条命令早就在，缺的一直是入口
 *
 * `Dissolve` 从档案写路径落地那天就实现了（逐群 `DetachTeam` + 删档案），但界面从
 * 来没有调过它：一支队伍能被建、能改名册、能在某个群被「移除」，却**删不掉**。
 * 席位撤光之后它从智能体页的名录里消失（名录按席位装配），档案却仍留在
 * `RelaySettings.teamProfiles` 里。
 *
 * 两个入口都走这一条：
 * 1. **团队详情页置顶栏的垃圾桶** —— 席位还在时（有 `groupId` 可定位）；
 * 2. **名录「未入驻任何群」那一段的垃圾桶** —— 席位已经撤光、只剩档案时。
 *    那种队伍进不了详情页（`agentTeamDetail` 按席位键定位，解析不到它），
 *    所以它的落点必须在名录侧，否则它永远只能从群信息的「添加团队」里看见、删不掉。
 *
 * ## 解散 ≠ 踢人
 *
 * 每个群走 `DetachTeam`：席位**回单兵**（`entityType` 清成 SINGLE、`teamId` 清空），
 * 领队本人仍留在群里、仍能被 `@`。摘席位是 `LeaveSeat` 的事，两条命令不混用
 * （见 `planDetachTeam` 的说明）—— 想把人踢出群是另一个动作。
 *
 * ## 有群没摘干净就说出来
 *
 * 跨群不是事务：某个群 detach 被拒（已不是成员 / 群已散）时档案仍然会被删，
 * 那些群的席位就地留下一条**指着已不存在档案**的 `teamId`。这不是可以吞掉的
 * 细节 —— 走警告位，用户得知道去那一群手工处理。
 */
public fun RelayController.dissolveTeamProfileEntry(
    teamId: String,
    origin: DeclarationOrigin = DeclarationOrigin.AGENT_HUB,
) {
    val result = applyTeamProfile(
        TeamProfileCommand.Dissolve(
            teamId = teamId,
            actor = DeclarationActor.Human(_state.value.selfNodeId),
            origin = origin,
        ),
    )
    when (result) {
        is TeamProfileResult.Applied ->
            if (result.failedGroupIds.isEmpty()) {
                info(BannerMessage.TeamProfileDissolved(result.name, result.groupIds.size))
            } else {
                warn(
                    BannerMessage.TeamProfileDissolved(
                        name = result.name,
                        groupCount = result.groupIds.size,
                        failedGroupCount = result.failedGroupIds.size,
                    ),
                )
            }
        is TeamProfileResult.Rejected -> warn(BannerMessage.TeamProfileRejected)
    }
}

/**
 * 界面入口：把**已有**团队整体加进一个群（群信息里「添加团队」的落点）。
 *
 * 名册不重填 —— 这正是「群添加可以直接添加一个团队」与「一个个加人」的差别：
 * 组员随档案过来，群里立刻出现一条团队席位（含领队与全部组员）。
 */
public fun RelayController.attachTeamToGroupEntry(
    teamId: String,
    groupId: String,
    origin: DeclarationOrigin = DeclarationOrigin.AGENT_HUB,
) {
    val self = _state.value.selfNodeId
    val result = applyTeamProfile(
        TeamProfileCommand.AddToGroups(
            teamId = teamId,
            groupIds = listOf(groupId),
            actor = DeclarationActor.Human(self),
            origin = origin,
        ),
    )
    when (result) {
        is TeamProfileResult.Applied ->
            if (result.failedGroupIds.isEmpty()) {
                info(
                    BannerMessage.TeamProfileSaved(
                        name = result.name,
                        groupCount = result.groupIds.size,
                        aliasedGroupCount = result.aliasedGroupIds.size,
                    ),
                )
            } else {
                warn(
                    BannerMessage.TeamProfileSaved(
                        name = result.name,
                        groupCount = 0,
                        failedGroupCount = result.failedGroupIds.size,
                        aliasedGroupCount = result.aliasedGroupIds.size,
                    ),
                )
            }
        is TeamProfileResult.Rejected -> warn(BannerMessage.TeamProfileRejected)
    }
}

// ------------------------------------------------------------------ 内部工具

/**
 * 名册在**每个目标群**都没有必须改的错吗（空 / 超长 / 非 slug / 名册内重名 / 超员）。
 *
 * 复用 [declarationRosterIssue]（与 `planAttachTeam` 同一个判据），不另写。
 * **撞名不在这里拦** —— 那是逐群别名的事，属于正常结果，不是拒绝原因。
 * 返回第一个不通过的群的拒绝原因，null = 全过。
 */
private fun RelayController.preflightRoster(
    groupIds: List<String>,
    roster: List<com.kapp.tenon.core.protocol.TeamMemberDraft>,
): DeclarationRejection? {
    for (groupId in groupIds) {
        declarationRosterIssue(groupId, roster)?.let { return it }
    }
    return null
}

/** 目标群预校验：群存在 + actor 是本机成员。返回 null = 全过。 */
private fun RelayController.teamTargetGroups(
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

/** 哪些群正在引用这份档案（席位 `teamId` 相等；仅本机席位算数 —— 别人的档案不归我改）。 */
private fun RelayController.groupsReferencing(teamId: String): List<String> =
    _state.value.settings.groups.groupsReferencingTeam(teamId).map { it.groupId }

/** 一次逐群挂席位的结果。三个集合互不重叠，`applied` 之外的两个都要如实回报给用户。 */
private data class AttachOutcome(
    val applied: List<String>,
    val failed: List<String>,
    val aliased: List<String>,
)

/** 逐群挂席位。单群失败不影响其它群 —— 跨群不是事务。 */
private fun RelayController.attachToGroups(
    self: String,
    profile: TeamProfile,
    groupIds: List<String>,
    actor: DeclarationActor,
    origin: DeclarationOrigin,
    baseRev: Int?,
): AttachOutcome {
    val applied = mutableListOf<String>()
    val failed = mutableListOf<String>()
    for (groupId in groupIds) {
        val result = applyDeclaration(
            GroupDeclarationCommand.AttachTeam(
                groupId = groupId,
                agentId = profile.leaderAgentId,
                teamId = profile.teamId,
                teamName = profile.name,
                roster = profile.rosterDrafts(),
                agentKind = agentKindOf(self, profile.leaderAgentId),
                baseRev = if (actor is DeclarationActor.Agent) baseRev else null,
                actor = actor,
                origin = origin,
            ),
        )
        when (result) {
            is DeclarationResult.Applied -> applied += groupId
            is DeclarationResult.Rejected -> failed += groupId
            is DeclarationResult.Conflict -> failed += groupId
        }
    }
    return AttachOutcome(applied, failed, aliasedGroupsOf(profile, applied))
}

/**
 * 哪些群用了**本群别名**：从**落盘后**的席位读实际名字再和档案比，不看请求参数。
 *
 * 口令与 `EmployeeProfileWritePath` 同一条 —— 档案里的名字是「默认名」，席位里的是
 * 「本群 `@` token」，两者不同**只可能**是因为这个群已经有同名的东西。拿请求参数比
 * 会把「本来就叫这个」误判成别名；拿落盘结果比才是事实。
 *
 * 失败群不参与（它可能压根没写进去）。
 */
private fun RelayController.aliasedGroupsOf(profile: TeamProfile, applied: List<String>): List<String> {
    if (applied.isEmpty()) return emptyList()
    val canonical = profile.members.map { it.name }.toSet()
    return applied.filter { groupId ->
        val meta = _state.value.settings.groups.firstOrNull { it.groupId == groupId } ?: return@filter false
        val seat = meta.virtualAgents.orEmpty().firstOrNull { it.teamId == profile.teamId }
            ?: return@filter false
        seat.teamMembers.map { it.name }.toSet() != canonical
    }
}

/** 本机台账里这个 agent 的 kind（只用于新席位派生默认名；认不出就给 null，不猜）。 */
internal fun RelayController.agentKindOf(self: String, agentId: String): String? =
    _state.value.agentWorkspaces.values.firstOrNull {
        it.peerId == self && it.agentId.equals(agentId.trim(), ignoreCase = true)
    }?.agentKind

/** 档案落盘：设置里只此一处写 `teamProfiles`（守卫 `TeamProfileBypassGuardTest` 钉住）。 */
private fun RelayController.persistTeamProfiles(next: List<TeamProfile>) {
    handlePersist(_state.value.settings.copy(teamProfiles = next))
    publish()
}

/** 分配档案键：`tm-` + 时间基 36 进制 + 4 位随机，够本机唯一（不跨机，不需要全局唯一）。 */
private fun allocateTeamId(): String =
    "tm-" + nowMillis().toString(36) + "-" + Random.nextInt(0x1000, 0x10000).toString(16)
