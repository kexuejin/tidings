// SPDX-License-Identifier: GPL-3.0-only

package app.relay.feature.agents

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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.relay.app.RelayUiState
import app.relay.app.WorkspaceAgentSectionUi
import app.relay.app.agentSections
import app.relay.app.inboxWorkspaceUnits
import app.relay.feature.conversations.InboxUnitRow
import app.relay.generated.resources.Res
import app.relay.generated.resources.agent_empty_card
import app.relay.generated.resources.agent_empty_exit_body
import app.relay.generated.resources.agent_empty_exit_title
import app.relay.generated.resources.agent_empty_message
import app.relay.generated.resources.agent_empty_section
import app.relay.generated.resources.agent_empty_subtitle
import app.relay.generated.resources.agent_empty_title
import app.relay.generated.resources.agent_subtitle_pending
import app.relay.generated.resources.agent_title
import app.relay.generated.resources.desktop_action_refresh
import app.relay.generated.resources.ic_chev
import app.relay.generated.resources.ic_lan_search
import app.relay.generated.resources.ic_refresh
import app.relay.generated.resources.ic_warn
import app.relay.generated.resources.workspace_board_subtitle
import app.relay.ui.theme.LocalAppSpacing
import app.relay.ui.theme.LocalCardShape
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import app.relay.generated.resources.conv_inbox_ws_badge_unknown_kind
import app.relay.generated.resources.workspace_agent_section_pending
import app.relay.generated.resources.workspace_agent_section_sessions
import app.relay.ui.theme.IconSizes
import app.relay.feature.conversations.UnassignedSessionsRow
import app.relay.app.unassignedSessions
import app.relay.generated.resources.conv_inbox_ws_group_counts
import app.relay.generated.resources.conv_inbox_ws_group_offline
import app.relay.generated.resources.conv_inbox_ws_pending
import app.relay.ui.components.StatusDot
import app.relay.ui.components.TopBarIconAction
import app.relay.ui.components.relativeTime
import app.relay.ui.theme.accents
import app.relay.app.model.PeerUi
import app.relay.ui.theme.relayCardShadow

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
 * - 点**行** → 进聊天页（`onOpenChat(nodeId)`）。这是「我要跟它说话」；
 * - 行尾那颗聊天按钮是同一件事（`WorkspaceUnitCard.onOpenChat`）—— 同一行点两处去同一地；
 * - 待审批由 `AgentApprovalsSection` 逐条画（处置就在卡里）；
 *   `onOpenSession` 仍是工作区行 / 未归属行进会话的路径；
 * - `m-workspacedetail` 那屏代码侧还没有落点（已登记为偏差），所以这里不编一个到不了的位置。
 *
 * `onSelectAgent` 只在**有会话要选中**时才用：进聊天页后聊天页要停在那条会话上。
 * 它是「选中哪个 agent」，不是「切换视图」。
 */
