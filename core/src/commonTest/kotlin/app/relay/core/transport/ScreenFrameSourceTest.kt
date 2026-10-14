// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 投屏帧源的「静止重发」语义（2026-10-08 投屏接线批次）。
 *
 * 存在的理由：手机的帧源是**推式**的（MediaProjection 的 ImageReader、ReplayKit 扩展的
 * 共享容器帧槽），画面静止时"这一拍没有新帧"是常态；而 [ScreenSender] 的契约是
 * sample 返回 null ⇒ 会话以 `capture-ended` 结束。没有这层包装，真机上手机一停手
 * 投屏就断（桌面的 Robot 是拉式采集、永远给新缓冲，才没暴露这个契约错位）。
 *
 * 四条钉死的语义：
 * 1. 有新帧：返回新帧并记为缓存；
 * 2. 没新帧：重发上一帧（seq 在前进，观看端画面仍是"活的"）；
 * 3. 一帧都没采到过仍没帧：返回 null —— 同意了但采集根本没起来，必须失败关闭，
 *    不能假装正常地挂一个空会话；
 * 4. markDead 之后恒 null：外部终止（系统通知里点了"停止投射"、扩展被回收）
 *    是真故障不是静止，缓存不能吞掉它。
 */
class ScreenFrameSourceTest {

    @Test
    fun staticScreenResendsTheLastFrameInsteadOfEndingTheSession() = runBlocking {
        var next: ByteArray? = "frame-1".encodeToByteArray()
        val source = ScreenFrameSource { seq -> next.also { next = null } }

        val first = source.sample(1)
        assertContentEquals("frame-1".encodeToByteArray(), first, "第一拍要把采到的新帧原样给出")

        // 之后采集器再也没有新帧（画面静止）。
        repeat(5) { i ->
            val frame = source.sample((i + 2).toLong())
            assertEquals(first, frame, "静止期间必须重发上一帧；返回 null 会被 ScreenSender 当成 capture-ended")
        }
    }

    @Test
    fun noFrameEverArrivesStillFailsClosed() = runBlocking {
        val source = ScreenFrameSource { _ -> null }
        assertNull(source.sample(1), "首帧都没有 = 采集真的没起来，必须让会话失败关闭")
        assertNull(source.sample(2), "持续没有首帧时仍然是 null，缓存不能凭空造帧")
    }

    @Test
    fun captureTerminatedExternallyIsNotSwallowedByTheCache() = runBlocking {
        var live = true
        val source = ScreenFrameSource { _ -> if (live) "x".encodeToByteArray() else null }
        source.sample(1)

        // 采集被外部终止：先有缓存，之后一律 null —— markDead 之前那会被缓存吞掉，
        // 这正是必须走 onCaptureEnded 回调、不能靠 null 采样的原因。
        live = false
        source.markDead()
        assertNull(source.sample(2), "markDead 之后缓存必须失效，会话要按 capture-ended 结束")
    }

    @Test
    fun aThrowingCaptureCountsAsNoNewFrame() = runBlocking {
        var boom = false
        val source = ScreenFrameSource { _ ->
            if (boom) error("采集器抛了")
            boom = true
            "ok".encodeToByteArray()
        }
        val first = source.sample(1)
        assertContentEquals("ok".encodeToByteArray(), first)
        // 抛异常按"这一拍没新帧"处理：重发缓存，而不是把异常传进泵、也不是判死。
        assertContentEquals("ok".encodeToByteArray(), source.sample(2))
    }
}
