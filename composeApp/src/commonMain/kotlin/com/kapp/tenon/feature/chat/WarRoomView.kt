// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kapp.tenon.domain.AssistantQuestCardPayload
import com.kapp.tenon.domain.QuestStreamState
import com.kapp.tenon.domain.QuestStreamUnavailableReason
import com.kapp.tenon.ui.theme.LocalAppSpacing

/**
 * 支线作战室**整页**（真机验收反馈的整页化）。
 *
 * ## 为什么从「内嵌面板」改成独立一页
 *
 * 以前 quest 分支 = 面包屑 + 一张贴在消息列表上方的终端卡 + 原样的消息列表 ——
 * 真机上终端被挤成一条缝，机主原话是「作战室在哪」。现在 quest 分支的主体
 * 就是这间房：状态行（任务卡状态 + 动作按钮）+ 全高终端 + 补丁审查。
 * 任务卡时间线没有丢：面包屑回退就是会话（卡片都在那里），三条导航层是
 * 会话 → 作战室 → 白盒。
 *
 * 组件复用（§2.6 内容还是外壳）：状态胶囊与动作按钮组复用消息列表那一份
 * （[CardStateTag] / [CardButtonGroupRow]），终端流复用 [QuestTerminalContent] ——
 * 同一张卡、同一条流，在两个壳里必须长一个样。
 *
 * `card == null`（进入作战室但首条状态通知还没到）时状态行整行不画 ——
 * 宁可少一行，也不画一个编造的「未知状态」。
 */
@Composable
internal fun WarRoomView(
    card: AgentCardUi?,
    state: QuestStreamState,
    unavailableReason: QuestStreamUnavailableReason?,
    lines: List<String>,
    patchText: String?,
    onCardAction: (actionId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sp = LocalAppSpacing.current
    Column(modifier = modifier) {
        if (card != null) {
            WarRoomStatusRow(card = card, onCardAction = onCardAction)
        }
        QuestTerminalContent(
            state = state,
            unavailableReason = unavailableReason,
            lines = lines,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = sp.lg),
        )
        if (patchText != null) {
            // 有界高度：终端吃掉 weight(1f) 之后，补丁审查卡拿固定上限 ——
            // LazyColumn 在无界高度里会撑满整列，把终端挤出屏。
            QuestDiffReviewPanel(
                patchText = patchText,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = sp.lg, vertical = sp.xs)
                    .heightIn(max = 280.dp),
            )
        }
    }
}

/**
 * 状态行：状态胶囊 + 任务标题 + 最近一条摘要 + 动作按钮组（与消息列表卡同一份）。
 *
 * OPEN_QUEST 在行里被摘掉：人在作战室里再给一颗「进入作战室」是死按钮 ——
 * 按钮在骗人（DeadButtonGuard 同一条纪律）。
 */
@Composable
private fun WarRoomStatusRow(
    card: AgentCardUi,
    onCardAction: (actionId: String) -> Unit,
) {
    val sp = LocalAppSpacing.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = sp.lg, vertical = sp.sm),
        verticalArrangement = Arrangement.spacedBy(sp.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(sp.xs)) {
            CardStateTag(card)
            Text(
                text = card.title?.takeIf { it.isNotBlank() } ?: card.cardId,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        card.context?.takeIf { it.isNotBlank() }?.let { summary ->
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val warRoomActions = card.actions.filterNot { action ->
            action.actionId == AssistantQuestCardPayload.Actions.OPEN_QUEST || action.actionId == "open_quest"
        }
        if (warRoomActions.isNotEmpty()) {
            CardButtonGroupRow(
                card = card.copy(actions = warRoomActions),
                pendingActions = emptySet(),
                actionsEnabled = true,
                onAction = { _, actionId -> onCardAction(actionId) },
            )
        }
    }
}
