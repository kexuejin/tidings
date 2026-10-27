// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import platform.UIKit.UIApplication
import platform.UIKit.UIUserInterfaceStyle

/**
 * iOS 实现：把**窗口**的外观跟随**应用内**主题（而不是系统设置）。
 *
 * ## 为什么必须在这里做
 *
 * 状态栏（及刘海区 / home indicator 区）的图标明暗由窗口的 trait collection 决定，
 * 而窗口默认跟随**系统**深浅色。本应用允许用户在设置里强制浅色/深色 ——
 * 于是"系统浅色 + 应用内深色"时状态栏变成深色图标压在深色背景上，看不见。
 * 与 Android 侧 `CareSystemBars.android.kt` 说的是同一句话：**图标明暗跟应用主题走**。
 *
 * 之前这里是一段 no-op，KDoc 声称"宿主 `iOSApp.swift` 会按应用主题设置
 * `overrideUserInterfaceStyle`" —— 实际上没有任何人设置（`iosApp/iOSApp.swift` 里
 * 一个 `UIUserInterfaceStyle` 都没有）。注释许诺了一件不存在的事，比缺陷更贵：
 * 下一个人读到它就以为有人管了。
 *
 * ## 为什么在 Compose 层而不是 Swift
 *
 * 主题的**唯一事实来源**是 Compose 里的设置（`RelayApp` 把 `ThemeMode` 解析成 dark）。
 * Swift 侧拿不到那个值；让 Swift 再推导一遍就是两个真相来源。所以驱动点放在这里：
 * `SideEffect` 每次提交后按当前 `darkTheme` 刷一次窗口外观（主题切换的那一帧就生效）。
 *
 * **已编译验证（2026-10-27）**：有 iOS 工具链后实测——原来直接 import `UIUserInterfaceStyleDark`
 * 若 `UIUserInterfaceStyleDark` / `UIUserInterfaceStyleLight` 在 Kotlin/Native 的导入形态
 * 与预期不同，编译会在这里失败 —— 那是唯一的风险点，逻辑本身只有"按布尔二选一"。
 */
@Composable
actual fun CareSystemBars(darkTheme: Boolean) {
    SideEffect {
        // keyWindow 在某些启动阶段还是 null（Compose 首帧可能早于窗口挂载）：拿不到就跳过，
        // 下一次重组/主题切换会再来一次。不要为此在启动路径上抛异常。
        val window = UIApplication.sharedApplication.keyWindow ?: return@SideEffect
        window.overrideUserInterfaceStyle =
            if (darkTheme) {
                UIUserInterfaceStyle.UIUserInterfaceStyleDark
            } else {
                UIUserInterfaceStyle.UIUserInterfaceStyleLight
            }
    }
}