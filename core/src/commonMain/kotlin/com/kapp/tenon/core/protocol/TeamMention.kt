// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

/**
 * 团队执行（v2）的**成员 mention 解析** —— token → (席位, 组员) 的单源判据。
 *
 * ## 为什么在 core
 *
 * 三个消费方必须用同一份判定：app 的路由层（`resolvePromptTarget` 决定"谁执行"）、
 * 编排层的团队分支（`resolveTeamTarget` 决定"编译成什么"）、以及将来的 @ 候选。
 * 各写一份解析就是「@ 了没反应 / 执行了别人」那类分叉的种子。
 *
 * ## 判据（方案 team-execution §4.1）
 *
 * - 只有 `isTeam` 席位的组员参与：单兵席位即使本地有执行档，也不被 @ 解析成团队；
 * - 组员 token = `TeamMemberInfo.name`（ASCII slug，声明层已收紧）；比较**大小写不敏感**；
 * - **长 token 优先**（与 [mentionedVirtualAgent] 同一套：`@coder-review` 不该被 `@coder`
 *   抢先命中）；词边界复用 [mentionsToken] 的 ASCII 判据（`@dsh你好` 命中 `@dsh`）;
 * - **重复 token fail-closed**：元数据被手改成跨席位重名时返回 [TeamMention.Ambiguous]，
 *   绝不"挑一个"——挑错席位就是在别人的仓里跑命令。
 *
 * 只解析**最长命中**的那一个 token；「一次 @ 了多个目标」的歧义由路由层
 * （app 的 `resolvePromptTarget`）按全部命中集合判定，这里不重复。
 */
sealed interface TeamMention {
    /** 命中：[seat] 是组员所属的席位（执行主体），[member] 是被点名的组员。 */
    data class Hit(val seat: VirtualAgentMember, val member: TeamMemberInfo) : TeamMention

    /** 同一 token 挂在 ≥2 个席位上（手改帧/坏数据）——不发执行。 */
    data object Ambiguous : TeamMention
}

/**
 * 从群文本里解析 `@<组员名>` 或 `@<岗位角色>`。没有组员命中时返回 null（调用方回落席位级解析）。
 */
fun GroupMeta.resolveTeamMention(text: String): TeamMention? {
    val seats = virtualAgents.orEmpty().filter { it.isTeam }
    if (seats.isEmpty()) return null
    val ownersByToken = LinkedHashMap<String, MutableList<VirtualAgentMember>>()
    for (seat in seats) {
        for (member in seat.teamMembers) {
            val token = member.name.trim().lowercase()
            if (token.isNotEmpty()) {
                ownersByToken.getOrPut(token) { mutableListOf() }.add(seat)
            }
            for (rt in roleTokens(member.role)) {
                ownersByToken.getOrPut(rt) { mutableListOf() }.add(seat)
            }
        }
    }
    if (ownersByToken.isEmpty()) return null
    val hit = ownersByToken.keys.sortedByDescending { it.length }
        .firstOrNull { token -> mentionsToken(text, token) } ?: return null
    return resolveTeamMemberToken(hit)
}

/**
 * 岗位角色别名集（ASCII 归一）。
 */
private fun roleTokens(role: String): Set<String> {
    val r = role.trim().lowercase()
    if (r.isEmpty()) return emptySet()
    val tokens = mutableSetOf(r)
    when (r) {
        "leader", "lead" -> tokens.addAll(listOf("leader", "lead"))
        "coder", "worker", "dev", "developer" -> tokens.addAll(listOf("coder", "worker", "dev", "developer"))
        "reviewer", "review" -> tokens.addAll(listOf("reviewer", "review"))
        "tester", "test", "qa" -> tokens.addAll(listOf("tester", "test", "qa"))
    }
    return tokens
}

/**
 * 按**给定 token**（已小写语义，内部归一）精确解析组员 —— 不做文本匹配。
 *
 * [resolveTeamMention]（长 token 优先、按文本）与路由层按命中集合逐个分类
 * 都走这一份席位归属判定 —— 单源。
 * 判定优先匹配成员名（name），次选匹配岗位角色（role）及其别名。
 * 返回 null = 这个 token 不是任何 isTeam 席位的组员或角色；
 * [TeamMention.Ambiguous] = 跨席位或同席位重名/重角色（fail-closed）。
 */
fun GroupMeta.resolveTeamMemberToken(token: String): TeamMention? {
    val key = token.trim().lowercase()
    if (key.isEmpty()) return null
    val seats = virtualAgents.orEmpty().filter { it.isTeam }
    if (seats.isEmpty()) return null

    // 1. 优先按组员名称 (name) 匹配
    val nameHits = seats.flatMap { seat ->
        seat.teamMembers.filter { it.name.trim().lowercase() == key }.map { seat to it }
    }
    if (nameHits.isNotEmpty()) {
        if (nameHits.size > 1) return TeamMention.Ambiguous
        val (seat, member) = nameHits.first()
        return TeamMention.Hit(seat, member)
    }

    // 2. 次选按岗位角色 (role) 及其别名匹配
    val roleHits = seats.flatMap { seat ->
        seat.teamMembers.filter { roleTokens(it.role).contains(key) }.map { seat to it }
    }
    if (roleHits.isNotEmpty()) {
        if (roleHits.size > 1) return TeamMention.Ambiguous
        val (seat, member) = roleHits.first()
        return TeamMention.Hit(seat, member)
    }

    return null
}

