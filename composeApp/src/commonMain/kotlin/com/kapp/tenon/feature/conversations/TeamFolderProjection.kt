// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.conversations

import com.kapp.tenon.domain.model.TeamFolderUi
import com.kapp.tenon.domain.model.TeamMemberUi

/**
 * Team Folder 的**折叠投影**：排序层与渲染层之间的一步纯函数。
 *
 * ## 形态（一句话）
 *
 * 挂了团队的群**仍然是一条会话行**，折叠只收起成员列表 —— 不替代、不复制、不丢信息。
 *
 * 这条是本文件最重要的约束，也是它上一版被推翻的原因：上一版让「组头」替代会话行，
 * 结果组头只画得了 状态点/名字/人数/未读，**画不了最后一条消息与时间**，展开后还要把
 * 同一条会话补回来 ⇒ 折叠态信息残缺、展开态同会话两个表示。现在会话行永远在，
 * 团队信息只是行内一枚标记 + 行下若干成员行。
 *
 * ## 纪律
 *
 * - 排序真源仍是 `orderInboxFeed` / `orderConversationList`；本函数**不重排**，
 *   团队信息的位置永远跟随它所属的那条会话。
 * - 折叠态每条会话**恰好一行**；展开态 = 该行 + 每个团队一行标签 + 各团队成员行。
 * - 展开状态是**会话级**的（[foldTeamRows] 的 `expandedConvs`）：一行只能有一个折叠符号，
 *   所以一个群挂多个团队时，展开即显示全部团队，用标签行区分归属。
 * - 会话键只能是 `group:<groupId>`，不造 `team-*`（那会被判私聊 ⇒ 静默无帧）。
 */
internal sealed interface FeedRow {
    val key: String

    /** 一条会话行。`teams` 非空 = 这行挂了团队（行内画标记），但**行本身仍是会话**。 */
    data class Session(
        val item: InboxFeedItem.Conversation,
        val teams: List<TeamFolderUi>,
        val expanded: Boolean,
    ) : FeedRow {
        override val key: String get() = item.key

        /** 任一团队在跑即点亮：与域层「任一 BUSY 即 Running」同一口径，不另立判据。 */
        val running: Boolean get() = teams.any { it.isRunning }

        val memberCount: Int get() = teams.sumOf { it.members.size }
    }

    /**
     * 展开态下每个团队一行标签：团队名（协议里就是 leader 的 `@` token）+ 人数。
     *
     * 为什么需要它：会话行上不能同时写「N 个成员」与群行副标的「n/m 人在线」（一个是 Agent、
     * 一个是人，并排会读成同一个计数）；人数与 token 放进缩进的标签行，语义才不打架。
     * 一个群挂多个团队时，它也是成员归属的唯一线索。
     */
    data class TeamLabel(
        val convId: String,
        val pluginId: String,
        val title: String,
        val memberCount: Int,
    ) : FeedRow {
        override val key: String get() = "teamlabel_" + convId + "_" + pluginId
    }

    data class Member(
        val convId: String,
        val pluginId: String,
        /** 该团队 leader 的 `@` token（协议里 `VirtualAgentMember.name` 就是它）。 */
        val token: String,
        val member: TeamMemberUi,
    ) : FeedRow {
        override val key: String get() =
            "teammember_" + convId + "_" + pluginId + "_" + member.agentId
    }

    /** 非会话条目（目前只有「工作区」）。类型上分开，`when` 才是穷尽的。 */
    data class WorkspaceItem(val item: InboxFeedItem.Workspace) : FeedRow {
        override val key: String get() = item.key
    }
}

/**
 * 把已排序的 feed 投影成渲染序列。
 *
 * @param foldersByConv 会话键 → 该会话上挂的团队（一个群可挂多个 team）
 * @param expandedConvs 已展开的**会话键**集合；空 = 全折叠
 */
internal fun foldTeamRows(
    feed: List<InboxFeedItem>,
    foldersByConv: Map<String, List<TeamFolderUi>>,
    expandedConvs: Set<String> = emptySet(),
): List<FeedRow> {
    val out = ArrayList<FeedRow>(feed.size)
    for (item in feed) {
        when (item) {
            is InboxFeedItem.Conversation -> {
                val teams = foldersByConv[item.conv.convId].orEmpty()
                val expanded = teams.isNotEmpty() && item.conv.convId in expandedConvs
                out += FeedRow.Session(item, teams, expanded)
                if (!expanded) continue
                for (team in teams) {
                    out += FeedRow.TeamLabel(team.convId, team.pluginId, team.title, team.members.size)
                    for (member in team.members) {
                        out += FeedRow.Member(team.convId, team.pluginId, team.title, member)
                    }
                }
            }

            is InboxFeedItem.Workspace -> out += FeedRow.WorkspaceItem(item)
        }
    }
    return out
}
