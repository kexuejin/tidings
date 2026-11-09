// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.conversations

import com.kapp.tenon.domain.model.TeamFolderUi
import com.kapp.tenon.domain.model.TeamMemberUi
import com.kapp.tenon.domain.model.teamGroupKey

/**
 * Team Folder 的**折叠投影**：排序层与渲染层之间的一步纯函数（阶段 0 / C4）。
 *
 * ## 为什么单独一层
 *
 * - 排序的唯一真源是 [orderInboxFeed] / [orderConversationList]（置顶 → 有未处理 → 最近活动）。
 *   折叠若在渲染层各写一份，手机与桌面迟早给出不同结果；若在排序层塞分支，又要给
 *   [InboxFeedItem] 补四个抽象属性并改 `ConversationSidebar` 的 public 签名。
 * - 所以折叠只做**结构投影**：输入已排序的 feed + team 映射 + 展开集合，输出"组头/成员/原行"
 *   交替的渲染序列。**不重排、不改协议、不新增会话实体。**
 *
 * ## 纪律
 *
 * - 组的位置跟随它那条会话在 feed 中的位置，不挪到最前。
 * - 折叠态下组头**替代**会话行（组头本身就是进入该会话的入口）；展开态在组头下补回会话行
 *   （只在同会话的第一个组下补一次）与成员行。
 * - 同一 `convId` 上挂多个 team 时按 `(convId, pluginId)` 各出一行；只有成员是各自的。
 * - 折叠集合由屏幕级持有并作为参数传入 —— 本函数是纯函数，不持有 UI 状态。
 */
internal sealed interface FeedRow {
    val key: String

    data class Folder(
        val folder: TeamFolderUi,
        val expanded: Boolean,
        /**
         * 被替代的那条会话行的未读数。
         *
         * 为什么必须带出来：折叠态下组头**替代**了会话行，若组头不画未读，那一格的未读数
         * 就从列表里消失了 —— 而副标是按全部会话求和算的（"N 条未读"），于是会出现
         * "标题说 4 条未读、列表只数得出 2 条"。本仓对这类"两处说法不一致"是零容忍的
         * （工作区段整段不画却仍报工作区数，是同一个毛病）。
         */
        val unread: Int = 0,
    ) : FeedRow {
        override val key: String get() = "team_" + folder.convId + "_" + folder.pluginId
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

    data class Item(val item: InboxFeedItem) : FeedRow {
        override val key: String get() = item.key
    }
}

/**
 * 把已排序的 feed 投影成渲染序列。
 *
 * @param foldersByConv 会话键 → 该会话上挂的团队（一个群可挂多个 team，按 manifest 顺序）
 * @param expandedGroups 已展开的组键集合（[teamGroupKey]）；空 = 全折叠
 */
internal fun foldTeamRows(
    feed: List<InboxFeedItem>,
    foldersByConv: Map<String, List<TeamFolderUi>>,
    expandedGroups: Set<String> = emptySet(),
): List<FeedRow> {
    if (foldersByConv.isEmpty()) return feed.map { FeedRow.Item(it) }

    val out = ArrayList<FeedRow>(feed.size)
    // 同一条会话行在一个会话只补一次：两个 team 都展开时，会话行不会出现两遍。
    val sessionRowEmitted = HashSet<String>()

    for (item in feed) {
        if (item is InboxFeedItem.Conversation) {
            val folders = foldersByConv[item.conv.convId]
            if (!folders.isNullOrEmpty()) {
                for (folder in folders) {
                    val expanded = teamGroupKey(folder.convId, folder.pluginId) in expandedGroups
                    out += FeedRow.Folder(folder, expanded, item.conv.unread)
                    if (!expanded) continue
                    if (sessionRowEmitted.add(folder.convId)) out += FeedRow.Item(item)
                    for (member in folder.members) {
                        out += FeedRow.Member(folder.convId, folder.pluginId, folder.title, member)
                    }
                }
                continue
            }
        }
        out += FeedRow.Item(item)
    }
    return out
}
