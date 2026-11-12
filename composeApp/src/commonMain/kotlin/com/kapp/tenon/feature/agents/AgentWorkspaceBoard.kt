// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.agents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kapp.tenon.domain.AgentSlice
import com.kapp.tenon.domain.NodeSlice
import com.kapp.tenon.domain.SettingsSlice
import com.kapp.tenon.domain.peer
import com.kapp.tenon.domain.RelayUiState
import com.kapp.tenon.domain.WorkspaceAgentSectionUi
import com.kapp.tenon.domain.agentSections
import com.kapp.tenon.domain.groupBoundToWorkspace
import com.kapp.tenon.domain.hubAgents
import com.kapp.tenon.domain.inboxWorkspaceUnits
import com.kapp.tenon.domain.pendingApprovalCount
import com.kapp.tenon.domain.mayDecideApproval
import com.kapp.tenon.domain.model.AgentApprovalUi
import com.kapp.tenon.domain.model.AgentSessionKey
import com.kapp.tenon.domain.model.WorkspaceUi
import com.kapp.tenon.domain.firstProjectCandidate
import com.kapp.tenon.feature.conversations.ConversationFailedEmptyState
import com.kapp.tenon.feature.conversations.InboxUnitRow
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.agent_empty_agents_only_card
import com.kapp.tenon.generated.resources.agent_empty_agents_only_message
import com.kapp.tenon.generated.resources.agent_empty_agents_only_title
import com.kapp.tenon.generated.resources.agent_empty_card
import com.kapp.tenon.generated.resources.agent_empty_exit_body
import com.kapp.tenon.generated.resources.agent_empty_exit_title
import com.kapp.tenon.generated.resources.agent_empty_message
import com.kapp.tenon.generated.resources.agent_empty_section
import com.kapp.tenon.generated.resources.agent_empty_section_n
import com.kapp.tenon.generated.resources.agent_empty_subtitle
import com.kapp.tenon.generated.resources.agent_empty_title
import com.kapp.tenon.generated.resources.agent_subtitle_pending
import com.kapp.tenon.generated.resources.agent_title
import com.kapp.tenon.generated.resources.approval_focus_subtitle
import com.kapp.tenon.generated.resources.approval_focus_subtitle_detail
import com.kapp.tenon.generated.resources.desktop_action_refresh
import com.kapp.tenon.generated.resources.ic_chev
import com.kapp.tenon.generated.resources.ic_lan_search
import com.kapp.tenon.generated.resources.ic_plus
import com.kapp.tenon.generated.resources.ic_refresh
import com.kapp.tenon.generated.resources.ic_warn
import com.kapp.tenon.generated.resources.workspace_board_discovering
import com.kapp.tenon.generated.resources.workspace_board_discovering_hint
import com.kapp.tenon.generated.resources.workspace_board_starting
import com.kapp.tenon.generated.resources.workspace_board_starting_hint
import com.kapp.tenon.generated.resources.workspace_board_subtitle
import com.kapp.tenon.generated.resources.workspace_board_action_create
import com.kapp.tenon.ui.ContentWidths
import com.kapp.tenon.ui.components.AppButton
import com.kapp.tenon.ui.components.AppButtonSize
import com.kapp.tenon.ui.components.LargeTitleBar
import com.kapp.tenon.ui.components.TitleBarDivider
import com.kapp.tenon.ui.theme.LocalAppSpacing
import com.kapp.tenon.ui.theme.LocalCardShape
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import com.kapp.tenon.generated.resources.conv_inbox_ws_badge_unknown_kind
import com.kapp.tenon.generated.resources.workspace_agent_section_pending
import com.kapp.tenon.ui.theme.IconSizes
import com.kapp.tenon.feature.conversations.UnassignedSessionsRow
import com.kapp.tenon.domain.unassignedSessions
import com.kapp.tenon.generated.resources.conv_inbox_ws_group_offline
import com.kapp.tenon.generated.resources.conv_inbox_ws_pending
import com.kapp.tenon.ui.components.StatusDot
import com.kapp.tenon.ui.components.TopBarIconAction
import com.kapp.tenon.ui.components.relativeTime
import com.kapp.tenon.domain.model.PeerUi
import com.kapp.tenon.ui.theme.relayCardShadow
import com.kapp.tenon.domain.model.AgentApprovalDecision

