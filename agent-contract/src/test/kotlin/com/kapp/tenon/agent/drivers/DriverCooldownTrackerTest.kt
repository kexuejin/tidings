// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P2 规格 5 的被动限流台账：**只认限流/配额耗尽，不认一般瞬态故障**。
 * 时钟是注入的 —— 用真实 delay 赌 300 秒会撞 envDependentTestGate，也跑不动。
 */
class DriverCooldownTrackerTest {

    private var now = 10_000L
    private val tracker = DriverCooldownTracker { now }

    @Test
    fun a429ErrorLocksTheDriverForFiveMinutes() {
        assertTrue(tracker.captureIfRateLimited("claude", "HTTP 429 Too Many Requests"))
        assertFalse(tracker.isAvailable("claude"), "限流冷却期内必须不可派发")
        assertEquals(DriverCooldownTracker.DEFAULT_COOLDOWN_MS, tracker.remainingMs("claude"))
        assertTrue(tracker.isAvailable("dsh"), "冷却是 per-driver，不能全表禁用")
    }

    @Test
    fun quotaExhaustedAndRateLimitWordingsAreCaptured() {
        assertTrue(tracker.captureIfRateLimited("codex", "Quota Exhausted for this account"))
        assertTrue(tracker.captureIfRateLimited("agy", "rate limit exceeded, retry later"))
        assertTrue(tracker.captureIfRateLimited("dsh", "429"))
        assertFalse(tracker.isAvailable("codex"))
        assertFalse(tracker.isAvailable("agy"))
        assertFalse(tracker.isAvailable("dsh"))
    }

    @Test
    fun ordinaryFailuresNeverStartACooldown() {
        assertFalse(tracker.captureIfRateLimited("claude", "cli exited with code 1"))
        assertFalse(tracker.captureIfRateLimited("claude", "network error: socket hang up"))
        assertFalse(tracker.captureIfRateLimited("claude", "ECONNRESET"))
        assertFalse(tracker.captureIfRateLimited("claude", null))
        assertTrue(tracker.isAvailable("claude"), "普通错误/网络闪断不该把 agent 锁 5 分钟")
    }

    @Test
    fun ttlExpiryRestoresAvailabilityWithoutRestart() {
        tracker.markRateLimited("claude", 300_000L)
        now += 299_999L
        assertFalse(tracker.isAvailable("claude"), "差 1 ms 也算在冷却期")
        now += 1L
        assertTrue(tracker.isAvailable("claude"), "到期自然复苏，无需重启或清表")
        assertEquals(0L, tracker.remainingMs("claude"))
    }

    @Test
    fun aFreshRateLimitRefreshesTheDeadlineInsteadOfStacking() {
        tracker.markRateLimited("claude")
        now += 200_000L
        tracker.markRateLimited("claude", 60_000L)
        assertEquals(60_000L, tracker.remainingMs("claude"), "显式时长覆盖而不是累加")
    }

    @Test
    fun cliSuffixedKeysShareOneSlot() {
        tracker.markRateLimited("claude-cli")
        assertFalse(tracker.isAvailable("claude"))
        assertTrue(tracker.isAvailable("antigravity"), "agy 与 claude 是不同槽")
        tracker.markRateLimited("antigravity")
        assertFalse(tracker.isAvailable("agy"), "别名归一到同一槽")
    }

    @Test
    fun blankKeysAndNonPositiveDurationsFailClosed() {
        assertFalse(tracker.markRateLimited("  "), "空键不许写通配槽")
        assertFalse(tracker.markRateLimited("claude", 0L), "非正时长不许锁死")
        assertTrue(tracker.isAvailable("claude"))
    }
}
