// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 「最新活动」的新鲜度判据（不变量 4）。
 *
 * 这条判据存在的唯一理由是**别让文案撒谎**：三天前的活动不是「最新」，对端离线的
 * 会话也不该被引导去「立即回复」（它现在收不到）。
 *
 * 反向探针：把 `!online` 那一条分支删掉（离线也算 FRESH）⇒
 * `offlineIsNeverFresh` 变红；把 24h 窗口改成永远 FRESH ⇒ `yesterdaysActivityIsNotLatest` 变红。
 */
class ActivityFreshnessTest {

    private val now = 1_700_000_000_000L

    @Test
    fun freshNeedsBothOnlineAndRecent() {
        assertEquals(ActivityFreshness.FRESH, agentActivityFreshness(now - 60_000, now, online = true))
    }

    @Test
    fun yesterdaysActivityIsNotLatest() {
        assertEquals(
            ActivityFreshness.STALE,
            agentActivityFreshness(now - ACTIVITY_FRESH_WINDOW_MS - 1, now, online = true),
        )
    }

    @Test
    fun offlineIsNeverFresh() {
        // 刚刚还热，但对端已离线：不许说「最新」，更不许引导回复。
        assertEquals(ActivityFreshness.OFFLINE, agentActivityFreshness(now - 1_000, now, online = false))
    }

    @Test
    fun noActivityAtAllIsItsOwnSlot() {
        // 0 是「没有活动」，不是「很久以前」：整句文案都不该画。
        assertEquals(ActivityFreshness.NONE, agentActivityFreshness(0L, now, online = true))
        assertEquals(ActivityFreshness.NONE, agentActivityFreshness(-1L, now, online = false))
    }

    @Test
    fun exactlyOneDayOldIsAlreadyStale() {
        assertEquals(
            ActivityFreshness.STALE,
            agentActivityFreshness(now - ACTIVITY_FRESH_WINDOW_MS, now, online = true),
            "窗口是左闭右开：正好一天就跨出「最新」",
        )
    }
}
