// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.conversations

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.kapp.tidings.ui.theme.LocalAppSpacing
import com.kapp.tidings.domain.RelayUiState
import com.kapp.tidings.domain.model.ConversationUi
import com.kapp.tidings.domain.WorkspaceUnitGroupUi
import com.kapp.tidings.domain.WorkspaceUnitUi
import com.kapp.tidings.domain.inboxWorkspaceUnits
import com.kapp.tidings.core.nowMillis
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.conv_empty_action
import com.kapp.tidings.generated.resources.conv_empty_message
import com.kapp.tidings.generated.resources.conv_empty_title
import com.kapp.tidings.generated.resources.conv_filter_empty_action
import com.kapp.tidings.generated.resources.conv_filter_empty_title
import com.kapp.tidings.generated.resources.conv_loading
import com.kapp.tidings.generated.resources.conv_title
import com.kapp.tidings.generated.resources.conv_more_action
import com.kapp.tidings.generated.resources.conv_filter_all
import com.kapp.tidings.generated.resources.conv_filter_unread
import com.kapp.tidings.generated.resources.conv_filter_groups
import com.kapp.tidings.generated.resources.conv_filter_agents
import com.kapp.tidings.generated.resources.conv_filter_people
import com.kapp.tidings.generated.resources.conv_filter_workspaces
import com.kapp.tidings.generated.resources.conv_subtitle_summary
import com.kapp.tidings.generated.resources.conv_subtitle_summary_ws
import com.kapp.tidings.generated.resources.conv_subtitle_unread
import com.kapp.tidings.generated.resources.conv_subtitle_recent_suffix
import com.kapp.tidings.generated.resources.conv_subtitle_recent_suffix_ws
import com.kapp.tidings.generated.resources.conv_search_hint_global
import com.kapp.tidings.generated.resources.ic_search
import com.kapp.tidings.generated.resources.ic_more
import com.kapp.tidings.ui.components.AppBanner
import com.kapp.tidings.ui.components.GroupCreateDialog
import com.kapp.tidings.ui.components.LargeTitleBar
import com.kapp.tidings.ui.components.TitleBarDivider
import com.kapp.tidings.ui.components.TopBarIconAction
import com.kapp.tidings.ui.EmptyState
import com.kapp.tidings.ui.LoadingState
import org.jetbrains.compose.resources.stringResource
import com.kapp.tidings.domain.model.NodePhase

/**
 * 会话列表。
 *
 * 与脚手架版本的根本差别：这里渲染的是 [RelayUiState.conversations] ——
 * 由真实节点表与真实消息流聚合出来的行，而不是 `ConversationList / long_title, many_items`
 * 这样的占位串。
 *
 * **曾经有过一个 `compact: Boolean = false` 参数**（审计 §5.2 / 工作包 V6），
 * 注释写的是「宽屏双栏时列表收窄，不需要再显示整块顶栏」。它已被删除，原因不是
 * 「没人用」而是**它的前提已经不成立**：
 * - 全仓 12 个调用点没有一处传过它，生产调用点 `App.kt` 也不传，所以它恒为 `false`，
 *   顶栏（本文件下方的 `LargeTitleBar`）在生产里**恒画**；
 * - 更根本的是，宽屏双栏那条路根本不走这个函数 —— `App.kt` 的 `twoPane` 分支交给
 *   `DesktopShell`，会话列表由 `DesktopConversationsPane` 渲染。
 *   也就是说「宽屏双栏时收窄顶栏」这件事**在这个函数里永远不会发生**。
 * 留一个恒假的分支比删掉更危险：下一个人会以为它接上了。
 * 若将来真要在宽屏复用本屏，正确的做法是让它自己量宽度（照 `CabinetPane` 的
 * `sizeClass` 形状），而不是由调用方塞一个布尔值进来。
 */