@Composable
internal fun WorkspaceBoardView(
    state: RelayUiState,
    agents: List<PeerUi>,
    selectedAgent: PeerUi?,
    showHeader: Boolean,
    now: Long,
    onSelectAgent: (String) -> Unit,
    onOpenSession: (peerId: String, sessionId: String) -> Unit,
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
    val units = state.inboxWorkspaceUnits(limit = Int.MAX_VALUE)
    val totalPending = state.agentApprovals.values.count { it.status == "pending" }

    if (showHeader) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = LocalAppSpacing.current.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.agent_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                // 页头汇总（原型 relay.html:4208-4211 的 `sub`）：四个数**总是**印，
                // 待审批只在真的有的时候接在后面。此前只在待批时印后半句，于是屏上
                // 既看不到「几台设备 / 几个 Agent / 几个工作区 / 几个会话」，也无法
                // 判断「0 个工作区」到底是真没有还是没加载。
                //
                // 口径与组头一致（一把尺子）：设备 = groups.size；Agent = 各设备
                // agentSections().size 之和（**不是** group.agentCount —— 理由见 BoardGroupHead：
                // 真机上 group.agentCount 报过「0 个 Agent」而下面明明画着 agent 段）；
                // 工作区 = units.workspaceCount（排除未归属行，父稿 §2.2）；
                // 会话 = 全部 units 的 sessionCount 之和（未归属会话也是会话）。
                val deviceTotal = units.groups.size
                val agentTotal = units.groups.sumOf { group -> group.agentSections().size }
                val sessionTotal = units.groups.sumOf { group -> group.units.sumOf { it.sessionCount } }
                // 空屏只印「0 个工作区」（原型 screenAgentsEmpty relay.html:4564 写死这一句）：
                // 四个零排一行是噪音，而「一个都没上报」这件事本身已经由下面的警告条说清了。
                val summary = if (units.isEmpty) {
                    stringResource(Res.string.agent_empty_subtitle, units.workspaceCount)
                } else {
                    stringResource(
                        Res.string.workspace_board_subtitle,
                        deviceTotal,
                        agentTotal,
                        units.workspaceCount,
                        sessionTotal,
                    )
                }
                Text(
                    text = if (totalPending > 0) {
                        summary + " · " + stringResource(Res.string.agent_subtitle_pending, totalPending)
                    } else {
                        summary
                    },
                    // 顶栏副标题 = 原型 `.topbar .sub`（prototype/relay.html:578-579）：
                    // 11px（= labelSmall）、on-2（= onSurfaceVariant）、margin-top 2px、
                    // **nowrap + 省略号**。此前写 bodyMedium（14sp）且不设 maxLines，
                    // 于是这一行在 393dp 下折成两行（实测副标题墨迹占 y134..220 两行），
                    // 而原型永远一行（:578 的 white-space:nowrap + text-overflow:ellipsis）。
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = LocalAppSpacing.current.xxs),
                )
            }
            if (onRefresh != null) {
                // 原型这颗是 .ibtn（浅色圆角方块 + --on 图标），不是裸图标、更不是 primary：
                // 原型截图 m11-agents.png 顶栏右侧就是一颗 #F0EFE9 的 34×34 方块。
                TopBarIconAction(
                    icon = Res.drawable.ic_refresh,
                    contentDescription = stringResource(Res.string.desktop_action_refresh),
                    onClick = onRefresh,
                )
            }
        }
    }

    // 空态判据用**派生**的结果而不是 agents.isEmpty：一个 ag1 节点都没有时两者同真，
    // 而「有 agent 但一条会话、一个工作区都没宣告过」时派生仍会给出一行降级行
    // （inboxWorkspaceUnits() 的「未上报类型」），那条路不该被空态挡掉。
    //
    // 注：agents / selectedAgent 两个形参留给**桌面壳**（它仍走二级下钻），手机侧这条
    // 路径不用它们 —— 见 AgentConsoleScreen 的 isViewingDetail 分支。
    if (units.isEmpty) {
        val scanning = state.scanProgress
        if (scanning != null) {
            AgentLoadingState(progress = scanning, modifier = Modifier.fillMaxWidth())
        } else {
            // 空态照原型 relay.html:4563-4579 screenAgentsEmpty 的四段形状画：
            // 警告条（.banner warn = secondaryContainer，原型 :33 注释自己把
            // --queued-c 对到 onSecondaryContainer）→「Agent（0）」段头（.grphead）
            // → 卡内说明（.card .empty）→「去看看节点」行（.krow）。
            //
            // 为什么不走 EmptyState：那是「标题 + 一句说明 + 一颗按钮」的通用形状，
            // 而这一屏原型是四段；而且文案里不许出现 wire 键（原型 relay.html:4562
            // 写死「不印 `ag1` 这种 wire 键」，此前代码恰好印了）。
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
                                text = stringResource(Res.string.agent_empty_title),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Text(
                                text = stringResource(Res.string.agent_empty_message),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
                Text(
                    text = stringResource(Res.string.agent_empty_section),
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
                        text = stringResource(Res.string.agent_empty_card),
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

    // 待处理审批（原型 relay.html 的 `.grphead`「待处理审批 N 项」+ 逐条卡）。
    val pendingApprovals = state.agentApprovals.values.filter { it.status == "pending" }
    if (pendingApprovals.isNotEmpty()) {
        AgentApprovalsSection(
            approvals = pendingApprovals,
            onRespond = onRespondApproval,
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
                            // 点行 = 进聊天页（不是进事件流调试台）。先把该行那条活跃会话选中，
                            // 聊天页进去就停在它上面（聊天页对 agent 节点本来就有会话切换条）。
                            onOpenUnit = {
                                onSelectAgent(unit.peerId)
                                unit.active?.let { active -> onOpenSession(unit.peerId, active.sessionId) }
                                onOpenChat(unit.peerId, unit.wsId)
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
                                unassigned.active?.let { active -> onOpenSession(peerId, active.sessionId) }
                                // 未归属会话没有工作区可收窄 —— 传 null 是如实的（父稿 §2.5）。
                                onOpenChat(peerId, null)
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * Agent 段头：一枚小小的身份行（身份键 + 「N 个会话」+ 可选待审批）。
 *
 * 它不是可点的对象：点它没有比"点它名下的工作区行"更明确的落点 ——
 * 而「打开聊天」这件事在每一行都有自己的按钮。
 */
@Composable
private fun AgentSectionHead(section: WorkspaceAgentSectionUi) {
    val sp = LocalAppSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = sp.xs, end = sp.xs, top = sp.sm),
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
        Text(
            text = stringResource(Res.string.workspace_agent_section_sessions, section.sessionCount),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
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
private fun BoardGroupHead(group: app.relay.app.WorkspaceUnitGroupUi, now: Long) {
    val sp = LocalAppSpacing.current
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val dot = when {
        group.online -> MaterialTheme.accents.online
        group.lastSeenAt == 0L -> MaterialTheme.colorScheme.error
        else -> onSurfaceVariant
    }
    // 计数口径 = **本屏画出来的东西**（父稿 §2.2：未归属桶不参与计数，所以不能数 units）。
    // Agent 数 = 画了几个 Agent 段（不是"带 wsId 的 agent 行"：一个 agent 只要有一个
    // 工作区就成段，而它名下可能一条"agent 行"都没有 —— 真机上这里报过「0 个 Agent」
    // 而上面明明画着 dsh-cli 那一段）。
    val agentCount = group.agentSections().size
    val workspaceCount = group.units.count { it.wsId != null }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = sp.xs, end = sp.xs, top = sp.lg14, bottom = sp.sm6),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(sp.sm),
    ) {
        StatusDot(group.online)
        Text(
            // 与消息页组头同形：节点名 + 平台（同名设备靠平台消歧）。
            text = if (group.platform.isNullOrBlank()) group.nodeName else group.nodeName + " · " + group.platform,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = buildString {
                append(stringResource(Res.string.conv_inbox_ws_group_counts, agentCount, workspaceCount))
                if (!group.online && group.lastSeenAt > 0L) {
                    append(stringResource(Res.string.conv_inbox_ws_group_offline, relativeTime(group.lastSeenAt, now)))
                }
                if (group.unattributedApprovals > 0) {
                    append(" · " + stringResource(Res.string.conv_inbox_ws_pending, group.unattributedApprovals))
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
