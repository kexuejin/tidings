// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

/**
 * 投屏加密信道上的一帧：**把 screen-* 协议的字节原样封进一个二进制帧**。
 *
 * ## 为什么需要一层封套（而不是像文件那样直接投屏协议帧）
 *
 * 信道的入站口要判断「这条帧该不该进应用层」：非 Envelope 的字节会被
 * `senderOf()` 解不出来而丢弃。文件数据面靠 `TDCH`/TDCA` 两个魔数把自己认出来
 * （见 [FileChunkFrame]），投屏走的是**同一条纪律**：一个魔数 + 长度上限，
 * 让传输层一眼分开「Envelope / 文件块 / 画面」。
 *
 * ## 为什么帧里不带序号
 *
 * 下面的字节流本身就是 screen-* 协议（自带握手与 `seq`）。这一层只负责把
 * **字节流**切成信道能送的消息，不引入第二套序号 —— 两套序号迟早对不上，
 * 而这里的语义只有一条：**按顺序、不丢、不重**。
 *
 * 线格式（大端）：
 * ```
 * magic "TDSC" (4) | len (u32) | payload (len)
 * ```
 */
object ScreenChannelFrame {
    /** 魔数。选四个可读 ASCII，便于抓包与日志里一眼认出。 */
    val MAGIC = byteArrayOf('T'.code.toByte(), 'D'.code.toByte(), 'S'.code.toByte(), 'C'.code.toByte())

    /** 单帧上限：一次投屏帧最大 8MiB（协议 `FileTransfer.MAX_FRAME` 同量级）。 */
    const val MAX_PAYLOAD: Int = 8 * 1024 * 1024

    private const val HEADER = 8

    /** 这一段字节是不是投屏帧（只看魔数，不看长度）。 */
    fun isScreenFrame(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        for (i in MAGIC.indices) if (bytes[i] != MAGIC[i]) return false
        return true
    }

    /** 封一帧。payload 超过 [MAX_PAYLOAD] 直接抛错（调用方该更小的切）。 */
    fun wrap(payload: ByteArray, length: Int = payload.size): ByteArray {
        require(length in 0..payload.size) { "length=$length 不在 0..${payload.size}" }
        require(length <= MAX_PAYLOAD) { "投屏帧上限 ${MAX_PAYLOAD} 字节，收到 $length" }
        val out = ByteArray(HEADER + length)
        MAGIC.copyInto(out, 0)
        out[4] = (length ushr 24).toByte()
        out[5] = (length ushr 16).toByte()
        out[6] = (length ushr 8).toByte()
        out[7] = length.toByte()
        payload.copyInto(out, HEADER, 0, length)
        return out
    }

    /**
     * 解一帧；不是投屏帧 / 长度对不上时返回 null。
     *
     * **长度必须与实到字节严格相等**：短了说明信道把一帧拆了（本层不负责重组），
     * 长了说明帧尾混进了别的东西。两种都当坏帧，不做"尽力取前面那截"。
     */
    fun unwrap(bytes: ByteArray): ByteArray? {
        if (!isScreenFrame(bytes)) return null
        if (bytes.size < HEADER) return null
        val len = ((bytes[4].toInt() and 0xFF) shl 24) or
            ((bytes[5].toInt() and 0xFF) shl 16) or
            ((bytes[6].toInt() and 0xFF) shl 8) or
            (bytes[7].toInt() and 0xFF)
        if (len < 0 || len > MAX_PAYLOAD) return null
        if (bytes.size != HEADER + len) return null
        return bytes.copyOfRange(HEADER, bytes.size)
    }
}
