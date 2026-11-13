// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.agents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.kapp.tenon.core.protocol.AVATAR_LEGACY_INITIAL_VALUE
import com.kapp.tenon.domain.AgentTeamDetailUi
import com.kapp.tenon.domain.AgentTeamGroupRow
import com.kapp.tenon.domain.AgentTeamMemberUi
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.action_back
import com.kapp.tenon.generated.resources.agent_detail_assignment_leader
import com.kapp.tenon.generated.resources.agent_detail_assignment_member
import com.kapp.tenon.generated.resources.agent_subtitle_pending
import com.kapp.tenon.generated.resources.common_cancel
import com.kapp.tenon.generated.resources.conv_inbox_ws_row_unnamed
import com.kapp.tenon.generated.resources.group_project_directory_unset
import com.kapp.tenon.generated.resources.hub_team_leader_format
import com.kapp.tenon.generated.resources.hub_team_size_format
import com.kapp.tenon.generated.resources.ic_arrow_back
import com.kapp.tenon.generated.resources.ic_chev
import com.kapp.tenon.generated.resources.ic_trash
import com.kapp.tenon.generated.resources.team_detail_groups_title
import com.kapp.tenon.generated.resources.team_detail_roster_title
import com.kapp.tenon.generated.resources.team_profile_dissolve
import com.kapp.tenon.generated.resources.team_profile_dissolve_confirm_body
import com.kapp.tenon.generated.resources.team_profile_dissolve_hint
import com.kapp.tenon.generated.resources.team_profile_edit
import com.kapp.tenon.ui.ContentWidths
import com.kapp.tenon.ui.centerCapped
import com.kapp.tenon.ui.components.AppButtonSize
import com.kapp.tenon.ui.components.AppIconButton
import com.kapp.tenon.ui.components.AppTextButton
import com.kapp.tenon.ui.components.MutedTag
import com.kapp.tenon.ui.components.PeerAvatar
import com.kapp.tenon.ui.components.PendingPill
import com.kapp.tenon.ui.components.RelayDialog
import com.kapp.tenon.ui.components.RelayDialogButton
import com.kapp.tenon.ui.components.SectionHeader
import com.kapp.tenon.ui.components.StatusDot
import com.kapp.tenon.ui.theme.IconSizes
import com.kapp.tenon.ui.theme.LocalAppSpacing
import com.kapp.tenon.ui.theme.LocalCardShape
import com.kapp.tenon.ui.theme.RelayElevations
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 团队详情页（团队视角的实体页）：点团队名录行的第一落点。
 *
 * ## 它替换的是「落到领队」
 *
 * 团队行原先点下去进的是**领队**那张员工详情 —— 标题是人名、段落是「这个人的任职」。
 * 用户点的是「这支队」，看到的却是「带这支队的那个人」：团队自己的名字、名册、
 * 在哪些群里干活，全都没有地方出现。这不是少一个页面，是**点错了对象**。
 *
 * ## 内容只有这一份
 *
 * 手机全屏二级页与桌面两栏右列画的是同一份本 Composable：外壳（谁传 `modifier`、
 * 下钻走导航还是本地状态）由调用方决定，段落内容不抄两遍（AGENTS §2.6）。
 * 形态语言照 `AgentDetailView` 的次级页骨架 —— 置顶栏、`centerCapped(ContentWidths.Pane)`
 * 收列、段头用既有 [SectionHeader]、列表用既有 `SectionListCard`（白卡 + 发丝线）。
 *
 * ## 数据从哪儿来
 *
 * [com.kapp.tenon.domain.agentTeamDetail]：名录行 → 详情，同一份 [com.kapp.tenon.domain.agentTeams]
 * 输出，不在这里 join 任何切片。空段如实：没有成员就写一句「还没有」，不画填料。
 *
 * ## 为什么成员行不可下钻
 *
 * 组员在协议里**不是席位**（没有 hostNodeId、没有独立进程，执行宿主恒为领队宿主），
 * `agentHubDetail` 按 `(host, agentId)` 根本解析不到它。给一行点了弹空态的入口
 * 等于骗用户：**能力缺失就是不画那条路**，与「手机不画编辑员工」同一条纪律。
 */
