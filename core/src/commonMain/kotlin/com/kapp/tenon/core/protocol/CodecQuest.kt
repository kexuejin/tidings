// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 主支线（Quest）跨设备帧族编解码：`quest-dispatch`（便签派发）与
 * `quest-notice`（轻量通知回执）。
 *
 * ## 能力协商不走广播 caps（D-018 同款裁决）
 *
 * 桌面广播 caps 16/16 满额（Limits.CAPS），qud1/qun1 进广播档必有一项让位。
 * quest 族的能力改由**两端显式设置**承担：[RelaySettings 兄弟层的 questPairNodes]
 * （对称配对清单，空 = 双向 fail-closed）。发端只发给配了的对端；收端只收配了的
 * 发端——旧端/陌生端的帧到不了这里（发端就不发），跑旧版的对端会因 KNOWN_TYPES
 * 不认识而拒收，便签在发端如实留存（优雅降级，不是丢消息）。
 *
 * ## 取值域
 *
 * `status` 的取值域就是 [QuestStatus] 枚举名（§3 第 7 种教训：键对、值不在域内
 * 一样整帧拒——这里收口成枚举比对，不收裸字符串）。
 */
object CodecQuest {

    internal val QUEST_DISPATCH_EXPECTED_KEYS = setOf("clientToken", "prompt")

    /** 与 [AssistantQuestNotice] 的序列化键集逐字一致（测试钉住对齐）。 */
    /** 必须在场的键（缺一整帧拒）。 */
    internal val QUEST_NOTICE_REQUIRED_KEYS = setOf("questId", "title", "status", "summary", "convId", "rev")

    /** 允许缺省的键（在场时逐字段校验；序列化时 null 字段不写 —— 与 encode 对齐）。 */
    internal val QUEST_NOTICE_OPTIONAL_KEYS = setOf(
        "clientToken", "questionText", "questionKind", "diffStats", "patchSha256",
    )

    fun validateQuestDispatch(o: JsonObject, textLimit: Int): Boolean {
        if (!hasOnlyKeys(o, QUEST_DISPATCH_EXPECTED_KEYS, emptySet())) return false
        if (!CodecWire.isStr(o["clientToken"], Limits.ID)) return false
        if (!CodecWire.isStr(o["prompt"], textLimit)) return false
        return true
    }

    fun validateQuestNotice(o: JsonObject): Boolean {
        if (!hasOnlyKeys(o, QUEST_NOTICE_REQUIRED_KEYS, QUEST_NOTICE_OPTIONAL_KEYS)) return false
        if (!CodecWire.isStr(o["questId"], Limits.ID)) return false
        if (!CodecWire.isStr(o["title"], 200)) return false
        val status = (o["status"] as? JsonPrimitive)?.content ?: return false
        if (status !in QuestStatus.entries.map { it.name }) return false
        if (!CodecWire.isStr(o["summary"], 400)) return false
        if (!CodecWire.isStr(o["convId"], Limits.ID)) return false
        (o["clientToken"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?.let { if (it.length > Limits.ID) return false }
        (o["questionText"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?.let { if (it.length > 400) return false }
        (o["questionKind"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?.let { if (it != AssistantQuestNotice.QUESTION_MERGE_APPROVAL) return false }
        (o["diffStats"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?.let { if (it.length > 100) return false }
        (o["patchSha256"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?.let { if (it.length > 64) return false }
        val rev = (o["rev"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return false
        if (rev < 0) return false
        return true
    }

    fun encodeQuestDispatch(clientToken: String, prompt: String): JsonObject = buildJsonObject {
        put("clientToken", JsonPrimitive(clientToken))
        put("prompt", JsonPrimitive(prompt))
    }

    fun encodeQuestNotice(notice: AssistantQuestNotice): JsonObject = buildJsonObject {
        put("questId", JsonPrimitive(notice.questId))
        put("title", JsonPrimitive(notice.title))
        put("status", JsonPrimitive(notice.status.name))
        put("summary", JsonPrimitive(notice.summary))
        put("convId", JsonPrimitive(notice.convId))
        notice.clientToken?.let { put("clientToken", JsonPrimitive(it)) }
        notice.questionText?.let { put("questionText", JsonPrimitive(it)) }
        notice.questionKind?.let { put("questionKind", JsonPrimitive(it)) }
        notice.diffStats?.let { put("diffStats", JsonPrimitive(it)) }
        notice.patchSha256?.let { put("patchSha256", JsonPrimitive(it)) }
        put("rev", JsonPrimitive(notice.rev))
    }

    fun decodeQuestNotice(o: JsonObject): AssistantQuestNotice? {
        if (!validateQuestNotice(o)) return null
        fun str(key: String): String? = (o[key] as? JsonPrimitive)?.content
        fun opt(key: String): String? = str(key)?.takeIf { it.isNotBlank() }
        return AssistantQuestNotice(
            questId = str("questId") ?: return null,
            title = str("title") ?: return null,
            status = QuestStatus.entries.firstOrNull { it.name == str("status") } ?: return null,
            summary = str("summary") ?: return null,
            convId = str("convId") ?: return null,
            clientToken = opt("clientToken"),
            questionText = opt("questionText"),
            questionKind = opt("questionKind"),
            diffStats = opt("diffStats"),
            patchSha256 = opt("patchSha256"),
            rev = str("rev")?.toLongOrNull() ?: 0L,
        )
    }

    /** 无未知键 + 必备键全在场（可选键在场时逐字段校验，缺省合法）。 */
    private fun hasOnlyKeys(o: JsonObject, required: Set<String>, optional: Set<String>): Boolean =
        o.keys.all { it in required || it in optional } && required.all { it in o.keys }
}
