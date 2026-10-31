// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kapp.tidings.core.protocol.AgentExecAuthorizer
import com.kapp.tidings.domain.model.MessageUi
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.card_decided_by
import com.kapp.tidings.generated.resources.card_details_hide
import com.kapp.tidings.generated.resources.card_details_show
import com.kapp.tidings.generated.resources.card_diff_summary
import com.kapp.tidings.generated.resources.card_expired
import com.kapp.tidings.generated.resources.card_pending_confirm
import com.kapp.tidings.generated.resources.card_progress_label
import com.kapp.tidings.generated.resources.card_rev_tag
import com.kapp.tidings.generated.resources.card_status_blocked
import com.kapp.tidings.generated.resources.card_status_done
import com.kapp.tidings.generated.resources.card_status_failed
import com.kapp.tidings.generated.resources.card_status_pending
import com.kapp.tidings.generated.resources.card_status_running
import com.kapp.tidings.generated.resources.card_waiting_host
import com.kapp.tidings.generated.resources.agent_state_banner_failed
import com.kapp.tidings.ui.components.AppButton
import com.kapp.tidings.ui.components.AppOutlinedButton
import com.kapp.tidings.ui.components.AppButtonSize
import com.kapp.tidings.ui.components.AppButtonTone
import com.kapp.tidings.ui.theme.IconSizes
import com.kapp.tidings.ui.theme.LocalAppSpacing
import com.kapp.tidings.ui.theme.SurfaceTiers
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

/**
 * 产物元数据（对齐 `agent-artifact`）：**只描述不传二进制** ——
 * 文件本体走 file-msg 那条通路，卡片里放的是「有这么个东西」。
 */
data class AgentCardArtifact(
    val name: String,
    val bytes: Long? = null,
    val mime: String? = null,
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
    /** 产物元数据（只描述，不传二进制）。 */
    val artifacts: List<AgentCardArtifact> = emptyList(),
    /**
     * 审批到期时刻（毫秒，插件给的绝对时间）；null = 不会过期。
     *
     * **不占 `agentState` 四态**：过期是渲染层按 `expiresAt` 判出来的子态
     * （设计稿 v2 §6），协议面一个字没动。
     */
    val expiresAt: Long? = null,
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
        artifacts = json.array("artifacts").mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val name = obj.str("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            AgentCardArtifact(
                name = name,
                bytes = obj.long("bytes"),
                mime = obj.str("mime")?.takeIf { it.isNotBlank() },
            )
        },
        expiresAt = json.long("expiresAt"),
    )
}

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }
        ?.content?.toIntOrNull()

private fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }
        ?.content?.toLongOrNull()

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

/**
 * 一条**带卡片负载**的消息在会话流里的去向（设计稿 v2 §4.3-S21）。
 *
 * 三种，不是两种 —— 这是 S21 的全部要点：
 * - [Render]：身份键齐全且是本 cardId 的最新 rev ⇒ 画卡；
 * - [Hidden]：不是最新 rev ⇒ 原位刷新已把它收起（§5.3，历史翻得到、当下不刷屏）；
 * - [FallbackToText]：身份键**缺了**（`cardId` 或 `rev` 不在）⇒ 退成一条普通文本。
 *
 * 为什么第三种必须存在：解析不出身份键时「整行不画」是**静默吞行** —— 插件一次手误
 * （少写一个 `rev`）在群里就变成「凭空少了一句话」，而两端都不报错、门禁也全绿。
 * 宁可显示一条样式朴素的消息。这与 [latestRevCardMessageIds] 的折叠语义是两件事：
 * 折叠是「这条不是最新」，退档是「这条根本不是一张卡」。
 *
 * 判据顺序**不可交换**：身份键判在可见集之前。反过来的话，坏数据永远不在可见集里，
 * 于是它既画不成卡也退不成文本 —— 又回到静默。
 *
 * [FallbackToText.text] 是消息原文，可能为空（卡片帧本来就不带正文）；渲染层在空串时
 * 补一句本地兜底文案。纯函数不碰资源，才能被合成输入钉死。
 */
sealed interface CardRowOutcome {
    data class Render(val card: AgentCardUi) : CardRowOutcome
    data class FallbackToText(val text: String) : CardRowOutcome
    data object Hidden : CardRowOutcome
}

/** 见 [CardRowOutcome] 的 KDoc：`visibleCardMessageIds` 来自 [latestRevCardMessageIds]。 */
fun cardRowOutcome(message: MessageUi, visibleCardMessageIds: Set<String>): CardRowOutcome {
    val card = parseAgentCard(message.card) ?: return CardRowOutcome.FallbackToText(message.text)
    return if (message.id in visibleCardMessageIds) CardRowOutcome.Render(card) else CardRowOutcome.Hidden
}

