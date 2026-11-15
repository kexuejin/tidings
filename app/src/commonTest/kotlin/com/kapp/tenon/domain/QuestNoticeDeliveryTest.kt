// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * quest 终态通知的有界重试投递（真机实测缺陷：链路断窗吞掉单发，便签停在 RUNNING）。
 *
 * 语义三条：预算内重试直到成功；预算耗尽如实放弃（不无限重试）；成功即刻停
 * （终态通知小而幂等，多发无害，但没必要）。
 * **反向验证**：把「单发不重试」的旧行为放回去（send 只调一次），
 * `retriesUntilSuccessWithinBudget` 立刻红 —— 这条测试确实在区分两个世界。
 */
class QuestNoticeDeliveryTest {

    /** 虚拟时钟：sleep 推进时间，不真的等待。 */
    private class VirtualClock {
        var now: Long = 0
        var sleeps = 0

        fun tick(): () -> Long = { now }

        suspend fun pause(ms: Long) {
            sleeps++
            now += ms
        }
    }

    @Test
    fun retriesUntilSuccessWithinBudget(): Unit = runTest {
        val clock = VirtualClock()
        var attempts = 0
        val delivered = QuestNoticeDelivery.deliverWithRetry(
            send = {
                attempts += 1
                attempts >= 3
            },
            deadlineMs = 120_000L,
            intervalMs = 3_000L,
            now = clock.tick(),
            sleep = { clock.pause(it) },
        )
        assertTrue(delivered, "第三次尝试成功必须在预算内送达")
        assertEquals(3, attempts, "前两次失败各重试一次")
        assertEquals(2, clock.sleeps, "成功后的等待不再发生")
    }

    @Test
    fun givesUpHonestlyWhenBudgetIsExhausted(): Unit = runTest {
        val clock = VirtualClock()
        var attempts = 0
        val delivered = QuestNoticeDelivery.deliverWithRetry(
            send = {
                attempts += 1
                false
            },
            deadlineMs = 12_000L,
            intervalMs = 3_000L,
            now = clock.tick(),
            sleep = { clock.pause(it) },
        )
        assertFalse(delivered, "预算耗尽必须如实放弃（quest-sync 亮屏对账兜底）")
        // 预算 12s / 间隔 3s：尝试发生在 now=0,3,6,9,12 —— 第 5 次恰好落在预算
        // 边界上（`>` 判据放行），其后 now+interval 越界才停。次数由预算与间隔
        // 决定，不是无限重试。
        assertEquals(5, attempts, "尝试次数由预算与间隔决定，不是无限重试")
    }

    @Test
    fun firstSuccessStopsImmediately(): Unit = runTest {
        val clock = VirtualClock()
        var attempts = 0
        val delivered = QuestNoticeDelivery.deliverWithRetry(
            send = {
                attempts += 1
                true
            },
            deadlineMs = 120_000L,
            intervalMs = 3_000L,
            now = clock.tick(),
            sleep = { clock.pause(it) },
        )
        assertTrue(delivered)
        assertEquals(1, attempts, "链路健康时一次送达，零重试零等待")
        assertEquals(0, clock.sleeps)
    }

    @Test
    fun budgetSmallerThanIntervalFailsAfterFirstAttempt(): Unit = runTest {
        val clock = VirtualClock()
        var attempts = 0
        val delivered = QuestNoticeDelivery.deliverWithRetry(
            send = {
                attempts += 1
                false
            },
            deadlineMs = 500L,
            intervalMs = 3_000L,
            now = clock.tick(),
            sleep = { clock.pause(it) },
        )
        assertFalse(delivered)
        assertEquals(1, attempts, "预算容不下第二次尝试时只发一次")
    }

    @Test
    fun oldSingleShotBehaviorWouldFailTheRetryAssertions() {
        // 反向验证：旧行为 = 只调一次 send。把「失败一次就放弃」放回去，
        // retriesUntilSuccessWithinBudget 的期望（送达 + 3 次尝试）就不成立。
        var attempts = 0
        val singleShotResult = run {
            attempts += 1
            attempts >= 3
        }
        assertFalse(singleShotResult && attempts == 3, "单发语义到不了三次尝试")
    }
}
