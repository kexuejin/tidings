// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

/**
 * 「用哪张网发」的平台接缝（task-85 真身）。
 *
 * ## 要解决的问题
 *
 * Android 只把**默认网络**的路由装进主路由表。于是当默认网络是 WiFi、而那个 WiFi 的
 * IPv6 出不去（路由器没开 v6 / 开了但入站被挡 / 上游没 v6）时：
 *
 *  - 应用发往公网 v6 的包走 WiFi 的 `::/0` ⇒ 转发不出去 ⇒ **不可达**；
 *  - 同时手机可能还有**蜂窝的 v6** 可用，但它的路由**不在主表里** ⇒ 应用用不上它。
 *
 * 真机现场（红米）：`nc -6` 直连 Mac 的公网 v6 报 `Network is unreachable` —— 不是超时，
 * 是内核里没有到那个地址的路由。而把 WiFi 关掉（默认网络变成蜂窝）就通了。
 *
 * ## 怎么修
 *
 * 平台层提供一个 [resolve]，回答「要访问这个地址，应该把 socket 绑到哪张网」；
 * 传输层在**建 socket 之前**拿它一次，然后把 socket `bindSocket` 到那张网。
 * 这样即使默认网络到不了对端，我们也能显式走另一张。
 *
 * 默认实现返回 null（= 不绑，行为与以前一字不差）—— 桌面端与 iOS 走这条。
 */
interface NetworkBinder {
    /**
     * 为访问 [host] 选一张网。
     *
     * @return 平台句柄（Android 上是 `android.net.Network`）；null = 不用绑。
     *   实现方**不得**在这里做长时间的阻塞探测：它会被拨号路径调用，慢一点就是一次拨号超时。
     */
    fun networkFor(host: String): Any?

    /** 把已经建好的 socket 绑到 [handle]。返回是否绑成功（失败就当没绑，继续走默认网络）。 */
    fun bind(socket: Any, handle: Any): Boolean
}

/** 什么都不做的默认实现：所有平台在没装 binder 时的行为。 */
object NoNetworkBinder : NetworkBinder {
    override fun networkFor(host: String): Any? = null
    override fun bind(socket: Any, handle: Any): Boolean = false
}

/**
 * 装/取本机的网络选择器。
 *
 * 与 `NetworkWatch.installIdleProvider` 同一形状（core 提供会合点，平台在启动时装入）：
 * core 不认识 `ConnectivityManager`，也不该认识。
 */
object NetworkBinderHolder {
    @Volatile
    private var binder: NetworkBinder = NoNetworkBinder

    fun install(next: NetworkBinder) {
        binder = next
    }

    fun current(): NetworkBinder = binder
}
