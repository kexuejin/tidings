// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.protocol

/**
 * TCP 通道分帧：4 字节**大端**长度前缀 + UTF-8 JSON 正文（protocol §8）。
 *
 * 只做最小的编解码，不含读循环状态机 —— 读循环在 transport 层，用 ktor 的
 * `ByteReadChannel` 拼够 4 字节读长度、再读正文。
 */
object TcpFrame {

    const val LENGTH_PREFIX_BYTES: Int = 4

    /** 给正文加上长度前缀。 */
    fun encode(body: ByteArray): ByteArray {
        val out = ByteArray(LENGTH_PREFIX_BYTES + body.size)
        val n = body.size
        out[0] = ((n ushr 24) and 0xFF).toByte()
        out[1] = ((n ushr 16) and 0xFF).toByte()
        out[2] = ((n ushr 8) and 0xFF).toByte()
        out[3] = (n and 0xFF).toByte()
        body.copyInto(out, LENGTH_PREFIX_BYTES)
        return out
    }

    /**
     * 读长度前缀。
     *
     * 负数不可能出现（最高位被当成符号位时也只会是长度超过 2 GiB，协议里不存在），
     * 调用方只需检查 `>= 0` 并设一个自己的上限。
     */
    fun decodeLength(header: ByteArray): Int {
        require(header.size >= LENGTH_PREFIX_BYTES) {
            "长度前缀需要 $LENGTH_PREFIX_BYTES 字节，实际 ${header.size}"
        }
        return ((header[0].toInt() and 0xFF) shl 24) or
            ((header[1].toInt() and 0xFF) shl 16) or
            ((header[2].toInt() and 0xFF) shl 8) or
            (header[3].toInt() and 0xFF)
    }
}
