// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.platform

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Android 实现：用 `WindowInsetsControllerCompat` 把图标明暗刷成应用主题的那一套。
 *
 * 为什么是 [SideEffect] 而不是 `LaunchedEffect` / `remember`：这两个都**跳过首次组合**，
 * 于是首帧仍是系统主题给的图标明暗，用户能看到一次"主题切了但状态栏没跟上"的闪动。
 * `SideEffect` 每次提交后都跑，首帧也跑。
 *
 * 为什么不在 [MainActivity.onCreate] 里一次写死：`enableEdgeToEdge()` 的图标明暗是按
 * **系统**深浅色定的，而这个应用的 `darkTheme` 是可以被手动改的（见 `AppTheme` 的参数），
 * 一次写死就只对了首屏。主题一变这里就再刷一次，两边一致。
 *
 * `isAppearanceLightNavigationBars` 在 API < 26 上是 no-op —— `WindowCompat` 内部自己降级，
 * 这里不用判版本号。
 */
@Composable
actual fun CareSystemBars(darkTheme: Boolean) {
    val view = LocalView.current
    // 预览 / 布局检查器里没有真的窗口，拿到 null 就什么都不做。
    if (view.isInEditMode) return

    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            // 深色主题 = 浅色图标。命名反着看很容易写错，这里就是这一行。
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }
}

/**
 * `LocalView.context` 未必直接就是 Activity：Compose 可能包一层 `ContextWrapper`
 * （Application、或者别的壳）。逐层拆到 Activity 为止，拆不到就返回 null。
 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}