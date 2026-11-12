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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.kapp.tenon.domain.ActivityFreshness
import com.kapp.tenon.domain.AgentHubArtifactUi
import com.kapp.tenon.domain.AgentHubRowKind
import com.kapp.tenon.domain.AgentHubStatusRow
import com.kapp.tenon.domain.AgentHubUi
import com.kapp.tenon.domain.agentActivityFreshness
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.agent_count_items
import com.kapp.tenon.generated.resources.agent_subtitle_pending
import com.kapp.tenon.generated.resources.conv_inbox_ws_badge_unknown_kind
import com.kapp.tenon.generated.resources.conv_inbox_ws_open_chat
import com.kapp.tenon.generated.resources.hub_agent_meta_format
import com.kapp.tenon.generated.resources.hub_agent_role_single
import com.kapp.tenon.generated.resources.hub_agent_role_team
import com.kapp.tenon.generated.resources.hub_activity_last_format
import com.kapp.tenon.generated.resources.hub_activity_offline_format
import com.kapp.tenon.generated.resources.hub_agent_section_title
import com.kapp.tenon.generated.resources.hub_artifact_latest_tag
import com.kapp.tenon.generated.resources.ic_bot
import com.kapp.tenon.generated.resources.ic_chat_bubble
import com.kapp.tenon.generated.resources.ic_file
import com.kapp.tenon.generated.resources.workspace_latest_activity_format
import com.kapp.tenon.ui.components.AppButtonSize
import com.kapp.tenon.ui.components.AppOutlinedButton
import com.kapp.tenon.ui.components.MutedTag
import com.kapp.tenon.ui.components.PendingPill
import com.kapp.tenon.ui.components.SectionHeader
import com.kapp.tenon.ui.components.SessionStateLabel
import com.kapp.tenon.ui.components.StatusDot
import com.kapp.tenon.ui.components.formatCount
import com.kapp.tenon.ui.components.relativeTime
import com.kapp.tenon.ui.theme.IconSizes
import com.kapp.tenon.ui.theme.LocalAppSpacing
import com.kapp.tenon.ui.theme.LocalCardShape
import com.kapp.tenon.ui.theme.relayCardShadow
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 智能体中心卡片流（Phase B2）：每个 `(宿主, agent)` 实体一张卡，置顶在工作区治理段之上
 * （决策稿 §2 的「两段一保留」——治理段原样保留，页头骨架不动）。
 *
 * ## 数据从哪儿来
 *
 * [com.kapp.tenon.domain.hubAgents] 投影（Phase B1）：合并规则、Urgent-First 排序、
 * 审批到群的归因全在投影里做完，这里只画 —— 一屏的规则只留一处。
 *
 * ## 组件共享（AGENTS §2.6）
 *
 * 卡片**内容**只有这一份，手机与桌面共用（两壳都经 `AgentConsoleScreen` 进来）；
 * 外壳差异（宽度收列、页头、单列还是两栏）留给屏本体。行的形态语言全部复用既有件：
 * [StatusDot] / [MutedTag] / [PendingPill] / [SessionStateLabel] / [AppOutlinedButton]，
 * 容器与看板其余卡片同一套（白卡 + [LocalCardShape] + 自绘投影）。
 *
 * 桌面两栏（决策稿 §2）的左列也画这一份：窄列只收密度（[compact]），不另抄内容。
 */
