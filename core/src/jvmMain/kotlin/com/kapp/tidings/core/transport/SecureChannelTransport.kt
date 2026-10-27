// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import uniffi.luciola_uniffi.SecureSession
import uniffi.luciola_uniffi.SecureSessionListener
import java.util.concurrent.ConcurrentHashMap

/**
 * 阶段三 C:UdpTransport 的加密信道替换(接口同形,Messenger 零改动)。
 *
 * 每个 (host, port) 映射一条 [SecureSession](Ed25519 认证 + AES-GCM);
 * 懒建连(首次 send 触发),自动重连(1s→60s ±20%)。
 * ACK/退避逻辑原样跑在信道上——对 Messenger 完全透明。
 *
 * 对端公钥由 [peerKey] 提供(配对过的才能连:deny-by-default)。
 */
class SecureChannelTransport(
    private val identitySeed: ByteArray,
    /** (host, port) → pinned Ed25519 public key;null = 未配对,拒绝。 */
    private val peerKey: (String, Int) -> ByteArray?,
) {
    private val channels = ConcurrentHashMap<String, SecureSession>()

    private val _incoming = MutableSharedFlow<ReceivedDatagram>(extraBufferCapacity = 256)
    val incoming: SharedFlow<ReceivedDatagram> = _incoming

    private fun channelFor(host: String, port: Int): SecureSession {
        val key = "$host:$port"
        return channels.computeIfAbsent(key) {
            val pinned = peerKey(host, port)
                ?: throw IllegalStateException("unpaired peer $key — deny by default")
            val session = SecureSession(identitySeed)
            session.connect(
                listOf(host), port.toUShort(), pinned, /*autoReconnect=*/true,
                object : SecureSessionListener {
                    override fun onState(state: String, detail: String?) {
                        if (state == "error" || state == "closed") channels.remove(key)
                    }
                    override fun onMessage(message: ByteArray) {
                        _incoming.tryEmit(ReceivedDatagram(message, host, port))
                    }
                },
            )
            session
        }
    }

    suspend fun send(bytes: ByteArray, host: String, port: Int): Boolean {
        return try {
            channelFor(host, port).send(bytes)
        } catch (_: Exception) {
            false // 未配对/队列满 → 与 UDP 发送失败语义一致
        }
    }

    fun stop() {
        channels.values.forEach { it.disconnect() }
        channels.clear()
    }
}
