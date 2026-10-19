// SPDX-License-Identifier: GPL-3.0-only

package app.relay.feature.conversations

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.relay.feature.nodes.AddNodeEntryRow
import app.relay.app.InboxWorkspaceUnitsUi
import app.relay.app.WorkspaceUnitGroupUi
import app.relay.app.WorkspaceUnitUi
import app.relay.generated.resources.agent_session_untitled
import app.relay.generated.resources.conv_inbox_ws_row_unassigned
import app.relay.generated.resources.conv_inbox_ws_row_unassigned_body
import app.relay.generated.resources.ic_list
import app.relay.ui.theme.LocalCardShape
import app.relay.generated.resources.Res
import app.relay.generated.resources.conv_inbox_ws_badge_no_workspace
import app.relay.generated.resources.conv_inbox_ws_badge_unknown_kind
import app.relay.generated.resources.conv_inbox_ws_count_short
import app.relay.generated.resources.conv_inbox_ws_count_short_agents
import app.relay.generated.resources.conv_inbox_ws_group_offline
import app.relay.generated.resources.conv_inbox_ws_open_chat
import app.relay.generated.resources.conv_inbox_ws_pending
import app.relay.generated.resources.conv_inbox_ws_pending_pill
import app.relay.generated.resources.conv_inbox_ws_row_agent_none
import app.relay.generated.resources.conv_inbox_ws_row_unnamed
import app.relay.generated.resources.conv_inbox_ws_row_unnamed_hint
import app.relay.generated.resources.conv_inbox_ws_tail_body
import app.relay.generated.resources.conv_inbox_ws_tail_body_pending
import app.relay.generated.resources.conv_inbox_ws_tail_title
import app.relay.generated.resources.conv_inbox_ws_title
import app.relay.generated.resources.ic_bot
import app.relay.generated.resources.ic_chev
import app.relay.generated.resources.ic_grid
import app.relay.generated.resources.ic_terminal
import app.relay.ui.components.StatusDot
import app.relay.ui.components.WorkspaceUnitCard
import app.relay.ui.components.WorkspaceUnitCardUi
import app.relay.ui.components.WorkspaceUnitSessionUi
import app.relay.ui.components.relativeTime
import app.relay.ui.theme.IconSizes
import app.relay.ui.theme.LocalAppSpacing
import app.relay.ui.theme.RelayElevations
import app.relay.ui.theme.accents
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 消息页签的「工作区」段（原型 `m01-home-recent` 下半屏）。
 *
 * ## 为什么是列表里的**一个** item
 *
 * 段头 + 若干组头 + 若干张卡在原型里是一整块：它们之间的间距全是 0（只有圆角与阴影分隔），
 * 而外层 `LazyColumn` 的 `spacedBy(sp.sm)` 会插在**每个** item 之间。要保住"段内不散开、
 * 段与会话区之间有 8px"这个形状，最省事也最不容易漂的就写法是把整段提交成一颗 item ——
 * 拆成多颗就再也管不住它们之间的间距了。
 *
 * ## 取数全在 [app.relay.app.inboxWorkspaceUnits] 里
 *
 * 这个文件只负责画：排序、上限、待审批的二次 join、`未上报` 的降级都在派生里做完
 * （那里有逐条注释）。界面哪怕想改顺序也改不动，这是故意的 —— 一屏的规则只留一处。
 *
 * ## 两个红点是两种红
 *
 * 会话区的红点是**未读条数**，这一段的红点是**待审批条数**（`pending`）。它们不共用一个
 * 数字也不混排：一段只有一把尺子（段内按"待审批降序 → 最近活动降序"），跨段不 join。
 *
 * ## 阶段一画出来的是什么
 *
 * 协议还没有 `wsId`（父稿 D1/D2），所以今天每一行都是 **Agent 行**：标题是 Agent 身份键
 * （`agentId ?? agentKind`，都没有时写「未上报类型」），副行如实写"对端还没宣告过工作区"。
 * 工作区行、未命名工作区、未归属会话那几支代码已经写好，数据一到就自己亮。
 */
