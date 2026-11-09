// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

/**
 * 团队编制的**编辑期**草稿行（方案 §3.2「编辑保全 DTO 契约」）。
 *
 * 放在 core 是因为三处共用它：UI（TeamRosterDialog 的行状态）、领域
 * （handleSetGroupTeamDeclaration 的入参）、校验（[TeamRosterValidation]）。
 *
 * ## 编辑保全（这条契约的存在理由）
 *
 * [agentId] 是组员的**内部稳定 id** —— 加载既有团队时严格沿用
 * `TeamMemberInfo.agentId` 原值，**改名绝不重算**（重算等于把 v2 执行档
 * `team-execution.json` 的索引打断，组员变成孤儿）。只有**本次新建**的行
 * （[isNew] = true）才由界面生成 id（`"m-" + slug(name) + "-" + 随机 4 位`）。
 */
data class TeamMemberDraft(
    val agentId: String,
    /** `@` 识别 token：小写 ASCII slug；中文放 [role]。 */
    val name: String,
    /** 角色标签（编码/审查/测试…），自由文本。 */
    val role: String,
    val isNew: Boolean = false,
)

/** 校验问题落在哪一行、哪个字段。 */
enum class TeamRosterField { NAME, ROLE, AGENT_ID, COUNT }

/** 校验问题的种类。UI 据此选文案，不在这里拼人话（文案进 strings.xml）。 */
enum class TeamRosterIssueKind {
    /** 该行该字段为空。 */
    EMPTY,
    /** 超长度预算（[TeamRosterIssue.limit] 给出上限）。 */
    TOO_LONG,
    /** 不是合法的 @ slug（字符集/形状，见 [AgentMemberName.slug]）。 */
    BAD_SLUG,
    /** 团队内重复（大小写不敏感）。 */
    DUPLICATE_IN_TEAM,
    /** 与群内既有 `@` token 冲突（[TeamRosterIssue.conflictWith] 给出撞上的那个）。 */
    TAKEN,
    /** 组员数超过上限。 */
    TOO_MANY,
}

/** 一条结构化的校验问题：哪一行（[index]）/哪个字段/什么问题/跟谁冲突。UI 直接按它定位。 */
data class TeamRosterIssue(
    /** 草稿列表里的行号（0 起）；`COUNT` 类问题无行可指，为 -1。 */
    val index: Int,
    val field: TeamRosterField,
    val kind: TeamRosterIssueKind,
    /** 与谁冲突（TAKEN 时给撞上的既有 token）；其它种类为 null。 */
    val conflictWith: String? = null,
    /** 长度/数量上限（TOO_LONG / TOO_MANY 时给出）。 */
    val limit: Int? = null,
)

/**
 * 团队编制校验（方案 §4.4）：**core 纯函数**，UI、领域写路径与测试共用同一份。
 *
 * ## 冲突域（修正工作稿 T4 的那条）
 *
 * 一个组员名将来要成为 `@` token，所以它必须避开**群内所有现在就能被 @ 的东西**：
 * 既有 `virtualAgents[].name` + `agentId` + 已知 agentKind（`mentionKeys` 的口径），
 * 加上**其它**团队声明的组员名 —— 大小写不敏感。人类昵称（`memberNicks`/`selfNick`）
 * **不是** token，不参与（人类昵称是展示数据，伪造它没有任何 @ 特权）。
 *
 * 池里**不包含**被编辑这条团队自己的既有组员名 —— 那些名字正在被这份草稿整体替换，
 * 留在池里会让「沿用旧名」误报冲突。领队自己的 token（席位 name/agentId/kind）
 * **要留在池里**：组员撞上领队名等于影子领队，必须拦。
 *
 * ## 名字现在就按「未来的 token」收
 *
 * v1 不路由 `@组员`，但唯一性现在就保证 —— v2 上线时不必逼任何用户改名
 * （改名等于把别人正在用的名字打断）。中文放 [TeamMemberDraft.role]。
 */
object TeamRosterValidation {

