// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

/**
 * JVM/Android：直接用 [TlsLinkTransport.startRawServer]（同一套 TLS 服务端身份 + 双向固定）。
 *
 * 身份按 [identityDir] 落盘，所以这里再建一个实例拿到的是同一把私钥与同一张证书 ⇒ pin 不变。
 */
actual fun createCrossNetworkFileListener(
    identityDir: () -> String,
    addresses: () -> List<String>,
): CrossNetworkFileListener = TlsRawFileListener(TlsLinkTransport(identityDir, addresses))

private class TlsRawFileListener(private val tls: TlsLinkTransport) : CrossNetworkFileListener {

    override val canServe: Boolean = true

    override suspend fun start(
        port: Int,
        onConnection: suspend (RawPullConnection) -> Unit,
    ): Int? = tls.startRawServer(port) { connection, _, _ -> onConnection(connection) }

    override suspend fun stop() {
        tls.stopRawServer()
    }
}
