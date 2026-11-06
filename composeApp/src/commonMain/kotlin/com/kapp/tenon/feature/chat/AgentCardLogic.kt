// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.chat

import com.kapp.tenon.domain.SYSTEM_ACTION_SESSION_EXEMPT
import com.kapp.tenon.domain.sessionExemptActionIdOf
// 会话免审动作 id 的编解码是**领域契约**（编码侧手机、解码侧宿主共用一份），
// 住在 :app；这几个文件只做判定与渲染，不重抄那份编码。
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.kapp.tenon.core.protocol.AgentExecAuthorizer
import com.kapp.tenon.generated.resources.card_session_exempt_active

/**
 * 卡片的**纯函数策略层**：折叠/资格/渲染计划/上限裁剪/审批锁三态。
 *
 * 这里一行 Compose 都没有，所以每条策略都能用合成输入直接测（[cardRowOutcome]、
 * [cardActionEligibilityOf]、[cardRenderPlanOf] 等）。UI 只消费它们的返回值 ——
 * 判断留在这一层，是为了「同一个判断在手机和桌面都成立」不靠两份代码保持一致。
 *
 * ## 为什么有几条是 `internal` 而不是 `private`
 *
 * Kotlin 的 `private` 是**文件级**的。ARP-4 把原来一个文件里的渲染代码拆成
 * `AgentCards.kt` / `AgentCardParts.kt` 之后，被两边同时读的那几个渲染上限常量
 * （以及 `artifactMetaLine`）就必须跨文件可见。放宽只到这里：`internal`（模块内），
 * 没有一条升到 `public` —— 这些仍然是"本引擎怎么排版"的细节，不是给外人用的契约。
 */

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
    /** 反向多选（升级稿 ask_question，detailed-design §7.9）：预设选项点选。 */
    OptionList,
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
        // 反向多选（§7.9）：横幅说问题，选项块给决策 —— 给它按钮之外的块是稀释。
        "ask_question" to setOf(CardBlock.Banner, CardBlock.OptionList),
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
    if (card.options.isNotEmpty()) blocks += CardBlock.OptionList
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

/** 元信息行：mime 与人读体积拼一行（新稿 .art .mt 的单行 meta）。
 * 两者都缺 = 没有元信息（null 由调用方跳过 —— 与 formatArtifactSize 同一条口径）。
 */
internal fun artifactMetaLine(artifact: AgentCardArtifact): String? =
    listOfNotNull(artifact.mime, artifact.bytes?.let { formatArtifactSize(it) }).joinToString(SEP_META).ifBlank { null }

/** 元信息分隔符：一处定义。 */
private const val SEP_META = " · "

// ── 插件文本字段的渲染上限（§7）─────────────────────────────────────────
//
// 卡片体是**插件定义**的：一条超长 label / summary 不该把整张卡撑破，也不该把
// 同一屏的其它消息挤走。上限集中在这里，不散落在各 `maxLines` 调用点 ——
// 改上限时只改一处，也不会出现「标题收 2 行、横幅收 5 行」这种不一致。
internal const val CARD_TITLE_MAX_LINES = 2
internal const val CARD_BANNER_MAX_LINES = 3
internal const val CARD_STEP_LABEL_MAX_LINES = 2
internal const val CARD_DIFF_SUMMARY_MAX_LINES = 2
internal const val CARD_ARTIFACT_NAME_MAX_LINES = 1

/** 摘要超过这个字符数才给「展开」入口：短摘要配一个展开按钮是骗点击。 */
internal const val CARD_DIFF_SUMMARY_EXPAND_OVER = 48

// ── Acceptance 渲染策略（升级计划 Phase 2 的纯函数层）───────────────────
//
// 与上面的策略层同一条纪律：只吃入参、不读时钟、不碰 Compose —— 判据抽成数据，
// 帧只是它的投影，单测用合成输入钉死。

/** Hunk 视窗默认只露的行数；超出的部分进「展开」，收起态不截断成 0 行。 */
const val CARD_HUNK_COLLAPSED_LINES = 6

/** 打回动作的 actionId 前缀：反馈文本以它编码（见 [rejectRevisionActionIdOf]）。 */
const val SYSTEM_ACTION_REJECT_REVISION = "reject-revision"

