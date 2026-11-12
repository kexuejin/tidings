// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.AgentDuties
import com.kapp.tenon.core.protocol.AgentMemberName
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
