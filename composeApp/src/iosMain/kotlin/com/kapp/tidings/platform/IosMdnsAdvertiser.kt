// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.platform

import kotlin.coroutines.resume
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSNetService
import platform.Foundation.NSNetServiceDelegateProtocol
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * iOS mDNS 广播器：用 Bonjour 的 `NSNetService` 发布 `_relay._udp`。
 *
 * 为什么不是 `NWListener`：Network.framework 的 `NWListener` 是 **Swift 类**，
 * 不进 C/ObjC 头文件，所以 Kotlin/Native 的 cinterop 根本看不见它（能看见的只有
 * `nw_listener_t` 那一套 C API）。`NSNetService` 是 ObjC 的，而且浏览那一半
 * （[IosMdnsBrowser]）本来就在用 `NSNetServiceBrowser` —— 收发两侧同一套 API。
 *
 * `publish()` 是异步的，成功/失败走 delegate，所以 [start] 会挂起等回调，超时按失败算。
 * 上一版这里写的是"不等回调，与 Android 侧保持一致"——那条理由只说明两边一致地**没有
 * 返回值**：`didNotPublish` 已经在实现里了，却只用来把 service 置空，调用方拿到的永远是
 * `true`。现在两端都等，契约才是同一件事。
 *
 * **必须在主队列上 publish（模拟器上查出来的）。** `NSNetService` 的注册是
 * 挂在一个 **run loop** 上的：`publish()` 会把服务调度到**当前线程**的 run loop，而
 * Kotlin/Native 的协程跑在 Darwin 全局队列的工作线程上 —— 那些线程**没有 run loop 在跑**，
 * 于是注册请求排进去就再也没人推，5 秒后超时、服务被撤下。症状很有欺骗性：UDP/TCP 都
 * 绑上了（`lsof` 看得见 `UDP *:17888`、`TCP *:17889 LISTEN`），节点也 `Running`，
 * 但 `dns-sd -B _relay._udp local.` **一条都搜不到本机** —— 别人永远发现不了我们。
 * 主队列上有 UIKit 的主 run loop 在转，所以创建 + `publish()` 一并丢过去。
 * `stop()` 同理：`NSNetService` 不是线程安全的，所有碰它的地方都在同一个队列上。
 */
class IosMdnsAdvertiser : MdnsAdvertiser {

    private var service: NSNetService? = null
    private var delegate: PublishDelegate? = null

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun start(serviceName: String, port: Int): Boolean {
        stop()
        return try {
            val outcome = withTimeoutOrNull(REGISTER_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val publishDelegate = PublishDelegate(
                        onPublished = { if (cont.isActive) cont.resume(true) },
                        onFailed = { if (cont.isActive) cont.resume(false) },
                    )
                    // 创建也放主队列：`NSNetService` 的 init 本身不挑线程，
                    // 但"创建它的线程"就是 `publish()` 认的那个 run loop 宿主，
                    // 两件事分在两个队列上会让这段代码只在主线程恰好是调用者时才对。
                    dispatch_async(dispatch_get_main_queue()) {
                        val netService = NSNetService(
                            domain = BONJOUR_DOMAIN,
                            type = SERVICE_TYPE,
                            name = serviceName,
                            port = port,
                        )
                        netService.delegate = publishDelegate
                        netService.publish()
                        service = netService
                        delegate = publishDelegate
                    }
                }
            }
            if (outcome == null) {
                // 超时等于没发布成功，把服务撤掉，别留一个"看着在广播"的对象。
                stop()
                false
            } else {
                outcome
            }
        } catch (e: Exception) {
            false
        }
    }

    override fun stop() {
        // 排队而不是就地做：`stop()` 可能在超时路径上被工作线程调到，
        // 而 `service` 的每一次赋值都发生在主队列里（见上面的 KDoc）。
        dispatch_async(dispatch_get_main_queue()) {
            service?.stop()
            service = null
            delegate = null
        }
    }

    private companion object {
        const val SERVICE_TYPE = "_relay._udp."
        const val BONJOUR_DOMAIN = "local."
        const val REGISTER_TIMEOUT_MS = 5_000L
    }
}

@OptIn(ExperimentalForeignApi::class)
private class PublishDelegate(
    private val onPublished: () -> Unit,
    private val onFailed: () -> Unit,
) : NSObject(), NSNetServiceDelegateProtocol {

    override fun netServiceDidPublish(sender: NSNetService) {
        onPublished()
    }

    override fun netService(sender: NSNetService, didNotPublish: Map<Any?, *>) {
        onFailed()
    }
}