// ── 策略层纯函数（设计稿 v2 阶段 A）───────────────────────────────────────
//
// 这一段的三个函数**只吃入参**：不读时钟、不读全局状态、不碰 Compose。
// 这样它们能被合成输入钉死（`AgentCardPolicyTest`），也不会被 `envDependentTestGate`
// 判红 —— 一条「过期了没有」的判据如果自己读 `System.currentTimeMillis()`，
// 它在慢机器与快机器上就是两条不同的测试。

/**
 * 审批是否已过期（§4.3-S12）。
 *
 * 边界取**闭区间**：`now == expiresAt` 算过期 —— 留给用户的时间已经用完，
 * 按钮不该还能点。`expiresAt` 缺席 ⇒ 永不过期（绝大多数卡都没有这个字段，
 * 不能因为字段缺席就判成过期）。
 *
 * [now] 是**入参**而不是内部读时钟：预览帧要能固定，测试要能钉边界。
 */
fun isCardExpired(expiresAt: Long?, now: Long): Boolean = expiresAt != null && now >= expiresAt

/** 五类原子组件 + 产物条目（§7.3-3 的渲染块，产物条目随设计稿 v2 新增）。 */
enum class CardBlock {
    Banner,
    Progress,
    StepList,
    DiffViewer,
    ButtonGroup,
    ArtifactList,
}

/**
 * 卡片类型注册表（设计稿 v2 §3）：`type → 推荐构成`。
 *
 * **这是建议层，不是白名单。** 协议侧早已裁决「未知 type 不拒帧」，这里也不做闸：
 * 查不到就返回 null，渲染照旧按数据里的块走。它存在的意义只有三个 ——
 * 给插件一份稳定契约、给 UI 一处「这类卡该长什么样」的收敛点、给 `@Preview` 一份帧清单依据。
 *
 * null 与空集语义不同：**null = 没有建议**（按块渲染），**空集 = 这类卡什么都不该画**。
 * 目前没有任何一类是空集，保留这个区分是为了不让将来「故意画空」和「不认识」混为一谈。
 */
object CardTypeRegistry {

    /** 七类卡的推荐构成（顺序无关，是集合不是序列）。 */
    val recommended: Map<String, Set<CardBlock>> = mapOf(
        "task-board" to setOf(CardBlock.Progress, CardBlock.StepList, CardBlock.ButtonGroup),
        "approval" to setOf(CardBlock.Banner, CardBlock.DiffViewer, CardBlock.ButtonGroup),
        "result" to setOf(CardBlock.Banner, CardBlock.StepList, CardBlock.DiffViewer, CardBlock.ArtifactList),
        // 生命周期轻通知：只有横幅。给它按钮就把「不抢眼」破坏了。
        "status" to setOf(CardBlock.Banner),
        "timeout" to setOf(CardBlock.Banner, CardBlock.ButtonGroup),
        "diff-review" to setOf(CardBlock.DiffViewer, CardBlock.ButtonGroup),
        "assignment" to setOf(CardBlock.StepList),
    )

    /** 查建议构成；未知 type（含 null / 空白）返回 null = 没有建议，不是拒绝。 */
    fun recommendedBlocksOf(type: String?): Set<CardBlock>? =
        type?.trim()?.takeIf { it.isNotEmpty() }?.let { recommended[it] }
}

/**
 * 这张卡**实际**有哪些块 —— 从数据推，不看注册表。
 *
 * 与 [CardTypeRegistry] 的关系是「建议 vs 事实」：注册表说 approval 该有横幅与按钮，
 * 但插件没给横幅，就没有横幅。空卡片不会因为 type 对了就凭空长出内容。
 */
fun presentBlocksOf(card: AgentCardUi): Set<CardBlock> {
    val blocks = mutableSetOf<CardBlock>()
    if (card.banner != null) blocks += CardBlock.Banner
    if (card.progress != null) blocks += CardBlock.Progress
    if (card.steps.isNotEmpty()) blocks += CardBlock.StepList
    if (card.diff != null) blocks += CardBlock.DiffViewer
    if (card.actions.isNotEmpty()) blocks += CardBlock.ButtonGroup
    if (card.artifacts.isNotEmpty()) blocks += CardBlock.ArtifactList
    return blocks
}