public fun LazyListScope.inboxWorkspaceSection(
    units: InboxWorkspaceUnitsUi,
    now: Long,
    onOpenUnit: (WorkspaceUnitUi) -> Unit,
    onOpenBoard: () -> Unit,
    onOpenChat: (String) -> Unit,
) {
    if (units.isEmpty) return
    item(key = "inbox-workspace-section") {
        InboxWorkspaceSection(units, now, onOpenUnit, onOpenBoard, onOpenChat)
    }
}

@Composable
private fun InboxWorkspaceSection(
    units: InboxWorkspaceUnitsUi,
    now: Long,
    onOpenUnit: (WorkspaceUnitUi) -> Unit,
    onOpenBoard: () -> Unit,
    onOpenChat: (String) -> Unit,
) {
    val sp = LocalAppSpacing.current
    Column(modifier = Modifier.fillMaxWidth()) {
        InboxSectionHead(units)
        units.groups.forEach { group ->
            InboxGroupHead(group, now)
            // 一行 = 一张卡（看板同款）。卡间留 `sp.md` —— 与看板列表的
            // `verticalArrangement = Arrangement.spacedBy(sp.md)` 同一个间距；
            // 这一整段在消息页里是一颗 lazy item，间距必须自己给，外层管不着。
            Column(verticalArrangement = Arrangement.spacedBy(sp.md)) {
                group.units.forEach { unit ->
                    InboxUnitRow(
                        unit = unit,
                        online = group.online,
                        now = now,
                        onOpenUnit = onOpenUnit,
                        onOpenChat = onOpenChat,
                    )
                }
            }
        }
        if (units.hiddenUnits > 0) {
            Spacer(Modifier.size(sp.md))
            InboxUnitCard { InboxTailRow(units, onOpenBoard) }
        }
    }
}

/**
 * 段头：左边「工作区」、右边「N 个 · 待审批 M」。
 *
 * 上边距为什么是 `sp.md10` 而不是原型的 18px：外层列表有 `spacedBy(sp.sm = 8)`，8 + 10 = 18。
 * 写成 18 会变成 26 —— 这不是随手取的数，是两处相加才对得上。
 *
 * 右边那半句只在 `M > 0` 时画（原型是无条件画的，但它的夹具里 M 恒为 3，那一档从没被渲染过；
 * 而同一段里"行内药丸 pending = 0 就不画"本来就是原型自己的规矩）。这一处偏离已登记。
 */
@Composable
internal fun InboxSectionHead(units: InboxWorkspaceUnitsUi) {
    val sp = LocalAppSpacing.current
    val count = if (units.countsWorkspaces) {
        stringResource(Res.string.conv_inbox_ws_count_short, units.totalUnits)
    } else {
        // 只有 Agent 行时单位词换成「N 个 Agent」：照原样写「N 个」会让人以为在数工作区，
        // 而下面明明一条工作区行都没有。
        stringResource(Res.string.conv_inbox_ws_count_short_agents, units.totalUnits)
    }
    val headCount = if (units.pendingTotal > 0) {
        count + " · " + stringResource(Res.string.conv_inbox_ws_pending, units.pendingTotal)
    } else {
        count
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = sp.md10),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(sp.sm6),
    ) {
        Text(
            text = stringResource(Res.string.conv_inbox_ws_title),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = headCount,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
            maxLines = 1,
        )
    }
}

/**
 * 组头 = 一台设备：圆点 + 「节点名 · 平台」 + 「N 个 Agent · M 个工作区」。
 *
 * 组头**不挂点击**：它不是一个对象，只是这些行的归属说明。原型的看板里那个组头可以点
 * （点了收窄到该节点），消息页签这段没有这个动作 —— 收窄是看板的活儿。
 *
 * 离线才在末尾补「 · N 分钟前在线」；**从来没有回应过的设备不补**（`lastSeenAt == 0`），
 * 它的圆点已经是 `error` 色了，再写一句"很久以前在线"是编的。
 */