/**
 * `@<团队名>` 的解析结果 —— **整队**目标的单源判据。
 *
 * ## 为什么团队名要单独一条解析
 *
 * 团队名（[VirtualAgentMember.teamName]）是**展示串**，可含中文与空格，和组员 token
 * （ASCII slug）不是同一种东西；把它塞进 `ownersByToken` 会让「团队名恰好等于某个
 * 组员名」变成一次静默抢词。所以这里单独判，并**显式报冲突**，不猜。
 *
 * ## 判据
 *
 * - 只认 `isTeam` 席位上的 [VirtualAgentMember.teamName] 快照（档案化团队的广播投影）；
 * - 比较大小写不敏感，**长名优先**（`@开发团队二组` 不该被 `@开发团队` 抢先命中），
 *   词边界复用 [mentionsToken] 的 ASCII 判据（中文后直接接内容仍算命中）；
 * - 同名挂多条席位 / 多个 teamId ⇒ [Ambiguous]（不挑一支）；
 * - 团队名同时是某个席位 token 或组员 token ⇒ [Ambiguous]：这时候 `@` 永远先命中
 *   成员，团队名等于**永远打不到**，必须让用户改名，而不是让两套语义抢一个词。
 */
sealed interface TeamNameMention {
    /** 命中：[seats] 是该团队在本群的席位（正常恰好一条），[seat] 是取用目标。 */
    data class Hit(
        val teamId: String?,
        val teamName: String,
        val seat: VirtualAgentMember,
    ) : TeamNameMention

    /** 同名挂多席位 / 与成员 token 撞名 —— 不执行，提示改名。 */
    data object Ambiguous : TeamNameMention
}

/**
 * 从群文本里解析 `@<团队名>`。没有团队名命中时返回 null（调用方继续用席位/组员判据）。
 *
 * **不用成员 token 池判撞名**：团队名自己也在池里（它就是可 @ 的 token），拿池判会
 * 自己撞自己。撞名判据改成直接问两个成员判定函数 —— 那才是「`@` 会先命中谁」的答案。
 */
fun GroupMeta.resolveTeamNameMention(text: String): TeamNameMention? {
    val teams = mentionableTeams()
    if (teams.isEmpty()) return null
    val hit = teams.keys.sortedByDescending { it.length }
        .firstOrNull { name -> mentionsToken(text, name) } ?: return null
    return teamNameVerdict(teams.getValue(hit), hit)
}

/**
 * 按**给定 token**（不做文本匹配）找团队席位 —— 与 [resolveTeamNameMention] 同一份判据。
 *
 * 消费方是宿主侧派单：发送侧已经把 `mentionedAgentKey` 归一成一个 token，那里没有原文，
 * 只能按 token 反查。**不要在这里另写一份精确比较**：两支同名队、团队名与成员撞名
 * 这两种情形若两处判得不一样，「@ 了团队」就会时而执行时而静默。
 */
fun GroupMeta.teamSeatMatchingName(token: String): TeamNameMention? {
    val key = token.trim()
    if (key.isEmpty()) return null
    val teams = mentionableTeams()
    val name = teams.keys.firstOrNull { it.equals(key, ignoreCase = true) } ?: return null
    return teamNameVerdict(teams.getValue(name), name)
}

/**
 * 可 @ 的团队名 → 该名的全部席位。
 *
 * `@` 打不出带空白的名字（输入法会在空格处断开），这类团队名不参与解析 ——
 * 不是「不许起」，而是「起了也 @ 不到」，由界面提示改名，别让它静默失效。
 */
internal fun GroupMeta.mentionableTeams(): Map<String, List<VirtualAgentMember>> {
    val out = LinkedHashMap<String, MutableList<VirtualAgentMember>>()
    for (seat in virtualAgents.orEmpty()) {
        if (!seat.isTeam) continue
        val name = seat.teamName?.trim().orEmpty()
        if (name.isEmpty() || name.any { it.isWhitespace() }) continue
        val existing = out.keys.firstOrNull { it.equals(name, ignoreCase = true) } ?: name
        out.getOrPut(existing) { mutableListOf() }.add(seat)
    }
    return out
}

/** 同名多席位 / 与成员 token 撞名 ⇒ [TeamNameMention.Ambiguous]；唯一命中才给 Hit。 */
private fun GroupMeta.teamNameVerdict(
    seats: List<VirtualAgentMember>,
    name: String,
): TeamNameMention {
    // 同一支队在本群必须只有一条席位：>1 时「派给谁」没有答案（两支同名队同此）。
    if (seats.size > 1) return TeamNameMention.Ambiguous
    // 与成员 token 撞名：`@` 会先命中成员，团队名等于打不到 —— 报歧义让用户改名。
    if (resolveTeamMemberToken(name) != null) return TeamNameMention.Ambiguous
    if (seatsMatchingToken(name).isNotEmpty()) return TeamNameMention.Ambiguous
    val seat = seats.single()
    // 位置实参：`teamName = …` 这种命名实参会命中「团队名写入点」的源码守卫
    // （守卫认的是赋值形状），而这里只是把读到的快照搬进解析结果，不是写席位。
    return TeamNameMention.Hit(
        seat.teamId?.trim()?.takeIf { it.isNotEmpty() },
        name,
        seat,
    )
}

/**
 * 一个 token（已小写）在群元数据里命中的**席位 token 目标**：
 * 席位名 / 席位 agentId 之一（大小写不敏感）。
 *
 * 宿主自报的 kind（`dsh`）不在群元数据里 —— 它走席位的 agentId 档（声明时兜底），
 * 解析不到就不产生目标（路由层回落既有行为）。返回命中席位列表；>1 = 跨席位重名
 * （fail-closed，与 [resolveTeamMention] 同一条纪律）。
 */
fun GroupMeta.seatsMatchingToken(token: String): List<VirtualAgentMember> {
    val key = token.trim().lowercase()
    if (key.isEmpty()) return emptyList()
    return virtualAgents.orEmpty().filter { seat ->
        seat.name.trim().lowercase() == key || seat.agentId.trim().lowercase() == key
    }
}
