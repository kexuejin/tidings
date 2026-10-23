// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.platform

import androidx.compose.runtime.Composable

/**
 * iOS 实现：刻意什么都不做。
 *
 * iOS 的状态栏（及刘海区 / home indicator 区）文字颜色由 trait collection 跟随窗口的
 * `UIUserInterfaceStyle` 走，不是能逐条刷的开关 —— 宿主 `iOSApp.swift` 按应用主题设置
 * `overrideUserInterfaceStyle`，这一层就跟着对了。
 *
 * 在这里再按 `darkTheme` 刷一次不但没用，还会在**手动切主题**的半路上产生不一致：
 * `overrideUserInterfaceStyle` 的生效时机由 UIKit 决定，这里抢先刷出来的值可能正好是
 * 旧的。用户能看见的差异只有"切主题那一帧的状态栏"，不值得为它加一个真实现。
 */
@Composable
actual fun CareSystemBars(darkTheme: Boolean) {
    // no-op：理由见上方 KDoc。
}