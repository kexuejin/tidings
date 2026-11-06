// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tenon.core.transport

import com.kapp.tenon.core.protocol.Codec
import com.kapp.tenon.core.protocol.MsgTypes
import com.kapp.tenon.core.protocol.Protocol
import com.kapp.tenon.core.protocol.makeEnvelope
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「哪些帧属于文件数据面」这道**闸门的判据**。
 *
 * ## 为什么这条测试必须存在（真机缺陷 B）
 *
 * 数据面在真机上曾经是**单向通**的：数据块（发端→收端）能过闸门，窗口确认
 * （收端→发端）过不去 —— 因为闸门只认 `TDCH`，而 `TDCA` 解不成 Envelope，
 * 于是被整帧静默丢弃。症状是发端 `acked` 永远为 0、窗口停在首窗 2MiB：
 * 小文件"看起来能传"（整份落在首窗里，收端已转正），大文件 `.part` 卡死在
 * 2,097,152 B。
 *
 * 回环测试（`ChannelFileTransferE2ETest`）绕过闸门直接对接两个路由器，所以
 * **它再绿也不能证明这条判据是对的** —— 判据本身必须单独钉住。
 *
 * 只测 [FileChannelRouter.isChannelFrame] 这个纯函数：闸门（`SecureChannelTransport`
 * 的默认参数）必须复用它，那条"不许抄第二份"由 jvmTest 里的源码级守卫盯着。
 */
class FileChannelFrameGateTest {

    @Test
    fun dataChunkFramesPassTheGate() {
        val frame = FileChunkFrame.encode("transfer#file", 0L, ByteArray(16) { it.toByte() })
        assertTrue(FileChannelRouter.isChannelFrame(frame), "TDCH 是数据面帧")
    }

    /**
     * 这条就是缺陷 B 的**回归钉**：ACK 必须与数据块同权。
     *
     * 少了它，发端的窗口永远不前进 —— 而所有小文件的用例都会照样通过。
     */
    @Test
    fun windowAckFramesPassTheGate() {
        val ack = FileChunkAck.encode("transfer#file", 64L * 1024L)
        assertTrue(FileChannelRouter.isChannelFrame(ack), "TDCA 也是数据面帧，缺它发端窗口永不前进")
    }

    @Test
    fun envelopeBytesAreNotDataplaneFrames() {
        val env = makeEnvelope(
            type = MsgTypes.MSG,
            from = "node-a",
            payload = Codec.textPayload("hello"),
            id = "e1",
            ts = 1_000L,
        )
        assertFalse(
            FileChannelRouter.isChannelFrame(Codec.encodeToBytes(env)),
            "普通消息必须交回 Messenger 走可靠管线，不能被数据面吞掉",
        )
    }

    @Test
    fun shortAndForeignBytesAreNotDataplaneFrames() {
        assertFalse(FileChannelRouter.isChannelFrame(ByteArray(0)))
        assertFalse(FileChannelRouter.isChannelFrame(byteArrayOf('T'.code.toByte())))
        assertFalse(FileChannelRouter.isChannelFrame("TDCH".encodeToByteArray().copyOf(3)))
        // 魔数对、版本对、但长度不自洽 —— 仍算数据面帧（由 decode 判坏帧），
        // 这里只钉「魔数族」这一层，不越权替解码器下结论。
        assertTrue(FileChannelRouter.isChannelFrame(byteArrayOf('T'.code.toByte(), 'D'.code.toByte(), 'C'.code.toByte(), 'A'.code.toByte())))
    }

    @Test
    fun bothFamiliesFitTheUdpBudgetHeadroom() {
        // 只是把「ACK 是极小帧」这件事写下来：它比任何 UDP/TCP 分片阈值都小得多，
        // 所以"ACK 被当成大帧丢掉"不可能是尺寸问题 —— 只可能是判据问题。
        val ack = FileChunkAck.encode("t#f", 0L)
        assertTrue(ack.size < Protocol.UDP_MAX_PAYLOAD, "ACK 帧 " + ack.size + "B 应远小于 UDP 上限")
    }
}
