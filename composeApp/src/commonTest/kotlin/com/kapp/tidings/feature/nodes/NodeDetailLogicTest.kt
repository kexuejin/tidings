// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.nodes

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 详情页「最近」一行的三档取值（[recentSeenBucket]）。
 *
 * 现场：手动地址从没接通（`lastSeenAt = 0`），详情页头部挂着红药丸「没有回应」，
 * 下面「最近」却写着「刚刚」—— 同一屏自相矛盾。修法：「刚刚」只留给在线档；
 * 没有记录如实印「—」。
 *
 * 反向验证：把 `recentSeenBucket` 的 `lastSeenAt <= 0` 分支改回 `JustNow`（旧行为），
 * [noRecordIsNotJustNow] 变红。
 */
class NodeDetailLogicTest {

    @Test
    fun onlineIsJustNowEvenWithoutTimestamp() {
        // 在线节点的 lastSeenAt 本来就在秒级滚动；就算还没拿到时间戳，"在线"这个事实
        // 也让「刚刚」成立（与旧行为一致的那一档，不能顺手改掉）。
        assertEquals(RecentSeenBucket.JustNow, recentSeenBucket(online = true, lastSeenAt = 0L))
        assertEquals(RecentSeenBucket.JustNow, recentSeenBucket(online = true, lastSeenAt = 123L))
    }

    @Test
    fun noRecordIsNotJustNow() {
        // 修的那一档：没在线、也没时间戳 = 从没见过。它不是「刚刚」。
        assertEquals(RecentSeenBucket.NoRecord, recentSeenBucket(online = false, lastSeenAt = 0L))
        assertEquals(RecentSeenBucket.NoRecord, recentSeenBucket(online = false, lastSeenAt = -1L))
    }

    @Test
    fun offlineWithTimestampRendersRelative() {
        assertEquals(RecentSeenBucket.Relative, recentSeenBucket(online = false, lastSeenAt = 86_400_000L))
    }
}