@Composable
internal fun AgentTeamDetailView(
    detail: AgentTeamDetailUi,
    onBack: () -> Unit,
    /** 点群行 → 那条群会话。null = 这个壳没有群会话入口 ⇒ 行不可点。 */
    onOpenGroup: ((groupId: String) -> Unit)?,
    /** 点「项目目录」段的行 → 既有工作区详情。null = 壳没有工作区详情层 ⇒ 行不可点。 */
    onOpenWorkspaceDetail: ((wsId: String) -> Unit)?,
    /** 「编辑团队」：只有带档案（`teamId != null`）且壳有档案面时才画（手机为 null）。 */
    onEditTeam: (() -> Unit)? = null,
    /**
     * 「解散团队」：逐群把席位退回单兵并删除档案。
     *
     * null = 这个壳没有档案面（手机）**或**这支队没有档案（旧式群内编制，没有可删的东西）
     * ⇒ 那颗入口不画。与 [onEditTeam] 同一个 gate —— 能编辑的地方才能解散。
     */
    onDissolveTeam: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val sp = LocalAppSpacing.current
    // 解散是不可逆的（档案删了不回来），所以先问一次 —— 与退群/解散群同一把确认。
    var confirmingDissolve by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().centerCapped(ContentWidths.Pane)) {
        // 置顶栏：返回 + 团队名 + 副题（领队 · 人数）。与员工详情同形。
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
                    text = detail.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    // 副题只说两件每支队都有的事：谁带队、几个人。群名在下面「在哪些群里」里说。
                    text = stringResource(Res.string.hub_team_leader_format, detail.leaderName) + " · " +
                        stringResource(Res.string.hub_team_size_format, detail.teamSize),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 编辑团队：没档案的旧团队走群内编制，在这里给一颗按钮会诱使以为改的是同一件事
            // （与名录行同一个 gate）；手机壳没有档案面 ⇒ null ⇒ 不画。
            if (onEditTeam != null && detail.teamId != null) {
                AppTextButton(
                    text = stringResource(Res.string.team_profile_edit),
                    size = AppButtonSize.Small,
                    onClick = onEditTeam,
                )
            }
            // 解散：图标而不是第二颗文字按钮 —— 置顶栏已经有「编辑团队」，两颗并排会把
            // 标题挤到没有位置。文案走 contentDescription，读屏与长按都指得出来。
            if (onDissolveTeam != null) {
                AppIconButton(
                    icon = Res.drawable.ic_trash,
                    contentDescription = stringResource(Res.string.team_profile_dissolve),
                    onClick = { confirmingDissolve = true },
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
            TeamIdentityCard(detail)

            // ===== 名册（这支队有谁，名字是**本群**那一份）=====
            SectionHeader(text = stringResource(Res.string.team_detail_roster_title))
            SectionListCard {
                detail.members.forEachIndexed { index, member ->
                    if (index > 0) SectionListDivider()
                    TeamMemberRow(member)
                }
            }

            // ===== 在哪些群里（每群：执行点目录 + 本群待批；行可点 → 群会话）=====
            SectionHeader(text = stringResource(Res.string.team_detail_groups_title))
            SectionListCard {
                detail.groups.forEachIndexed { index, row ->
                    if (index > 0) SectionListDivider()
                    TeamGroupRow(
                        row = row,
                        onOpen = onOpenGroup?.let { open -> { open(row.groupId) } },
                        onOpenWorkspace = onOpenWorkspaceDetail?.let { open ->
                            { row.wsId?.let(open) }
                        },
                    )
                }
            }
        }
    }

    if (confirmingDissolve && onDissolveTeam != null) {
        // 位置实参：具名 `teamName = …` 会命中 `TeamProfileWriteGuardTest` 的写入点探针
        // （那根针查的是 `teamName = `，分不清赋值与具名传参 —— 见 saveEmployeeProfile 的同款处理）。
        TeamDissolveDialog(
            detail.displayName,
            onDismiss = { confirmingDissolve = false },
            onConfirm = {
                confirmingDissolve = false
                onDissolveTeam()
            },
        )
    }
}

/**
 * 解散确认框：团队详情页的垃圾桶与名录里「未入驻」那一行的垃圾桶**共用这一份**。
 *
 * 共用不是为了少写几行 —— 框里那句「解散不踢人」是这个操作的唯一防线。两个入口各写
 * 一份，早晚有一份忘了写，而那一处就会有人点完之后以为把同事踢出了群。
 */
@Composable
internal fun TeamDissolveDialog(
    teamName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val sp = LocalAppSpacing.current
    RelayDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.team_profile_dissolve)) },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(sp.sm)) {
                Text(
                    text = stringResource(Res.string.team_profile_dissolve_confirm_body, teamName),
                    style = MaterialTheme.typography.bodyMedium,
                )
                // 这句是这次确认里最要紧的一句：解散**不踢人**。不写清楚的话，
                // 用户会以为要么不敢点、要么点完以为把同事踢出了群。
                Text(
                    text = stringResource(Res.string.team_profile_dissolve_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            RelayDialogButton(
                text = stringResource(Res.string.team_profile_dissolve),
                danger = true,
                onClick = onConfirm,
            )
        },
        dismissButton = {
            RelayDialogButton(
                text = stringResource(Res.string.common_cancel),
                onClick = onDismiss,
            )
        },
    )
}

