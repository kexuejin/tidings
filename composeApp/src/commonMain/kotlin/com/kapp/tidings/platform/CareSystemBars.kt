// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.platform

import androidx.compose.runtime.Composable

/**
 * 让状态栏 / 导航栏**图标的明暗**跟随**应用**主题，而不是跟随系统设置。
 *
 * 为什么要单独有这个函数：宿主侧只把窗口铺到系统栏后面（Android
 * `enableEdgeToEdge()`），而图标明暗是 `enableEdgeToEdge()` 按**系统**深浅色定的。
 * 这个应用允许手动强制浅色或深色 —— 用户在应用里选了深色时系统可能正好是浅色，
 * 于是出现"深色页 + 浅色图标"的半截，状态栏那一块看起来永远像没做完。
 *
 * 所以归属明确成两句：**窗口铺满由宿主负责，图标明暗由 Compose 主题负责**。
 *
 * 调用点只有一个 —— `App.kt` 里 `AppTheme` 内部，且必须在 `AppTheme` 之后调用，
 * 否则读到的是上一套主题的颜色。
 *
 * iOS 与 JVM 的实现是带说明的 no-op：iOS 的状态栏文字颜色由 trait collection 跟随应用的
 * `UIUserInterfaceStyle`，不需要在这里再按主题刷一次；JVM / 桌面没有系统栏。
 */
@Composable
expect fun CareSystemBars(darkTheme: Boolean)