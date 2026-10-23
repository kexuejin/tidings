// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kapp.tidings.domain.RelayController
import com.kapp.tidings.platform.QrPreviewProvider

/**
 * 相机取景框这个**可空槽**的界面侧出口。
 *
 * ## 为什么它不挂在 `RelayController` 上
 *
 * 它返回的是 `(@Composable (Modifier) -> Unit)?` —— 一个 Compose 类型。挂在控制器上，
 * 领域层就得 `import androidx.compose.runtime.Composable` 才能声明它（实测过：那条
 * import 让「领域层零 Compose」这条不变量破功，且按模块拆开后直接编译不过）。
 *
 * 而它的**判据**只有一句：「这台设备有没有能画出来的取景框」。这句话由平台回答
 * （`AppPlatform.qrScannerPreview` 的可空槽），控制器只是转发过一手 —— 转发没有
 * 带来任何领域信息，所以把这一手去掉，让界面直接问平台。
 *
 * ## 契约（与 `AppPlatform.qrScannerPreview` 逐字一致）
 *
 * `null` = 本平台画不出取景框（桌面就没有相机）。界面据此**不画**扫码入口 ——
 * 「按下去没反应的按钮比没有更糟」。
 *
 * 读的是 `controller.platform`（`internal`，本模块内可见）。按模块拆开之后这个
 * 扩展会跟着 `ui/` 走，而 `platform` 是 `:app` 的公开属性 —— 那时它照样能读到。
 */
internal val RelayController.qrScannerPreviewUi: (@Composable (Modifier) -> Unit)?
    get() = (platform as? QrPreviewProvider)?.qrScannerPreview
