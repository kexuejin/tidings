// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

/**
 * iOS 的 K2 连接层：**暂只提供明文**（TLS 实现待落地，见 docs/spike-kmp-tls.md）。
 *
 * 这不是"忘了做"：返回明文实现 ⇒ [LinkTransport.isSecure] = false ⇒ 策略层只允许局域网，
 * 公网长连接被拒（fail-closed）。iOS 上不会出现"公网走明文"这条路。
 */
actual fun createPlatformLinkTransport(
    identityDir: () -> String,
    addresses: () -> List<String>,
): LinkTransport = PlaintextLinkTransport()
