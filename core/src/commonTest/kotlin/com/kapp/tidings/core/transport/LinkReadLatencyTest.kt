// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import com.kapp.tidings.core.crypto.TlsPin
import com.kapp.tidings.core.nowMillis
import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.Profile
import io.ktor.network.sockets.Socket
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.kapp.tidings.core.testutil.awaitTrue
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * task-35：**拨号卡住时，读侧（节点表 / 长连接快照）必须仍然百毫秒级**。
 *
 * 真机现场：手机切到移动网后，本机 127.0.0.1:8788 连续 5 次 HTTP 000、每次卡满 8.00s，
 * 切回 WiFi 立刻恢复 —— 回环控制面被对端的换网拨号拖死，连"读 /v1/peers 看状态"这条
 * 诊断手段都被挡住。
 *
 * 这条测试把"拨号"与"读侧"放在同一个进程里：传输替身模拟**黑洞地址**（connect 一直挂着），
 * 然后在拨号进行中反复读节点表与长连接快照。任何一次超过 [MAX_READ_MS] 就是锁被拨号
 * 长时间持有 —— 那是这条缺陷的核心机制。
 */
class LinkReadLatencyTest {

    /** 黑洞传输：connect 一直挂着（模拟 SYN 有去无回），直到被取消。 */
    private class HangingTransport(override val isSecure: Boolean) : LinkTransport {
        override suspend fun connect(host: String, port: Int, expectedPin: TlsPin?, timeoutMs: Long): LinkConnection? {
            delay(30_000)
            return null
        }

        override suspend fun accept(
            socket: Socket,
            read: ByteReadChannel,
            write: ByteWriteChannel,
            remoteHost: String,
            remotePort: Int,
            expectedPin: TlsPin?,
        ): LinkConnection? = null
    }

    private fun profile(nodeId: String) = Profile(
        nodeId = nodeId,
        nick = nodeId,
        company = "",
        dept = "",
        team = "",
        avatar = 0,
        profileRev = 0,
        host = "h",
        platform = "linux",
        tcpPort = 17879,
        tlsPort = 17880,
        ver = "relay-0.1.0",
        caps = listOf(MsgTypes.CAP_LINK),
    )

    @Test
    fun readingThePeerTableAndTheLinkSnapshotStaysFastWhileADialHangs() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val registry = PeerRegistry("self")
        // 公网地址 + lnk1 + tlsPort + pin ⇒ LinkClient 一定会去拨它（TLS）。
        registry.touch("peer", "192.0.2.1", 17878, profile("peer"), 1L, confirmed = true)
        registry.setPin("peer", "sha256:" + "a".repeat(64))
        val hanging = HangingTransport(isSecure = true)
        val client = LinkClient(
            registry = registry,
            transport = hanging,
            plaintextTransport = hanging,
            scope = scope,
            selfNodeId = { "self" },
            enabled = { true },
            tickMs = 20,
            connectTimeoutMs = 30_000,
        )
        client.start()
        try {
            // 前置：拨号必须真的已经开始（attempts 在 connect 之前计数），否则这条测试会假绿。
            // 等待策略统一走 testutil（硬超时，不再自己数 20ms 的拍子）。
            awaitTrue(what = "前置：拨号必须已经开始") {
                (client.dialStats()["peer"]?.attempts ?: 0) > 0
            }

            // 拨号挂着的时候，读侧反复读：最慢一次也不能超过 MAX_READ_MS。
            var worst = 0L
            repeat(20) {
                val started = nowMillis()
                registry.list()
                registry.get("peer")
                client.linkCarriers()
                client.dialStats()
                val elapsed = nowMillis() - started
                if (elapsed > worst) worst = elapsed
            }
            assertTrue(worst < MAX_READ_MS, "拨号卡住时读侧最慢一次 ${worst}ms（应 < ${MAX_READ_MS}ms）")
        } finally {
            client.stop()
            scope.cancel()
        }
    }

    private companion object {
        /** 拨号挂着时读侧的单次上限。真机验收是 < 200ms；这里留出 CI 抖动的余量。 */
        const val MAX_READ_MS = 1_000L
    }
}
