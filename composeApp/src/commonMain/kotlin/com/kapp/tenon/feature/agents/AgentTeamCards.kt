// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.kapp.tenon.core.protocol.AVATAR_LEGACY_INITIAL_VALUE
import com.kapp.tenon.domain.AgentEmployeeProfileUi
import com.kapp.tenon.domain.AgentHubUi
import com.kapp.tenon.domain.AgentTeamOrphanUi
import com.kapp.tenon.domain.AgentTeamUi
import com.kapp.tenon.domain.AgentWorkstationPort
import com.kapp.tenon.domain.teamDisplayName
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.agent_count_items
import com.kapp.tenon.generated.resources.agent_profile_section_title
import com.kapp.tenon.generated.resources.agent_subtitle_pending
import com.kapp.tenon.generated.resources.agent_team_ai_new
import com.kapp.tenon.generated.resources.conv_inbox_ws_open_chat
import com.kapp.tenon.generated.resources.desktop_toolbar_more
import com.kapp.tenon.generated.resources.exec_config_entry
import com.kapp.tenon.generated.resources.hub_agent_section_title
import com.kapp.tenon.generated.resources.hub_team_empty
import com.kapp.tenon.generated.resources.hub_team_leader_format
import com.kapp.tenon.generated.resources.hub_team_open_group
import com.kapp.tenon.generated.resources.hub_team_orphan_hint
import com.kapp.tenon.generated.resources.hub_team_orphan_meta_format
import com.kapp.tenon.generated.resources.hub_team_orphan_section
import com.kapp.tenon.generated.resources.hub_team_place_format
import com.kapp.tenon.generated.resources.hub_team_size_format
import com.kapp.tenon.generated.resources.hub_view_employees
import com.kapp.tenon.generated.resources.hub_view_profiles
import com.kapp.tenon.generated.resources.hub_view_teams
import com.kapp.tenon.generated.resources.ic_chat_bubble
import com.kapp.tenon.generated.resources.ic_chev
import com.kapp.tenon.generated.resources.ic_more
import com.kapp.tenon.generated.resources.ic_settings
import com.kapp.tenon.generated.resources.ic_trash
import com.kapp.tenon.generated.resources.team_profile_dissolve
import com.kapp.tenon.generated.resources.team_profile_edit
import com.kapp.tenon.generated.resources.team_profile_new
import com.kapp.tenon.ui.components.AppButtonSize
import com.kapp.tenon.ui.components.AppIconButton
import com.kapp.tenon.ui.components.AppTextButton
import com.kapp.tenon.ui.components.MutedTag
import com.kapp.tenon.ui.components.PeerAvatar
import com.kapp.tenon.ui.components.PendingPill
import com.kapp.tenon.ui.components.SectionHeader
import com.kapp.tenon.ui.components.StatusDot
import com.kapp.tenon.ui.components.formatCount
import com.kapp.tenon.ui.theme.IconSizes
import com.kapp.tenon.ui.theme.LocalAppSpacing
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 智能体页的三档（不变量 5）：**员工**是主体，**团队**是归属的另一面，
 * **档案**是登记簿（「谁登记过」，与运行时无关）。
 */
internal enum class AgentDirectoryView { EMPLOYEES, TEAMS, PROFILES }

/**
 * 智能体页的两个视角 + 登记簿（不变量 5）：**员工**是主体，**团队**是归属的另一面，
 * **档案**是「谁登记过」。
 *
 * ## 为什么是「切换」而不是「上下两段」
 *
 * 两个视角回答的是不同问题（「这个人任职于哪些团队」vs「这队有谁」），但用的是**同一批
 * 声明的两个方向**。上下堆在一起会让同一批人出现两遍，用户还得自己分辨哪一段是权威的。
 * 规范 §4.2 的裁决就是：视图切换，不是两段。
 *
 * 档案那一档是第三种问题（不是第三个方向）：它不依赖任何运行时事实 —— 一个人可以先建档、
 * 一个群都不进；席位全撤、agent 下线之后档案也还在。所以它也不能混进名录，
 * 但它比另两档更需要一个落点：注销此前没有任何界面入口。
 *
 * ## 为什么不在这里放任何下拉
 *
 * 底座与模型是宿主本地执行档（团队粒度），改它们只在「执行配置」页做。行上放下拉
 * 等于把同一份配置画 N 遍，窄屏还会挤成三个控件（`designFormPairingGate` /
 * `designDpDebtGate` 的既有教训）。行上只留只读徽标。
 */