/**
 * 这台设备上的人**能不能点这张卡的按钮**（设计稿 v2 §4.8）。
 *
 * 三态而不是布尔量：只读态要的是「**整块不画按钮组**」（按下去没反应的按钮比没有更糟，
 * M3 的既有原则），而 agent 回声要的是「跳过交互」—— 这两件事在渲染上不同，
 * 用 `false` 一个值表达不了。
 */
enum class CardActionEligibility {
    /** 有资格：画按钮组，可点。 */
    Actionable,

    /** 无资格：**不画按钮组**，只读形态。 */
    ReadOnly,

    /** 这条消息是 agent 自己发的：回声不渲染交互。 */
    AgentEcho,
}

/**
 * 把 `AgentExecAuthorizer` 的判定翻成卡片交互资格。
 *
 * **不维护第二份名单**：能不能点完全由 [AgentExecAuthorizer.Verdict.permitsExecution]
 * 决定（判定在宿主侧，它才有权威授权表；UI 只消费结论）。将来枚举多一档，
 * 这里自动跟着走，不会有一档静默变成可点。
 *
 * 判不了（`verdict == null`：没有群元数据 / 没有台账）⇒ [CardActionEligibility.ReadOnly]。
 * fail-closed：「不知道能不能」和「能」不是一回事。
 */
fun cardActionEligibilityOf(
    senderIsAgent: Boolean,
    verdict: AgentExecAuthorizer.Verdict?,
): CardActionEligibility {
    if (senderIsAgent) return CardActionEligibility.AgentEcho
    val permitted = verdict != null && AgentExecAuthorizer.run { verdict.permitsExecution() }
    return if (permitted) CardActionEligibility.Actionable else CardActionEligibility.ReadOnly
}

/**
 * 一张卡**这一刻该画成什么样**（渲染决策，纯函数）。
 *
 * 为什么把它从 Composable 里抽出来：`AgentCardBubble` 有五条「画不画」的分支
 * （有没有动作通路 / 有没有资格 / 过没过期 / 是不是回声 / 有没有已决人），
 * 写在 `if` 里就只能靠截图验；抽成数据以后，判据是普通的 assert，
 * 帧只是它的投影。这也是本仓 `cardActionUiOf` 一开始就用的形状。
 */
data class CardRenderPlan(
    /** 画按钮组吗？无资格、agent 回声、壳没有动作通路 —— 三者任一都不画。 */
    val showActions: Boolean,
    /** 画了的话按钮能不能点。过期 ⇒ 全部禁用（`cardActionUiOf` 的三态之上再压一层）。 */
    val actionsEnabled: Boolean,
    /** 过期标签：`expiresAt` 已到。 */
    val showExpiredTag: Boolean,
    /** 审批卡 + 无资格 ⇒ 「等待机主处理」。没有这行，只读态就是一张「不知道为什么没有按钮」的卡。 */
    val showWaitingForHost: Boolean,
)

/**
 * 算这张卡的渲染决策。
 *
 * [hasActionChannel] 是「这个壳有没有动作通路」：没有通路时按钮组整块不画
 * （按下去没反应的按钮比没有更糟，M3 既有原则）。
 * [now] 由调用方给，组件自己不读时钟 —— 与 [isCardExpired] 同一条理由。
 */
fun cardRenderPlanOf(
    card: AgentCardUi,
    eligibility: CardActionEligibility,
    hasActionChannel: Boolean,
    now: Long,
): CardRenderPlan {
    val expired = isCardExpired(card.expiresAt, now)
    val hasActions = card.actions.isNotEmpty()
    return CardRenderPlan(
        showActions = hasActionChannel && hasActions && eligibility == CardActionEligibility.Actionable,
        actionsEnabled = !expired,
        showExpiredTag = expired,
        showWaitingForHost = hasActions && eligibility == CardActionEligibility.ReadOnly,
    )
}

/**
 * 产物体积的人读形式（ASCII 单位，不翻译：`KB`/`MB` 在两种语言里是同一个词）。
 *
 * 只在卡片里显示，不进协议、不参与判定 —— 所以它容忍「不知道」（null 由调用方跳过）。
 */
fun formatArtifactSize(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${bytes / 1024}.${(bytes % 1024) * 10 / 1024} KB"
    bytes < 1024L * 1024L * 1024L -> "${bytes / (1024L * 1024L)}.${(bytes % (1024L * 1024L)) * 10 / (1024L * 1024L)} MB"
    else -> "${bytes / (1024L * 1024L * 1024L)}.${(bytes % (1024L * 1024L * 1024L)) * 10 / (1024L * 1024L * 1024L)} GB"
}

