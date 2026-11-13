// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.AgentDuties
import com.kapp.tenon.core.protocol.AgentMemberName
import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.core.protocol.sameAgent
import kotlinx.serialization.Serializable

/**
 * 一份**员工档案**：这台机器上某个 agent 的业务名与职责，住在本机设置里。
 *
 * ## 它解决的问题
 *
 * 员工原先没有对象。它是「群席位 + 工作区行 + 会话」的聚合投影（`AgentHubProjection`），
 * 名字在**入驻那一刻**由 `AgentMemberName.base(kind, agentId, duty)` 现场派生，并且
 * `unique` 是**按群**去重的 —— 于是同一个 agent 进两个群可以叫 `dsh-coder` 与
 * `dsh-coder-2`，而「这个员工到底叫什么」没有一处权威答案；想改名只能撤回再入驻
 * （`@` 历史全断，因为名字是随席位走的）。
 *
 * 档案把这件事反过来：**先有员工，再把它加进群**。群席位只持有 [employeeId] 引用
 * 与一份广播快照（`VirtualAgentMember.employeeName`）。
 *
 * ## 它不是什么
 *
 * - **不是 agent 的创建**：agent 是「这台机器上真的有一个进程在跑」，身份来自宣告；
 *   档案只做**登记 + 命名**（[agentId] 必须能在本机台账里找到，见 `EmployeeProfileWritePath`）。
 *   做成空表单「新建员工」只会产出点了派单静默失败的幽灵。
 * - **不是全局对象**：它住在宿主机本机，不进任何协议帧；对端拿到的是席位上的快照。
 * - **不是派单目标**：派单只认 agentId（`@` token 或组员 token），员工档案不参与派单解析。
 *
 * ## 名字与 token 是两个字段
 *
 * [name] 是人看的（可含中文，如「前端小王」）；[token] 是 `@` 后面要打的 ASCII 短名。
 * 分开是因为 `@` 之后是**输入法打出来的字**，中文名在补全里会出现「打了一半对不上」。
 * token 在这里定**一次**，各群席位默认用它 —— 这样跨群同名；某个群撞名时才在该群用别名
 * （席位 `name` 与档案 [token] 不同，[employeeIsAliased] 判定）。
 *
 * @property employeeId 档案键，`emp-` 前缀，由本机分配。席位上的
 *   `VirtualAgentMember.employeeId` 指向它。
 * @property agentId 本机 agent 身份键（稳定；**换底座不改它**）。同一 agent 在本机只有一份档案。
 * @property name 显示名（可含中文）。长度见 `Limits.NICK`（与昵称同预算）。
 * @property token `@` 默认 token（小写 ASCII，`AgentMemberName.slug` 产出）。改 [name] 时
 *   默认**不动它** —— token 一漂，`@` 历史就断。
 * @property duty 职责（归一后取值，见 `AgentDuties`）；只用于新席位的默认名与展示。
 * @property rev 档案版本，每次写入 +1（「重建是否真的发生了」的判据）。
 */
@Serializable
public data class EmployeeProfile(
    val employeeId: String,
    val agentId: String,
    val name: String,
    val token: String,
    val duty: String = AgentDuties.DEFAULT_SINGLE,
    val rev: Int = 0,
    val updatedTs: Long = 0,
) {
    /** 这份档案能被 `@` 的默认 token；空串（老数据/坏数据）回落 `agent`，绝不产出空 token。 */
    public fun effectiveToken(): String = token.trim().ifEmpty { "agent" }
}

/** 按 [employeeId] 找档案；null = 没有这份档案（如实区分「没有」与「空名字」）。 */
public fun List<EmployeeProfile>.byEmployeeId(employeeId: String?): EmployeeProfile? {
    val key = employeeId?.trim().orEmpty()
    if (key.isEmpty()) return null
    return firstOrNull { it.employeeId == key }
}

/** 按 agent 身份键找档案（一个 agent 在本机只有一份）。大小写不敏感，与席位归一同一口径。 */
public fun List<EmployeeProfile>.byAgentId(agentId: String?): EmployeeProfile? {
    val key = agentId?.trim().orEmpty()
    if (key.isEmpty()) return null
    return firstOrNull { it.agentId.equals(key, ignoreCase = true) }
}

/**
 * 按**身份**找档案：与智能体页投影（`hubAgents`）同一条判据（[sameAgent]，前缀归一）。
 *
 * 为什么不能只用 [byAgentId] 的精确比：档案里可能存的是短 id（`dsh`），而席位上写的是
 * 完整实例 id（`dsh-029bbbf3`）。精确比会让「卡片上明明写着员工名、入驻时却认不出这是谁」，
 * 于是同一个人在一端叫员工名、在另一端叫 `kind-职责`。展示与写入必须用同一把尺子。
 */
