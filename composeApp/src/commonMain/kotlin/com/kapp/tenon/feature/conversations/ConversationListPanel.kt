// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.conversations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.kapp.tenon.domain.WorkspaceUnitUi
import com.kapp.tenon.domain.model.TeamFolderUi
import com.kapp.tenon.platform.PlatformLazyListScrollbar
import com.kapp.tenon.ui.theme.LocalAppSpacing

/**
 * 会话列表的**滚动体** —— 手机屏与桌面左栏共用这一份。
 *
 * ## 为什么它单独存在
 *
 * 合并之前两个壳各写了一个 \`LazyColumn + ConversationRow + 分割线\`：手机那份在
 * \`ConversationsScreen\`、桌面那份在 \`ConversationSidebar\`。行本身已经共享了（见
 * \`ConversationRow\`），但**行的容器**还在各写一遍 —— 而行的间距、分割线的位置、
 * 最后一行画不画线，全在容器里。两个容器 = 两种列表观感，这正是「同一个应用里同一个
 * 列表长两个样」的最后一层。
 *
 * ## 什么留在这里、什么留给调用方
 *
 * | 留在本组件（**内容**） | 留给调用方（**外壳**） |
 * |---|---|
 * | 行间距、分割线、最后一行不画线 | 顶栏、banner、筛选 chip |
 * | 会话行 vs 工作区行的分派 | 排序（\`orderInboxFeed\`）与筛选 |
 * | 选中态透传 | 空态、建群入口、搜索 |
 *
 * 判据就是 AGENTS.md §2.6 那条：**把外壳换掉之后，这块像素还应不应该一样？**
 * 列表的"一行挨一行"应该一样，所以它在这里。
 *
 * @param feed 已经排好序、也筛过的行 —— 排序与筛选是外壳的决定，本组件不重排。
 * @param selectedConvId 桌面两栏的选中会话；手机传 null（列表独占一屏，没有"选中"）。
 * @param onOpenWorkspaceUnit 工作区行的下钻。null = 这个壳没有工作区段（那么 feed 里也不该有）。
 */