// ── 插件文本字段的渲染上限（§7）─────────────────────────────────────────
//
// 卡片体是**插件定义**的：一条超长 label / summary 不该把整张卡撑破，也不该把
// 同一屏的其它消息挤走。上限集中在这里，不散落在各 `maxLines` 调用点 ——
// 改上限时只改一处，也不会出现「标题收 2 行、横幅收 5 行」这种不一致。
private const val CARD_TITLE_MAX_LINES = 2
private const val CARD_BANNER_MAX_LINES = 3
private const val CARD_STEP_LABEL_MAX_LINES = 2
private const val CARD_DIFF_SUMMARY_MAX_LINES = 2
private const val CARD_ARTIFACT_NAME_MAX_LINES = 1

/** 摘要超过这个字符数才给「展开」入口：短摘要配一个展开按钮是骗点击。 */
private const val CARD_DIFF_SUMMARY_EXPAND_OVER = 48

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
 * [onAction] 是可空槽：null = 这个壳还没有动作通路，按钮组**整块不画** ——
 * 一个按下去没反应的按钮比没有更糟（DeadButtonGuard 同一条规矩）。
 * [eligibility] 是「这个人有没有资格驱动这个 agent」（§4.8 的结论，判定在宿主侧）：
 * [CardActionEligibility.ReadOnly] 同样整块不画按钮，而不是画一颗灰按钮。
 * [now] 由调用方给（会话里已经有一份时钟），组件自己不读 —— 过期子态因此可测、可截帧。
 *
 * 画不画、能不能点，全部由 [cardRenderPlanOf] 一个纯函数决定；这里只负责把它画出来。
 * `pendingActions` 由调用方持有（会话切换即重建），本组件不存状态。
 */
@Composable
fun AgentCardBubble(
    card: AgentCardUi,
    pendingActions: Set<String>,
    onAction: ((card: AgentCardUi, actionId: String) -> Unit)?,
    eligibility: CardActionEligibility = CardActionEligibility.Actionable,
    now: Long = 0L,
    modifier: Modifier = Modifier,
) {
    val sp = LocalAppSpacing.current
    val plan = cardRenderPlanOf(
        card = card,
        eligibility = eligibility,
        hasActionChannel = onAction != null,
        now = now,
    )
    Surface(
        // 原型 .acard{border-radius:var(--r-card)}，--r-card:16 —— 大圆角卡族
        // （M3 shapes.large=16）；ListCard(11) 是行卡族的角色，整幅宽的独立消息卡不是行卡。
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        // 原型 .acard 的结构（prototype/relay.html .acard 区块）：壳只管圆角/底色/描边，
        // **每个块自带内边距**，块与块之间靠 padding 与发丝线分隔（.st+.st / .art / .acts
        // 的 border-top）—— 不是壳统一缩进。照抄统一缩进会让色块与卡缘之间多出一圈
        // 卡面同色的空带，1280 宽下尤其明显。
        Column(modifier = Modifier.fillMaxWidth()) {
            // 头行（.hd{padding:10px 14px 8px}）：type 徽标 + 标题（.tt）+ rev 游标（.rv）。
            // 没有任何头部信息就不画头行 —— 不编一个。
            if (card.type != null || card.title != null || card.rev > 0 || plan.showExpiredTag) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(sp.sm6),
                    modifier = Modifier.padding(start = sp.lg14, end = sp.lg14, top = sp.md10, bottom = sp.sm),
                ) {
                    card.type?.let { CardTypeTag(it) }
                    if (card.title != null) {
                        Text(
                            text = card.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                            maxLines = CARD_TITLE_MAX_LINES,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Box(Modifier.weight(1f))
                    }
                    if (plan.showExpiredTag) {
                        // 过期标签（.apst.exp）：中性灰对 —— 原型注释原话「过期不是错误，
                        // 是『不再可用』」。以前挂 error 说不出出处。
                        CardNeutralTag(text = stringResource(Res.string.card_expired))
                    }
                    Text(
                        text = stringResource(Res.string.card_rev_tag, card.rev),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            card.banner?.let { CardBannerRow(it) }
            card.progress?.let { CardProgressRow(it) }
            if (card.steps.isNotEmpty()) {
                CardStepListColumn(card.steps)
            }
            card.diff?.let { CardDiffViewerRow(it) }
            if (card.artifacts.isNotEmpty()) {
                CardArtifactRows(card.artifacts)
            }
            // 宿主判定的知情行（.decided{padding:6px 14px 10px}，11px on-2）：
            // 它不是按钮，是「这件事已被谁定了」的事实 —— 就算这个壳没有动作通路
            // （onAction == null），它也照画。
            card.decidedBy?.let { decided ->
                Text(
                    text = stringResource(Res.string.card_decided_by, decided),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = sp.lg14, end = sp.lg14, top = sp.sm6, bottom = sp.md10),
                )
            }
            // 只读态必须说清「为什么没有按钮」（.wait 同 .decided 的位置与字阶）。
            if (plan.showWaitingForHost) {
                Text(
                    text = stringResource(Res.string.card_waiting_host),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = sp.lg14, end = sp.lg14, top = sp.sm6, bottom = sp.md10),
                )
            }
            if (plan.showActions) {
                CardButtonGroupRow(card, pendingActions, plan.actionsEnabled, onAction!!)
            }
        }
    }
}

