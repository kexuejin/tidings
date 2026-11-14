// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.core.protocol.ConvId
import com.kapp.tenon.domain.RelaySettings
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 外部节点单聊配额与权限看门狗 (AgentQuotaGate)。
 *
 * 防白嫖与限流铁律：
 * 1. 默认关闭拦截：
 *    若机主在设置中未开启 allowExternalPrivateChat（默认 false），所有来自外部节点的单聊请求一律拒止；
 * 2. 针对【物理对端节点 (peerNodeId)】统一限流：
 *    从会话键 `agent:<nodeId>/<agentId>` 中精准提取 `peerNodeId` 作为配额键，杜绝外部节点通过频繁变换 agentId 绕过配额；
 * 3. 严格配额边界：
 *    当 limitPerDay <= 0 时直接拒止，绝不放行任何请求；
 * 4. 按天滚动配额与有界内存自愈：
 *    通过 epochDay 滚动重置计数，并在超出容量阈值时清理过期的历史桶，杜绝恶意 nodeId 撑爆内存。
 */
class AgentQuotaGate(
    private val limitPerDay: Int = 10,
    private val maxBuckets: Int = 1000,
    private val epochDayProvider: () -> Long = {
        System.currentTimeMillis() / (1000L * 60L * 60L * 24L)
    },
) {
    data class DailyQuota(val epochDay: Long, val count: AtomicInteger)

    private val quotaBuckets = ConcurrentHashMap<String, DailyQuota>()

    companion object {
        fun extractPeerNodeId(convId: String): String {
            if (!ConvId.isExternalAgent(convId)) return convId
            val withoutPrefix = convId.removePrefix(ConvId.AGENT_PREFIX)
            return withoutPrefix.substringBefore('/')
        }
    }

    /**
     * 检查外部节点发起的会话是否放行。
     *
     * @param convId 会话 ID (如 agent:node-123/code-reviewer)
     * @param settings 当前系统的全局设置
     * @param currentEpochDay 可选注入的测试 epochDay，为 null 时走 epochDayProvider
     * @return true 放行并扣减配额，false 拦截
     */
    fun checkAndConsume(
        convId: String,
        settings: RelaySettings,
        currentEpochDay: Long? = null,
    ): Boolean {
        // 非外部 Agent 单聊不占用外部配额
        if (!ConvId.isExternalAgent(convId)) {
            return true
        }

        // 铁律 1：未开启外部单聊开关时，一律拒止
        if (!settings.allowExternalPrivateChat || limitPerDay <= 0) {
            return false
        }

        // 铁律 2：按物理对端节点计数，严防变换 agentId 白嫖
        val peerKey = extractPeerNodeId(convId)
        val today = currentEpochDay ?: epochDayProvider()

        // 内存自愈与防爆：超过上限时清理非今日的陈旧桶
        if (quotaBuckets.size > maxBuckets) {
            quotaBuckets.entries.removeIf { it.value.epochDay != today }
        }

        while (true) {
            val current = quotaBuckets[peerKey]
            if (current == null || current.epochDay != today) {
                // 新建或跨天重置
                val newBucket = DailyQuota(today, AtomicInteger(1))
                if (current == null) {
                    if (quotaBuckets.putIfAbsent(peerKey, newBucket) == null) {
                        return true
                    }
                } else {
                    if (quotaBuckets.replace(peerKey, current, newBucket)) {
                        return true
                    }
                }
            } else {
                // 当天累加
                val currentCount = current.count.get()
                if (currentCount >= limitPerDay) {
                    return false
                }
                if (current.count.compareAndSet(currentCount, currentCount + 1)) {
                    return true
                }
            }
        }
    }

    fun getUsedQuota(convId: String, currentEpochDay: Long? = null): Int {
        val peerKey = extractPeerNodeId(convId)
        val today = currentEpochDay ?: epochDayProvider()
        val bucket = quotaBuckets[peerKey] ?: return 0
        return if (bucket.epochDay == today) bucket.count.get() else 0
    }

    fun reset() {
        quotaBuckets.clear()
    }
}