@Composable
fun ConversationsScreen(
    state: RelayUiState,
    onOpenChat: (String) -> Unit,
    onGoToPairing: () -> Unit,
    onDismissBanner: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 会话列表的滚动状态；由上层持有，才能在「进会话 → 返回」后保住位置。
     * 默认在内部创建（行为与改动前一致），要保住位置的壳自己传。
     */
    listState: LazyListState = rememberLazyListState(),
    /**
     * 渲染"几分钟前"所用的当前时刻。
     *
     * 为什么是参数而不是在函数里直接读挂钟：相对时间只要在渲染时取真实时间，
     * 同一帧在两次运行里就会渲染出不同的字，截图门禁永远红灯。把它提出来，
     * 预览可以钉死一个时刻，参考图才是确定的。
     */
    now: Long = nowMillis(),
    /**
     * 建群。null = 这个壳不画建群入口（预览与没有控制器的宿主）。
     *
     * 回调给的是 `(名字, 成员 nodeId 列表)`；`groupId` 由控制器生成 —— 界面不该
     * 有机会造出两个同 id 的群，那是控制器的职责。
     */
    onCreateGroup: ((String, List<String>) -> Unit)? = null,
    /**
     * 建群对话框是否**一开始就打开**，默认关。
     *
     * 与 [initialFilter] 同一个理由：`creating` 是屏内私有状态，没有任何参数能打开它，
     * 于是这个对话框只能靠人手点出来，截图门禁永远抓不到 —— 而它是本轮新增的
     * 唯一一个对话框，成员排序、自己不在名单里、"没有可拉的人时说清楚"三条规矩
     * 全在里面。生产调用方一律走默认值。
     */
    initialCreating: Boolean = false,
    /**
     * 筛选 chip 的初值（`all` / `unread` / `group` / `agent` / `people`），**只给截图门禁用**。
     *
     * `filter` 是屏内状态，静态帧点不了那颗 chip：没有这个接缝，原型 `m01c-home-unread`
     * 那一屏（副标换成「N 个会话有未读 · 共 M 条」、只留两行、banner 整条不画）
     * 永远录不到 —— 而"按未读过滤时 banner 不该再占一行"正是那一屏要钉的规矩。
     * 生产调用方一律用默认值。
     */
    initialFilter: String = "all",
    /**
     * 「全部标为已读」。null = 这个壳没有这条出口（那一项画成灰的，而不是消失）。
     *
     * 为什么画灰而不是不画：原型 `m01b-home-more` 的菜单里这一项是**有**的，
     * 而且菜单项的有无本身就是信息 —— 少一项会让人以为"这个壳不支持"，
     * 画灰则是"这个壳支持、但此刻没接上"。同一份菜单在预览里也能看出差别。
     */
    onMarkAllRead: (() -> Unit)? = null,
    /** 长按会话行的三个动作；null = 这个壳不提供菜单（预览/门禁用默认值）。 */
    onTogglePin: ((String) -> Unit)? = null,
    onToggleMute: ((String) -> Unit)? = null,
    onDeleteConversation: ((String) -> Unit)? = null,
    /**
     * 打开**全局搜索**（原型 `m-gsearch`）。null = 这个壳没有这条出口，那颗放大镜整颗不画。
     *
     * 为什么这颗在**顶栏**而不是列表上方：原型 `screenGSearch` 的注释逐字写着
     * 「这是移动端唯一的搜索入口：它搜全库（跨会话的消息体、文件、联系人），
     * 而提案已经删掉了列表上方那条只管**标题与预览**的框 —— 两条并排在窄屏上分不清
     * 哪条搜哪儿，所以只留范围更大的这条」。原型顶栏那颗的落点是
     * `prototype/relay.html:2803` / `:2888` 的 `<button class="ibtn" aria-label="全局搜索"
     * onclick="go('m-gsearch')">`，就在 ⋮ 左边。
     */
    onSearchGlobal: (() -> Unit)? = null,
    /**
     * 点「工作区」段里的一行（下钻）。
     *
     * 段里的一行**不是**一个会话：Agent 行下钻到该 Agent 的会话详情、工作区行下钻到工作区详情。
     * 「打开聊天」是行尾那颗按钮的事（`onOpenChat`）—— 这两件事在本轮之前是同一颗点击
     * （看板整卡与"去会话"都打开聊天），这一屏是第一个把它们分开的地方（设计稿 §5）。
     *
     * 与 [onOpenWorkspaceBoard] 一起为 null 时整段不画：预览与没有控制器的宿主不该凭空多出一段。
     */
    onOpenWorkspaceUnit: ((WorkspaceUnitUi) -> Unit)? = null,
    /**
     * 段尾那行「还有 N 行」的落点：工作区看板（原型 `m-agents`）。null = 不画这一段（同上）。
     */
    onOpenWorkspaceBoard: (() -> Unit)? = null,
) {
    var creating by remember { mutableStateOf(initialCreating) }
    var showMenu by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(initialFilter) }

    val conversations = state.conversations
    val totalUnread = conversations.sumOf { it.unread }
    // 「工作区」段的取数：排序、上限、待审批的二次 join 都在派生里做完（见 InboxWorkspaceUnits.kt）。
    // 一次派生、一次排序、一次裁剪 —— Composable 只负责画，重排不会在这里发生。
    val workspaceUnits = state.inboxWorkspaceUnits()
    // 段画不画，副标就报不报工作区数：两者必须是**同一个**判据。否则会出现
    // 「标题栏说有 1 个工作区、下面一段都没有」的谎话（预览帧就是这么露出来的：
    // 帧不传入口 lambda ⇒ 段不画，副标却还报着数）。
    val showWorkspaces = onOpenWorkspaceUnit != null && onOpenWorkspaceBoard != null

    val allFeedItems = remember(conversations, workspaceUnits, showWorkspaces) {
        orderInboxFeed(
            buildList {
                conversations.forEach { add(InboxFeedItem.Conversation(it)) }
                if (showWorkspaces) {
                    workspaceUnits.groups.forEach { group ->
                        group.units.forEach { unit ->
                            add(InboxFeedItem.Workspace(unit, group))
                        }
                    }
                }
            },
        )
    }

    val filteredFeed = allFeedItems.filter { item ->
        when (filter) {
            "unread" -> when (item) {
                is InboxFeedItem.Conversation -> item.conv.unread > 0
                is InboxFeedItem.Workspace -> item.unit.pendingCount > 0
            }
            "workspace" -> item is InboxFeedItem.Workspace
            "group" -> item is InboxFeedItem.Conversation && item.conv.isGroup
            "agent" -> item is InboxFeedItem.Conversation && !item.conv.isGroup && item.conv.isAgent
            "people" -> item is InboxFeedItem.Conversation && !item.conv.isGroup && !item.conv.isAgent
            else -> true
        }
    }

    if (creating && onCreateGroup != null) {
        GroupCreateDialog(
            peers = state.peers,
            onDismiss = { creating = false },
            onConfirm = { name, members ->
                creating = false
                onCreateGroup(name, members)
            },
        )
    }

    Column(modifier.fillMaxSize()) {
        LargeTitleBar(
            title = stringResource(Res.string.conv_title),
            // 副标随筛选轴走，照原型两张图各自的原文：
            //  - `m01-home-recent`：「5 个会话 · 3 条未读，按最近一条消息排」
            //  - `m01c-home-unread`：「2 个会话有未读 · 共 3 条」
            // 两者不是同一句话换个数：前者第一项是**会话总数**，后者第一项是
            // **有未读的会话数** —— 未读轴上"总共有几个会话"已经不是问题了。
            subtitle = if (filter == "unread") {
                val unreadFeedCount = allFeedItems.count {
                    when (it) {
                        is InboxFeedItem.Conversation -> it.conv.unread > 0
                        is InboxFeedItem.Workspace -> it.unit.pendingCount > 0
                    }
                }
                stringResource(
                    Res.string.conv_subtitle_unread,
                    unreadFeedCount,
                    totalUnread + (if (showWorkspaces) workspaceUnits.pendingTotal else 0),
                )
            } else {
                val withWorkspaces = showWorkspaces && workspaceUnits.totalUnits > 0
                val summary = if (withWorkspaces) {
                    stringResource(
                        Res.string.conv_subtitle_summary_ws,
                        conversations.size,
                        workspaceUnits.totalUnits,
                        totalUnread + workspaceUnits.pendingTotal,
                    )
                } else {
                    stringResource(Res.string.conv_subtitle_summary, conversations.size, totalUnread)
                }
                val suffix = if (withWorkspaces) {
                    stringResource(Res.string.conv_subtitle_recent_suffix_ws)
                } else {
                    stringResource(Res.string.conv_subtitle_recent_suffix)
                }
                summary + suffix
            },
            actions = {
                // 全局搜索入口。原型顶栏是「🔍 然后 ⋮」两颗，且 🔍 在左；
                // 两颗都是 .ibtn 浅色圆角方块（见 TopBarIconAction 的 KDoc），不是裸图标。
                if (onSearchGlobal != null) {
                    TopBarIconAction(
                        icon = Res.drawable.ic_search,
                        contentDescription = stringResource(Res.string.conv_search_hint_global),
                        onClick = onSearchGlobal,
                    )
                }
                Box {
                    TopBarIconAction(
                        icon = Res.drawable.ic_more,
                        contentDescription = stringResource(Res.string.conv_more_action),
                        onClick = { showMenu = true },
                    )
                    // 项序与分隔线照原型 `m01b-home-more`（`screenHomeMore`）逐条对齐：
                    // 建群 / 新建会话（去配对节点）｜只看有未读的 / 全部标为已读｜排序：最近优先。
                    // 原型把最后一项「排序：最近优先」画成**灰的** —— 排序键目前是固定的
                    // 最近优先，没有第二种排法，所以这里也 `enabled = false`，不做成可点的空动作。
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                    ) {
                        ConversationsOverflowItems(
                            onCreateGroup = onCreateGroup?.let { { creating = true } },
                            onGoToPairing = onGoToPairing,
                            onOnlyUnread = { filter = "unread" },
                            onMarkAllRead = onMarkAllRead,
                        )
                    }
                }
            },
        )
        TitleBarDivider()

        state.banner?.let {
            AppBanner(it, onDismissBanner, Modifier.padding(horizontal = LocalAppSpacing.current.lg, vertical = LocalAppSpacing.current.sm))
        }

        // 未读轴上**整条不画**待发横幅：原型 `m01c-home-unread` 里没有它。
        // 不是"画了但被折叠"—— 那一屏要钉的就是"只看未读时，屏幕纵向空间全给列表"，
        // 所以横幅（以及上面那条 `state.banner`）在这条轴上都让位。
        // **不再画「有成员不在线，他们上线后会收到」这条横幅。**
        //
        // 它说明的是一件用户既不能验证、也不能处理的事：消息本来就进了队列（会自动补发），
        // 而"谁不在线"在这条列表上既没有主体、也没有下一步 —— 用户原话「这个提示没有任何用」。
        // 真正需要告知「这条还没发出去」的地方是**那条消息自己**（气泡上的发送态），
        // 那里有主体、有时机，也能重试。
        //
        // `state.totalPending` 仍然保留在状态里：气泡的发送态与补发逻辑都还在用它，
        // 这里只是不再把它抬成一条列表级横幅。

        // 筛选标签条：全部 / 未读 / 群聊 / Agent / 同事。
        //
        // **每一颗都带计数**，不是只有未读那颗带 —— 原型 `m01-home-recent` 那一排是
        // 「全部 5 / 未读 3 / 群聊 1 / Agent 2 / 同事 2」，五个数全在。
        // 计数写在药丸里而不是散在列表上，所以"群里还有 0 条未读"也是能一眼看出来的
        // （原型 `m01c-home-unread` 的注释）。
        //
        // 未读那颗的数字是**未读消息条数**（`totalUnread`），不是"有未读的会话数"——
        // 原型 5 个会话里只有 2 个有未读、而那 2 个一共 3 条，药丸上写的是 **3**。
        // 这两个数在这一屏上恰好只差 1，最容易顺手写错，所以分开算。
        val groupCount = conversations.count { it.isGroup }
        val agentCount = conversations.count { !it.isGroup && it.isAgent }
        val peopleCount = conversations.count { !it.isGroup && !it.isAgent }
        val workspaceCount = if (showWorkspaces) workspaceUnits.totalUnits else 0
        val pendingTotal = if (showWorkspaces) workspaceUnits.pendingTotal else 0
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = LocalAppSpacing.current.lg, vertical = LocalAppSpacing.current.xs),
            horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterPill(
                selected = filter == "all",
                onClick = { filter = "all" },
                label = "${stringResource(Res.string.conv_filter_all)} ${allFeedItems.size}",
            )
            FilterPill(
                selected = filter == "unread",
                onClick = { filter = "unread" },
                label = "${stringResource(Res.string.conv_filter_unread)} ${totalUnread + pendingTotal}",
            )
            if (showWorkspaces && workspaceCount > 0) {
                FilterPill(
                    selected = filter == "workspace",
                    onClick = { filter = "workspace" },
                    label = "${stringResource(Res.string.conv_filter_workspaces)} $workspaceCount",
                )
            }
            FilterPill(
                selected = filter == "group",
                onClick = { filter = "group" },
                label = "${stringResource(Res.string.conv_filter_groups)} $groupCount",
            )
            FilterPill(
                selected = filter == "agent",
                onClick = { filter = "agent" },
                label = "${stringResource(Res.string.conv_filter_agents)} $agentCount",
            )
            FilterPill(
                selected = filter == "people",
                onClick = { filter = "people" },
                label = "${stringResource(Res.string.conv_filter_people)} $peopleCount",
            )
        }

        when {
            // 「正在恢复会话…」只在**真的会去恢复**时画：关掉落盘（`persistMessages = false`）
            // 时应用一个字节都不读，画一句"正在恢复"就是在说一件没发生的事（问题 #2 要求 b）。
            // 这一档直接落到下面的空态 —— 那句话说清了"怎么才会有会话"，没有骗人。
            allFeedItems.isEmpty() && state.network.phase == NodePhase.Starting && state.settings.persistMessages ->
                LoadingState(
                    label = stringResource(Res.string.conv_loading),
                    modifier = Modifier.fillMaxWidth(),
                )

            allFeedItems.isEmpty() -> EmptyState(
                title = stringResource(Res.string.conv_empty_title),
                message = stringResource(Res.string.conv_empty_message),
                actionLabel = stringResource(Res.string.conv_empty_action),
                onAction = onGoToPairing,
                modifier = Modifier.fillMaxWidth(),
            )

            filteredFeed.isEmpty() -> EmptyState(
                title = stringResource(Res.string.conv_filter_empty_title),
                actionLabel = stringResource(Res.string.conv_filter_empty_action),
                onAction = { filter = "all" },
                modifier = Modifier.fillMaxWidth(),
            )

            // 行的容器是**共享组件**（`ConversationListPanel`）—— 行间距、分割线、
            // 最后一行不画线都在它里面。这里只提供外壳：边距与已排好序/筛过的 feed。
            else -> ConversationListPanel(
                feed = filteredFeed,
                now = now,
                onOpenChat = onOpenChat,
                onTogglePin = onTogglePin,
                onToggleMute = onToggleMute,
                onDeleteConversation = onDeleteConversation,
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = LocalAppSpacing.current.lg,
                    end = LocalAppSpacing.current.lg,
                    top = LocalAppSpacing.current.sm,
                    bottom = LocalAppSpacing.current.lg,
                ),
                onOpenWorkspaceUnit = onOpenWorkspaceUnit,
            )
        }
    }
}

