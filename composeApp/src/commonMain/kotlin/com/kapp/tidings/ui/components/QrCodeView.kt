// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.kapp.tidings.core.qr.QrEncoder

/**
 * 把 [QrEncoder] 生成的矩阵画出来。
 *
 * 两条刻意的决定：
 *
 * 1. **颜色不用主题色，永远白底黑码。** 深色主题下跟着主题画"深底浅码"是常见做法，
 *    但读码器认的是对比度与极性，不是好看：浅色码在不少系统相机上根本扫不出来。
 *    配对码是拿来扫的，不是拿来看的。
 * 2. **静区由本组件负责画。** 标准要求码四周留 4 个模块宽的浅色边，缺了它贴边的
 *    定位图案会被读码器的边缘检测吃掉。调用方不必自己垫 padding —— 垫少了就扫不出来，
 *    而这种失败现场没人 debug 得出来。
 *
 * [contentDescription] 是给读屏软件的一句话（画面本身没有文字）；屏幕上的可读文本
 * （载荷原文）由调用方负责写，读屏用户走的是那条路。
 */
@Composable
fun QrCodeView(
    code: QrEncoder.QrCode,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier.semantics { this.contentDescription = contentDescription },
    ) {
        val cells = code.size + QUIET_ZONE_CELLS * 2
        val cell = size.minDimension / cells
        val drawn = cell * cells
        val originX = (size.width - drawn) / 2f
        val originY = (size.height - drawn) / 2f

        // 静区 + 浅色模块一起画成一张白底，省掉逐格画浅色。
        drawRect(
            color = Color.White,
            topLeft = Offset(originX, originY),
            size = Size(drawn, drawn),
        )
        // 深色模块：每格多画半个像素，避免浮点取整在格与格之间留下发丝缝
        // —— 那点缝在缩略图里看不出来，在相机里就是断掉的定位图案。
        for (y in 0 until code.size) {
            for (x in 0 until code.size) {
                if (!code[x, y]) continue
                drawRect(
                    color = Color.Black,
                    topLeft = Offset(
                        originX + (x + QUIET_ZONE_CELLS) * cell,
                        originY + (y + QUIET_ZONE_CELLS) * cell,
                    ),
                    size = Size(cell + 0.5f, cell + 0.5f),
                )
            }
        }
    }
}

/** 标准要求的静区宽度：4 个模块。 */
private const val QUIET_ZONE_CELLS = 4
