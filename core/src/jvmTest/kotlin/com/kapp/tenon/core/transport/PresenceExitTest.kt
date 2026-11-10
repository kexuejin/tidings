// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.transport

import com.kapp.tenon.core.protocol.Codec
import com.kapp.tenon.core.protocol.DecodeResult
import com.kapp.tenon.core.protocol.Envelope
import com.kapp.tenon.core.protocol.MsgTypes
import com.kapp.tenon.core.protocol.Profile
import com.kapp.tenon.core.session.NodeRuntime
import com.kapp.tenon.core.testing.IngestNode
import com.kapp.tenon.core.nowMillis
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 告别帧（`exit`）这条路。
 *
 * 为什么要单独立一个测试：`exit` 在本仓曾经是**只收不发**的（接收端早就会
 * `registry.markOffline`，但没有任何出站路径），后果是退出的一端已经走了，对端还当它
 * 在线 —— 要等满 `Timings.offlineAfter`（90 秒）的 presence 超时才改判，这 90 秒里
 * 发过去的消息全部进排队。用户看到的是"明明退了，它还显示在线，我发消息它还排队"。
 *
 * legacy `Discovery` 删除后，发送面搬进 `NodeRuntime.announceExit`（对已知节点与
 * 手动地址各发一帧）、接收面住 `PresenceIngest` —— 两个主人都要钉住：
 *
 * 1. **收到 `exit` 立刻改判离线**（不用等超时）—— 这是告别帧存在的全部意义；
 * 2. **载荷是空对象** —— `entry`/`alive` 靠载荷里的 profile 传资料，`exit` 不传；
 *    两端都不读它（接收端只用信封的 `from`，TS 侧校验就是 `isRecord(payload)`）。
 *    往载荷里塞东西不会有人报错，但会让两端的线格式悄悄分叉。
 *
 * 发送侧用真实 `NodeRuntime`、接收侧用 [IngestNode]，两条真实 socket 绑在 127.0.0.1
 * 上跑，不做假传输 —— 这条路的失败（地址写错、载荷形状不对）只有在真收发时才暴露。
 */
class PresenceExitTest {

    private fun profile(nodeId: String) = Profile(
        nodeId = nodeId,
        nick = "n",
        company = "",
        dept = "",
        team = "",
        avatar = 0,
        profileRev = 0,
        host = "127.0.0.1",
        platform = "linux",
        tcpPort = 17879,
        ver = "relay-0.1.0",
        caps = emptyList(),
    )

    @Test
    fun exitIsSentOnLeaveAndThePeerGoesOfflineWithoutWaitingForTheTimeout() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transportA = UdpTransport(port = 0, bindHost = "127.0.0.1")
        val transportB = UdpTransport(port = 0, bindHost = "127.0.0.1")
        transportA.start()
        transportB.start()

        // 发送侧是一台**真实的 runtime**：`announceExit()` 的发送面刚从 legacy
        // `Discovery` 搬家过来，钉住的是搬完之后"真的会发"这一条。
        val host = NodeRuntime(
            messengerTransportFactory = { _ -> transportA },
            profile = profile("node-a"),
            udpPort = 0,
            bindHost = "127.0.0.1",
            tcpPort = 0,
        )
        host.start(scope)

        val registryB = PeerRegistry("node-b")
        val ingestB = IngestNode(
            transport = transportB,
            registry = registryB,
            selfProfile = { profile("node-b") },
        )
        ingestB.start()

        // 在 B 的入站流上再挂一个收集器：用 Mutex 保护跨协程列表访问。
        val seen = mutableListOf<Envelope>()
        val seenLock = kotlinx.coroutines.sync.Mutex()
        val watcher = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            transportB.incoming.collect { datagram ->
                val decoded = Codec.decode(datagram.bytes)
                if (decoded is DecodeResult.Ok) {
                    seenLock.lock()
                    try {
                        seen += decoded.env
                    } finally {
                        seenLock.unlock()
                    }
                }
            }
        }

        try {
            // 前提：B 已经认识 A 且判它在线（心跳把 A 的 entry 送到、B 采纳）。
            host.setPeerEndpoints("node-b", listOf(PeerEndpoint("127.0.0.1", transportB.boundPort)))
            awaitCondition(timeoutMs = 8_000, stepMs = 50, onRetry = { host.announce() }, what = "B 没有把 A 记成在线：entry 没送到") {
                registryB.get("node-a")?.online == true
            }

            host.announceExit()

            awaitCondition(timeoutMs = 8_000, stepMs = 50, onRetry = { host.announceExit() }, what = "B 收到 exit 之后没有立刻把 A 改判离线") {
                registryB.get("node-a")?.online == false
            }

            val exit = seenLock.withLock {
                seen.lastOrNull { it.type == MsgTypes.EXIT }
            }
            assertTrue(exit != null, "B 从没收到过 exit 帧：出站路径没接上")
            assertEquals("node-a", exit.from)
            assertEquals(JsonObject(emptyMap()), exit.payload, "exit 的载荷必须是空对象")
        } finally {
            watcher.cancel()
            host.stop()
            ingestB.stop()
            transportA.stop()
            transportB.stop()
            scope.cancel()
        }
    }

    /** 轮询到条件成立为止；周期性重试触发以应对 UDP 丢包，超时带着 [what] 失败。 */
    private suspend fun awaitCondition(
        timeoutMs: Long = 8_000,
        stepMs: Long = 50,
        retryIntervalMs: Long = 500,
        onRetry: (suspend () -> Unit)? = null,
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = nowMillis() + timeoutMs
        var lastRetry = nowMillis()
        if (onRetry != null) onRetry()
        while (nowMillis() < deadline) {
            if (condition()) return
            delay(stepMs)
            val now = nowMillis()
            if (onRetry != null && now - lastRetry >= retryIntervalMs) {
                lastRetry = now
                onRetry()
            }
        }
        assertTrue(condition(), what)
    }
}
