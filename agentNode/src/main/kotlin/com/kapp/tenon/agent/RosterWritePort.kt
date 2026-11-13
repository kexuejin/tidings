// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

/**
 * 档案面（员工 + 团队）的**读写端口**（P3 工具面：agent 自己组队）。
 *
 * ## 为什么这是一个端口，而不是在控制面里直接改设置
 *
 * 员工与团队档案都住在 `:app` 的 `RelaySettings` 里，唯一写路径是
 * `RelayController.applyEmployeeProfile` / `applyTeamProfile`。`:agentNode` 不依赖 `:app`
 * （控制面是 JVM 库，还要能被 headless 单进程用），所以只能在这里定义**接口**，
 * 由装配方（`:orchestration` 的 `DesktopExtension`，它握着 controller）实现。
 *
 * null = 没有装配 ⇒ 工具路由如实回 503，不假装能写（与 `/v1/agent/chain`、
 * `/v1/agent/invoke` 同一条「能力缺失就说缺」的纪律）。
 *
 * ## 为什么员工没有 token / agentId 参数
 *
 * agent 能改的只有**名字与职责**。`@` token 一漂群里所有人的输入习惯就断（见
 * `EmployeeProfile` 的 KDoc），`agentId` 是身份（换底座都不许动）。这两样不开口子，
 * 也就不需要「agent 能不能改 token」这种判据 —— 判据不存在比判据写对更安全。
 *
 * 团队同理：能改名字与名册，但**不能改 `teamId`**，也不在这里建/撤群席位
 * （进群走 `TeamProfileCommand.AddToGroups`，那个动作这一版没开放给 agent）。
 */
interface RosterWritePort {

    /** 本机档案快照（归属判定、审计前后值用）。只读。 */
    fun employees(): List<RosterEmployeeSnapshot>

    /**
     * 改一份员工档案。实现方负责：档案存在、跨群别名重建、落盘，以及**把行为记到 right actor 上**
     * —— `agentKey` 非空时审计应是 `AGENT(hostNodeId, agentKey)`，不是「人改的」
     * （回执与审计是这条工具面存在的证据）。
     *
     * @return [RosterWriteResult] —— **拒绝必须给可读原因**（哪个校验挂的），
     *   不许沉默：这套系统里「没反应」是最贵的故障形态。
     */
    fun updateEmployee(
        agentKey: String?,
        employeeId: String,
        name: String?,
        duty: String?,
    ): RosterWriteResult

    /**
     * 本机团队档案快照（`team.roster.read` 的唯一数据源）。
     *
     * 每份带 [RosterTeamSnapshot.groupIds] —— 「这支队在哪些群」是 agent 决定
     * 「能不能 @ 到它」的关键事实，缺了它读出来的名册等于一份没有落点的清单。
     */
    fun teams(): List<RosterTeamSnapshot>

    /** 建一份团队档案（`groupIds` 空 = 只建档、不进群，与界面同一条口径）。 */
    fun createTeam(agentKey: String?, draft: RosterTeamDraft): RosterWriteResult

    /** 改名 / 换名册（两者至少给一个；`members` 给空表由域层拒）。 */
    fun updateTeam(
        agentKey: String?,
        teamId: String,
        name: String?,
        members: List<RosterMember>?,
    ): RosterWriteResult
}

/** 本机一条员工档案的只读快照（工具面用；不含 token 之外的本机细节）。 */
data class RosterEmployeeSnapshot(
    val employeeId: String,
    val agentId: String,
    val name: String,
    val duty: String,
)

/** 名册里的一行（组员）。工具面读与写用同一个形状 —— 两个形状就要写两次映射，迟早漂。 */
data class RosterMember(
    val agentId: String,
    val name: String,
    val role: String,
)

/** 本机一份团队档案的只读快照。 */
data class RosterTeamSnapshot(
    val teamId: String,
    val name: String,
    val leaderAgentId: String,
    val members: List<RosterMember>,
    /** 正在引用这份档案的群（席位 `teamId` 相等）。空 = 建了档但没进任何群。 */
    val groupIds: List<String>,
    val rev: Int,
)

/** 建队时提交的内容（工具参数 → 写路径的桥）。 */
data class RosterTeamDraft(
    val name: String,
    val leaderAgentId: String,
    val members: List<RosterMember>,
    val groupIds: List<String>,
)

/** 写面的结果：成功带前后值（审计与回执都靠它），失败带分类码。 */
sealed interface RosterWriteResult {

    data class Applied(
        val before: RosterEmployeeSnapshot,
        val after: RosterEmployeeSnapshot,
    ) : RosterWriteResult

    /** 团队档案改动。`before == null` = 新建（回执据此说「建了」而不是「改了」）。 */
    data class TeamApplied(
        val before: RosterTeamSnapshot?,
        val after: RosterTeamSnapshot,
    ) : RosterWriteResult

    /**
     * 拒绝。**码 + 细节**，不带整句文案：端口住在 `:agentNode`，而拒绝要同时服务
     * agent（HTTP 回执）、审批卡（人读）、日志 —— 三处措辞不同，句子该由呈现层拼
     *（本仓的 `cjkLiteralGate` 也要求 orchestration 里没有硬编码文案）。
     */
    data class Denied(
        val reason: RosterDenial,
        val detail: String? = null,
    ) : RosterWriteResult
}

/** 写面拒绝的分类（机器可读；一句话文案见呈现层）。 */
enum class RosterDenial {
    /** 本机没有这份员工档案。 */
    UNKNOWN_EMPLOYEE,

    /** 本机没有这份团队档案。 */
    UNKNOWN_TEAM,

    /** 名字不合法（空 / 超长 / 与本机另一份档案撞了）。 */
    NAME_INVALID,

    /** 名册没过校验（空名、重名、超员、非 slug）。 */
    ROSTER_INVALID,

    /** 命令没给出要改的字段。 */
    ARGUMENT_INVALID,

    /** 发起方不在目标群里（跨群重建快照被拒）。 */
    NOT_MEMBER,

    /** 落盘被拒，什么都没改。 */
    PERSIST_REJECTED,

    /** 其它（detail 里带原始枚举名）。 */
    OTHER,
}
