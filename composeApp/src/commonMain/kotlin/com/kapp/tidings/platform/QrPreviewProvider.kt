// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 「这块设备画得出相机取景框」这个可空槽 —— 与 [AppPlatform] **分开**声明。
 *
 * ## 为什么它不能留在 AppPlatform 上
 *
 * 它返回 `(@Composable (Modifier) -> Unit)?`，一个 Compose 类型。只要它出现在
 * [AppPlatform] 里，整个 `platform` 包的契约文件就必须 `import androidx.compose`，
 * 于是「领域层零 Compose」这条不变量在最核心的契约上破功 —— 而搬成 `:app` 之后
 * 那是直接编译不过。
 *
 * 拆开的代价很小：这个槽**只有界面会读**（扫码页决定画不画取景框），领域层从不读它。
 * 所以让它成为一个独立接口，由平台实现类**额外**实现；界面侧做一次类型判断即可。
 *
 * ## 为什么不是又一条 `expect/actual`
 *
 * 预览必须绑到**那个具体的扫描器实例**上（Android 的检测跑在预览那条 `ImageAnalysis`
 * 里，iOS 的会话就是预览层的数据源），而实例只活在平台壳里；用 `expect` 顶层函数
 * 就得再引一份全局单例。
 *
 * ## 契约
 *
 * `null`（或**不实现本接口**）= 本平台画不出取景框（桌面就没有相机）。
 * 界面据此**不画**扫码入口 —— 「按下去没反应的按钮比没有更糟」。
 */
interface QrPreviewProvider {

    /** 取景框；`null` = 这台设备画不出来。 */
    val qrScannerPreview: (@Composable (Modifier) -> Unit)?
}
