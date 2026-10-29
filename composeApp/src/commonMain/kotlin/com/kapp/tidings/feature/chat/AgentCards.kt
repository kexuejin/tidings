// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kapp.tidings.domain.model.MessageUi
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.card_decided_by
import com.kapp.tidings.generated.resources.card_diff_summary
import com.kapp.tidings.generated.resources.card_diff_title
import com.kapp.tidings.generated.resources.card_pending_confirm
import com.kapp.tidings.generated.resources.card_progress_label
import com.kapp.tidings.generated.resources.card_rev_tag
import com.kapp.tidings.generated.resources.card_status_blocked
import com.kapp.tidings.generated.resources.card_status_done
import com.kapp.tidings.generated.resources.card_status_failed
import com.kapp.tidings.generated.resources.card_status_pending
import com.kapp.tidings.generated.resources.card_status_running
import com.kapp.tidings.ui.components.AppButton
import com.kapp.tidings.ui.components.AppButtonSize
import com.kapp.tidings.ui.components.AppButtonTone
import com.kapp.tidings.ui.theme.LocalAppSpacing
import com.kapp.tidings.ui.theme.ShapeRoles
import com.kapp.tidings.ui.theme.accents
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.compose.resources.stringResource

/**
 * **声明式卡片渲染引擎**（方案 §4.3 / §5.3 / §7.3，M3 交付 1）。
 *
 * 数据源是**消息库**（`MessageUi.card`，M1-T8 落库列），不是 agentNode 直连 ——
 * 与其它消息同一条读回路径，掉线重连、翻历史、换设备看到的卡片完全一致。
 *
 * ## 5 类受限原子组件（§7.3-3）
 *
 * Banner / Progress / StepList / DiffViewer / ButtonGroup。只有这五类，多一个
 * 组件类型就多一分恶意排版的攻击面（内存/布局滥用）；不认识的 `type` 键被忽略，
 * 渲染引擎永远不因卡片体里多了一个字段而崩 —— 卡片体是**插件定义**的，
 * 协议层只守字节预算（[com.kapp.tidings.core.protocol.Limits.CARD_BYTES]）。
 *
 * ## 原位刷新 = 按 cardId 折叠只渲染最新 rev（§5.3）
 *
 * 落库是**追加式新行**（同 cardId、rev+1 的新消息，M1 定死，绕开
 * `INSERT OR REPLACE` / FTS 那套不变量）；「不刷屏」是 UI 语义：
 * [latestRevCardMessageIds] 按 cardId 折叠出**该渲染**的那些消息 id，
 * 旧 rev 整行收起（不渲染）——「UI 不新增可见气泡」由此成立。
 *
 * ## 审批防重的 UI 侧（§5.3 / §7.3-2）
 *
 * 点击 = 本机乐观置灰（[CardActionLock.PendingConfirm]）+ 交给壳的动作槽；
 * 宿主判定以 **rev+1 的新帧**回放全群，`decidedBy` 到达后全员进
 * [CardActionLock.Decided] 并显示批准人。跨端 ack 广播 M4 才接，
 * 三态语义已由 `MultiDeviceApprovalLockTest`（仿真判据）钉死。
 */

// ── 纯模型与解析 ─────────────────────────────────────────────────────────

/** StepList 里一步的状态（§4.3 样例的三态 + agentState 四态里借来的失败/受阻）。 */
enum class AgentCardStepStatus { Done, Running, Pending, Failed, Blocked }

enum class AgentCardBannerTone { Info, Success, Warn, Error }

data class AgentCardStep(
    val id: String,
    val label: String,
    val status: AgentCardStepStatus,
    val assignee: String? = null,
)

data class AgentCardDiff(
    val path: String? = null,
    val summary: String? = null,
    val additions: Int? = null,
    val deletions: Int? = null,
)

data class AgentCardBanner(
    val text: String,
    val tone: AgentCardBannerTone = AgentCardBannerTone.Info,
)

data class AgentCardAction(
    val actionId: String,
    val label: String,
    /** 原样的 style 字符串（primary / secondary / danger…），插件自定义；UI 只认 approval。 */
    val style: String? = null,
    /** 高危审批按钮（§5.3）：享受乐观置灰与防重击语义。 */
    val approval: Boolean = false,
)

/**
 * 一张卡**此刻**的渲染形状。身份是二元组 `(cardId, rev)`：
 * 没有 cardId 就无法折叠，没有 rev 就没有「哪份更新」—— 两者缺一都不渲染。
 */
