// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.SocketAddress

/**
 * JVM 与 Android **共用**的一份 actual：优先用 ktor 暴露的 `resolveAddress()`
 * 原始字节还原成数值 IP。
 *
 * 不能直接用 `hostname`（那就是会反解的 `getHostName()`），也不能拿 ktor 内部的
 * `java.net.InetSocketAddress`（`address$ktor_network` 是 internal，跨模块取不到）。
 *
 * Android 在这里适用同一套代码，因为 ktor-network 在 Android 上解析到的
 * 就是 jvm 变体（实测两份实现逐行相同，只差上面那句注释里的平台名）。
 * iOS 那边**不适用**：`HostAddress.ios.kt` 走 NSHost/NSBonjour 那条路，语义不同。
 */
actual fun literalHost(address: SocketAddress): String {
    val inet = address as? InetSocketAddress ?: return address.toString()
    val numeric = runCatching { inet.resolveAddress()?.let { ipBytesToString(it) } }.getOrNull()
    return numeric ?: inet.hostname
}
