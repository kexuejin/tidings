// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 捎话 Tidings contributors

package com.kapp.tidings.desktop

import java.awt.Window
import kotlinx.coroutines.delay

/**
 * 窗口抖动动画（收到 Nudge 戳一碰时），在约 300ms 内微调物理坐标后精准复位。
 */
internal suspend fun shakeWindow(window: Window) {
    val origLoc = try {
        window.location
    } catch (_: Throwable) {
        return
    }
    val offsets = listOf(
        -8 to 0,
        8 to 0,
        -6 to 0,
        6 to 0,
        -4 to 0,
        4 to 0,
        -2 to 0,
        2 to 0,
        0 to 0,
    )
    try {
        for ((dx, dy) in offsets) {
            window.setLocation(origLoc.x + dx, origLoc.y + dy)
            delay(30)
        }
    } finally {
        window.location = origLoc
    }
}