data class AgentCardUi(
    val cardId: String,
    val rev: Int,
    val type: String? = null,
    val title: String? = null,
    val progress: Int? = null,
    val steps: List<AgentCardStep> = emptyList(),
    val diff: AgentCardDiff? = null,
    val banner: AgentCardBanner? = null,
    val actions: List<AgentCardAction> = emptyList(),
    /** 宿主判定人（随 rev+1 的新帧到达）；null = 还没有判定。 */
    val decidedBy: String? = null,
    /** 宿主判定的 actionId；与 [decidedBy] 成对。 */
    val decidedActionId: String? = null,
)

/** 宽容解析：未知字段忽略、未知状态退档；身份键（cardId/rev）缺失整卡作废。 */
fun parseAgentCard(json: JsonObject?): AgentCardUi? {
    if (json == null) return null
    val cardId = json.str("cardId")?.takeIf { it.isNotBlank() } ?: return null
    val rev = json.int("rev") ?: return null
    return AgentCardUi(
        cardId = cardId,
        rev = rev,
        type = json.str("type")?.takeIf { it.isNotBlank() },
        title = json.str("title")?.takeIf { it.isNotBlank() },
        progress = json.int("progress")?.coerceIn(0, 100),
        steps = json.array("items").mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val label = obj.str("label")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            AgentCardStep(
                id = obj.str("id") ?: label,
                label = label,
                status = when (obj.str("status")) {
                    "done" -> AgentCardStepStatus.Done
                    "running" -> AgentCardStepStatus.Running
                    "failed" -> AgentCardStepStatus.Failed
                    "blocked" -> AgentCardStepStatus.Blocked
                    // pending 与一切未来值：显示成"还没轮到它"比显示成空洞诚实。
                    else -> AgentCardStepStatus.Pending
                },
                assignee = obj.str("assignee")?.takeIf { it.isNotBlank() },
            )
        },
        diff = (json["diff"] as? JsonObject)?.let { d ->
            AgentCardDiff(
                path = d.str("path")?.takeIf { it.isNotBlank() },
                summary = d.str("summary")?.takeIf { it.isNotBlank() },
                additions = d.int("additions"),
                deletions = d.int("deletions"),
            )
        },
        banner = (json["banner"] as? JsonObject)?.let { b ->
            b.str("text")?.takeIf { it.isNotBlank() }?.let { text ->
                AgentCardBanner(
                    text = text,
                    tone = when (b.str("tone")) {
                        "success" -> AgentCardBannerTone.Success
                        "warn" -> AgentCardBannerTone.Warn
                        "error" -> AgentCardBannerTone.Error
                        else -> AgentCardBannerTone.Info
                    },
                )
            }
        },
        actions = json.array("actions").mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val actionId = obj.str("actionId")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            AgentCardAction(
                actionId = actionId,
                label = obj.str("label")?.takeIf { it.isNotBlank() } ?: actionId,
                style = obj.str("style")?.takeIf { it.isNotBlank() },
                approval = obj.boolean("approval") == true,
            )
        },
        decidedBy = json.str("decidedBy")?.takeIf { it.isNotBlank() },
        decidedActionId = json.str("decidedActionId")?.takeIf { it.isNotBlank() },
    )
}

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }
        ?.content?.toIntOrNull()

private fun JsonObject.boolean(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }
        ?.content?.toBooleanStrictOrNull()

private fun JsonObject.array(key: String): List<JsonElement> =
    (this[key] as? JsonArray)?.toList() ?: emptyList()

/**
 * 按 cardId 折叠出**该渲染**的消息 id 集合（原位刷新的 UI 语义，§5.3）。
 *
 * 只要有 `card` 的消息参与折叠；同 cardId 取 rev 最大者，同 rev 按**到达序**取
 * 最后一条（库是追加式的，最后一行就是最新事实 —— 不赌时间戳：落库时刻与信封
 * `ts` 是两件事，AGENTS §3 的教训）。入参**不要求有序**：分页读回的顺序不保证
 * 全局有序，判据不能建立在调用方的好习惯上。
 */
