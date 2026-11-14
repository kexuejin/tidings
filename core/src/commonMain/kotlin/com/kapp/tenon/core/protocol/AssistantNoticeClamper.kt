// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

import kotlinx.serialization.json.Json

/**
 * 支线任务通知帧截断器。
 *
 * 保证序列化后整帧字节数严格小于等于 [Limits.QUEST_NOTICE_FRAME_BUDGET] (650 字节)，
 * 并且字符截断时严格避开 UTF-16 代理对（Surrogate Pair），绝不产出 \uFFFD 乱码替换符。
 * 若极端情况下仍超出预算，Fail-Closed 返回 [ClampOutcome.Exceeded]，杜绝超限毒帧逃逸上线。
 */
object AssistantNoticeClamper {

    sealed interface ClampOutcome {
        val fits: Boolean
        data class Success(val notice: AssistantQuestNotice, val json: String, val bytesSize: Int) : ClampOutcome {
            override val fits: Boolean get() = true
        }
        data class Exceeded(val attemptedBytes: Int, val maxBytes: Int) : ClampOutcome {
            override val fits: Boolean get() = false
        }
    }

    private fun safeSubstring(str: String, targetLength: Int): String {
        var len = targetLength.coerceIn(0, str.length)
        if (len > 0 && str[len - 1].isHighSurrogate()) {
            len-- // 避免切在代理对中间产生 \uFFFD
        }
        return str.substring(0, len)
    }

    /**
     * 对通知对象进行阶梯级降级截断，并返回带有显式 fits 校验的结果。
     */
    fun clampNotice(
        notice: AssistantQuestNotice,
        json: Json = Codec.wireJson,
        maxBytes: Int = Limits.QUEST_NOTICE_FRAME_BUDGET
    ): ClampOutcome {
        var n = notice
        var jsonStr = json.encodeToString(AssistantQuestNotice.serializer(), n)
        var bytes = jsonStr.encodeToByteArray()
        if (bytes.size <= maxBytes) return ClampOutcome.Success(n, jsonStr, bytes.size)

        // 阶梯 1: 优先截断 summary
        var summary = n.summary
        while (bytes.size > maxBytes && summary.isNotEmpty()) {
            val step = maxOf(1, (bytes.size - maxBytes) / 3)
            summary = safeSubstring(summary, summary.length - step)
            n = n.copy(summary = if (summary.isEmpty()) "..." else "$summary...")
            jsonStr = json.encodeToString(AssistantQuestNotice.serializer(), n)
            bytes = jsonStr.encodeToByteArray()
        }
        if (bytes.size <= maxBytes) return ClampOutcome.Success(n, jsonStr, bytes.size)

        // 阶梯 2: 截断 questionText
        var question = n.questionText ?: ""
        while (bytes.size > maxBytes && question.isNotEmpty()) {
            val step = maxOf(1, (bytes.size - maxBytes) / 3)
            question = safeSubstring(question, question.length - step)
            n = n.copy(questionText = if (question.isEmpty()) null else "$question...")
            jsonStr = json.encodeToString(AssistantQuestNotice.serializer(), n)
            bytes = jsonStr.encodeToByteArray()
        }
        if (bytes.size <= maxBytes) return ClampOutcome.Success(n, jsonStr, bytes.size)

        // 阶梯 3: 截断 title
        var title = n.title
        while (bytes.size > maxBytes && title.length > 5) {
            val step = maxOf(1, (bytes.size - maxBytes) / 3)
            title = safeSubstring(title, title.length - step)
            n = n.copy(title = "$title...")
            jsonStr = json.encodeToString(AssistantQuestNotice.serializer(), n)
            bytes = jsonStr.encodeToByteArray()
        }
        if (bytes.size <= maxBytes) return ClampOutcome.Success(n, jsonStr, bytes.size)

        // 阶梯 4: 剥离全部可选大字段
        n = n.copy(summary = "...", questionText = null, diffStats = null, patchSha256 = null, clientToken = null)
        jsonStr = json.encodeToString(AssistantQuestNotice.serializer(), n)
        bytes = jsonStr.encodeToByteArray()
        if (bytes.size <= maxBytes) return ClampOutcome.Success(n, jsonStr, bytes.size)

        // 阶梯 5: 截断 questId
        var qId = n.questId
        while (bytes.size > maxBytes && qId.length > 8) {
            val step = maxOf(1, (bytes.size - maxBytes) / 3)
            qId = safeSubstring(qId, qId.length - step)
            n = n.copy(questId = qId)
            jsonStr = json.encodeToString(AssistantQuestNotice.serializer(), n)
            bytes = jsonStr.encodeToByteArray()
        }

        return if (bytes.size <= maxBytes) {
            ClampOutcome.Success(n, jsonStr, bytes.size)
        } else {
            ClampOutcome.Exceeded(bytes.size, maxBytes)
        }
    }
}
