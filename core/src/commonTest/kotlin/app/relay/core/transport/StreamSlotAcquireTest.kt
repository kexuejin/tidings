// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

import app.relay.core.nowMillis
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 有界抢流槽（task-61 服务端根因的确定性那一半）。
 *
 * 真机现场：一次 3MB 成功、一次「传输失败：timeout」+ 0 字节 .part。服务端根因是
 * **fresh pull（offset == 0）此前没有排队心跳**，拿不到流槽时既不发 wait 也不发 pull-ok，
 * 客户端只能等满 60s 空闲超时。修法有两半：① 排队也发 wait（fresh 与续传同一条心跳）；
 * ② 有界等待 —— 超时就明确回 err(busy)，让客户端快速失败并换候选。
 *
 * 这条测试钉的是 ②：把信号量传进来（自己那个已被占满的），确定性地走超时分支 ——
 * 不去造一条真的卡住的流（那是时序竞态，迟早变成假红）。
 */
class StreamSlotAcquireTest {

    @Test
    fun aFreeSlotIsTakenImmediately() = runBlocking {
        val slots = Semaphore(1)
        assertTrue(acquireStreamSlotOrNull(slots, 500L), "有空槽就该拿到")
        // 拿到就该真的占着：再抢一次（超时 50ms）必须失败。
        assertFalse(acquireStreamSlotOrNull(slots, 50L), "槽已被占 ⇒ 第二次抢必须失败而不是重复成功")
    }

    @Test
    fun aFullPoolGivesUpInsteadOfWaitingForever() = runBlocking {
        val slots = Semaphore(1)
        slots.acquire()
        val started = nowMillis()
        val acquired = acquireStreamSlotOrNull(slots, 150L)
        val elapsed = nowMillis() - started
        assertFalse(acquired, "槽被占满时必须在有界等待后放弃（好回 err(busy)），不许无限等")
        assertTrue(elapsed >= 100L, "必须是等满上限才放弃（不是立刻失败）：实测 $elapsed ms")
    }
}
