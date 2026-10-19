// SPDX-License-Identifier: GPL-3.0-only

package app.relay.feature.agents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.relay.app.RelayUiState
import app.relay.app.model.AgentSessionUi
import app.relay.app.model.GrantRequestStatus
import app.relay.app.model.PeerUi
import app.relay.app.model.WorkspaceUi
import app.relay.app.pickActiveSession
import app.relay.core.protocol.AgentGrantAccess
import app.relay.core.transport.SendOutcome
import app.relay.generated.resources.Res
import app.relay.generated.resources.action_back
import app.relay.generated.resources.agent_offline_message
import app.relay.generated.resources.agent_offline_title
import app.relay.generated.resources.agent_open_chat
import app.relay.generated.resources.agent_session_untitled
import app.relay.generated.resources.common_close
import app.relay.generated.resources.common_never
import app.relay.generated.resources.desktop_action_refresh
import app.relay.generated.resources.ic_arrow_back
import app.relay.generated.resources.ic_chat_bubble
import app.relay.generated.resources.ic_chev
import app.relay.generated.resources.ic_refresh
import app.relay.generated.resources.ic_terminal
import app.relay.generated.resources.ic_warn
import app.relay.generated.resources.workspace_dismiss_notice
import app.relay.generated.resources.workspace_driver_label
import app.relay.generated.resources.workspace_grant_mgr_btn
import app.relay.generated.resources.workspace_grant_remote_hint
import app.relay.generated.resources.workspace_grant_request_btn
import app.relay.generated.resources.workspace_grant_request_no_runtime
import app.relay.generated.resources.workspace_grant_request_peer_no_loop
import app.relay.generated.resources.workspace_grant_request_peer_not_agent
import app.relay.generated.resources.workspace_grant_request_pending
import app.relay.generated.resources.workspace_grant_request_sent
import app.relay.generated.resources.workspace_grant_none
import app.relay.generated.resources.workspace_grant_section_title
import app.relay.generated.resources.workspace_host_badge
import app.relay.generated.resources.workspace_no_sessions
import app.relay.generated.resources.workspace_no_title_banner
import app.relay.generated.resources.workspace_perm_label
import app.relay.generated.resources.workspace_perm_ro
import app.relay.generated.resources.workspace_perm_rw
import app.relay.generated.resources.workspace_qr_btn
import app.relay.generated.resources.workspace_recent_session_open
import app.relay.generated.resources.workspace_recent_session_title
import app.relay.generated.resources.workspace_remote_badge
import app.relay.generated.resources.workspace_role_admin
import app.relay.generated.resources.workspace_role_collaborator
import app.relay.generated.resources.workspace_role_delegated_approval
import app.relay.generated.resources.workspace_role_my_device
import app.relay.generated.resources.workspace_role_no_delegated_approval
import app.relay.generated.resources.workspace_role_observer
import app.relay.generated.resources.workspace_role_owner
import app.relay.generated.resources.workspace_session_pending_count
import app.relay.generated.resources.workspace_session_warn_no_announce
import app.relay.generated.resources.workspace_source_label
import app.relay.ui.ContentWidths
import app.relay.ui.centerCapped
import app.relay.ui.components.PendingPill
import app.relay.ui.components.RelayDialog
import app.relay.ui.components.RelayDialogButton
import app.relay.ui.components.SessionStateLabel
import app.relay.ui.components.StatusDot
import app.relay.ui.components.WorkspaceQrDialog
import app.relay.ui.components.relativeTime
import app.relay.ui.theme.IconSizes
import app.relay.ui.theme.LocalAppSpacing
import app.relay.ui.theme.LocalCardShape
import app.relay.ui.theme.RelayElevations
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 工作区详情（二级）= **工作区操作中心**。
 *
 * ## 三层 IA（issue 6，用户裁决）
 *
 * ① 工作区看板（按设备列工作区）→ ② **本屏**（操作中心：直通码 / 权限管理 / 最近会话入口）
 * → ③ 会话详情（事件流 + 审批队列，见 `SessionDetailView`）。
 *
 * ## 为什么砍掉整段会话列表与事件流
 *
 * 二级原来是一份「工作区详情 + 会话列表 + 活跃会话事件流」的混合页：它和三级会话详情
 * 画的是同一批东西（会话行、事件流），而用户从卡片点进来的意图是"我要对这个工作区做点
 * 什么"，不是"在这里读会话"。同一批内容在两屏各画一遍，正是这一轮要消掉的那类重复。
 * 会话列表整段删除；事件流归三级（它本来就是"某个会话的事件流"，没有会话列表就没有
 * 在二级选它的理由）。二级只留**操作**：
 *
 * - 直通码（`WorkspaceQrDialog`）与权限管理 —— 从会话详情搬过来：授权是按工作区给的，
 *   不是按某一轮会话给的（task-4 D）；
 * - 最近会话入口 —— 一条"最关键会话"的卡片，点它进三级（唯一保留下来的会话线索）；
 * - 待审批队列 —— 保留：它是工作区级的等待项（跨会话），处置入口不该因为砍了列表而消失。
 *
 * ## 一处派生
 *
 * "最近会话"挑哪一条由 `pickActiveSession` 决定（唯一一处规则，与看板卡片、会话详情同源）。
 * 本屏**不再自己算一遍** —— 那正是用户反馈"工作区最新会话显示错误"的成因。
 */
