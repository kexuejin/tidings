// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 档案页「新建档案」的候选表（[employeeProfileCandidates]）判据。
 *
 * 这张表决定用户能把谁登记成员工，所以每条过滤都对应一类**会静默出错**的选择：
 *
 * - 收进别人的 agent ⇒ 存下去被写路径以 `EMPLOYEE_UNKNOWN` 拒掉（横幅是红的，但用户
 *   在对话框里明明选中了它 —— 「看得见却存不下」是最难自证的一种错）；
 * - 收进已建档的 agent ⇒ `EMPLOYEE_EXISTS`，同一份档案两个入口打架；
 * - 收进没有身份键的卡 ⇒ 拿不出可写的 `agentId`，只能编一个，于是产出一座派不出去的幽灵；
 * - 空 `selfNodeId` 时不返回空表 ⇒ 会把**所有宿主**的 agent 都当成候选（身份还在恢复时）。
 *
 * 反向探针：去掉任一条 `filter` / `if (self.isEmpty())` / `seen.add` 去重，对应用例变红。
 */
class EmployeeProfileCandidatesTest {

    private val self = "self-node-0001"
    private val peer = "host-other-0002"

    private fun card(
        hostNodeId: String = self,
        hostName: String? = "本机",
        agentKey: String? = "dsh",
        displayName: String? = "dsh-coder",
        employeeId: String? = null,
        employeeName: String? = null,
    ) = AgentHubUi(
        hostNodeId = hostNodeId,
        hostName = hostName,
        agentKey = agentKey,
        displayName = displayName,
        employeeId = employeeId,
        employeeName = employeeName,
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

    @Test
    fun onlyLocalAgentsWithoutAProfileAreCandidates() {
        val rows = employeeProfileCandidates(
            cards = listOf(
                card(agentKey = "dsh", displayName = "dsh-coder"),
                // 已建档：档案页那一档里已经有它，这里再来一次就是「点了说已存在」。
                card(agentKey = "codex", displayName = "codex-ops", employeeId = "emp-1", employeeName = "老王"),
                // 别人的 agent：档案只住本机设置，登记它存不下去。
                card(hostNodeId = peer, agentKey = "claude", displayName = "claude-dev"),
            ),
            selfNodeId = self,
        )

        assertEquals(listOf("dsh"), rows.map { it.agentId })
        assertEquals("dsh-coder", rows.single().name)
        assertEquals("本机", rows.single().hostName)
    }

    @Test
    fun anAgentWithoutAnIdentityKeyIsNotACandidate() {
        // 「未上报类型」的卡没有可写的 agentId —— 编一个只会得到派不出去的幽灵员工。
        val rows = employeeProfileCandidates(
            cards = listOf(card(agentKey = null, displayName = null)),
            selfNodeId = self,
        )
        assertTrue(rows.isEmpty(), "没有身份键就没有可登记的 agentId")
    }

    @Test
    fun theSameIdentityAppearsOnce() {
        // 卡片流按宿主 + 身份键聚类，但同一 agent 的多条来源（会话 / 宣告）可能给出大小写
        // 不同的写法：候选表按归一后的小写去重，否则同一个进程会被登记两次。
        val rows = employeeProfileCandidates(
            cards = listOf(
                card(agentKey = "dsh", displayName = "dsh-coder"),
                card(agentKey = "DSH", displayName = "dsh-coder-2"),
            ),
            selfNodeId = self,
        )
        assertEquals(1, rows.size)
        assertEquals("dsh", rows.single().agentId, "保留第一次出现的写法（与卡片流的代表键一致）")
    }

    @Test
    fun candidatesAreOrderedByName() {
        val rows = employeeProfileCandidates(
            cards = listOf(
                card(agentKey = "dsh", displayName = "zulu"),
                card(agentKey = "codex", displayName = "alpha"),
            ),
            selfNodeId = self,
        )
        assertEquals(listOf("alpha", "zulu"), rows.map { it.name })
    }

    @Test
    fun aKnownSelfIdIsRequired() {
        // 身份还在恢复（selfNodeId 为空）时不能把「所有宿主」都当成本机候选。
        assertEquals(
            emptyList(),
            employeeProfileCandidates(
                cards = listOf(card(hostNodeId = ""), card(agentKey = "codex")),
                selfNodeId = "",
            ),
        )
    }

    @Test
    fun aCandidateWithoutADisplayNameFallsBackToTheIdentityKey() {
        // 候选行必须有标题：`displayName` 三档全空时落回身份键（不画一行空白行）。
        val rows = employeeProfileCandidates(
            cards = listOf(card(agentKey = "dsh", displayName = null)),
            selfNodeId = self,
        )
        assertEquals("dsh", rows.single().name)
    }

    @Test
    fun theLedgerAndTheCandidatesNeverOverlap() {
        // 两个投影问的是互补的两个问题（「谁登记过」/「谁还没登记」），重叠会让档案页
        // 同时出现「新建」与「编辑」同一个人的两个入口。
        val card = card(agentKey = "dsh", employeeId = "emp-1", employeeName = "小王")
        val ledger = employeeProfileLedger(
            SettingsSlice(
                settings = RelaySettings.firstRun().copy(
                    nodeId = self,
                    employeeProfiles = listOf(
                        EmployeeProfile(
                            employeeId = "emp-1",
                            agentId = "dsh",
                            name = "小王",
                            token = "dsh-coder",
                        ),
                    ),
                ),
            ),
        )
        assertEquals(listOf("emp-1"), ledger.map { it.employeeId })
        assertTrue(
            employeeProfileCandidates(listOf(card), self).isEmpty(),
            "已建档的 agent 不该再出现在候选表里",
        )
    }
}
