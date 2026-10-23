// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Android 相机权限门：**检查 + 申请**。
 *
 * 上一版只 `checkSelfPermission` 就返回，注释写着"申请由 UI 层负责" —— 而 UI 层从来没接过，
 * 于是这个函数在真机上永远返回 false，扫码页永远走"权限被拒"分支，用户连一次系统弹窗都
 * 见不到。**只检查不申请**等于把"还没问过"和"被拒绝了"当成同一件事。
 *
 * 申请走 [AndroidPermissionBridge]（与 `POST_NOTIFICATIONS` 同一根桥）：它把
 * `ActivityResultLauncher` 注册进来的动作原样执行，并把系统给出的答案带回来；
 * 桥没接上（宿主没起来 / 已销毁）时返回 false —— 不知道就说不知道。
 */
class AndroidCameraGate(
    private val context: Context,
    private val bridge: AndroidPermissionBridge,
) : CameraGate {

    override suspend fun ensureAccess(): Boolean {
        if (granted()) return true
        // 已经明确拒绝过的用户再点一次扫码时，系统会立刻回 false 而不弹窗（除非系统策略允许
        // 再问一次）。这里照样问 —— 代价是一次空转，比"因为猜错了状态而永远不给用户机会"好。
        val answer = bridge.launch(Manifest.permission.CAMERA)
        // 系统说给了、并且真的给了，才算给：两个来源不一致时以 `checkSelfPermission` 为准。
        return answer && granted()
    }

    private fun granted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
}
