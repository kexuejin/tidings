// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.session

import com.kapp.tidings.core.protocol.Profile
import com.kapp.tidings.core.protocol.newId
import com.kapp.tidings.core.transport.CrossNetworkFileListener
import com.kapp.tidings.core.transport.RawPullConnection
import java.net.ServerSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `tcpBound` 的护栏。
 *
 * 这条不变量值得一个测试，因为它丢过一次，而丢掉的后果不是"少个功能"而是**投递错人**：
 * `NodeRuntime.start()` 原来写 `manager?.start()`，把 `TransferServer.start` 的
 * `Boolean` 直接扔掉。于是同一台机器上桌面茶话间占着 `17879` 时，agentNode 绑不上
 * 却照样在自己的资料里声明 `tcpPort = 17879`，对端按 `(ip, tcpPort)` 连过来连到的是
 * **桌面端** —— 2026-10-04 真机上，两条发给 Agent 的消息落进了人类的聊天窗口。
 *
 * 为什么放在 `jvmTest` 而不是 `commonTest`：要真的占住一个 TCP 端口才能造出失败分支，
 * 而 `commonTest` 在 Android target 上根本不跑（Gradle 会警告
 * "commonTest source directory exists, but android host tests are not enabled"）。
 */
class NodeRuntimeTcpBindTest {

