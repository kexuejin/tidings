// SPDX-License-Identifier: GPL-3.0-only

package app.relay.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 钉住 [EventCursorAllocator]：会话内严格递增（task-50 审查修）。
 *
 * 修之前三处传 `System.currentTimeMillis()` 当 cursor，而 `AgentSessionRegistry` 的 KDoc
 * 明确禁止用时间戳 —— 同一毫秒内的两条事件会撞在同一个游标上：注册表按游标覆盖（先到那条没了），
 * 桌面端新加的投递去重再按 `sessionId:cursor` 把后到那条当重复丢掉。两条都是**静默丢内容**。
 *
 * 这条测试钉的就是「同一毫秒也不许撞」：把分配器改回直接返回 now，它会立刻红。
 */
class EventCursorAllocatorTest {

    @Test
    fun twoEventsInTheSameMillisecondNeverShareACursor() {
        val allocator = EventCursorAllocator()
        val first = allocator.next("s-1", now = 1_000L)
        val second = allocator.next("s-1", now = 1_000L)
        val third = allocator.next("s-1", now = 1_000L)

        assertEquals(1_000L, first, "第一条就用当下这个毫秒")
        assertTrue(second > first, "同一毫秒的第二条必须比第一条大：$second vs $first")
        assertTrue(third > second, "第三条继续递增：$third vs $second")
    }

    @Test
    fun theCursorFollowsTheClockWhenItMovesForward() {
        val allocator = EventCursorAllocator()
        allocator.next("s-2", now = 1_000L)
        assertEquals(5_000L, allocator.next("s-2", now = 5_000L), "时钟前进就跟时钟，不额外加一")
    }

    @Test
    fun aClockGoingBackwardsDoesNotProduceASmallerCursor() {
        val allocator = EventCursorAllocator()
        val high = allocator.next("s-3", now = 9_000L)
        val afterBackwardsJump = allocator.next("s-3", now = 4_000L)
        assertTrue(
            afterBackwardsJump > high,
            "时钟回拨（NTP/手动改时间）不许把游标打回去：$afterBackwardsJump vs $high",
        )
    }

    @Test
    fun differentSessionsKeepIndependentCursors() {
        val allocator = EventCursorAllocator()
        allocator.next("s-a", now = 1_000L)
        allocator.next("s-a", now = 1_000L)
        assertEquals(1_000L, allocator.next("s-b", now = 1_000L), "另一个会话从头开始，不受别的会话影响")
    }
}
