// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain.orchestrator

/**
 * 瞬态异常分类器（参考 DeepSeek dsh-superagent 的 `transient(error)` 设计）。
 *
 * 毫秒级识别网络闪断、HTTP 429 配额用尽、服务端过载与连接超时：
 * - 判定为瞬态异常 ⇒ 触发发起侧协调器的换人重派（Failover 降级矩阵）；
 * - 判定为非瞬态异常 ⇒ 归类为确定性业务/编译逻辑错误，触发局部自愈重试（≤3轮）或人类求助。
 */
object TransientErrorClassifier {

    private val TRANSIENT_PATTERN = Regex(
        "(?i)ECONNRESET|ECONNREFUSED|ETIMEDOUT|EAI_AGAIN|ENOTFOUND|EPIPE|socket hang up|fetch failed|network error|overloaded|rate limit|quota exceeded|too many requests|\\b(?:408|429|50[0-4])\\b"
    )

    /** 检查错误信息是否属于瞬态故障。 */
    fun isTransient(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        return TRANSIENT_PATTERN.containsMatchIn(message)
    }

    /** 检查异常对象是否属于瞬态故障。 */
    fun isTransient(throwable: Throwable?): Boolean {
        if (throwable == null) return false
        val text = "${throwable::class.simpleName} ${throwable.message.orEmpty()}"
        return isTransient(text)
    }
}