@Composable
internal fun ConversationListPanel(
    feed: List<InboxFeedItem>,
    now: Long,
    onOpenChat: (String) -> Unit,
    /** 长按会话行的三个动作；全 null = 不提供菜单（见 ConversationRow 的同名参数）。 */
    onTogglePin: ((String) -> Unit)? = null,
    onToggleMute: ((String) -> Unit)? = null,
    onDeleteConversation: ((String) -> Unit)? = null,
    /** 群会话「删除并退出群聊」用；null = 这个壳没有退群能力（见 ConversationRow 同名参数）。 */
    onLeaveGroup: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    /**
     * 滚动状态。**调用方持有**，这样「进会话 → 返回」时位置还在。
     *
     * 此前这个 LazyColumn 没有 state 参数，Compose 会为它隐式创建一个 —— 隐式 state
     * 随这个 Composable 的实例存在，导航切走一屏它就没了。真机现场：116 个会话的列表，
     * 用户翻到「11 天前」那批，点进一个会话再返回，**回到顶部**，得重新滚。
     *
     * 默认 rememberLazyListState() 让老调用点行为不变；要保住位置的那一侧自己传一个
     * 活得更久的 state（见 ConversationsScreen / DesktopConversationWidgets）。
     */
    state: LazyListState = rememberLazyListState(),
    selectedConvId: String? = null,
    onOpenWorkspaceUnit: ((WorkspaceUnitUi) -> Unit)? = null,
    /**
     * 群会话预览的发送者显示名解析（convId → 名字；null/解析不出 = 不署名）。
     *
     * 名字的权威在域层（`RelayUiState.previewSenderNameOf`，与 convTitle 同一个解析器）；
     * 这里只把它从壳运到行 —— 拼装用的文案归 UI 层（`conv_preview_sender`），域层不写死。
     * null = 这个壳不署名（预览 / 未接域层的宿主），行的形状不变。
     */
    previewSenderNameOf: ((String) -> String?)? = null,
    /**
     * 会话草稿预览（convId → 已拼好、带「草稿」标记的文案；null = 该会话没有草稿）。
     *
     * 草稿表活在壳里（手机 App.kt / 桌面 DesktopShell），列表拿不到 —— 所以由壳注入。
     * null = 这个壳没接草稿（预览/截图夹具），行形状与行为保持不变。
     */
    draftPreviewOf: ((String) -> String?)? = null,
    /**
     * 列表尾部追加的东西（桌面左栏的"搜到的消息"那一段）。
     *
     * 用**插槽**而不是参数化：它整块不同（导出不同、行类型不同、还带小节标题），
     * 参数化会在这儿长出一堆条件分支。插槽的边界很清楚 —— 会话行那部分仍然只有一份。
     */
    trailingContent: (LazyListScope.() -> Unit)? = null,
    /**
     * 桌面密度档，原样透传给 [ConversationRow]（见它的 dense KDoc）。
     *
     * 容器也是共享组件（AGENTS §2.6）：绕过它把密度直接传给行，等于把「行的容器」
     * 又拆回两份。默认 false = 手机调用方不传即手机帧零变化。
     */
    dense: Boolean = false,
    /**
     * Team Folder：会话键 → 该会话上挂的团队（一个群可挂多个 team，按 manifest 顺序）。
     *
     * 空 = 不折叠。默认空是为了让预览、截图夹具与尚未接域的壳保持**零像素变化**。
     */
    teamFoldersByConv: Map<String, List<TeamFolderUi>> = emptyMap(),
    /**
     * 已展开的**会话键**集合（`group:<groupId>`）。空 = 全折叠。
     *
     * 为什么是会话级而不是团队级：一行只有一个折叠符号。一个群挂多个团队时，展开即显示
     * 全部团队（用标签行区分归属）—— 否则同一行要画两个开关。
     * 由屏幕级持有 —— 容器是共享组件，`remember` 在这里会让手机与桌面各自记一份折叠态。
     */
    expandedTeamConvs: Set<String> = emptySet(),
    /** 折叠回调：参数是**会话键**。调用方自行维护 [expandedTeamConvs]。null = 标记只展示。 */
    onToggleTeam: ((String) -> Unit)? = null,
    /**
     * 点团队成员：请壳打开该群会话并预填 `@token`。
     *
     * null = 成员行不可点（预览、或壳没有草稿写入口）。预填文本由壳拼 —— 列表不该知道
     * `@` 后面要加空格这种事。
     */
    onMentionMember: ((convId: String, token: String) -> Unit)? = null,
) {
    val sp = LocalAppSpacing.current
    // 折叠只做结构投影，不重排：排序真源仍是 orderInboxFeed / orderConversationList。
    val rows = remember(feed, teamFoldersByConv, expandedTeamConvs) {
        foldTeamRows(feed, teamFoldersByConv, expandedTeamConvs)
    }
    Box(modifier = modifier) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            // 原型 `.sidelist{gap:1px}` —— 行间 2dp（档位里最接近的）。用 8dp 会让
            // 圆角行看起来像一张张独立的卡，反而与"扁平行 + 分割线"这套分组打架。
            verticalArrangement = Arrangement.spacedBy(sp.xxs),
        ) {
            itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
                when (row) {
                    // 会话行永远是**这一行本身**：团队信息只是行内一枚标记，不替代、不复制。
                    is FeedRow.Session -> ConversationRow(
                        row = row.item.conv,
                        now = now,
                        onOpenChat = onOpenChat,
                        onTogglePin = onTogglePin?.let { act -> { act(row.item.conv.convId) } },
                        onToggleMute = onToggleMute?.let { act -> { act(row.item.conv.convId) } },
                        onDeleteConversation = onDeleteConversation?.let { act -> { act(row.item.conv.convId) } },
                        onLeaveGroup = onLeaveGroup?.let { act -> { act(row.item.conv.convId) } },
                        previewSenderName = previewSenderNameOf?.invoke(row.item.conv.convId),
                        draftPreview = draftPreviewOf?.invoke(row.item.conv.convId),
                        selected = selectedConvId != null && row.item.conv.convId == selectedConvId,
                        dense = dense,
                        teamSlot = if (row.teams.isEmpty()) {
                            null
                        } else {
                            {
                                TeamBadge(
                                    convId = row.item.conv.convId,
                                    running = row.running,
                                    expanded = row.expanded,
                                    memberCount = row.memberCount,
                                    onToggle = onToggleTeam,
                                )
                            }
                        },
                    )

                    is FeedRow.TeamLabel -> TeamLabelRow(
                        token = row.title,
                        memberCount = row.memberCount,
                    )

                    is FeedRow.Member -> TeamMemberRow(
                        member = row.member,
                        onClick = onMentionMember?.let { mention ->
                            { mention(row.convId, row.token) }
                        },
                    )

                    is FeedRow.WorkspaceItem -> WorkspaceFeedRow(
                        unit = row.item.unit,
                        group = row.item.group,
                        now = now,
                        onOpenWorkspaceUnit = { onOpenWorkspaceUnit?.invoke(it) },
                    )
                }
                // 最后一行下面不画线：列表尾部再挂一条会看起来像"下面还有东西"。
                // 展开块（团队标签 + 成员行）内部也不画线：它们是同一条会话的下挂内容，
                // 画线会把它们拆成互不相干的会话行。块的最后一行之后才恢复分割线。
                val next = rows.getOrNull(index + 1)
                val nextIsBlockBody = next is FeedRow.TeamLabel || next is FeedRow.Member
                if (!nextIsBlockBody && index != rows.lastIndex) ConversationListDivider()
            }
            trailingContent?.invoke(this)
        }
        PlatformLazyListScrollbar(
            state = state,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}