/**
 * 身份卡：首字头像 + 在线点 + 团队名 + 人数标签。
 *
 * 头像走**首字**（`AVATAR_LEGACY_INITIAL_VALUE` = -1 就是既有的首字模式），与名录行
 * 同一套：底色 `nickHash(name) % 10`、前景按底色亮度取（对比度 ≥4.58:1）。
 * 这是「像员工」的关键一格 —— 全仓的智能体原先共用同一个机器人图标，谁是谁看不出来。
 */
@Composable
private fun TeamIdentityCard(detail: AgentTeamDetailUi) {
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
                PeerAvatar(
                    name = detail.displayName,
                    avatar = AVATAR_LEGACY_INITIAL_VALUE,
                    size = sp.xxl,
                    shape = MaterialTheme.shapes.medium,
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(sp.xxs)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(sp.sm6),
                    ) {
                        StatusDot(detail.online)
                        Text(
                            text = detail.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        MutedTag(stringResource(Res.string.hub_team_size_format, detail.teamSize))
                    }
                    // 群名只在**与标题不同**时画：没有独立团队名时标题就是群名，
                    // 再来一行同名属于噪音。领队与人数已由顶栏说过，这里不重复。
                    if (detail.displayName != detail.groupName) {
                        Text(
                            text = detail.groupName,
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
}

/**
 * 名册一行：首字头像 + 组内名 + 角色（领队行写「领队」，不重复它自己的角色）。
 *
 * 名字是**本群**那一份（协议把 `name` / `role` 挂在席位上，员工换群就换一份），
 * 所以同一名员工在两个团队的详情里会出现两个名字 —— 那是事实，不是笔误。
 */
@Composable
private fun TeamMemberRow(member: AgentTeamMemberUi) {
    val sp = LocalAppSpacing.current
    // 声明里没写名字时如实说「未命名」，不拿 agentId 顶替（身份键不是名字）。
    val name = member.name.takeIf { it.isNotBlank() }
        ?: stringResource(Res.string.conv_inbox_ws_row_unnamed)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = sp.lg14, vertical = sp.md10),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(sp.sm6),
    ) {
        PeerAvatar(
            name = name,
            avatar = AVATAR_LEGACY_INITIAL_VALUE,
            size = sp.xl,
            // 圆角与名录行同档（`shapes.medium`）：换一档新的会把这一屏推进
            // `designScreenDiffGate` 的 radius 差集 —— 24dp 的头像上 medium 恰好是半圆。
            shape = MaterialTheme.shapes.medium,
        )
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        MutedTag(
            text = if (member.isLeader) {
                stringResource(Res.string.agent_detail_assignment_leader)
            } else {
                // 没写角色时兜「组员」（与员工详情的任职行同一个键），不编一个角色名。
                member.role.takeIf { it.isNotBlank() }
                    ?: stringResource(Res.string.agent_detail_assignment_member)
            },
        )
    }
}

/**
 * 群行：群名 + 本群执行点目录（未设就写「本群还没有项目目录」）+ 本群待批。
 *
 * 两个点击目标不能共用一个 `Row.clickable`：点目录名要进工作区详情、点其余部分要进群会话，
 * 合成一个就会让「想看这个群的目录」变成「进群说话」。
 */
@Composable
private fun TeamGroupRow(
    row: AgentTeamGroupRow,
    onOpen: (() -> Unit)?,
    onOpenWorkspace: (() -> Unit)?,
) {
    val sp = LocalAppSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(horizontal = sp.lg14, vertical = sp.md10),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(sp.sm6),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(sp.xxs)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(sp.sm6),
            ) {
                Text(
                    text = row.groupName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (row.pendingCount > 0) {
                    PendingPill(stringResource(Res.string.agent_subtitle_pending, row.pendingCount))
                }
            }
            Text(
                text = if (row.wsId != null) {
                    // 已暴露 = 席位声明的目录名（未命名由既有键如实翻译）；
                    // 未暴露 = 「本群还没有项目目录」（同一个既有键，员工详情也这么写）。
                    row.wsTitle?.takeIf { it.isNotBlank() }
                        ?: stringResource(Res.string.conv_inbox_ws_row_unnamed)
                } else {
                    stringResource(Res.string.group_project_directory_unset)
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (onOpenWorkspace != null && row.wsId != null) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (onOpenWorkspace != null && row.wsId != null) {
                            Modifier.clickable(onClick = onOpenWorkspace)
                        } else {
                            Modifier
                        },
                    )
                    .padding(vertical = sp.xxs),
            )
        }
        if (onOpen != null) {
            Icon(
                painter = painterResource(Res.drawable.ic_chev),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(IconSizes.Label),
            )
        }
    }
}
