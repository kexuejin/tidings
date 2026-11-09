// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.conversations

import com.kapp.tenon.domain.model.ConversationUi
import com.kapp.tenon.domain.model.TeamFolderUi
import com.kapp.tenon.domain.model.TeamMemberUi
import com.kapp.tenon.domain.model.teamGroupKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 折叠投影的行为钉子（阶段 0 / C4）。
 *
 * 这些用例锁的是**结构契约**，不是像素：不重排、折叠替代会话行、展开补回会话行且只补一次、
 * 一个会话挂多个 team 时各出一行、key 唯一。UI 接线（`ConversationListPanel` /
 * `ConversationSidebar` 各调一次）在阶段 1 由源码守卫另行钉住。
 */
class TeamFolderProjectionTest {

    private fun conv(id: String, lastMessageAt: Long = 0L, unread: Int = 0) = ConversationUi(
        convId = id,
        title = id,
        preview = null,
        at = lastMessageAt,
        unread = unread,
        isAgent = false,
        online = true,
        pendingCount = 0,
        lastMessageAt = lastMessageAt,
        presenceAt = 0L,
        isPinned = false,
    )

    private fun feed(vararg ids: String): List<InboxFeedItem> =
        ids.map { InboxFeedItem.Conversation(conv(it)) }

    private fun folder(
        convId: String,
        pluginId: String = "devops-swarm",
        members: List<TeamMemberUi> = listOf(
            TeamMemberUi("butler", "butler", "Orchestrator"),
            TeamMemberUi("coder", "coder", "Engineer"),
        ),
    ) = TeamFolderUi(
        convId = convId,
        pluginId = pluginId,
        title = "DevOps 智能协同团队",
        members = members,
    )

    @Test
    fun emptyFeedStaysEmpty() {
        assertTrue(foldTeamRows(emptyList(), emptyMap()).isEmpty())
        assertTrue(foldTeamRows(emptyList(), mapOf("group:g1" to listOf(folder("group:g1")))).isEmpty())
    }

    @Test
    fun withoutFoldersEveryItemPassesThrough() {
        val rows = foldTeamRows(feed("group:g1", "peer-a"), emptyMap())
        assertEquals(
            listOf("conv_group:g1", "conv_peer-a"),
            rows.map { it.key },
        )
        assertTrue(rows.all { it is FeedRow.Item })
    }

    @Test
    fun collapsedFolderReplacesItsSessionRow() {
        val folder = folder("group:g1")
        val rows = foldTeamRows(feed("group:g1", "peer-a"), mapOf("group:g1" to listOf(folder)))

        assertEquals(2, rows.size)
        assertEquals("team_group:g1_devops-swarm", rows[0].key)
        assertEquals(false, (rows[0] as FeedRow.Folder).expanded)
        assertEquals("conv_peer-a", rows[1].key)
        // 折叠态下会话行不单独出现 —— 组头本身就是进入会话的入口。
        assertTrue(rows.none { it.key == "conv_group:g1" })
    }

    @Test
    fun expandedFolderRestoresSessionRowThenMembers() {
        val folder = folder("group:g1")
        val rows = foldTeamRows(
            feed = feed("group:g1", "peer-a"),
            foldersByConv = mapOf("group:g1" to listOf(folder)),
            expandedGroups = setOf(teamGroupKey("group:g1", "devops-swarm")),
        )

        assertEquals(
            listOf(
                "team_group:g1_devops-swarm",
                "conv_group:g1",
                "teammember_group:g1_devops-swarm_butler",
                "teammember_group:g1_devops-swarm_coder",
                "conv_peer-a",
            ),
            rows.map { it.key },
        )
        assertTrue((rows[0] as FeedRow.Folder).expanded)
    }

    @Test
    fun folderKeepsTheSessionPositionInsteadOfJumpingToTop() {
        val rows = foldTeamRows(
            feed = feed("peer-a", "group:g1", "peer-b"),
            foldersByConv = mapOf("group:g1" to listOf(folder("group:g1"))),
        )
        assertEquals(
            listOf("conv_peer-a", "team_group:g1_devops-swarm", "conv_peer-b"),
            rows.map { it.key },
        )
    }

    @Test
    fun oneConversationWithTwoTeamsGetsTwoFolders() {
        val rows = foldTeamRows(
            feed = feed("group:g1"),
            foldersByConv = mapOf(
                "group:g1" to listOf(folder("group:g1", "team-a"), folder("group:g1", "team-b")),
            ),
        )
        assertEquals(
            listOf("team_group:g1_team-a", "team_group:g1_team-b"),
            rows.map { it.key },
        )
    }

    @Test
    fun sessionRowIsEmittedOnceEvenWhenBothTeamsExpand() {
        val rows = foldTeamRows(
            feed = feed("group:g1"),
            foldersByConv = mapOf(
                "group:g1" to listOf(folder("group:g1", "team-a"), folder("group:g1", "team-b")),
            ),
            expandedGroups = setOf(
                teamGroupKey("group:g1", "team-a"),
                teamGroupKey("group:g1", "team-b"),
            ),
        )
        assertEquals(1, rows.count { it.key == "conv_group:g1" })
        // 两个组各两名成员 ⇒ 两个组头 + 一条会话行 + 四条成员行。
        assertEquals(7, rows.size)
    }

    @Test
    fun collapsedHeaderKeepsTheUnreadCountOfTheRowItReplaces() {
        // 折叠态下组头**替代**会话行：不把未读带出来，那一格的数字就从列表里消失，
        // 而副标是按全部会话求和的 —— 于是"标题说 N 条未读、列表只数得出 M 条"。
        val rows = foldTeamRows(
            feed = listOf(InboxFeedItem.Conversation(conv("group:g1", lastMessageAt = 9L, unread = 2))),
            foldersByConv = mapOf("group:g1" to listOf(folder("group:g1"))),
        )
        val header = rows.single() as FeedRow.Folder
        assertEquals(2, header.unread, "组头必须承接被替代会话行的未读数")

        // 展开态下会话行补回来了，组头与行会同时在场 —— 组头仍带着同一个数（它替代的是
        // 同一格信息），不重复计数由渲染侧决定，投影层不该悄悄清零。
        val expanded = foldTeamRows(
            feed = listOf(InboxFeedItem.Conversation(conv("group:g1", lastMessageAt = 9L, unread = 2))),
            foldersByConv = mapOf("group:g1" to listOf(folder("group:g1"))),
            expandedGroups = setOf(teamGroupKey("group:g1", "devops-swarm")),
        )
        assertEquals(2, (expanded.first() as FeedRow.Folder).unread)
        assertTrue(expanded.any { it.key == "conv_group:g1" }, "展开态必须把会话行补回来")
    }

    @Test
    fun rowKeysAreUnique() {
        val rows = foldTeamRows(
            feed = feed("peer-a", "group:g1", "group:g2"),
            foldersByConv = mapOf(
                "group:g1" to listOf(folder("group:g1")),
                "group:g2" to listOf(folder("group:g2", "team-b")),
            ),
            expandedGroups = setOf(teamGroupKey("group:g1", "devops-swarm")),
        )
        val keys = rows.map { it.key }
        assertEquals(keys.size, keys.toSet().size, "渲染行的 key 必须唯一，否则 LazyColumn 复用会串行")
    }
}
