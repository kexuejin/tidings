// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.platform

import androidx.compose.runtime.Composable

/**
 * JVM / 桌面实现：没有系统栏这回事。
 *
 * 这个 target 存在是为了让预览与 JVM 测试能跑同一份 UI 代码（`jvmTest` 里跑的就是
 * `RelayApp`）。不是"以后再说"的占位 —— 桌面窗口本来就没有状态栏 / 导航栏，
 * `WindowInsets.safeDrawing` 在这里恒为 0，根层的 inset 边界自动退化成无操作。
 */
@Composable
actual fun CareSystemBars(darkTheme: Boolean) {
    // no-op：JVM 没有系统栏。
}