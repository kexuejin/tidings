// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.AgentDuties
import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.core.protocol.VirtualAgentMember
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 员工档案投影（[employeeProfileLedger]，登记簿视角）的判据。
 *
 * 它回答三个只有登记簿答得出的问题：**它叫什么、`@` 是什么、在几个群里** ——
 * 名录（`hubAgents`）做不到这一点，因为一张卡要有运行时事实才装得出来：一个人可以先建档、
 * 一个群都不进，也可以席位全撤之后档案还在。那种档案在名录里没有任何位置，
 * 而它恰恰是最需要被管理（改名 / 注销）的那一档。
 *
 * ## 反向探针（每条判据都必须能区分对错）
 *
 * - `groupNames` 恒空 ⇒ `countingUsesTheSameJudgeAsTheWritePath` 变红（「不在任何群」
 *   会把两个群里的员工画成孤儿）；
 * - 去掉 `sortedBy { name }` ⇒ `rowsAreOrderedByName` 变红（档案序 = 登记序，改名后跳）；
 * - 去掉名字兜底 ⇒ `aBlankNameFallsBackToTheIdentityKey` 变红（画出一行没有标题的卡）。
 */
class EmployeeProfileLedgerTest {

    private val self = "self-node-0001"
    private val peer = "host-other-0002"

    private fun profile(
        employeeId: String,
        name: String,
        agentId: String = "dsh",
        token: String = "dsh-coder",
        duty: String = AgentDuties.CODER,
    ) = EmployeeProfile(
        employeeId = employeeId,
        agentId = agentId,
        name = name,
        token = token,
        duty = duty,
        rev = 1,
    )

    private fun seat(
        agentId: String,
        name: String,
        employeeId: String? = null,
        hostNodeId: String = self,
    ) = VirtualAgentMember(
        agentId = agentId,
        hostNodeId = hostNodeId,
        name = name,
        employeeId = employeeId,
    )

    private fun group(groupId: String, name: String, seats: List<VirtualAgentMember>) =
        GroupMeta.create(groupId, name, members = emptyList(), selfId = self, updatedTs = 1L)
            .copy(virtualAgents = seats)

    private fun ledgerOf(
        profiles: List<EmployeeProfile>,
        groups: List<GroupMeta> = emptyList(),
    ): List<AgentEmployeeProfileUi> = employeeProfileLedger(
        SettingsSlice(
            settings = RelaySettings.firstRun().copy(
                nodeId = self,
                groups = groups,
                employeeProfiles = profiles,
            ),
        ),
    )

    @Test
    fun aProfileWithNoGroupStillAppearsInTheLedger() {
        // 「先建人、后进群」是 `Create` 明确允许的一档：名录里它没有卡，
        // 但它必须出现在登记簿里 —— 否则这份档案在界面上不存在，也就删不掉。
        val rows = ledgerOf(profiles = listOf(profile("emp-1", "前端小王")))

        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("emp-1", row.employeeId)
        assertEquals("dsh", row.agentId)
        assertEquals("前端小王", row.name)
        assertEquals("dsh-coder", row.token)
        assertEquals(AgentDuties.CODER, row.duty)
        assertEquals(emptyList(), row.groupNames, "没进群就是没进群，不拿群名兜底")
    }

    @Test
    fun countingUsesTheSameJudgeAsTheWritePath() {
        // 两个群引用 emp-1、另一个群只引用别人的档案 —— 计数必须与注销时会去摘的群一致。
        val rows = ledgerOf(
            profiles = listOf(
                profile("emp-1", "老王"),
                profile("emp-2", "小李", agentId = "codex", token = "codex-ops"),
            ),
            groups = listOf(
                group("g1", "项目研发群", listOf(seat("dsh", "dsh-coder", employeeId = "emp-1"))),
                group("g2", "测试群", listOf(seat("dsh", "dsh-coder", employeeId = "emp-1"))),
                group("g3", "别的群", listOf(seat("codex", "codex-ops", employeeId = "emp-2"))),
            ),
        )

        val byId = rows.associateBy { it.employeeId }
        assertEquals(listOf("项目研发群", "测试群"), byId.getValue("emp-1").groupNames)
        assertEquals(listOf("别的群"), byId.getValue("emp-2").groupNames, "别人的群不许算到这份档案头上")
    }

    @Test
    fun rowsAreOrderedByName() {
        // 登记序（emp-b, emp-a）与名字序（alpha, bravo）不同 —— 排序失效这条会红。
        // 名字用 ASCII：中文比较等于断言码位序，那不是这里的判据。
        val rows = ledgerOf(
            profiles = listOf(
                profile("emp-b", "bravo"),
                profile("emp-a", "alpha"),
            ),
        )
        assertEquals(listOf("alpha", "bravo"), rows.map { it.name })
    }

    @Test
    fun aBlankNameFallsBackToTheIdentityKey() {
        // 写路径不许空名，但库里可能留着坏数据：宁可显示身份键，也不画一行空白行。
        val blank = EmployeeProfile(
            employeeId = "emp-x",
            agentId = "dsh",
            name = "   ",
            token = "dsh",
        )
        val row = ledgerOf(profiles = listOf(blank)).single()
        assertEquals("dsh", row.name)
        assertTrue(row.token.isNotEmpty(), "token 兜底由 effectiveToken 保证，不许是空串")
    }

    @Test
    fun noProfilesMeansNoLedger() {
        // 手机端就是这个形状（`employeeProfiles` 只住宿主机）⇒ 整档不画。
        assertEquals(emptyList(), ledgerOf(profiles = emptyList()))
        // 反过来：群再多、一条档案都没有时也不许凭空造行。
        assertEquals(
            emptyList(),
            ledgerOf(
                profiles = emptyList(),
                groups = listOf(group("g1", "项目研发群", listOf(seat("dsh", "dsh-lead")))),
            ),
        )
    }
}