/**
 * 工作区页签的一级视图：**按节点分组的工作区列表**。
 *
 * ## 架构与视图设计
 *
 * 视图按节点分组工作区，直观反映 agent 及其工作区从属关系。
 *
 * ## 一处真源：派生与行组件都复用消息页那套
 *
 * 这一屏与消息页签「工作区」段取的是**同一个** `state.inboxWorkspaceUnits()`：排序
 * （待审批降序 → 最近活动降序）、上限、待审批的二次 join、降级阶梯全在派生里做完。
 * 三件组件（段头 / 组头 / 行）也复用 `InboxSectionHead` / `InboxGroupHead` / `InboxUnitRow`。
 *
 * 为什么不各写一份：统一使用同一套派生规则，避免同一状态在不同界面展示出不同结果。
 *
 * ## 点击语义（与消息页同一条）
 *
 * 三级 IA 之下，**整张卡与行尾按钮不再是同一件事**（issue 6）：
 *
 * - 点**行**（卡身）→ 进**工作区操作中心**（二级，`onOpenWorkspace` →
 *   `nav.openAgentWorkspaceDetail`）：直通码 / 权限管理 / 最近会话入口；
 * - 行尾那颗聊天按钮（`WorkspaceUnitCard.onOpenChat`）→ 进聊天（"我要跟它说话"）。
 *   没有绑定协同群时，两条壳都是**针对这一行的工作区**提示建群并进入（issue 7），
 *   不再弹「所有工作区」列表（那是独立入口「协同群」的事）；
 * - 待审批由 `AgentApprovalsSection` 逐条画（处置就在卡里）；
 *   `onOpenSession` 仍是工作区行 / 未归属行进**三级会话详情**的路径。
 *
 * `onSelectAgent` 只在**有会话要选中**时才用：进聊天页后聊天页要停在那条会话上。
 * 它是「选中哪个 agent」，不是「切换视图」。
 */
/**
 * 工作区页签的顶栏：**手机壳专用**，与消息 / 文件柜 / 节点 / 设置共用 [LargeTitleBar]。
 *
 * 桌面壳不画这一条：壳顶部已有窗口标题栏 + 工具栏，内容区再叠一条就是双层 chrome；
 * 桌面各 tab 一律传 `showHeader = false`，由 [AgentConsoleScreen] 的调用条件挡住。
 * 手机单列靠它撑起五个首页 tab 共用的顶栏（真机像素复测口径，见
 * `TabChromeConsistencyGuardTest`）。
 *
 * 此前这一行画在 [AgentConsoleScreen] 带横向 padding 的滚动内容列里，标题直接
 * 铺在 `background` 上 —— 浅色只差 #FFFFFF→#FAFAF8，深色却差 #232625→#121413
 * 一整条带；而且它会跟着内容滚走、没有 hairline。原型 m11-agents 的 `.topbar`
 * 用 `--surface`，下面的内容才透出 `--bg`。现在把顶栏提到滚动容器**外面**，
 * 底色铺满整幅、内容按 [ContentWidths.Pane] 收列。
 *
 * 副标题口径仍在派生层（[inboxWorkspaceUnits] / [pendingApprovalCount]），
 * 与看板正文的计数是同一把尺子，不在顶栏另算一套。
 */
@Composable
internal fun WorkspaceBoardTitleBar(
    now: Long,
    onRefresh: (() -> Unit)?,
    onCreateWorkspaceGroup: (() -> Unit)?,
    agentSlice: AgentSlice,
    nodeSlice: NodeSlice,
    settingsSlice: SettingsSlice,
) {
    val units = inboxWorkspaceUnits(agentSlice, nodeSlice, settingsSlice, limit = Int.MAX_VALUE)
    val totalPending = pendingApprovalCount(agentSlice.agentApprovals.values, now)
    // 页头汇总（原型 relay.html:4208-4211 的 `sub`）：四个数总是印，
    // 待审批只在真的有的时候接在后面。设备 = groups.size；Agent = 各设备
    // agentSections().size 之和（不是 group.agentCount，真机报过「0 个 Agent」
    // 而下面明明画着 agent 段）；工作区 = units.workspaceCount（排除未归属行）。
    val deviceTotal = units.groups.size
    // Agent 数与空态共用**同一把尺子**（卡片流投影）：只数有证据（会话 / 目录 / 席位）
    // 的实体。修复前这里数 `agentSections()`（只含带目录的），而空态数 `agentPeers.size`
    // （恒含本机宿主），于是同一屏上「0 Agent」与「Agent（1）」同时出现。
    val agentTotal = hubAgents(agentSlice, nodeSlice, settingsSlice, now).size
    val summary = if (units.isEmpty) {
        stringResource(Res.string.agent_empty_subtitle, units.workspaceCount)
    } else {
        stringResource(
            Res.string.workspace_board_subtitle,
            deviceTotal,
            agentTotal,
            units.workspaceCount,
        )
    }
    LargeTitleBar(
        title = stringResource(Res.string.agent_title),
        subtitle = if (totalPending > 0) {
            summary + " · " + stringResource(Res.string.agent_subtitle_pending, totalPending)
        } else {
            summary
        },
        contentMaxWidth = ContentWidths.Pane,
        actions = {
            onCreateWorkspaceGroup?.let { createWs ->
                // 原型顶栏这颗是 `.btn sm pri`（relay.html:6314）：顶栏密排位置。
                AppButton(
                    text = stringResource(Res.string.workspace_board_action_create),
                    onClick = createWs,
                    size = AppButtonSize.Small,
                    leadingIcon = Res.drawable.ic_plus,
                )
            }
            if (onRefresh != null) {
                TopBarIconAction(
                    icon = Res.drawable.ic_refresh,
                    contentDescription = stringResource(Res.string.desktop_action_refresh),
                    onClick = onRefresh,
                )
            }
        },
    )
    TitleBarDivider()
}

