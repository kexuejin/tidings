// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.darwin.dispatch_get_main_queue

/**
 * iOS 的 [startPlatformNetworkWatch] actual：`NWPathMonitor`（K3 ①）。
 *
 * 用 Network 框架的 **C API**：`platform.Network` 这个 klib 只导出 `nw_path_monitor_*`
 * 系列函数，ObjC 的 `NWPathMonitor` 类不在里面（与 `composeApp` 里那个同名的监听同一套符号）。
 * 回调挂在**主队列**上：`NetworkWatch.notifyChanged` 会一路走到长连接的锁与 registry，
 * 放别的队列上跑是在没有同步保证的情况下碰这些结构。
 *
 * **不装 idle provider**：iOS 没有 Doze 这个名字，后台执行本身就是被系统挂起/节流；
 * 在这里假装有一个"省电窗口"只会让我们在后台**主动**把心跳拉长，把本来还能用的一小段
 * 后台时间也弄丢。默认 false（不降频）才是诚实的。
 */
actual fun startPlatformNetworkWatch(): () -> Unit {
    val monitor = nw_path_monitor_create()
    nw_path_monitor_set_update_handler(monitor) { _ -> NetworkWatch.notifyChanged() }
    nw_path_monitor_set_queue(monitor, dispatch_get_main_queue())
    nw_path_monitor_start(monitor)
    return { nw_path_monitor_cancel(monitor) }
}
