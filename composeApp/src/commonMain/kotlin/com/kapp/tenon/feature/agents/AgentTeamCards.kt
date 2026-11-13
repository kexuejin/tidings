// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.agents

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
import com.kapp.tenon.domain.AgentHubUi
import com.kapp.tenon.domain.AgentTeamUi
import com.kapp.tenon.domain.AgentWorkstationPort
import com.kapp.tenon.domain.teamDisplayName
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.agent_count_items
import com.kapp.tenon.generated.resources.agent_subtitle_pending
import com.kapp.tenon.generated.resources.agent_team_ai_new
import com.kapp.tenon.generated.resources.conv_inbox_ws_open_chat
import com.kapp.tenon.generated.resources.desktop_toolbar_more
import com.kapp.tenon.generated.resources.exec_config_entry
import com.kapp.tenon.generated.resources.hub_agent_section_title
import com.kapp.tenon.generated.resources.hub_team_empty
import com.kapp.tenon.generated.resources.hub_team_leader_format
import com.kapp.tenon.generated.resources.hub_team_open_group
import com.kapp.tenon.generated.resources.hub_team_place_format
import com.kapp.tenon.generated.resources.hub_team_size_format
import com.kapp.tenon.generated.resources.hub_view_employees
import com.kapp.tenon.generated.resources.hub_view_teams
import com.kapp.tenon.generated.resources.ic_chat_bubble
import com.kapp.tenon.generated.resources.ic_chev
import com.kapp.tenon.generated.resources.ic_more
import com.kapp.tenon.generated.resources.ic_settings
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

/** 智能体页的两个视角（不变量 5）：**员工**是主体，**团队**是归属的另一面。 */
internal enum class AgentDirectoryView { EMPLOYEES, TEAMS }

/**
 * 智能体页的首屏目录：一个切换器 + 两个视角（员工名录 / 团队名录）。
 *
 * ## 为什么是「切换」而不是「上下两段」
 *
 * 两个视角回答的是不同问题（「这个人任职于哪些团队」vs「这队有谁」），但用的是**同一批
 * 声明的两个方向**。上下堆在一起会让同一批人出现两遍，用户还得自己分辨哪一段是权威的。
 * 规范 §4.2 的裁决就是：视图切换，不是两段。
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
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    /** 当前选中的宿主与身份键（桌面两栏联动高亮）。 */
    selectedHostNodeId: String? = null,
    selectedAgentKey: String? = null,
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
    // 只有**存在团队**（或这个壳能新建团队）时才给切换器：一个团队都没有、又没建队能力时
    // 「团队」那一档永远是空列表，给一颗点了什么都没有的按钮与「能力缺失 = 不画」同一条纪律。
    var view by rememberSaveable { mutableStateOf(AgentDirectoryView.EMPLOYEES) }
    val hasTeamView = teams.isNotEmpty() || onCreateTeam != null
    val effectiveView = if (hasTeamView) view else AgentDirectoryView.EMPLOYEES
    val showingTeams = hasTeamView && effectiveView == AgentDirectoryView.TEAMS

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(sp.md)) {
        if (hasTeamView) {
            // 段头行：左边说「现在这一段是什么 · 几条」，右边是换段。
            //
            // 此前左边恒写「智能体」—— 切到团队档时，段头说智能体、内容是团队，
            // 团队段自己的段头又叠一层「团队」，一屏两个标题且第一个是错的。
            // 段头必须跟档位走：切换器换的是**哪一段**，不是「智能体」的分类筛选。
            val headingRes = if (showingTeams) Res.string.hub_view_teams else Res.string.hub_agent_section_title
            val headingCount = if (showingTeams) teams.size else employees.size
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
                showSectionHeader = !hasTeamView,
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
                compact = compact,
                // 有切换器时段头由上面那一行承担（标题 · 计数已说清楚）；
                // 独立使用时（手机无团队 → 无切换器）仍由本段自己画。
                showSectionHeader = !hasTeamView,
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
    /**
     * 画不画本段自己的段头（「团队 · N」+ 两支动作）。
     *
     * 首屏目录把段头提到两段之上（与员工段共用一行，标题跟档位走）—— 这时这里再画一遍
     * 就是同一屏两个标题，且第一个（「智能体」）在团队档里是错的。
     * 默认 true = 独立使用时形态不变。
     */
    showSectionHeader: Boolean = true,
) {
    val sp = LocalAppSpacing.current
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
        if (teams.isEmpty()) {
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
        AgentRoster(count = teams.size) { index ->
            val team = teams[index]
            AgentTeamRow(
                team = team,
                now = now,
                compact = compact,
                onOpen = onOpenTeam?.let { open -> { open(team) } },
                onOpenGroup = onOpenGroup?.let { open -> { open(team.groupId) } },
                // 只有带档案的团队可编：没有 teamId 的旧团队名册归群声明，改它要去群信息里
                // 整表替换 —— 在这里给一颗按钮会诱使用户以为改的是同一件事。
                onEdit = if (team.teamId != null) onEditTeam?.let { edit -> { edit(team) } } else null,
            )
        }
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
    onOpen: (() -> Unit)?,
    onOpenGroup: (() -> Unit)?,
    onEdit: (() -> Unit)?,
) {
    val sp = LocalAppSpacing.current
    val title = teamDisplayName(team.teamName, team.groupName)
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