@Composable
fun WorkspaceDetailView(
    /** 这一屏对应的工作区 id。workspace 可能为 null（只有会话、还没宣告帧），但 id 一定有。 */
    wsId: String,
    workspace: WorkspaceUi?,
    peer: PeerUi?,
    sessions: List<AgentSessionUi>,
    state: RelayUiState,
    now: Long,
    onBack: () -> Unit,
    onOpenSession: (sessionId: String) -> Unit,
    onOpenChat: () -> Unit,
    onOpenSessionChat: ((sessionId: String) -> Unit)? = null,
    onRespondApproval: ((approvalId: String, allow: Boolean) -> Unit)? = null,
    onRefresh: (() -> Unit)? = null,
    /**
     * 「申请授权」：调用宿主的 `sendAgentGrantRequest(peerId, agentKey, reason, wsId, access)`
     * （task-10）。组合根（手机 = `App.kt`）把它接到 `controller::sendAgentGrantRequest`；
     * 桌面壳那条调用点暂时不接（task-9 在改 desktopApp，避免撞车），所以桌面侧没有这个入口。
     *
     * null = 本宿主没有这条能力 ⇒ 远端分支不画「申请授权」按钮（能力不存在就不放按钮，
     * 与「直通码只在有本机宿主能力时画」同一条规矩）。
     */
    onRequestGrant: (suspend () -> SendOutcome?)? = null,
    modifier: Modifier = Modifier,
) {
    val wsTitle = workspace?.wsTitle ?: workspace?.wsId ?: stringResource(Res.string.agent_session_untitled)
    val peerNick = peer?.displayName ?: ""
    val kind = workspace?.agentKind ?: sessions.firstOrNull()?.agentKind ?: ""

    // 宿主判据与派生层（InboxWorkspaceUnits 的 isHost）同一份：本机节点 / 回环地址。
    val isHost = peer != null && (
        peer.nodeId == state.selfNodeId ||
            peer.ip == "127.0.0.1" || peer.host == "localhost" || peer.host == "127.0.0.1"
        )
    val sessionGrant = peer?.let { state.grantFor(it.nodeId, workspace?.agentKey, wsId) }
    val canApprove = isHost ||
        sessionGrant?.isMyDevice == true ||
        sessionGrant?.access == AgentGrantAccess.OWNER ||
        (sessionGrant?.access == AgentGrantAccess.ADMIN && sessionGrant.delegatedApproval)
    val isReadOnly = !canApprove

    // 「最近会话」= 唯一一处挑选规则（优先待批 → 其次未超时 busy → 其次最新活动）。
    // 与本屏原来那段 `pending → busy → first` 的差别不只是收敛：旧代码的 "first" 排在
    // 一个**未按最新活动排序**的列表上（调用方递进来的是 visibleAgentSessionsOf 的过滤结果），
    // 于是它与看板卡片挑的常常不是同一条。
    val lastEventAtOf: (AgentSessionUi) -> Long = { s ->
        state.agentEventsOf(s).maxOfOrNull { it.at } ?: 0L
    }
    val activeSession = remember(sessions, state.agentApprovals, state.agentEvents, peer) {
        pickActiveSession(
            sessions = sessions,
            lastEventAtOf = lastEventAtOf,
            pendingSessionIds = state.agentApprovals.values
                .filter { it.peerId == peer?.nodeId && it.status == "pending" }
                .map { it.sessionId }
                .toSet(),
            now = now,
        )
    }

    var showQrDialog by remember { mutableStateOf(false) }
    var showGrantDialog by remember { mutableStateOf(false) }

    // task-10：申请授权的入口状态。**只对远端**有意义（本机就是宿主本身，权限由本机自己给）。
    val scope = rememberCoroutineScope()
    val peerCaps = peer?.caps.orEmpty()
    val grantStatus = peer?.let { state.grantStatusOf(it.nodeId, workspace?.agentKey, wsId) }
        ?: GrantRequestStatus.NONE
    val grantEntry = grantRequestEntry(peerCaps, grantStatus)
    var grantRequestSent by remember { mutableStateOf(false) }
    var grantRequestFailureUi by remember { mutableStateOf<GrantRequestFailure?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .centerCapped(ContentWidths.Pane),
    ) {
        // 置顶顶栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = LocalAppSpacing.current.lg, vertical = LocalAppSpacing.current.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.xs),
                modifier = Modifier.weight(1f),
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_arrow_back),
                        contentDescription = stringResource(Res.string.action_back),
                    )
                }
                Column {
                    Text(
                        text = wsTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (kind.isNotEmpty()) peerNick + " · " + kind else peerNick,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onRefresh != null) {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_refresh),
                            contentDescription = stringResource(Res.string.desktop_action_refresh),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                IconButton(onClick = onOpenChat) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_chat_bubble),
                        contentDescription = stringResource(Res.string.agent_open_chat),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LocalAppSpacing.current.lg, vertical = LocalAppSpacing.current.md),
            verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md),
        ) {
            // 异常态：未命名工作区横幅
            if (workspace != null && workspace.wsTitle.isNullOrBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(LocalAppSpacing.current.md),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm),
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_warn),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = stringResource(Res.string.workspace_no_title_banner, workspace.wsId),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            }

            // 工作区基础卡片（对齐原型 .krow dis）。会话数那一行按 issue 6 移除。
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = LocalCardShape.current,
                elevation = CardDefaults.cardElevation(defaultElevation = RelayElevations.Card),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(LocalAppSpacing.current.lg14),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md),
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            Icon(
                                painter = painterResource(Res.drawable.ic_terminal),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.xs),
                        ) {
                            Text(
                                text = wsTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (kind.isNotEmpty()) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                ) {
                                    Text(
                                        text = kind,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }

                        if (!workspace?.wsPath.isNullOrBlank()) {
                            Text(
                                text = workspace.wsPath.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }

                    StatusDot(online = peer?.online == true)
                }
            }

            // 离线状态横幅
            if (peer != null && !peer.online) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(LocalAppSpacing.current.lg)) {
                        Text(
                            text = stringResource(Res.string.agent_offline_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            text = stringResource(
                                Res.string.agent_offline_message,
                                if (peer.lastSeenAt > 0) relativeTime(peer.lastSeenAt, now) else stringResource(Res.string.common_never),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            // ===== 操作中心：权限与宿主控制条（从会话详情迁移，task-4 D）=====
            // 授权是按工作区给的，不是按某一轮会话给的：同一工作区里换一条会话不该换一套权限，
            // 也不该让"要看权限"这件事依赖"先挑一条会话"。所以它必须挂在工作区这一层。
            //
            // 本机（isHost）：直通码 + 权限管理，行为与 task-4 完全一致。
            // 远端（task-10）：**也能打开同一份授权清单**（只读），并多一个「申请授权」入口 ——
            //   四档角色（我的设备/拥有者/管理员/协作者）此前只在本机才看得到，远端工作区在
            //   手机上根本不知道谁被授信。
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = LocalCardShape.current,
                elevation = CardDefaults.cardElevation(defaultElevation = RelayElevations.Card),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = LocalAppSpacing.current.lg14, vertical = LocalAppSpacing.current.md10),
                    verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm),
                ) {
                    // 第一行：这一行是谁的（本机 = 宿主徽标 + 驱动；远端 = 远端徽标 + 来源）。
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.xs),
                    ) {
                        if (isHost) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                            ) {
                                Text(
                                    text = stringResource(Res.string.workspace_host_badge),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                            Text(
                                text = stringResource(Res.string.workspace_driver_label, kind.ifEmpty { "Agent" }),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Text(
                                    text = stringResource(Res.string.workspace_remote_badge),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                            Text(
                                text = stringResource(Res.string.workspace_source_label, peerNick),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // 第二行（只有远端）：当前权限。本机不画 —— 本机就是宿主本身，权限由它自己给。
                    if (!isHost) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.xs),
                        ) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (isReadOnly) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer,
                            ) {
                                Text(
                                    text = stringResource(
                                        Res.string.workspace_perm_label,
                                        if (isReadOnly) stringResource(Res.string.workspace_perm_ro) else stringResource(Res.string.workspace_perm_rw),
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isReadOnly) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }

                    // 第三行：动作。窄屏上这三颗按钮与上面那两行分开排，不然「远端工作区 + 来源 +
                    // 当前权限」会把按钮挤出去（本机只有两颗，但仍与第一行同排会显得挤）。
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (isHost) {
                            OutlinedButton(onClick = { showQrDialog = true }) {
                                Text(stringResource(Res.string.workspace_qr_btn), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Button(onClick = { showGrantDialog = true }) {
                            Text(stringResource(Res.string.workspace_grant_mgr_btn), style = MaterialTheme.typography.labelSmall)
                        }
                        if (!isHost && onRequestGrant != null) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val outcome = onRequestGrant?.invoke()
                                        if (outcome == null) {
                                            grantRequestSent = false
                                            grantRequestFailureUi = grantRequestFailure(peerCaps, state.network.isRunning)
                                        } else {
                                            grantRequestFailureUi = null
                                            grantRequestSent = true
                                        }
                                    }
                                },
                                enabled = grantEntry == GrantRequestEntry.Ready,
                            ) {
                                Text(stringResource(Res.string.workspace_grant_request_btn), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    // 申请结果 / 入口为什么按不了：**一档一句，不合并**（task-10 的硬要求）。
                    // 「没有运行时」与「对端没声明 ag1/agr1」是三种不同的处境，合成一句就等于
                    // 替对端说一个它没说过的结论。
                    val grantNote: String? = when {
                        grantRequestSent -> stringResource(Res.string.workspace_grant_request_sent)
                        grantRequestFailureUi == GrantRequestFailure.NoRuntime ->
                            stringResource(Res.string.workspace_grant_request_no_runtime)
                        grantRequestFailureUi == GrantRequestFailure.PeerNotAgent ->
                            stringResource(Res.string.workspace_grant_request_peer_not_agent)
                        grantRequestFailureUi == GrantRequestFailure.PeerNoGrantLoop ->
                            stringResource(Res.string.workspace_grant_request_peer_no_loop)
                        grantEntry == GrantRequestEntry.Pending ->
                            stringResource(Res.string.workspace_grant_request_pending)
                        grantEntry == GrantRequestEntry.PeerNotAgent ->
                            stringResource(Res.string.workspace_grant_request_peer_not_agent)
                        grantEntry == GrantRequestEntry.PeerNoGrantLoop ->
                            stringResource(Res.string.workspace_grant_request_peer_no_loop)
                        else -> null
                    }
                    if (grantNote != null) {
                        Text(
                            text = grantNote,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (grantRequestFailureUi != null) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }

            // 工作区专属待处理审批区段（跨会话；处置就在卡里，所以它留在操作中心）
            val sessionIds = remember(sessions) { sessions.map { it.sessionId }.toSet() }
            val wsApprovals = remember(peer, sessionIds, state.agentApprovals) {
                state.agentApprovals.values.filter {
                    it.peerId == (peer?.nodeId ?: "") && it.sessionId in sessionIds && it.status == "pending"
                }
            }
            if (wsApprovals.isNotEmpty()) {
                AgentApprovalsSection(
                    approvals = wsApprovals,
                    onRespond = onRespondApproval,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // ===== 操作中心：最近会话入口（取代整段会话列表）=====
            Text(
                text = stringResource(Res.string.workspace_recent_session_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = LocalAppSpacing.current.xs),
            )
            val recent = activeSession
            if (recent == null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = LocalCardShape.current,
                ) {
                    Text(
                        text = stringResource(Res.string.workspace_no_sessions),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(LocalAppSpacing.current.lg14),
                    )
                }
            } else {
                val events = state.agentEventsOf(recent)
                val lastEvent = events.lastOrNull()
                val pendingCount = state.agentApprovals.values.count {
                    it.peerId == recent.peerId && it.sessionId == recent.sessionId && it.status == "pending"
                }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenSession(recent.sessionId) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = LocalCardShape.current,
                    elevation = CardDefaults.cardElevation(defaultElevation = RelayElevations.Card),
                ) {
                    Column(Modifier.padding(LocalAppSpacing.current.lg14)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.xs),
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    painter = painterResource(Res.drawable.ic_terminal),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = recent.displayTitle ?: stringResource(Res.string.agent_session_untitled),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (!recent.agentKind.isNullOrBlank()) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                    ) {
                                        Text(
                                            text = recent.agentKind,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }
                            if (pendingCount > 0) {
                                PendingPill(stringResource(Res.string.workspace_session_pending_count, pendingCount))
                            } else if (!recent.state.isNullOrBlank()) {
                                SessionStateLabel(recent.state)
                            }
                        }

                        val desc = lastEvent?.let { it.summary ?: it.body } ?: recent.title
                        val title = recent.displayTitle
                        val isDuplicateDesc = desc.isNullOrBlank() ||
                            desc.trim() == title?.trim() ||
                            desc.trim() == recent.alias?.trim() ||
                            desc.trim() == recent.title?.trim()
                        if (!isDuplicateDesc && !desc.isNullOrBlank()) {
                            Text(
                                text = desc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = LocalAppSpacing.current.xs),
                            )
                        }

                        if (recent.announced == false) {
                            Text(
                                text = stringResource(Res.string.workspace_session_warn_no_announce),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = LocalAppSpacing.current.xs),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = relativeTime(lastEvent?.at ?: recent.sortActivityAt, now),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (onOpenSessionChat != null) {
                                    IconButton(
                                        onClick = { onOpenSessionChat(recent.sessionId) },
                                        modifier = Modifier.size(28.dp),
                                    ) {
                                        Icon(
                                            painter = painterResource(Res.drawable.ic_chat_bubble),
                                            contentDescription = stringResource(Res.string.agent_open_chat),
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                                Text(
                                    text = stringResource(Res.string.workspace_recent_session_open),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = LocalAppSpacing.current.xs),
                                )
                                Icon(
                                    painter = painterResource(Res.drawable.ic_chev),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(IconSizes.Label),
                                )
                            }
                        }
                    }
                }
            }

            // 底部「打开聊天」动作按钮
            Button(
                onClick = onOpenChat,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = LocalAppSpacing.current.md),
                shape = LocalCardShape.current,
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_chat_bubble),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(LocalAppSpacing.current.xs))
                Text(stringResource(Res.string.agent_open_chat))
            }

            Spacer(Modifier.height(LocalAppSpacing.current.xl))
        }
    }

    // ===== 直通码（从会话详情迁移）=====
    if (showQrDialog) {
        val host = peer?.host?.takeIf { it.isNotBlank() }
            ?: peer?.ip?.takeIf { it.isNotBlank() }
            ?: state.network.localAddresses.firstOrNull()
            ?: state.peers.firstOrNull { it.online }?.let { it.host.ifBlank { it.ip } }
        val port = if (peer?.nodeId == state.selfNodeId || (peer?.udpPort ?: 0) <= 0) {
            state.network.boundUdpPort.takeIf { it > 0 } ?: state.settings.udpPort
        } else {
            peer?.udpPort ?: state.settings.udpPort
        }
        // 只有"这个工作区就是本机的"时才把本机候选塞进码里：`host` 上面优先取的是**对端**的
        // 地址（远端工作区），那时我们的地址与这张码要连的目标不是同一台机器，塞进去会让对方
        // 连错机器。对端那侧的多候选地址本机也拿不到，只能沿用单地址形态。
        val qrIsSelf = peer == null || peer.nodeId == state.selfNodeId
        WorkspaceQrDialog(
            wsTitle = workspace?.wsTitle ?: workspace?.wsId ?: wsTitle,
            wsId = wsId,
            wsPath = workspace?.wsPath,
            host = host,
            port = port,
            addresses = if (qrIsSelf) state.network.localAddresses else emptyList(),
            nodeId = if (qrIsSelf) state.selfNodeId else "",
            // task-86：本机直通码必须带 pin（对端工作区的码不带 —— 那时手机连的是别人）。
            tlsPin = if (qrIsSelf) state.network.selfTlsPin else null,
            // 同理：昵称也只在"这张码指向本机"时才带（对端的码印我们的名字会张冠李戴）。
            nick = if (qrIsSelf) state.settings.nick else null,
            onDismiss = { showQrDialog = false },
        )
    }

    // ===== 权限管理（从会话详情迁移）=====
    if (showGrantDialog) {
        WorkspaceGrantDialog(
            peer = peer,
            wsId = wsId,
            state = state,
            // task-10：远端也开这扇窗，但只读 —— 清单是本端已知的授权，改不了对端的策略。
            isRemote = !isHost,
            onDismiss = { showGrantDialog = false },
        )
    }
}

