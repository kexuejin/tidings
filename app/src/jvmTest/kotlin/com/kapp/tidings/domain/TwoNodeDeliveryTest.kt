// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.domain.model.DeliveryState
import com.kapp.tidings.platform.InMemoryAppPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **两个真实节点之间投递一条消息** —— 全程零 UI。
 *
 * ## 为什么这条测试是「领域层自足」的核心证据
 *
 * `:cli` 证明了「领域层能启动、不加载 AWT」。但它只证明了控制器**跑得起来**，
 * 没证明**一条消息真的从发送侧走到接收侧** —— 而后者才是这个产品的全部意义。
 *
 * 本测试在同一 JVM 里起两个 [RelayController]，各自绑一个真实 UDP/TCP 端口，
 * 通过 `addManualPeer` 互相认识，然后 A 发一句话，断言 B 的状态里**真的出现了**
 * 那句话，且 A 拿到 `Sent`。
 *
 * ## 它落在 :app 里这件事本身就是判据
 *
 * 如果「投递一条消息」这件事还需要 Compose 才能跑起来，本测试根本编译不过 ——
 * 因为 `:app` 没有 Compose。所以它同时钉住了两件事：投递链路完整，且链路不含 UI。
 *
 * ## 为什么用随机端口
 *
 * 写死端口会在 CI 上撞到并行任务或上一次没退干净的实例，症状是 `BindFailed`
 * —— 那条失败看起来像「投递坏了」，其实是端口被占。随机端口把这种假红挡在门外。
 */
class TwoNodeDeliveryTest {

    private val scope = CoroutineScope(Job())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `a message really arrives at the other node`() = runBlocking {
        val portA = freePort()
        val portB = freePort()
        val a = controller(udpPort = portA, tcpPort = portA + 1)
        val b = controller(udpPort = portB, tcpPort = portB + 1)
        try {
            a.start()
            b.start()
            awaitUntil("A 起来") { a.state.value.network.phase.name == "Running" }
            awaitUntil("B 起来") { b.state.value.network.phase.name == "Running" }

            val selfA = a.state.value.selfNodeId
            val selfB = b.state.value.selfNodeId
            assertTrue(selfA.isNotBlank() && selfB.isNotBlank(), "两个节点都要有身份")

            // 互相登记为手动节点：本机回环 + 真实端口，握手走正常协议。
            a.addManualPeer("127.0.0.1", portB.toString())
            b.addManualPeer("127.0.0.1", portA.toString())
            awaitUntil("A 看见 B") { a.state.value.peers.any { it.nodeId == selfB } }
            awaitUntil("B 看见 A") { b.state.value.peers.any { it.nodeId == selfA } }

            val body = "relay-cli 之外也走同一条链路"
            a.send(selfB, body)

            // 接收侧：那句话必须真的出现在 B 的会话里（不是「发送侧以为发出去了」）。
            awaitUntil("B 收到正文") {
                b.state.value.messages[selfA].orEmpty().any { it.text == body }
            }

            val received = b.state.value.messages[selfA].orEmpty().first { it.text == body }
            assertEquals(body, received.text, "正文必须逐字到达")
            assertTrue(!received.mine, "在 B 这一侧它是收到的，不是自己发的")
            assertEquals(selfA, received.peerId, "它必须挂在 A 这条会话上")

            // 发送侧：A 那条自己发的消息最终必须是 Sent（而不是停在 Sending）。
            awaitUntil("A 的消息变成已送达") {
                a.state.value.messages[selfB].orEmpty().any {
                    it.text == body && it.delivery == DeliveryState.Sent
                }
            }

            println("[two-node] A=" + selfA.take(8) + " B=" + selfB.take(8) + " body=" + body)
        } finally {
            a.stop()
            b.stop()
        }
    }

    private fun controller(udpPort: Int, tcpPort: Int): RelayController =
        RelayController(
            platform = InMemoryAppPlatform(),
            store = InMemorySettingsStore(
                RelaySettings.firstRun().copy(
                    udpPort = udpPort,
                    tcpPort = tcpPort,
                    persistMessages = false,
                    // 决策位也要置上，否则首次运行分支会再问一遍「要不要落盘」，
                    // 而那个分支在无头测试里没人回答它。
                    persistMessagesDecided = true,
                ),
            ),
            messageStore = InMemoryMessageStore(),
            agentStore = InMemoryAgentStore(),
            scope = scope,
        )

    /** 端口 0 由系统分配；这里取一个当前空闲的固定号，够两个实例分开用。 */
    private fun freePort(): Int = java.net.ServerSocket(0).use { it.localPort }

    private suspend fun awaitUntil(what: String, condition: () -> Boolean) {
        withTimeout(20_000) {
            while (!condition()) delay(50)
        }
        // 超时是 IOException/TimeoutCancellationException；把「等什么」带进消息，
        // 否则失败输出只有一句超时，看不出卡在哪一步。
        if (!condition()) throw AssertionError("等不到：" + what)
    }
}
