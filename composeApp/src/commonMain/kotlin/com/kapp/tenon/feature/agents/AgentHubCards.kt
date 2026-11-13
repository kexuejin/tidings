// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.kapp.tenon.core.protocol.AVATAR_LEGACY_INITIAL_VALUE
import com.kapp.tenon.domain.ActivityFreshness
import com.kapp.tenon.domain.AgentHubArtifactUi
import com.kapp.tenon.domain.AgentHubRowKind
import com.kapp.tenon.domain.AgentHubUi
import com.kapp.tenon.domain.AgentWorkstationPort
import com.kapp.tenon.domain.AgentWorkstationUi
import com.kapp.tenon.domain.agentActivityFreshness
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.agent_count_items
import com.kapp.tenon.generated.resources.agent_subtitle_pending
import com.kapp.tenon.generated.resources.conv_inbox_ws_badge_unknown_kind
import com.kapp.tenon.generated.resources.conv_inbox_ws_open_chat
import com.kapp.tenon.generated.resources.hub_activity_last_format
import com.kapp.tenon.generated.resources.hub_activity_offline_format
import com.kapp.tenon.generated.resources.hub_agent_meta_format
import com.kapp.tenon.generated.resources.hub_agent_role_team
import com.kapp.tenon.generated.resources.hub_agent_section_title
import com.kapp.tenon.generated.resources.hub_artifact_latest_tag
import com.kapp.tenon.generated.resources.hub_workstation_format
import com.kapp.tenon.generated.resources.ic_chat_bubble
import com.kapp.tenon.generated.resources.ic_file
import com.kapp.tenon.generated.resources.workspace_latest_activity_format
import com.kapp.tenon.ui.components.AppIconButton
import com.kapp.tenon.ui.components.MutedTag
import com.kapp.tenon.ui.components.PeerAvatar
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
 * 智能体中心**名录**（原卡片流）：每个 `(宿主, agent)` 实体一行，同段共用一个容器。
 *
 * ## 为什么是「一个容器里的行」而不是「一叠卡」
 *
 * 卡片流把每个员工包成一张带投影的白卡：一屏放得下三个人，而每一个人的头部结构完全
 * 相同（同样的方块 + 同样的机器人图标）—— 读起来像配置表，认不出「谁是谁」。名录把
 * 容器收成一份、行间只用 hairline 分隔，密度还给身份本身：首字头像（每人一个稳定
 * 底色）+ 名字 + 工位 + 状态。
 *
 * 头像走首字而不是机器人图标：`PeerAvatar` 的 `avatar = AVATAR_LEGACY_INITIAL_VALUE`
 * 就是「首字模式」，底色由名字哈希分配（`AvatarPalette.colorIndex` 的既有口径），
 * 前景按底色亮度二选一（`foregroundOn`，对比度 ≥ 4.58:1）—— 不新造一套配色。
 *
 * ## 数据从哪儿来
 *
 * [com.kapp.tenon.domain.hubAgents] 投影：合并规则、Urgent-First 排序、审批到群的归因
 * 全在投影里做完，这里只画 —— 一屏的规则只留一处。
 *
 * ## 组件共享（AGENTS §2.6）
 *
 * 名录**内容**只有这一份，手机与桌面共用（两壳都经 `AgentConsoleScreen` 进来）；
 * 外壳差异（宽度收列、页头、单列还是两栏）留给屏本体。行的形态语言全部复用既有件：
 * [StatusDot] / [MutedTag] / [PendingPill] / [SessionStateLabel] / [AppIconButton]，
 * 容器与看板其余面板同一套（surface + [LocalCardShape] + 自绘投影）。
 */
@Composable
internal fun AgentHubSection(
    cards: List<AgentHubUi>,
    now: Long,
    /** 点行：进该实体的智能体详情页（实体视角；治理段从详情可达）。 */
    onOpenEntity: (AgentHubUi) -> Unit,
    /** 行尾聊天按钮：既有 1:1 入口。null = 不画按钮（壳没有这条路时）。 */
    onOpenChat: ((AgentHubUi) -> Unit)?,
    /** 点「正在执行」那行摘要：进那条会话详情（既有三级入口）。null = 不可点。 */
    onOpenRowSession: ((hostNodeId: String, sessionId: String) -> Unit)?,
    modifier: Modifier = Modifier,
    /**
     * 窄列密度（桌面两栏左列，[ContentWidths.Sidebar] 宽）：收起**次级**信息 ——
     * 角色徽标（团队 N 人）、「目录/群」计数、「正在执行」摘要与最近活动时刻；
     * 显示名、工位、待批/执行态与行尾动作原样保留。默认 false = 全宽横段的形态。
     */
    compact: Boolean = false,
    /**
     * 画不画本段自己的段头（「智能体 · N」）。
     *
     * 首屏目录把一个切换器挂在两段之上，标题与计数由那个切换器提供 —— 这时再画一遍
     * 段头就是同一屏两个标题。默认 true = 独立使用时形态不变。
     */
    showSectionHeader: Boolean = true,
    /** 当前选中的宿主与身份键（两栏主从联动高亮）。null = 无选中。 */
    selectedHostNodeId: String? = null,
    selectedAgentKey: String? = null,
    /**
     * 员工工位端口：名录副行上「用什么驱动干活」。
     *
     * null = 读不到本机执行绑定（手机端常态）⇒ 副行只写宿主名。**不画占位**：
     * 「不知道用什么工具」与「这台机器」是两件事，前者不该在行上留一个空格子。
     */
    workstationPort: AgentWorkstationPort? = null,
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
        AgentRoster(count = cards.size) { index ->
            val card = cards[index]
            AgentHubRow(
                card = card,
                now = now,
                compact = compact,
                selected = card.hostNodeId == selectedHostNodeId && card.agentKey == selectedAgentKey,
                // 工位是**读一次就够**的事实：在行里按 (宿主, 身份键) 问端口，不预先攒一张表 ——
                // 端口背后是内存里的执行档，没有 IO；攒表反而要维护一份会过期的缓存。
                workstation = workstationPort?.workstationOf(card.hostNodeId, card.agentKey.orEmpty()),
                onOpen = { onOpenEntity(card) },
                onOpenChat = onOpenChat?.let { open -> { open(card) } },
                onOpenRowSession = onOpenRowSession,
            )
        }
    }
}

