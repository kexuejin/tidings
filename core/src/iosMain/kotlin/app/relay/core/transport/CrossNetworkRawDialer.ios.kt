// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

import app.relay.core.crypto.TlsPin

/**
 * iOS：TLS 实现（task-26 ①）落地前**没有**原始字节流的 TLS 拨号能力。
 *
 * 刻意**不是**「明文凑合」：公网对端的内容只允许经 TLS（task-13），所以这里
 * `canDialTls = false` + `connect` 恒 null ⇒ 公网文件路径整条不可用（fail-closed），
 * 跨网文件在这台设备上退回局域网原路径。task-26 ① 落地后把这里换成真实实现即可。
 */
actual fun createCrossNetworkRawDialer(
    identityDir: () -> String,
    addresses: () -> List<String>,
): CrossNetworkRawDialer = UnavailableRawDialer

private object UnavailableRawDialer : CrossNetworkRawDialer {
    override val canDialTls: Boolean = false

    override suspend fun connect(
        host: String,
        port: Int,
        expectedPin: TlsPin?,
        timeoutMs: Long,
    ): RawPullConnection? = null
}
