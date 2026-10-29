// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.capsule_default_ws
import com.kapp.tidings.generated.resources.capsule_dismiss
import com.kapp.tidings.generated.resources.capsule_entity_team
import com.kapp.tidings.generated.resources.capsule_expand_hint
import com.kapp.tidings.generated.resources.capsule_new_task
import com.kapp.tidings.generated.resources.capsule_sheet_empty
import com.kapp.tidings.generated.resources.capsule_sheet_new_task
import com.kapp.tidings.generated.resources.capsule_sheet_recent
import com.kapp.tidings.generated.resources.capsule_sheet_title
import com.kapp.tidings.generated.resources.ic_close
import com.kapp.tidings.generated.resources.ic_plus
import com.kapp.tidings.ui.theme.IconSizes
import com.kapp.tidings.ui.theme.LocalAppSpacing
import com.kapp.tidings.ui.theme.ShapeRoles
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * **上下文唤起胶囊**（Context Capsule，方案 §5.2 / §7.3-5，M3 交付 2）。
 *
 * 废除输入框上方**常驻**的目标条：平时 100% 纯净，仅在两种时刻唤起 ——
 * 输入文本中键入**合法**的 `@agent`，或点某条 Agent/Team 发言的「回复」。
 *
 * ## 交互状态是纯函数（本仓没有 Compose UI 测试基建）
 *
 * 唤起/收起/点选全部抽成 [CapsuleState] 上的纯函数，由
 * `ContextCapsuleErgonomicsTest` 钉死；Compose 层只负责把状态画出来。
 *
 * ## 键入 vs 粘帖（§7.3-5 的防误触）
 *
 * 界面层拿不到「这次变更是不是粘帖」的系统信号，但**编辑粒度**拿得到：
 * 一次插入超过键入粒度的整段文本就是粘帖（[classifyDraftEdit]）。粘帖不触发唤起、
 * 也不撤销既有绑定 —— 它管的是「唤起」，不是把用户已选好的目标拍掉。
 *
 * ## 多 @ 只绑首个执行主体
 *
 * 「首个」= 文本位置最左（[firstAgentMentionToken]），且长 token 优先
 * （`@dsh-teahouse` 不被 `@dsh` 抢先命中 —— 与协议侧 `mentionedVirtualAgent` 同一条规则）。
 */

// ── 数据 ─────────────────────────────────────────────────────────────────

/** agent 的一个授权工作区（白名单条目；路径不进 UI —— 非宿主根本没有，宿主也不需要）。 */
data class CapsuleWorkspaceOption(
    val wsId: String,
    val title: String,
)

/**
 * 胶囊可绑定的一个执行主体。由调用方从**群声明**（`GroupMeta.virtualAgents`）投影，
 * 键是 `@` token —— 与 `MentionTarget.token`、气泡署名同一份事实，不另造名字。
 */
data class CapsuleAgentInfo(
    val agentToken: String,
    val agentName: String,
    /** 装进 `agentIntent.agentId` 的协议 id（声明里的 `agentId`，不是显示名）。 */
    val agentId: String,
    val hostNodeId: String,
    val isTeam: Boolean,
    val workspaces: List<CapsuleWorkspaceOption>,
    val defaultWsId: String?,
)

/** 最近任务（Bottom Sheet 里「最近任务」段的一行）。 */
data class CapsuleTaskOption(
    val sessionId: String,
    val title: String,
    val wsId: String?,
)

/** 发送时装进 `agentIntent` 键的选中意图（方案 §4.2）。 */
data class CapsuleSelection(
    val agentId: String,
    val wsId: String?,
    val resumeSessionId: String?,
    val sessionTitle: String?,
)

/**
 * 意图载荷：字段与方案 §4.2 样例一一对应，`mode` 恒 `auto`（§0-5「单帧原子意图」
 * 的默认档）。缺省字段**整个不带键** —— 白名单协议里 null 字段是噪声。
 */
fun CapsuleSelection.toIntentJson(): JsonObject = buildJsonObject {
    put("agentId", agentId)
    if (wsId != null) put("wsId", wsId)
    if (resumeSessionId != null) put("resumeSessionId", resumeSessionId)
    put("mode", "auto")
}

