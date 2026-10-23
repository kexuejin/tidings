// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import kotlin.coroutines.resume

/**
 * iOS 相机权限门。
 *
 * `AVAuthorizationStatus` 是四个值，不是两个：`notDetermined`(0) 才该去弹框，
 * `restricted`(1，家长控制之类) 与 `denied`(2) 都是**已经拒绝**，再问一次不会弹框 ——
 * 上一版把这两个也当成"还没问过"丢给 `requestAccessForMediaType`，语义是错的，
 * 而且真机上若某个状态不回调，这里就永久挂起（界面停在"正在启动相机…"）。
 */
class IosCameraGate : CameraGate {

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun ensureAccess(): Boolean {
        return when (AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)) {
            AVAuthorizationStatusAuthorized -> true
            AVAuthorizationStatusNotDetermined -> requestCameraPermission()
            // restricted / denied：系统只会立刻回 false（有些版本连回调都不给），
            // 直接按拒绝处理，让界面去说"请在系统设置里开启"。
            else -> false
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private suspend fun requestCameraPermission(): Boolean {
        return suspendCancellableCoroutine { cont ->
            AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
                cont.resume(granted)
            }
        }
    }
}
