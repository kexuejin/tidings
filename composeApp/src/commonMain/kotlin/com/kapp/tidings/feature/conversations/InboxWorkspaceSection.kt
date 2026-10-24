// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.conversations

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.dp
import com.kapp.tidings.feature.nodes.AddNodeEntryRow
import com.kapp.tidings.domain.WorkspaceUnitUi
import com.kapp.tidings.generated.resources.agent_session_untitled
import com.kapp.tidings.generated.resources.conv_inbox_ws_row_unassigned
import com.kapp.tidings.generated.resources.conv_inbox_ws_row_unassigned_body
import com.kapp.tidings.generated.resources.ic_list
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.conv_inbox_ws_badge_no_workspace
import com.kapp.tidings.generated.resources.conv_inbox_ws_badge_unknown_kind
import com.kapp.tidings.generated.resources.conv_inbox_ws_open_chat
import com.kapp.tidings.generated.resources.conv_inbox_ws_pending_pill
import com.kapp.tidings.generated.resources.conv_inbox_ws_row_agent_none
import com.kapp.tidings.generated.resources.conv_inbox_ws_row_unnamed
import com.kapp.tidings.generated.resources.conv_inbox_ws_row_unnamed_hint
import com.kapp.tidings.generated.resources.ic_bot
import com.kapp.tidings.generated.resources.ic_terminal
import com.kapp.tidings.ui.components.WorkspaceUnitCard
import com.kapp.tidings.ui.components.WorkspaceUnitCardUi
import com.kapp.tidings.ui.components.WorkspaceUnitSessionUi
import com.kapp.tidings.ui.theme.IconSizes
import com.kapp.tidings.ui.theme.LocalAppSpacing
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/*
 * 消息页签里「工作区」行与「未归属会话」行的家 —— 只剩**被引用**的两件：
 * [InboxUnitRow]（工作区看板 `AgentWorkspaceBoard` 复用）与 [UnassignedSessionsRow]（同）。
 *
 * ## 为什么这个文件瘦了一大圈
 *
 * 这里曾经住着整段「工作区」节的渲染（`LazyListScope.inboxWorkspaceSection` 扩展 +
 * 私有 `InboxWorkspaceSection` + 段头/组头/组卡/尾巴行）。会话列表改为混排 feed 后
 * （`InboxFeedItem` / `WorkspaceFeedRow`，见 `ConversationRow.kt` 与
 * `ConversationListPanel.kt`），那条扩展**一个调用点都不剩**（逐符号 grep 证实：
 * `inboxWorkspaceSection` 全仓 0 引用，段头/组头/组卡/尾巴只被那条死链自己引用），
 * 于是整条死链被删除 —— 唯一没有写代码的代码，是永远不需要改的代码。
 *
 * ## 取数仍在 `com.kapp.tidings.domain.inboxWorkspaceUnits` 里
 *
 * 排序、上限、待审批的二次 join、`未上报` 的降级都在派生里做完（那里有逐条注释）。
 * 界面哪怕想改顺序也改不动，这是故意的 —— 一屏的规则只留一处。
 *
 * ## 两个红点是两种红
 *
 * 会话区的红点是**未读条数**，工作区段的红点是**待审批条数**（`pending`）。它们不共用
 * 一个数字也不混排：一段只有一把尺子，跨段不 join。
 */
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
 * 2. **副行**：本段如实写"这一行是谁的"，看板写"在线 · TCP 11800"。
 * 3. **头像**：本段按行分 Agent（机器人）与工作区（终端），看板用节点头像。
 *
 * 行尾那颗聊天按钮才是"打开聊天"，整卡点击是**下钻**（Agent 行 → 该 Agent 的会话详情、
 * 工作区行 → 工作区详情）—— 换之前整卡点击与"去会话"都落在打开聊天上，看板上根本
 * 下钻不去（父稿 §5）。
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
