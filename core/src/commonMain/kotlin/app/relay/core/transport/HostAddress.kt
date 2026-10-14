// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

import io.ktor.network.sockets.SocketAddress

/**
 * 取出**字面量**地址（数值 IP），而不是可能被反解出来的主机名。
 *
 * 为什么非要有这一层：ktor 在 JVM 上把来源地址暴露为 `InetSocketAddress.hostname`，
 * 而它背后是 `java.net.InetSocketAddress.getHostName()` —— 会做反向解析，于是
 * `127.0.0.1` 变成 `"localhost"`、局域网 IP 变成 `"MacBook-Pro.local"`。
 *
 * 后果很具体：节点表里存的是 `"localhost"`，而调用方 POST 的是 `{"to":"127.0.0.1"}`，
 * 字符串永远匹配不上 —— 握手明明成功（对端已回 `alive`），`/v1/send` 却回 404。
 * 协议与产品语义说的都是 IP，所以必须在进节点表之前还原成数值形式。
 *
 * 拿不到数值形式时退回主机名/字符串，**绝不抛错** —— 地址不可解析不该让收包路径崩。
 */
expect fun literalHost(address: SocketAddress): String

/**
 * 4/16 字节地址转字面量。JVM 与 Android 的 `actual` 共用；iOS 走不到这里。
 *
 * @return 长度不是 4 或 16 时返回 null（调用方退回主机名）。
 */
internal fun ipBytesToString(bytes: ByteArray): String? = when (bytes.size) {
    4 -> bytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
    16 -> bytes.toList().chunked(2).joinToString(":") { pair ->
        pair.joinToString("") { hex2(it) }
    }
    else -> null
}

private const val HEX_DIGITS = "0123456789abcdef"

private fun hex2(byte: Byte): String {
    val v = byte.toInt() and 0xFF
    return "${HEX_DIGITS[v ushr 4]}${HEX_DIGITS[v and 0x0F]}"
}
