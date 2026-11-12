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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.kapp.tenon.domain.AgentHubDetailUi
import com.kapp.tenon.domain.AgentSlice
import com.kapp.tenon.domain.NodeSlice
import com.kapp.tenon.domain.SettingsSlice
import com.kapp.tenon.domain.mayDecideApproval
import com.kapp.tenon.domain.model.AgentApprovalDecision
import com.kapp.tenon.domain.pendingApprovalCount
import com.kapp.tenon.domain.primaryAssignment
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.action_back
import com.kapp.tenon.generated.resources.agent_employee_edit
import com.kapp.tenon.generated.resources.agent_employee_register
import com.kapp.tenon.ui.components.AppButtonSize
import com.kapp.tenon.ui.components.AppTextButton
import com.kapp.tenon.generated.resources.agent_detail_assignment_leader
import com.kapp.tenon.generated.resources.agent_detail_assignment_member
import com.kapp.tenon.generated.resources.agent_detail_assignments_section_title
import com.kapp.tenon.generated.resources.agent_detail_default_team_inferred
import com.kapp.tenon.generated.resources.agent_detail_default_team_mark
import com.kapp.tenon.generated.resources.agent_detail_groups_section_title
import com.kapp.tenon.generated.resources.agent_detail_no_assignments
import com.kapp.tenon.generated.resources.agent_detail_members_title
import com.kapp.tenon.generated.resources.agent_detail_no_groups
import com.kapp.tenon.generated.resources.agent_detail_no_workspace
import com.kapp.tenon.generated.resources.agent_detail_sessions_section_title
import com.kapp.tenon.generated.resources.agent_detail_ws_session_count
import com.kapp.tenon.generated.resources.agent_detail_workspaces_section_title
import com.kapp.tenon.generated.resources.agent_session_untitled
import com.kapp.tenon.generated.resources.agent_subtitle_pending
import com.kapp.tenon.generated.resources.conv_inbox_ws_badge_unknown_kind
import com.kapp.tenon.generated.resources.conv_inbox_ws_row_unnamed
import com.kapp.tenon.generated.resources.group_project_directory_unset
import com.kapp.tenon.generated.resources.hub_agent_role_single
import com.kapp.tenon.generated.resources.hub_agent_role_team
import com.kapp.tenon.generated.resources.ic_arrow_back
import com.kapp.tenon.generated.resources.ic_bot
import com.kapp.tenon.generated.resources.ic_chev
import com.kapp.tenon.generated.resources.workspace_no_sessions
import com.kapp.tenon.generated.resources.workspace_session_pending_count
import com.kapp.tenon.ui.ContentWidths
import com.kapp.tenon.ui.centerCapped
import com.kapp.tenon.ui.components.AppIconButton
import com.kapp.tenon.ui.components.MutedTag
import com.kapp.tenon.ui.components.PendingPill
import com.kapp.tenon.ui.components.SectionHeader
import com.kapp.tenon.ui.components.SessionStateLabel
import com.kapp.tenon.ui.components.StatusDot
import com.kapp.tenon.ui.components.relativeTime
import com.kapp.tenon.ui.theme.IconSizes
import com.kapp.tenon.ui.theme.LocalAppSpacing
import com.kapp.tenon.ui.theme.LocalCardShape
import com.kapp.tenon.ui.theme.RelayElevations
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 智能体详情页（Phase B4）：点卡片流的第一落点，**实体视角**的整页。
 *
 * ## 它是什么、不是什么（决策稿 §2 / §4 的卡片动作修订）
 *
 * 点卡从「直冲工作区治理段」改为「进这里」：先看这个 (宿主, agent) 实体是谁、服务哪些群、
 * 名下有哪些目录，再从项目目录行进既有的工作区详情（治理面从详情可达，不消失）、从会话行
 * 进既有的会话详情。返回层级：会话详情 ← 工作区详情 ← **本页** ← 看板。
 *
 * ## 内容只有这一份（AGENTS §2.6）
 *
 * 手机全屏二级页与桌面两栏右列画的是**同一份** [AgentHubDetailView]：外壳（谁传 `modifier`、
 * 下钻走导航还是本地状态）由调用方决定，段落内容不抄两遍。形态语言照 `WorkspaceDetailView`
 * 的次级页骨架 —— 置顶栏（返回 + 标题 + 副题）、`centerCapped(ContentWidths.Pane)` 收列、
 * 段头用既有 [SectionHeader]、列表用既有 [SectionListCard]（白卡 + 发丝线），不新造体系。
 *
 * ## 数据从哪儿来
 *
 * [com.kapp.tenon.domain.agentHubDetail] 投影：聚类、席位匹配、审批归因全在投影里做完
 * （与卡片流同一套装配），这里只画 —— 一屏的规则只留一处。空段如实：没有群、没有目录
 * 就写「还没有」，不画填料也不藏段落标题（没有可看的东西本身就是状态）。
 */
