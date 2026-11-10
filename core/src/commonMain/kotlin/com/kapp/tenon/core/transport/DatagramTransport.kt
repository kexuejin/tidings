// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tenon.core.transport

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
     * 这个端点此刻**具备发出去的前提**吗（已配对或有会话）。默认 true：legacy UDP
     * 与测试回环对地址没有准入限制，任何地址都算一个可尝试的目标。
     *
     * 存在的理由：心跳循环用「有目标却一个包都没交出去」判传输泵停摆并重建传输面。
     * 安全信道对**未配对**端点是 deny-by-default，发不出去是准入事实、不是泵坏了；
     * 把这些历史地址算进 targets，会让一台只剩离线旧身份的节点被判成停摆、反复
     * restart（每重启一次会话全断）。所以判停摆只数**可送达**的目标。
     */
    fun canReach(host: String, port: Int): Boolean = true

    /**
     * 向对端发「配对自证」(luciola 的 TLPAIR1)。默认 false = 这条传输没有这个概念
     * (legacy UDP);宿主据此不宣称"已配对"。
     */
    suspend fun requestPairing(host: String, port: Int, selfNodeId: String): Boolean = false
}

/**
 * 一条入站 UDP 报文。
 *
 * [bytes] 是载荷本身（不含任何分帧）；[host]/[port] 是发送方地址 —— 茶话间
 * 用「回包发回 rinfo 的地址」而不是注册表里的地址，所以这两个字段必须原样带上来。
 */
data class ReceivedDatagram(
    val bytes: ByteArray,
    val host: String,
    val port: Int,
) {
    // ByteArray 的 equals 是引用比较，data class 生成的版本会骗人，这里显式写清楚。
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReceivedDatagram) return false
        return host == other.host && port == other.port && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = (host.hashCode() * 31 + port) * 31 + bytes.contentHashCode()

    override fun toString(): String = "ReceivedDatagram($host:$port, ${bytes.size}B)"
}
