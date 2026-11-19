// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.AgentDuties
import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.core.protocol.sameEmployee

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
    val driverKey: String = "",
    val model: String? = null,
)

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
                driverKey = profile.driverKey,
                model = profile.model,
            )
        }
        .sortedBy { it.name }
}

/**
 * 员工卡 → 它的**本机**档案（卡片行上那颗「编辑」键的唯一判据）。
 *
 * ## 判据是 employeeId 精确相等（三层正交模型）
 *
 * 身份锚是 `employeeId`，`agentId`（含 hex）只是路由线索。若拿 [sameAgent] 去撞
 * `agentId`，同底座的两个真人会并到第一份档案（洞 E）—— 第二个人永远找不到自己的
 * 编辑入口。这里改用 [sameEmployee] 按 `card.employeeId` 精确匹配，各归各档。
 *
 * ## 为什么还要先看宿主
 *
 * `employeeProfiles` 只住宿主本机设置：远端卡即便碰巧带着相同 employeeId，也不该在本机
 * 冒出一颗编辑键。所以宿主不是本机 ⇒ 没有档案，一颗键都不给。
 *
 * @param selfNodeId 本机节点 id；空串（身份还在恢复）时一律 null —— 宁可暂时不给入口，
 *   也不拿一个未知身份去配对。
 */
public fun employeeProfileOf(
    card: AgentHubUi,
    profiles: List<AgentEmployeeProfileUi>,
    selfNodeId: String,
): AgentEmployeeProfileUi? {
    val self = selfNodeId.trim()
    if (self.isEmpty() || card.hostNodeId != self) return null
    return profiles.firstOrNull { sameEmployee(it.employeeId, card.employeeId) }
}