/**
 * 头行的 type 徽标（原型 `.hd` 里的 `.tag.agent`：agent-c/agent-t 对、11px 600、
 * 胶囊圆角、1.5×6 内边距）。type 是**内容**（插件给的），原样渲染、不进 strings 体系。
 */
@Composable
private fun CardTypeTag(type: String) {
    val sp = LocalAppSpacing.current
    Text(
        text = type,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.accents.agentAccentText,
        modifier = Modifier
            .background(MaterialTheme.accents.agentAccentContainer, RoundedCornerShape(50))
            .padding(horizontal = sp.sm, vertical = 1.5.dp),
    )
}

/**
 * 中性状态小标签（原型 `.apst.exp`：`--surface-2 / --on-2` —— 原型注释原话
 * 「过期不是错误，是『不再可用』」）。圆角取 M3 shapes.small（8）：
 * `.apst` 的 6px 属低频档外值，按 ShapeRoles 表头的裁决留给并排截图就近归并。
 */
@Composable
private fun CardNeutralTag(text: String) {
    val sp = LocalAppSpacing.current
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .background(SurfaceTiers.Raised, MaterialTheme.shapes.small)
            .padding(horizontal = sp.sm, vertical = 1.5.dp),
    )
}

/** 原子 1/5 · Banner（§7.3）：一行带色的说明。 */
@Composable
private fun CardBannerRow(banner: AgentCardBanner) {
    BannerSurface(tone = banner.tone) { BannerText(banner.text) }
}

/**
 * 横幅的**外壳**（圆角 + 底色 + 内边距）：卡内 Banner 与群流执行状态横幅共用。
 *
 * 抽出来的理由与 [bannerColorsOf] 相同，只是更进一步：只共用颜色、不共用外壳，
 * 两处的圆角/内边距迟早会各走各的 —— 那时同一个「失败」不但颜色对不上，
 * 连块的大小都不一样，看起来像两个不同组件在说两件事。
 *
 * 形状 = 控件圆角 12（原型 `.banner{border-radius:var(--r-ctl)}`，`--r-ctl:12`）。
 * 以前挂 [ShapeRoles.DayCell]（7dp）说不出出处——横幅不是历史搜索日期格。
 * 内边距由 [BannerText] 自带（原型 `.bn{padding:8px 14px}`），壳不再叠加。
 */
@Composable
private fun BannerSurface(
    tone: AgentCardBannerTone,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val (container, contentColor) = bannerColorsOf(tone)
    Surface(shape = MaterialTheme.shapes.medium, color = container, contentColor = contentColor, modifier = modifier) {
        content()
    }
}

/** 横幅的**正文**（字阶 / 行数上限 / 省略）。与 [BannerSurface] 成对，一处定义。 */
@Composable
private fun BannerText(text: String) {
    val sp = LocalAppSpacing.current
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        maxLines = CARD_BANNER_MAX_LINES,
        overflow = TextOverflow.Ellipsis,
        // 原型 .bn{padding:8px 14px}：横幅正文自带内边距（--fs 12px 档 = bodySmall）。
        modifier = Modifier.fillMaxWidth().padding(horizontal = sp.lg14, vertical = sp.sm),
    )
}

/**
 * 色调 → (容器色, 内容色)。**只此一份**：卡内 Banner 与群流里的执行状态横幅共用它，
 * 否则同一个「失败」在两处会长成两种红（§2.6 一个 UI 元素只有一处定义）。
 *
 * 四对取值逐字对齐原型 `.acard` 的横幅区块（prototype/relay.html `.bn` 系列与
 * `.banner` 系列，两处共用同一组语义色对）：
 * - Info = `--surface-2 / --on-2`（代码侧即 surfaceVariant / onSurfaceVariant，值逐字相同）；
 * - Success(`done`) = `--primary-c / --on-primary-c`（= primaryContainer / onPrimaryContainer）；
 * - Warn = `--queued-c / --on-queued-c`（= secondaryContainer / onSecondaryContainer）；
 * - Error = `--error-c / --on-error-c`（= errorContainer / onErrorContainer，本就正确）。
 *
 * 以前 Info 挂在 secondaryContainer（=`--queued-c`，跟 Warn 撞对）、Warn 挂
 * tertiaryContainer（原型里不存在这个蓝调对）、Success 用 online 叠 16% alpha 合成——
 * 三者都是「说不出原型出处」的取值；合成 alpha 同时是一处二次 alpha（对比度审计 D11 族）。
 */
