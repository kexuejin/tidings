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

    // ------------------------------------------------------------ 整队（@团队名）

    private fun namedSeat(
        host: String = selfHost,
        teamId: String? = "team-dev",
        teamName: String? = "开发团队",
    ) = teamSeat(host = host).copy(teamId = teamId, teamName = teamName)

    /**
     * `@开发团队` = 整队接单：执行主体仍是那条席位，编译命中执行档里**唯一的 worker**。
     *
     * 为什么不 fan-out 给每个组员：组员没有独立执行体（责任链角色），一人一单会绕过
     * `[worker] + [reviewer…]` 的既有链、产生 N 份互不相干的卡。
     */
    @Test
    fun aTeamNameResolvesToTheSeatAndItsWorker() {
        val store = memberStore(coderProfile())
        val hit = assertIs<TeamExecutionResolver.Target.Hit>(
            resolve(store, group(namedSeat()), "开发团队"),
        )
        assertEquals("dsh-lead", hit.seat.agentId)
        assertEquals("m-coder", hit.member.agentId, "整队默认落在 worker 那一棒")
        assertEquals(2, hit.profile.members.size, "reviewer 照既有责任链走，不在这里丢")
    }

    /**
     * 整队命中但本机没有这个席位的执行档：出 blocked 卡，**不**回落 codex 兜底。
     *
     * （「有档但没有 worker」不是可达输入：`TeamExecutionStore.save` 保存期就拒了，
     * 那个形状只能来自手改文件；读取期仍由 `profile.worker ?: NotConfigured` 兜住。）
     */
    @Test
    fun aTeamWithoutAnExecutionProfileIsNotConfigured() {
        val store = memberStore()
        val denied = assertIs<TeamExecutionResolver.Target.NotConfigured>(
            resolve(store, group(namedSeat()), "开发团队"),
        )
        assertTrue("not configured" in denied.reason, denied.reason)
    }

    /** 团队名与组员 token 撞名：`@` 会先命中组员，整队打不到 ⇒ 报歧义让用户改名。 */
    @Test
    fun aTeamNamedLikeAMemberIsNotConfigured() {
        val store = memberStore(coderProfile())
        val denied = assertIs<TeamExecutionResolver.Target.NotConfigured>(
            resolve(store, group(namedSeat(teamName = "coder")), "coder"),
        )
        assertTrue("rename the team" in denied.reason, denied.reason)
    }

    /** 整队的席位在别的宿主：本机不执行（同 @组员 的判据，不另开一条）。 */
    @Test
    fun aTeamHostedElsewhereDoesNotResolveHere() {
        val store = memberStore(coderProfile())
        val denied = assertIs<TeamExecutionResolver.Target.NotConfigured>(
            resolve(store, group(namedSeat(host = otherHost)), "开发团队", self = selfHost),
        )
        assertTrue("another device" in denied.reason, denied.reason)
    }
}
