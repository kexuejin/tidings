// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSError
import platform.Foundation.NSLog
import platform.Foundation.NSUUID
import platform.darwin.NSObject
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationPresentationOptionBanner
import platform.UserNotifications.UNNotificationPresentationOptionList
import platform.UserNotifications.UNNotificationPresentationOptionSound
import platform.UserNotifications.UNNotificationPresentationOptions
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationResponse
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * iOS 推送通知处理器。
 *
 * 用 `UNUserNotificationCenter` 发本地通知。远程推送（APNs）需要服务端与
 * provisioning profile，这里不做 —— 接口留着，将来加 push 时不用改调用方。
 *
 * 三个写法上的坑：
 * - `title`/`body` 在 Kotlin/Native 里是**只读属性**（继承自 `UNNotificationContent`，
 *   可写的那份在子类里是 setter 方法），所以只能 `setTitle()`/`setBody()`，
 *   写成 `content.title = ...` 会报 "'val' cannot be reassigned"。
 * - 没有 `System.currentTimeMillis()` —— 那是 JVM 的。标识符用 `NSUUID`。
 * - 点通知的回调要一个 `NSObject` 子类实现 `UNUserNotificationCenterDelegateProtocol`，
 *   而且这个 delegate **必须存字段留住**：`UNUserNotificationCenter` 只持弱引用，
 *   不存会被回收，回调永远不来（在模拟器上补齐）。
 */
@OptIn(ExperimentalForeignApi::class)
class IosPushNotificationHandler : PushNotificationHandler {

    /**
     * 点通知的回调，由应用层通过 [setOnNotificationTap] 装上。
     * 这里只负责把 peer id 递出去，不做界面动作 —— 界面归 UI 层。
     */
    private var onNotificationTap: ((focusPeerId: String) -> Unit)? = null

    /**
     * 必须留住：`UNUserNotificationCenter.delegate` 是**弱引用**，
     * 只设不存的话这个对象立刻被回收，`didReceive` 永远不会被调用。
     */
    private val delegate = IosNotificationDelegate { peerId -> onNotificationTap?.invoke(peerId) }

    init {
        // 在 init 里挂上：处理器是随 App 启动构造的（见 IosPlatform），
        // 所以这里就是"启动时挂 delegate"的落点。
        UNUserNotificationCenter.currentNotificationCenter().delegate = delegate
    }

    override fun setOnNotificationTap(callback: ((focusPeerId: String) -> Unit)?) {
        onNotificationTap = callback
    }

    override fun showNotification(title: String, body: String, focusPeerId: String?) {
        val content = UNMutableNotificationContent()
        content.setTitle(title)
        content.setBody(body)
        if (focusPeerId != null) {
            // 把"这条通知说的是谁"写进 userInfo：点通知时 `didReceive` 从这里读回来，
            // 交给应用层变成一次定位请求（协议 §8）。
            content.setUserInfo(mapOf<Any?, Any?>(FOCUS_PEER_KEY to focusPeerId))
        }

        val request = UNNotificationRequest.requestWithIdentifier(
            identifier = "relay-${NSUUID().UUIDString}",
            content = content,
            trigger = null,
        )

        UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request) {
                error: NSError? ->
            // 通知没排上不是"消息丢了"：消息本身走 TCP/UDP 通道，通知只是提示。
            // 所以这里不打扰用户，也不重试；但**不静默** —— 排期失败留一条日志，
            // 否则"通知就是不出现"在真机上无从追查（接口 KDoc 的硬要求）。
            if (error != null) {
                NSLog("[relay-notif] addNotificationRequest error: " + error.localizedDescription)
            }
        }
    }

    override suspend fun requestPermission(): Boolean {
        return suspendCancellableCoroutine { cont ->
            UNUserNotificationCenter.currentNotificationCenter().requestAuthorizationWithOptions(
                options = UNAuthorizationOptionAlert or UNAuthorizationOptionBadge or
                    UNAuthorizationOptionSound,
            ) { granted: Boolean, _: NSError? ->
                // 只把系统给的答案递回去，不猜。拒绝时界面自己会给横幅，
                // 这里不再多打一条日志。
                cont.resume(granted)
            }
        }
    }
}

/**
 * `UNUserNotificationCenter` 的 delegate。
 *
 * 这两件事原先一件都没做，所以 iOS 上的通知实际上是"发出去但用户看不见"：
 * - **前台**没有 `willPresent` 时，iOS 会把前台投递的通知**静默丢掉** ——
 *   横幅没有、通知中心也没有，而 `addNotificationRequest` 依然以 nil error 完成，
 *   查起来像是"发出去了但没人收到"。这里明确要求显示。
 * - **点通知**没有 `didReceive` 时，`focusPeerId` 完全空转：点一下只把应用打开，
 *   不会定位到那个会话。
 */
@OptIn(ExperimentalForeignApi::class)
private class IosNotificationDelegate(
    private val onTap: (focusPeerId: String) -> Unit,
) : NSObject(), UNUserNotificationCenterDelegateProtocol {

    override fun userNotificationCenter(
        center: UNUserNotificationCenter,
        willPresentNotification: UNNotification,
        withCompletionHandler: (UNNotificationPresentationOptions) -> Unit,
    ) {
        withCompletionHandler(
            UNNotificationPresentationOptionBanner or
                UNNotificationPresentationOptionList or
                UNNotificationPresentationOptionSound,
        )
    }

    override fun userNotificationCenter(
        center: UNUserNotificationCenter,
        didReceiveNotificationResponse: UNNotificationResponse,
        withCompletionHandler: () -> Unit,
    ) {
        val userInfo = didReceiveNotificationResponse.notification.request.content.userInfo
        val peerId = userInfo[FOCUS_PEER_KEY] as? String
        if (peerId != null) {
            onTap(peerId)
        }
        withCompletionHandler()
    }

}

/**
 * `userInfo` 里存"这条通知说的是谁"的键，与 `showNotification` 写入的键必须一致。
 *
 * 放文件顶层而不是放进 [IosNotificationDelegate] 的伴生对象：Kotlin/Native 不允许
 * ObjC 子类（这里是 `NSObject`）的伴生对象里有字段，写了会报
 * `Fields are not supported for Companion of subclass of ObjC type`。
 */
private const val FOCUS_PEER_KEY = "focusPeerId"
