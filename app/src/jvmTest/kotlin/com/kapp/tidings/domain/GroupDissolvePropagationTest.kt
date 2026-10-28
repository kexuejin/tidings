// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.domain

import com.kapp.tidings.platform.InMemoryAppPlatform
import com.kapp.tidings.testutil.awaitUntil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **解散群聊必须真的传到对端** —— 两个真实节点，零 UI。
 *
 * ## 为什么单独的单元测试不够（这条用例的由来）
 *
 * `RelayControllerGroupTest.theOwnerCanDisbandAGroup` 只断言了「本机不再持有这个群」，
 * 它在 `runtime == null` 下跑 —— 而 `handleDissolveGroup` 的**广播部分整个在
 * `val rt = host.runtime ?: return` 之后**。于是那条用例对「对端到底收没收到」
 * 一个字都没说，却让人以为解散是通的。真机上就是这样：桌面弹了「已解散」，
 * 手机的群还好好留着（本仓 §3 记的「发送侧成功、接收侧什么都没有」）。
 *
 * 本用例把两个真节点接上，断言对端的 `settings.groups` 里那个群**真的没了**。
 */
class GroupDissolvePropagationTest {

    private val scope = CoroutineScope(Job())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun dissolvingAGroupRemovesItOnTheOtherNode() = runBlocking {
        val portA = freePort()
        val portB = freePort()
        val a = controller(portA)
        val b = controller(portB)
        try {
            a.start()
            b.start()
            awaitUntil("A 起来") { a.state.value.network.phase.name == "Running" }
            awaitUntil("B 起来") { b.state.value.network.phase.name == "Running" }

            val selfA = a.state.value.selfNodeId
            val selfB = b.state.value.selfNodeId
            a.addManualPeer("127.0.0.1", portB.toString())
            b.addManualPeer("127.0.0.1", portA.toString())
            awaitUntil("A 看见 B") { a.state.value.peers.any { it.nodeId == selfB && it.online } }
            awaitUntil("B 看见 A") { b.state.value.peers.any { it.nodeId == selfA && it.online } }

            // A 建群并把 B 拉进来。
            val groupId = a.createGroup("要解散的群", listOf(selfB))!!
            awaitUntil("B 收到群元数据") {
                b.state.value.settings.groups.any { it.groupId == groupId }
            }

            // A 解散：对端必须跟着把群去掉。
            a.dissolveGroup(groupId)

            // 既有语义（GroupMerge.Removed 的 KDoc）：对端**保留**元数据，但成员表里
            // 不再有自己 —— 那是它回答「我为什么发不出话」的唯一依据。删掉反而更糟：
            // 历史消息还在，会话键会退化成标题里的一串 group:ab。
            awaitUntil("B 收到解除（成员表里不再有自己）") {
                b.state.value.settings.groups
                    .firstOrNull { it.groupId == groupId }
                    ?.hasMember(selfB) == false
            }
            val atB = b.state.value.settings.groups.firstOrNull { it.groupId == groupId }
            assertNotNull(atB, "对端保留元数据（Removed 的既有语义）")
            assertTrue(!atB.members.contains(selfB), "解散必须传到对端：B 的成员表里不能再有自己")
            assertEquals(2, atB.rev, "解除帧的 rev = 原 rev + 1")
            assertNull(
                a.state.value.settings.groups.firstOrNull { it.groupId == groupId },
                "发起端也不该留着它",
            )
        } finally {
            a.stop()
            b.stop()
        }
    }

    @Test
    fun theDissolveFrameCarriesOnlyTheOwner() = runBlocking {
        // 帧内容本身就是判据：成员表只剩自己，对端才可能判 Removed。
        // 单独一条，是因为「广播了但内容不对」与「根本没广播」在界面上表现一样。
        val portA = freePort()
        val portB = freePort()
        val a = controller(portA)
        val b = controller(portB)
        try {
            a.start()
            b.start()
            awaitUntil("A 起来") { a.state.value.network.phase.name == "Running" }
            awaitUntil("B 起来") { b.state.value.network.phase.name == "Running" }
            val selfA = a.state.value.selfNodeId
            val selfB = b.state.value.selfNodeId
            a.addManualPeer("127.0.0.1", portB.toString())
            b.addManualPeer("127.0.0.1", portA.toString())
            awaitUntil("A 看见 B") { a.state.value.peers.any { it.nodeId == selfB && it.online } }
            awaitUntil("B 看见 A") { b.state.value.peers.any { it.nodeId == selfA && it.online } }

            val groupId = a.createGroup("再看一眼成员表", listOf(selfB))!!
            awaitUntil("B 收到群元数据") {
                b.state.value.settings.groups.any { it.groupId == groupId }
            }

            a.dissolveGroup(groupId)

            // B 收到的那份解除帧里，自己已经不在成员表里 —— 这是它判 Removed 的依据。
            awaitUntil("B 的成员表里不再有自己") {
                b.state.value.settings.groups
                    .firstOrNull { it.groupId == groupId }
                    ?.hasMember(selfB) == false
            }
            val atB = b.state.value.settings.groups.firstOrNull { it.groupId == groupId }
            assertNotNull(atB)
            assertEquals(listOf(selfA), atB.members, "解除帧的成员表只剩群主")
        } finally {
            a.stop()
            b.stop()
        }
    }

    private fun controller(udpPort: Int): RelayController =
        RelayController(
            platform = InMemoryAppPlatform(),
            store = InMemorySettingsStore(
                RelaySettings.firstRun().copy(
                    udpPort = udpPort,
                    tcpPort = udpPort + 1,
                    persistMessages = false,
                    persistMessagesDecided = true,
                ),
            ),
            messageStore = InMemoryMessageStore(),
            agentStore = InMemoryAgentStore(),
            scope = scope,
        )

    private fun freePort(): Int = java.net.ServerSocket(0).use { it.localPort }
}
