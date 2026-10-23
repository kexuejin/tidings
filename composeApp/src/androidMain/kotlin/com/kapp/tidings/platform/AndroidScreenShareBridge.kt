// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.platform

import android.content.Intent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Compose 层与「投屏系统授权」之间的桥（MediaProjection 用户同意弹窗）。
 *
 * 与 [AndroidPermissionBridge] 同一个理由、同一套纪律：`createScreenCaptureIntent()`
 * 的确认对话框必须由 Activity 启动（`startActivityForResult` 语义），而采集服务
 * 构造在 `Application` 里。桥只干一件事 —— 把用户按下"立即开始"/"取消"的那一刻，
 * 原样带回给挂起的 [requestConsent]。
 *
 * **拿不到桥或宿主离开时返回 null（"没拿到授权"），绝不返回一份假的 Intent**：
 * 用未授权的 result 去 `getMediaProjection` 会直接抛异常，而"永远为真的返回值
 * 等于没有返回值"这个坑在通知权限那一版踩过（PermissionBridge 的文件头注释）。
 */
class AndroidScreenShareBridge {

    /** (resultCode, data Intent)；用户取消时 resultCode 不是 RESULT_OK。 */
    data class Consent(val resultCode: Int, val data: Intent?)

    private var launcher: (() -> Unit)? = null
    private var pending: CompletableDeferred<Consent?>? = null

    /** Compose/Activity 层注册进来的启动动作（内部自己 `createScreenCaptureIntent` 并发车）。 */
    fun attach(launch: () -> Unit) {
        launcher = launch
    }

    /** 宿主离开时断开：挂起的请求结束成 null（"没拿到"），而不是让它永远等着。 */
    fun detach() {
        launcher = null
        pending?.complete(null)
        pending = null
    }

    /** Activity 的 activity-result 回调把系统给的结果原样交进来。 */
    fun deliver(result: Consent?) {
        val deferred = pending ?: return
        pending = null
        deferred.complete(result)
    }

    /**
     * 弹出系统"开始录制或投放屏幕？"确认框并等待结果。
     *
     * 超时是兜底而不是主要路径：正常流程里 deliver/detach 总会先到。没有注册的启动器
     * 直接返回 null —— 用户没被问过就是没授权，这里没有第三种答案。
     */
    suspend fun requestConsent(timeoutMs: Long = 120_000L): Consent? {
        if (pending != null) return null
        val launch = launcher ?: return null
        val deferred = CompletableDeferred<Consent?>()
        pending = deferred
        launch()
        val consent = withTimeoutOrNull(timeoutMs) { deferred.await() }
        if (consent == null) pending = null
        return consent
    }
}
