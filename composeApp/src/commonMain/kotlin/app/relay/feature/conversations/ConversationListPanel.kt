// SPDX-License-Identifier: GPL-3.0-only

package app.relay.feature.conversations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.relay.app.WorkspaceUnitUi
import app.relay.ui.theme.LocalAppSpacing

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
     * 列表尾部追加的东西（桌面左栏的"搜到的消息"那一段）。
     *
     * 用**插槽**而不是参数化：它整块不同（导出不同、行类型不同、还带小节标题），
     * 参数化会在这儿长出一堆条件分支。插槽的边界很清楚 —— 会话行那部分仍然只有一份。
     */
    trailingContent: (LazyListScope.() -> Unit)? = null,
) {
    val sp = LocalAppSpacing.current
    LazyColumn(
        state = state,
        modifier = modifier,
        contentPadding = contentPadding,
        // 原型 \`.sidelist{gap:1px}\` —— 行间 2dp（档位里最接近的）。用 8dp 会让
        // 圆角行看起来像一张张独立的卡，反而与"扁平行 + 分割线"这套分组打架。
        verticalArrangement = Arrangement.spacedBy(sp.xxs),
    ) {
        itemsIndexed(feed, key = { _, item -> item.key }) { index, item ->
            when (item) {
                is InboxFeedItem.Conversation -> ConversationRow(
                    row = item.conv,
                    now = now,
                    onOpenChat = onOpenChat,
                    onTogglePin = onTogglePin?.let { act -> { act(item.conv.convId) } },
                    onToggleMute = onToggleMute?.let { act -> { act(item.conv.convId) } },
                    onDeleteConversation = onDeleteConversation?.let { act -> { act(item.conv.convId) } },
                    selected = selectedConvId != null && item.conv.convId == selectedConvId,
                )

                is InboxFeedItem.Workspace -> WorkspaceFeedRow(
                    unit = item.unit,
                    group = item.group,
                    now = now,
                    onOpenWorkspaceUnit = { onOpenWorkspaceUnit?.invoke(it) },
                )
            }
            // 最后一行下面不画线：列表尾部再挂一条会看起来像"下面还有东西"。
            if (index != feed.lastIndex) ConversationListDivider()
        }
        trailingContent?.invoke(this)
    }
}
