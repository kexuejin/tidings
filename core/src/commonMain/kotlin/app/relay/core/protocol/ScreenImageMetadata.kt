// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.protocol

/**
 * 屏幕图像尺寸与 JPEG 头部元数据。
 */
data class ScreenImageSize(
    val width: Int,
    val height: Int,
)

/**
 * 屏幕协助图像元数据检查工具，对齐屏幕协助图像尺寸规范。
 */
object ScreenImageMetadata {

    private val JPEG_START_OF_FRAME: Set<Int> = setOf(
        0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf
    )

    fun isScreenSize(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        if (width > Limits.SCREEN_MAX_EDGE || height > Limits.SCREEN_MAX_EDGE) return false
        val pixels = width.toLong() * height.toLong()
        return pixels <= Limits.SCREEN_MAX_PIXELS
    }

    /**
     * 校验 JPEG 字节流格式、尺寸与有界性。
     */
    fun screenImageSize(bytes: ByteArray): ScreenImageSize? {
        if (bytes.isEmpty() || bytes.size > Limits.SCREEN_MAX_FRAME_BYTES) return null
        // 校验 JPEG 尾部标记 EOI (0xFF, 0xD9)
        if ((bytes[bytes.size - 2].toInt() and 0xFF) != 0xFF ||
            (bytes[bytes.size - 1].toInt() and 0xFF) != 0xD9
        ) {
            return null
        }
        val meta = inspectJpeg(bytes) ?: return null
        if (!isScreenSize(meta.width, meta.height)) return null
        return meta
    }

    private fun inspectJpeg(bytes: ByteArray): ScreenImageSize? {
        if (bytes.size < 4) return null
        if ((bytes[0].toInt() and 0xFF) != 0xFF || (bytes[1].toInt() and 0xFF) != 0xD8) return null
        var offset = 2
        while (offset + 3 < bytes.size) {
            while (offset < bytes.size && (bytes[offset].toInt() and 0xFF) != 0xFF) offset++
            while (offset < bytes.size && (bytes[offset].toInt() and 0xFF) == 0xFF) offset++
            if (offset >= bytes.size) return null
            val marker = bytes[offset++].toInt() and 0xFF
            if (marker == 0xd8 || marker == 0x01 || (marker in 0xd0..0xd7)) continue
            if (marker == 0xd9 || marker == 0xda || offset + 2 > bytes.size) return null
            val length = uint16be(bytes, offset)
            if (length < 2 || offset + length > bytes.size) return null
            if (marker in JPEG_START_OF_FRAME) {
                if (length < 7) return null
                val h = uint16be(bytes, offset + 3)
                val w = uint16be(bytes, offset + 5)
                return if (w > 0 && h > 0) ScreenImageSize(width = w, height = h) else null
            }
            offset += length
        }
        return null
    }

    private fun uint16be(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 2 > bytes.size) return 0
        val b0 = bytes[offset].toInt() and 0xFF
        val b1 = bytes[offset + 1].toInt() and 0xFF
        return (b0 shl 8) or b1
    }
}
