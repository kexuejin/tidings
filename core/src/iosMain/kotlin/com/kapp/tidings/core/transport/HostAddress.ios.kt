// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.SocketAddress

/**
 * iOS 实现：ktor 在 Kotlin/Native 上不提供原始字节（没有 JVM 的 `resolveAddress()`），
 * 但它把 `sockaddr` 转成主机名时本来就是数值形式，所以直接用 `hostname`。
 *
 * 真机验证（`/v1/send {"to":"<IP>"}` 能否解析）排在 P3 iOS 阶段；这里只保证不崩。
 */
actual fun literalHost(address: SocketAddress): String =
    (address as? InetSocketAddress)?.hostname ?: address.toString()