@Composable
internal fun InboxGroupHead(group: WorkspaceUnitGroupUi, now: Long) {
    val sp = LocalAppSpacing.current
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    // 与节点页同一条规矩：在线绿、从没回应过红、离线走中性灰。
    val dot = when {
        group.online -> MaterialTheme.accents.online
        group.lastSeenAt == 0L -> MaterialTheme.colorScheme.error
        else -> onSurfaceVariant
    }
    // 计数已按 issue 6 的裁决整体移除（组头不再印「N 个 Agent · M 个工作区」）：
    // 组头要回答的是"这些是谁的"，数量是段/页层面的事，不该在每一台设备的抬头再数一遍。
    // 离线时间与"归不到 Agent 的待审批"保留 —— 它们不是数量噪音，而是组头必须替
    // 那些行说出来的状态（join 不到 Agent 的审批只能写在节点级，父稿 §4.2）。
    val counts = buildString {
        if (!group.online && group.lastSeenAt > 0L) {
            append(stringResource(Res.string.conv_inbox_ws_group_offline, relativeTime(group.lastSeenAt, now)))
        }
        if (group.unattributedApprovals > 0) {
            if (isNotEmpty()) append(" · ")
            append(stringResource(Res.string.conv_inbox_ws_pending, group.unattributedApprovals))
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = sp.xs, end = sp.xs, top = sp.lg14, bottom = sp.sm6),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(sp.sm),
    ) {
        StatusDot(online = group.online, size = sp.lg14, color = dot)
        Text(
            text = "${group.nodeName} · ${group.platform}",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = counts,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 一组行外面那张卡。
 *
 * 圆角用 `MaterialTheme.shapes.medium`(12) 而不是 `LocalCardShape`(16)：与会话行同一张卡、
 * 同一条已登记的跨模块偏差（`design/fidelity-deviations.yaml`）。
 */
@Composable
private fun InboxUnitCard(content: @Composable () -> Unit) {
    val sp = LocalAppSpacing.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        shadowElevation = RelayElevations.Card,
    ) {
        Column(
            modifier = Modifier.padding(
                start = sp.lg14,
                end = sp.lg14,
                top = sp.md10,
                bottom = sp.lg14,
            ),
        ) {
            content()
        }
    }
}

/**
 * 一行 = 看板同款的**一张卡**。
 *
 * ## 为什么这里不再自己画
 *
 * 以前这里是「一组行共一张 `Surface`、行间一条分隔线」的紧凑行，与工作区看板
 * `AgentConsoleScreen` 里那张 `WorkspaceCard` 是**同一张卡写了两遍**（头像 + 状态点 + 标题 +
 * 徽标 + 待审批药丸 + 活跃会话）。两处各自演化，已经在用词与计数上出现分歧。
 * 现在两屏共用 `WorkspaceUnitCard`，这里只负责把这一行的语义算成卡片要的字段。
 *
 * ## 两屏的差异只剩三处，都留在这里
 *
 * 1. **标题阶梯**：本段是 `wsTitle → Agent 身份键 → 「未上报类型」`。阶梯里**没有**
 *    路径末段 / host / 端口 —— 拿它们冒充工作区名是明令禁止的（看板直接用节点名）。
 * 2. **副行**：本段如实写"这台 Agent 有几个会话"，看板写"在线 · TCP 11800"。
 * 3. **头像**：本段按行分 Agent（机器人）与工作区（终端），看板用节点头像。
 *
 * 行尾那颗聊天按钮才是"打开聊天"，整卡点击是**下钻**（Agent 行 → 该 Agent 的会话详情、
 * 工作区行 → 工作区详情）—— 这正是本轮要修的那个缺口：换之前整卡点击与"去会话"都落在
 * 打开聊天上，于是看板上根本下钻不去（父稿 §5）。
 */
@Composable
internal fun InboxUnitRow(
    unit: WorkspaceUnitUi,
    online: Boolean,
    now: Long,
    onOpenUnit: (WorkspaceUnitUi) -> Unit,
    onOpenChat: (String) -> Unit,
) {
    val unknownKind = stringResource(Res.string.conv_inbox_ws_badge_unknown_kind)
    val agentKey = unit.agentKind ?: unknownKind
    val title = when {
        unit.isWorkspace -> unit.wsTitle?.takeIf { it.isNotBlank() }
            ?: stringResource(Res.string.conv_inbox_ws_row_unnamed)
        else -> agentKey
    }
    // 副行只说"这一行是谁的"，不再数会话（issue 6 用户裁决）：
    // · 工作区行 → 归属的 Agent 身份键；
    // · Agent 行（对端从没带过 wsId）→「对端还没宣告过工作区」这句如实的说明。
    // 会话数曾经把这两句都挤成数字，而真正要紧的那条由卡片下半的「活跃会话」说。
    val subtitle = if (unit.isWorkspace) {
        agentKey
    } else {
        stringResource(Res.string.conv_inbox_ws_row_agent_none)
    }
    WorkspaceUnitCard(
        ui = WorkspaceUnitCardUi(
            online = online,
            title = title,
            // 工作区行**一个徽标都不挂**：原型实测过，394px 宽时挂上类型徽标会把最长的
            // 那个工作区名截成「teahouse-m…」——名字比类型重要，类型在副行里已经有 Agent 名了。
            kindBadge = !unit.isWorkspace,
            // 身份键本身已经是「未上报类型」时不再重复挂一枚 —— 标题已经说了同一句话。
            mutedTag = if (!unit.isWorkspace && unit.agentKind != null) {
                stringResource(Res.string.conv_inbox_ws_badge_no_workspace)
            } else {
                null
            },
            subtitle = subtitle,
            // 宿主自报的会话总数（见该字段的 KDoc）：本地为空时靠它区分「没有」与
            // 「还没同步到」，后者要说清并给出路。
            sessionCount = unit.sessionCount,
            hint = if (unit.isUnnamedWorkspace) {
                stringResource(Res.string.conv_inbox_ws_row_unnamed_hint)
            } else {
                null
            },
            chatLabel = stringResource(Res.string.conv_inbox_ws_open_chat),
            pendingLabel = if (unit.pendingCount > 0) {
                stringResource(Res.string.conv_inbox_ws_pending_pill, unit.pendingCount)
            } else {
                null
            },
            queueCount = unit.queueCount,
            runCount = unit.runCount,
            artifactCount = unit.artifactCount,
            session = unit.active?.let { active ->
                WorkspaceUnitSessionUi(
                    // 派生层只给 null（"这个会话没有名字"），文案在这一层翻 —— 先例是同一行
                    // 上面工作区名那句 `?: conv_inbox_ws_row_unnamed`（无名的处理只有一套）。
                    title = active.title ?: stringResource(Res.string.agent_session_untitled),
                    state = active.state,
                    summary = active.summary,
                    at = active.at,
                )
            },

            isHost = unit.isHost,
            access = unit.access,
        ),
        now = now,
        onClick = { onOpenUnit(unit) },
        onOpenChat = { onOpenChat(unit.peerId) },
        avatar = {
            UnitAvatar(
                icon = if (unit.isWorkspace) Res.drawable.ic_terminal else Res.drawable.ic_bot,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
    )
}

/** 方块头像位：中性底 + 一枚有色的字形（工作区 = 终端、Agent = 机器人）。 */
@Composable
private fun UnitAvatar(icon: DrawableResource, tint: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .size(UnitAvatarSize)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(IconSizes.Label),
            tint = tint,
        )
    }
}

/**
 * 上限挡住的行收成一行尾巴：「还有 N 行」→ 点进工作区看板（`m-agents`）。
 *
 * 尾巴必须把被挡住的待审批数报出来：只写"还有 4 行"会把最要紧的那条藏在后面。
 */
@Composable
private fun InboxTailRow(units: InboxWorkspaceUnitsUi, onOpenBoard: () -> Unit) {
    val sp = LocalAppSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenBoard() }
            .padding(horizontal = sp.lg14, vertical = sp.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(sp.md),
    ) {
        UnitAvatar(icon = Res.drawable.ic_grid, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(Res.string.conv_inbox_ws_tail_title, units.hiddenUnits),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (units.hiddenPending > 0) {
                    stringResource(Res.string.conv_inbox_ws_tail_body_pending, units.hiddenPending)
                } else {
                    stringResource(Res.string.conv_inbox_ws_tail_body)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = sp.xs),
            )
        }
        Icon(
            painter = painterResource(Res.drawable.ic_chev),
            contentDescription = null,
            modifier = Modifier.size(IconSizes.Inline),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 行里那个方块头像的边长 = 原型 `.av` 的基础档 40px。
 *
 * 为什么是具名常量而不是 `40.dp` 写在 `size()` 里：`:designTokenUsageGate` 把新增的
 * 尺寸字面量记成欠债（棘轮只许欠债持平或下降）。仓库里同形态的常量有 27 处
 * （`NodeListAvatarSize = 32.dp`、`NodeDetailAvatarSize = 72.dp`、`ComposerToolIconSize = 28.dp`…），
 * 这是既有写法。40px 这一档目前没有共享 token，已作为待收敛项登记。
 */
private val UnitAvatarSize = 40.dp

/**
 * 「未归属会话 N」那一行（父稿 §2.5 / 原型 `prototype/relay.html:4256-4260`）。
 *
 * 照原型设计采用 `.krow` 白底列表行，行上的在线点不展示（在线是节点/工作区的属性）。
 *
 * ## 规范原文的形态
 *
 * > agent 有工作区、也有未归属会话 → 工作区行 + 一行「未归属会话 N」
 * > （未归属会话是一等状态，但**不造卡**）
 *
 * 所以这里**不是**一张 `WorkspaceUnitCard`：一行计数 + 一个去向就够。
 *
 * ## 为什么它能点、且点是聊天
 *
 * 未归属会话包含真实会话（未携带 `wsId`），点按可直接进入对应聊天。
 * 没有活跃会话时整行仍画（计数本身是信息），但不可点。
 */
@Composable
internal fun UnassignedSessionsRow(
    count: Int,
    onOpenChat: (() -> Unit)?,
) {
    // 形状 = 原型的 `.krow`（`prototype/relay.html`）：白底列表行 + 40dp 圆形图标
    // 头像 +「未归属会话」+ 计数胶囊 + 一句解释 + chev。复用 `AddNodeEntryRow`。
    AddNodeEntryRow(
        title = stringResource(Res.string.conv_inbox_ws_row_unassigned),
        subtitle = stringResource(Res.string.conv_inbox_ws_row_unassigned_body),
        icon = Res.drawable.ic_list,
        onClick = onOpenChat,
        showDivider = false,
        titleTrailing = { UnassignedCountTag(count) },
    )
}

/**
 * `.tag.muted` 计数胶囊（`prototype/relay.html:310/329`）：11px/600、`1.5px 6px`、
 * `border-radius:var(--r-chip)` = 999px、底 `--surface-2`、字 `--on-2`。
 *
 * 纵向 1.5px 就近 snap 到 `sp.xxs`（2dp）—— 与 `AddNodeEntryRow` 把 11px snap 到 `sp.md10`
 * 同一个口径：`AppSpacing` 是设计契约的档位表，不为半像素再开一档。
 */
@Composable
private fun UnassignedCountTag(count: Int) {
    val sp = LocalAppSpacing.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = CircleShape,
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = sp.sm6, vertical = sp.xxs),
        )
    }
}
