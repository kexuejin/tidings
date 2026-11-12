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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.kapp.tenon.domain.AgentHubUi
import com.kapp.tenon.domain.AgentTeamUi
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.agent_count_items
import com.kapp.tenon.generated.resources.agent_subtitle_pending
import com.kapp.tenon.generated.resources.exec_config_entry
import com.kapp.tenon.generated.resources.hub_agent_section_title
import com.kapp.tenon.generated.resources.hub_team_empty
import com.kapp.tenon.generated.resources.hub_team_leader_format
import com.kapp.tenon.generated.resources.hub_team_member_no_role
import com.kapp.tenon.generated.resources.hub_team_member_role_format
import com.kapp.tenon.generated.resources.hub_team_open_group
import com.kapp.tenon.generated.resources.hub_team_size_format
import com.kapp.tenon.generated.resources.hub_view_employees
import com.kapp.tenon.generated.resources.hub_view_teams
import com.kapp.tenon.generated.resources.ic_bot
import com.kapp.tenon.generated.resources.ic_chev
import com.kapp.tenon.generated.resources.ic_settings
import com.kapp.tenon.generated.resources.ic_chat_bubble
import com.kapp.tenon.generated.resources.workspace_latest_activity_format
import com.kapp.tenon.ui.components.AppButtonSize
import com.kapp.tenon.ui.components.AppOutlinedButton
import com.kapp.tenon.ui.components.MutedTag
import com.kapp.tenon.ui.components.PendingPill
import com.kapp.tenon.ui.components.SectionHeader
import com.kapp.tenon.ui.components.StatusDot
import com.kapp.tenon.ui.components.formatCount
import com.kapp.tenon.ui.components.relativeTime
import com.kapp.tenon.ui.theme.IconSizes
import com.kapp.tenon.ui.theme.LocalAppSpacing
import com.kapp.tenon.ui.theme.LocalCardShape
import com.kapp.tenon.ui.theme.relayCardShadow
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** 智能体页的两个视角（不变量 5）：**员工**是主体，**团队**是归属的另一面。 */
internal enum class AgentDirectoryView { EMPLOYEES, TEAMS }

/**
 * 智能体页的首屏目录：一个切换器 + 两个视角（员工卡片流 / 团队卡片流）。
 *
 * ## 为什么是「切换」而不是「上下两段」
 *
 * 两个视角回答的是不同问题（「这个人任职于哪些团队」vs「这队有谁」），但用的是**同一批
 * 声明的两个方向**。上下堆在一起会让同一批人出现两遍，用户还得自己分辨哪一段是权威的。
 * 规范 §4.2 的裁决就是：视图切换，不是两段。
 *
 * ## 为什么不在这里放任何下拉
 *
 * 底座与模型是宿主本地执行档（团队粒度），改它们只在「执行配置」页做。卡上放下拉
 * 等于把同一份配置画 N 遍，窄屏还会挤成三个控件（`designFormPairingGate` /
 * `designDpDebtGate` 的既有教训）。卡上只留只读徽标。
 */
@Composable
internal fun AgentDirectorySection(
    employees: List<AgentHubUi>,
    teams: List<AgentTeamUi>,
    now: Long,
    onOpenEntity: (AgentHubUi) -> Unit,
    onOpenChat: ((AgentHubUi) -> Unit)?,
    onOpenRowSession: ((hostNodeId: String, sessionId: String) -> Unit)?,
    /** 点团队卡：壳没有团队详情层时为 null —— 卡片照样画（内容是事实），只是不可下钻。 */
    onOpenTeam: ((AgentTeamUi) -> Unit)? = null,
    /** 「打开协同群」：null = 这个壳没有群会话入口 ⇒ 不画那颗按钮（不画点了没反应的按钮）。 */
    onOpenGroup: ((groupId: String) -> Unit)? = null,
    /**
     * 「执行配置」入口：null = 这个壳拿不到执行档端口（手机 / 预览）⇒ 整行不画。
     *
     * 底座与模型是宿主本地配置，摊在每张卡上等于把同一份配置画 N 遍；这里只给一个
     * 独立入口（规范 §4.0 的「一个真源 + 一层覆盖」）。
     */
    onOpenExecutionConfig: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val sp = LocalAppSpacing.current
    // 只有**存在团队**时才给切换器：一个团队都没有时「团队」那一档永远是空列表，
    // 给一颗点了什么都没有的按钮与「能力缺失 = 不画」同一条纪律。
    var view by rememberSaveable { mutableStateOf(AgentDirectoryView.EMPLOYEES) }
    val effectiveView = if (teams.isEmpty()) AgentDirectoryView.EMPLOYEES else view

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(sp.md)) {
        if (teams.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(sp.sm6),
            ) {
                Text(
                    text = stringResource(Res.string.hub_agent_section_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                DirectoryViewChip(
                    text = stringResource(Res.string.hub_view_employees),
                    selected = effectiveView == AgentDirectoryView.EMPLOYEES,
                    onClick = { view = AgentDirectoryView.EMPLOYEES },
                )
                DirectoryViewChip(
                    text = stringResource(Res.string.hub_view_teams),
                    selected = effectiveView == AgentDirectoryView.TEAMS,
                    onClick = { view = AgentDirectoryView.TEAMS },
                )
            }
        }
        if (onOpenExecutionConfig != null && !compact) {
            ExecutionConfigEntry(onClick = onOpenExecutionConfig)
        }
        when (effectiveView) {
            AgentDirectoryView.EMPLOYEES -> AgentHubSection(
                cards = employees,
                now = now,
                onOpenEntity = onOpenEntity,
                onOpenChat = onOpenChat,
                onOpenRowSession = onOpenRowSession,
                compact = compact,
                showSectionHeader = teams.isEmpty(),
            )
            AgentDirectoryView.TEAMS -> AgentTeamSection(
                teams = teams,
                now = now,
                onOpenTeam = onOpenTeam,
                onOpenGroup = onOpenGroup,
                compact = compact,
            )
        }
    }
}

