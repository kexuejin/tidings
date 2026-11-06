// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kapp.tenon.domain.AgentSlice
import com.kapp.tenon.domain.sessionPageKeyOf
import com.kapp.tenon.domain.model.MessageUi
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.chat_task_divider_label
import com.kapp.tenon.generated.resources.chat_task_divider_new
import com.kapp.tenon.generated.resources.ic_terminal
import com.kapp.tenon.ui.theme.LocalAppSpacing
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * ChatScreen 的**会话分段与边界小件**（ARP-14：从 ChatScreen.kt 移出，逐行多重集比对零差异）。
 *
 * 搬了什么：`SessionDivider`（agent 会话分段线）、`HistoryEdgeMarker`（消息流两端边界提示）、
 * 会话分段判定与取名的四个纯函数（`sessionTransitionIds` / `sessionIdOf` /
 * `sessionDividerTitle` / `hasMoreSessionsLocal`）、产物预览的文件行解析
 * （`artifactSavedPathOf`），以及两个常量（`HISTORY_LOADING_GIVE_UP_MS` /
 * `CAPSULE_RECENT_TASK_LIMIT`）。
 *
 * 为什么搬得动：这一整块的引用**全部**在 `ChatScreen.kt` 内（全仓引用数为 0），没有
 * 任何成员状态依赖 —— 纯函数 + 无参组合。包名不变，既有测试零改动。
 *
 * 可见性：原 `private` 是文件级，跨文件后必须放宽 —— 全部 `internal`，没有一条到
 * `public`（分层口径「public = 屏与外壳，internal = 缝」）。
 *
 * 注意：`sessionTransitionIds` 原文的两段 KDoc 归属在搬移前就错位（`sessionIdOf` 的
 * KDoc 插在它前面），此处**逐行保持原样**，多重集比对不受影响；归属修正留待专门提交。
 */

/**
 * 某个工作区还有没有下一页（PPE-1 增补 D8）。
 *
 * 判据：本地已知的会话数 < 宿主在工作区帧里自报的 sessionCount。用自报数当上界而不是
 * 「上一页是否装满」，是因为后者在最后一页恰好装满时会多要一次空页（用户点了
 * 「加载更多」却什么都没发生）。
 *
 * 出口一：上面那个自报数是**宿主的话**，它只在收到 agent-workspace 帧时更新（而且
 * 键缺失时还保留旧值）—— 宿主那侧删了会话、或帧丢过一轮，local < declared 就会一直
 * 成立，按钮永远点得动、点下去却一页空转。`sessionPageExhausted` 就是为这件事记的账：
 * 发过一页、5 秒后一条新行都没来，本端自己判定这个工作区到底了，不再画这颗按钮。
 */
internal fun hasMoreSessionsLocal(agentSlice: AgentSlice, peerId: String, wsId: String): Boolean {
    if (sessionPageKeyOf(peerId, wsId) in agentSlice.sessionPageExhausted) return false
    val declared = agentSlice.agentWorkspaces.values
        .firstOrNull { it.peerId == peerId && it.wsId == wsId }
        ?.sessionCount
        ?.toInt()
        ?: return false
    // 与分页 offset 同一把尺子：数**未过滤**原始表（见 `agentSessionCountOf` 的 KDoc）。
    return agentSlice.agentSessions.values.count { it.peerId == peerId && it.wsId == wsId } < declared
}

/**
 * 消息流里每段会话的**第一条**消息 id —— 也就是分隔线的落点（task-42 ④）。
 *
 * 判据是「这条消息的会话 id 与上一条不同」：会话 id 相同的一段算同一段，换段就是新一段。
 * 抽成纯函数是为了能被单测钉住：新会话是否真的会产生一条分隔线，不该只能靠真机肉眼确认。
 */
/**
 * 一条消息属于哪个 agent 会话：**只认协议字段**（债#1）。
 *
 * 字段（`agentSessionId`）是**事实**，由协议携带，两端一致；id 前缀（`agent-ev-`）是
 * 桌面端本地构造消息的副产物，不是协议事实 —— 解析它的 `extractSessionId` 已删。
 * 旧落库行（只有前缀、没有字段）的分段线不迁移：兼容约束已按裁决解除。
 */
internal fun sessionIdOf(message: MessageUi): String? = message.agentSessionId

