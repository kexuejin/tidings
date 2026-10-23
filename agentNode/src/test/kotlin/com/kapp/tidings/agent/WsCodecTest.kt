// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * `Ws.encode` 的帧头编码断言。
 *
 * 为什么值得单独立一个测试：帧头一旦写错，症状是「小消息全对、一传大文件整个失步」，
 * 而失步点在**对端**、报错误导成网络/内存/代理问题，本地很难一眼归因
 * （2026-10 实测：64 位长度用 Int 移位写成 `00 00 01 00 00 01 00 00`，
 * 1 MiB 流从第一帧 64 KiB 之后全烂，而 70 KB 流完全正常）。
 *
 * 所以这里不测「能连上」，只测**字节**：每个长度分支的帧头必须逐字节等于期望值。
 */
class WsCodecTest {

    /** 解析服务端帧头里的长度字段，模拟对端（RFC 6455 §5.2）。 */
    private fun declaredLength(frame: ByteArray): Long {
        val b1 = frame[1].toInt() and 0xFF
        val twoByte = ((frame[2].toInt() and 0xFF) * 256L) + (frame[3].toInt() and 0xFF)
        return when {
            b1 < 126 -> b1.toLong()
            b1 == 126 -> twoByte
            else -> {
                var v = 0L
                for (i in 2..9) v = v * 256L + (frame[i].toLong() and 0xFF)
                v
            }
        }
    }

    private fun headerSize(frame: ByteArray): Int = when (frame[1].toInt() and 0xFF) {
        126 -> 4
        127 -> 10
        else -> 2
    }

    @Test
    fun `短帧用 7 位长度`() {
        val f = Ws.encode(ByteArray(10), Ws.OP_BINARY)
        assertContentEquals(byteArrayOf(0x82.toByte(), 10), f.copyOf(2))
        assertEquals(12, f.size)
    }

    @Test
    fun `126 到 65535 走 2 字节扩展长度`() {
        for (n in listOf(126, 127, 1000, 65328, 65535)) {
            val f = Ws.encode(ByteArray(n), Ws.OP_BINARY)
            assertEquals(126, f[1].toInt() and 0xFF, "n=$n 应走 126 分支")
            assertEquals(n.toLong(), declaredLength(f), "n=$n 长度字段不对")
            assertEquals(4 + n, f.size, "n=$n 帧长不对")
        }
    }

    /**
     * 这一组是本次事故的正面靶心。
     *
     * `CHUNK = 64 * 1024`，socket 读只有把缓冲区填满才返回满 65536 —— 也就是说
     * 127 分支在聊天时几乎碰不到，一到文件传输就必然踩中。
     */
    @Test
    fun `65536 起走 8 字节扩展长度且是大端`() {
        for (n in listOf(65536, 65537, 262144, 1_048_576, 4_194_304, 16_777_216)) {
            val f = Ws.encode(ByteArray(n), Ws.OP_BINARY)
            assertEquals(127, f[1].toInt() and 0xFF, "n=$n 应走 127 分支")
            assertEquals(n.toLong(), declaredLength(f), "n=$n 长度字段不对")
            assertEquals(10 + n, f.size, "n=$n 帧长不对")
            // 长度必须用「最少的字节数」编码，8 字节里的高 4 字节只能是 0
            assertContentEquals(
                ByteArray(4),
                f.copyOfRange(2, 6),
                "n=$n 的 64 位长度高 32 位非零 —— 多半又把 Int 移位折回低 32 位了",
            )
        }
    }

    @Test
    fun `不同长度编码出的帧头互不相同`() {
        // 反向防护：曾经出现过高 4 字节重复低 4 字节的写法，
        // 它会让 256 和 65536+256 编出同样的头，肉眼看字节很难发现。
        val heads = listOf(256, 65536 + 256, 65536 + 65536 + 256).map {
            Ws.encode(ByteArray(it), Ws.OP_BINARY).copyOf(10)
        }
        assertEquals(heads.size, heads.toSet().size, "不同长度的帧头撞车了")
    }

    @Test
    fun `opcode 落在第一个字节低位且 FIN 恒置位`() {
        for (op in listOf(Ws.OP_TEXT, Ws.OP_BINARY, Ws.OP_CLOSE, Ws.OP_PING, Ws.OP_PONG)) {
            val f = Ws.encode(ByteArray(3), op)
            assertEquals(0x80, f[0].toInt() and 0xF0, "FIN 位没置上，或 RSV 位脏了")
            assertEquals(op, f[0].toInt() and 0x0F, "opcode 写错了")
        }
    }
}