@Composable
internal fun WorkspaceBoardView(
    agents: List<PeerUi>,
    selectedAgent: PeerUi?,
    now: Long,
    onSelectAgent: (String) -> Unit,
    onOpenWorkspace: (peerId: String, wsId: String?, sessionId: String?) -> Unit,
    /**
     * 点一行进聊天页。`wsId` 非空 = 用户点的是**某个工作区**，聊天页要收窄到它
     * （与消息页「工作区」段的 `onOpenWorkspaceUnit` 同一语义）。
     *
     * ## 为什么形参必须带 wsId
     *
     * 传递目标工作区 ID，以便聊天页精准按工作区收窄会话。
     */
    onOpenChat: (peerId: String, wsId: String?) -> Unit,
    onFindNodes: (() -> Unit)?,
    onRefresh: (() -> Unit)?,
    onRespondApproval: ((approvalId: String, allow: Boolean) -> Unit)?,
    /** 三参处置（「本会话免审」）：null 时退回 [onRespondApproval]，不画免审按钮。 */
    onRespondChoice: ((approvalId: String, decision: AgentApprovalDecision) -> Unit)? = null,
    /**
     * 看板要不要画那一段逐条审批卡。桌面 Wide + Agents 时右侧 inspector 已经常驻
     * 同一批卡，壳会置 false —— 左右两栏各画一遍一模一样的审批卡不是冗余备份，
     * 是重复渲染。默认 true：手机 / 单栏 / 预览帧保持原形态。
     *
     * 置顶的 [PinnedApprovalFocusCard] 不跟着关：它是「N 项待批 → 进聊天」的直达口，
     * 不是审批卡列表，inspector 里没有对应物，关掉等于平白丢一条通路。
     */
    showApprovalQueue: Boolean = true,
    /**
     * 手机壳：置顶审批卡点「立即审批」时**原地**弹出处置面板，而不是把人甩进聊天页
     * （UX 规范 §2.2②「迷宫横跳」）。
     *
     * 默认 false —— 桌面宽屏的右栏 inspector 已经常驻同一批审批卡，再叠一层面板
     * 是重复渲染；手机组合根显式置 true。面板本体复用 [AgentApprovalsSection]，
     * 逐条处置仍只有一份实现。
     */
    inPlaceApprovals: Boolean = false,
    /**
     * 新手冷启动引导卡（UX 规范 §2.6）的出口：点 CTA → 壳去开「精简建群弹窗」。
     *
     * null = 这个壳没有建群入口（或没有对话框可用）⇒ **整张卡不画**。与
     * `onOpenShareRoot` 同一条规矩：能力缺失 = 不画，不是"点了没反应"。
     */
    onBindFirstProject: ((WorkspaceUi) -> Unit)? = null,
    /** Phase 4 订阅面切片（蓝图 §4.4）：默认值由 [state] 投影，未迁移/预览路径行为不变。 */
    agentSlice: AgentSlice,
    nodeSlice: NodeSlice,
    settingsSlice: SettingsSlice,
) {
    // 与消息页同一份派生（排序 / 上限 / 待审批 join / 降级都在里面做完）。
    // **这一屏是清单页，不设上限**。
    //
    // 用 `Int.MAX_VALUE` 展示全量工作区列表。
    // 进入这一屏时**自动向节点要一次最新状态**。
    //
    // ## key 为什么是 Unit 而不是 onRefresh
    //
    // 使用 Unit 作为 key，确保进入本屏时仅触发一次初始刷新，避免重组触发重复网络请求。
    val refresh = rememberUpdatedState(onRefresh)
    LaunchedEffect(Unit) { refresh.value?.invoke() }
    val units = inboxWorkspaceUnits(agentSlice, nodeSlice, settingsSlice, limit = Int.MAX_VALUE)
    // 页头的「N 项待审批」与审批段同一把尺子：领域层的 pendingApprovalCount
    // （pending 且未过期）。分段的 pill、会话切换条、选择器也走它 —— 一个数只有一个来源。
    val totalPending = pendingApprovalCount(agentSlice.agentApprovals.values, now)

    // 页头已提到 [WorkspaceBoardTitleBar]：它必须画在滚动容器之外、铺满整幅宽的
    // `surface` 面上。这里只剩看板正文，滚动时标题栏不再跟着走。

    // 空态判据用**派生**的结果而不是 agents.isEmpty：一个 ag1 节点都没有时两者同真，
    // 而「有 agent 但一条会话、一个工作区都没宣告过」时派生仍会给出一行降级行
    // （inboxWorkspaceUnits() 的「未上报类型」），那条路不该被空态挡掉。
    //
    // **但判据不能是 `!units.isEmpty`**：派生层在「什么都没宣告、一条帧都没收到」时
    // 造的那行是**空壳**（wsTitle=null / sessionCount=0 / active=null），它在界面上就是一张
    // 没有主体的卡 —— 空态因此被挡掉，用户看到的是「工作区页面空白，没有空屏效果」。
    // 用 [InboxWorkspaceUnitsUi.hasVisibleContent]：只有真有内容时才不画空态。
    //
    // 注：agents / selectedAgent 两个形参留给**桌面壳**（它仍走二级下钻），手机侧这条
    // 路径不用它们 —— 见 AgentConsoleScreen 的 isViewingDetail 分支。
    if (!units.hasVisibleContent) {
        // task-10：切过来的头几秒先别下「还没有任何 Agent 上报」这个结论 —— 节点还没起来、
        // 或一个对端都还没发现时，那不是**空**，是**还没到**。分档规则抽成纯函数
        // [boardEmptyStage]，单测才钉得住"加载态与真·空态有区分"。
        //
        // 为什么不用「等 N 秒就转空态」：mDNS 是持续发现，没有"发现完成"这个事件；
        // 拿一个计时器猜，等于用一个假事件去替一个真状态。
        val stage = boardEmptyStage(
            nodeRunning = nodeSlice.network.isRunning,
            scanning = nodeSlice.scanProgress != null,
            peerCount = nodeSlice.peers.size,
            hasFailure = nodeSlice.network.failure != null,
        )
        val scanning = nodeSlice.scanProgress
        if (stage == BoardEmptyStage.Scanning && scanning != null) {
            AgentLoadingState(progress = scanning, modifier = Modifier.fillMaxWidth())
        } else if (stage == BoardEmptyStage.NodeFailed) {
            // 启动**失败**与"还在启动"是两件事：失败时继续转圈，用户只会以为 App 卡了。
            // 原因文案复用会话页那份（bannerText 的同一句话），并给一条出路。
            ConversationFailedEmptyState(
                failure = nodeSlice.network.failure,
                actionLabel = stringResource(Res.string.agent_empty_exit_title),
                onAction = {
                    onFindNodes?.invoke()
                    onRefresh?.invoke()
                },
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (stage == BoardEmptyStage.NodeStarting || stage == BoardEmptyStage.Discovering) {
            val starting = stage == BoardEmptyStage.NodeStarting
            AgentBoardWaitingState(
                title = stringResource(
                    if (starting) Res.string.workspace_board_starting else Res.string.workspace_board_discovering
                ),
                hint = stringResource(
                    if (starting) Res.string.workspace_board_starting_hint else Res.string.workspace_board_discovering_hint
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            // 空态照原型 relay.html:4563-4579 screenAgentsEmpty 的四段形状画：
            // 警告条（.banner warn = secondaryContainer，原型 :33 注释自己把
            // --queued-c 对到 onSecondaryContainer）→「Agent（0）」段头（.grphead）
            // → 卡内说明（.card .empty）→「去看看节点」行（.krow）。
            //
            // 为什么不走 EmptyState：那是「标题 + 一句说明 + 一颗按钮」的通用形状，
            // 而这一屏原型是四段；而且文案里不许出现 wire 键（原型 relay.html:4562
            // 写死「不印 `ag1` 这种 wire 键」，此前代码恰好印了）。
            // 「一个 agent 都没上报」与「上报了 agent、但还没有可显示的工作区/会话」是两句
            // 不同的话。修复前两者共用后者文案 + 写死的「Agent（0）」：设备明明报了 agent，
            // 屏上却说"还没有任何 Agent 上报" —— 那是把"我这边没收到内容"说成"对方没装"。
            // 「有没有 Agent 上报」的**唯一尺子** = 卡片流投影（hubAgents）。
            //
            // 修复前这里是 `agents.isNotEmpty()`，而 `agents` 是 `agentPeers` —— 它会把
            // **本机宿主**追加进去（只要 `agentWorkspaces` 里有本机的键，哪怕那是一条
            // 没宣告任何目录的空壳）。于是同一屏上三句话打架：页头「0 Agent」、
            // 段头「Agent（1）」、卡里「还没有设备上报 Agent」。
            //
            // hubAgents 只收有证据（会话 / 目录 / 席位）的实体，没有证据的本机节点不进。
            // 页头与空态从此共用这一把尺子，三个数不可能再各说各话。
            val hubAgentCount = remember(agentSlice, nodeSlice, settingsSlice, now) {
                hubAgents(agentSlice, nodeSlice, settingsSlice, now).size
            }
            val anyAgentReported = hubAgentCount > 0
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.lg14),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(
                            horizontal = LocalAppSpacing.current.md,
                            vertical = LocalAppSpacing.current.sm6,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm6),
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_warn),
                            contentDescription = null,
                            modifier = Modifier.size(IconSizes.Label),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Column {
                            Text(
                                text = stringResource(
                                    if (anyAgentReported) Res.string.agent_empty_agents_only_title
                                    else Res.string.agent_empty_title
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Text(
                                text = stringResource(
                                    if (anyAgentReported) Res.string.agent_empty_agents_only_message
                                    else Res.string.agent_empty_message
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
                Text(
                    // 段头的数字取真实值：写死「Agent（0）」会在"报了 2 个 agent 但没有
                    // 可见工作区"时与上方那句同时撒谎。
                    text = if (anyAgentReported) {
                        stringResource(Res.string.agent_empty_section_n, hubAgentCount)
                    } else {
                        stringResource(Res.string.agent_empty_section)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Card(
                    shape = LocalCardShape.current,
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .relayCardShadow(),
                ) {
                    Text(
                        // 卡内说明必须跟上面的判据一起翻档。修复前它恒是「还没有设备上报
                        // Agent」—— 于是横幅说「已收到 Agent」、卡里说「没有设备上报」，
                        // 同一块内容自己反驳自己。
                        text = stringResource(
                            if (anyAgentReported) {
                                Res.string.agent_empty_agents_only_card
                            } else {
                                Res.string.agent_empty_card
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(LocalAppSpacing.current.lg),
                    )
                }
                if (onFindNodes != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onFindNodes)
                            .padding(horizontal = LocalAppSpacing.current.md, vertical = LocalAppSpacing.current.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md),
                    ) {
                        // .krow .av：32dp 圆角方块 + surface-2 底 + primary 图标（原型 :4574）。
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.size(32.dp),
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(
                                    painter = painterResource(Res.drawable.ic_lan_search),
                                    contentDescription = null,
                                    modifier = Modifier.size(IconSizes.Label),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(Res.string.agent_empty_exit_title),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = stringResource(Res.string.agent_empty_exit_body),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            painter = painterResource(Res.drawable.ic_chev),
                            contentDescription = null,
                            modifier = Modifier.size(IconSizes.Label),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        return
    }

    // 新手冷启动引导（UX 规范 §2.6）：**已经有目录、但一个都还没绑群** —— 屏幕列着几个
    // 目录名，用户不知道下一步是建群。规范原文写的是「未绑定任何项目群」，但照字面做成
    // "空态替代品"会得到一颗死按钮：空态成立时一个目录都没有（判据是 hasVisibleContent，
    // 而一条已宣告的目录本身就让它成立），「立即关联第一个项目」无目录可绑。
    // 判据与三条 fail-closed 都在 [firstProjectCandidate] 里，这里只画。
    val firstProject = remember(agentSlice, nodeSlice, settingsSlice) {
        firstProjectCandidate(agentSlice, nodeSlice, settingsSlice)
    }
    val bindFirstProject = onBindFirstProject
    if (firstProject != null && bindFirstProject != null) {
        FirstProjectCard(
            first = firstProject,
            onBind = bindFirstProject,
            modifier = Modifier.padding(bottom = LocalAppSpacing.current.lg14),
        )
    }

    // 待处理审批（原型 relay.html 的 `.grphead`「待处理审批 N 项」+ 逐条卡）。
    //
    // **喂给审批段的是「待批 + 本屏刚处置的」**，不是只喂 pending：审批段自己的
    // 「处置后保留 2 秒」（`AgentApprovalsSection` 里的 `seenPending` 记账）要先拿到
    // 已决行才生效 —— 在调用方先把非 pending 滤掉，那段留痕就是死代码，用户点完
    // 批准卡立刻消失，与「它被谁删了」无法区分（issue「处置留痕 2 秒」）。
    // 过期的不画、也不计数（`pendingApprovalCount` 同一判据）。
    val approvalRows = rememberLingeringApprovalRows(agentSlice.agentApprovals.values, now)

    // 原位处置面板的开关（手机壳才可能开；见形参 `inPlaceApprovals` 的 KDoc）。
    var approvalSheetOpen by remember { mutableStateOf(false) }

    // 归属行：设备 · 项目目录。看板与面板共用**同一份**派生 —— 两处各写一遍迟早
    // 有一处忘了跟会话表更新。审批段的归属、置顶卡的群名都从这里取。
    val approvalAttribution: (AgentApprovalUi) -> String? = { appr ->
        val session = agentSlice.agentSessions[AgentSessionKey(appr.peerId, appr.sessionId)]
        val agentKey = session?.let { it.agentId ?: it.agentKind }
        val wsTitle = session?.wsId?.let { ws ->
            agentSlice.agentWorkspaces.values.firstOrNull { it.wsId == ws }?.wsTitle
        }
        val device = peer(appr.peerId, nodeSlice, settingsSlice)?.displayName
        listOfNotNull(device, wsTitle ?: agentKey)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
            .takeIf { it.isNotBlank() }
    }

    // 待审批**置顶聚焦卡**（task-20；原型 screenAgents 置顶条 relay.html:4692-4701）：
    // 聚合事实 + 立即审批直达。计数用页头同一把尺子（上方 totalPending = 领域层
    // pendingApprovalCount），不在这里数第二遍；这里只做聚焦不处置 —— 不画第二套
    // 批准/拒绝按钮。
    //
    // **副标必须是真实事实**：修复前这里是一句写死的「涉及 Git 远程推送与敏感文件修改」——
    // 真实的待批项可能只是 `rmdir /tmp/cache`，那句话在编造用户没做过的事。现在优先报
    // 「群名 · 命令/摘要」；归因不了（直通宿主私聊、或目录没绑群）才退回通用文案。
    //
    // 「立即审批」的去向分两档：手机壳原地弹处置面板（§2.2②，不再把人甩进聊天页）；
    // 桌面壳保持原型 4701 行的语义（选中待批项所在宿主并进聊天），因为那边右栏
    // inspector 已经常驻审批列表，跳转只是换焦点、不是换屏。
    // totalPending 与 lingering 行的 pending 判据同源（isPendingApproval = pending
    // 且未过期），所以 totalPending > 0 时下面的 firstOrNull 必命中。
    val focusApproval = approvalRows.firstOrNull { it.status == "pending" }
    val focusPeerId = focusApproval?.peerId
    if (totalPending > 0 && focusPeerId != null) {
        val focusGroupName = focusApproval?.let { appr ->
            agentSlice.agentSessions[AgentSessionKey(appr.peerId, appr.sessionId)]
                ?.wsId
                ?.let { ws -> groupBoundToWorkspace(ws, settingsSlice.settings.groups)?.name }
        }
        val focusCommand = focusApproval?.let { appr ->
            appr.summary?.takeIf { it.isNotBlank() } ?: appr.tool?.takeIf { it.isNotBlank() }
        }
        val focusSubtitle = if (focusGroupName != null && focusCommand != null) {
            stringResource(Res.string.approval_focus_subtitle_detail, focusGroupName, focusCommand)
        } else {
            stringResource(Res.string.approval_focus_subtitle)
        }
        PinnedApprovalFocusCard(
            pendingCount = totalPending,
            subtitle = focusSubtitle,
            onApproveClick = {
                if (inPlaceApprovals) {
                    approvalSheetOpen = true
                } else {
                    onSelectAgent(focusPeerId)
                    onOpenChat(focusPeerId, null)
                }
            },
            modifier = Modifier.padding(bottom = LocalAppSpacing.current.lg14),
        )
    }
    // 面板打开时主屏这段队列暂时不画：同一个屏幕上不出现两份一模一样的审批列表
    // （与 §2.2① 桌面两栏去重同一条理由）。面板关掉立刻回来。
    if (approvalRows.isNotEmpty() && showApprovalQueue && !approvalSheetOpen) {
        AgentApprovalsSection(
            approvals = approvalRows,
            onRespond = onRespondApproval,
            onRespondChoice = onRespondChoice,
            // 审批权只有一处判据（:core 的 canApproveAgentRequest，经 RelayUiState.mayDecideApproval）：
            // 修复前只挡 RO，于是 ADMIN 未开委托审批、乃至没有授权记录的人也能点「批准」，
            // 而同一个用户在会话详情里看到的却是「只读」。
            isReadOnly = { appr -> !mayDecideApproval(appr, agentSlice, nodeSlice, settingsSlice, now) },
            // 跨设备/跨工作区的平铺清单必须有归属：否则两台设备同时报审批时只能靠 summary 猜。
            // 与置顶卡、面板共用同一份派生（`approvalAttribution`）。
            attribution = approvalAttribution,
        )
    }
    if (approvalSheetOpen) {
        AgentApprovalSheet(
            approvals = approvalRows,
            onRespond = onRespondApproval,
            onRespondChoice = onRespondChoice,
            // 权限判据不在这里另写一份：与主屏队列、聊天横幅同一处（:core 的唯一判据）。
            isReadOnly = { appr -> !mayDecideApproval(appr, agentSlice, nodeSlice, settingsSlice, now) },
            attribution = approvalAttribution,
            onDismiss = { approvalSheetOpen = false },
        )
    }

    // 分三层画：**节点 → Agent（只含带工作区的）→ 工作区行**，末尾一行「未归属会话」
    Column(verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.lg14)) {
        units.groups.forEach { group ->
            // 组头只在**多于一台设备**时画（原型 relay.html:4247 `groups.length > 1`）：
            // 只有一台设备时那一行只是把设备名重复一遍。
            if (units.groups.size > 1) {
                BoardGroupHead(group, now)
            }
            Column(verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md)) {
                group.agentSections().forEach { section ->
                    AgentSectionHead(section)
                    section.workspaces.forEach { unit ->
                        InboxUnitRow(
                            unit = unit,
                            online = group.online,
                            now = now,
                            onOpenUnit = {
                                onSelectAgent(unit.peerId)
                                onOpenWorkspace(unit.peerId, unit.wsId, null)
                            },
                            onOpenChat = {
                                onSelectAgent(unit.peerId)
                                onOpenChat(unit.peerId, unit.wsId)
                            },
                        )
                    }
                }

                // 「未归属会话」：一条工作区都没报的那些会话合起来画一行（父稿 §2.5）。
                // 它排在**最后** —— 规范：未归属/未命名桶永远排最后。
                val unassigned = group.unassignedSessions()
                if (!unassigned.isEmpty) {
                    UnassignedSessionsRow(
                        count = unassigned.sessionCount,
                        onOpenChat = unassigned.peerId?.let { peerId ->
                            {
                                onSelectAgent(peerId)
                                onOpenWorkspace(peerId, null, unassigned.active?.sessionId)
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * Agent 段头：一枚小小的身份行（身份键 + 可选待审批）。
 *
 * ## 为什么不再挂「N 个会话」（issue 6，用户裁决）
 *
 * 会话数是**实现细节**：同一件事在段头、组头、页头、卡片底栏各印一遍，屏上一半的字
 * 都在数数（用户反馈「工作区行/组头全是数字」）。工作区行自己会用「活跃会话」
 * 说出真正要紧的那条，段头只需要回答"这是谁的"。
 *
 * 它不是可点的对象：点它没有比"点它名下的工作区行"更明确的落点 ——
 * 而「打开聊天」这件事在每一行都有自己的按钮。
 */
@Composable
private fun AgentSectionHead(section: WorkspaceAgentSectionUi) {
    val sp = LocalAppSpacing.current
    Row(
        // Agent 这一层是**排版层级**，不是容器层级：它夹在设备卡与工作区卡之间，
        // 再套一张卡会让"设备 > Agent > 工作区"读成三层并列的盒子。
        // 缩进对齐卡内边距（sp.lg），让这行字看起来属于上面那张设备卡。
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = sp.lg, end = sp.lg, top = sp.lg14, bottom = sp.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(sp.sm6),
    ) {
        Text(
            text = section.agentKey ?: stringResource(Res.string.conv_inbox_ws_badge_unknown_kind),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (section.pendingCount > 0) {
            Text(
                text = stringResource(Res.string.workspace_agent_section_pending, section.pendingCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 1,
            )
        }
    }
}


/**
 * 板内的组头 = 一台设备。
 *
 * 与消息页 `InboxGroupHead` 同形，但计数按本屏画出来的行：
 * **只数带 `wsId` 的行**（未归属会话并成了一行，不参与计数 —— 父稿 §2.2）。
 */
@Composable
private fun BoardGroupHead(group: com.kapp.tenon.domain.WorkspaceUnitGroupUi, now: Long) {
    val sp = LocalAppSpacing.current
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    // 计数已按 issue 6 的裁决整体移除（组头不再印「N 个 Agent · M 个工作区」）：
    // 组头要回答的是"这些是谁的"，数量是段/页层面的事，不该在每一台设备的抬头再数一遍。
    val meta = buildString {
        // 离线时间与"归不到 Agent 的待审批"都保留：它们不是数量噪音，而是
        // 组头必须替那些行说出来的状态（join 不到 Agent 的审批只能写在节点级）。
        if (!group.online && group.lastSeenAt > 0L) {
            append(stringResource(Res.string.conv_inbox_ws_group_offline, relativeTime(group.lastSeenAt, now)))
        }
        if (group.unattributedApprovals > 0) {
            if (isNotEmpty()) append(" · ")
            append(stringResource(Res.string.conv_inbox_ws_pending, group.unattributedApprovals))
        }
    }
    // **同一套容器语言**：与会话行 / 工作区行 / 节点行一样，是"白卡 + LocalCardShape + 自绘投影"。
    // 之前这里是一条裸文本行 —— 夹在一排卡片中间，看起来像外壳没画完。
    // 层次感由**排版**承担（设备名 titleSmall/SemiBold，状态行 labelSmall），不靠另起一种盒子。
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = LocalCardShape.current,
        modifier = Modifier
            .fillMaxWidth()
            .relayCardShadow(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = sp.lg, vertical = sp.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(sp.md),
        ) {
            StatusDot(group.online)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    // 只印设备名：平台（linux/macOS）是排障信息，不是设备对用户的名字。
                    text = group.nodeName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * 工作区页的「还没到」态（task-10）：与「真·空」态分开。
 *
 * 空态是一个**结论**（还没有任何 Agent 上报），它带着「去看看节点」那条出路；而这一档是
 * **过渡**：节点还没起来，或一个对端都还没发现 —— 这时候下结论会让用户以为"这功能是空的"，
 * 而他其实只等了半秒。所以这里只给转圈 + 一句说明，不给那条出路（还没有证据说节点页有问题）。
 */
@Composable
private fun AgentBoardWaitingState(
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(LocalAppSpacing.current.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md),
        ) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp),
                color = MaterialTheme.colorScheme.primary,
            )
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 工作区页「一行都没有」时该画哪一档（task-10）。
 *
 * 抽成纯函数是为了可测：这条规则的全部内容就是"什么时候还不到下结论的时候"，
 * 而它此前埋在一个 `if (scanning != null)` 里，没有任何测试盯得住。
 */
internal enum class BoardEmptyStage {
    /** 正在扫描网段（有进度可报）。 */
    Scanning,

    /** 本机节点**起不来**（有失败原因）—— 不是"再等等"，要如实报原因并给出路。 */
    NodeFailed,

    /** 本机节点还没起来 —— 数据不可能到，等它。 */
    NodeStarting,

    /** 节点在跑，但一个对端都还没发现 —— 这是"网络上还没有人"，不是"没有工作区"。 */
    Discovering,

    /** 节点在跑、也发现过对端，只是没有一个 agent/工作区 —— 这才是真·空。 */
    TrulyEmpty,
}

internal fun boardEmptyStage(
    nodeRunning: Boolean,
    scanning: Boolean,
    peerCount: Int,
    /**
     * 本机节点有没有一条**已记录**的启动失败原因（`NetworkUi.failure`）。
     *
     * 修复前只有三档：失败与"还在启动"共用「节点还没启动」+ 无限转圈那一档，
     * 用户永远等不到下结论。有原因就必须分开 —— 那一档要报原因 + 给出路。
     */
    hasFailure: Boolean = false,
): BoardEmptyStage = when {
    scanning -> BoardEmptyStage.Scanning
    !nodeRunning && hasFailure -> BoardEmptyStage.NodeFailed
    !nodeRunning -> BoardEmptyStage.NodeStarting
    peerCount == 0 -> BoardEmptyStage.Discovering
    else -> BoardEmptyStage.TrulyEmpty
}

/**
 * 审批段这一轮要画的行（**主屏口径**）：待处置的 + 本屏刚处置、还在留痕窗口里的。
 *
 * ## 为什么主屏不能只喂 pending
 *
 * `AgentApprovalsSection` 的「处置后保留 2 秒」靠它自己的 `seenPending` 记账：
 * 一条审批得先以 pending 进来过，变成已决之后才会被它计时、到点收起。调用方若在
 * 外面先按 `status == "pending"` 过滤，已决行永远进不去，那段留痕就是**死代码** ——
 * 用户点完批准卡立刻消失，是「点上了吗」还是「被谁删了」无法区分。
 *
 * ## 为什么不是把整张表都喂进去
 *
 * `seenPending` 只替**本屏见过它 pending** 的条目计时；磁盘里恢复回来的历史已决项
 * 喂进看板会**永远**留在清单上（常驻历史是会话详情的行为，不是清单页的）。所以
 * [lingerIds] 就是「本屏见过 pending 的 id 集合」，已决行只有在里面才留；2 秒后由
 * `AgentApprovalsSection` 自己收起（它仍是驻留时长的唯一主人）。
 *
 * [lingerIds] 是可变集合，结果每次组合现算（审批条数个位数，开销可忽略）。
 */
internal fun approvalRowsFor(
    approvals: Collection<AgentApprovalUi>,
    now: Long,
    lingerIds: Set<String>,
    peerId: String? = null,
    sessionIds: Set<String>? = null,
): List<AgentApprovalUi> = visibleApprovalRows(
    approvals = approvals,
    now = now,
    lingerDismissed = emptySet(),
    includeExpiredPending = false,
    peerId = peerId,
    sessionIds = sessionIds,
    lingerIds = lingerIds,
)

/**
 * [approvalRowsFor] 的组合期包装：顺手把「本屏见过它 pending」记进 `lingerIds`。
 *
 * 记账用普通 `MutableSet`（不是 snapshot 状态）：它只在组合期写读，写入**不改变
 * 本轮结果**（本轮读到的"已决"一定来自上一轮的记账），真正的重组由
 * `agentSlice.agentApprovals` 变化驱动。
 */
@Composable
internal fun rememberLingeringApprovalRows(
    approvals: Collection<AgentApprovalUi>,
    now: Long,
    peerId: String? = null,
    sessionIds: Set<String>? = null,
): List<AgentApprovalUi> {
    val lingerIds = remember { mutableSetOf<String>() }
    approvals.forEach { approval ->
        if (approval.status == "pending") lingerIds.add(approval.approvalId)
    }
    return approvalRowsFor(approvals, now, lingerIds, peerId, sessionIds)
}