/**
 * feed 的一行：会话或工作区。
 *
 * `timestamp` / `pendingCount` **只服务于 [orderInboxFeed] 的混排**，不是这两个概念在
 * 型号上的全量语义：会话已经有 `ConversationUi.lastMessageAt` 与 `pendingCount`
 * （待发条数），工作区有自己的 `lastActivityAt` / `pendingCount`（待审批条数）。
 */
internal sealed interface InboxFeedItem {
    val key: String

    /**
     * 混排用的时间键。会话取 [ConversationUi.lastMessageAt]（**没聊过是 0**，不是对端的
     * 最后在线时间）；工作区取最近活动。两把尺子各自成立，但都不再用"对端刚刚在线"。
     */
    val timestamp: Long

    /** 混排用的"待处理置顶"键。工作区 = 待审批条数；会话恒 0（理由见下）。 */
    val pendingCount: Int

    /**
     * 用户手动**置顶**了这一行。
     *
     * 它是排序的**第一把尺子**（比"有未处理"更优先）：用户钉它上去的意思就是
     * "不管多旧、多安静，都留在最上面"。工作区行恒 false —— 置顶是会话列表的概念，
     * 工作区段由它自己的待审批规则排序。
     */
    val isPinned: Boolean

    data class Conversation(val conv: ConversationUi) : InboxFeedItem {
        override val key: String get() = "conv_${conv.convId}"