@Composable
internal fun AgentDirectorySection(
    employees: List<AgentHubUi>,
    teams: List<AgentTeamUi>,
    now: Long,
    onOpenEntity: (AgentHubUi) -> Unit,
    onOpenChat: ((AgentHubUi) -> Unit)?,
    onOpenRowSession: ((hostNodeId: String, sessionId: String) -> Unit)?,
    /** 点团队行：壳没有团队详情层时为 null —— 行照样画（内容是事实），只是不可下钻。 */
    onOpenTeam: ((AgentTeamUi) -> Unit)? = null,
    /** 「打开协同群」：null = 这个壳没有群会话入口 ⇒ 不画那颗按钮（不画点了没反应的按钮）。 */
    onOpenGroup: ((groupId: String) -> Unit)? = null,
    /**
     * 「执行配置」入口：null = 这个壳拿不到执行档端口（手机 / 预览）⇒ 整行不画。
     *
     * 底座与模型是宿主本地配置，摊在每一行上等于把同一份配置画 N 遍；这里只给一个
     * 独立入口（规范 §4.0 的「一个真源 + 一层覆盖」）。
     */
    onOpenExecutionConfig: (() -> Unit)? = null,
    /**
     * 「新建团队」入口：null = 这个壳没有团队档案面（手机：档案只住宿主机）⇒ 整行不画。
     *
     * 与执行配置入口同一条纪律：能力缺失就是**不画**，不是画一颗点了没反应的按钮。
     */
    onCreateTeam: (() -> Unit)? = null,
    /** 「AI 建队」入口：null = 这个壳没有建队助手（手机/预览）⇒ 不画。与 onCreateTeam 同 gate。 */
    onCreateTeamWithAi: (() -> Unit)? = null,
    /** 团队行上的「编辑团队」：只有带档案（`teamId != null`）的团队可编 —— 旧团队走群内编制。 */
    onEditTeam: ((AgentTeamUi) -> Unit)? = null,
    /**
     * 未入驻任何群的团队档案：名录按**席位**装配，所以席位撤光的队伍从这里才看得见。
     * 手机端读不到档案（`teamProfiles` 只住宿主机）⇒ 恒空 ⇒ 整段不画。
     */
    orphanTeams: List<AgentTeamOrphanUi> = emptyList(),
    /** 解散一支「未入驻」的团队：null = 这个壳没有档案写通路 ⇒ 垃圾桶不画。 */
    onDissolveOrphanTeam: ((teamId: String) -> Unit)? = null,
    /**
     * 本机登记过的**员工档案**（第三档「档案」的唯一数据源）。
     *
     * 手机端读不到档案（`employeeProfiles` 只住宿主机）⇒ 恒空 ⇒ 这一档连切换器都不出现，
     * 与团队档案同一条纪律。空表而**有**团队时也不会多出一档空名录。
     */
    employeeProfiles: List<AgentEmployeeProfileUi> = emptyList(),
    /** 点档案行 = 编辑：null = 没有档案写通路 ⇒ 行不可点（点了弹不出表单）。 */
    onEditEmployeeProfile: ((AgentEmployeeProfileUi) -> Unit)? = null,
    /** 档案行的注销：null = 没有档案写通路 ⇒ 垃圾桶不画。 */
    onForgetEmployeeProfile: ((AgentEmployeeProfileUi) -> Unit)? = null,
    /**
     * 档案页的「新建档案」：null = 这个壳没有档案写通路 ⇒ 按钮不画。
     *
     * 与 [onEditEmployeeProfile] 不同，它**不需要**先有一行：给还没进任何群的本机 agent
     * 建档正是这条通路的用处，所以它在位时「档案」这一档空表也成立。
     */
    onNewEmployeeProfile: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    /** 当前选中的宿主与身份键（桌面两栏联动高亮）。 */
    selectedHostNodeId: String? = null,
    selectedAgentKey: String? = null,
    /**
     * 当前选中的**团队**（桌面两栏联动高亮）：团队键与 [AgentTeamUi] 的行同口径。
     *
     * 与员工那一对分开传：团队行原先靠 `selectedHostNodeId` 亮不起来（团队与领队是
     * 两个实体，宿主相同不等于同一行），点团队后左列看不出右列画的是哪一行。
     */
    selectedTeamGroupId: String? = null,
    selectedTeamLeaderAgentId: String? = null,
    /**
     * 员工工位端口（驱动名册 + 执行档）：null = 读不到本机执行绑定（手机端常态）
     * ⇒ 员工副行只写宿主名。
     *
     * 团队视角**不用它**：团队是聚合体，成员可以各用各的底座 —— 在团队行上写一个驱动名
     * 必然把「某些成员的」说成「这队的」。
     */
    workstationPort: AgentWorkstationPort? = null,
) {
    val sp = LocalAppSpacing.current
    // 只有**存在团队**（或这个壳能新建团队）时才给团队那一档：一个团队都没有、又没建队能力时
    // 「团队」那一档永远是空列表，给一颗点了什么都没有的按钮与「能力缺失 = 不画」同一条纪律。
    var view by rememberSaveable { mutableStateOf(AgentDirectoryView.EMPLOYEES) }
    val hasTeamView = teams.isNotEmpty() || onCreateTeam != null
    // 档案那一档在**有档案 或 能建档**时出现：空表 + 有入口 = 「从零建一个人」的起点
    // （这也是一直以来那个洞：登记入口只挂在员工详情页上，而详情页要先有一张卡才进得去）。
    // 手机两样都没有（档案只住宿主机）⇒ 这一档照旧不出现。
    val hasProfileView = employeeProfiles.isNotEmpty() || onNewEmployeeProfile != null
    val hasSwitcher = hasTeamView || hasProfileView
    // 选中的那一档没了（最后一支队解散 / 最后一份档案注销）⇒ 落回员工档，
    // 不留一屏「切换器上没有它、内容却是它」的残影。
    val effectiveView = when {
        view == AgentDirectoryView.TEAMS && !hasTeamView -> AgentDirectoryView.EMPLOYEES
        view == AgentDirectoryView.PROFILES && !hasProfileView -> AgentDirectoryView.EMPLOYEES
        else -> view
    }
    val showingTeams = hasSwitcher && effectiveView == AgentDirectoryView.TEAMS
    val showingProfiles = hasSwitcher && effectiveView == AgentDirectoryView.PROFILES

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(sp.md)) {
        if (hasSwitcher) {
            // 段头行：左边说「现在这一段是什么 · 几条」，右边是换段。
            //
            // 此前左边恒写「智能体」—— 切到团队档时，段头说智能体、内容是团队，
            // 团队段自己的段头又叠一层「团队」，一屏两个标题且第一个是错的。
            // 段头必须跟档位走：切换器换的是**哪一段**，不是「智能体」的分类筛选。
            val headingRes = when {
                showingTeams -> Res.string.hub_view_teams
                showingProfiles -> Res.string.agent_profile_section_title
                else -> Res.string.hub_agent_section_title
            }
            val headingCount = when {
                showingTeams -> teams.size
                showingProfiles -> employeeProfiles.size
                else -> employees.size
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(sp.sm6),
            ) {
                Text(
                    text = stringResource(headingRes) + " · " +
                        stringResource(
                            Res.string.agent_count_items,
                            formatCount(headingCount.toLong()),
                        ),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                DirectoryViewChip(
                    text = stringResource(Res.string.hub_view_employees),
                    selected = effectiveView == AgentDirectoryView.EMPLOYEES,
                    onClick = { view = AgentDirectoryView.EMPLOYEES },
                )
                if (hasTeamView) {
                    DirectoryViewChip(
                        text = stringResource(Res.string.hub_view_teams),
                        selected = effectiveView == AgentDirectoryView.TEAMS,
                        onClick = { view = AgentDirectoryView.TEAMS },
                    )
                }
                if (hasProfileView) {
                    DirectoryViewChip(
                        text = stringResource(Res.string.hub_view_profiles),
                        selected = effectiveView == AgentDirectoryView.PROFILES,
                        onClick = { view = AgentDirectoryView.PROFILES },
                    )
                }
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
                showSectionHeader = !hasSwitcher,
                selectedHostNodeId = selectedHostNodeId,
                selectedAgentKey = selectedAgentKey,
                workstationPort = workstationPort,
            )
            AgentDirectoryView.TEAMS -> AgentTeamSection(
                teams = teams,
                now = now,
                onOpenTeam = onOpenTeam,
                onOpenGroup = onOpenGroup,
                onCreateTeam = onCreateTeam,
                onEditTeam = onEditTeam,
                onCreateTeamWithAi = onCreateTeamWithAi,
                orphans = orphanTeams,
                onDissolveOrphan = onDissolveOrphanTeam,
                compact = compact,
                selectedGroupId = selectedTeamGroupId,
                selectedLeaderAgentId = selectedTeamLeaderAgentId,
                // 有切换器时段头由上面那一行承担（标题 · 计数已说清楚）；
                // 独立使用时（手机无团队 → 无切换器）仍由本段自己画。
                showSectionHeader = !hasSwitcher,
            )
            AgentDirectoryView.PROFILES -> AgentProfileSection(
                profiles = employeeProfiles,
                onEdit = onEditEmployeeProfile,
                onForget = onForgetEmployeeProfile,
                onCreate = onNewEmployeeProfile,
                // 同上：标题与计数由切换器那一行说，本段不再叠一个。
                showSectionHeader = !hasSwitcher,
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
 * 团队段的两支动作：新建团队 / AI 建队。
 *
 * 只有一个实现，三个落位（段头右侧、首屏目录的段头之下、空态说明之下）—— 三处各写一遍
 * 必然漂（这正是这一段原来的样子：段头里一份、空态里一份，条件还不一样）。
 *
 * 两支都藏进溢出菜单是**倒退**：建队是这一档唯一的创建入口，找不到就会去群里凑一支。
 */
@Composable
private fun TeamActionButtons(
    onCreateTeam: (() -> Unit)?,
    onCreateTeamWithAi: (() -> Unit)?,
) {
    if (onCreateTeam == null && onCreateTeamWithAi == null) return
    val sp = LocalAppSpacing.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(sp.sm6),
    ) {
        onCreateTeam?.let { open ->
            AppTextButton(
                text = stringResource(Res.string.team_profile_new),
                onClick = open,
                size = AppButtonSize.Small,
            )
        }
        onCreateTeamWithAi?.let { open ->
            AppTextButton(
                text = stringResource(Res.string.agent_team_ai_new),
                onClick = open,
                size = AppButtonSize.Small,
            )
        }
    }
}

/**
 * 团队名录（团队视角）：每个 `(groupId, leaderAgentId)` 一行。
 *
 * 与员工名录**共用同一个外框**（[AgentRoster]）与同一套行内语言，差别只在内容：
 * 这里画的是「哪支队、谁带队、几个人、本群几件待批」。
 *
 * ## 成员清单为什么不在行上
 *
 * 一行人数的编制事实已经由「N 人」说出来，队员名字展开在领队的详情页里 —— 行上再摆
 * 一列名字会把「这支队现在有事吗」挤掉。参考的团队名录同样只有「队长 / 成员数」两列。
 */
@Composable
internal fun AgentTeamSection(
    teams: List<AgentTeamUi>,
    now: Long,
    onOpenTeam: ((AgentTeamUi) -> Unit)?,
    onOpenGroup: ((groupId: String) -> Unit)?,
    onCreateTeam: (() -> Unit)? = null,
    onEditTeam: ((AgentTeamUi) -> Unit)? = null,
    /** 「AI 建队」入口：null = 这个壳没有建队助手（手机/预览）⇒ 不画。与 onCreateTeam 同 gate。 */
    onCreateTeamWithAi: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    /** 桌面两栏联动高亮：与 [AgentTeamUi.groupId] 同口径；null = 没有选中的团队。 */
    selectedGroupId: String? = null,
    /** 团队键的另一半（领队席位）；两格成对才亮，同宿主的另一支队不会跟着亮。 */
    selectedLeaderAgentId: String? = null,
    /**
     * 画不画本段自己的段头（「团队 · N」+ 两支动作）。
     *
     * 首屏目录把段头提到两段之上（与员工段共用一行，标题跟档位走）—— 这时这里再画一遍
     * 就是同一屏两个标题，且第一个（「智能体」）在团队档里是错的。
     * 默认 true = 独立使用时形态不变。
     */
    showSectionHeader: Boolean = true,
    /**
     * 未入驻任何群的团队档案（见 [AgentDirectorySection] 的同名参数）。
     *
     * 名录是「在群里的团队」，这一段是「档案还在、但没有群」—— 两个分类，不混进
     * 同一个 [AgentRoster]：混进去之后每一行都得解释「这一行为什么没有群」。
     */
    orphans: List<AgentTeamOrphanUi> = emptyList(),
    /** 解散未入驻团队：null ⇒ 孤儿段整段不画（不画一颗点了没反应的垃圾桶）。 */
    onDissolveOrphan: ((teamId: String) -> Unit)? = null,
) {
    val sp = LocalAppSpacing.current
    // 有孤儿可画时不能再画「还没有团队」：确实一支入驻的都没有，但档案还在 —— 那句
    // 说明会让用户以为这里什么都没有，而下面正列着他删不掉的队伍。
    val showOrphans = orphans.isNotEmpty() && onDissolveOrphan != null
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(sp.md)) {
        if (showSectionHeader) {
            SectionHeader(
                text = stringResource(Res.string.hub_view_teams),
                action = {
                    // 「新建团队」与计数并排：团队视角里这是唯一一个建队入口，
                    // 藏在溢出菜单里等于没有（用户找不到就会去群里凑）。
                    if (!compact) TeamActionButtons(onCreateTeam, onCreateTeamWithAi)
                    Text(
                        text = stringResource(Res.string.agent_count_items, formatCount(teams.size.toLong())),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        } else if (teams.isNotEmpty()) {
            // 首屏目录形态：标题与计数已由上方那一行（「团队 · N」+ 切换器）说过，
            // 两支动作落到这里独占一行 —— 段头一被抑制，动作不能跟着消失
            // （桌面两栏左列就是这么丢掉「新建团队」的）。
            // 动作**独立成行**，不跟标题抢宽度：这一行只有两颗文字按钮，窄列也放得下。
            TeamActionButtons(onCreateTeam, onCreateTeamWithAi)
        }
        if (teams.isEmpty() && !showOrphans) {
            // 空段只画一句说明：与详情页的空段同形（标题下留一行 muted 文案，不画空行）。
            Text(
                text = stringResource(Res.string.hub_team_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 出口画在说明**之下**（说明 → 出路）；段头在时动作已由段头承担，不重复。
            TeamActionButtons(
                onCreateTeam = if (showSectionHeader) null else onCreateTeam,
                onCreateTeamWithAi = if (showSectionHeader) null else onCreateTeamWithAi,
            )
            return@Column
        }
        if (teams.isNotEmpty()) {
            AgentRoster(count = teams.size) { index ->
                val team = teams[index]
                AgentTeamRow(
                    team = team,
                    now = now,
                    compact = compact,
                    selected = team.groupId == selectedGroupId && team.leaderAgentId == selectedLeaderAgentId,
                    onOpen = onOpenTeam?.let { open -> { open(team) } },
                    onOpenGroup = onOpenGroup?.let { open -> { open(team.groupId) } },
                    // 只有带档案的团队可编：没有 teamId 的旧团队名册归群声明，改它要去群信息里
                    // 整表替换 —— 在这里给一颗按钮会诱使用户以为改的是同一件事。
                    onEdit = if (team.teamId != null) onEditTeam?.let { edit -> { edit(team) } } else null,
                )
            }
        }
        if (showOrphans) {
            OrphanTeamSection(
                orphans = orphans,
                onDissolve = onDissolveOrphan,
            )
        }
    }
}

/**
 * 「未入驻任何群」的团队档案段。
 *
 * ## 它为什么必须存在
 *
 * 名录按席位装配。一支队伍被逐群「移除」之后（或它所在的群被解散之后）就从名录里
 * 消失，档案却还在 `RelaySettings.teamProfiles` 里 —— 群信息的「添加团队」列表照样
 * 列着它。用户看得见、删不掉，唯一的出路是先把它加回某个群再从详情页解散。
 *
 * 这一段的落点是**解散**，不是「重新加群」：加群已经有入口（群信息 → 添加团队），
 * 而删除此前没有任何入口。
 *
 * ## 行不可点
 *
 * 团队详情页按 `(groupId, leaderAgentId)` 定位（`agentTeamDetail`），孤儿没有群、
 * 也解析不到席位 —— 给一行点了弹空态的入口等于骗用户。所以行不画点击，只给行尾一颗
 * 垃圾桶（能力缺失就是不画那条路）。
 */
@Composable
private fun OrphanTeamSection(
    orphans: List<AgentTeamOrphanUi>,
    onDissolve: ((teamId: String) -> Unit)?,
) {
    val sp = LocalAppSpacing.current
    Column(verticalArrangement = Arrangement.spacedBy(sp.xs)) {
        SectionHeader(
            text = stringResource(Res.string.hub_team_orphan_section),
            action = {
                Text(
                    text = stringResource(
                        Res.string.agent_count_items,
                        formatCount(orphans.size.toLong()),
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        Text(
            text = stringResource(Res.string.hub_team_orphan_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AgentRoster(count = orphans.size) { index ->
            val orphan = orphans[index]
            AgentTeamOrphanRow(
                orphan = orphan,
                onDissolve = onDissolve?.let { dissolve -> { dissolve(orphan.teamId) } },
            )
        }
    }
}

/**
 * 一条「未入驻」的团队：首字头像 + 名字 + 「N 人 · 不在任何群」+ 解散。
 *
 * 与名录行同一套语言（同一个 [PeerAvatar] 首字模式、同样的两行文字），差别只在第三格：
 * 名录行那里是待批与最近活动，这里恒为「不在任何群」—— 它没有席位，就没有那些事实。
 */
@Composable
private fun AgentTeamOrphanRow(
    orphan: AgentTeamOrphanUi,
    onDissolve: (() -> Unit)?,
) {
    val sp = LocalAppSpacing.current
    var confirming by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = sp.lg, vertical = sp.md10),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(sp.md),
    ) {
        PeerAvatar(
            name = orphan.name,
            avatar = AVATAR_LEGACY_INITIAL_VALUE,
            size = sp.xxl,
            shape = MaterialTheme.shapes.medium,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(sp.xxs),
        ) {
            Text(
                text = orphan.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(Res.string.hub_team_orphan_meta_format, orphan.memberCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onDissolve != null) {
            AppIconButton(
                icon = Res.drawable.ic_trash,
                contentDescription = stringResource(Res.string.team_profile_dissolve),
                onClick = { confirming = true },
                modifier = Modifier.size(sp.xxl),
            )
        }
    }
    if (confirming) {
        // 位置实参：具名 `teamName = …` 会命中团队名写入点守卫（同详情页那一处）。
        TeamDissolveDialog(
            orphan.name,
            onDismiss = { confirming = false },
            onConfirm = {
                confirming = false
                onDissolve?.invoke()
            },
        )
    }
}

/**
 * 名录里的一行团队。
 *
 * ## 名字的「团队内」限定
 *
 * 协议里 `name` / `role` 挂在席位上，同一个人在不同团队是两个名字；行上写的是**本团队**
 * 那一份（`领队 墨查`），不是「这个人的名字」。
 *
 * ## 为什么没有「在线」以外的状态词
 *
 * 状态词表只有 `AgentSessionUi.state` 那一套（idle/busy/done/error）与宿主在线位；
 * 团队是聚合体，没有自己的运行时状态，给它编一个「忙碌/空闲」就是编数据。
 */
@Composable
private fun AgentTeamRow(
    team: AgentTeamUi,
    now: Long,
    compact: Boolean,
    /** 这一行是不是右列正在看的那支队（桌面两栏联动）。 */
    selected: Boolean,
    onOpen: (() -> Unit)?,
    onOpenGroup: (() -> Unit)?,
    onEdit: (() -> Unit)?,
) {
    val sp = LocalAppSpacing.current
    val title = teamDisplayName(team.teamName, team.groupName)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 选中底色与员工行同一个 token（`secondaryContainer`）：两档名录共用一套选中语言，
            // 换一个颜色就等于在同一屏里说两种"选中"。
            .then(
                if (selected) {
                    Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                } else {
                    Modifier
                },
            )
            .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(horizontal = sp.lg, vertical = sp.md10),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(sp.md),
    ) {
        PeerAvatar(
            name = title,
            avatar = AVATAR_LEGACY_INITIAL_VALUE,
            size = sp.xxl,
            shape = MaterialTheme.shapes.medium,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(sp.xxs),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(sp.sm6),
            ) {
                StatusDot(team.online)
                Text(
                    // 团队名优先（档案 → 快照 → 群名，统一走 teamDisplayName）：
                    // 团队有自己的名字之后，群名不该再顶在标题上。
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!compact) {
                    MutedTag(text = stringResource(Res.string.hub_team_size_format, team.teamSize))
                }
                // 窄列：待批挪进名字行（与员工行同一落位），把整段宽度留给名字。
                if (compact && team.pendingCount > 0) TeamPendingSlot(team)
            }
            Text(
                // 有独立团队名时把群名挂在副行：同一支队进了多个群时，
                // 群名是唯一能区分「这是哪一行」的线索（不做就两行同名）。
                text = if (team.teamName.isNullOrBlank()) {
                    stringResource(Res.string.hub_team_leader_format, team.leaderName)
                } else {
                    stringResource(Res.string.hub_team_leader_format, team.leaderName) +
                        " · " + team.groupName
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 第三行：这支队在哪个项目目录里干活（席位声明的执行点）。
            // 团队行此前只有「领队 · 群名」两行，宽态比员工行矮一行 —— 员工行那一格
            // 是工位（用什么驱动、在哪台机器），团队没有自己的工位，但有自己的地盘。
            // 窄列不画：左列 268dp 里两行已是上限（再加一行就把「N 人」挤出去）。
            val place = team.primaryWsTitle?.takeIf { it.isNotBlank() }
            if (!compact && place != null) {
                Text(
                    text = stringResource(Res.string.hub_team_place_format, place),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!compact) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(sp.xxs),
            ) {
                TeamPendingSlot(team)
                HubActivityLine(at = team.lastActivityAt, online = team.online, now = now)
            }
        }
        // 行尾动作恒为**一颗**：主操作直连，其余收进溢出。
        // 行尾两颗图标按钮在窄列里会把名字挤成省略号（`designFormPairingGate` 的老教训）。
        when {
            onEdit != null -> RosterOverflow(
                items = buildList {
                    if (onOpenGroup != null) add(stringResource(Res.string.hub_team_open_group) to onOpenGroup)
                    add(stringResource(Res.string.team_profile_edit) to onEdit)
                },
            )
            onOpenGroup != null -> AppIconButton(
                icon = Res.drawable.ic_chat_bubble,
                contentDescription = stringResource(Res.string.conv_inbox_ws_open_chat),
                onClick = onOpenGroup,
                modifier = Modifier.size(sp.xxl),
            )
        }
    }
}

/**
 * 团队行的**待批格**（没有待批时整格不画）。
 *
 * 本群待批：归因按「审批 → 会话 → 会话自报 wsId → 席位声明的绑定群」，不是成员名下
 * 合计（那个数会把别的群的待批算到这一行上）。
 */
@Composable
private fun TeamPendingSlot(team: AgentTeamUi) {
    if (team.pendingCount <= 0) return
    PendingPill(text = stringResource(Res.string.agent_subtitle_pending, team.pendingCount))
}

/**
 * 名录行的溢出菜单：一个图标 + 浮层里的动作清单。
 *
 * 只在「有两个以上动作」时出现（一个动作直连图标即可）。菜单走 `Popup`，Layoutlib 不
 * 组合它的子内容 —— 所以它不参与截图签收，这也正是把它收在这里的理由：行上的可见
 * 元素仍然只有头像、文字、状态与一颗图标。
 */
@Composable
private fun RosterOverflow(items: List<Pair<String, () -> Unit>>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        AppIconButton(
            icon = Res.drawable.ic_more,
            contentDescription = stringResource(Res.string.desktop_toolbar_more),
            onClick = { expanded = true },
            modifier = Modifier.size(LocalAppSpacing.current.xxl),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            items.forEach { (label, action) ->
                DropdownMenuItem(
                    text = { Text(text = label, style = MaterialTheme.typography.bodyMedium) },
                    onClick = {
                        expanded = false
                        action()
                    },
                )
            }
        }
    }
}
