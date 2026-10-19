// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

/**
 * F1：跨网文件的**主机侧原始 TLS 监听**（task-39 分叉二(b)）。
 *
 * 与 [CrossNetworkRawDialer] 成对：一个拨、一个听，共用同一套 TLS 身份与双向 pin。
 * 端口按裁决取 `对端 profile 的 tlsPort + 1`（[crossNetworkFilePort]）。
 *
 * ## fail-closed
 *
 * iOS 的 TLS 服务端（task-26 ①）落地前，工厂返回的实例 `canServe = false`、`start` 恒 null
 * ⇒ 主机在这台设备上不提供跨网文件入口，手机只能走局域网原路径，**不会降级明文**。
 * 端口被占同样返回 null（不重试别的端口：宁可这条路不可用，也不让对端猜一个没人听的端口）。
 */
interface CrossNetworkFileListener {

    /** 本平台能不能起这条监听。false ⇒ 主机侧不提供跨网文件入口。 */
    val canServe: Boolean

    /**
     * 在 [port] 上开始接受原始字节流连接；每条已握手的连接交给 [onConnection]。
     *
     * @return 实际绑上的端口；**null = 起不来**（平台不支持 / 端口被占）⇒ 调用方按
     *         「这条路径不可用」处理，退回局域网原路径。
     */
    suspend fun start(port: Int, onConnection: suspend (RawPullConnection) -> Unit): Int?

    /** 停掉监听（幂等）。 */
    suspend fun stop()
}

/**
 * 平台工厂。JVM/Android 复用 [TlsLinkTransport] 的同一套身份与 pin；
 * iOS 在 task-26 ① 落地前返回不可用实现。
 */
expect fun createCrossNetworkFileListener(
    identityDir: () -> String,
    addresses: () -> List<String>,
): CrossNetworkFileListener
