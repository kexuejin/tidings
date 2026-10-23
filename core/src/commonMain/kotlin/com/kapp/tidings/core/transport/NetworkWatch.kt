// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import com.kapp.tidings.core.createCoreLock
import com.kapp.tidings.core.withLock
import kotlin.concurrent.Volatile

/**
 * 进程级「网络路径变了」信号，外加一句只读的「现在是不是省电窗口」（K3 ①③）。
 *
 * ## 为什么需要它（而不是直接调长连接）
 *
 * 网络变化有两个来源、两个消费方：
 *  · 来源：Android 的 ConnectivityManager（在 `composeApp` 平台层，它手里才有 Context）、
 *    iOS 的 NWPathMonitor（在本模块 `iosMain`）；
 *  · 消费方：长连接的发起侧（`LinkClient`，要**立刻**清退避重拨）。
 *
 * 平台层够不到 `LinkClient`（它活在 `NodeRuntime` 里），`NodeRuntime` 又够不到平台 API。
 * 所以中间立一个**进程级会合点**：平台报告变化，运行时订阅变化 —— 与 `ConnectionKeepAliveGate`
 * 同一套「两边生命周期对不上就立显式会合点」的规矩，不靠反射、不靠全局查找。
 *
 * ## 与 `composeApp` 的 `NetworkMonitor` 的分工
 *
 * `NetworkMonitor` 回答的是「本机现在有哪些地址、走什么传输」——它服务 UI 与 UDP 广播；
 * 这里回答的是「刚刚变过」这一个事件 —— 它服务长连接的重拨。两者不合并：前者的消费方
 * 是控制器（要刷新快照），后者的消费方是运行时（要清退避），生命周期与刷新频率都不同。
 */
object NetworkWatch {

    private val lock = createCoreLock()
    private val listeners = mutableListOf<() -> Unit>()

    /**
     * 订阅网络变化，返回取消函数。
     *
     * 取消函数是必须的：`NodeRuntime` 会随 `restart()` 反复 start/stop，
     * 不退订就会越订越多（每个死掉的运行时都还会被叫一次）。
     */
    fun onChanged(listener: () -> Unit): () -> Unit {
        lock.withLock { listeners += listener }
        return { lock.withLock { listeners -= listener } }
    }

    /**
     * 平台层报告一次网络变化。
     *
     * 逐个 `runCatching`：一个订阅者抛异常不许拖垮其它订阅者，也不许把异常带回平台的
     * 网络回调里（那里抛出去等于把系统回调打崩，症状是"换网之后再也没人通知我"）。
     */
    fun notifyChanged() {
        val snapshot = lock.withLock { listeners.toList() }
        for (listener in snapshot) runCatching { listener() }
    }

    /**
     * 平台装的「省电/空闲窗口」问句（Android：Doze 的 `isDeviceIdleMode`）。
     *
     * 默认 `null` ⇒ [isIdleNow] 恒 false ⇒ 不降频。桌面与 iOS 不装：桌面没有 Doze，
     * iOS 的后台本身就是挂起（假装有一个"降频窗口"只会让心跳在后台被我们主动拉长）。
     */
    @Volatile
    private var idleProvider: (() -> Boolean)? = null

    fun installIdleProvider(provider: (() -> Boolean)?) {
        idleProvider = provider
    }

    fun isIdleNow(): Boolean = idleProvider?.invoke() == true
}

/**
 * 启动**本平台**的网络路径监听（K3 ①）。返回取消函数。
 *
 * 各平台做到哪一步（不假装统一）：
 *  · **iOS**：`NWPathMonitor`（Network 框架的 C API），路径一变就 [NetworkWatch.notifyChanged]；
 *  · **JVM / Android**：空实现。Android 的真实信号由 `composeApp` 平台层那条**已有**的
 *    `registerDefaultNetworkCallback` 发出（它手里才有 Context，而本模块的 jvmShared 源集
 *    要同时给纯 JVM 编译，不能引用 android.* 的类）；桌面没有"被 cached 冻结"这回事。
 */
expect fun startPlatformNetworkWatch(): () -> Unit