public fun List<EmployeeProfile>.byAgentIdentity(agentId: String?): EmployeeProfile? {
    val key = agentId?.trim().orEmpty()
    if (key.isEmpty()) return null
    return firstOrNull { sameAgent(it.agentId, null, key) }
}

/**
 * 哪些群正在引用这份档案（席位 `employeeId` 相等）—— **全仓唯一一份判定**。
 *
 * 两处读它：写路径（改名要重建哪些群的快照、注销要摘哪些群的引用）与界面（「他在几个群里」）。
 * 各写一遍 `filter` 的后果不是重复代码，是**判据分叉**：某天一方加了条件、另一边没加，
 * 界面就会把一个还在两个群里的员工写成「不在任何群」，或者反过来 —— 注销时少摘一个群，
 * 那个群的席位就一直挂着一份已经不存在的档案。
 *
 * 返回群对象而不只是 id：界面要判「空」还要群名，写路径要 id（`.map { it.groupId }`）。
 */
public fun List<GroupMeta>.groupsReferencingEmployee(employeeId: String): List<GroupMeta> =
    filter { group -> group.virtualAgents.orEmpty().any { it.employeeId == employeeId } }

/**
 * 入驻时这条席位该用的名字（本群 `@` token）：档案 token；库里脏了（非归一形态）回落
 * `kind-职责`。
 *
 * 与 [defaultEmployeeToken] 的回落、`AttachEmployee` 的 token 校验是同一条口径：
 * 席位名必须是**能直接打出来**的 ASCII 短名，脏 token 会产出「@ 了却叫不醒」的席位。
 * 同名冲突不在这里解决 —— 那是调用方的 token 池（`AgentMemberName.unique`）。
 */
public fun EmployeeProfile.seatToken(agentKind: String?, duty: String?): String {
    val raw = token.trim()
    if (raw.isNotEmpty() && raw == AgentMemberName.slug(raw)) return raw
    return AgentMemberName.base(agentKind, agentId, AgentDuties.normalize(duty ?: this.duty))
}

/**
 * 员工卡的显示名：**档案名优先，其次席位名（本群 token），最后才是身份键**。
 *
 * 与 `teamDisplayName` 同一条纪律：兜底顺序只写一处，避免调用方各自写 `?:` 时顺序不一致
 * （那会让同一个员工在两个页面上显示两个名字）。
 */
public fun employeeDisplayName(employeeName: String?, seatName: String?): String =
    employeeName?.trim()?.takeIf { it.isNotEmpty() }
        ?: seatName?.trim()?.takeIf { it.isNotEmpty() }
        ?: "agent"

/**
 * 这条席位是不是**本群别名**：席位 token 与该档案的 `@` 默认 token 不同。
 *
 * 判据必须用 [EmployeeProfile.token]（ASCII）而不是 [name]（可能是中文「前端小王」）——
 * 用档案名比会在**每个群**都判成别名。档案只在宿主机本机，所以这个判定也只在本机成立；
 * 对端（手机）只有快照，不需要知道「是否别名」，它显示 [name] 与席位 token 即可。
 */
public fun EmployeeProfile.isAliasedIn(seatName: String?): Boolean {
    val seat = seatName?.trim().orEmpty()
    return seat.isNotEmpty() && !token.equals(seat, ignoreCase = true)
}

/**
 * 新档案的默认 token：`slug(名字)` → 空则回落 `kind-职责` → 再在本机员工池里去重。
 *
 * 纯中文名（「前端小王」）`slug` 之后是空串，必须回落 —— 否则 `@` 后面是一个打不出来的
 * 空 token。回落用的是与入驻同一条规则（[AgentMemberName.base] + [AgentDuties.normalize]），
 * 不发明第二套拼写。
 *
 * @param taken 本机**已占用**的 token（其它档案的 [EmployeeProfile.token]）。
 */
public fun defaultEmployeeToken(
    name: String,
    agentKind: String?,
    agentId: String,
    duty: String?,
    taken: Set<String>,
): String {
    val fromName = AgentMemberName.slug(name)
    val base = if (fromName.isNotEmpty()) {
        fromName
    } else {
        AgentMemberName.base(agentKind, agentId, AgentDuties.normalize(duty))
    }
    return AgentMemberName.unique(base, taken)
}
