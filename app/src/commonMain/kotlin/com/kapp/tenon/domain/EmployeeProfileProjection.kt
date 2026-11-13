// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.AgentDuties
import com.kapp.tenon.core.protocol.GroupMeta

/**
 * 员工**档案管理**的一行：登记簿视角，不是运行时视角。
 *
 * ## 为什么它不能被卡片流代替
 *
 * 名录（[hubAgents]）是「谁在跑」—— 一张卡要有一份运行时事实（会话 / 目录宣告 / 群席位）
 * 才装得出来。档案是「谁登记过」：它先于任何群存在（`Create` 允许一个群都不进），也可以在
 * 席位全撤之后继续存在。于是同一个人在名录里可能**完全没有位置**，而档案还在设置里；
 * 更要紧的是，注销档案此前没有任何界面入口 —— 库里删得掉、界面上找不到。
 *
 * 所以这一层回答的是三个只有登记簿答得出的问题：**它叫什么、`@` 是什么、在几个群里**。
 *
 * ## 它不是什么
 *
 * - **不是运行时状态**：这里没有在线 / 待批 / 最近活动，档案里也没有这些字段；
 * - **不是协议对象**：`employeeProfiles` 只住宿主机本机（不进任何协议帧），
 *   手机端读到的恒为空列表 ⇒ 那一端整段不画，与团队档案同一条纪律。
 *
 * @property employeeId 档案键（编辑 / 注销都按它定位，不按名字 —— 名字会变）。
 * @property agentId 本机 agent 身份键。换底座不改它，所以它是这份档案真正指向的东西。
 * @property name 档案名（可含中文）；空串（坏数据）回落身份键，绝不画一行没有标题的卡。
 * @property token `@` 默认 token（`effectiveToken()` 已兜底，不会是空串）。
 * @property duty 岗位（归一后取值，见 [AgentDuties]）；与编辑对话框的初始值同源。
 * @property groupNames 正在引用这份档案的群名（按群声明序，去重后不漏）。
 *   空 = 没有任何群在用它 —— 这不是错误，`Create` 允许「先建人、后进群」。
 */
public data class AgentEmployeeProfileUi(
    val employeeId: String,
    val agentId: String,
    val name: String,
    val token: String,
    val duty: String,
    val groupNames: List<String>,
)

/**
 * 一个**可以被登记**的 agent：候选行的全部内容（点它就是「给这个人建档」）。
 *
 * 与 [AgentEmployeeProfileUi] 的区别是「谁」：那一档说的是**已经有了档案**的人，
 * 这一档说的是**还没有档案**的本机 agent。两者互斥（[employeeProfileCandidates] 已按
 * 身份键把已建档的剔掉），所以档案页永远不会给出一张「点了会说已存在」的行。
 *
 * @property agentId 身份键（写路径的 `Create.agentId`，也是 `sameAgent` 归一的输入）。
 * @property name 现有显示名（档案名没有，所以是席位 token 或身份键）—— 只做**识别**用，
 *   不预填进档案姓名：档案名的意义就是人给的名字，预填一个 `dsh-coder` 只会被原样存下去。
 * @property hostName 宿主显示名（这台机器）。null = 节点表里还不认识它。
 */
public data class AgentEmployeeCandidateUi(
    val agentId: String,
    val name: String,
    val hostName: String?,
)

/**
 * 本机**还没建档**的 agent（档案页「新建档案」的候选表）。
 *
 * ## 为什么从卡片流取，而不是从「本机 agent 台账」自己扫一遍
 *
 * 卡片流（[hubAgents]）已经把同一件事算过一次：会话与宣告按身份键聚成一个实体、宿主机
 * 归到 `hostNodeId`、档案引用落在 `employeeId`。再扫一遍 `agentWorkspaces` 就是第二份
 * 归一化 —— 短 id（`dsh`）与实例 id（`dsh-029bbbf3`）会在这两份里各聚一次，于是候选表
 * 与名录对同一个 agent 给出两行（或一行都不给）。
 *
 * ## 两条过滤，各挡一类错
 *
 * 1. **只留本机**（`hostNodeId == selfNodeId`）：档案住宿主本机设置，把别人的 agent 登记成
 *    自己的员工，`Create` 的台账检查会以 `EMPLOYEE_UNKNOWN` 拒掉（那张卡的真实所有权在对面）。
 * 2. **只留没有档案的**：`employeeId != null` 的实体已有档案，这里再列一次就是「点了说已存在」。
 *    判据用 `employeeId` 而不是名字/身份键 —— 那是投影给的**引用**，与注销时摘的是同一条。
 *
 * `agentKey == null`（未上报类型）不进候选：没有身份键就没有可写的 `agentId`，
 * 造一个出来只会得到一座派不出去的幽灵。
 */
public fun employeeProfileCandidates(
    cards: List<AgentHubUi>,
    selfNodeId: String,
): List<AgentEmployeeCandidateUi> {
    val self = selfNodeId.trim()
    if (self.isEmpty()) return emptyList()
    val seen = LinkedHashSet<String>()
    return cards
        .asSequence()
        .filter { it.hostNodeId == self && it.employeeId == null }
        .mapNotNull { card ->
            val agentId = card.agentKey?.trim().orEmpty()
            if (agentId.isEmpty()) return@mapNotNull null
            // 同一身份键只留一行（卡片流按宿主+身份键聚类，重复来自同一 agent 的多条来源）。
            if (!seen.add(agentId.lowercase())) return@mapNotNull null
            AgentEmployeeCandidateUi(
                agentId = agentId,
                // 档案名 → 席位 token → 身份键：投影已经按这个顺序算好 `displayName`，
                // 只剩「都没有」这一档（未上报）落回身份键 —— 候选行得有标题。
                name = card.displayName?.trim()?.takeIf { it.isNotEmpty() } ?: agentId,
                hostName = card.hostName,
            )
        }
        .sortedBy { it.name }
        .toList()
}

/**
 * 本机登记过的员工档案（档案管理页的唯一数据源）。
 *
 * ## 判据与写路径同一份
 *
 * 「在几个群里」走 [groupsReferencingEmployee] —— 也就是注销时真的会去摘引用的那几个群。
 * 各写一遍 filter 的话，界面可能说「在 2 个群里」而注销只摘了 1 个：用户以为清干净了，
 * 另一个群的席位还挂着一份已经删掉的档案。
 *
 * ## 排序
 *
 * 按名字钉稳定序（`sortedBy` 稳定，重名保持登记序）。档案里没有「最近使用」这类时间戳
 * 可以排 —— 只有 `updatedTs`（最后一次改名），拿它排会让列表在改名后跳一下。
 */
public fun employeeProfileLedger(settingsSlice: SettingsSlice): List<AgentEmployeeProfileUi> {
    val settings = settingsSlice.settings
    val profiles = settings.employeeProfiles
    if (profiles.isEmpty()) return emptyList()
    val groups: List<GroupMeta> = settings.groups
    return profiles
        .map { profile ->
            AgentEmployeeProfileUi(
                employeeId = profile.employeeId,
                agentId = profile.agentId,
                // 档案名由写路径保证非空；这里仍兜一层身份键 —— 坏数据不该变成一行空白行。
                name = profile.name.trim().ifEmpty { profile.agentId },
                token = profile.effectiveToken(),
                duty = AgentDuties.normalize(profile.duty),
                groupNames = groups.groupsReferencingEmployee(profile.employeeId).map { it.name },
            )
        }
        .sortedBy { it.name }
}