/**
 * 「执行配置」入口行：一行文字 + 箭头，不带第二颗按钮（窄屏 360dp 一卡一主按钮的纪律
 * 同样适用于这种行）。
 */
@Composable
private fun ExecutionConfigEntry(onClick: () -> Unit) {
    val sp = LocalAppSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = sp.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(sp.sm6),
    ) {
        Icon(
            painter = painterResource(Res.drawable.ic_settings),
            contentDescription = null,
            modifier = Modifier.size(IconSizes.Label),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(Res.string.exec_config_entry),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            painter = painterResource(Res.drawable.ic_chev),
            contentDescription = null,
            modifier = Modifier.size(IconSizes.Label),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 视角切换的一格。选中态用 secondaryContainer（与既有 pill 的选中口径同一套颜色）。 */
@Composable
private fun DirectoryViewChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        onClick = onClick,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            modifier = Modifier.padding(
                horizontal = LocalAppSpacing.current.md10,
                vertical = LocalAppSpacing.current.xxs,
            ),
        )
    }
}

/**
 * 团队卡片流（团队视角）：每个 `(groupId, leaderAgentId)` 一张卡。
 *
 * 与员工卡片流**共用同一套容器语言**（surface + [LocalCardShape] + 自绘投影 + 分割线），
 * 差别只在内容：这里画的是「这队有谁、谁带队、本群几件待批」。
 */
@Composable
internal fun AgentTeamSection(
    teams: List<AgentTeamUi>,
    now: Long,
    onOpenTeam: ((AgentTeamUi) -> Unit)?,
    onOpenGroup: ((groupId: String) -> Unit)?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val sp = LocalAppSpacing.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(sp.md)) {
        SectionHeader(
            text = stringResource(Res.string.hub_view_teams),
            action = {
                Text(
                    text = stringResource(Res.string.agent_count_items, formatCount(teams.size.toLong())),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        if (teams.isEmpty()) {
            // 空段只画一句说明：与详情页的空段同形（标题下留一行 muted 文案，不画空卡）。
            Text(
                text = stringResource(Res.string.hub_team_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        teams.forEach { team ->
            AgentTeamCard(
                team = team,
                now = now,
                compact = compact,
                onOpen = onOpenTeam?.let { open -> { open(team) } },
                onOpenGroup = onOpenGroup?.let { open -> { open(team.groupId) } },
            )
        }
    }
}

/**
 * 一张团队卡。
 *
 * ## 成员表为什么带名字的「团队内」限定
 *
 * 协议里 `name` / `role` 挂在席位上，同一个人在不同团队是两个名字。团队卡上写的就是
 * **本团队**那一份（`墨查 · 质检审查员`），不是「这个人的名字」—— 换团队就换一份。
 *
 * ## 为什么没有「在线」以外的状态词
 *
 * 状态词表只有 `AgentSessionUi.state` 那一套（idle/busy/done/error）与宿主在线位；
 * 团队是聚合体，没有自己的运行时状态，给它编一个「忙碌/空闲」就是编数据。
 */
@Composable
private fun AgentTeamCard(
    team: AgentTeamUi,
    now: Long,
    compact: Boolean,
    onOpen: (() -> Unit)?,
    onOpenGroup: (() -> Unit)?,
) {
    val sp = LocalAppSpacing.current
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = LocalCardShape.current,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
            .relayCardShadow(),
    ) {
        Column(
            modifier = Modifier.padding(sp.lg),
            verticalArrangement = Arrangement.spacedBy(sp.md),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(sp.md),
            ) {
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
                        StatusDot(team.online)
                        Text(
                            text = team.groupName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (!compact) {
                            MutedTag(
                                text = stringResource(Res.string.hub_team_size_format, team.teamSize),
                            )
                        }
                    }
                    if (!compact) {
                        Text(
                            text = stringResource(Res.string.hub_team_leader_format, team.leaderName),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (onOpenGroup != null) {
                    AppOutlinedButton(
                        text = stringResource(Res.string.hub_team_open_group),
                        onClick = onOpenGroup,
                        size = AppButtonSize.Small,
                        leadingIcon = Res.drawable.ic_chat_bubble,
                    )
                }
            }

            HorizontalDivider()

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(sp.xs),
            ) {
                // 成员表：领队在前（投影已排序），本团队内的名字与角色一并给出。
                team.members.forEach { member ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(sp.sm6),
                    ) {
                        Text(
                            text = if (member.role.isBlank()) {
                                stringResource(Res.string.hub_team_member_no_role, member.name)
                            } else {
                                stringResource(Res.string.hub_team_member_role_format, member.name, member.role)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(sp.sm6),
                ) {
                    if (team.pendingCount > 0) {
                        // 本群待批：归因按「审批 → 会话 → 会话自报 wsId → 席位声明的绑定群」，
                        // 不是成员名下合计（那个数会把别的群的待批算到这张卡上）。
                        PendingPill(
                            text = stringResource(Res.string.agent_subtitle_pending, team.pendingCount),
                        )
                    }
                    Text(
                        text = stringResource(
                            Res.string.workspace_latest_activity_format,
                            relativeTime(team.lastActivityAt, now),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