@Composable
private fun bannerColorsOf(tone: AgentCardBannerTone): Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color> =
    when (tone) {
        AgentCardBannerTone.Info ->
            MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        AgentCardBannerTone.Success ->
            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        AgentCardBannerTone.Warn ->
            MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        AgentCardBannerTone.Error ->
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }

/**
 * 执行状态四态 → 横幅色调（设计稿 v2 §3 的 `status` 口径：
 * accept→Info、blocked→Warn、completed→Done、failed→Danger）。
 *
 * **未知状态返回 null ⇒ 不画**：协议层已经只放行这四个值，走到这里的未知值只可能来自
 * 更早版本的库或手工构造的测试数据 —— 画一个没有色调的横幅比不画更让人困惑。
 */
fun agentStateBannerToneOf(state: String?): AgentCardBannerTone? = when (state) {
    "accept" -> AgentCardBannerTone.Info
    "blocked" -> AgentCardBannerTone.Warn
    "completed" -> AgentCardBannerTone.Success
    "failed" -> AgentCardBannerTone.Error
    else -> null
}

/**
 * 群流里的**执行状态轻横幅**（设计稿 v2 §5「失败如实」）。
 *
 * 数据来源是消息库里的 `MessageUi.agentState`（协议 `agentState` 键，M1 已在白名单）：
 * 宿主在「插件接不了活 / 执行没做成」时发一条 agent 署名的群文本，正文由领域侧的
 * `RelayUiTexts` 给出。这里只管画 —— 判定在领域层，措辞在 strings.xml。
 *
 * 为什么不是气泡：它不是「谁说了句话」，是「这次派单没成」的状态说明。用横幅画，
 * 位置与语气都不抢卡片，也不会被当成一条可以回复的消息。
 *
 * 正文为空时（帧由没有文案桥的壳发出）用一句本地兜底：**空横幅等于沉默**，
 * 而沉默正是这个组件存在的理由。
 */
@Composable
fun AgentStateBannerRow(state: String, text: String, modifier: Modifier = Modifier) {
    val tone = agentStateBannerToneOf(state) ?: return
    BannerSurface(tone = tone, modifier = modifier.fillMaxWidth()) {
        BannerText(
            text.ifBlank {
                if (state == "failed") stringResource(Res.string.agent_state_banner_failed) else ""
            },
        )
    }
}

