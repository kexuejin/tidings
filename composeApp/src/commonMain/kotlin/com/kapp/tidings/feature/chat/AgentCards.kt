// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Spacer
import com.kapp.tidings.core.protocol.AgentExecAuthorizer
import com.kapp.tidings.domain.model.MessageUi
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.ic_file
import com.kapp.tidings.generated.resources.ic_terminal
import com.kapp.tidings.generated.resources.ic_bot
import com.kapp.tidings.generated.resources.ic_shield
import com.kapp.tidings.generated.resources.ic_list
import com.kapp.tidings.generated.resources.ic_check_double
import com.kapp.tidings.generated.resources.ic_refresh
import com.kapp.tidings.generated.resources.ic_info
import com.kapp.tidings.generated.resources.ic_warn
import com.kapp.tidings.generated.resources.ic_close
import com.kapp.tidings.generated.resources.ic_chev
import com.kapp.tidings.generated.resources.ic_chevd
import com.kapp.tidings.generated.resources.card_check_drawer_close
import com.kapp.tidings.generated.resources.card_check_drawer_open
import com.kapp.tidings.generated.resources.card_check_time_format
import com.kapp.tidings.generated.resources.card_diff_collapse_code
import com.kapp.tidings.generated.resources.card_diff_expand_code
import com.kapp.tidings.generated.resources.card_revision_chip_compat
import com.kapp.tidings.generated.resources.card_revision_chip_contrast
import com.kapp.tidings.generated.resources.card_revision_chip_perf
import com.kapp.tidings.generated.resources.card_revision_chip_test
import com.kapp.tidings.generated.resources.card_revision_hint
import com.kapp.tidings.generated.resources.card_revision_submit
import com.kapp.tidings.generated.resources.card_session_exempt
import com.kapp.tidings.generated.resources.card_session_exempt_active
import com.kapp.tidings.generated.resources.card_decided_by
import com.kapp.tidings.generated.resources.card_details_hide
import com.kapp.tidings.generated.resources.card_details_show
import com.kapp.tidings.generated.resources.card_expired
import com.kapp.tidings.generated.resources.card_option_recommended
import com.kapp.tidings.generated.resources.card_action_abort
import com.kapp.tidings.generated.resources.card_action_merge_patch
import com.kapp.tidings.generated.resources.card_pending_confirm
import com.kapp.tidings.generated.resources.card_progress_label
import com.kapp.tidings.generated.resources.card_status_aborted
import com.kapp.tidings.generated.resources.card_status_blocked
import com.kapp.tidings.generated.resources.card_status_done
import com.kapp.tidings.generated.resources.card_status_failed
import com.kapp.tidings.generated.resources.card_status_merged
import com.kapp.tidings.generated.resources.card_status_pending
import com.kapp.tidings.generated.resources.card_status_prototype
import com.kapp.tidings.generated.resources.card_status_running
import com.kapp.tidings.generated.resources.card_artifact_intent_patch_full
import com.kapp.tidings.generated.resources.card_artifact_intent_prototype
import com.kapp.tidings.generated.resources.card_artifact_intent_run_log
import com.kapp.tidings.generated.resources.card_artifact_intent_test_report
import com.kapp.tidings.generated.resources.card_status_review_ready
import com.kapp.tidings.generated.resources.card_type_task_board
import com.kapp.tidings.generated.resources.card_type_diff_review
import com.kapp.tidings.generated.resources.card_type_prototype
import com.kapp.tidings.generated.resources.card_type_approval
import com.kapp.tidings.generated.resources.card_type_ask_question
import com.kapp.tidings.generated.resources.card_waiting_host
import com.kapp.tidings.generated.resources.agent_state_banner_failed
import com.kapp.tidings.ui.components.AppButton
import com.kapp.tidings.ui.components.AppOutlinedButton
import com.kapp.tidings.ui.components.AppButtonSize
import com.kapp.tidings.ui.components.AppButtonTone
import com.kapp.tidings.ui.theme.LocalAppSpacing
import com.kapp.tidings.ui.theme.RelayElevations
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

/** 单项验收检查（LobeHub Acceptance 契约）。包含单项耗时、观察结论与日志堆栈。 */
data class AgentCardCheck(
    val id: String,
    val label: String,
    val status: AgentCardStepStatus,
    val time: String? = null,
    val observation: String? = null,
    val drawer: String? = null,
    val open: Boolean = false,
)

/** 数字员工身份工牌元信息。 */
data class AgentCardBot(
    val name: String,
    val avatar: String? = null,
    val branch: String? = null,
    val bdot: String? = null,
)

/** 影响面与目标标签。 */
data class AgentCardChip(
    val text: String,
    val tone: String? = null,
)

data class AgentCardDiff(
    val path: String? = null,
    val summary: String? = null,
    val additions: Int? = null,
    val deletions: Int? = null,
    val note: String? = null,
    val hunk: List<String> = emptyList(),
    val open: Boolean = false,
)

/**
 * 产物元数据（对齐 `agent-artifact`）：**只描述不传二进制** ——
 * 文件本体走 file-msg 那条通路，卡片里放的是「有这么个东西」。
 *
 * 自描述契约（升级计划 §7.4）：[intent] 是发出方给的**稳定键**（如 `patch-full`），
 * 人话由渲染端按键查词条 —— 键跨语言稳定，词条才可本地化；[delivered] 是发出方
 * 声明的**直投事实**（完整文件已随卡经 P2P 文件通路投出）。缺省 = 旧卡没带这些键，
 * 照旧渲染，不画意图行、不画「可预览」的承诺。
 */
data class AgentCardArtifact(
    val name: String,
    val bytes: Long? = null,
    val mime: String? = null,
    val intent: String? = null,
    val delivered: Boolean? = null,
)

/**
 * 反向多选卡的一个预设选项（detailed-design §7.9：手机端拇指一点决策，Agent 拿到
 * 确定性参数再开工）。[recommended] 是插件标注的推荐项（原型 `(推荐)` 后缀的语义化）。
 */
data class AgentCardOption(
    val label: String,
    val recommended: Boolean = false,
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
    /** 反向多选预设选项（ask_question，§7.9）；空 = 这张卡没有选项块。 */
    val options: List<AgentCardOption> = emptyList(),
    /**
     * 发出方声明的任务状态（`working` / `review_ready` / `blocked` / `aborted`）。
     *
     * null = 老卡没带这个字段，此时退回「按 actions 与 progress 反推」的老办法。
     *
     * 为什么要有显式字段：反推那套里没有「已中止」的位置 —— 用户按了停止，
     * 卡片还挂着「待合并」。谁结束的、怎么结束的，
     * 该由发出方说了算，不该让渲染层猜。
     */
    val state: String? = null,
    val bot: AgentCardBot? = null,
    val chips: List<AgentCardChip> = emptyList(),
    val context: String? = null,
    val cmd: String? = null,
    val checks: List<AgentCardCheck> = emptyList(),
    val subActions: List<AgentCardAction> = emptyList(),

    /** 宿主最近一次心跳的墙钟毫秒（§六.1）。前端据此渲染「N 秒前心跳」，缺省 = 无心跳信息。 */
    val lastBeatTs: Long? = null,
)

