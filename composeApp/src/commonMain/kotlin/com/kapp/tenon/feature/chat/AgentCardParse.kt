// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.kapp.tenon.domain.model.MessageUi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 卡片 JSON → [AgentCardUi] 的解析（含宽容取值扩展与「按 cardId 折叠只留最新 rev」）。
 *
 * 解析侧的纪律：**认不出的键一律忽略，不为多一个字段就崩** —— 卡片体是插件定义的，
 * 协议层只守字节预算。反过来，形状不对又必须整张卡作废（返回 null），不能画半张：
 * 半张卡的按钮会把「不能点」画成「能点」。
 */

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
            status = stepStatusOf(obj.str("status")),
            assignee = obj.str("assignee")?.takeIf { it.isNotBlank() },
            detail = obj.str("detail")?.takeIf { it.isNotBlank() },
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
                status = stepStatusOf(obj.str("status")),
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
                labelKey = obj.str("labelKey")?.takeIf { it.isNotBlank() },
                labelArgs = obj.labelArgs(),
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
                labelKey = obj.str("labelKey")?.takeIf { it.isNotBlank() },
                labelArgs = obj.labelArgs(),
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

/** 步骤/检查项的 status 字符串 → 枚举：steps 与 checks 两处逐字同源，未知值退 Pending（协议兼容）。 */
private fun stepStatusOf(raw: String?): AgentCardStepStatus = when (raw) {
    "done", "review_ready" -> AgentCardStepStatus.Done
    "running", "working" -> AgentCardStepStatus.Running
    "failed", "error" -> AgentCardStepStatus.Failed
    "blocked" -> AgentCardStepStatus.Blocked
    else -> AgentCardStepStatus.Pending
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

/** 文案键的插值参数：只认字符串元素；非字符串/空串丢弃（宁可少一个参数也不猜）。 */
private fun JsonObject.labelArgs(): List<String> =
    array("labelArgs").mapNotNull { element ->
        (element as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    }

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
