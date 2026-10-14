// SPDX-License-Identifier: GPL-3.0-only

package app.relay.platform

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Compose 层与「运行时权限」之间的桥（目前接的是 `POST_NOTIFICATIONS` 与 `CAMERA`）。
 *
 * 与 [AndroidPickerBridge] 同一个理由：`ActivityResultLauncher` 只能在 Activity/Compose
 * 里注册，而 `AppPlatform` 构造在 `Application` 里 —— 两者生命周期对不上。
 *
 * **为什么要专门给它建一根桥**：这个能力上一版是
 * `override suspend fun requestPermission(): Boolean = true` —— 一个**永远为真**的返回值。
 * 而 Android 13+（`targetSdk` 36）的 `POST_NOTIFICATIONS` 是运行时权限、**默认拒绝**，
 * 于是通知从来没有出现过，代码却一路认为"权限已给"，`showNotification` 里那句
 * `catch (e: SecurityException)` 把唯一能说话的异常也咽掉了。
 *
 * 它接的是一个权限字符串，因此不限于通知 —— 相机权限走同一根桥，见 `AndroidCameraGate`。
 *
 * 同样的坑在 mDNS 广告侧踩过一次：**一个永远为真的返回值
 * 等于没有返回值**。所以这里不再猜，只做一件事 —— 把系统弹窗的结果原样带回来。
 *
 * 拿不到桥（宿主还没起来 / 已经销毁）时返回 `false`，而不是 `true`：不知道就说不知道。
 */
class AndroidPermissionBridge {

    /** 当前注册的启动器；未注册时 [launch] 直接返回 false。 */
    private var launcher: ((String) -> Unit)? = null

    /**
     * 当前挂起的那一次请求。
     *
     * 同一时刻只允许一次：系统权限弹窗是前台独占的，第二次会把第一次的回调吞掉，
     * 那个挂起的调用方就只能干等到超时，所以第二次直接回 false。
     */
    private var pending: CompletableDeferred<Boolean>? = null

    /**
     * Compose 层注册进来的启动器。
     *
     * 交进来的是一个"发一次权限请求"的动作，而不是 `ActivityResultLauncher` 本身：
     * 平台层因此不必认识 activity-result 的类型、也就不会攥着一个 Activity 引用不放。
     */
    fun attach(launch: (String) -> Unit) {
        launcher = launch
    }

    /**
     * 宿主离开时断开。
     *
     * 必须**主动结束**挂起的那次请求：launcher 已随 Activity 销毁，回调永远不会来。
     * 这里结束成 `false`（"没拿到"），不是 `true`。
     */
    fun detach() {
        launcher = null
        pending?.complete(false)
        pending = null
    }

    /** Compose 层在 `ActivityResultCallback` 里调用，把系统的答案交回挂起的请求。 */
    fun deliver(granted: Boolean) {
        // `complete` 在已完成时返回 false，重复投递天然幂等：系统可能在重建后补投一次。
        pending?.complete(granted)
    }

    /** 挂起等待用户作答；没有桥或超时都返回 false。 */
    suspend fun launch(permission: String, timeoutMs: Long = 2 * 60_000L): Boolean {
        val start = launcher ?: return false
        if (pending != null) return false

        val deferred = CompletableDeferred<Boolean>()
        pending = deferred
        return try {
            start(permission)
            // 超时与"用户没答"都落到 false —— 对调用方而言两者要做的事完全一样。
            withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
        } finally {
            // 只有自己那一次才清，避免把后来者的 deferred 抹掉。
            if (pending === deferred) pending = null
        }
    }
}
