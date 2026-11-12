// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.core.protocol.TeamMemberInfo
import com.kapp.tenon.core.protocol.VirtualAgentMember
import com.kapp.tenon.core.protocol.resolveTeamMemberToken
import com.kapp.tenon.core.protocol.resolveTeamNameMention
import com.kapp.tenon.core.protocol.seatsMatchingToken

/**
 * 一条群文本的**派单目标解析**（团队执行方案 §4.2）：`@` 点的是席位（领队）还是组员？
 *
 * ## 为什么必须是先解析、再决定执不执行
 *
 * 组员名进入 `@` token 池（P0-0.1）之后，一句「@coder 把登录修了，@reviewer 审查」
 * 会同时命中两个 token。没有这一步的话，路由层拿 `mentionedAgentKeys.firstOrNull()`
 * （**字母序**，不是说话顺序）随机挑一个 —— 「随机挑一个目标执行」正是本函数要堵的洞。
 *
 * - [PromptTarget.None]：没有可解析的目标（含群为 null —— 1:1 没有团队语义）；
 * - [PromptTarget.Seat]：点的是席位（领队 token / 席位 agentId）—— 走既有单 agent 路径；
 * - [PromptTarget.Member]：点的是**组员** —— 触发团队执行分支（席位宿主编译责任链）；
 * - [PromptTarget.Ambiguous]：同时点了席位与组员、或点了两个组员、或跨席位重名
 *   —— **不执行**，并给一句可读提示（fail-closed；「随机挑一个」比不执行更糟）。
 *
 * 宿主自报的裸 kind（`dsh`）不在群元数据里，映射不出目标席位 —— 跳过（路由层对
 * 这类 token 保持既有行为）；席位 token 解析只认群元数据里的 `name` / `agentId`。
 *
 * 判据单源：组员命中走 core 的 [resolveTeamMemberToken]（同一份函数服务编排层），
 * 席位命中走 [GroupMeta.seatsMatchingToken] —— 这里不重写第三遍。
 */
sealed interface PromptTarget {
    data object None : PromptTarget

    /** 点的是席位本身（`@leader` / `@<席位 agentId>`）：既有单 agent 派单，不进团队分支。 */
    data class Seat(val seat: VirtualAgentMember) : PromptTarget {
        val hostNodeId: String get() = seat.hostNodeId
    }

    /** 点的是组员（`@coder`）：团队执行分支的目标。执行主体仍是席位宿主。 */
    data class Member(val seat: VirtualAgentMember, val member: TeamMemberInfo) : PromptTarget {
        val hostNodeId: String get() = seat.hostNodeId
    }

    /** 点的是**整支队**（`@<团队名>`）：走团队分支，命中成员由执行档的 worker 决定。 */
    data class Team(
        val teamId: String?,
        val teamName: String,
        val seat: VirtualAgentMember,
    ) : PromptTarget {
        val hostNodeId: String get() = seat.hostNodeId
    }

    /** 同时点了多个目标 / 命中跨席位重名 —— 不执行，提示「一次只 @ 一个目标」。 */
    data object Ambiguous : PromptTarget
}

/**
 * @param matchableTokens 本群全部可被 `@` 的 token（= `groupParticipantRows().mentionKeys`，
 *   已含组员名 —— P0-0.1 的单源池）。这里只消费它做**命中集合**，不另建正则。
 */
fun resolvePromptTarget(text: String, group: GroupMeta?, matchableTokens: Set<String>): PromptTarget {
    if (group == null) return PromptTarget.None
    val mentioned = RelayController.mentionedAgentKeys(text, matchableTokens)
    // **`@团队名` 单判一条**：团队名不在成员 token 池里（它是展示串，可含中文），
    // 所以不走上面那条命中集合。歧义（同名多队 / 与成员撞名）直接 fail-closed ——
    // 撞名时 `@` 永远先命中成员，团队名等于打不到，必须让用户改名而不是猜。
    val teamTarget = when (val teamHit = group.resolveTeamNameMention(text)) {
        is com.kapp.tenon.core.protocol.TeamNameMention.Ambiguous -> return PromptTarget.Ambiguous
        is com.kapp.tenon.core.protocol.TeamNameMention.Hit ->
            PromptTarget.Team(teamHit.teamId, teamHit.teamName, teamHit.seat)
        null -> null
    }
    if (mentioned.isEmpty()) return teamTarget ?: PromptTarget.None

    val targets = LinkedHashSet<PromptTarget>()
    for (token in mentioned) {
        when (val memberHit = group.resolveTeamMemberToken(token)) {
            is com.kapp.tenon.core.protocol.TeamMention.Ambiguous -> return PromptTarget.Ambiguous
            is com.kapp.tenon.core.protocol.TeamMention.Hit -> {
                targets += PromptTarget.Member(memberHit.seat, memberHit.member)
                continue
            }
            null -> Unit
        }
        val seats = group.seatsMatchingToken(token)
        when (seats.size) {
            0 -> Unit // 宿主自报 kind / 未知 token：映射不出目标，跳过
            1 -> targets += PromptTarget.Seat(seats.single())
            else -> return PromptTarget.Ambiguous // 跨席位重名（fail-closed）
        }
    }
    if (targets.isEmpty()) return teamTarget ?: PromptTarget.None
    // 团队名与成员同时被点到（「@开发团队 让 @coder 修」）：两个意图，不替人合并。
    if (teamTarget != null) return PromptTarget.Ambiguous
    // ≥2 个目标（席位+组员混点 / 两个组员 / 跨席位）⇒ 歧义（§4.2：一次只 @ 一个目标）。
    // 同席位的「领队 token + 组员 token」同帧同样歧义 —— 用户意图不明，随机挑一个
    // 就是在替人做决定。
    return if (targets.size == 1) targets.single() else PromptTarget.Ambiguous
}
