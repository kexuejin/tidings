// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import com.kapp.tidings.core.crypto.TlsPin
import com.kapp.tidings.core.testing.createTempDir
import com.kapp.tidings.core.testing.removeRecursively
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * task-26 第 4 步的**拒绝启动**契约（可在测试二进制里验的那一半）。
 *
 * 拿不到 keychain identity（测试进程就是这种情况：`SecItemAdd` → -25291 errSecNotAvailable）时，
 * iOS 传输层必须**一切拒绝**：不拨号、不监听。宁可不提供 TLS，也绝不做两件事 ——
 * "没有客户端证书也去连"、"开一个不用客户端证书的监听口"。
 */
class IosTlsTransportRefusalTest {

    private val publicPin = TlsPin.parse("sha256:" + "a".repeat(64))

    @Test
    fun withoutAKeychainIdentityTheTransportRefusesEverything() = runBlocking {
        val dir = createTempDir("relay-ios-tls-refusal")
        try {
            val transport = IosTlsLinkTransport(identityDir = { dir }, addresses = { listOf("127.0.0.1") })
            assertTrue(transport.isSecure, "iOS 的 TLS 传输层必须声明 isSecure")

            val identity = IosLinkIdentity.ensure(dir, listOf("127.0.0.1"))
            if (identity.secIdentity == null) {
                // 测试进程（不是 app）走这一支：必须拒绝，而不是"降级成没有客户端证书"。
                assertNull(
                    transport.connect("127.0.0.1", 17880, publicPin, 1_000),
                    "没有 keychain identity 时 connect 必须返回 null",
                )
                assertFalse(
                    transport.startServer(0) { },
                    "没有 keychain identity 时 startServer 必须拒绝",
                )
            }
        } finally {
            removeRecursively(dir)
        }
    }

    @Test
    fun withoutAnExpectedPinTheTransportNeverDials() = runBlocking {
        val dir = createTempDir("relay-ios-tls-nopin")
        try {
            val transport = IosTlsLinkTransport(identityDir = { dir }, addresses = { listOf("127.0.0.1") })
            // 没有期望 pin ⇒ 不拨（"没验"不等于"验过"），这条与 keychain 是否可用无关。
            assertNull(transport.connect("127.0.0.1", 17880, null, 1_000), "没有 pin 绝不拨号")
        } finally {
            removeRecursively(dir)
        }
    }
}