@Composable
internal fun AgentDetailView(
    detail: AgentHubDetailUi,
    now: Long,
    onBack: () -> Unit,
    /** 点项目目录行：进既有工作区详情（治理面的入口在详情里保持可达）。 */
    onOpenWorkspaceDetail: (wsId: String) -> Unit,
    /** 点会话行：进既有会话详情。 */
    onOpenSessionDetail: (sessionId: String) -> Unit,
    /** 待审批处置（两参）。null = 不画处置按钮（壳没有这条路时）。 */
    onRespondApproval: ((approvalId: String, allow: Boolean) -> Unit)?,
    /** 三参处置（「本会话免审」）：与工作区详情同一条透传，不在这里新写审批逻辑。 */
    onRespondChoice: ((approvalId: String, decision: AgentApprovalDecision) -> Unit)?,
    /** 审批段的只读判定与逐会话待批数要读切片（与工作区详情同源，不在页里另立判据）。 */
    agentSlice: AgentSlice,
    nodeSlice: NodeSlice,
    settingsSlice: SettingsSlice,
    /**
     * 「登记为员工 / 编辑员工」。null = 这个壳没有员工档案面（手机：档案只住宿主机）⇒ 不画按钮。
     *
     * 复用的是同一个回调：对话框按 [AgentHubDetailUi.employeeId] 自己决定是登记还是编辑 ——
     * 页头不需要知道两种形态的差别，它只负责把「用户点了」这件事交出去。
     */
    onEditEmployee: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val sp = LocalAppSpacing.current
    // 不拿身份键顶标题：身份键不是名字（与卡片的「未上报类型」兜底同一条纪律）。
    val displayName = detail.displayName
    val hostName = detail.hostName

    Column(modifier.fillMaxSize().centerCapped(ContentWidths.Pane)) {
        // 置顶顶栏：返回 + 标题 + 副题（宿主名）。与 WorkspaceDetailView 的次级页骨架同形。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = sp.lg, vertical = sp.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(sp.xs),
        ) {
            AppIconButton(
                icon = Res.drawable.ic_arrow_back,
                contentDescription = stringResource(Res.string.action_back),
                onClick = onBack,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName ?: stringResource(Res.string.conv_inbox_ws_badge_unknown_kind),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (hostName != null) {
                    Text(
                        text = hostName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // 登记 / 编辑员工：只在**有身份键**时画（没有 agentId 就无从登记 —— 点了只会失败），
            // 且只在壳给了通路时画（手机没有宿主档案，不画一颗按下去没反应的按钮）。
            if (onEditEmployee != null && detail.agentKey != null) {
                AppTextButton(
                    text = stringResource(
                        if (detail.employeeId == null) {
                            Res.string.agent_employee_register
                        } else {
                            Res.string.agent_employee_edit
                        },
                    ),
                    size = AppButtonSize.Small,
                    onClick = onEditEmployee,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = sp.lg, vertical = sp.md),
            verticalArrangement = Arrangement.spacedBy(sp.md),
        ) {
            AgentHubIdentityCard(detail = detail)

            // ===== 任职（不变量 5 的归属维：同一人可在多团队，每个团队一份名字/角色）=====
            SectionHeader(text = stringResource(Res.string.agent_detail_assignments_section_title))
            if (detail.assignments.isEmpty()) {
                DetailEmptyNote(stringResource(Res.string.agent_detail_no_assignments))
            } else {
                // 「主团队」是**推断值**（领队优先），规范 §5.1 的默认团队台账本轮没落盘。
                // 所以这一行必须自带「推断」二字：不标的话用户会以为在电脑上改过就生效了。
                val primary = primaryAssignment(detail.assignments)
                SectionListCard {
                    detail.assignments.forEachIndexed { index, assignment ->
                        if (index > 0) SectionListDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = sp.lg14, vertical = sp.md10),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(sp.sm6),
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(sp.xxs),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(sp.sm6),
                                ) {
                                    Text(
                                        text = assignment.groupName,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    if (assignment == primary) {
                                        MutedTag(stringResource(Res.string.agent_detail_default_team_mark))
                                        Text(
                                            text = stringResource(Res.string.agent_detail_default_team_inferred),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                Text(
                                    // 名字与角色都是**本团队**那一份；组员没写角色时补「组员」，
                                    // 不编一个角色名。
                                    text = assignment.seatName + " · " + (
                                        if (assignment.isLeader) {
                                            stringResource(Res.string.agent_detail_assignment_leader)
                                        } else {
                                            assignment.role ?: stringResource(Res.string.agent_detail_assignment_member)
                                        }
                                        ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            // ===== 服务群（每群：执行点状态 + 该群待批数；如实，未暴露就写未暴露）=====
            SectionHeader(text = stringResource(Res.string.agent_detail_groups_section_title))
            if (detail.servingGroups.isEmpty()) {
                DetailEmptyNote(stringResource(Res.string.agent_detail_no_groups))
            } else {
                SectionListCard {
                    detail.servingGroups.forEachIndexed { index, groupRow ->
                        if (index > 0) SectionListDivider()
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = sp.lg14, vertical = sp.md10),
                            verticalArrangement = Arrangement.spacedBy(sp.xxs),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(sp.sm6),
                            ) {
                                Text(
                                    text = groupRow.groupName,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (groupRow.pendingCount > 0) {
                                    PendingPill(
                                        stringResource(Res.string.agent_subtitle_pending, groupRow.pendingCount),
                                    )
                                }
                            }
                            Text(
                                // 已暴露 = 席位声明的目录名（未命名由既有键如实翻译）；
                                // 未暴露 = 「本群还没有项目目录」（既有键，不编造归属）。
                                text = if (groupRow.defaultWsId != null) {
                                    groupRow.resolvedWsTitle
                                        ?: stringResource(Res.string.conv_inbox_ws_row_unnamed)
                                } else {
                                    stringResource(Res.string.group_project_directory_unset)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            // ===== 项目目录（行可点 → 既有工作区详情）=====
            SectionHeader(text = stringResource(Res.string.agent_detail_workspaces_section_title))
            if (detail.workspaces.isEmpty()) {
                DetailEmptyNote(stringResource(Res.string.agent_detail_no_workspace))
            } else {
                SectionListCard {
                    detail.workspaces.forEachIndexed { index, wsRow ->
                        if (index > 0) SectionListDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenWorkspaceDetail(wsRow.wsId) }
                                .padding(horizontal = sp.lg14, vertical = sp.md10),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(sp.sm6),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = wsRow.wsTitle ?: stringResource(Res.string.conv_inbox_ws_row_unnamed),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = stringResource(Res.string.agent_detail_ws_session_count, wsRow.sessionCount),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                            // 宿主自报的目录内活跃态；null = 没报，点成灰 —— 沉默 ≠ 离线，
                            // 与 WorkspaceDetailView 对 PeerUi.online 的既有消费同一处理。
                            StatusDot(online = wsRow.online == true)
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

            // ===== 待审批（既有审批段整段复用：留痕、只读判定、免审按钮都在那一份里）=====
            val approvalRows = rememberLingeringApprovalRows(approvals = detail.approvals, now = now)
            if (approvalRows.isNotEmpty()) {
                AgentApprovalsSection(
                    approvals = approvalRows,
                    onRespond = onRespondApproval,
                    onRespondChoice = onRespondChoice,
                    // 只读用户也要能看审批段，但按钮位换成「只读」胶囊（与工作区详情同一判据）。
                    isReadOnly = { appr -> !mayDecideApproval(appr, agentSlice, nodeSlice, settingsSlice, now) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // ===== 会话（top 若干；行可点 → 既有会话详情）=====
            SectionHeader(text = stringResource(Res.string.agent_detail_sessions_section_title))
            if (detail.sessions.isEmpty()) {
                DetailEmptyNote(stringResource(Res.string.workspace_no_sessions))
            } else {
                SectionListCard {
                    detail.sessions.forEachIndexed { index, session ->
                        if (index > 0) SectionListDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenSessionDetail(session.sessionId) }
                                .padding(horizontal = sp.lg14, vertical = sp.md10),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(sp.sm6),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = session.displayTitle
                                        ?: stringResource(Res.string.agent_session_untitled),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                // 活动时刻为 0 = 宿主从没报过时间，那一行就不画（不印 1970）。
                                if (session.sortActivityAt > 0L) {
                                    Text(
                                        text = relativeTime(session.sortActivityAt, now),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                            }
                            // 待批优先于执行态：这一行真正要人看的事（与看板行同一分工）。
                            // 计数走全仓唯一入口 pendingApprovalCount（含过期滤除）。
                            val pending = pendingApprovalCount(
                                approvals = agentSlice.agentApprovals.values,
                                now = now,
                                peerId = detail.hostNodeId,
                                sessionId = session.sessionId,
                            )
                            if (pending > 0) {
                                PendingPill(stringResource(Res.string.workspace_session_pending_count, pending))
                            } else {
                                val state = session.state
                                if (!state.isNullOrBlank()) SessionStateLabel(state)
                            }
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
    }
}

/**
 * 身份卡：头像 + 在线点 + 显示名 + 角色标签（单兵 / 团队 N 人）+ 组员清单。
 * 与卡片流的头部同一套元素（头像槽、[StatusDot]、[MutedTag]），放到详情页的卡里。
 */
@Composable
private fun AgentHubIdentityCard(detail: AgentHubDetailUi) {
    val sp = LocalAppSpacing.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = LocalCardShape.current,
        elevation = CardDefaults.cardElevation(defaultElevation = RelayElevations.Card),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(sp.lg14),
            verticalArrangement = Arrangement.spacedBy(sp.md),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(sp.md),
            ) {
                // 头像槽：与卡片流同款（surfaceVariant 底 + primary 图标），尺寸走 spacing token。
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.size(sp.xxl),
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_bot),
                            contentDescription = null,
                            modifier = Modifier.size(IconSizes.Body),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(sp.sm6),
                    ) {
                        StatusDot(detail.online)
                        Text(
                            text = detail.displayName
                                ?: stringResource(Res.string.conv_inbox_ws_badge_unknown_kind),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        MutedTag(
                            text = if (detail.isTeam) {
                                stringResource(Res.string.hub_agent_role_team, detail.teamSize)
                            } else {
                                stringResource(Res.string.hub_agent_role_single)
                            },
                        )
                    }
                }
            }

            if (detail.isTeam && detail.teamMembers.isNotEmpty()) {
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(sp.xxs)) {
                    Text(
                        text = stringResource(Res.string.agent_detail_members_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    detail.teamMembers.forEach { member ->
                        val role = member.role
                        Text(
                            // 组员行 = 名字 + 声明的角色；角色没写就只画名字（不编）。
                            text = if (role.isBlank()) member.name else member.name + " · " + role,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** 空段的一句话：如实说「还没有」，不给段落画填料卡片。 */
@Composable
private fun DetailEmptyNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
