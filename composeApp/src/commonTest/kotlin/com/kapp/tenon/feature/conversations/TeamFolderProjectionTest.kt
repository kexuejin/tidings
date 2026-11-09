// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.conversations

import com.kapp.tenon.domain.model.ConversationUi
import com.kapp.tenon.domain.model.TeamFolderUi
import com.kapp.tenon.domain.model.TeamMemberUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 折叠投影的行为钉子。
 *
 * 这些用例锁的是**结构契约**，不是像素。最重要的一条是它上一版被推翻的原因：
 *
 * > 折叠态每条会话**恰好一行**，而且那一行就是会话本身（`FeedRow.Session`），
 * > 带着真实的 `ConversationUi`。
 *
 * 上一版让"组头"替代会话行，于是折叠态丢预览与时间、展开态同会话两个表示 —— 现在
 * 会话行永远在，团队只是行内标记；展开只**追加**标签行与成员行。
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
        running: Boolean = false,
        members: List<TeamMemberUi> = listOf(
            TeamMemberUi("butler", "butler", "Orchestrator"),
            TeamMemberUi("coder", "coder", "Engineer"),
        ),
    ) = TeamFolderUi(
        convId = convId,
        pluginId = pluginId,
        title = pluginId,
        members = members,
        isRunning = running,
    )

    @Test
    fun withoutTeamsEveryConversationIsStillOneSessionRow() {
        val rows = foldTeamRows(feed("peer-a", "peer-b"), emptyMap())
        assertEquals(2, rows.size)
        assertTrue(rows.all { it is FeedRow.Session }, "没有团队时也走 Session —— 会话行类型不该有两种")
        assertTrue(rows.all { (it as FeedRow.Session).teams.isEmpty() })
    }

    @Test
    fun collapsedTeamKeepsExactlyOneRowAndItIsTheConversationItself() {
        val rows = foldTeamRows(
            feed = feed("group:g1"),
            foldersByConv = mapOf("group:g1" to listOf(folder("group:g1"))),
        )
        assertEquals(1, rows.size, "折叠态每条会话只许一行 —— 多一行就是替代/复制")
        val session = rows.single() as FeedRow.Session
        assertEquals("group:g1", session.item.conv.convId, "那一行必须是真实会话")
        assertEquals(false, session.expanded)
        assertEquals(1, session.teams.size)
    }

    @Test
    fun expandingOnlyAppendsLabelAndMembersNeverRepeatsTheSession() {
        val rows = foldTeamRows(
            feed = feed("group:g1"),
            foldersByConv = mapOf("group:g1" to listOf(folder("group:g1"))),
            expandedConvs = setOf("group:g1"),
        )
        // 1 条会话 + 1 行团队标签 + 2 名成员；会话行**不重复**。
        assertEquals(4, rows.size)
        assertEquals(1, rows.count { it is FeedRow.Session })
        val label = rows[1] as FeedRow.TeamLabel
        assertEquals("devops-swarm", label.title, "标签行的团队名就是 leader 的 @ token")
        assertEquals(2, label.memberCount)
        assertEquals(
            "group:g1",
            (rows.first() as FeedRow.Session).item.conv.convId,
            "会话行仍在最前，位置不挪",
        )
    }

    @Test
    fun expandingOneConversationLeavesOthersCollapsed() {
        val rows = foldTeamRows(
            feed = feed("group:g1", "group:g2"),
            foldersByConv = mapOf(
                "group:g1" to listOf(folder("group:g1")),
                "group:g2" to listOf(folder("group:g2", "team-b")),
            ),
            expandedConvs = setOf("group:g1"),
        )
        assertEquals(2, rows.count { it is FeedRow.Session })
        assertEquals(1, rows.count { it is FeedRow.TeamLabel }, "只有展开的那个会话出标签行")
    }

    @Test
    fun twoTeamsOnOneConversationShareTheRowAndGetOneLabelEach() {
        val rows = foldTeamRows(
            feed = feed("group:g1"),
            foldersByConv = mapOf(
                "group:g1" to listOf(folder("group:g1", "team-a"), folder("group:g1", "team-b")),
            ),
            expandedConvs = setOf("group:g1"),
        )
        // 一行只能有一个折叠符号 ⇒ 展开即整块展开，靠标签行区分归属。
        assertEquals(1, rows.count { it is FeedRow.Session })
        assertEquals(2, rows.count { it is FeedRow.TeamLabel })
        assertEquals(4, rows.count { it is FeedRow.Member })
    }

    @Test
    fun runningIsTheUnionOfItsTeams() {
        val idle = foldTeamRows(
            feed = feed("group:g1"),
            foldersByConv = mapOf("group:g1" to listOf(folder("group:g1", "team-a"))),
        ).single() as FeedRow.Session
        assertEquals(false, idle.running, "没有团队在跑就是空闲")

        val mixed = foldTeamRows(
            feed = feed("group:g1"),
            foldersByConv = mapOf(
                "group:g1" to listOf(
                    folder("group:g1", "team-a", running = false),
                    folder("group:g1", "team-b", running = true),
                ),
            ),
        ).single() as FeedRow.Session
        assertEquals(true, mixed.running, "任一团队在跑就点亮（与域层 any BUSY 同一口径）")
    }

    @Test
    fun rowKeysAreUnique() {
        val rows = foldTeamRows(
            feed = feed("peer-a", "group:g1", "group:g2"),
            foldersByConv = mapOf(
                "group:g1" to listOf(folder("group:g1")),
                "group:g2" to listOf(folder("group:g2", "team-b")),
            ),
            expandedConvs = setOf("group:g1", "group:g2"),
        )
        val keys = rows.map { it.key }
        assertEquals(keys.size, keys.toSet().size, "渲染行的 key 必须唯一，否则 LazyColumn 复用会串行")
    }
}
