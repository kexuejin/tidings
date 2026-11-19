// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 洞 A 探针：新建员工（`MINT`）派单后，会话卡身份必须收敛到档案的 `employeeId`（方案 §2②）。
 *
 * ## 终案（三层正交模型）
 *
 * 身份锚是 `employeeId`，`agentId`（含 hex）只是路由线索。卡面身份 = 会话登记透传的
 * `employeeId`，`employeeProfileOf` 按它精确命中档案。**只有 `employeeId` 是身份。**
 *
 * ## 为什么这条只测「按 employeeId 精确归档」，不测整条派单链
 *
 * 文档 §7 把它写成「MINT 建档 → 挂席位 → @派单 → 会话卡」的 E2E。但把 E2E 拆到域层时，
 * 现状的 `AgentSessionUi` 与 `agent-session` 帧**根本没有 employeeId 字段**——直接写
 * `session(employeeId = …)` 会编译失败，那不是「断言红」而是「代码不存在」，不合格的探针。
 * 所以这条钉住终案真正要修的那个**判据**：卡带着自己的 employeeId 时，`employeeProfileOf`
 * 必须按它精确命中，而不是拿 agentKey 走 `sameAgent` 前缀归一去猜。
 *
 * 现状必红：`employeeProfileOf` 忽略 `card.employeeId`，走
 * `profiles.firstOrNull { sameAgent(it.agentId, null, card.agentKey) }` —— 前缀归一会把
 * 同底座的另一份档案认成这一份，或卡 agentKey 与档案 agentId 精度不同时归错。
 * 终案（按 `card.employeeId == profile.employeeId` 精确）下转绿。
 *
 * 完整的「帧透传 employeeId → 卡面」链路在四段改造落地后，用集成测试补验（见方案 §7）；
 * 本探针是它的判据层前置，先红在这里。
 */
class MintIdentityConvergenceTest {

    private val self = "self-node-0001"

    /** 卡带着透传来的身份锚；agentKey 故意保留更粗的 kind 级值，用来暴露「按前缀猜」的错。 */
    private fun card(employeeId: String, agentKey: String?) = AgentHubUi(
        hostNodeId = self,
        hostName = "本机",
        agentKey = agentKey,
        displayName = employeeId,
        employeeId = employeeId,
        employeeName = null,
        isTeam = false,
        teamSize = 0,
        groupCount = 0,
        workspaceCount = 0,
        online = true,
        pendingCount = 0,
        rows = emptyList(),
        primaryWsId = null,
        primaryWsTitle = null,
        lastActivityAt = 0,
    )

    private fun profile(employeeId: String, agentId: String) = AgentEmployeeProfileUi(
        employeeId = employeeId,
        agentId = agentId,
        name = "小码",
        token = "dsh-coder",
        duty = "coder",
        groupNames = emptyList(),
    )

    @Test
    fun aMintedCardConvergesToItsOwnEmployeeId() {
        // 两份档案同底座（都是 dsh）—— MINT 建出来的人可以同底座、人设不同。
        val profiles = listOf(
            profile(employeeId = "emp-alice", agentId = "dsh"),
            profile(employeeId = "emp-bob", agentId = "dsh"),
        )
        // 派单后的会话卡透传了 emp-bob；agentKey 只是 kind 级 "dsh"。
        val bobCard = card(employeeId = "emp-bob", agentKey = "dsh")

        val hit = employeeProfileOf(bobCard, profiles, self)
        assertEquals(
            "emp-bob",
            hit?.employeeId,
            "卡面身份必须是透传的 employeeId 并精确命中同一份档案；" +
                "现状走 sameAgent(agentId, agentKey) + firstOrNull，会归到第一份 emp-alice（假收敛）",
        )
    }
}
