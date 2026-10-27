// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

/**
 * 文件数据块的**二进制线格式**（走加密信道，不走 base64/JSON）。
 *
 * magic "TDCH"(4) + ver u8 + tidLen u16 + tid + offset u64 + len u32 + payload。
 *
 * 为什么不用 Envelope + base64：base64 多 33%，JSON 再叠一层；实测信道
 * 64KB 帧回环 5.32MB/s，base64 后只剩 ~3.5MB/s，投屏会更紧。二进制帧直接把
 * 文件字节交给信道。身份/信任由信道握手负责（帧本身不认证）。
 */
object FileChunkFrame {
    private val MAGIC = byteArrayOf('T'.code.toByte(), 'D'.code.toByte(), 'C'.code.toByte(), 'H'.code.toByte())
    const val VERSION: Int = 1
    const val MAX_TID_BYTES: Int = 128
    const val MAX_PAYLOAD_BYTES: Int = 256 * 1024

    data class Decoded(val transferId: String, val offset: Long, val payload: ByteArray)

    /** 轻量判据(只查 magic + 最小长度),用于入站分流,不做完整校验。 */
    fun isChunkFrame(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        for (k in MAGIC.indices) if (bytes[k] != MAGIC[k]) return false
        return true
    }

    fun encode(transferId: String, offset: Long, payload: ByteArray): ByteArray {
        require(offset >= 0) { "offset must be >= 0" }
        require(payload.size <= MAX_PAYLOAD_BYTES) { "payload too large: " + payload.size }
        val tid = transferId.toByteArray(Charsets.UTF_8)
        require(tid.isNotEmpty() && tid.size <= MAX_TID_BYTES) { "bad transferId length: " + tid.size }
        val out = ByteArray(HEADER_FIXED + tid.size + payload.size)
        var i = 0
        for (b in MAGIC) out[i++] = b
        out[i++] = VERSION.toByte()
        out[i++] = ((tid.size ushr 8) and 0xFF).toByte()
        out[i++] = (tid.size and 0xFF).toByte()
        for (b in tid) out[i++] = b
        for (shift in 56 downTo 0 step 8) out[i++] = ((offset ushr shift) and 0xFF).toByte()
        for (shift in 24 downTo 0 step 8) out[i++] = ((payload.size ushr shift) and 0xFF).toByte()
        for (b in payload) out[i++] = b
        return out
    }

    fun decode(bytes: ByteArray): Decoded? {
        if (bytes.size < HEADER_FIXED) return null
        for (k in 0 until MAGIC.size) if (bytes[k] != MAGIC[k]) return null
        var i = MAGIC.size
        if (bytes[i++].toInt() and 0xFF != VERSION) return null
        val tidLen = ((bytes[i++].toInt() and 0xFF) shl 8) or (bytes[i++].toInt() and 0xFF)
        if (tidLen !in 1..MAX_TID_BYTES) return null
        if (bytes.size < HEADER_FIXED + tidLen) return null
        val transferId = String(bytes, i, tidLen, Charsets.UTF_8)
        i += tidLen
        var offset = 0L
        repeat(8) { offset = (offset shl 8) or (bytes[i++].toLong() and 0xFF) }
        var len = 0
        repeat(4) { len = (len shl 8) or (bytes[i++].toInt() and 0xFF) }
        if (len < 0 || len > MAX_PAYLOAD_BYTES) return null
        if (bytes.size != i + len) return null
        return Decoded(transferId, offset, bytes.copyOfRange(i, i + len))
    }

    private const val HEADER_FIXED = 4 + 1 + 2 + 8 + 4
}