fun latestRevCardMessageIds(messages: List<MessageUi>): Set<String> {
    val latest = LinkedHashMap<String, Pair<Int, String>>() // cardId -> (rev, messageId)
    for (message in messages) {
        val card = message.card ?: continue
        val cardId = (card["cardId"] as? JsonPrimitive)?.content ?: continue
        val rev = (card["rev"] as? JsonPrimitive)?.content?.toIntOrNull() ?: continue
        val current = latest[cardId]
        if (current == null || rev >= current.first) {
            latest[cardId] = rev to message.id
        }
    }
    return latest.values.map { it.second }.toSet()
}

// ── 审批锁三态（§5.3）────────────────────────────────────────────────────

enum class CardActionLock {
    /** 可点。 */
    Editable,

    /** 本机已点击、乐观置灰，等宿主判定（跨端 ack 广播 M4 才接）。 */
    PendingConfirm,

    /** 宿主判定已到（rev+1 新帧的 decidedBy）：全员终态，显示批准人。 */
    Decided,
}

data class CardActionUi(
    val lock: CardActionLock,
    val decidedBy: String? = null,
) {
    val disabled: Boolean get() = lock != CardActionLock.Editable
}

/**
 * 一颗按钮此刻的锁态。
 *
 * 乐观记账键是 `(cardId, actionId)`：同卡片的其它按钮不受连坐；
 * 宿主判定只覆盖它判定的那颗（`decidedActionId`），别的按钮照常。
 */
fun cardActionUiOf(card: AgentCardUi, pendingActions: Set<String>, actionId: String): CardActionUi {
    if (card.decidedBy != null && (card.decidedActionId == null || card.decidedActionId == actionId)) {
        return CardActionUi(CardActionLock.Decided, decidedBy = card.decidedBy)
    }
    if ("${card.cardId}|$actionId" in pendingActions) {
        return CardActionUi(CardActionLock.PendingConfirm)
    }
    return CardActionUi(CardActionLock.Editable)
}

// ── UI ───────────────────────────────────────────────────────────────────

/**
 * 聊天流里的 agent 卡片（整幅宽，与屏幕协助卡同一条归属规矩：它是独立消息不是气泡附件）。
 *
 * 手机与桌面**共用这一份**（AGENTS §2.6：内容共享、外壳参数化）—— 两条壳都从
 * [com.kapp.tidings.feature.chat.ChatScreen] 的消息流里走到这里，没有第二份实现。
 *
 * [onAction] 是可空槽：null = 这个壳还没有动作通路（跨端派发 M4 才接），
 * 按钮组**整块不画** —— 一个按下去没反应的按钮比没有更糟（DeadButtonGuard 同一条规矩）。
 * `pendingActions` 由调用方持有（会话切换即重建），本组件不存状态。
 */