    /**
     * 校验一份组员草稿。
     *
     * @param roster 要保存的组员草稿（空 = 关闭团队；调用方决定语义，这里空表恒合法）
     * @param takenTokens 群内所有可被 `@` 的既有 token（大小写不敏感比较）；
     *   不含 [roster] 自己 —— 见类 KDoc 的冲突域说明。
     */
    fun validate(roster: List<TeamMemberDraft>, takenTokens: Set<String>): List<TeamRosterIssue> {
        if (roster.size > Limits.TEAM_MEMBERS_MAX) {
            return listOf(
                TeamRosterIssue(
                    index = -1,
                    field = TeamRosterField.COUNT,
                    kind = TeamRosterIssueKind.TOO_MANY,
                    limit = Limits.TEAM_MEMBERS_MAX,
                ),
            )
        }
        val takenLower = takenTokens.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        val issues = mutableListOf<TeamRosterIssue>()
        val seenNames = HashMap<String, Int>()   // lowercase name → 首次出现的行号
        val seenAgentIds = HashMap<String, Int>() // lowercase agentId → 首次出现的行号
        roster.forEachIndexed { index, member ->
            // ---- name：非空、预算内、slug 形状、团队内唯一、群内不撞 token ----
            val name = member.name.trim()
            when {
                name.isEmpty() -> issues += TeamRosterIssue(index, TeamRosterField.NAME, TeamRosterIssueKind.EMPTY)
                name.length > Limits.NICK ->
                    issues += TeamRosterIssue(index, TeamRosterField.NAME, TeamRosterIssueKind.TOO_LONG, limit = Limits.NICK)
                name != AgentMemberName.slug(name) ->
                    issues += TeamRosterIssue(index, TeamRosterField.NAME, TeamRosterIssueKind.BAD_SLUG)
            }
            val nameLower = name.lowercase()
            val firstNamed = seenNames[nameLower]
            if (firstNamed == null) {
                seenNames[nameLower] = index
            } else if (name.isNotEmpty()) {
                issues += TeamRosterIssue(index, TeamRosterField.NAME, TeamRosterIssueKind.DUPLICATE_IN_TEAM)
            }
            if (name.isNotEmpty() && nameLower in takenLower) {
                issues += TeamRosterIssue(index, TeamRosterField.NAME, TeamRosterIssueKind.TAKEN, conflictWith = nameLower)
            }

            // ---- role：非空、预算内（自由文本，允许中文）----
            val role = member.role.trim()
            if (role.isEmpty()) {
                issues += TeamRosterIssue(index, TeamRosterField.ROLE, TeamRosterIssueKind.EMPTY)
            } else if (role.length > Limits.TEAM_ROLE) {
                issues += TeamRosterIssue(index, TeamRosterField.ROLE, TeamRosterIssueKind.TOO_LONG, limit = Limits.TEAM_ROLE)
            }

            // ---- agentId：非空、预算内、slug 归一、团队内唯一（大小写不敏感）----
            val agentId = member.agentId.trim()
            when {
                agentId.isEmpty() -> issues += TeamRosterIssue(index, TeamRosterField.AGENT_ID, TeamRosterIssueKind.EMPTY)
                agentId.length > Limits.ID ->
                    issues += TeamRosterIssue(index, TeamRosterField.AGENT_ID, TeamRosterIssueKind.TOO_LONG, limit = Limits.ID)
                agentId != AgentMemberName.slug(agentId) ->
                    issues += TeamRosterIssue(index, TeamRosterField.AGENT_ID, TeamRosterIssueKind.BAD_SLUG)
            }
            val idLower = agentId.lowercase()
            val firstId = seenAgentIds[idLower]
            if (firstId == null) {
                seenAgentIds[idLower] = index
            } else if (agentId.isNotEmpty()) {
                issues += TeamRosterIssue(index, TeamRosterField.AGENT_ID, TeamRosterIssueKind.DUPLICATE_IN_TEAM)
            }
        }
        return issues
    }
}
