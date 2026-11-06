// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import java.util.concurrent.ConcurrentHashMap

/**
 * 驱动限流的**被动感知**台账（P2 规格 5）：只在驱动真的报了 429 / 配额耗尽时记一个
 * 到期时间戳，之后由代码门在读档阶段避坑。零心跳、零探测、零网络开销、零持久化 ——
 * 进程重启后冷却自然消失，这是刻意的：限流是上游的短时事实，不该变成跨重启的历史判决。
 *
 * 与域层瞬态分类器的分工：那边判「要不要换人重派」的宽集合（含网络闪断），这里只认
 * **限流与配额**这一窄集合。把网络闪断也锁 5 分钟会让一次断线误伤整个名册。
 *
 * 时钟可注入：TTL 用假时钟验证，不赌真实挂钟，也不引真实 delay。
 */
class DriverCooldownTracker(private val now: () -> Long = System::currentTimeMillis) {

    private val untilByKey = ConcurrentHashMap<String, Long>()

    /** 显式标记冷却。空键与非正时长一律拒绝 —— 不许写出通配槽或永久锁死。 */
    fun markRateLimited(driverKey: String, durationMs: Long = DEFAULT_COOLDOWN_MS): Boolean {
        val slot = slotOf(driverKey) ?: return false
        if (durationMs <= 0L) return false
        untilByKey[slot] = now() + durationMs
        return true
    }

    /** 从驱动错误文本里被动捕获限流；非限流错误返回 false 且不写台账。 */
    fun captureIfRateLimited(driverKey: String, message: String?): Boolean {
        if (message.isNullOrBlank() || !RATE_LIMIT_PATTERN.containsMatchIn(message)) return false
        return markRateLimited(driverKey)
    }

    /** 剩余冷却毫秒；已过期返回 0 并顺手清掉该槽（查表即清，不另起清理线程）。 */
    fun remainingMs(driverKey: String): Long {
        val slot = slotOf(driverKey) ?: return 0L
        val until = untilByKey[slot] ?: return 0L
        val left = until - now()
        if (left > 0L) return left
        untilByKey.remove(slot)
        return 0L
    }

    fun isAvailable(driverKey: String): Boolean = remainingMs(driverKey) <= 0L

    /** 显式解除（用户改了密钥 / 手动重试）。生产路径不依赖它，TTL 自己会过期。 */
    fun clear(driverKey: String) {
        slotOf(driverKey)?.let { untilByKey.remove(it) }
    }

    private fun slotOf(rawKey: String): String? {
        val canonical = DriverKeys.canonicalKey(rawKey) ?: return null
        return DriverKeys.stripCliSuffix(canonical).takeIf { it.isNotBlank() }
    }

    companion object {
        const val DEFAULT_COOLDOWN_MS: Long = 300_000L

        /** 宿主进程内共享一份：驱动侧写、代码门侧读，必须是同一个实例。 */
        val Shared: DriverCooldownTracker = DriverCooldownTracker()

        private val RATE_LIMIT_PATTERN = Regex(
            "(?i)\\b429\\b|too many requests|quota (?:exhausted|exceeded)|rate[ -]?limit|rate_limited",
        )
    }
}
