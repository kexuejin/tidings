// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.platform

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Android 走系统的 `BitmapFactory`。
 *
 * 与桌面/iOS 的 Skia 不同，这里是**立即解码**的：`decodeByteArray` 会把整张图展开成像素。
 * 所以必须先只读尺寸、再按需降采样 —— 一张 8000×8000 的 PNG 原样解开是几百 MB，
 * 而气泡里只需要一个缩略图，不降采样就是替对端把 App 撑爆。
 */
actual fun decodeImageBytes(bytes: ByteArray): ImageBitmap? {
    val bounds = runCatching {
        BitmapFactory.Options().apply { inJustDecodeBounds = true }.also {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it)
        }
    }.getOrNull() ?: return null
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
    }
    return runCatching {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }.getOrNull()
}

/**
 * 让最长边降到 [MAX_DECODED_SIDE] 以下所需的最小 2 的幂（`BitmapFactory` 只认 2 的幂）。
 * 返回 1 = 原尺寸。
 */
private fun sampleSizeFor(width: Int, height: Int): Int {
    var sample = 1
    var longest = maxOf(width, height)
    while (longest / 2 >= MAX_DECODED_SIDE) {
        longest /= 2
        sample *= 2
    }
    return sample
}

/** 解码目标上限（最长边像素）。预览最大也就画到 ~240dp，1024 已经远超任何屏幕。 */
private const val MAX_DECODED_SIDE = 1024
