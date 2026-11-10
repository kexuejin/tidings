// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.core.protocol.EntityTypes
import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.core.protocol.TeamMemberInfo
import com.kapp.tenon.core.protocol.VirtualAgentMember
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 宿主侧 `@组员` 解析矩阵（团队执行方案 §4.3）：token → (席位, 组员, 执行档) 的
 * 三类结论必须**可区分**（AGENTS §4：断言能区分对错的量）：
 *
 * - [TeamExecutionResolver.Target.Hit] = 完整命中；
 * - [TeamExecutionResolver.Target.NotConfigured] = 是组员 token 但派不出去 ——
 *   **必须**出 blocked 卡，绝不回落 codex 单棒（§2 要堵的洞）；
 * - `null`（[TeamExecutionResolver.Target.None]）= 不是组员 token —— **绝不**
 *   因为「不是组员」而拦截既有路径（领队直呼照旧）。
 *
 * 悬空校验（§3.5）在这里钉住：执行档里的成员已不在声明 roster ⇒ 配置过期，
 * 派单给 blocked 卡并提示重新编制，不静默挑一个成员顶上。
 *
 * 反向探针形态：矩阵里的每个 NotConfigured 变体都是「把这条判据摘掉就会落进
 * 下一层（codex 兜底 / 错误席位）」的错值输入 —— 纯函数矩阵自身即反向验证。
 */
class TeamExecutionResolverTest {

    private val selfHost = "host-self-node"
    private val otherHost = "host-other-node"

    private var tmp: File? = null

    @AfterTest
    fun tearDown() {
        tmp?.deleteRecursively()
        tmp = null
    }

    private fun memberStore(vararg profiles: TeamExecutionProfile): TeamExecutionStore {
        val dir = Files.createTempDirectory("team-exec-resolver").toFile()
        tmp = dir
        val store = TeamExecutionStore(File(dir, "team-execution.json"))
        profiles.forEach { p ->
            val verdict = store.save(p)
            assertIs<TeamExecutionSaveVerdict.Saved>(verdict, "前置：执行档必须能落盘：$p")
        }
        return store
    }

    private fun teamSeat(
        host: String = selfHost,
        defaultWsId: String? = "ws-1",
        members: List<TeamMemberInfo> = listOf(
            TeamMemberInfo("m-coder", "coder", "编码"),
            TeamMemberInfo("m-reviewer", "reviewer", "审查"),
        ),
    ) = VirtualAgentMember(
        agentId = "dsh-lead",
        hostNodeId = host,
        name = "lead-seat",
        entityType = EntityTypes.TEAM,
        teamMembers = members,
        defaultWsId = defaultWsId,
    )

    private fun group(vararg seats: VirtualAgentMember) = GroupMeta(
        groupId = "g-team", name = "团队群",
        members = listOf(selfHost, otherHost), rev = 2,
        updatedBy = selfHost, updatedTs = 1,
        ownerId = selfHost, adminIds = emptyList(),
        virtualAgents = seats.toList(),
    )

    private fun coderProfile(leaderAgentId: String = "dsh-lead") = TeamExecutionProfile(
        groupId = "g-team", dirId = "ws-1", leaderAgentId = leaderAgentId,
        updatedAt = 0,
        members = listOf(
            TeamExecutionMemberSpec("m-coder", TeamExecutionKinds.WORKER, "codex"),
            TeamExecutionMemberSpec("m-reviewer", TeamExecutionKinds.REVIEWER, "claude"),
        ),
    )

    private fun resolve(
        store: TeamExecutionStore,
        group: GroupMeta?,
        token: String?,
        self: String = selfHost,
        dirIdOfGroup: ((String) -> String?)? = null,
    ): TeamExecutionResolver.Target =
        TeamExecutionResolver(store, dirIdOfGroup ?: { "ws-1" }).resolve(
            groupId = group?.groupId, mentionedKey = token, group = group, selfNodeId = self,
        )

    @Test
    fun aMemberTokenWithAValidProfileResolvesToSeatMemberAndProfile() {
        val store = memberStore(coderProfile())
        val seat = teamSeat()
        val target = resolve(store, group(seat), "coder")
        val hit = assertIs<TeamExecutionResolver.Target.Hit>(target)
        assertEquals("dsh-lead", hit.seat.agentId, "执行主体是席位（宿主），不是组员单独开会话")
        assertEquals("m-coder", hit.member.agentId)
        assertEquals("ws-1", hit.profile.dirId)
        assertEquals(2, hit.profile.members.size)
    }

    @Test
    fun aMemberNameMatchIsCaseInsensitiveLikeEveryOtherToken() {
        val store = memberStore(coderProfile())
        val hit = assertIs<TeamExecutionResolver.Target.Hit>(
            resolve(store, group(teamSeat()), "CODER"),
        )
        assertEquals("m-coder", hit.member.agentId)
    }

    @Test
    fun aTeamSeatHostedElsewhereDoesNotResolveHere() {
        val store = memberStore(coderProfile())
        val target = resolve(store, group(teamSeat(host = otherHost)), "coder", self = selfHost)
        val notConfigured = assertIs<TeamExecutionResolver.Target.NotConfigured>(
            target,
            "席位在别的宿主 ⇒ 本机不该执行；但也不能返回 None —— None 会落进 codex 兜底",
        )
        assertTrue(
            "another device" in notConfigured.reason,
            "原因要可读（谁托管了它）：${notConfigured.reason}",
        )
    }