@Composable
fun AgentCardBubble(
    card: AgentCardUi,
    pendingActions: Set<String>,
    onAction: ((card: AgentCardUi, actionId: String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val sp = LocalAppSpacing.current
    Surface(
        shape = ShapeRoles.ListCard,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = sp.md, vertical = sp.sm),
            verticalArrangement = Arrangement.spacedBy(sp.sm),
        ) {
            // 头行：标题 + 版本小字。没有标题就不画头行 —— 不编一个。
            if (card.title != null || card.rev > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (card.title != null) {
                        Text(
                            text = card.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Box(Modifier.weight(1f))
                    }
                    Text(
                        text = stringResource(Res.string.card_rev_tag, card.rev),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            card.banner?.let { CardBannerRow(it) }
            card.progress?.let { CardProgressRow(it) }
            if (card.steps.isNotEmpty()) {
                CardStepListColumn(card.steps)
            }
            card.diff?.let { CardDiffViewerRow(it) }
            // 宿主判定的知情行：它不是按钮，是「这件事已被谁定了」的事实 ——
            // 就算这个壳没有动作通路（onAction == null），它也照画。
            card.decidedBy?.let { decided ->
                Text(
                    text = stringResource(Res.string.card_decided_by, decided),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (onAction != null && card.actions.isNotEmpty()) {
                CardButtonGroupRow(card, pendingActions, onAction)
            }
        }
    }
}

/** 原子 1/5 · Banner（§7.3）：一行带色的说明。 */
@Composable
private fun CardBannerRow(banner: AgentCardBanner) {
    val sp = LocalAppSpacing.current
    val (container, content) = when (banner.tone) {
        AgentCardBannerTone.Info ->
            MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        AgentCardBannerTone.Success ->
            MaterialTheme.accents.online.copy(alpha = 0.16f) to MaterialTheme.colorScheme.onSurface
        AgentCardBannerTone.Warn ->
            MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        AgentCardBannerTone.Error ->
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(shape = ShapeRoles.DayCell, color = container, contentColor = content) {
        Text(
            text = banner.text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().padding(horizontal = sp.sm, vertical = sp.sm6),
        )
    }
}

/** 原子 2/5 · Progress（§7.3）：整幅细条 + 数字。 */
@Composable
private fun CardProgressRow(progress: Int) {
    val sp = LocalAppSpacing.current
    Column(verticalArrangement = Arrangement.spacedBy(sp.xs)) {
        val label = stringResource(Res.string.card_progress_label, progress)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(sp.sm),
            modifier = Modifier.semantics { contentDescription = label },
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.weight(1f).height(6.dp),
            ) {}
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 原子 3/5 · StepList（§7.3）：状态点 + 标签 + 承办人。 */
@Composable
private fun CardStepListColumn(steps: List<AgentCardStep>) {
    val sp = LocalAppSpacing.current
    Column(verticalArrangement = Arrangement.spacedBy(sp.sm6)) {
        steps.forEach { step -> CardStepRow(step) }
    }
}

@Composable
private fun CardStepRow(step: AgentCardStep) {
    val sp = LocalAppSpacing.current
    val statusLabel = when (step.status) {
        AgentCardStepStatus.Done -> stringResource(Res.string.card_status_done)
        AgentCardStepStatus.Running -> stringResource(Res.string.card_status_running)
        AgentCardStepStatus.Pending -> stringResource(Res.string.card_status_pending)
        AgentCardStepStatus.Failed -> stringResource(Res.string.card_status_failed)
        AgentCardStepStatus.Blocked -> stringResource(Res.string.card_status_blocked)
    }
    val statusColor = when (step.status) {
        AgentCardStepStatus.Done -> MaterialTheme.accents.online
        AgentCardStepStatus.Running -> MaterialTheme.colorScheme.primary
        AgentCardStepStatus.Failed -> MaterialTheme.colorScheme.error
        AgentCardStepStatus.Blocked -> MaterialTheme.colorScheme.tertiary
        AgentCardStepStatus.Pending -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(sp.sm),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Surface(shape = RoundedCornerShape(50), color = statusColor.copy(alpha = 0.16f)) {
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
                modifier = Modifier.padding(horizontal = sp.sm6, vertical = sp.xxs),
            )
        }
        Text(
            text = step.label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        step.assignee?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 原子 4/5 · DiffViewer（§7.3）：路径/摘要一行，+ − 计数靠色。 */
@Composable
private fun CardDiffViewerRow(diff: AgentCardDiff) {
    val sp = LocalAppSpacing.current
    Surface(shape = ShapeRoles.DayCell, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = sp.sm, vertical = sp.sm6),
            verticalArrangement = Arrangement.spacedBy(sp.xxs),
        ) {
            Text(
                text = stringResource(Res.string.card_diff_title),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(sp.sm)) {
                if (diff.path != null) {
                    Text(
                        text = diff.path,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                if (diff.additions != null || diff.deletions != null) {
                    Text(
                        text = "+${diff.additions ?: 0}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.accents.online,
                    )
                    Text(
                        text = "−${diff.deletions ?: 0}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            diff.summary?.let {
                Text(
                    text = stringResource(Res.string.card_diff_summary, it, diff.additions ?: 0, diff.deletions ?: 0),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 原子 5/5 · ButtonGroup（§7.3）：审批防重的 UI 落点（三态见 [cardActionUiOf]）。 */
@Composable
private fun CardButtonGroupRow(
    card: AgentCardUi,
    pendingActions: Set<String>,
    onAction: (card: AgentCardUi, actionId: String) -> Unit,
) {
    val sp = LocalAppSpacing.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(sp.sm),
        modifier = Modifier.fillMaxWidth(),
    ) {
        card.actions.forEachIndexed { index, action ->
            val ui = cardActionUiOf(card, pendingActions, action.actionId)
            AppButton(
                text = when (ui.lock) {
                    CardActionLock.PendingConfirm -> stringResource(Res.string.card_pending_confirm)
                    else -> action.label
                },
                onClick = { onAction(card, action.actionId) },
                enabled = !ui.disabled,
                size = AppButtonSize.Small,
                tone = if (action.approval) AppButtonTone.Primary else AppButtonTone.Neutral,
                modifier = if (index == 0) Modifier.weight(1f) else Modifier.weight(1f),
            )
        }
    }
}
