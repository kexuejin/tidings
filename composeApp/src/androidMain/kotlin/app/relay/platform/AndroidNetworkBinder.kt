// SPDX-License-Identifier: GPL-3.0-only

package app.relay.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import app.relay.core.protocol.IpAddressUtils
import app.relay.core.transport.NetworkBinder
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Android 的 [NetworkBinder]：**默认网络到不了对端时，改用另一张网**（task-85 真身）。
 *
 * ## 为什么必须有
 *
 * Android 只把**默认网络**的路由装进主路由表。真机现场（红米）：
 *
 *     nc -6 -w 6 2001:db8:2020:cb44:4805:e245:f001:2 17879
 *       => nc: connect: Network is unreachable          <- 不是超时，是内核没有路由
 *
 * 当时 wifi_on=1、wlan0=192.168.20.252/24（连的是**另一个** WiFi），于是默认网络变成
 * 那个 WiFi，蜂窝的 v6 默认路由不在主表里，发往 Mac 公网 v6 的包**无路可走**。
 * 把 WiFi 关掉（默认网络变蜂窝）就通了。
 *
 * ## 做法（**保守**：只在默认网络确实到不了时才切）
 *
 * [networkFor] 不靠"这张网有 v6 能力"来判断 —— 那只说明能力，不说明能到这个前缀。
 * 这里做一次**短超时的真实探测**：先绑到那张网，再连目标端口。
 *
 * 边界：
 *  - 探测有超时上限（PROBE_TIMEOUT_MS），不会把拨号拖长到超时；
 *  - **只对公网地址**启用（局域网里默认网络本来就对，探测纯属浪费）；
 *  - 探测失败一律**回落**到"不绑"（宁可用默认网络再失败一次，也不因为探测出错改变既有行为）
 *    —— 这是 fail-safe 方向，与安全策略的 fail-closed 不是一回事。
 */
internal class AndroidNetworkBinder(
    private val context: Context,
    /** 探测用的端口（对端的 TLS 端口）；取不到就跳过探测。 */
    private val probePort: () -> Int = { 0 },
) : NetworkBinder {

    private val manager: ConnectivityManager?
        get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /**
     * **只在默认网络到不了公网对端时**才返回另一张网。
     *
     * 局域网地址（私网 / 同段 / ULA）一律返回 null：那种情况下默认网络就是对的。
     */
    /**
     * 本机当前地址（**必须传给 `isLanAddress`**）。
     *
     * 传空表会漏掉"同 /64 的 GUA 也算局域网"这条判据 —— 中国宽带最常见的就是
     * 家里 LAN 用公网 GUA-/64。漏了它，一个明明在同一网段的地址会被当成"公网"，
     * 于是多付一次探测；更糟的是探测失败会被判成"默认网络到不了"，白切一次网。
     */
    private fun localAddresses(): List<String> =
        runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { nic -> nic.inetAddresses.toList().map { it.hostAddress ?: "" } }
                .filter { it.isNotBlank() }
        }.getOrDefault(emptyList())

    override fun networkFor(host: String): Any? {
        val clean = IpAddressUtils.unbracket(host).trim().substringBefore('%')
        if (clean.isEmpty()) return null
        if (IpAddressUtils.isLanAddress(clean, localAddresses())) return null
        val port = probePort()
        if (port !in 1..65535) return null
        val cm = manager ?: return null
        val default = cm.activeNetwork ?: return null

        // 默认网络到得了 ⇒ 不需要绑（绝大多数情况，成本只有一次连接尝试）。
        if (reachable(default, clean, port)) return null

        // 默认网络到不了：在其余网络里找一张能到的。
        for (network in cm.allNetworks) {
            if (network == default) continue
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            if (reachable(network, clean, port)) return network
        }
        return null
    }

    /** 把 socket 绑到指定网络。绑不上就当没绑（调用方继续走默认网络）。 */
    override fun bind(socket: Any, handle: Any): Boolean {
        val target = handle as? Network ?: return false
        val java = socket as? Socket ?: return false
        return runCatching { target.bindSocket(java) }.isSuccess
    }

    /**
     * 用 [network] 试连一次 [host]:[port]（冒烟探测）。
     *
     * 先 bindSocket 再 connect：不然这次 connect 走的是默认网络，探的就不是那张网。
     * 任何异常都算"到不了"—— 这个函数只回答可达性，不区分原因。
     */
    private fun reachable(network: Network, host: String, port: Int): Boolean =
        runCatching {
            Socket().use { probe ->
                network.bindSocket(probe)
                probe.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS)
            }
            true
        }.getOrDefault(false)

    private companion object {
        /**
         * 探测超时。**必须远小于一次拨号超时**（connectTimeoutMs = 5s）：
         * 探测只是"选网"，选完还要真的拨一次，两次加起来不能超过用户能忍的那条线。
         */
        const val PROBE_TIMEOUT_MS = 600
    }
}