// ── 纯状态机 ─────────────────────────────────────────────────────────────

/** 胶囊为什么被唤起：键入（含 @ 候选点选）或回复。收起依据跟着来源走。 */
enum class CapsuleSource { Typing, Reply }

/** 胶囊的完整状态：绑定主体 + 选中的意图 + 绑定来源。三处都 null = 没有胶囊。 */
data class CapsuleState(
    val agent: CapsuleAgentInfo? = null,
    val selection: CapsuleSelection? = null,
    val source: CapsuleSource? = null,
) {
    companion object {
        val Empty = CapsuleState()
    }
}

/** 一次草稿编辑的分类：键入（含逐字删除）/ 粘帖 / @ 候选点选。 */
enum class DraftEditKind { Typed, Pasted, MentionPicked }

/**
 * 按编辑粒度分类。键入的粒度是**一两个字符**（含 IME 单字上屏与退格）；
 * 一次进来一整段就是粘帖；调用方明确知道是候选点选时（插入整个 token）走
 * [DraftEditKind.MentionPicked]。
 */
fun classifyDraftEdit(previous: String, next: String, explicitPick: Boolean): DraftEditKind = when {
    explicitPick -> DraftEditKind.MentionPicked
    // 负 delta = 删除；逐字删除（±2 以内）算键入。大块删除（剪切/整段回退）与粘帖同档：
    // 都不该唤起胶囊。
    kotlin.math.abs(next.length - previous.length) <= 2 -> DraftEditKind.Typed
    else -> DraftEditKind.Pasted
}

/**
 * 文本中**第一个**命中已声明 agent 的 token；同位置长 token 优先。
 *
 * 「首个」按**文本位置**算（多 @ 只绑首个执行主体，§7.3-5），长 token 优先是消歧
 * （`@dsh-teahouse` 不被 `@dsh` 抢走）—— 与 `GroupMeta.mentionedVirtualAgent` 的
 * 「从长到短匹配」同一套规则，界面与协议各写一遍必然漂，但那边的签名吃 `GroupMeta`、
 * 这边吃纯文本，抽到一起反而要造出一个人造类型；两边各留一条测试钉住同一行为。
 */
fun firstAgentMentionToken(text: String, tokens: Collection<String>): String? {
    if (tokens.isEmpty()) return null
    val ordered = tokens.sortedByDescending { it.length }
    var best: Pair<Int, String>? = null // (位置, token)
    for (token in ordered) {
        if (token.isEmpty()) continue
        var from = 0
        while (true) {
            val at = text.indexOf(token, from, ignoreCase = true)
            if (at < 0) break
            val current = best
            if (current == null || at < current.first || (at == current.first && token.length > current.second.length)) {
                best = at to token
            }
            from = at + 1
        }
    }
    return best?.second
}

private fun defaultSelectionOf(agent: CapsuleAgentInfo): CapsuleSelection = CapsuleSelection(
    agentId = agent.agentId,
    wsId = agent.defaultWsId,
    resumeSessionId = null,
    sessionTitle = null,
)

/**
 * 草稿编辑后的胶囊状态。
 *
 * 规则矩阵（全部有测试）：
 * - 粘帖：**不动** —— 不唤起（防误触），也不撤销既有绑定；
 * - 键入 / 点选：文本里第一个 agent token 决定绑定（没有 → 键入绑定的收起）；
 * - 回复绑定只认 [capsuleOnReply]，草稿编辑动不了它。
 */
fun capsuleOnDraftEdit(
    state: CapsuleState,
    previousDraft: String,
    nextDraft: String,
    agentsByToken: Map<String, CapsuleAgentInfo>,
): CapsuleState {
    if (state.source == CapsuleSource.Reply) return state
    return when (classifyDraftEdit(previousDraft, nextDraft, explicitPick = false)) {
        DraftEditKind.Pasted -> state
        DraftEditKind.MentionPicked -> state
        DraftEditKind.Typed -> {
            val token = firstAgentMentionToken(nextDraft, agentsByToken.keys)
            val agent = token?.let(agentsByToken::get)
            if (agent == null) {
                CapsuleState.Empty
            } else if (agent == state.agent) {
                state
            } else {
                CapsuleState(agent = agent, selection = defaultSelectionOf(agent), source = CapsuleSource.Typing)
            }
        }
    }
}