/**
 * 名录外框：**一个**容器装下所有行，行与行之间只隔一条 hairline。
 *
 * 员工名录与团队名录共用这一份（同一屏里两块面板必须同形；各写一份必然漂）。
 * 容器语言与看板组卡一致：surface 底 + 全局卡片圆角 + 自绘投影 —— 不用
 * `Card(elevation = 0.dp)`，海拔字面量是已记账的债，新代码不再欠。
 */
@Composable
internal fun AgentRoster(
    count: Int,
    modifier: Modifier = Modifier,
    row: @Composable (Int) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = LocalCardShape.current,
        modifier = modifier.fillMaxWidth().relayCardShadow(),
    ) {
        Column {
            for (index in 0 until count) {
                if (index > 0) HorizontalDivider()
                row(index)
            }
        }
    }
}

/**
 * 名录里的一行员工。
 *
 * ## 两种密度，同一批事实
 *
 * - **窄列**（[compact]，桌面两栏左列 [ContentWidths.Sidebar] 宽）：两行 —— 名字行（含
 *   待批 / 执行态）与工位行。最近活动在窄列里不画：它与工位行抢的是同一段宽度，画上去
 *   的结果是名字被截成 `cod…`（实测 300dp 列里只给名字留 58dp）。窄列的第一要务是
 *   **认得出这是谁**，状态次之，时刻再次之。
 * - **宽态**（手机 / 平板 / 单列整幅）：名字行 + 工位行 + 「正在执行」摘要或最近交付，
 *   状态与时刻竖排在右侧两格。宽态画得下，就不该为窄列的形状让步。
 *
 * 两种密度不是两套内容：头像、名字、工位、待批、执行态在两种密度里都在。
 *
 * ## 「正在执行」与「最近交付」共用第三行
 *
 * 两者都是「它此刻产出过什么」，同一时刻只画一条（执行中是更新的那件事）。两条都在
 * 详情页里完整可见。
 */
@Composable
private fun AgentHubRow(
    card: AgentHubUi,
    now: Long,
    compact: Boolean,
    selected: Boolean,
    /** 工位（驱动 + 机器）；null = 读不到 —— 副行只写宿主名，不写占位。 */
    workstation: AgentWorkstationUi?,
    onOpen: () -> Unit,
    onOpenChat: (() -> Unit)?,
    onOpenRowSession: ((hostNodeId: String, sessionId: String) -> Unit)?,
) {
    val sp = LocalAppSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selected) {
                    Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onOpen)
            .padding(horizontal = sp.lg, vertical = sp.md10),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(sp.md),
    ) {
        PeerAvatar(
            // 无名实体（连身份键都没有）不拿身份键顶替：空串进首字模式会退成「茶」。
            name = card.displayName.orEmpty(),
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
                StatusDot(card.online)
                Text(
                    // 显示名缺失 = 「未上报类型」（复用既有文案键），不拿身份键顶替。
                    text = card.displayName
                        ?: stringResource(Res.string.conv_inbox_ws_badge_unknown_kind),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // 团队人数是这条「员工」的编制事实；单兵不挂标签 —— 没有人数可报，
                // 一枚「单兵」徽标只是把「它不是团队」再说一遍。
                if (!compact && card.isTeam) {
                    MutedTag(text = stringResource(Res.string.hub_agent_role_team, card.teamSize))
                }
                if (compact) HubStatusSlot(card)
            }
            val meta = hubRowMeta(card, compact, workstation)
            if (meta != null) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!compact) {
                val busySessionId = card.rows.firstOrNull { it.kind == AgentHubRowKind.BUSY }?.sessionId
                val busySummary = card.rows.firstOrNull { it.kind == AgentHubRowKind.BUSY }?.summary
                if (busySummary != null) {
                    Text(
                        text = busySummary,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (busySessionId != null && onOpenRowSession != null) {
                            Modifier.clickable { onOpenRowSession(card.hostNodeId, busySessionId) }
                        } else {
                            Modifier
                        },
                    )
                } else {
                    // 最近交付（UX 规范 §2.5②）：它刚给了你什么 —— 只在没有「执行中」时占这一行。
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
        if (!compact) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(sp.xxs),
            ) {
                HubStatusSlot(card)
                HubActivityLine(at = card.lastActivityAt, online = card.online, now = now)
            }
        }
        if (onOpenChat != null) {
            // 行尾只放一颗图标按钮：行本身已可点（进详情），「对话」是唯一的次要动作。
            AppIconButton(
                icon = Res.drawable.ic_chat_bubble,
                contentDescription = stringResource(Res.string.conv_inbox_ws_open_chat),
                onClick = onOpenChat,
                modifier = Modifier.size(sp.xxl),
            )
        }
    }
}