/** 原子 2/5 · Progress（§7.3）：6px 细条 + 等宽百分比（原型 `.pg` 区块）。 */
@Composable
private fun CardProgressRow(progress: Int) {
    val sp = LocalAppSpacing.current
    val label = stringResource(Res.string.card_progress_label, progress)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(sp.sm),
        // .pg{padding:8px 14px 4px}
        modifier = Modifier
            .padding(start = sp.lg14, end = sp.lg14, top = sp.sm, bottom = sp.xs)
            .semantics { contentDescription = label },
    ) {
        // .pg .bar：轨道 = --surface-3（小内嵌控件那档，SurfaceTiers.Inset），圆角
        // --r-chip（胶囊）。以前挂 surfaceVariant（=--surface-2，标签档）说不出出处。
        Surface(
            shape = RoundedCornerShape(50),
            color = SurfaceTiers.Inset,
            modifier = Modifier.weight(1f).height(6.dp),
        ) {
            Box(Modifier.fillMaxWidth()) {
                // .pg .bar i：已走的部分画 primary 填充 —— 进度条没有填充就不是进度条
                // （0% 帧自然为空，P1 验证点不受影响；100% 满条，P3 验证点看得到终态）。
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress / 100f)
                        .height(6.dp)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        // .pg .pc：11px on-2 等宽（tabular-nums 由 Mono 字体承担）。
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * 原子 3/5 · StepList（§7.3）：状态字形 + 标签 + 承办人徽标，行间发丝线
 * （原型 `.sts{padding:4px 14px 8px}` + `.st+.st{border-top:1px solid var(--hair)}`）。
 */
@Composable
private fun CardStepListColumn(steps: List<AgentCardStep>) {
    val sp = LocalAppSpacing.current
    Column(
        // .sts 的上下 padding 由首尾行自带；这里只管容器。
        modifier = Modifier.padding(top = sp.xs, bottom = sp.sm),
    ) {
        steps.forEachIndexed { index, step ->
            if (index > 0) {
                HorizontalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(horizontal = sp.lg14),
                )
            }
            CardStepRow(step)
        }
    }
}

@Composable
private fun CardStepRow(step: AgentCardStep) {
    val sp = LocalAppSpacing.current
    val statusGlyph = when (step.status) {
        // 字形对齐原型 ST 表（.st 五态：✓ done / ▶ run / ○ pend / ✕ fail / ⏸ blk）。
        AgentCardStepStatus.Done -> "✓"
        AgentCardStepStatus.Running -> "▶"
        AgentCardStepStatus.Pending -> "○"
        AgentCardStepStatus.Failed -> "✕"
        AgentCardStepStatus.Blocked -> "⏸"
    }
    val statusColor = when (step.status) {
        // .st.done→--online / .run→--primary / .pend→--outline / .fail→--error /
        // .blk→--on-queued-c（queued 对的内容色，即 onSecondaryContainer）。
        AgentCardStepStatus.Done -> MaterialTheme.accents.online
        AgentCardStepStatus.Running -> MaterialTheme.colorScheme.primary
        AgentCardStepStatus.Failed -> MaterialTheme.colorScheme.error
        AgentCardStepStatus.Blocked -> MaterialTheme.colorScheme.onSecondaryContainer
        AgentCardStepStatus.Pending -> MaterialTheme.colorScheme.outline
    }
    // 状态全称仍以语义描述进无障碍树（.st .ic 只有字形，屏幕阅读器需要全称）。
    val statusLabel = when (step.status) {
        AgentCardStepStatus.Done -> stringResource(Res.string.card_status_done)
        AgentCardStepStatus.Running -> stringResource(Res.string.card_status_running)
        AgentCardStepStatus.Pending -> stringResource(Res.string.card_status_pending)
        AgentCardStepStatus.Failed -> stringResource(Res.string.card_status_failed)
        AgentCardStepStatus.Blocked -> stringResource(Res.string.card_status_blocked)
    }
    Row(
        // .st{padding:5px 0}：行的竖向内边距，横向缩进由容器 .sts 的 14px 承担。
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(sp.sm),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = sp.lg14, vertical = sp.oxs5)
            .semantics { contentDescription = statusLabel },
    ) {
        // .st .ic：16px 字形槽（flex:none;width:16px;text-align:center）。
        Text(
            text = statusGlyph,
            style = MaterialTheme.typography.bodySmall,
            color = statusColor,
            modifier = Modifier.size(IconSizes.Label),
            textAlign = TextAlign.Center,
        )
        Text(
            text = step.label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = CARD_STEP_LABEL_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // .st .asg：承办人徽标 —— agent 紫对（.tag.agent 同款：agent-c/agent-t）。
        step.assignee?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.accents.agentAccentText,
                modifier = Modifier
                    .background(MaterialTheme.accents.agentAccentContainer, RoundedCornerShape(50))
                    .padding(horizontal = sp.osm7, vertical = 1.5.dp),
            )
        }
    }
}

/**
 * 原子 4/5 · DiffViewer（§7.3）：路径/摘要一行，+ − 计数靠色。
 *
 * 摘要默认收 [CARD_DIFF_SUMMARY_MAX_LINES] 行；**只有真的可能被截断时才给「展开」**
 * （设计稿 v2 §7 的「明细折叠入口」）。短摘要配一个展开按钮是骗点击 ——
 * 点开以后什么都没变，用户会以为按钮坏了。
 */