/**
 * 「回复某条 Agent/Team 发言」：无论草稿内容如何都绑定它（§5.2 的第二个唤起时机）。
 * [agent] 为 null = 取消引用 ⇒ 回复绑定收起（键入绑定不受影响）。
 */
fun capsuleOnReply(state: CapsuleState, agent: CapsuleAgentInfo?): CapsuleState = when {
    agent == null -> if (state.source == CapsuleSource.Reply) CapsuleState.Empty else state
    else -> CapsuleState(agent = agent, selection = defaultSelectionOf(agent), source = CapsuleSource.Reply)
}

/**
 * 从 @ 候选里点选（或长按头像直接 @）：绑定**插入后**文本里最左的 agent token ——
 * 多 @ 只绑首个执行主体，点选也不例外；token 缺席时退回点选的那个（直接 @ 的
 * 草稿还没插进去的时序兜底）。
 */
fun capsuleOnAgentPick(
    state: CapsuleState,
    picked: CapsuleAgentInfo,
    nextDraft: String,
    agentsByToken: Map<String, CapsuleAgentInfo>,
): CapsuleState {
    val token = firstAgentMentionToken(nextDraft, agentsByToken.keys)
    val agent = token?.let(agentsByToken::get) ?: picked
    return CapsuleState(agent = agent, selection = defaultSelectionOf(agent), source = CapsuleSource.Typing)
}

/** 轻点 ✕：收起胶囊。签名里没有草稿 —— 「收起不清输入」是结构保证，不是约定。 */
fun capsuleOnDismiss(state: CapsuleState): CapsuleState = CapsuleState.Empty

/** Bottom Sheet 里的点选：即刻生效（零锁死 —— 没有「确认中」中间态）。 */
fun capsuleOnSelect(state: CapsuleState, selection: CapsuleSelection): CapsuleState =
    state.copy(selection = selection)

// ── UI：胶囊本体 ─────────────────────────────────────────────────────────

/**
 * 目标胶囊（§5.2 场景 2 的那一行）：`@dev-lead (Team) · teahouse-mobile (默认) · 新任务 ▾`。
 *
 * 轻点展开 Bottom Sheet（[AgentTaskPickerSheet]，开合由调用方持有）；✕ 只收胶囊
 * 不清输入。手机与桌面**共用这一份** —— 它长在共享的 [ChatScreen] 里，两条壳
 * 天然同源，没有第二份实现。
 */
@Composable
fun AgentContextCapsule(
    agent: CapsuleAgentInfo,
    selection: CapsuleSelection?,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sp = LocalAppSpacing.current
    val expandHint = stringResource(Res.string.capsule_expand_hint)
    Surface(
        shape = ShapeRoles.ListCard,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = expandHint }
            .clickable(onClick = onExpand),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = sp.md, vertical = sp.sm6),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(sp.sm6),
        ) {
            Text(
                text = agent.agentToken,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (agent.isTeam) {
                CapsuleBadge(stringResource(Res.string.capsule_entity_team))
            }
            agent.workspaces.firstOrNull { it.wsId == (selection?.wsId ?: agent.defaultWsId) }?.let { ws ->
                Text(text = "·", style = MaterialTheme.typography.labelLarge)
                Text(
                    text = ws.title,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (ws.wsId == agent.defaultWsId) {
                    CapsuleBadge(stringResource(Res.string.capsule_default_ws))
                }
            }
            Text(text = "·", style = MaterialTheme.typography.labelLarge)
            Text(
                text = selection?.sessionTitle ?: stringResource(Res.string.capsule_new_task),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            val dismissLabel = stringResource(Res.string.capsule_dismiss)
            Box(
                modifier = Modifier
                    .semantics { contentDescription = dismissLabel }
                    .clickable(onClick = onDismiss)
                    .padding(sp.xs),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_close),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(IconSizes.Inline),
                )
            }
        }
    }
}

