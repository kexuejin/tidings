// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import platform.Foundation.NSNetService
import platform.Foundation.NSNetServiceBrowser
import platform.Foundation.NSNetServiceBrowserDelegateProtocol
import platform.Foundation.NSNetServiceDelegateProtocol
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * iOS mDNS 服务浏览器：用 `NSNetServiceBrowser` 发现 `_relay._udp`。
 *
 * 三个坑，都不是可选的：
 *
 * 1. **必须 `resolveWithTimeout`。** `netServiceBrowser:didFindService:` 送来的
 *    `NSNetService` 只有名字，`hostName` 还是 null、`port` 还是 0 —— 只有 resolve
 *    成功（`netServiceDidResolveAddress`）之后才拿得到。直接读 `hostName` 的实现
 *    会在真机上把每一个服务都当成无效丢掉，而单元测试永远发现不了。
 * 2. **`ObjCSignatureOverride`。** `didFindService` 与 `didRemoveService` 两个
 *    selector 在 Kotlin 里签名一模一样（`NSNetServiceBrowser, NSNetService, Boolean`），
 *    没有这个注解编译器会判成「Conflicting overloads」而不是两个 override。
 * 3. **必须在主队列上 start/stop（模拟器上查出来的）。** 与
 *    [IosMdnsAdvertiser] 同一个 run loop 问题：`searchForServicesOfType` 把浏览
 *    调度到**当前线程**的 run loop，而 Kotlin/Native 的协程跑在没有 run loop 的
 *    Darwin 工作线程上 —— 搜索请求排进去就没人推，一条结果也不会回来。所以对象
 *    的创建、start、stop 全部搬到主队列；delegate 回调也就在主 run loop 上到达，
 *    回调里的 `resolveWithTimeout` 自然挂对了 run loop。
 */
class IosMdnsBrowser : MdnsBrowser {

    private var browser: NSNetServiceBrowser? = null
    private var delegate: MdnsBrowserDelegate? = null

    @OptIn(ExperimentalForeignApi::class)
    override fun startBrowsing(
        onServiceFound: (String, String, Int) -> Unit,
        onServiceLost: (String) -> Unit,
    ) {
        stopBrowsing()

        val browserDelegate = MdnsBrowserDelegate(
            onServiceFound = onServiceFound,
            onServiceLost = onServiceLost,
        )

        dispatch_async(dispatch_get_main_queue()) {
            val nsBrowser = NSNetServiceBrowser()
            nsBrowser.delegate = browserDelegate
            nsBrowser.searchForServicesOfType(SERVICE_TYPE, inDomain = BONJOUR_DOMAIN)
            browser = nsBrowser
            delegate = browserDelegate
        }
    }

    override fun stopBrowsing() {
        dispatch_async(dispatch_get_main_queue()) {
            browser?.stop()
            browser = null
            delegate = null
        }
    }
}

private const val SERVICE_TYPE = "_relay._udp."
private const val BONJOUR_DOMAIN = "local."

/** resolve 的超时。过了就当发现失败，不回调 —— 半条记录比没有更糟。 */
private const val RESOLVE_TIMEOUT_SECONDS = 5.0

@OptIn(ExperimentalForeignApi::class)
private class MdnsBrowserDelegate(
    private val onServiceFound: (String, String, Int) -> Unit,
    private val onServiceLost: (String) -> Unit,
) : NSObject(), NSNetServiceBrowserDelegateProtocol, NSNetServiceDelegateProtocol {

    /** 正在 resolve 的服务。NSNetService 的 equals 是 NSObject 的身份比较，可以当 key。 */
    private val resolving = mutableSetOf<NSNetService>()

    @ObjCSignatureOverride
    override fun netServiceBrowser(
        browser: NSNetServiceBrowser,
        didFindService: NSNetService,
        moreComing: Boolean,
    ) {
        val service = didFindService
        service.delegate = this
        resolving.add(service)
        service.resolveWithTimeout(RESOLVE_TIMEOUT_SECONDS)
    }

    @ObjCSignatureOverride
    override fun netServiceBrowser(
        browser: NSNetServiceBrowser,
        didRemoveService: NSNetService,
        moreComing: Boolean,
    ) {
        resolving.remove(didRemoveService)
        // Kotlin/Native 把 `NSNetService.name` 映射成**非空** `String`（编译器因此把
        // `?: return` 报成「Elvis 恒左值」）。真空串意味着服务已经注销，丢掉即可。
        val name = didRemoveService.name
        if (name.isEmpty()) return
        onServiceLost(name)
    }

    override fun netServiceDidResolveAddress(sender: NSNetService) {
        resolving.remove(sender)
        val name = sender.name
        if (name.isEmpty()) return
        // resolve 过之后 hostName 才是 `xxx.local.`。仍然可能为 null（服务已下线），
        // 这种情况上层会通过 didRemoveService 收到通知。
        val host = sender.hostName ?: return
        onServiceFound(name, host, sender.port.toInt())
    }

    override fun netService(sender: NSNetService, didNotResolve: Map<Any?, *>) {
        // resolve 失败：不回调 onServiceFound。记录里没有一条半成品，
        // 上层下一轮浏览还会再看到这个服务。
        resolving.remove(sender)
    }
}
