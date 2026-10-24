// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * 远程查看的两条不变量（issue 9①②）：
 *
 * 1. **帧解码不在调用方线程上**：旧实现 `remember(frameBytes) { decodeImageBytes(it) }`
 *    在组合期的 UI 线程同步解整张位图；新实现必须把解码挪到后台 dispatcher。
 *    测法：注入记录解码线程的 decoder，断言它不是调用方线程 —— 把实现改回
 *    「直接调用 decoder」本测试就红（反向验证过）。
 * 2. **Active 相无帧要画占位**：旧实现什么都不画（整屏纯黑）；新实现的判定
 *    [activeFrameBody] 必须给「无帧」返回 [RemoteViewBody.WaitingFrames]。
 *
 * 3. 坏帧折成 null 而不是抛异常 —— 否则一次坏帧会炸掉 produceState 的协程，
 *    之后所有帧都不再解码（表现为画面永远停在最后一帧上）。
 */
class RemoteViewFrameDecodeTest {

    @Test
    fun decodeRunsOffCallerThread() = runBlocking {
        val callerThread = Thread.currentThread()
        var decodeThread: Thread? = null
        val bytes = ByteArray(8) { 0 }

        val result = decodeFrameBackground(
            bytes = bytes,
            dispatcher = Dispatchers.Default,
            decoder = {
                decodeThread = Thread.currentThread()
                null
            },
        )

        assertNull(result, "decoder 返回 null 时透传 null（坏帧折成占位，不是异常）")
        val ranOn = decodeThread
        assertNotNull(ranOn, "decoder 必须真的被执行")
        assertTrue(ranOn !== callerThread, "解码必须离开调用方线程（旧实现卡在 UI 线程组合期）")
    }

    @Test
    fun nullBytesDecodeToNullWithoutInvokingDecoder() = runBlocking {
        var invoked = false
        val result = decodeFrameBackground(
            bytes = null,
            dispatcher = Dispatchers.Default,
            decoder = {
                invoked = true
                null
            },
        )
        assertNull(result)
        assertTrue(!invoked, "没有帧字节就不该碰 decoder")
    }

    @Test
    fun decoderThrowingFoldsToNull() = runBlocking {
        val result = decodeFrameBackground(
            bytes = ByteArray(4),
            dispatcher = Dispatchers.Default,
            decoder = { error("注入：坏帧让解码器抛异常") },
        )
        assertNull(result, "坏帧必须折成 null，不能让异常炸掉解码协程")
    }

    /**
     * 反向验证过：把 [activeFrameBody] 改回旧行为（无帧也返回 Frame / 或删掉
     * WaitingFrames 档），本测试变红；恢复后变绿。
     */
    @Test
    fun activePhaseWithoutFrameShowsPlaceholder() {
        assertEquals(RemoteViewBody.WaitingFrames, activeFrameBody(hasFrame = false))
        assertEquals(RemoteViewBody.Frame, activeFrameBody(hasFrame = true))
    }

    // 不在这里测「真解码器在后台解出位图」：Skiko 无法在单测 JVM 里初始化，
    // decodeImageBytes 的真实现是平台代码（各 actual 自带），也不是本修复的对象。
    // decodeFrameBackground 的契约（离线程 / null 透传 / 坏帧折 null）已由上面的
    // 注入接缝测试钉死。
}