@Composable
private fun CapsuleBadge(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = LocalAppSpacing.current.sm6, vertical = LocalAppSpacing.current.xxs),
        )
    }
}

// ── UI：Bottom Sheet ─────────────────────────────────────────────────────

/**
 * 目标选择 Bottom Sheet（§5.2）：按授权工作区分组展示最近任务与「发起新任务」。
 *
 * 点选即刻生效、零界面锁死：每行点下去就是最终态，没有「下一步/确认」。
 * 组的顺序 = [agent] 的授权白名单顺序，每组的最近任务紧跟在「发起新任务」后面；
 * 没有授权工作区的 agent 只有一句说明 + 「发起新任务」（wsId = null，协议侧解释为
 * 它自己的会话）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentTaskPickerSheet(
    agent: CapsuleAgentInfo,
    selection: CapsuleSelection?,
    recentTasks: List<CapsuleTaskOption>,
    onPick: (CapsuleSelection) -> Unit,
    onDismiss: () -> Unit,
) {
    val sp = LocalAppSpacing.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // 与 NodeSheets 同一条注释的结论：M3 的默认档恰是 spec 的 Sheet 档，底色显式给 surface。
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = sp.lg, vertical = sp.sm6),
            verticalArrangement = Arrangement.spacedBy(sp.sm6),
        ) {
            Text(
                text = stringResource(Res.string.capsule_sheet_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (agent.workspaces.isEmpty()) {
                Text(
                    text = stringResource(Res.string.capsule_sheet_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CapsuleTaskRow(
                    leading = { Icon(painter = painterResource(Res.drawable.ic_plus), contentDescription = null, modifier = Modifier.size(IconSizes.Inline)) },
                    title = stringResource(Res.string.capsule_sheet_new_task),
                    selected = selection?.wsId == null && selection?.resumeSessionId == null,
                    onClick = { onPick(CapsuleSelection(agent.agentId, null, null, null)) },
                )
                val loose = recentTasks.filter { it.wsId == null }
                if (loose.isNotEmpty()) {
                    Text(
                        text = stringResource(Res.string.capsule_sheet_recent),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = sp.sm6),
                    )
                }
                loose.forEach { task ->
                    CapsuleTaskRow(
                        leading = null,
                        title = task.title,
                        selected = selection?.resumeSessionId == task.sessionId,
                        onClick = { onPick(CapsuleSelection(agent.agentId, null, task.sessionId, task.title)) },
                    )
                }
            }
            agent.workspaces.forEach { ws ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(sp.sm6),
                    modifier = Modifier.padding(top = sp.sm6),
                ) {
                    Text(
                        text = ws.title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (ws.wsId == agent.defaultWsId) {
                        CapsuleBadge(stringResource(Res.string.capsule_default_ws))
                    }
                }
                CapsuleTaskRow(
                    leading = { Icon(painter = painterResource(Res.drawable.ic_plus), contentDescription = null, modifier = Modifier.size(IconSizes.Inline)) },
                    title = stringResource(Res.string.capsule_sheet_new_task),
                    selected = selection?.wsId == ws.wsId && selection.resumeSessionId == null,
                    onClick = { onPick(CapsuleSelection(agent.agentId, ws.wsId, null, null)) },
                )
                recentTasks.filter { it.wsId == ws.wsId }.forEach { task ->
                    CapsuleTaskRow(
                        leading = null,
                        title = task.title,
                        selected = selection?.resumeSessionId == task.sessionId,
                        onClick = { onPick(CapsuleSelection(agent.agentId, ws.wsId, task.sessionId, task.title)) },
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            Box(Modifier.padding(bottom = sp.xl))
        }
    }
}

@Composable
private fun CapsuleTaskRow(
    leading: (@Composable () -> Unit)?,
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val sp = LocalAppSpacing.current
    Surface(
        shape = ShapeRoles.DayCell,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = sp.md, vertical = sp.sm6),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(sp.sm6),
        ) {
            leading?.invoke()
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