        // 取 lastMessageAt 而不是 at：at 在"没聊过"时会退回对端 lastSeenAt，
        // 而局域网发现持续刷新它 —— 用它当键会把没聊过的在线对端一次次顶到最前（反馈 #1）。
        override val timestamp: Long get() = conv.lastMessageAt

        // 恒 0 是**对的**，不是漏了一个赋值：这里的 pendingCount 表达"待处理、该置顶"，
        // 会话的 ConversationUi.pendingCount 是"没发出去的消息"（ConversationRow 画徽标），
        // 两者不是一个概念。它若参与置顶，用户自己发的一条离线消息就会把会话顶到最上面。
        override val pendingCount: Int get() = 0

        override val isPinned: Boolean get() = conv.isPinned
    }

    data class Workspace(
        val unit: WorkspaceUnitUi,
        val group: WorkspaceUnitGroupUi,
    ) : InboxFeedItem {
        override val key: String get() = "ws_${unit.peerId}_${unit.agentKind.orEmpty()}_${unit.wsId.orEmpty()}"
        override val timestamp: Long get() = unit.lastActivityAt
        override val pendingCount: Int get() = unit.pendingCount

        // 置顶是**会话列表**的概念；工作区段用它自己的待审批规则排序。
        override val isPinned: Boolean get() = false
    }
}