    @Test
    fun freeTcpPortIsReportedAsBound() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtime = newNode(tcpPort = 0)
        try {
            runtime.start(scope)
            assertTrue(runtime.tcpBound, "端口是空闲的，tcpBound 必须为 true")
        } finally {
            runtime.stop()
            scope.cancel()
        }
        assertFalse(runtime.tcpBound, "stop() 之后不该还宣称自己绑着端口")
    }

    @Test
    fun takenTcpPortFallsBackToAPortWeActuallyOwn() = runBlocking {
        // 先真的占住一个端口，模拟"桌面茶话间已经在 17879 上听着"。
        val squatter = ServerSocket(0)
        val taken = squatter.localPort
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtime = newNode(tcpPort = taken)
        try {
            runtime.start(scope)
            assertTrue(
                runtime.tcpBound,
                "声明端口 $taken 被占时应退到系统分配的端口，而不是整个不绑 —— 协议里没有\"我没有 TCP\"这种表达",
            )
            // 关键的一条：**资料里那个值必须是真的绑上的那一个**。
            // 对端会照着它连，声明错的值就是"投递错人"（真机上连到了桌面茶话间）。
            assertNotEquals(taken, runtime.profile.tcpPort, "资料里不能留着被别人占着的 $taken")
            assertEquals(
                runtime.tcpBoundPort,
                runtime.profile.tcpPort,
                "资料里的 tcpPort 必须等于真正绑上的 TCP 端口",
            )
            // 而且那个端口真的能连上（不是编出来的数字）。
            ServerSocket().use { probe ->
                probe.reuseAddress = true
                assertFailsWith<java.io.IOException>("绑在资料声明的端口上应当失败，说明它确实是我们的") {
                    probe.bind(java.net.InetSocketAddress("0.0.0.0", runtime.profile.tcpPort))
                }
                Unit
            }
        } finally {
            runtime.stop()
            scope.cancel()
            squatter.close()
        }
    }

    @Test
    fun boundTcpPortIsStillAdvertisedAsRequested() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtime = newNode(tcpPort = 0)
        try {
            runtime.start(scope)
            assertTrue(runtime.tcpBound)
            // 传 0 = 让系统分配。资料里**不能**照抄 0：[Codec.validateProfile] 要求
            // `tcpPort` 在 `1..65535`，声明 0 会让对端直接拒收整份资料（症状是握手超时）。
            assertEquals(
                runtime.tcpBoundPort,
                runtime.profile.tcpPort,
                "传 0 时资料里要写真正绑上的 TCP 端口，而不是 0",
            )
        } finally {
            runtime.stop()
            scope.cancel()
        }
    }

    /**
     * UDP 端口固定传 0（由系统分配），免得和真机上跑着的 17878 抢；
     * TCP 端口由每个用例自己决定，因为这里测的就是它。
     */
    private fun newNode(tcpPort: Int) = NodeRuntime(
        profile = Profile.mobile(
            nodeId = newId(),
            nick = "tcp-bind-test",
            host = "localhost",
            tcpPort = tcpPort,
            ver = "test",
        ),
        udpPort = 0,
        tcpPort = tcpPort,
    )
    /**
     * luciola 信道模式（fch1）下**不创建** legacy 监听。
     *
     * 这条不变量是 goal 第 (5) 步的判据：真机上 lsof 不该看到 17879/17881。
     * 这里在 JVM 上把它做成可复核的两面：
     *  1. 明文端口上**连不上**（不是「绑了但不用」）；
     *  2. 跨网 TLS 监听器**一次都没被 start**（用记账实现证明，避免只是端口恰好空着）。
     *
     * 同一份夹具跑 legacy 模式作为对照：那时端口必须绑上、监听器必须被调用 ——
     * 否则这个测试是空转的（永远绿）。
     */
    @Test
    fun luciolaChannelModeCreatesNoLegacyListeners() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tcpPort = ServerSocket(0).use { it.localPort }
        val tlsPort = ServerSocket(0).use { it.localPort }
        val listener = RecordingCrossNetworkListener()
        val runtime = NodeRuntime(
            profile = Profile.mobile(
                nodeId = newId(),
                nick = "legacy-listener-gate",
                host = "localhost",
                tcpPort = tcpPort,
                tlsPort = tlsPort,
                ver = "test",
            ),
            udpPort = 0,
            tcpPort = tcpPort,
            fileChannelEnabled = true,
            crossNetworkFileListener = listener,
            crossNetworkScreenListener = RecordingCrossNetworkListener(),
        )
        try {
            runtime.start(scope)
            assertFalse(runtime.tcpBound, "信道模式下不该宣称绑上了明文 TCP 端口")
            assertEquals(null, runtime.tcpBoundPort, "信道模式下没有 legacy 明文入口")
            assertFalse(canConnect(tcpPort), "明文端口 " + tcpPort + " 必须连不上（真的没在听）")
            assertTrue(listener.startCalls.isEmpty(), "跨网 TLS 监听不该被创建，实际: " + listener.startCalls)
        } finally {
            runtime.stop()
            scope.cancel()
        }
    }

    /** 对照组：同一条路径在 legacy 模式（默认）下必须真的开监听，证明上面不是空转。 */
    @Test
    fun legacyModeStillOpensBothListeners() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tcpPort = ServerSocket(0).use { it.localPort }
        val tlsPort = ServerSocket(0).use { it.localPort }
        val listener = RecordingCrossNetworkListener()
        val runtime = NodeRuntime(
            profile = Profile.mobile(
                nodeId = newId(),
                nick = "legacy-listener-control",
                host = "localhost",
                tcpPort = tcpPort,
                tlsPort = tlsPort,
                ver = "test",
            ),
            udpPort = 0,
            tcpPort = tcpPort,
            crossNetworkFileListener = listener,
            crossNetworkScreenListener = RecordingCrossNetworkListener(),
        )
        try {
            runtime.start(scope)
            assertTrue(runtime.tcpBound, "legacy 模式必须绑上明文端口")
            assertTrue(canConnect(tcpPort), "legacy 模式下明文端口必须可连")
            assertEquals(listOf(tlsPort + 1), listener.startCalls, "跨网入口是 tlsPort + 1")
        } finally {
            runtime.stop()
            scope.cancel()
        }
    }

    /** 记账用的跨网监听器：只记 start 的端口，不真绑（避免端口冲突干扰断言）。 */
    private class RecordingCrossNetworkListener : CrossNetworkFileListener {
        val startCalls = mutableListOf<Int>()
        override val canServe: Boolean get() = true
        override suspend fun start(port: Int, onConnection: suspend (RawPullConnection) -> Unit): Int? {
            startCalls += port
            return port
        }
        override suspend fun stop() = Unit
    }

    /** 端口上有没有人在听：连得上 = 有人在听。 */
    private fun canConnect(port: Int): Boolean = try {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress("127.0.0.1", port), 300)
        }
        true
    } catch (_: java.io.IOException) {
        false
    }
}