// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.Profile
import com.kapp.tidings.core.protocol.Timings
import com.kapp.tidings.core.session.NodeRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * task-29：**发现泵静默停摆**的两条自愈。
 *
 * 真机现场：手机进程活着、保活前台服务在跑、logcat 无异常、`/proc/net/tcp6` 里一条连接都没有、
 * 对端 lastSeen 永远停在某一刻。两个成因各自钉一条测试：
 *  1. 运行时的泵跑在**调用方**（Compose/Activity）的 scope 上 —— 那个 scope 一被取消，
 *     整套泵就永久死掉，而 `runtime != null` 又让重启入口直接 return；
 *  2. socket 被关/坏之后 `send` 静默失败（异常被 `runCatching` 吞掉），没有任何人重建它。
 */
class TransportSelfHealTest {

    private fun profile(nodeId: String) = Profile(
        nodeId = nodeId,
        nick = nodeId,
        company = "",
        dept = "",
        team = "",
        avatar = 0,
        profileRev = 0,
        host = "127.0.0.1",
        platform = "linux",
        tcpPort = 17879,
        ver = "relay-0.1.0",
        caps = listOf(MsgTypes.CAP_MOBILE_TEXT),
    )

    private suspend fun awaitTrue(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        withTimeout(timeoutMs) { while (!condition()) delay(10) }
    }

    @Test
    fun theRuntimeKeepsBeatingAfterTheCallersScopeIsCancelled() = runBlocking {
        // 调用方 scope 与"观察者"的 scope **必须分开**：这个测试要取消前者，
        // 而观察者得活着继续数包。
        val callerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val watcher = UdpTransport(port = 0, bindHost = "127.0.0.1")
        watcher.start()
        val host = NodeRuntime(
            profile = profile("host-node"),
            udpPort = 0,
            bindHost = "127.0.0.1",
            tcpPort = 0,
            manualPeers = listOf(ManualPeer("127.0.0.1", watcher.boundPort)),
            timings = Timings(presenceInterval = 60, sweepInterval = 60_000),
        )
        // 用普通 var：commonTest 不能碰 java.*（iOS 目标也要编译）。计数只用于断言，容忍竞态。
        var presences = 0
        // 观察者跑在 runBlocking 自己的 scope 上，不会被 callerScope.cancel() 带走。
        val watch = launch {
            watcher.incoming.collect { datagram ->
                if (datagram.bytes.isNotEmpty()) presences++
            }
        }
        host.start(callerScope)
        try {
            awaitTrue("前置：心跳必须已经在跑") { presences > 0 }
            // 模拟界面销毁：调用方 scope 被取消，而 runtime 对象还在。
            callerScope.cancel()
            val before = presences
            awaitTrue("调用方 scope 取消后，运行时自己的泵必须继续发心跳") { presences > before }
        } finally {
            host.stop()
            watcher.stop()
            watch.cancel()
        }
    }

    @Test
    fun aStalledTransportIsRebuiltByThePresenceLoop() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sender = UdpTransport(port = 0, bindHost = "127.0.0.1")
        val watcher = UdpTransport(port = 0, bindHost = "127.0.0.1")
        sender.start()
        watcher.start()
        val discovery = Discovery(
            transport = sender,
            registry = PeerRegistry("node-a"),
            selfProfile = { profile("node-a") },
            timings = Timings(presenceInterval = 50, sweepInterval = 60_000),
            manualPeers = listOf(ManualPeer("127.0.0.1", watcher.boundPort)),
            broadcastHost = "127.0.0.1",
            broadcastPort = 65534,
        )
        var received = 0
        val watch = scope.launch { watcher.incoming.collect { received++ } }
        discovery.start(scope)
        try {
            awaitTrue("前置：心跳必须已经在跑") { received > 0 }
            // 模拟 socket 坏掉：直接关掉（从前的写法会让发送永久静默）。
            sender.stop()
            assertFalse(sender.isRunning, "前提：transport 已停")
            val before = received
            awaitTrue("连续两轮发不出去之后必须重建 transport，心跳重新到达") { received > before }
            assertTrue(sender.isRunning, "自愈后 transport 必须重新在跑")
        } finally {
            discovery.stop()
            sender.stop()
            watcher.stop()
            watch.cancel()
            scope.cancel()
        }
    }

    @Test
    fun aSendOnAStoppedTransportReportsFailureInsteadOfThrowing() = runBlocking {
        val transport = UdpTransport(port = 0, bindHost = "127.0.0.1")
        transport.start()
        assertTrue(transport.send(ByteArray(1), "127.0.0.1", 17878), "在跑的时候必须报成功")
        transport.stop()
        assertFalse(transport.send(ByteArray(1), "127.0.0.1", 17878), "停了以后必须报失败，而不是抛异常被吞")
    }
}
