// SPDX-License-Identifier: GPL-3.0-only

package app.relay.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

/**
 * 桌面走 Skia（Compose 桌面版自带）。
 *
 * `makeFromEncoded` 是**惰性**的：它只把编码数据包起来，真正的像素在第一次画的时候才解 ——
 * 所以一张大图在预览里会被直接按显示尺寸采样，而不是先整张解进内存。
 */
actual fun decodeImageBytes(bytes: ByteArray): ImageBitmap? =
    runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
