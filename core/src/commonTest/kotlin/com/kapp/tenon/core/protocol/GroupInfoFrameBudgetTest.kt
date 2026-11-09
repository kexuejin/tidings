// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `group.info` 的**整帧预算**测试（方案 §4.6）。
 *
 * `group.info` 没有发送侧 clamp：超预算的帧在收端是 framing **peer-closed**
 * （`Link` 读帧路径 `len > MAX_FRAME_BYTES` 返回 null），队列还会重放同一帧 ——
 * 唯一的防线是「最坏合法元数据也编不出超帧」。本用例把 §3.4 的全部上限拉满
 * （成员表 200、昵称 200、声明 48、组员总数 64，全部字段顶到预算），断言编码后
 * < [Limits.MAX_FRAME_BYTES] 并留信封余量。
 *
 * **上限一改，这条测试必须跟着改**（写在 `Limits` 那两个常量的 KDoc 里）：
 * 调大任何一个都先跑它，红了就说明帧预算被顶穿 —— 不许只改常量。
 *
 * 如实的边界：`allowedWorkspaces` 按方案 §4.5 只校验键与长度、**不加条数上限**
 * （它的体量由宿主自己的工作区表决定）。它极端膨胀时会顶穿帧预算 —— 那是
 * v1 之前就存在的形状，与本方案无关；要收口它是独立的一条（含对 TS 侧的同步）。
 */
class GroupInfoFrameBudgetTest {

    /** 信封包装余量（`v/type/id/from/ts` + `op/group` 两个键，≈ 150 B，取 1 KiB）。 */
    private val envelopeHeadroom = 1024

    @Test
    fun theWorstLegalMetadataStillFitsTheFrame() {
        val maxId = "a".repeat(Limits.ID)
        val maxNick = "n".repeat(Limits.NICK)
        val maxRole = "r".repeat(Limits.TEAM_ROLE)
        val maxDesc = "d".repeat(Limits.GROUP_DESCRIPTION)
        val maxAnnounce = "a".repeat(Limits.GROUP_ANNOUNCE)

        val members = List(GROUP_MAX_MEMBERS) { "m".repeat(Limits.FROM) }
        // ownerId 必须属于 members（validateGroup 的成对校验），取成员表里那一个。
        val nicks = members.associateWith { maxNick }

        // 48 条声明；组员总数顶满 64（8 个团队席位 × 8 人，其余单兵）。
        // (hostNodeId, agentId) 必须各不相同 —— 团队席位每对至多一条（发送侧同一约束）。
        var teamBudget = Limits.GROUP_MAX_TEAM_MEMBERS_TOTAL
        val agents = (1..Limits.GROUP_MAX_VIRTUAL_AGENTS).map { i ->
            val roster = buildList {
                if (i <= Limits.GROUP_MAX_TEAM_MEMBERS_TOTAL / Limits.TEAM_MEMBERS_MAX) {
                    repeat(Limits.TEAM_MEMBERS_MAX) { j ->
                        check(teamBudget > 0)
                        teamBudget--
                        add(TeamMemberInfo(agentId = "m$j$maxId".take(Limits.ID), name = "m$j$maxNick".take(Limits.NICK), role = maxRole))
                    }
                }
            }
            VirtualAgentMember(
                agentId = ("a".repeat(60) + i.toString().padStart(4, '0')),
                hostNodeId = "h".repeat(Limits.FROM),
                name = maxNick,
                entityType = if (roster.isEmpty()) EntityTypes.SINGLE else EntityTypes.TEAM,
                teamMembers = roster,
                allowedWorkspaces = listOf(
                    GroupWorkspaceInfo(wsId = maxId, title = maxNick, description = maxDesc),
                ),
                defaultWsId = maxId,
            )
        }

        val worst = GroupMeta(
            groupId = maxId,
            name = maxNick,
            members = members,
            rev = Int.MAX_VALUE,
            updatedBy = maxId,
            updatedTs = Long.MAX_VALUE,
            creatorIp = "2".repeat(Limits.IP),
            creatorId = maxId,
            ownerId = members.first(),
            adminIds = emptyList(),
            avatarHash = "a".repeat(64),
            adminSecretHash = "a".repeat(64),
            adminHint = "h".repeat(Limits.GROUP_ADMIN_HINT),
            description = maxDesc,
            announce = maxAnnounce,
            memberNicks = nicks,
            virtualAgents = agents,
        )

        // 最坏合法元数据首先要过发送侧校验（它就是「合法」的定义）。
        assertTrue(CodecGroup.validateGroupMeta(worst), "最坏合法元数据必须过发送侧校验")
        val bytes = CodecGroup.groupInfoPayload(worst).toString().encodeToByteArray().size
        assertTrue(
            bytes + envelopeHeadroom <= Limits.MAX_FRAME_BYTES,
            "最坏合法帧 $bytes B + 信封余量 $envelopeHeadroom B 必须 ≤ ${Limits.MAX_FRAME_BYTES} B。" +
                "红了说明 §3.4 的某个上限被调大顶穿了帧预算 —— 收端 framing 会直接 peer-closed。",
        )
    }
}