    /** 席位没绑默认工作区：团队链需要确定的仓（§4.3 workspace 显式取席位默认）。 */
    @Test
    fun aGroupWithoutProjectDirectoryIsNotConfigured() {
        val store = memberStore(coderProfile())
        val target = resolve(store, group(teamSeat()), "coder", dirIdOfGroup = { null })
        val notConfigured = assertIs<TeamExecutionResolver.Target.NotConfigured>(target)
        assertTrue(
            "directory" in notConfigured.reason,
            "原因要指向群没设项目目录：${notConfigured.reason}",
        )
    }

    /** 有声明、没执行档：blocked 卡要提示「去编制里配置」，不是静默。 */
    @Test
    fun aDeclaredTeamWithoutAnExecutionProfileIsNotConfigured() {
        val store = memberStore() // 空档：什么都没配
        val target = resolve(store, group(teamSeat()), "coder")
        val notConfigured = assertIs<TeamExecutionResolver.Target.NotConfigured>(target)
        assertTrue(
            "not configured" in notConfigured.reason,
            "原因要写明未配置：${notConfigured.reason}",
        )
    }

    /** §3.5 悬空：档里的成员已不在当前声明里 ⇒ 配置过期，读时判过期。 */
    @Test
    fun aProfileWithAMemberNoLongerInTheRosterIsStale() {
        // 保存时不传 declaredMemberIds（跳过 ⊆ 校验），模拟「保存后声明里删了人」的存量数据。
        val stale = coderProfile().copy(
            members = coderProfile().members +
                TeamExecutionMemberSpec("m-gone", TeamExecutionKinds.REVIEWER, "claude"),
        )
        val store = memberStore(stale)
        val target = resolve(store, group(teamSeat()), "coder")
        val notConfigured = assertIs<TeamExecutionResolver.Target.NotConfigured>(
            target,
            "悬空成员 ⇒ 配置过期（§3.5），不许静默挑一个顶上",
        )
        assertTrue(
            "stale" in notConfigured.reason && "m-gone" in notConfigured.reason,
            "过期原因要点名哪个成员悬空：${notConfigured.reason}",
        )
    }

    /** 命中的组员不在执行档里（档只配了 worker）：同样 NotConfigured，不猜。 */
    @Test
    fun aHitMemberMissingFromTheProfileIsNotConfigured() {
        val workerOnly = coderProfile().copy(
            members = listOf(TeamExecutionMemberSpec("m-coder", TeamExecutionKinds.WORKER, "codex")),
        )
        val store = memberStore(workerOnly)
        val target = resolve(store, group(teamSeat()), "reviewer")
        val notConfigured = assertIs<TeamExecutionResolver.Target.NotConfigured>(target)
        assertTrue(
            "reviewer" in notConfigured.reason,
            "原因要点名缺谁：${notConfigured.reason}",
        )
    }

    /** 跨席位重名 token：fail-closed（手改元数据），绝不挑一个 —— 挑错席位就是在别人的仓里跑命令。 */
    @Test
    fun aTokenDuplicatedAcrossSeatsFailsClosed() {
        val other = teamSeat().copy(
            agentId = "dsh-other", hostNodeId = otherHost,
            teamMembers = listOf(TeamMemberInfo("m-copy", "coder", "编码")),
        )
        val store = memberStore(coderProfile())
        val target = resolve(store, group(teamSeat(), other), "coder")
        val notConfigured = assertIs<TeamExecutionResolver.Target.NotConfigured>(
            target,
            "重名 = 元数据被手改 ⇒ 不发执行（与 core 单源判据同一结论）",
        )
        assertTrue(
            "multiple" in notConfigured.reason,
            "原因要写明歧义：${notConfigured.reason}",
        )
    }

    // ------------------------------------------------------------- None 档
    // 注意：resolver 的「未命中」结论是 Target.None（data object），不是 null ——
    // 与 core 的 resolveTeamMemberToken（返回 null）是两套形状，各有消费方。

    /** 领队 token 不是组员 token：None ⇒ 既有单 agent 路径接管（§2：@leader 行为不变）。 */
    @Test
    fun aLeaderTokenIsNoneNotNotConfigured() {
        val store = memberStore(coderProfile())
        assertIs<TeamExecutionResolver.Target.None>(
            resolve(store, group(teamSeat()), "lead-seat"),
            "领队 token 解析不出组员 ⇒ None（既有路径）；误判 NotConfigured 会拦死领队派单",
        )
    }

    @Test
    fun aHostReportedKindIsNone() {
        val store = memberStore(coderProfile())
        assertIs<TeamExecutionResolver.Target.None>(
            resolve(store, group(teamSeat()), "dsh"),
            "宿主自报 kind 不在群元数据的组员池里",
        )
    }

    @Test
    fun aBlankOrNullTokenIsNone() {
        val store = memberStore(coderProfile())
        val g = group(teamSeat())
        assertIs<TeamExecutionResolver.Target.None>(resolve(store, g, null))
        assertIs<TeamExecutionResolver.Target.None>(resolve(store, g, ""))
        assertIs<TeamExecutionResolver.Target.None>(resolve(store, g, "   "))
    }

    @Test
    fun aMissingGroupOrUnknownTokenIsNone() {
        val store = memberStore(coderProfile())
        assertIs<TeamExecutionResolver.Target.None>(resolve(store, null, "coder"), "群为 null（1:1）没有团队语义")
        assertIs<TeamExecutionResolver.Target.None>(resolve(store, group(teamSeat()), "stranger"), "未知 token 不是组员")
    }
}
