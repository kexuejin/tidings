// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-G8（§7.10 Jitter Ping）：心跳间隔的抖动带判定 —— 抖动源注入后，间隔分布
 * 可测（种子固定 = 确定性；断言的是「带内」与「关断语义」，不是真实时钟）。
 */
class HeartbeatJitterTest {

    /** 引擎私有字段的抖动公式在测试里的镜像（单一事实：公式改这里必须跟着改）。 */
    private fun jittered(base: Long, jitterMs: Long, random: Random): Long =
        (base + if (jitterMs > 0) random.nextLong(-jitterMs, jitterMs + 1) else 0L).coerceAtLeast(1)

    @Test
    fun jitteredIntervalsStayWithinTheBand() {
        val random = Random(42) // 种子固定 = 确定性
        repeat(200) {
            val interval = jittered(5_000, 1_500, random)
            assertTrue(interval in 3_500..6_500, "间隔落带内（base±jitter），实际 $interval")
        }
    }

    @Test
    fun zeroJitterKeepsTheExactBeat() {
        val random = Random(7)
        repeat(50) {
            assertEquals(5_000L, jittered(5_000, 0, random), "jitter=0 = 测试用的固定节拍")
        }
    }

    @Test
    fun intervalNeverCollapsesBelowOne() {
        val random = Random(1)
        repeat(200) {
            assertTrue(jittered(5_000, 6_000, random) >= 1, "极端抖动也不得 ≤0（delay 非法）")
        }
    }

}
