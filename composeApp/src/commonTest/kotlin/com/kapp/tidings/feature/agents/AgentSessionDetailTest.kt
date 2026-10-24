// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.feature.agents

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 会话详情「跟随滚动」的判定（issue「会话详情无跟随滚动」）。
 *
 * 旧行为：整屏 verticalScroll 且从不自动滚 —— 新事件追加在屏外，用户毫无感知。
 * 新行为：视口停在底部附近（离底 ≤ 阈值）才跟随；用户往上翻了就不打扰。
 */
class AgentSessionDetailTest {

    /**
     * 反向验证过：把 [shouldAutoFollow] 改回「恒 false」（旧的从不滚动行为），
     * 本测试变红；恢复后变绿 —— 它钉住的是「近底必须跟随」这条纪律。
     */
    @Test
    fun testShouldAutoFollowNearBottom() {
        // 内容不足一屏（无滚动条）：按跟随处理 —— 追加内容本来就该看得见。
        assertEquals(true, shouldAutoFollow(lastVisible = 0, max = 0, threshold = 480))
        // 正好在底。
        assertEquals(true, shouldAutoFollow(lastVisible = 1_000, max = 1_000, threshold = 480))
        // 离底在阈值内：跟随。
        assertEquals(true, shouldAutoFollow(lastVisible = 600, max = 1_000, threshold = 480))
        assertEquals(true, shouldAutoFollow(lastVisible = 520, max = 1_000, threshold = 480))
    }

    @Test
    fun testShouldNotAutoFollowWhenScrolledUp() {
        // 用户往上翻了（离底超过阈值）：不打扰 —— 此时自动滚动会抢走正在回看的阅读位置。
        assertEquals(false, shouldAutoFollow(lastVisible = 0, max = 1_000, threshold = 480))
        assertEquals(false, shouldAutoFollow(lastVisible = 519, max = 1_000, threshold = 480))
    }

    /**
     * 触发跟随的信号必须覆盖这一屏的**每一路**内容，而不只是聊天消息。
     *
     * 反向验证过：把 [followScrollSignature] 改回只返回 `listOf(messageCount)`
     * （旧实现拿聊天条数单独当 key），下面每个"只动一路"的断言都会变红。
     */
    @Test
    fun testFollowSignatureCoversEveryFeed() {
        val base = followScrollSignature(messageCount = 3, eventCount = 5, approvalCount = 1, taskCount = 2)
        assertEquals(4, base.size, "四个分量各占一格（求和会让『来一条消息 + 走一条任务』互相抵消）")

        // 只动其中一路，签名都必须变化 —— 否则那一路的新内容不会触发滚动。
        listOf(
            followScrollSignature(messageCount = 4, eventCount = 5, approvalCount = 1, taskCount = 2),
            followScrollSignature(messageCount = 3, eventCount = 6, approvalCount = 1, taskCount = 2),
            followScrollSignature(messageCount = 3, eventCount = 5, approvalCount = 2, taskCount = 2),
            followScrollSignature(messageCount = 3, eventCount = 5, approvalCount = 1, taskCount = 3),
        ).forEach { moved ->
            assertEquals(true, moved != base, "任一路条数变化都必须改变跟随信号：$moved vs $base")
        }

        // 什么都没变时签名必须稳定：否则每次重组都滚一次，用户没法往上翻。
        assertEquals(base, followScrollSignature(messageCount = 3, eventCount = 5, approvalCount = 1, taskCount = 2))
    }
}
