// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

/**
 * iOS：TLS 服务端（task-26 ①）落地前没有原始字节流的 TLS 监听能力。
 *
 * 刻意**不是**「明文凑合」：公网对端的内容只允许经 TLS（task-13），所以这里
 * `canServe = false` + `start` 恒 null ⇒ 主机侧不提供跨网文件入口（fail-closed）。
 */
actual fun createCrossNetworkFileListener(
    identityDir: () -> String,
    addresses: () -> List<String>,
): CrossNetworkFileListener = UnavailableFileListener

private object UnavailableFileListener : CrossNetworkFileListener {
    override val canServe: Boolean = false

    override suspend fun start(
        port: Int,
        onConnection: suspend (RawPullConnection) -> Unit,
    ): Int? = null

    override suspend fun stop() = Unit
}