/** 宽容解析：未知字段忽略、未知状态退档；身份键（cardId/rev）缺失整卡作废。 */
fun parseAgentCard(json: JsonObject?): AgentCardUi? {
    if (json == null) return null
    val cardId = json.str("cardId")?.takeIf { it.isNotBlank() } ?: return null
    val rev = json.int("rev") ?: return null
    val rawSteps = json.array("items").mapNotNull { el ->
        val obj = el as? JsonObject ?: return@mapNotNull null
        val label = obj.str("label")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        AgentCardStep(
            id = obj.str("id") ?: label,
            label = label,
            status = when (obj.str("status")) {
                "done", "review_ready" -> AgentCardStepStatus.Done
                "running", "working" -> AgentCardStepStatus.Running
                "failed", "error" -> AgentCardStepStatus.Failed
                "blocked" -> AgentCardStepStatus.Blocked
                else -> AgentCardStepStatus.Pending
            },
            assignee = obj.str("assignee")?.takeIf { it.isNotBlank() },
        )
    }
    val rawChecks = json.array("checks")
    val checks = if (rawChecks.isNotEmpty()) {
        rawChecks.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val label = obj.str("label")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            AgentCardCheck(
                id = obj.str("id") ?: label,
                label = label,
                status = when (obj.str("status")) {
                    "done", "review_ready" -> AgentCardStepStatus.Done
                    "running", "working" -> AgentCardStepStatus.Running
                    "failed", "error" -> AgentCardStepStatus.Failed
                    "blocked" -> AgentCardStepStatus.Blocked
                    else -> AgentCardStepStatus.Pending
                },
                time = obj.str("time")?.takeIf { it.isNotBlank() },
                observation = obj.str("observation")?.takeIf { it.isNotBlank() },
                drawer = obj.str("drawer")?.takeIf { it.isNotBlank() },
                open = obj.boolean("open") == true,
            )
        }
    } else {
        rawSteps.map { s ->
            AgentCardCheck(
                id = s.id,
                label = s.label,
                status = s.status,
                observation = s.assignee,
            )
        }
    }
    return AgentCardUi(
        cardId = cardId,
        rev = rev,
        type = json.str("type")?.takeIf { it.isNotBlank() },
        title = json.str("title")?.takeIf { it.isNotBlank() },
        state = json.str("state")?.takeIf { it.isNotBlank() },
        progress = json.int("progress")?.coerceIn(0, 100),
        steps = rawSteps,
        checks = checks,
        bot = (json["bot"] as? JsonObject)?.let { b ->
            b.str("name")?.takeIf { it.isNotBlank() }?.let { name ->
                AgentCardBot(
                    name = name,
                    avatar = b.str("avatar")?.takeIf { it.isNotBlank() },
                    branch = b.str("branch")?.takeIf { it.isNotBlank() },
                    bdot = b.str("bdot")?.takeIf { it.isNotBlank() },
                )
            }
        },
        chips = json.array("chips").mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val text = obj.str("text")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            AgentCardChip(
                text = text,
                tone = obj.str("tone")?.takeIf { it.isNotBlank() },
            )
        },
        context = json.str("context")?.takeIf { it.isNotBlank() },
        cmd = json.str("cmd")?.takeIf { it.isNotBlank() },
        diff = (json["diff"] as? JsonObject)?.let { d ->
            AgentCardDiff(
                path = d.str("path")?.takeIf { it.isNotBlank() },
                summary = d.str("summary")?.takeIf { it.isNotBlank() },
                additions = d.int("additions"),
                deletions = d.int("deletions"),
                note = d.str("note")?.takeIf { it.isNotBlank() },
                hunk = (d["hunk"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList(),
                open = d.boolean("open") == true,
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
        subActions = json.array("subActions").mapNotNull { el ->
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
                intent = obj.str("intent")?.takeIf { it.isNotBlank() },
                delivered = obj.boolean("delivered"),
            )
        },
        expiresAt = json.long("expiresAt"),
        lastBeatTs = json.long("lastBeatTs"),
        options = json.array("options").mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val label = obj.str("label")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            AgentCardOption(label = label, recommended = obj.boolean("recommended") == true)
        },
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
        val card = message.renderCard ?: continue
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
    val card = parseAgentCard(message.renderCard) ?: return CardRowOutcome.FallbackToText(message.text)
    return if (message.id in visibleCardMessageIds) CardRowOutcome.Render(card) else CardRowOutcome.Hidden
}

// ── 策略层纯函数（设计稿 v2 阶段 A）───────────────────────────────────────
//
// 这一段的三个函数**只吃入参**：不读时钟、不读全局状态、不碰 Compose。
// 这样它们能被合成输入钉死（`AgentCardPolicyTest`），也不会被 `envDependentTestGate`
// 判红 —— 一条「过期了没有」的判据如果自己读 `System.currentTimeMillis()`，
// 它在慢机器与快机器上就是两条不同的测试。

/** 宿主端自己发出的系统动作 id（卡片 JSON 里 label 只是占位，文案端上本地化）。 */
const val SYSTEM_ACTION_MERGE_PATCH = "merge-patch"

/**
 * 「停止这件活」。宿主侧接在 `onHostCardAction`，直接摘进程树。
 *
 * 存在的理由：在此之前，中止任务的唯一办法是在群里打 `stop`/`abort`/`cancel`/`停`/`取消`
 * 之一（`AbortSignals`）。打错一个字（`stp`）就完全无效，而且界面一句话都不说 ——
 * 用户以为停了，任务还在跑。一个关键动作不该靠背诵。
 */
const val SYSTEM_ACTION_ABORT_TASK = "abort-task"

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
private fun artifactMetaLine(artifact: AgentCardArtifact): String? =
    listOfNotNull(artifact.mime, artifact.bytes?.let { formatArtifactSize(it) }).joinToString(SEP_META).ifBlank { null }

/** 元信息分隔符：一处定义。 */
private const val SEP_META = " · "

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

// ── Acceptance 渲染策略（升级计划 Phase 2 的纯函数层）───────────────────
//
// 与上面的策略层同一条纪律：只吃入参、不读时钟、不碰 Compose —— 判据抽成数据，
// 帧只是它的投影，单测用合成输入钉死。

/** Hunk 视窗默认只露的行数；超出的部分进「展开」，收起态不截断成 0 行。 */
const val CARD_HUNK_COLLAPSED_LINES = 6

/** 打回动作的 actionId 前缀：反馈文本以它编码（见 [rejectRevisionActionIdOf]）。 */
const val SYSTEM_ACTION_REJECT_REVISION = "reject-revision"

/** 会话免审开关的 subAction id（分级免审；L1 会话免审的中期形态 §4.2）。 */
const val SYSTEM_ACTION_SESSION_EXEMPT = "session-exempt"

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
 * 会话免审动作与所属卡的**单槽编码**：`session-exempt|<cardId>`（reject-revision| 同款前缀式）。
 *
 * 为什么把 cardId 编进动作串：免审是**会话粒度**的决定，但动作通路（[CardActionUi] 之下
 * 只有 actionId 一个字符串槽）与域层 `handleCardAction` 的签名只认 actionId —— 宿主必须
 * 能凭动作帧找回「免审的是哪张卡」，才能定位会话、把原卡翻成「已免审」。编码侧是纯函数、
 * 解码侧在宿主（桌面端同名镜像），两侧格式由各自的测试钉住 —— 改一侧必须同步另一侧。
 */
fun sessionExemptActionIdOf(cardId: String): String {
    val cleaned = cardId.trim()
    if (cleaned.isEmpty()) return SYSTEM_ACTION_SESSION_EXEMPT
    return "$SYSTEM_ACTION_SESSION_EXEMPT|" + cleaned
}

/** 见 [sessionExemptActionIdOf]：非免审前缀或空 cardId 返回 null（维持既有路由）。 */
fun sessionExemptCardIdOf(actionId: String): String? =
    actionId.takeIf { it.startsWith("$SYSTEM_ACTION_SESSION_EXEMPT|") }
        ?.removePrefix("$SYSTEM_ACTION_SESSION_EXEMPT|")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

/** 该动作串是不是会话免审动作（前缀判定，不做解码）。 */
fun isSessionExemptAction(actionId: String): Boolean =
    actionId.startsWith("$SYSTEM_ACTION_SESSION_EXEMPT|")

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
    /** 产物条目 → 消息流里同卡关联的已落盘文件路径；null = 本壳解析不出（预览不画）。 */
    artifactSavedPathFor: ((AgentCardArtifact) -> String?)? = null,
    /** 点开产物预览。null = 本壳没有预览通路，入口整块不画。 */
    onPreviewArtifact: ((AgentCardArtifact, String) -> Unit)? = null,
) {
    val sp = LocalAppSpacing.current
    val plan = cardRenderPlanOf(
        card = card,
        eligibility = eligibility,
        hasActionChannel = onAction != null,
        now = now,
    )
    Surface(
        // 升级稿 .acard（prototype/relay.html:515-518）：surface 底 + 8% 黑细边 + 16 圆角
        // + 双层柔和投影 + 壳自带 16×18 内边距与 12 的块间 gap（flex column）。
        // 旧稿（fdc4e1d6）的「块自带 padding + 发丝线」结构随升级稿整段作废。
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)),
        shadowElevation = RelayElevations.Card,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 数字员工工牌（升级计划 Phase 2 要点 1）：avatar + 角色名 + worktree 分支
            // 芯片 + 呼吸微动效点。bot 缺席 = 老插件没给，整块不画（不是每个卡都有承办人）。
            card.bot?.let { CardHeaderRow(it) }
            // 头行：上栏元信息（type 徽标 + 状态胶囊 + rev 游标）+ 独立主标题。
            if (card.type != null || card.title != null || card.rev > 0 || plan.showExpiredTag) {
                Column(verticalArrangement = Arrangement.spacedBy(sp.xs)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(sp.sm),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        card.type?.let { CardTypeTag(it) }
                        Spacer(Modifier.weight(1f))
                        if (plan.showExpiredTag) {
                            CardNeutralTag(text = stringResource(Res.string.card_expired))
                        }
                        CardStateTag(card)
                        // 这里曾经显示「第 N 版」。删掉的理由不是省地方，是它没有意义：
                        // rev 每广播一次就 +1（受理、结论、处理人落定各一次），真机上同一
                        // 张刚跑完的卡写着「第 4 版」—— 用户会以为前面还有三次任务。
                        // 卡的「变了没有」由状态胶囊和横幅表达；版本号是协议内部的事，
                        // 不该占用户的注意力。
                    }
                    if (card.title != null) {
                        Text(
                            text = card.title,
                            // 设计契约（componentSpecs.AgentCards.requiredTokens）要的就是
                            // titleSmall。先前这一行被改成 titleMedium，`designSpecDrift`
                            // 当天就红了 —— 规格里的人写明是这一档，改大要连规格一起改。
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = CARD_TITLE_MAX_LINES,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            card.banner?.let { CardBannerRow(it) }
            card.progress?.let { CardProgressRow(it) }
            // Acceptance 检查清单（升级计划 Phase 2 要点 2）：新式卡走 checks（带单项耗时 /
            // 观察结论 / 日志抽屉），老卡继续走 StepList。两者都画会把同一批步骤说两遍 ——
            // 解析侧 checks 本就由 items 回退而来，有 checks 时 steps 是它的影子。
            if (card.checks.isNotEmpty() && card.steps.isEmpty()) {
                CardChecksColumn(card.checks)
            } else if (card.steps.isNotEmpty()) {
                CardStepListColumn(card.steps)
            }
            card.diff?.let { CardDiffViewerRow(it) }
            card.diff?.takeIf { it.hunk.isNotEmpty() }?.let { CardDiffHunkViewer(it) }
            if (card.artifacts.isNotEmpty()) {
                CardArtifactRows(
                    artifacts = card.artifacts,
                    savedPathFor = { artifact -> artifactSavedPathFor?.invoke(artifact) },
                    onPreviewArtifact = onPreviewArtifact,
                )
            }
            if (card.options.isNotEmpty()) {
                CardOptionListRows(card, pendingActions, plan.actionsEnabled, onAction)
            }
            // 宿主判定的知情行（.decided{padding:6px 14px 10px}，11px on-2）：
            // 它不是按钮，是「这件事已被谁定了」的事实 —— 就算这个壳没有动作通路
            // （onAction == null），它也照画。
            card.decidedBy?.let { decided ->
                // 升级稿 .decided（relay.html:580-581）：surface-2 底 + r8 + 12 on-2，块化知情行。
                Text(
                    text = stringResource(Res.string.card_decided_by, decided),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceTiers.Raised, MaterialTheme.shapes.small)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            // 只读态必须说清「为什么没有按钮」（.wait 同 .decided：580 段的另一形态，
            // text-align:center —— relay.html:584-585）。
            if (plan.showWaitingForHost) {
                Text(
                    text = stringResource(Res.string.card_waiting_host),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceTiers.Raised, MaterialTheme.shapes.small)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            if (plan.showActions) {
                // 打回动作的就地展开（升级计划 Phase 2 要点 4）：reject 按钮被点时
                // **不直接派发**，先开反馈抽屉 —— 打回是要带话的，空手打回等于只说了
                // 「不行」。抽屉里提交才真正派发（带 feedbackText 编码的 actionId）。
                var revisionDrawerOpen by remember(card.cardId, card.rev) { mutableStateOf(false) }
                val hasReject = card.actions.any { it.actionId == SYSTEM_ACTION_REJECT_REVISION }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CardButtonGroupRow(
                        card = card,
                        pendingActions = pendingActions,
                        actionsEnabled = plan.actionsEnabled,
                        onAction = { c, actionId ->
                            if (actionId == SYSTEM_ACTION_REJECT_REVISION) {
                                revisionDrawerOpen = !revisionDrawerOpen
                            } else {
                                onAction!!(c, actionId)
                            }
                        },
                    )
                    if (hasReject && revisionDrawerOpen) {
                        CardRevisionDrawer(card, pendingActions, plan.actionsEnabled, onAction!!)
                    }
                }
            }
            // 会话免审行：宿主在 subActions 里给了这条语义才画（数据到不了的字段不画）。
            // 点击把 SYSTEM_ACTION_SESSION_EXEMPT|<cardId> 发进同一个动作通路；
            // 过期/无资格/无通路都不给点（与按钮组同一套纪律）。
            CardSessionExemptRow(card, pendingActions, plan.actionsEnabled, onAction)
        }
    }
}

/**
 * 数字员工工牌头行（升级计划 Phase 2 要点 1，原型 `.acard .hd`）。
 *
 * avatar 用**字母座**而不是位图：`avatar` 字段在载荷里是 emoji/短代号（原型里是
 * 🤖/⚡），按文本画进圆角座即可，图片解码与远端拉取都不属于卡片渲染的职责。
 * 呼吸微动效点（`.bdot`）只在 `run` 档动：done/fail/warn 是终态事实，动效属于
 * 「正在发生」，不属于「已经发生」。
 */
@Composable
internal fun CardHeaderRow(bot: AgentCardBot, modifier: Modifier = Modifier) {
    val sp = LocalAppSpacing.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(sp.sm),
        modifier = modifier.fillMaxWidth(),
    ) {
        // .hd .bot-badge 的字母座：agent 紫微底 + 角色名首字（图形级 3:1 用 agentAccent）。
        // 圆角 6px 属档外值（原型 .bot-badge 6px）：按 ShapeRoles 表头裁决就近归并 extraSmall。
        Box(
            modifier = Modifier
                .size(com.kapp.tidings.ui.theme.IconSizes.Title)
                .background(MaterialTheme.accents.agentAccentContainer, MaterialTheme.shapes.extraSmall),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = bot.avatar?.takeIf { it.isNotBlank() }
                    ?: bot.name.take(1).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.accents.agentAccentText,
            )
        }
        Text(
            text = bot.name,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        CardBreathDot(bdot = bot.bdot)
        bot.branch?.let { branch ->
            // .hd .wk-chip：等宽 11px surface-2 底 —— worktree 分支是机器事实，不做语义着色。
            Text(
                text = branch,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .background(SurfaceTiers.Raised, MaterialTheme.shapes.extraSmall)
                    .padding(horizontal = sp.sm6, vertical = 2.dp)
                    .semantics { contentDescription = branch },
            )
        }
    }
}

