// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

/**
 * JVM / Android 的 [startPlatformNetworkWatch] actual：**空实现**，这是刻意的。
 *
 * 两个理由，别把它当"没写完"：
 *  1. 这个源集（`jvmSharedMain`）要同时给纯 JVM 与 Android 编译，所以**不能**引用
 *     `android.net.ConnectivityManager` —— 纯 JVM 那一遍没有 android.jar。
 *  2. Android 的真实信号不需要在这里再造一个：`composeApp` 平台层已经用
 *     `registerDefaultNetworkCallback` 订阅了默认网络变化（那是 K3 之前就有的代码），
 *     它只需在回调里多喊一声 [NetworkWatch.notifyChanged]；Context 在那边是现成的。
 *
 * 桌面（纯 JVM）没有"退到后台被系统打成 cached 并收紧网络"这回事，
 * 长连接不需要靠网络变化来抢救。
 */
actual fun startPlatformNetworkWatch(): () -> Unit = { }
