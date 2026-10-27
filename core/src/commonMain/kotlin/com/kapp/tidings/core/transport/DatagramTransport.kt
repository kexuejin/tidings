// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

import kotlinx.coroutines.flow.SharedFlow

/**
 * 传输面的公共接口(UdpTransport 与 SecureChannelTransport 同形):
 * Messenger 只依赖它——阶段三(Messenger 走认证加密信道)的注入缝。
 */
interface DatagramTransport {
    /** 入站报文流。订阅必须先于 bind/连接,否则早期报文会丢。 */
    val incoming: SharedFlow<ReceivedDatagram>

    /** 实际绑定的 UDP/本地端口;不监听的实现(纯外拨)返回 -1。 */
    val boundPort: Int

    /** 启动传输(UdpTransport:bind + 泵)。幂等。 */
    suspend fun start()

    /** 停止传输并释放端口。幂等。 */
    fun stop()

    /** 自愈:socket 疑似失联时重建(UdpTransport:stop+start;纯外拨:加密信道自动重连,no-op)。 */
    suspend fun restart()

    suspend fun send(bytes: ByteArray, host: String, port: Int): Boolean

    /**
     * 向对端发「配对自证」(luciola 的 TLPAIR1)。默认 false = 这条传输没有这个概念
     * (legacy UDP);宿主据此不宣称"已配对"。
     */
    suspend fun requestPairing(host: String, port: Int, selfNodeId: String): Boolean = false
}