@Composable
private fun CardDiffViewerRow(diff: AgentCardDiff) {
    val sp = LocalAppSpacing.current
    var expanded by remember(diff.summary) { mutableStateOf(false) }
    val summary = diff.summary
    val expandable = summary != null && summary.length > CARD_DIFF_SUMMARY_EXPAND_OVER
    // 原型 .df{padding:8px 14px}：无底色的等宽块（路径 on、+add/−del 计数、note 12px on-2）。
    // 以前套 surfaceVariant 小卡 + 「代码变更」标题 —— 原型 .acard .df 区块里没有这两个东西。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = sp.lg14, vertical = sp.sm),
        verticalArrangement = Arrangement.spacedBy(sp.xxs),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(sp.sm)) {
            if (diff.path != null) {
                // .df .pt{color:var(--on)}；word-break:break-all 由省略承担。
                Text(
                    text = diff.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            if (diff.additions != null || diff.deletions != null) {
                // .df .add{color:var(--online)} / .df .del{color:var(--error)}（git 惯例对）。
                Text(
                    text = "+${diff.additions ?: 0}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.accents.online,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = "−${diff.deletions ?: 0}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        if (summary != null) {
                    Text(
                        text = stringResource(Res.string.card_diff_summary, summary, diff.additions ?: 0, diff.deletions ?: 0),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else CARD_DIFF_SUMMARY_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (expandable) {
            AppButton(
                text = stringResource(
                    if (expanded) Res.string.card_details_hide else Res.string.card_details_show,
                ),
                onClick = { expanded = !expanded },
                size = AppButtonSize.Small,
                tone = AppButtonTone.Neutral,
            )
        }
    }
}

/** 原子 6/6 · ArtifactList（设计稿 v2 §3 的 `result` 卡）：产物名 + 类型/体积（原型 `.art` 区块）。 */
@Composable
private fun CardArtifactRows(artifacts: List<AgentCardArtifact>) {
    val sp = LocalAppSpacing.current
    Column {
        artifacts.forEachIndexed { index, artifact ->
            if (index > 0) {
                // .art{border-top:1px solid var(--hair)}：条目间发丝线。
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            // .art{display:flex;gap:8px;padding:8px 14px}
            Row(
                horizontalArrangement = Arrangement.spacedBy(sp.sm),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = sp.lg14, vertical = sp.sm),
            ) {
                // .art .nm：14px primary 600，单行省略（--fs-body 档 = bodyMedium）。
                Text(
                    text = artifact.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = CARD_ARTIFACT_NAME_MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // .art .mt：11px on-2 等宽。
                val meta = listOfNotNull(
                    artifact.mime,
                    artifact.bytes?.let { formatArtifactSize(it) },
                ).joinToString(SEP_META)
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 元信息分隔符：一处定义，改样式不用翻三个调用点。 */
private const val SEP_META = " · "

/** 原子 5/5 · ButtonGroup（§7.3）：审批防重的 UI 落点（三态见 [cardActionUiOf]）。 */
@Composable
private fun CardButtonGroupRow(
    card: AgentCardUi,
    pendingActions: Set<String>,
    /** 过期时整组禁用：这是 [CardRenderPlan.actionsEnabled]，不是每颗按钮各自的事。 */
    actionsEnabled: Boolean,
    onAction: (card: AgentCardUi, actionId: String) -> Unit,
) {
    val sp = LocalAppSpacing.current
    // .acts{border-top:1px solid var(--hair)} + {padding:10px 14px 12px}：
    // 按钮组是卡片的收尾块，与上面内容之间一条发丝线（.hd/.pg/.sts/.df 同一个体系）。
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            horizontalArrangement = Arrangement.spacedBy(sp.sm),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = sp.lg14, end = sp.lg14, top = sp.md10, bottom = sp.md),
        ) {
            // 原型 `.acts` 的按钮是 `.btn.sm(`.pri`?)`（relay.html 的 agentCard 模板）：
            // **实心只给 `.pri`**（审批/主行动），其余走 `.btn` 基类 —— surface 底 +
            // 描边（relay.html 的 `.btn` 基类）。原型注释原话「之前那颗漏了 `.pri`，两颗长得
            // 一模一样」—— AppButton 的 Neutral 分支恰是那个形态（实心 primary），
            // 所以这里与 .appr 行同一做法：非审批用 [AppOutlinedButton]（描边档，
            // 描边色 outlineStrong = WP-D1 收编后的控件描边口径，原型 `.btn` 的
            // `.outline` 描边正是被 D2 清账的对象）。按钮宽按内容（`.acts` 是
            // inline-flex，不均分；48dp 触控下限由按钮组件自带兜底）。
            card.actions.forEach { action ->
                val ui = cardActionUiOf(card, pendingActions, action.actionId)
                val text = when (ui.lock) {
                    CardActionLock.PendingConfirm -> stringResource(Res.string.card_pending_confirm)
                    else -> action.label
                }
                if (action.approval) {
                    AppButton(
                        text = text,
                        onClick = { onAction(card, action.actionId) },
                        enabled = actionsEnabled && !ui.disabled,
                        size = AppButtonSize.Small,
                        tone = AppButtonTone.Primary,
                    )
                } else {
                    AppOutlinedButton(
                        text = text,
                        onClick = { onAction(card, action.actionId) },
                        enabled = actionsEnabled && !ui.disabled,
                        size = AppButtonSize.Small,
                        tone = AppButtonTone.Neutral,
                    )
                }
            }
        }
    }
}
