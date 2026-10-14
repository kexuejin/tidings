// SPDX-License-Identifier: GPL-3.0-only

package app.relay.platform

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Android mDNS 服务浏览器：用 `NsdManager` 发现 `_relay._udp`。
 *
 * ## 这个类曾经把发现结果**全部丢掉**
 *
 * 上一版的 `onServiceFound` 直接读 `serviceInfo.host`，而 Android 的
 * `DiscoveryListener.onServiceFound` 递过来的 `NsdServiceInfo` **只有服务名**，
 * host/port 都还是 null —— 地址要再调一次 `NsdManager.resolveService()` 才会补齐。
 * 于是 `serviceInfo.host?.hostAddress ?: return` 每次都提前返回，
 * 日志里只有 `onServiceLost` 没有 `onServiceFound`：daemon 明明收到了 mDNS 响应
 * （`MdnsDiscoveryManager: Handling response from service: …`），应用却一个服务都没显示。
 *
 * 与"广告侧从不调用"合起来，自动发现在真机上**两个方向同时为零**，而单测和截图门禁
 * 都看不见（它们不碰网卡）。教训：`?: return` 这类静默兜底必须配一条日志，
 * 否则"没发现"和"发现了但被丢掉"在日志里长得一模一样。
 *
 * ## 为什么要排队解析
 *
 * `resolveService` 在同一个 `NsdManager` 上**同时只能有一个在飞**，并发调用会得到
 * `FAILURE_ALREADY_ACTIVE`。多个服务同时被播报是很常见的，所以这里把待解析的服务排队，
 * 一个解析完再放下一个；撞上 ALREADY_ACTIVE 就退回队列稍后重试。
 */
class AndroidMdnsBrowser(
    private val context: Context,
) : MdnsBrowser {

    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 待解析队列；`resolveService` 一次只能跑一个。 */
    private val pendingResolves = ConcurrentLinkedQueue<NsdServiceInfo>()

    /** 是否已经有解析在飞。用主线程 Handler 串行化，所以普通 Boolean 就够。 */
    private var resolveInFlight = false

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var onServiceFoundCallback: ((String, String, Int) -> Unit)? = null
    private var onServiceLostCallback: ((String) -> Unit)? = null

    override fun startBrowsing(
        onServiceFound: (String, String, Int) -> Unit,
        onServiceLost: (String) -> Unit,
    ) {
        stopBrowsing()

        onServiceFoundCallback = onServiceFound
        onServiceLostCallback = onServiceLost

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "mDNS discovery started: $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val name = serviceInfo.serviceName.orEmpty()
                // 先记一条：地址还没解析出来是**正常**的，不是异常。
                Log.d(TAG, "mDNS service found: $name（地址待解析，host=${serviceInfo.host}）")
                if (name.isEmpty()) return
                resolve(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val name = serviceInfo.serviceName.orEmpty()
                Log.d(TAG, "mDNS service lost: $name")
                if (name.isEmpty()) return
                // 丢掉队列里同一个服务的待解析项，避免解析一个已经消失的服务。
                pendingResolves.removeAll { it.serviceName == name }
                onServiceLostCallback?.invoke(name)
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "mDNS discovery stopped: $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "mDNS discovery start failed: $serviceType, error=$errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "mDNS discovery stop failed: $serviceType, error=$errorCode")
            }
        }

        discoveryListener = listener

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start mDNS discovery", e)
        }
    }

    override fun stopBrowsing() {
        pendingResolves.clear()
        resolveInFlight = false
        discoveryListener?.let { listener ->
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop mDNS discovery", e)
            }
        }
        discoveryListener = null
        onServiceFoundCallback = null
        onServiceLostCallback = null
    }

    private fun resolve(serviceInfo: NsdServiceInfo) {
        pendingResolves.add(serviceInfo)
        pumpResolves()
    }

    private fun pumpResolves() {
        if (resolveInFlight) return
        val next = pendingResolves.poll() ?: return
        if (discoveryListener == null) return // 已经停了，别再解析

        resolveInFlight = true
        val name = next.serviceName.orEmpty()
        try {
            nsdManager.resolveService(next, object : NsdManager.ResolveListener {
                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    resolveInFlight = false
                    val host = resolved.host?.hostAddress
                    if (host == null) {
                        Log.w(TAG, "mDNS resolve 成功了却没给地址: $name")
                    } else {
                        Log.d(TAG, "mDNS service resolved: $name at $host:${resolved.port}")
                        onServiceFoundCallback?.invoke(name, host, resolved.port)
                    }
                    pumpResolves()
                }

                override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) {
                    resolveInFlight = false
                    if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                        // 另一个解析还在飞：退回队列，稍后再来。这不是失败。
                        Log.d(TAG, "mDNS resolve 撞上并发，稍后重试: $name")
                        pendingResolves.add(failed)
                        mainHandler.postDelayed({ pumpResolves() }, RETRY_DELAY_MS)
                    } else {
                        Log.w(TAG, "mDNS resolve failed: $name, error=$errorCode")
                        pumpResolves()
                    }
                }
            })
        } catch (e: Exception) {
            resolveInFlight = false
            Log.e(TAG, "resolveService 抛异常: $name", e)
            pumpResolves()
        }
    }

    companion object {
        private const val TAG = "AndroidMdnsBrowser"

        /** 与 `AndroidMdnsAdvertiser.SERVICE_TYPE` 一致；`NsdManager` 不要尾随的点。 */
        private const val SERVICE_TYPE = "_relay._udp"

        private const val RETRY_DELAY_MS = 300L
    }
}
