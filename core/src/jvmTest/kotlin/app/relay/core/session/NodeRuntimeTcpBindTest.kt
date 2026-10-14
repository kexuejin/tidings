// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.session

import app.relay.core.protocol.Profile
import app.relay.core.protocol.newId
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
}
