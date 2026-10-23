// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 等 accept 帧的宽限（task-61 真机竞态）。
 *
 * 现场：接收方「先回 accept、紧接着 pull」走两条承载，pull 可能先到 ⇒ 发送侧看到
 * 「transfer 已知但 accepted=false」⇒ 回 err(not-found) ⇒ UI 上 peer:not-found + 0 字节 .part。
 *
 * 这三条钉住宽限函数的**边界**：认识的才等、等到了就成功、等不到就放弃、不认识立刻失败。
 */
class AcceptGraceTest {

    /** 可注入的假时钟：每次 sleep 前进 [stepMs]，让测试不用真等。 */
    private class FakeClock(private val stepMs: Long = 60L) {
        var now: Long = 0L
        var sleeps: Int = 0
        fun sleep(): suspend (Long) -> Unit = { now += stepMs; sleeps++ }
    }

    @Test
    fun aPullThatBeatsTheAcceptFrameWaitsAndThenSucceeds() = runBlocking {
        val clock = FakeClock()
        val file = OutgoingFile("f1", "/tmp/f1", 16L)
        var attempts = 0
        val resolved = resolveAfterAcceptGrace(
            graceMs = 1_000L,
            isKnown = { true },
            resolve = {
                attempts++
                // 前两次还没有 accepted（accept 帧还在路上），第三次才成。
                if (attempts >= 3) file else null
            },
            sleep = clock.sleep(),
            now = { clock.now },
        )
        assertEquals(file, resolved, "accept 赶到之后必须成功，而不是把竞态判成 not-found")
        assertTrue(clock.sleeps >= 2, "必须真的等过（不是一次就放弃）：sleeps=" + clock.sleeps)
    }

    @Test
    fun anUnknownTransferFailsImmediatelyInsteadOfWaiting() = runBlocking {
        val clock = FakeClock()
        var attempts = 0
        val resolved = resolveAfterAcceptGrace(
            graceMs = 5_000L,
            isKnown = { false },
            resolve = { attempts++; null },
            sleep = clock.sleep(),
            now = { clock.now },
        )
        assertNull(resolved)
        assertEquals(1, attempts, "只解析一次就够（不认识就不该轮询）")
        assertEquals(0, clock.sleeps, "完全不认识的 transferId 不许等 —— 那是把真错误拖成几秒")
    }

    @Test
    fun aTransferThatNeverGetsAcceptedGivesUpAfterTheGrace() = runBlocking {
        val clock = FakeClock()
        val resolved = resolveAfterAcceptGrace(
            graceMs = 300L,
            isKnown = { true },
            resolve = { null },
            sleep = clock.sleep(),
            now = { clock.now },
        )
        assertNull(resolved, "等满宽限还是没 accepted ⇒ 放弃（对端会收到 not-found）")
        assertTrue(clock.sleeps >= 4, "应当是轮询到超时：sleeps=" + clock.sleeps)
    }
}