/**
 * 工作区授权设备清单对话框。
 *
 * 从 `AgentSessionDetail` 原样搬来（task-4 D）；两处硬编码中文换成了
 * `workspace_grant_section_title` / `workspace_grant_none` 两条资源 ——
 * 迁移不能把 cjkLiteralGate 的按文件条数撑高（棘轮只降不升）。
 *
 * ## 远端也能开（task-10）
 *
 * 它本来就是**只读**的（没有任何修改授权的动作，确认键只是关闭），所以远端复用同一扇窗
 * 没有风险；差别只在多一句说明 [isRemote]：清单是本端已知的，对端设备上的权限由对端决定。
 * 之前它只在 `isHost` 分支里画按钮，于是远端工作区在手机上完全看不到"谁被授信"。
 */
@Composable
private fun WorkspaceGrantDialog(
    peer: PeerUi?,
    wsId: String,
    state: RelayUiState,
    isRemote: Boolean,
    onDismiss: () -> Unit,
) {
    RelayDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.workspace_grant_mgr_btn)) },
        content = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(LocalAppSpacing.current.sm),
                verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm),
            ) {
                Text(
                    text = stringResource(Res.string.workspace_grant_section_title),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                // 远端多一句：这份清单是本端已知的，改不了对端的策略（task-10）。
                if (isRemote) {
                    Text(
                        text = stringResource(Res.string.workspace_grant_remote_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.workspace_dismiss_notice),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(LocalAppSpacing.current.xs),
                    )
                }
                val wsGrants = state.agentGrants.values.filter {
                    it.peerId == peer?.nodeId || (it.wsId != null && it.wsId == wsId)
                }
                if (wsGrants.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.workspace_grant_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    wsGrants.forEach { grant ->
                        val roleText = when {
                            grant.isMyDevice -> stringResource(Res.string.workspace_role_my_device)
                            grant.access == AgentGrantAccess.OWNER -> stringResource(Res.string.workspace_role_owner)
                            grant.access == AgentGrantAccess.ADMIN -> {
                                val approval = if (grant.delegatedApproval) {
                                    stringResource(Res.string.workspace_role_delegated_approval)
                                } else {
                                    stringResource(Res.string.workspace_role_no_delegated_approval)
                                }
                                stringResource(Res.string.workspace_role_admin) + " (" + approval + ")"
                            }
                            grant.access == AgentGrantAccess.RW -> stringResource(Res.string.workspace_role_collaborator)
                            else -> stringResource(Res.string.workspace_role_observer)
                        }
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = state.peer(grant.peerId)?.displayName ?: grant.peerId.take(8),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = grant.state,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Text(
                                text = roleText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            RelayDialogButton(
                text = stringResource(Res.string.common_close),
                onClick = onDismiss,
                primary = true,
            )
        },
    )
}