/**
 * 一行的**状态格**：待批药丸优先，其次执行态；两者都没有时整格不画。
 *
 * 只有一个来源（`pendingCount` 与 `rows` 的 BUSY 都是投影算好的），UI 不自己判「忙不忙」。
 */
@Composable
private fun HubStatusSlot(card: AgentHubUi) {
    if (card.pendingCount > 0) {
        // P1：待批是全行唯一的强警示（实心药丸），优先于执行态。
        PendingPill(text = stringResource(Res.string.agent_subtitle_pending, card.pendingCount))
    } else {
        card.rows.firstOrNull { it.kind == AgentHubRowKind.BUSY }?.state?.let { state ->
            SessionStateLabel(state)
        }
    }
}

/**
 * 状态列的**下格**：最近活动的时刻。
 *
 * 「最新」是一个有条件的断言（不变量 4）：超过一天就不再叫最新，对端离线也不叫 ——
 * 三天前那条活动不是「最新」，它只是「最后一条」。三档文案与判据
 * [agentActivityFreshness] 一一对应，UI 不自带第四档；一条活动都没有时整句不画
 * （0 不是「很久以前」，是「没有」）。
 */
@Composable
internal fun HubActivityLine(at: Long, online: Boolean, now: Long) {
    when (agentActivityFreshness(at, now, online)) {
        ActivityFreshness.FRESH -> Text(
            text = stringResource(Res.string.workspace_latest_activity_format, relativeTime(at, now)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        ActivityFreshness.STALE -> Text(
            text = stringResource(Res.string.hub_activity_last_format, relativeTime(at, now)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        ActivityFreshness.OFFLINE -> Text(
            text = stringResource(Res.string.hub_activity_offline_format, relativeTime(at, now)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        ActivityFreshness.NONE -> Unit
    }
}

/**
 * 行内副行：**工位**（驱动 + 宿主名）+ 项目目录数与群数。
 *
 * ## 三段各自的「缺失就整段不写」
 *
 * - 驱动名读不到（端口 null / 非本机 / 多值）⇒ 只写宿主名 —— **不写「未知」**：
 *   这是「本端读不到」，不是「这个员工没有底座」，两者在界面上不该长得一样。
 * - 宿主名读不到（节点表还不认识这台宿主）⇒ 不拿 hostId 顶替 —— 身份键不是名字，
 *   与 `AgentSessionUi.displayTitle` 不退回 sessionId 是同一条纪律。此时驱动名照写：
 *   它自己是完整的事实。
 * - 目录数与群数只在宽态写（窄列放不下），两格都为 0 才整句不画。
 *
 * 三段都没有时整行不画（不是画一行空文本）。
 */
@Composable
private fun hubRowMeta(
    card: AgentHubUi,
    compact: Boolean,
    workstation: AgentWorkstationUi?,
): String? {
    val hostPart = card.hostName?.takeIf { it.isNotBlank() }
    // 驱动名优先写真名（`Codex`），档案表里没有这个名字才退回键本身 —— 键是事实，
    // 编一个展示名不是。两者都没有 ⇒ 这一段不写。
    val driverPart = workstation?.let { ws ->
        (ws.driverLabel?.takeIf { it.isNotBlank() } ?: ws.driverKey)
            .takeIf { it.isNotBlank() }
    }
    val stationPart = when {
        driverPart != null && hostPart != null ->
            stringResource(Res.string.hub_workstation_format, driverPart, hostPart)
        driverPart != null -> driverPart
        else -> hostPart
    }
    val metaPart = if (!compact && (card.workspaceCount > 0 || card.groupCount > 0)) {
        // 两格都填（0 也是编制事实）；只有两格全空（一个目录没挂、一个群没进）才整句不画。
        stringResource(Res.string.hub_agent_meta_format, card.workspaceCount, card.groupCount)
    } else {
        null
    }
    return listOfNotNull(stationPart, metaPart).joinToString(" · ").ifBlank { null }
}

/**
 * 「最近交付」一行：文件名 + 相对时刻，整行可点。
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
 * 「最近」是个会腐坏的词：行上已有「最近活动 3 分钟前」，而产物可能是三天前的。
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
            .then(clickable),
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
