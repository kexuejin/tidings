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
 * 从群文本里解析 `@<组员名>`。没有组员命中时返回 null（调用方回落席位级解析）。
 */
fun GroupMeta.resolveTeamMention(text: String): TeamMention? {
    val seats = virtualAgents.orEmpty().filter { it.isTeam }
    if (seats.isEmpty()) return null
    val ownersByToken = LinkedHashMap<String, MutableList<VirtualAgentMember>>()
    for (seat in seats) {
        for (member in seat.teamMembers) {
            val token = member.name.trim().lowercase()
            if (token.isEmpty()) continue
            ownersByToken.getOrPut(token) { mutableListOf() }.add(seat)
        }
    }
    if (ownersByToken.isEmpty()) return null
    val hit = ownersByToken.keys.sortedByDescending { it.length }
        .firstOrNull { token -> mentionsToken(text, token) } ?: return null
    return resolveTeamMemberToken(hit)
}

/**
 * 按**给定 token**（已小写语义，内部归一）精确解析组员 —— 不做文本匹配。
 *
 * [resolveTeamMention]（长 token 优先、按文本）与路由层按命中集合逐个分类
 * 都走这一份席位归属判定 —— 单源。返回 null = 这个 token 不是任何 isTeam
 * 席位的组员；[TeamMention.Ambiguous] = 跨席位重名（fail-closed）。
 */
fun GroupMeta.resolveTeamMemberToken(token: String): TeamMention? {
    val key = token.trim().lowercase()
    if (key.isEmpty()) return null
    val seats = virtualAgents.orEmpty().filter { it.isTeam }
    if (seats.isEmpty()) return null
    val hits = seats.mapNotNull { seat ->
        seat.teamMembers.firstOrNull { it.name.trim().lowercase() == key }?.let { seat to it }
    }
    if (hits.isEmpty()) return null
    if (hits.size > 1) return TeamMention.Ambiguous
    val (seat, member) = hits.first()
    return TeamMention.Hit(seat, member)
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