/**
 * 打回动作与反馈文本的**单槽编码**：`reject-revision|<文本>`。
 *
 * 为什么挤进一个字符串：动作通路 [CardActionUi] 之下只有 actionId 一个字符串槽 ——
 * 域层 `handleCardAction` 的签名与 invoke 帧白名单都只认 actionId（帧预算与
 * 严格白名单是协议事实，渲染层不扩）。 Phase 4 的宿主编排器按前缀拆开，
 * 竖线后面的文本进下一轮自愈的前置约束。
 *
 * 编码侧是纯函数、解码侧在宿主（Phase 4）：竖线替换在**这里**做而不是宿主做，
 * 否则「文本里带竖线」会成为两端各猜一次的歧义。
 */
fun rejectRevisionActionIdOf(feedbackText: String): String {
    val cleaned = feedbackText.trim()
    if (cleaned.isEmpty()) return SYSTEM_ACTION_REJECT_REVISION
    return "$SYSTEM_ACTION_REJECT_REVISION|${cleaned.replace("|", "/")}"
}

/** 见 [rejectRevisionActionIdOf]：非打回前缀原样返回 null，空反馈返回空串。 */
fun feedbackTextOf(actionId: String): String? =
    actionId.removePrefix("$SYSTEM_ACTION_REJECT_REVISION|")
        .takeIf { actionId != it }

/**
 * 这张卡的会话免审**此刻**是否已生效（纯函数）：
 * 宿主回放的 subActions 带 `style=active` 是已生效的事实；本机动作已在路上
 * （pendingActions 命中 `cardId|<免审动作串>`）算进行中 —— 两种都按「已免审」渲染，
 * 开启态文案走 `card_session_exempt_active`。判定只吃入参，测试用合成输入钉死。
 */
fun isSessionExemptActive(card: AgentCardUi, pendingActions: Set<String>): Boolean {
    val offered = card.subActions.firstOrNull { it.actionId == SYSTEM_ACTION_SESSION_EXEMPT }
        ?: return false
    if (offered.style == "active") return true
    return card.cardId + "|" + sessionExemptActionIdOf(card.cardId) in pendingActions
}

/**
 * Hunk 行的解析结果：行号（上下文行从 hunk 头 `@@ -a,b +c,d @@` 推演）、
 * 增删极性。解析不了行号（hunk 头缺失或行序被插件截乱）⇒ [number] = null，
 * 行照样渲染 —— 少一个 gutter 数字比整段不画诚实。
 */
data class AgentCardHunkLine(
    val text: String,
    val kind: AgentCardHunkKind,
    val number: Int?,
)

enum class AgentCardHunkKind { Add, Del, Context, HunkHead }

/**
 * 把插件的原始 hunk 行翻成带行号的渲染行（升级计划 Phase 2 要点 3：等宽右对齐行号 +
 * 新增绿 / 删除红微底）。
 *
 * 行号**只从 hunk 头推演**：`@@ -10,7 +10,8 @@` 给出旧起新起两列，代码取新侧；
 * 每遇一行按极性推进（上下文两侧同进，删除不动新侧光标）。头缺失或行序被截乱时
 * 尾行可能对不上真实行号 —— 那是插件侧截断的事，渲染端不猜第二遍：
 * 推演不出的行 [AgentCardHunkLine.number] 为 null，gutter 留空。
 */
fun agentCardHunkLinesOf(hunk: List<String>): List<AgentCardHunkLine> {
    val out = mutableListOf<AgentCardHunkLine>()
    var newSide: Int? = null
    for (raw in hunk) {
        val kind = when {
            raw.startsWith("+++") || raw.startsWith("---") -> AgentCardHunkKind.HunkHead
            raw.startsWith("@@") -> {
                // `@@ -45,7 +45,8 @@ …`：新侧起点取第二个范围的起始行。
                newSide = Regex("@\\s*-(\\d+)(?:,\\d+)?\\s+\\+(\\d+)")
                    .find(raw)?.groupValues?.get(2)?.toIntOrNull()
                AgentCardHunkKind.HunkHead
            }
            raw.startsWith("+") -> AgentCardHunkKind.Add
            raw.startsWith("-") -> AgentCardHunkKind.Del
            else -> AgentCardHunkKind.Context
        }
        // 上下文与新增行各占新侧一个行号并推进光标；删除行只存在于旧侧，
        // 不动新侧光标也不领号 —— 这与 `git diff` 的行号语义一致。
        val number = when (kind) {
            AgentCardHunkKind.HunkHead, AgentCardHunkKind.Del -> null
            AgentCardHunkKind.Context, AgentCardHunkKind.Add -> newSide
        }
        if (kind == AgentCardHunkKind.Context || kind == AgentCardHunkKind.Add) {
            newSide = newSide?.plus(1)
        }
        out += AgentCardHunkLine(text = raw, kind = kind, number = number)
    }
    return out
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
