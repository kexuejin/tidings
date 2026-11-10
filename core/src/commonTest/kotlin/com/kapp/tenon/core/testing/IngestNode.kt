// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tenon.core.testing

import com.kapp.tenon.core.protocol.Envelope
import com.kapp.tenon.core.protocol.Profile
import com.kapp.tenon.core.protocol.Timings
import com.kapp.tenon.core.transport.DatagramTransport
import com.kapp.tenon.core.transport.PeerRegistry
import com.kapp.tenon.core.transport.PresenceIngest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * [PresenceIngest] 的**测试夹具**：把入站发现帧的登记器接上真实传输的入站流。
 *
 * ## 它替代了谁
 *
 * legacy `Discovery` 删除后，`entry` / `alive` / `presence` / `profile` / `exit` 的
 * 入站登记（touch / 回 alive / 学地址 / 学 pin / 丢 exit / 递 gossip）全部由
 * [PresenceIngest] 承担。原来那批 `Discovery*Test` 验的正是这些**协议事实** ——
 * 它们不该跟着广播机制一起删，换上这个夹具后逐条原样保留。
 *
 * 与 `Discovery` 的差别只有一条：不播报、不 sweep（前者是 legacy 的本职、已删；
 * 后者归 [com.kapp.tenon.core.session.SweepOnlyDiscoveryEngine]）。
 */
internal class IngestNode(
    private val transport: DatagramTransport,
    val registry: PeerRegistry,
    private val selfProfile: () -> Profile,
    private val timings: Timings = Timings(),
    /** 档案里带合法 `tlsPin` 时回调（配对固定入口）。 */
    private val onPeerPin: (nodeId: String, pin: String) -> Unit = { _, _ -> },
    /** `scan-ranges` 这类 gossip 帧的出口（原样递给应用层）。 */
    private val onGossip: (suspend (Envelope) -> Unit)? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    fun start() {
        val ingest = PresenceIngest(
            transport = transport,
            registry = registry,
            scope = scope,
            timings = timings,
            selfProfile = selfProfile,
            onPeerPin = onPeerPin,
            onGossip = onGossip ?: { _ -> },
        )
        job = scope.launch {
            transport.incoming.collect { datagram ->
                runCatching { ingest.onDatagram(datagram) }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        scope.cancel()
    }
}
