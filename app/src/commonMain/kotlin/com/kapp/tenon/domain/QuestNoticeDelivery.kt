// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import kotlinx.coroutines.delay

/**
 * quest 通知的**有界重试投递**。
 *
 * ## 为什么终态通知不许单发
 *
 * 真机实测（quesprobe4）：quest 失败的瞬间，桌面到手机的 TLS 正处于握手重试窗
 * （`state=retrying … next attempt in 35s`），单发 best-effort 返回 ok=false ——
 * 通知被无声丢弃，派单端的便签永远停在 RUNNING，而桌面卡早已翻成失败。
 * 终态通知是「必须最终到达」类：小、幂等（接收端按 questId+rev 乐观锁去重），
 * 漏一条 = 用户看到的状态与事实相反（本仓最贵的那类缺陷）。
 *
 * 所以投递在预算内**重试**：预算（默认 120s）盖过至少一轮链路重连（握手重试
 * 35s 一档）。超过预算仍失败就如实放弃并返回 false —— 更长的断窗由 quest-sync
 * 的亮屏对账兜底（那是它的职责，不是这里的）。
 */
internal object QuestNoticeDelivery {

    /**
     * 在 [deadlineMs] 预算内反复投递，成功即返回 true。
     *
     * [now] 与 [sleep] 是可注入的时钟与暂停（测试用虚拟时间），生产侧用
     * `nowMillis` 与 `delay`。[send] 的一次调用 = 一次真实发送尝试（其返回值
     * 就是该次的 ACK/成功语义）。
     */
    suspend fun deliverWithRetry(
        send: suspend () -> Boolean,
        deadlineMs: Long = DEFAULT_BUDGET_MS,
        intervalMs: Long = DEFAULT_INTERVAL_MS,
        now: () -> Long = { com.kapp.tenon.core.nowMillis() },
        sleep: suspend (Long) -> Unit = { delay(it) },
    ): Boolean {
        val start = now()
        val deadline = start + deadlineMs
        var attempt = 0
        while (true) {
            attempt++
            if (send()) return true
            if (now() + intervalMs > deadline) {
                return false
            }
            sleep(intervalMs)
        }
    }

    /** 预算盖过至少一轮链路重连（xport 握手重试是 35s 一档）。 */
    const val DEFAULT_BUDGET_MS: Long = 120_000L

    /** 重试间隔：足以跨过一次握手窗，又不至于把投递拖过任务清场。 */
    const val DEFAULT_INTERVAL_MS: Long = 3_000L
}