/**
 * feed 的混排顺序：**只有工作区段在这里参与排序**。
 *
 * 会话段的顺序权威在 [RelayUiState.conversations]（按最近一条消息时间降序，见那里的 KDoc）。
 * 这里刻意不再给会话按时间重排一次 —— [InboxFeedItem.Conversation.timestamp] 取的是
 * [ConversationUi.lastMessageAt]（没聊过是 0），配合 `sortedWith` 的**稳定**特性，
 * 会话项之间只会保留派生好的顺序；不可能出现"没聊过的在线对端顶到有消息的会话前面"
 * （真机反馈 #1 的另一半）。工作区段仍是它自己的两把尺子：待处理的置顶，其余按最近活动。
 *
 * 抽成顶层函数是因为它原来埋在一个 `remember {}` 里，排序纪律改错了没人拦得住；
 * 现在可以直接对拍（见 `com.kapp.tidings.feature.conversations.ConversationsFeedOrderTest`）。
 */
internal fun orderInboxFeed(items: List<InboxFeedItem>): List<InboxFeedItem> =
    items.distinctBy { it.key }
        .sortedWith(
            // 第一把尺子 = 用户手动置顶：钉上去的意思就是"不管多旧、多安静，都在最上面"，
            // 所以它必须排在「有未处理」之前 —— 否则一条待审批就能把置顶会话挤下去。
            compareByDescending<InboxFeedItem> { it.isPinned }
                .thenByDescending { it.pendingCount > 0 }
                .thenByDescending { it.timestamp },
        )

