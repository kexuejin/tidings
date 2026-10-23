// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop.capture

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class CaptureRect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

data class CaptureSize(
    val width: Float,
    val height: Float,
)

data class CaptureToolbarPosition(
    val left: Float,
    val top: Float,
)

data class ImageCrop(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val scaleX: Float,
    val scaleY: Float,
)

/**
 * 将窗口逻辑像素选区映射到截图实际像素；X/Y 独立换算以消除 Retina/高分屏 DPI 误差。
 * 对齐屏幕选区规范 capture-crop
 */
fun mapCaptureRectToImage(
    rect: CaptureRect,
    viewport: CaptureSize,
    image: CaptureSize,
): ImageCrop? {
    if (viewport.width <= 0f || viewport.height <= 0f || image.width <= 0f || image.height <= 0f || rect.width <= 0f || rect.height <= 0f) {
        return null
    }
    val scaleX = image.width / viewport.width
    val scaleY = image.height / viewport.height
    val left = max(0, min(image.width.toInt() - 1, (rect.x * scaleX).roundToInt()))
    val top = max(0, min(image.height.toInt() - 1, (rect.y * scaleY).roundToInt()))
    val right = max(left + 1, min(image.width.toInt(), ((rect.x + rect.width) * scaleX).roundToInt()))
    val bottom = max(top + 1, min(image.height.toInt(), ((rect.y + rect.height) * scaleY).roundToInt()))
    return ImageCrop(
        x = left,
        y = top,
        width = right - left,
        height = bottom - top,
        scaleX = scaleX,
        scaleY = scaleY,
    )
}

/**
 * 还没框选时工具条的位置：贴视口左下角、留 [margin] 边距。
 *
 * 原型截图窗的两个相位画的是**同一根 bar**，只是位置不同：
 * `screenDCapture(phase)` 里 `<div class="captbar ${phase==='select'?'below':'top'}">`
 * （prototype/relay.html:4604），而 `.captbar.below{position:absolute;bottom:8px;left:8px}`
 * （同文件 :1033）。⇒ 未框选（`select`）时工具条仍然要在，只是落在视口底部。
 */
fun placeCaptureToolbarAtBottom(
    toolbar: CaptureSize,
    viewport: CaptureSize,
    margin: Float = 8f,
): CaptureToolbarPosition {
    val maxTop = max(margin, viewport.height - toolbar.height - margin)
    return CaptureToolbarPosition(
        left = margin,
        top = (viewport.height - toolbar.height - margin).coerceIn(margin, maxTop),
    )
}

/**
 * 将截图工具条放进可用视口；优先选区下方，空间不足时翻到上方。
 * 对齐工具栏规范 capture-toolbar
 */
fun placeCaptureToolbar(
    selection: CaptureRect,
    toolbar: CaptureSize,
    viewport: CaptureSize,
    margin: Float = 8f,
    gap: Float = 8f,
): CaptureToolbarPosition {
    val maxLeft = max(margin, viewport.width - toolbar.width - margin)
    val preferredLeft = min(selection.x, selection.x + selection.width - toolbar.width)
    val left = preferredLeft.coerceIn(margin, maxLeft)

    val maxTop = max(margin, viewport.height - toolbar.height - margin)
    val below = selection.y + selection.height + gap
    val above = selection.y - gap - toolbar.height
    val top = if (below + toolbar.height <= viewport.height - margin) {
        below
    } else if (above >= margin) {
        above
    } else {
        below.coerceIn(margin, maxTop)
    }

    return CaptureToolbarPosition(left = left, top = top)
}