@Composable
internal fun AgentHubSection(
    cards: List<AgentHubUi>,
    now: Long,
    /** 点卡：进该实体的智能体详情页（Phase B4 的第一落点，实体视角；治理段从详情可达）。 */
    onOpenEntity: (AgentHubUi) -> Unit,
    /** 行尾聊天按钮：既有 1:1 入口。null = 不画按钮（壳没有这条路时）。 */
    onOpenChat: ((AgentHubUi) -> Unit)?,
    /** 点带会话的状态行：进那条会话详情（既有三级入口）。null = 行不可点。 */
    onOpenRowSession: ((hostNodeId: String, sessionId: String) -> Unit)?,
    modifier: Modifier = Modifier,
    /**
     * 窄列密度（桌面两栏左列，[ContentWidths.Sidebar] 宽）：收起头部副行（宿主名 ·
     * 目录/群计数）与角色徽标（单兵/团队 N 人）—— 两处**次级信息**在窄列里只会把
     * 身份行挤成省略号；显示名、在线点、[对话] 动作与 Urgent-First 状态行原样保留。
     * 默认 false = 全宽横段的改前形态。
     */
    compact: Boolean = false,
    /**
     * 画不画本段自己的段头（「智能体 · N」）。
     *
     * 首屏目录把两个视角（员工 / 团队）挂在同一个切换器下，标题与计数由那个切换器
     * 提供 —— 这时再画一遍段头就是同一屏两个标题。默认 true = 独立使用时形态不变。
     */
    showSectionHeader: Boolean = true,
) {
    if (cards.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md),
    ) {
        if (showSectionHeader) {
            SectionHeader(
                text = stringResource(Res.string.hub_agent_section_title),
                action = {
                    Text(
                        text = stringResource(Res.string.agent_count_items, formatCount(cards.size.toLong())),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }
        cards.forEach { card ->
            AgentHubCard(
                card = card,
                now = now,
                compact = compact,
                onOpen = { onOpenEntity(card) },
                onOpenChat = onOpenChat?.let { open -> { open(card) } },
                onOpenRowSession = onOpenRowSession,
            )
        }
    }
}

/**
 * 一张实体卡。头部（头像 + 在线点 + 显示名 + 角色标签 + 聊天按钮）→ 分隔线 →
 * Urgent-First 状态行（投影已排好序，这里照画）。
 *
 * 角色标签与「单兵/团队 N 人」都是**中性徽标**；待审批行的红药丸是全卡唯一的强警示 ——
 * 与工作区行卡片同一分工（[PendingPill] 的既有口径）。
 */
@Composable
private fun AgentHubCard(
    card: AgentHubUi,
    now: Long,
    /** 窄列密度：收起头部副行（次级元信息），身份行与状态行保留 —— 见 [AgentHubSection]。 */
    compact: Boolean,
    onOpen: () -> Unit,
    onOpenChat: (() -> Unit)?,
    onOpenRowSession: ((hostNodeId: String, sessionId: String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val sp = LocalAppSpacing.current
    // **同一套容器语言**（看板组头 BoardGroupHead 同款）：Surface 铺 surface + 全局卡片
    // 圆角 + 自绘投影 —— 不用 Card(elevation = 0.dp)，海拔字面量是已记账的债，新代码不再欠。
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = LocalCardShape.current,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
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
                // 头像槽：空态行同款（surface-2 底 + primary 图标），尺寸走 spacing token。
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
                        StatusDot(card.online)
                        Text(
                            // 显示名缺失 = 「未上报类型」（复用既有文案键），不拿身份键顶替。
                            text = card.displayName
                                ?: stringResource(Res.string.conv_inbox_ws_badge_unknown_kind),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        // 窄列（compact）同样收起这枚角色徽标：它和副行一样是次级信息，
                        // 而窄列里首要的是**名字本身** —— 徽标 + 行尾聊天按钮都留着的话，
                        // 268dp 内容宽里名字只剩省略号。角色与编制在全宽卡与下钻里如实可见。
                        if (!compact) {
                            MutedTag(
                                text = if (card.isTeam) {
                                    stringResource(Res.string.hub_agent_role_team, card.teamSize)
                                } else {
                                    stringResource(Res.string.hub_agent_role_single)
                                },
                            )
                        }
                    }
                    // 窄列（compact）收起这行次级元信息：设备名与目录/群计数在 300dp 列里
                    // 只会把身份行挤成省略号；这张卡要回答的「在不在忙」都在状态行里。
                    if (!compact) {
                        Text(
                            text = hubCardSubtitle(card),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (onOpenChat != null) {
                    AppOutlinedButton(
                        text = stringResource(Res.string.conv_inbox_ws_open_chat),
                        onClick = onOpenChat,
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
                card.rows.forEach { row ->
                    HubStatusRow(card = card, row = row, now = now, onOpenSession = onOpenRowSession)
                }
            }

            // 最近交付（UX 规范 §2.5②）：卡片底部一行。产物只落在消息流里时，
            // 「它刚给了你什么」在卡上完全看不见 —— 这行就是那个回溯入口。
            // 壳没给三级入口时照样画（只是不可点）：文件名本身仍是事实。
            card.latestArtifact?.let { artifact ->
                HubArtifactTag(
                    artifact = artifact,
                    now = now,
                    onOpen = onOpenRowSession?.let { open ->
                        { open(card.hostNodeId, artifact.sessionId) }
                    },
                )
            }
        }
    }
}

/**
 * 卡片底部的「最近交付」标签：文件名 + 相对时刻，整行可点。
 *
 * ## 只报事实，不断言成败
 *
 * `agent-artifact` 帧里没有产物的成败位，所以这行**不写「成功」**：它说的是「最近收到
 * 一件叫 X 的产物、什么时候收到的」，而不是「这次交付成功了」。
 *
 * ## 点击落到会话，不直接拉二进制
 *
 * 这里只有元数据（文件名 / 所属会话 / 收到时刻）；产物本体靠 `fileId` 指的那条
 * `file-msg`，所以点它进**产物所属的那条会话**（产物卡在那里），由既有文件动作去开。
 * 直接把 `fileId` 当可打开路径是另一种编造。
 *
 * ## 为什么保留相对时刻
 *
 * 「最近」是个会腐坏的词：卡上已有「最近活动 3 分钟前」，而产物可能是三天前的。
 * 只写文件名会让两件事看起来同时发生 —— 带上产物自己的时刻才如实。
 */
@Composable
private fun HubArtifactTag(
    artifact: AgentHubArtifactUi,
    now: Long,
    /** null = 壳没有三级入口：标签照画（文件名仍是事实），但不假装可点。 */
    onOpen: (() -> Unit)?,
) {
    val sp = LocalAppSpacing.current
    val clickable = if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(clickable)
            .padding(vertical = sp.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(sp.sm6),
    ) {
        Icon(
            painter = painterResource(Res.drawable.ic_file),
            contentDescription = null,
            modifier = Modifier.size(IconSizes.Label),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(Res.string.hub_artifact_latest_tag, artifact.name),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = relativeTime(artifact.at, now),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** 头部副行：设备名（节点表认识它才画）+ 项目目录数与群数。两段都没有时这句就不画。 */
@Composable
private fun hubCardSubtitle(card: AgentHubUi): String {
    val hostPart = card.hostName?.takeIf { it.isNotBlank() }
    val metaPart = if (card.workspaceCount > 0 || card.groupCount > 0) {
        // 两格都填（0 也是编制事实）；只有两格全空（一个目录没挂、一个群没进）才整句不画。
        stringResource(Res.string.hub_agent_meta_format, card.workspaceCount, card.groupCount)
    } else {
        null
    }
    return listOfNotNull(hostPart, metaPart).joinToString(" · ")
}

/**
 * 一条状态行。整行可点当且仅当它指向具体会话且壳给了三级入口 —— 点卡的语义是
 * 「看这条状态的下钻」，与整卡的「进治理段」分工（决策稿 §4 的卡片动作）。
 */
@Composable
private fun HubStatusRow(
    card: AgentHubUi,
    row: AgentHubStatusRow,
    now: Long,
    onOpenSession: ((hostNodeId: String, sessionId: String) -> Unit)?,
) {
    val sp = LocalAppSpacing.current
    val rowSessionId = row.sessionId
    val rowModifier = if (rowSessionId != null && onOpenSession != null) {
        Modifier
            .fillMaxWidth()
            .clickable { onOpenSession(card.hostNodeId, rowSessionId) }
    } else {
        Modifier.fillMaxWidth()
    }
    Row(
        modifier = rowModifier.padding(vertical = sp.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(sp.sm6),
    ) {
        when (row.kind) {
            AgentHubRowKind.PENDING -> {
                // P1：红药丸 + 可归因的群名。归因不了时只有全局计数 —— 不编造群名（决策稿 §4）。
                PendingPill(text = stringResource(Res.string.agent_subtitle_pending, row.pendingCount))
                if (row.pendingGroupNames.isNotEmpty()) {
                    Text(
                        text = row.pendingGroupNames.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            AgentHubRowKind.BUSY -> {
                // P2：执行态 + 最新事件文案。不显示结构化进度（协议无此字段，决策稿 §4）。
                row.state?.let { state -> SessionStateLabel(state) }
                row.summary?.let { summary ->
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            AgentHubRowKind.RECENT -> {
                // P3：最近活动 + 空闲/离线态（时刻口径在投影层，照画）。
                row.state?.let { state -> SessionStateLabel(state) }
                // **「最新」是一个有条件的断言**（不变量 4）：超过一天就不再叫最新，
                // 对端离线也不叫 —— 三天前那条活动不是「最新」，它只是「最后一条」。
                // 三档文案与判据 `agentActivityFreshness` 一一对应，UI 不自带第四档。
                when (agentActivityFreshness(row.at, now, card.online)) {
                    ActivityFreshness.FRESH -> Text(
                        text = stringResource(
                            Res.string.workspace_latest_activity_format,
                            relativeTime(row.at, now),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    ActivityFreshness.STALE -> Text(
                        text = stringResource(Res.string.hub_activity_last_format, relativeTime(row.at, now)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    ActivityFreshness.OFFLINE -> Text(
                        text = stringResource(Res.string.hub_activity_offline_format, relativeTime(row.at, now)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    // 一条活动都没有：整句不画。0 不是「很久以前」，是「没有」。
                    ActivityFreshness.NONE -> Unit
                }
                // 接力摘要（不变量 4 的正文）：投影已给的最近事件文案，一行，不拼句子。
                row.summary?.let { summary ->
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
