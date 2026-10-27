// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

/**
 * 文件数据面的窗口确认帧:告诉发送端「我已连续收到 [ackedUpTo] 字节」。
 *
 * magic "TDCA"(4) + ver u8 + tidLen u16 + tid + ackedUpTo u64。
 * 只确认**连续前缀**:空洞必须重传,不能让接收端"收一半就报全收"。
 */
object FileChunkAck {
    private val MAGIC = byteArrayOf('T'.code.toByte(), 'D'.code.toByte(), 'C'.code.toByte(), 'A'.code.toByte())
    const val VERSION: Int = 1
    const val MAX_TID_BYTES: Int = 128

    data class Decoded(val transferId: String, val ackedUpTo: Long)

    fun isAck(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        for (k in MAGIC.indices) if (bytes[k] != MAGIC[k]) return false
        return true
    }

    fun encode(transferId: String, ackedUpTo: Long): ByteArray {
        require(ackedUpTo >= 0)
        val tid = transferId.encodeToByteArray()
        require(tid.isNotEmpty() && tid.size <= MAX_TID_BYTES)
        val out = ByteArray(HEADER_FIXED + tid.size)
        var i = 0
        for (b in MAGIC) out[i++] = b
        out[i++] = VERSION.toByte()
        out[i++] = ((tid.size ushr 8) and 0xFF).toByte()
        out[i++] = (tid.size and 0xFF).toByte()
        for (b in tid) out[i++] = b
        for (shift in 56 downTo 0 step 8) out[i++] = ((ackedUpTo ushr shift) and 0xFF).toByte()
        return out
    }

    fun decode(bytes: ByteArray): Decoded? {
        if (bytes.size < HEADER_FIXED) return null
        for (k in MAGIC.indices) if (bytes[k] != MAGIC[k]) return null
        var i = MAGIC.size
        if (bytes[i++].toInt() and 0xFF != VERSION) return null
        val tidLen = ((bytes[i++].toInt() and 0xFF) shl 8) or (bytes[i++].toInt() and 0xFF)
        if (tidLen !in 1..MAX_TID_BYTES) return null
        if (bytes.size != HEADER_FIXED + tidLen) return null
        val transferId = bytes.decodeToString(i, i + tidLen)
        i += tidLen
        var upTo = 0L
        repeat(8) { upTo = (upTo shl 8) or (bytes[i++].toLong() and 0xFF) }
        return Decoded(transferId, upTo)
    }

    private const val HEADER_FIXED = 4 + 1 + 2 + 8
}
