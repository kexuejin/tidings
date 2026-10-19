// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

import app.relay.core.crypto.TlsPin

/**
 * JVM/Android：直接用 [TlsLinkTransport] 的同一套身份与 pin（task-39 分叉一(b)）。
 *
 * 身份是按 [identityDir] 落盘的（`LocalTlsIdentity.ensure`），所以这里再建一个实例拿到的是
 * **同一把私钥与同一张证书** ⇒ pin 不变，配对时存下的那条 pin 继续有效。
 */
actual fun createCrossNetworkRawDialer(
    identityDir: () -> String,
    addresses: () -> List<String>,
): CrossNetworkRawDialer = TlsRawDialer(TlsLinkTransport(identityDir, addresses))

private class TlsRawDialer(private val tls: TlsLinkTransport) : CrossNetworkRawDialer {

    override val canDialTls: Boolean = true

    override suspend fun connect(
        host: String,
        port: Int,
        expectedPin: TlsPin?,
        timeoutMs: Long,
    ): RawPullConnection? = tls.connectRaw(host, port, expectedPin, timeoutMs)
}