/**
 * 呼吸微动效点（原型 `.bdot`：7px 圆点，run 档 1.8s 呼吸）。
 *
 * alpha 在 0.4f~1.0f 循环（升级计划 Phase 2 要点 1 给定区间）；档位色逐字对齐原型：
 * run=primary、done=online、fail=error、warn=onSecondaryContainer（`--on-queued-c`）。
 * 未知档位按中性画 —— 多一个未知值不该让工牌消失（宽容解析的同一条口径）。
 */
@Composable
private fun CardBreathDot(bdot: String?, modifier: Modifier = Modifier) {
    val sp = LocalAppSpacing.current
    val baseColor = when (bdot) {
        "run" -> MaterialTheme.colorScheme.primary
        "done" -> MaterialTheme.accents.online
        "fail" -> MaterialTheme.colorScheme.error
        "warn" -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val alpha: Float = if (bdot == "run") {
        val infinite = rememberInfiniteTransition(label = "card-bdot")
        val breath by infinite.animateFloat(
            initialValue = 0.4f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "card-bdot-alpha",
        )
        breath
    } else {
        1f
    }
    // 点径 7px 是档外值：落 6dp 间距档（sm6），与单选圆点 .size(sp.sm) 同一条
    // 「小元素吃间距档」的既有先例。
    Box(
        modifier = modifier
            .size(sp.sm6)
            .background(baseColor.copy(alpha = alpha), CircleShape),
    )
}

/**
 * Acceptance 检查清单（升级计划 Phase 2 要点 2，原型 `.acard .checks`）：
 * 状态指示 + 等宽单项耗时 + 一行观察结论；带日志（drawer）的行可点开日志抽屉。
 *
 * 展开态是**行内本地状态**：卡片体是插件定义的只读投影，抽屉不改变任何协议事实，
 * 所以不进模型、不进 pendingActions —— 会话切换即重建，与本组件「不存状态」的
 * 既有纪律一致。
 */
@Composable
private fun CardChecksColumn(checks: List<AgentCardCheck>) {
    val sp = LocalAppSpacing.current
    Column(
        verticalArrangement = Arrangement.spacedBy(sp.sm6),
        modifier = Modifier.padding(vertical = 2.dp),
    ) {
        checks.forEach { check ->
            CardCheckRow(check)
        }
    }
}

@Composable
private fun CardCheckRow(check: AgentCardCheck) {
    val sp = LocalAppSpacing.current
    var drawerOpen by remember(check.id) { mutableStateOf(check.open) }
    val statusLabel = when (check.status) {
        AgentCardStepStatus.Done -> stringResource(Res.string.card_status_done)
        AgentCardStepStatus.Running -> stringResource(Res.string.card_status_running)
        AgentCardStepStatus.Pending -> stringResource(Res.string.card_status_pending)
        AgentCardStepStatus.Failed -> stringResource(Res.string.card_status_failed)
        AgentCardStepStatus.Blocked -> stringResource(Res.string.card_status_blocked)
    }
    val clickable = check.drawer != null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 原型 .chk 10px：档外值就近归并 shapes.small（CardNeutralTag 同一裁决）。
            .background(SurfaceTiers.Raised, MaterialTheme.shapes.small)
            .let { m ->
                if (clickable) {
                    m.clickable { drawerOpen = !drawerOpen }
                } else {
                    m
                }
            }
            .padding(horizontal = sp.md10, vertical = sp.sm6)
            .semantics { contentDescription = statusLabel },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(sp.sm),
        ) {
            StepStatusIcon(check.status)
            Text(
                text = check.label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (check.status == AgentCardStepStatus.Running) FontWeight.SemiBold else FontWeight.Normal,
                color = if (check.status == AgentCardStepStatus.Running) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = CARD_STEP_LABEL_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            check.time?.let { time ->
                // .chk-meta：等宽耗时标签 —— 机器事实用等宽字，与 hunk/分支芯片同一条口径。
                Text(
                    text = stringResource(Res.string.card_check_time_format, time),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (clickable) {
                Icon(
                    painter = painterResource(if (drawerOpen) Res.drawable.ic_chevd else Res.drawable.ic_chev),
                    contentDescription = stringResource(
                        if (drawerOpen) Res.string.card_check_drawer_close else Res.string.card_check_drawer_open,
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(com.kapp.tidings.ui.theme.IconSizes.Inline),
                )
            }
        }
        check.observation?.let { observation ->
            // 双层文本契约的第一层：一行人类可读的观察结论（第二层是下面的日志抽屉）。
            Text(
                text = observation,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (clickable && drawerOpen) {
            // .chk-drawer：等宽日志堆栈，pre-wrap 语义 —— 行数不设限的是插件给的现场，
            // 超长在载荷侧就该被截断（帧预算归协议管，渲染层不再猜一遍）。
            Text(
                text = check.drawer.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = sp.sm6),
            )
        }
    }
}

/**
 * Diff Hunk 代码视窗（升级计划 Phase 2 要点 3，原型 `.df-hunk`）：等宽行号右对齐、
 * 新增绿微底 12%、删除红微底 12%；默认只露 [CARD_HUNK_COLLAPSED_LINES] 行，
 * 超出给「展开/收起」。
 *
 * 行号与极性全部来自 [agentCardHunkLinesOf] 的推演 —— 渲染层不再各写一遍规则。
 */
@Composable
private fun CardDiffHunkViewer(diff: AgentCardDiff) {
    var expanded by remember(diff.hunk) { mutableStateOf(diff.open) }
    val lines = remember(diff.hunk) { agentCardHunkLinesOf(diff.hunk) }
    val expandable = lines.size > CARD_HUNK_COLLAPSED_LINES
    val visible = if (expandable && !expanded) lines.take(CARD_HUNK_COLLAPSED_LINES) else lines
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 原型 .df-hunk 8px 圆角：shapes.small 逐字同值。
            .background(SurfaceTiers.Inset, MaterialTheme.shapes.small)
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        visible.forEach { line ->
            CardHunkLineRow(line)
        }
        if (expandable) {
            // .dftoggle：文字开关（primary 11px 600），不用实心按钮 —— 代码视窗的
            // 主角是代码，展开入口退后一档。
            Text(
                text = stringResource(
                    if (expanded) Res.string.card_diff_collapse_code else Res.string.card_diff_expand_code,
                ),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun CardHunkLineRow(line: AgentCardHunkLine) {
    val sp = LocalAppSpacing.current
    val (bg, fg) = when (line.kind) {
        AgentCardHunkKind.Add ->
            MaterialTheme.accents.online.copy(alpha = 0.12f) to MaterialTheme.accents.online
        AgentCardHunkKind.Del ->
            MaterialTheme.colorScheme.error.copy(alpha = 0.12f) to MaterialTheme.colorScheme.error
        AgentCardHunkKind.HunkHead ->
            Color.Transparent to MaterialTheme.colorScheme.onSurfaceVariant
        AgentCardHunkKind.Context ->
            Color.Transparent to MaterialTheme.colorScheme.onSurface
    }
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = 8.dp),
    ) {
        Text(
            text = line.number?.toString() ?: "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
            // gutter 24px（原型 .ln）：两枚在档间距拼出（md10 + lg14），不引新档。
            modifier = Modifier.width(sp.md10 + sp.lg14),
        )
        Text(
            text = line.text,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        )
    }
}

/**
 * 就地打回反馈抽屉（升级计划 Phase 2 要点 4，原型 `.rev-drawer`）：点「打回」就地
 * 展开，4 枚高频芯片 + 输入框 + 提交。
 *
 * 芯片是**快捷短语**不是互斥选项：点一下把短语追加进输入框（可继续编辑、可多选），
 * 与原型 `.qchip` 的行为一致。提交走 [rejectRevisionActionIdOf] 把反馈编码进
 * actionId（动作通路只有一个字符串槽，见那个函数的 KDoc），乐观置灰与防重
 * 沿用 [cardActionUiOf] 的三态 —— 打回也是一次决策，不该享受豁免。
 *
 * [expanded] 由调用方持有：抽屉的开合跟随按钮的锁态，本组件不自持。
 */
@Composable
private fun CardRevisionDrawer(
    card: AgentCardUi,
    pendingActions: Set<String>,
    actionsEnabled: Boolean,
    onAction: (card: AgentCardUi, actionId: String) -> Unit,
) {
    val sp = LocalAppSpacing.current
    var feedback by remember(card.cardId) { mutableStateOf("") }
    val ui = cardActionUiOf(card, pendingActions, SYSTEM_ACTION_REJECT_REVISION)
    val enabled = actionsEnabled && !ui.disabled
    val submitted = ui.lock != CardActionLock.Editable
    Column(
        verticalArrangement = Arrangement.spacedBy(sp.sm),
        modifier = Modifier
            .fillMaxWidth()
            // 原型 .rev-drawer 10px 圆角 + 红 18% 细边：圆角就近归并 shapes.small。
            .background(SurfaceTiers.Raised, MaterialTheme.shapes.small)
            .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.18f), MaterialTheme.shapes.small)
            .padding(horizontal = sp.md10, vertical = sp.md10),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(sp.sm6),
            modifier = Modifier.fillMaxWidth(),
        ) {
            RevisionChip(Res.string.card_revision_chip_test, enabled) { feedback = feedback.appendChip(it) }
            RevisionChip(Res.string.card_revision_chip_contrast, enabled) { feedback = feedback.appendChip(it) }
            RevisionChip(Res.string.card_revision_chip_compat, enabled) { feedback = feedback.appendChip(it) }
            RevisionChip(Res.string.card_revision_chip_perf, enabled) { feedback = feedback.appendChip(it) }
        }
        // 输入框：无边框药丸（.rev-inp 的 surface 底 + 细边），与 MessageComposer 的
        // 「输入框自带容器」分工一致 —— 这里画的是抽屉自己的行内输入。
        androidx.compose.foundation.text.BasicTextField(
            value = feedback,
            onValueChange = { feedback = it },
            enabled = enabled,
            textStyle = MaterialTheme.typography.bodySmall.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            decorationBox = { inner ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small)
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f), MaterialTheme.shapes.small)
                        .padding(horizontal = sp.md10, vertical = sp.sm6),
                ) {
                    if (feedback.isEmpty()) {
                        Text(
                            text = stringResource(Res.string.card_revision_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        AppButton(
            text = stringResource(
                if (submitted) Res.string.card_pending_confirm else Res.string.card_revision_submit,
            ),
            onClick = {
                onAction(card, rejectRevisionActionIdOf(feedback))
            },
            enabled = enabled,
            size = AppButtonSize.Small,
            tone = AppButtonTone.Danger,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 芯片追加的接缝：第一条前不留；接缝用 ASCII 而不是任何一语的标点（双语共用一份）。 */
private fun String.appendChip(chip: String): String =
    if (isBlank()) chip else "$this + $chip"

@Composable
private fun RevisionChip(labelRes: StringResource, enabled: Boolean, onClick: (label: String) -> Unit) {
    val sp = LocalAppSpacing.current
    val label = stringResource(labelRes)
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.extraSmall)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.08f), MaterialTheme.shapes.extraSmall)
            .clickable(enabled = enabled) { onClick(label) }
            .padding(horizontal = sp.sm, vertical = 2.dp),
    )
}

/**
 * 会话免审的一行开关（升级计划 §4.2 分级免审的 UI 侧；L1 会话免审）。
 *
 * 它是 subAction 语义的落点：宿主没给这条 subAction 的卡不画（数据到不了的字段
 * 不画，与 ButtonGroup 的 sub-row 同一条纪律）。未生效时点击把免审动作发进同一个
 * 动作通路（单槽编码见 [sessionExemptActionIdOf]）；已生效后换文案并摘掉点击 ——
 * 「已免审」是事实不是动作，再画可点开关就是骗点击。
 */
@Composable
private fun CardSessionExemptRow(
    card: AgentCardUi,
    pendingActions: Set<String>,
    enabled: Boolean,
    onAction: ((card: AgentCardUi, actionId: String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    // 判定走纯函数（[isSessionExemptActive]）：宿主回放 style=active 与本机动作在途都算
    // 已生效 —— 开启态是事实不是动作，可点开关就此消失，只留一行状态。
    val exemptActive = isSessionExemptActive(card, pendingActions)
    val exemptOffered = card.subActions.any { it.actionId == SYSTEM_ACTION_SESSION_EXEMPT }
    if (!exemptOffered) return
    // 动作通路与资格缺一不可：没有通路（onAction == null）或当前无资格（ReadOnly，
    // renderPlan 已把按钮组整块收掉）时只画状态行 —— 按下去没反应的开关比没有更糟。
    val dispatchable = onAction != null && enabled && !exemptActive
    Text(
        text = stringResource(
            if (exemptActive) Res.string.card_session_exempt_active else Res.string.card_session_exempt,
        ),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier
            .background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.small)
            .clickable(enabled = dispatchable) { onAction!!(card, sessionExemptActionIdOf(card.cardId)) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/**
 * 头行的 type 徽标（原型 `.hd` 里的 `.tag.agent`：agent-c/agent-t 对、11px 600、
 * 胶囊圆角、带图标与本地化文案）。
 */
@Composable
private fun CardTypeTag(type: String) {
    val sp = LocalAppSpacing.current
    val (label, iconRes) = when (type) {
        "task-board" -> stringResource(Res.string.card_type_task_board) to Res.drawable.ic_terminal
        "diff-review" -> stringResource(Res.string.card_type_diff_review) to Res.drawable.ic_terminal
        "prototype" -> stringResource(Res.string.card_type_prototype) to Res.drawable.ic_bot
        "approval" -> stringResource(Res.string.card_type_approval) to Res.drawable.ic_shield
        "ask_question" -> stringResource(Res.string.card_type_ask_question) to Res.drawable.ic_list
        else -> type to Res.drawable.ic_bot
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .background(MaterialTheme.accents.agentAccent.copy(alpha = 0.09f), RoundedCornerShape(6.dp))
            .padding(horizontal = sp.sm, vertical = 2.dp),
    ) {
        androidx.compose.material3.Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.accents.agentAccentText,
            // 12dp 与间距档 md 同值：改吃 token（视觉零变化）。
            modifier = Modifier.size(sp.md),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.accents.agentAccentText,
        )
    }
}

/** 任务状态胶囊（待合并/进行中/已完成/受阻/已取消）。 */
@Composable
private fun CardStateTag(card: AgentCardUi) {
    val sp = LocalAppSpacing.current
    // 发出方声明的 state 优先。反推那套（下面）留着给不带 state 的老卡：
    // 它认不出「已中止」，会把一张停掉的卡继续标成「待合并」。
    val declared = when (card.state) {
        "aborted" -> Triple(
            stringResource(Res.string.card_status_aborted),
            MaterialTheme.accents.warn.copy(alpha = 0.12f),
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        "blocked" -> Triple(
            stringResource(Res.string.card_status_blocked),
            MaterialTheme.accents.warn.copy(alpha = 0.12f),
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        // 阶段 A 的待选卡：它既不是「受阻」也不是「待合并」—— 它在等人选方案。
        // 从前没有这一档，于是落到下面的「有 approve-prototype 动作 ⇒ 待合并」兜底，
        // 真机上「原型待选」被标成「待合并」，用户以为可以合并一个还不存在的改动。
        "prototype" -> Triple(
            stringResource(Res.string.card_status_prototype),
            MaterialTheme.accents.agentAccent.copy(alpha = 0.12f),
            MaterialTheme.accents.agentAccentText,
        )
        // 合流成功的终态（测试方案 §4.3 的判据）：以前这个 state 不在映射里，卡上
        // 干脆没有胶囊 —— 「合并成功了没有」只能靠横幅那句话去猜。
        "merged" -> Triple(
            stringResource(Res.string.card_status_merged),
            MaterialTheme.accents.online.copy(alpha = 0.12f),
            MaterialTheme.accents.online,
        )
        "review_ready" -> Triple(
            stringResource(Res.string.card_status_review_ready),
            MaterialTheme.accents.online.copy(alpha = 0.12f),
            MaterialTheme.accents.online,
        )
        "working" -> Triple(
            stringResource(Res.string.card_status_running),
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            MaterialTheme.colorScheme.primary,
        )
        else -> null
    }
    val (label, bg, fg) = declared ?: when {
        card.actions.any { it.actionId == SYSTEM_ACTION_MERGE_PATCH } ->
            Triple(
                stringResource(Res.string.card_status_review_ready),
                MaterialTheme.accents.online.copy(alpha = 0.12f),
                MaterialTheme.accents.online,
            )
        card.actions.any { it.actionId == "approve-prototype" } ->
            Triple(
                // 老卡（发出方还没声明 state）的兜底：有「Approve & Develop」就是在等选方案，
                // 不是「待合并」—— 待合并说的是「有补丁可以合」，这里连补丁都还没有。
                stringResource(Res.string.card_status_prototype),
                MaterialTheme.accents.agentAccent.copy(alpha = 0.12f),
                MaterialTheme.accents.agentAccentText,
            )
        card.actions.any { it.actionId == "retry" } ->
            Triple(
                stringResource(Res.string.card_status_blocked),
                MaterialTheme.accents.warn.copy(alpha = 0.12f),
                MaterialTheme.colorScheme.onSecondaryContainer,
            )
        card.progress == 100 ->
            Triple(
                stringResource(Res.string.card_status_done),
                MaterialTheme.accents.online.copy(alpha = 0.12f),
                MaterialTheme.accents.online,
            )
        card.progress != null ->
            Triple(
                stringResource(Res.string.card_status_running),
                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                MaterialTheme.colorScheme.primary,
            )
        else -> null
    } ?: return
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = fg,
        modifier = Modifier
            .background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = sp.sm, vertical = 2.dp),
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
    val (_, contentColor) = bannerColorsOf(banner.tone)
    val iconRes = when (banner.tone) {
        AgentCardBannerTone.Info -> Res.drawable.ic_info
        AgentCardBannerTone.Success -> Res.drawable.ic_check_double
        AgentCardBannerTone.Warn -> Res.drawable.ic_warn
        AgentCardBannerTone.Error -> Res.drawable.ic_close
    }
    BannerSurface(tone = banner.tone) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(com.kapp.tidings.ui.theme.IconSizes.Label),
            )
            Text(
                text = banner.text,
                style = MaterialTheme.typography.bodySmall,
                color = contentColor,
                maxLines = CARD_BANNER_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
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
    // 升级稿 .bn（relay.html:527-528）：圆角 10 + 柔和微底；形状 10 是档外值，
    // 按 ShapeRoles 表头裁决走并排就近归并 = shapes.small(8) 最近的在档值。
    Surface(shape = MaterialTheme.shapes.small, color = container, contentColor = contentColor, modifier = modifier) {
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
        // 升级稿 .bn{padding:10px 14px}（relay.html:527）。
        modifier = Modifier.fillMaxWidth().padding(horizontal = sp.lg14, vertical = sp.md10),
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
        // 升级稿 .bn 四态（relay.html:529-532）：微晕底 + 语义色直印（不再是 on-container 对）。
        // 公式色归一：紫 107,78,230 = agentAccent、绿 16,185,129 = --online 族（RelaySuccess）、
        // 琥珀 245,158,11 = accents.warn（本次新增 token）、红 239,68,68 = error（#E63946 近亲）。
        AgentCardBannerTone.Info ->
            MaterialTheme.accents.agentAccent.copy(alpha = 0.06f) to MaterialTheme.accents.agentAccentText
        AgentCardBannerTone.Success ->
            MaterialTheme.accents.online.copy(alpha = 0.07f) to MaterialTheme.colorScheme.onPrimaryContainer
        AgentCardBannerTone.Warn ->
            MaterialTheme.accents.warn.copy(alpha = 0.08f) to MaterialTheme.colorScheme.onSecondaryContainer
        AgentCardBannerTone.Error ->
            MaterialTheme.colorScheme.error.copy(alpha = 0.08f) to MaterialTheme.colorScheme.error
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
        horizontalArrangement = Arrangement.spacedBy(sp.md10),
        // 升级稿 .pg{padding:2px 0}（relay.html:535）—— 块间 12 gap 由壳承担。
        modifier = Modifier
            .padding(vertical = 2.dp)
            .semantics { contentDescription = label },
    ) {
        // .pg .bar（relay.html:536-537）：6px 轨、圆角 10、底 = --surface-3（Inset 档）。
        // 轨高 6dp 与间距档 sm6 同值：改吃 token（视觉零变化）。
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = SurfaceTiers.Inset,
            modifier = Modifier.weight(1f).height(sp.sm6),
        ) {
            Box(Modifier.fillMaxWidth()) {
                // .pg .bar i：填充 = primary → #8B5CF6 的 90° 渐变（升级稿给定的唯一
                // 渐变；终点紫 = accents.accentGradientEnd（#8B5CF6，升级稿 relay.html:537 原型给定的渐变终点）。
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress / 100f)
                        .height(sp.sm6)
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.accents.accentGradientEnd,
                                ),
                            ),
                        ),
                )
            }
        }
        // .pg .pc（relay.html:538-539）：12/600 mono tabular（labelSmall 11 → bodySmall 档带 600）。
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
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
    // 升级稿 .sts{gap:6px;padding:4px 0}（relay.html:541）：微型任务清单，
    // 无生硬分割线（升级稿注释原话）。
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        steps.forEach { step -> CardStepRow(step) }
    }
}

/**
 * 步骤状态指示图标（16×16）：使用 Canvas 精准绘制，消除字体字形基准线偏心与视觉重量不均。
 */
@Composable
private fun StepStatusIcon(status: AgentCardStepStatus) {
    val onlineColor = MaterialTheme.accents.online
    val primaryColor = MaterialTheme.colorScheme.primary
    val errorColor = MaterialTheme.colorScheme.error
    val warnColor = MaterialTheme.accents.warn
    val insetColor = SurfaceTiers.Inset
    val surfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant

    Canvas(modifier = Modifier.size(com.kapp.tidings.ui.theme.IconSizes.Label)) {
        val radius = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)

        when (status) {
            AgentCardStepStatus.Done -> {
                drawCircle(
                    color = onlineColor.copy(alpha = 0.15f),
                    radius = radius,
                    center = center,
                )
                val checkPath = Path().apply {
                    moveTo(size.width * 0.28f, size.height * 0.52f)
                    lineTo(size.width * 0.44f, size.height * 0.68f)
                    lineTo(size.width * 0.72f, size.height * 0.34f)
                }
                drawPath(
                    path = checkPath,
                    color = onlineColor,
                    style = Stroke(
                        width = 1.8.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
            }
            AgentCardStepStatus.Running -> {
                drawCircle(
                    color = primaryColor.copy(alpha = 0.14f),
                    radius = radius,
                    center = center,
                )
                drawCircle(
                    color = primaryColor.copy(alpha = 0.35f),
                    radius = radius - 0.5.dp.toPx(),
                    center = center,
                    style = Stroke(width = 1.2.dp.toPx()),
                )
                val trianglePath = Path().apply {
                    val shiftX = 0.5.dp.toPx()
                    val halfH = 3.2.dp.toPx()
                    val halfW = 2.8.dp.toPx()
                    moveTo(center.x - halfW * 0.7f + shiftX, center.y - halfH)
                    lineTo(center.x + halfW * 1.3f + shiftX, center.y)
                    lineTo(center.x - halfW * 0.7f + shiftX, center.y + halfH)
                    close()
                }
                drawPath(
                    path = trianglePath,
                    color = primaryColor,
                    style = Fill,
                )
            }
            AgentCardStepStatus.Pending -> {
                drawCircle(
                    color = insetColor,
                    radius = radius,
                    center = center,
                )
                drawCircle(
                    color = surfaceVariantColor.copy(alpha = 0.4f),
                    radius = 2.2.dp.toPx(),
                    center = center,
                )
            }
            AgentCardStepStatus.Failed -> {
                drawCircle(
                    color = errorColor.copy(alpha = 0.15f),
                    radius = radius,
                    center = center,
                )
                val strokeW = 1.8.dp.toPx()
                val offset = 4.dp.toPx()
                drawLine(
                    color = errorColor,
                    start = Offset(center.x - offset, center.y - offset),
                    end = Offset(center.x + offset, center.y + offset),
                    strokeWidth = strokeW,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = errorColor,
                    start = Offset(center.x + offset, center.y - offset),
                    end = Offset(center.x - offset, center.y + offset),
                    strokeWidth = strokeW,
                    cap = StrokeCap.Round,
                )
            }
            AgentCardStepStatus.Blocked -> {
                drawCircle(
                    color = warnColor.copy(alpha = 0.15f),
                    radius = radius,
                    center = center,
                )
                val strokeW = 1.8.dp.toPx()
                val barH = 3.5.dp.toPx()
                val barGap = 2.2.dp.toPx()
                drawLine(
                    color = warnColor,
                    start = Offset(center.x - barGap, center.y - barH),
                    end = Offset(center.x - barGap, center.y + barH),
                    strokeWidth = strokeW,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = warnColor,
                    start = Offset(center.x + barGap, center.y - barH),
                    end = Offset(center.x + barGap, center.y + barH),
                    strokeWidth = strokeW,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
private fun CardStepRow(step: AgentCardStep) {
    val sp = LocalAppSpacing.current
    val labelColor = if (step.status == AgentCardStepStatus.Running) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val labelWeight = if (step.status == AgentCardStepStatus.Running) FontWeight.SemiBold else FontWeight.Normal
    val statusLabel = when (step.status) {
        AgentCardStepStatus.Done -> stringResource(Res.string.card_status_done)
        AgentCardStepStatus.Running -> stringResource(Res.string.card_status_running)
        AgentCardStepStatus.Pending -> stringResource(Res.string.card_status_pending)
        AgentCardStepStatus.Failed -> stringResource(Res.string.card_status_failed)
        AgentCardStepStatus.Blocked -> stringResource(Res.string.card_status_blocked)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(sp.md10),
        modifier = Modifier
            .fillMaxWidth()
            // 8dp 圆角与 shapes.small 同值：改吃 token（视觉零变化，欠债 -1）。
            .clip(MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .semantics { contentDescription = statusLabel },
    ) {
        StepStatusIcon(step.status)
        Text(
            text = step.label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = labelWeight,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        step.assignee?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.04f), RoundedCornerShape(6.dp))
                    .background(SurfaceTiers.Raised, RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 1.5.dp),
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
/**
 * 原子 4/5 · DiffViewer（§7.3）：升级稿「现代代码视窗小卡」（relay.html:557-563）——
 * surface-2 底 + 5% 黑细边 + 圆角 10 + mono；.dfhead 两端分推路径与 .diff-stat，
 * note 11px on-2。摘要收 2 行，>48 字符才给「展开」（短摘要配展开是骗点击）。
 */
@Composable
private fun CardDiffViewerRow(diff: AgentCardDiff) {
    var expanded by remember(diff.summary) { mutableStateOf(false) }
    val summary = diff.summary
    val expandable = summary != null && summary.length > CARD_DIFF_SUMMARY_EXPAND_OVER
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
            .background(SurfaceTiers.Raised, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            androidx.compose.material3.Icon(
                painter = painterResource(Res.drawable.ic_terminal),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(com.kapp.tidings.ui.theme.IconSizes.Inline),
            )
            if (diff.path != null) {
                Text(
                    text = diff.path,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (diff.additions != null || diff.deletions != null) {
                // **`+N / −N` 是纯彩色等宽文字，不是药丸**。
                //
                // 原型 `.acard .df .diff-stat{display:inline-flex;gap:4px;font-size:11px}` +
                // `.add{color:var(--online);font-weight:600}` / `.del{color:var(--error);font-weight:600}`
                // （prototype/relay.html:561-562）：只有颜色与字重，没有底色、没有圆角、没有内边距。
                // 代码这里曾经自己加了一层「淡色药丸」（0.12 透明底色 + 4dp 圆角 + 5/1dp 内边距），
                // 于是逐屏取值差集在 5 屏（m-histdate / d-chat / d-histsearch / d-groupcreate /
                // 及其共享切片）同时长出「只代码 4」的圆角与「只代码 1」的间距 —— 那不是原型有的档，
                // 是这一处自造的。字重也照原型收成 600（原来是 700）。
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "+${diff.additions ?: 0}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.accents.online,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text = "−${diff.deletions ?: 0}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
        if (summary != null) {
            // 只放摘要文本（宿主侧本就只给「N 个文件」）：`+N −N` 已由上面头部行的
            // 彩色等宽文字承担（D-NEW-3）—— `card_diff_summary` 格式串曾把计数再追加
            // 一遍，同一张卡上出现两组增删数，用户会当成两个不同的统计。
            Text(
                text = summary,
                style = MaterialTheme.typography.labelSmall,
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

/**
 * 原子 6/6 · ArtifactList：升级稿「文件小胶囊」（relay.html:565-572）—— surface-2 底
 * + 圆角 10 + 细边框 + 28×28 图标座（agent 紫 10% 底、ic_file 16px）+ nm 13/600 + mt mono。
 *
 * 自描述契约的落点（升级计划 §7.4）：条目可带**意图行**（稳定键 → 本地词条）与
 * **预览入口**。入口只画「真的能打开」的：渲染种类可渲染 + 发出方声明 delivered +
 * 消息流里找到同 id 关联的已落盘文件（savedPathFor）—— 三个条件缺一个就
 * 退回纯元数据行，暂态（未投递）如实给一句解释而不是一颗死按钮。
 *
 * onPreviewArtifact 是可空槽：null = 这个壳还没有预览通路（预览窗整块不画），
 * 与动作槽的同名纪律一致 —— 按下去没反应的按钮比没有更糟。
 */
@Composable
private fun CardArtifactRows(
    artifacts: List<AgentCardArtifact>,
    savedPathFor: (AgentCardArtifact) -> String?,
    onPreviewArtifact: ((AgentCardArtifact, String) -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        artifacts.forEach { artifact ->
            val kind = ArtifactPreview.kindOf(artifact.name, artifact.mime)
            val savedPath = savedPathFor(artifact)
            val previewable = ArtifactPreview.canPreview(artifact, kind, savedPath)
            val blockedReason = ArtifactPreview.blockedReasonOf(artifact, kind, savedPath)
            var previewOpen by remember(artifact.name) { mutableStateOf(false) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
                    .background(SurfaceTiers.Raised, RoundedCornerShape(10.dp))
                    .then(
                        if (previewable && onPreviewArtifact != null) {
                            Modifier.clickable { previewOpen = true }
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                // .art .art-ic：28×28 图标座（agent 紫 10% 底 + primary 图标）。
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(MaterialTheme.accents.agentAccent.copy(alpha = 0.1f), RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_file),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(com.kapp.tidings.ui.theme.IconSizes.Label),
                    )
                }
                Column(Modifier.weight(1f)) {
                    // .art .nm：13/600 on 单行省略（relay.html:570-571）。
                    Text(
                        text = artifact.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (previewable && onPreviewArtifact != null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = CARD_ARTIFACT_NAME_MAX_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // .art .mt：11px on-2 mono（meta 与名字两行 —— 新稿布局）。
                    artifactMetaLine(artifact)?.let { meta ->
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                        )
                    }
                    // 意图行（§7.4 人类可读意图）：键在词条里查，查不到不画 ——
                    // 编一个通用词等于给不知道是什么的文件编故事。
                    artifact.intent?.let { key ->
                        artifactIntentLabel(key)?.let { label ->
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    // 暂态解释：文件还没投到本机时，一句「为什么看不了」胜过沉默。
                    if (!previewable && blockedReason != null) {
                        ArtifactPreviewBlockedReason(blockedReason)
                    }
                }
            }
            if (previewOpen && savedPath != null) {
                ArtifactPreviewDialog(
                    artifact = artifact,
                    savedPath = savedPath,
                    onDismiss = { previewOpen = false },
                )
            }
        }
    }
}

/**
 * 意图键 → 本地词条（双语进 strings，键跨语言稳定）。未登记的键返回 null：
 * 发出方给了一个本端不认识的键，不画比猜一个更诚实。
 */
@Composable
private fun artifactIntentLabel(intentKey: String): String? = when (intentKey) {
    "patch-full" -> stringResource(Res.string.card_artifact_intent_patch_full)
    "test-report" -> stringResource(Res.string.card_artifact_intent_test_report)
    "run-log" -> stringResource(Res.string.card_artifact_intent_run_log)
    "prototype" -> stringResource(Res.string.card_artifact_intent_prototype)
    else -> null
}

/**
 * 原子 5/5 · ButtonGroup：升级稿「按钮与说明融为一体」（relay.html:574-579）——
 * .acts 竖排：.pri-row（btn sm 等分 r9 7×12）+ .sub-row 居中次级文字按钮（11 on-2、
 * 无底无边框）。本仓动作模型暂无 subActions 数据键（载荷 Schema 无此键，
 * detailed-design §2.1）—— sub-row 的样式接缝先不落（数据到不了的字段不画）,
 * 只落 pri-row 的等分布局。审批防重三态判据不变。
 */
@Composable
private fun CardButtonGroupRow(
    card: AgentCardUi,
    pendingActions: Set<String>,
    actionsEnabled: Boolean,
    onAction: (card: AgentCardUi, actionId: String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            card.actions.forEach { action ->
                val ui = cardActionUiOf(card, pendingActions, action.actionId)
                // 已知系统动作的文案由端上本地化（宿主发出的卡只带 actionId 占位 ——
                // 数据不背文案，cjkLiteralGate 的口径也不容生产源码硬编码文案）。
                val text = when {
                    ui.lock == CardActionLock.PendingConfirm -> stringResource(Res.string.card_pending_confirm)
                    action.actionId == SYSTEM_ACTION_MERGE_PATCH -> stringResource(Res.string.card_action_merge_patch)
                    action.actionId == SYSTEM_ACTION_ABORT_TASK -> stringResource(Res.string.card_action_abort)
                    else -> action.label
                }
                val iconRes = when (action.actionId) {
                    SYSTEM_ACTION_MERGE_PATCH -> Res.drawable.ic_check_double
                    SYSTEM_ACTION_ABORT_TASK -> Res.drawable.ic_close
                    "approve-prototype" -> Res.drawable.ic_check_double
                    "retry" -> Res.drawable.ic_refresh
                    else -> null
                }
                if (action.approval) {
                    AppButton(
                        text = text,
                        onClick = { onAction(card, action.actionId) },
                        enabled = actionsEnabled && !ui.disabled,
                        size = AppButtonSize.Medium,
                        tone = AppButtonTone.Primary,
                        leadingIcon = iconRes,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    AppOutlinedButton(
                        text = text,
                        onClick = { onAction(card, action.actionId) },
                        enabled = actionsEnabled && !ui.disabled,
                        size = AppButtonSize.Medium,
                        tone = AppButtonTone.Neutral,
                        leadingIcon = iconRes,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * 预设选项块（升级稿 ask_question，detailed-design §7.9）→ **单选行**，不是按钮。
 *
 * 为什么不再画成按钮（真机真实用户反馈）：原型待选卡曾经是「推荐项 = 实心主按钮
 * + 另两项描边按钮 + Approve & Develop 实心主按钮」，四个同一种视觉语言 ——
 * 用户看到的是四个平级按钮，真相却是「三选一 + 一个确认」。
 * 选型是**选择**不是**动作**：radio 行表达「从这几个里挑一个」，推荐项用角标说
 * 「推荐」而不是用实心填充抢「主动作」的地位；实心主按钮只留给卡上唯一的确认
 * 动作（如 Approve & Develop）。
 * 动作路由不变：actionId = 选项文本（§7.9 第 3 条「获得确定性参数后再开工」）。
 * 已判定/已提交的选择行照实亮起（与按钮组同一套 [cardActionUiOf] 锁态）。
 * 无动作通路时整块不画（与 ButtonGroup 同一条 DeadButtonGuard 纪律）。
 */
@Composable
private fun CardOptionListRows(
    card: AgentCardUi,
    pendingActions: Set<String>,
    actionsEnabled: Boolean,
    onAction: ((card: AgentCardUi, actionId: String) -> Unit)?,
) {
    if (onAction == null) return  // 没有动作通路 = 点了没反应的选项比没有更糟
    val sp = LocalAppSpacing.current
    Column(verticalArrangement = Arrangement.spacedBy(sp.sm)) {
        card.options.forEach { option ->
            val ui = cardActionUiOf(card, pendingActions, option.label)
            val checked = ui.lock != CardActionLock.Editable
            val shape = MaterialTheme.shapes.small
            val borderStroke = BorderStroke(
                1.dp,
                if (checked) MaterialTheme.accents.agentAccent.copy(alpha = 0.45f)
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.16f),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(sp.md10),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(
                        if (checked) MaterialTheme.accents.agentAccent.copy(alpha = 0.10f)
                        else SurfaceTiers.Raised,
                        shape,
                    )
                    .border(borderStroke, shape)
                    .clickable(enabled = actionsEnabled && !ui.disabled) {
                        onAction(card, option.label)
                    }
                    .padding(horizontal = sp.lg14, vertical = sp.md),
            ) {
                OptionRadioDot(checked = checked)
                Text(
                    text = option.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (option.recommended && !checked) {
                    Text(
                        text = stringResource(Res.string.card_option_recommended),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.accents.agentAccentText,
                        modifier = Modifier
                            .background(
                                MaterialTheme.accents.agentAccent.copy(alpha = 0.12f),
                                RoundedCornerShape(sp.sm6),
                            )
                            .padding(horizontal = sp.sm, vertical = sp.xxs),
                    )
                }
            }
        }
    }
}

/** 单选行的 radio 圆点：Body 外圈，选中时 sm 实芯，颜色随选中态走 agentAccent。 */
@Composable
private fun OptionRadioDot(checked: Boolean) {
    val sp = LocalAppSpacing.current
    val ringColor =
        if (checked) MaterialTheme.accents.agentAccent
        else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    val radioBorder = BorderStroke(sp.xxs, ringColor)
    Box(
        modifier = Modifier
            .size(com.kapp.tidings.ui.theme.IconSizes.Body)
            .border(radioBorder, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Box(
                modifier = Modifier
                    .size(sp.sm)
                    .background(MaterialTheme.accents.agentAccent, CircleShape),
            )
        }
    }
}
