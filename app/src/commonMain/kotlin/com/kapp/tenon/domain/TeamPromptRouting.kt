// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.core.protocol.occupiedTeamMentionSpans
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

    /**
     * 队还没进这个群（§4-8b）：token 命中**本机团队档案** ∧ 该群无席位。
     * 路由层照常唤醒（本机档案=本机宿主），编排层出 blocked 卡，主行动
     * 「把队加进这个群」—— 而不是静默不执行（修复前：候选里连整队行都没有，
     * 手打 token 也是「点了没反应」）。
     */
    data class ProfileTeam(val team: TeamProfile) : PromptTarget {
        val hostNodeId: String get() = ""
    }

    /** 同时点了多个目标 / 命中跨席位重名 —— 不执行，提示「一次只 @ 一个目标」。 */
    data object Ambiguous : PromptTarget
}

/**
 * @param matchableTokens 本群全部可被 `@` 的 token（= `groupParticipantRows().mentionKeys`，
 *   已含组员名 —— P0-0.1 的单源池）。这里只消费它做**命中集合**，不另建正则。
 */
fun resolvePromptTarget(
    text: String,
    group: GroupMeta?,
    matchableTokens: Set<String>,
    /** 本机团队档案（§4-8b 的兜底判据源）。缺省 = 不做档案兜底（旧行为）。 */
    teamProfiles: List<TeamProfile> = emptyList(),
): PromptTarget {
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

    // **跨池仲裁（同位前缀撞名）**：员工/席位 token 的命中若**整体落在团队名占住的
    // 区间里**（`@e2e-team` 内含 `@e2e` —— `-` 在 mentionsToken 的边界判据里是标点，
    // 短 token 会假命中），那不是「第二个意图」，是同一句话被更长的一个 token
    // 覆盖了 —— 最长命中优先，被覆盖的让位。真机 UX 轮现场：整队派单因此被判成
    // 「团队名与成员同时被点到」的歧义而**静默不执行**（连发送端提示都没有）。
    val teamSpans = group.occupiedTeamMentionSpans(text)
    fun coveredByTeamSpan(token: String): Boolean {
        val needle = "@" + token.trim()
        if (needle.isEmpty() || teamSpans.isEmpty()) return false
        var from = 0
        while (true) {
            val at = text.indexOf(needle, from, ignoreCase = true)
            if (at < 0) return false
            val end = at + needle.length
            val after = text.getOrNull(end)
            // 词边界与 mentionsToken 同判据：后面跟着标识符字符 ⇒ 那是另一个 token。
            val isHit = after == null || after !in 'a'..'z' && after !in 'A'..'Z' && after !in '0'..'9' && after != '_'
            if (isHit && teamSpans.any { (ts, te) -> at >= ts && end <= te }) return true
            from = at + 1
        }
    }

    val targets = LinkedHashSet<PromptTarget>()
    for (token in mentioned) {
        if (teamTarget != null && coveredByTeamSpan(token)) continue
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
    if (targets.isEmpty()) {
        // 档案兜底（§4-8b）：席位没接住、团队席位也没有 —— 档案 token 在这里落地。
        return teamTarget
            ?: profileTeamFallback(text, mentioned, teamProfiles)
            ?: PromptTarget.None
    }
    // 团队名与成员同时被点到（「@开发团队 让 @coder 修」）：两个意图，不替人合并。
    if (teamTarget != null) return PromptTarget.Ambiguous
    // ≥2 个目标（席位+组员混点 / 两个组员 / 跨席位）⇒ 歧义（§4.2：一次只 @ 一个目标）。
    // 同席位的「领队 token + 组员 token」同帧同样歧义 —— 用户意图不明，随机挑一个
    // 就是在替人做决定。
    return if (targets.size == 1) targets.single() else PromptTarget.Ambiguous
}

/**
 * 档案兜底（§4-8b）：席位池没接住、团队席位也没有 —— token 却命中**本机团队
 * 档案**（队名/组员名）⇒ 队还没进这个群，路由照常唤醒（编排层出 blocked 卡
 * 带「把队加进这个群」）。档案 token 只进路由池不进候选，这里是它唯一的落点。
 */
private fun profileTeamFallback(text: String, mentioned: List<String>, teamProfiles: List<TeamProfile>): PromptTarget? {
    if (teamProfiles.isEmpty() || mentioned.isEmpty()) return null
    for (token in mentioned) {
        val team = TeamProfileMention.byNameOrMemberToken(teamProfiles, token) ?: continue
        return PromptTarget.ProfileTeam(team)
    }
    return null
}

/**
 * 这一单派单该带的**点名键**：从**已解析的目标**取 —— 目标经过跨池仲裁
 * （[resolvePromptTarget]：前缀撞名、多目标歧义都在那里裁决过）。
 * `mentionedAgentKeys().firstOrNull()` 的字母序会让前缀撞名抢答：
 * `@e2e-team …` 的 first 是 `e2e`（`-` 在边界判据里是标点，短 token 假命中），
 * 整队派单被劫成单兵 —— 真机 UX 轮现场。
 *
 * agent-kind 点名（`@dsh …`：kind 池不是席位，路由层判 [PromptTarget.None]）
 * 回落既有的 firstOrNull —— 那一档执行侧要的是**最短的档案键**
 * （GroupMentionKeySourceTest 钉着），两种语义各归各。
 */
fun addressedAgentKey(target: PromptTarget?, text: String, agentKinds: Set<String>): String? =
    when (target) {
        is PromptTarget.Team -> target.teamName.trim().lowercase().takeIf { it.isNotEmpty() }
        is PromptTarget.Member -> target.member.name.trim().lowercase().takeIf { it.isNotEmpty() }
        is PromptTarget.Seat -> {
            val seat = target.seat
            // 席位档取**能归一到驱动的最短 token**（agentId / 席位名）：席位名可能是
            // 带后缀的长名（`gpu-runner-assistant`），执行侧要的键是短的
            // （GroupMentionKeySourceTest 钉着「点名事实取最短的档案键」）。
            RelayController.mentionedAgentKeys(text, agentKinds)
                .filter { it == seat.name.trim().lowercase() || it == seat.agentId.trim().lowercase() }
                .minByOrNull { it.length }
                ?: seat.name.trim().lowercase().takeIf { it.isNotEmpty() }
        }
        else -> RelayController.mentionedAgentKeys(text, agentKinds).firstOrNull()
    }

/**
 * 本机**团队档案**的 mention 匹配（§4-8b：队还没进群，`@` 也要有回应）。
 *
 * 单源判据：编排层的 [com.kapp.tenon.orchestration.TeamExecutionResolver] 与路由层
 * 共用这一份 —— 各写一份就是「@ 了没反应 / 执行了别人」那类分叉的种子。
 */
/** 对模块外开放：编排层的 TeamExecutionResolver 是第二个消费方（单源判据）。 */
object TeamProfileMention {

    /** 按**团队名**或**组员名**匹配档案（大小写不敏感）。同名两支：不猜（返回 null）。 */
    fun byNameOrMemberToken(profiles: List<TeamProfile>, key: String): TeamProfile? {
        val k = key.trim()
        if (k.isEmpty()) return null
        val byName = profiles.filter { it.name.trim().equals(k, ignoreCase = true) }
        if (byName.size == 1) return byName.single()
        if (byName.size > 1) return null // 同名两支：不猜（blocked 卡走 TARGET_MISSING 的改名引导）
        return profiles.firstOrNull { p -> p.members.any { it.name.trim().equals(k, ignoreCase = true) } }
    }

    /**
     * 档案的可 @ token（团队名 + 组员名；与席位 token 同一条可打性纪律 ——
     * 带空白的打不出来，不进池）。**只进路由池，不进候选**：没入驻的队不该出现在
     * @ 候选里（用户 @ 它的唯一语义就是「把队加进来」，那是 blocked 卡的主行动）。
     */
    fun mentionTokensOf(profiles: List<TeamProfile>): Set<String> = buildSet {
        profiles.forEach { p ->
            p.name.trim().takeIf { it.isNotEmpty() && it.none { c -> c.isWhitespace() } }
                ?.let { add(it.lowercase()) }
            p.members.forEach { m ->
                m.name.trim().takeIf { it.isNotEmpty() && it.none { c -> c.isWhitespace() } }
                    ?.let { add(it.lowercase()) }
            }
        }
    }
}