/**
 * 产物预览的文件行解析（升级计划 §4.2-3）：消息流里与卡同 id 关联的**已落盘**文件。
 *
 * 关联判据是「同会话 + 同名文件」：宿主直投与发卡在协议上是两条消息（file-msg 与
 * 群文本），没有共享 id，能对上的唯一事实就是文件名。找到**收完**（Completed、
 * savedPaths 非空）的那行才返回路径 —— 还在传的文件给了路径也读不到全文。
 * 找不到返回 null：入口不画，不做孤立路径展示。
 */
internal fun artifactSavedPathOf(
    messages: List<MessageUi>,
    cardId: String,
    artifactName: String,
): String? {
    if (artifactName.isBlank()) return null
    // 卡必须真的在这条会话的消息流里：找不到卡 = 文件行无从归属，入口不画。
    val hasCard = messages.any { message ->
        (message.card ?: message.cardPayload)?.get("cardId")?.toString()?.contains(cardId) == true
    }
    if (!hasCard) return null
    return messages.firstOrNull { message ->
        val file = message.file ?: return@firstOrNull false
        file.name == artifactName &&
            file.state == com.kapp.tenon.domain.model.TransferState.Completed &&
            file.savedPaths.isNotEmpty()
    }?.file?.savedPaths?.firstOrNull()
}
internal fun sessionTransitionIds(messages: List<MessageUi>): Set<String> {
    val transitions = mutableSetOf<String>()
    var lastSessionId: String? = null
    for (m in messages) {
        val sid = sessionIdOf(m)
        if (sid != null && sid != lastSessionId) {
            transitions.add(m.id)
            lastSessionId = sid
        }
    }
    return transitions
}

/**
 * 分隔线上要写的会话标题；null = 标题还没到（调用方画中性文案「新会话」）。
 *
 * 空白串与 null 是同一档：它们都表示「这条会话现在没有可写的名字」，而不是一个真标题。
 * 这里**不**退回 sessionId —— 身份键不是标题（AgentSessionUi.displayTitle 的 KDoc）。
 */
internal fun sessionDividerTitle(sessionTitle: String?): String? =
    sessionTitle?.takeIf { it.isNotBlank() }

/**
 * 消息流两端的边界提示（"正在加载更早的消息" / "已经到最早一条了"）。
 *
 * 用居中弱化小字而不是转圈：它要回答的是"还有没有更早的"，不是"后台在忙"。
 * 高度固定不下来 —— 文案在两档语言里长度不同，靠 padding + 居中，不给死高度。
 */
@Composable
internal fun HistoryEdgeMarker(text: String) {
    val sp = LocalAppSpacing.current
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = sp.md10),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 「正在加载更早的消息」最多显示多久（域层在"没有更早的了"时不发信号，这里只防永远转圈）。 */
internal const val HISTORY_LOADING_GIVE_UP_MS = 6_000L

/** 目标选择 Sheet 里「最近任务」的条数上限：它是快捷入口，不是会话列表。 */
internal const val CAPSULE_RECENT_TASK_LIMIT = 8

@Composable
internal fun SessionDivider(
    sessionTitle: String?,
    modifier: Modifier = Modifier,
) {
    // 没有标题也**必须画这条分隔线**（task-42 ④）。
    //
    // 早先这里是「标题为空就 return」：本意是不让「任务：868856e1」这种把身份键当标题的
    // 东西上屏（那条规矩仍然成立，见 AgentSessionUi.displayTitle 的 KDoc）。但它把
    // **新建会话**这一档也一起吞了 —— 新会话先到消息、后到（甚至一直没到）带标题的
    // agent-session 帧，于是用户看到的是「有新消息，却没有任何分段标记」，而分段标记
    // 恰恰是他要的那个东西。
    //
    // 现在的取舍：**线永远画**；标题没到时用一句中性文案「新会话」，标题到齐后同一帧
    // 自然升级成真标题（标题来自 state，会触发重组）。仍然**不**退回 sessionId 前 8 位。
    val displayTitle = sessionDividerTitle(sessionTitle)
    val titleText = if (displayTitle != null) {
        stringResource(Res.string.chat_task_divider_label, displayTitle)
    } else {
        stringResource(Res.string.chat_task_divider_new)
    }
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
            border = null,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_terminal),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = titleText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
