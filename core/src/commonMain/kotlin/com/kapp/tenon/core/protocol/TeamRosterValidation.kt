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
    /** 组员数超过上限。 */
    TOO_MANY,
}

/** 一条结构化的校验问题：哪一行（[index]）/哪个字段/什么问题。UI 直接按它定位。 */
data class TeamRosterIssue(
    /** 草稿列表里的行号（0 起）；`COUNT` 类问题无行可指，为 -1。 */
    val index: Int,
    val field: TeamRosterField,
    val kind: TeamRosterIssueKind,
    /** 长度/数量上限（TOO_LONG / TOO_MANY 时给出）。 */
    val limit: Int? = null,
)

/**
 * 一行组员在**本群**最终用的 `@` token。
 *
 * [aliasedFrom] 非空 = 这个名字撞上了群内别人的 token，本群改叫 [name]（`coder-2`）。
 * **这不是错误**，是跟员工档案同一条纪律：档案给默认名，本群撞名就地取别名，
 * 不让「别人先占了名字」把整支团队卡在门外。
 */
data class TeamRosterName(val name: String, val aliasedFrom: String? = null) {
    /** 这一行是否被本群改了名（UI 据此显示「本群将叫 X」）。 */
    val isAliased: Boolean get() = aliasedFrom != null
}

/**
 * 团队编制校验（方案 §4.4）：**core 纯函数**，UI、领域写路径与测试共用同一份。
 *
 * ## 两件事，两个函数
 *
 * 1. [validate] —— **必须改的错**：空、超长、非 slug、名册内重名、超员。
 * 2. [resolveNames] —— **本群最终叫什么**：撞上群里别人的 token 就地取别名
 *    （`coder-2`），与员工档案 [AgentMemberName.unique] 同一条纪律。
 *
 * 分开的理由：撞名不是用户的错，是「别人先占了这个词」。以前把它当错误整体拒绝，
 * 于是「员工先建好、加进群」这件事会卡在一个纯粹由别人占名造成的原因上 ——
 * 而员工档案那条路一直是自动别名的，同一个产品里两种口径，用户看不懂。
 *
 * ## 冲突域（名字要避开的东西）
 *
 * 一个组员名将来要成为 `@` token，所以要避开**群内所有现在就能被 @ 的东西**：
 * 既有 `virtualAgents[].name` + `agentId` + 已知 agentKind（`mentionKeys` 的口径），
 * 加上**其它**团队声明的组员名 —— 大小写不敏感。人类昵称（`memberNicks`/`selfNick`）
 * **不是** token，不参与（人类昵称是展示数据，伪造它没有任何 @ 特权）。
 *
 * 池里**不包含**被编辑这条团队自己的既有组员名 —— 那些名字正在被这份草稿整体替换，
 * 留在池里会让「沿用旧名」误报冲突。领队自己的 token（席位 name/agentId/kind）
 * **要留在池里**：组员撞上领队名等于影子领队，必须拦（拦法也是别名，不减人）。
 *
 * ## 名册内重名仍然是错
 *
 * 同一份名册里两行都叫 `coder`：自动改成 `coder` / `coder-2` 会把用户的手误固化成
 * 两个不同的人，之后派单分不清谁是谁。这一条**必须让用户自己改**，与「撞外人」不同 ——
 * 外人的名字不是用户能改的，自己的笔误是。
 */
object TeamRosterValidation {

    /**
     * 校验一份组员草稿（**只报必须改的错**）。
     *
     * 没有 token 池参数是**故意的**：撞名不再是错，见 [resolveNames]。留一个用不上的池
     * 只会让下一个调用方以为「传了池就会拦撞名」。
     *
     * @param roster 要保存的组员草稿（空 = 关闭团队；调用方决定语义，这里空表恒合法）
     */
    fun validate(roster: List<TeamMemberDraft>): List<TeamRosterIssue> {
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
        val issues = mutableListOf<TeamRosterIssue>()
        val seenNames = HashMap<String, Int>()   // lowercase name → 首次出现的行号
        val seenAgentIds = HashMap<String, Int>() // lowercase agentId → 首次出现的行号
        roster.forEachIndexed { index, member ->
            // ---- name：非空、预算内、slug 形状、团队内唯一（撞外人另有 resolveNames）----
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

    /**
     * 每行组员在**本群**最终会用的 `@` token（顺序与 [roster] 一致）。
     *
     * 撞上 [takenTokens] 里已有的词就地取别名（`coder-2`、`coder-3`…），**不拒绝**。
     * 名册内部的行间冲突不在这里处理 —— 那是 [validate] 的 `DUPLICATE_IN_TEAM`，
     * 必须由用户改（自动编号会把笔误固化成两个不同的人）。
     *
     * 顺序敏感：同一个 base 出现两次时，第二个才会被编号；因此调用方必须传**同一份**
     * 顺序的草稿，且写席位时按同一顺序落盘 —— 否则「预览叫什么」与「实际叫什么」会分叉。
     */
    fun resolveNames(roster: List<TeamMemberDraft>, takenTokens: Set<String>): List<TeamRosterName> {
        // 池归一成小写：`unique` 是精确比较，池里的大小写混写会让 `Coder` 从 `coder` 底下漏过去。
        val taken = takenTokens.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toMutableSet()
        return roster.map { member ->
            val base = member.name.trim().lowercase()
            // 空名 / 形状非法不是这里的事：原样返回，让 validate 的错照常报出去。
            if (base.isEmpty()) return@map TeamRosterName(member.name.trim())
            val assigned = AgentMemberName.unique(base, taken)
            taken += assigned
            TeamRosterName(assigned, aliasedFrom = base.takeIf { it != assigned })
        }
    }
}
