// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.domain

import com.kapp.tidings.platform.InMemoryAppPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking

/**
 * 「移除节点」的统一入口按行类型分派。
 *
 * 为什么值得一条用例：界面上只有一颗按钮，但它面前混着两种行。分派错了不会崩，
 * 只会「看起来删了、其实删的是别的东西」—— 手动地址走成删身份记忆，或反过来。
 */
class RelayControllerRemoveNodeTest {

    private val self = "abc12345-0000-4000-8000-000000000000"

    @Test
    fun removingAManualAddressDeletesOnlyThatRecord() = runBlocking {
        val entry = ManualPeerEntry(host = "10.20.30.9", name = "同事的旧笔记本")
        val controller = controller(listOf(entry))

        controller.removeNode(NodeRemoval.ManualAddress(entry))

        assertTrue(controller.state.value.settings.manualPeers.isEmpty(), "手动地址必须从设置里消失")
        assertEquals(
            BannerMessage.PeerRemoved("同事的旧笔记本"),
            controller.state.value.banner?.message,
            "回执与按钮同一个名字来源（manualPeerDisplayName）",
        )
    }

    @Test
    fun removingADiscoveredNodeWithoutANodeRunningSaysWhy() = runBlocking {
        // 没有节点运行时删不了身份记忆（registry 是运行时的一部分）。此时必须**说原因**，
        // 不能静默成功——用户会以为删掉了，重启后它又回来。
        val controller = controller()

        controller.removeNode(NodeRemoval.DiscoveredNode("2f2f2f2f-2222-4222-8222-222222222222"))

        assertEquals(BannerMessage.NodeNotStarted, controller.state.value.banner?.message)
    }

    private fun CoroutineScope.controller(
        manualPeers: List<ManualPeerEntry> = emptyList(),
    ): RelayController = RelayController(
        platform = InMemoryAppPlatform(),
        store = MemorySettingsStore(self, manualPeers),
        scope = this,
        version = "relay-test",
    )

    private class MemorySettingsStore(
        private val nodeId: String,
        private val manualPeers: List<ManualPeerEntry>,
    ) : SettingsStore {
        private var current: RelaySettings? = RelaySettings(
            nodeId = nodeId,
            nick = "我的手机",
            udpPort = 0,
            tcpPort = 0,
            manualPeers = manualPeers,
        )

        override fun load(): RelaySettings? = current

        override fun save(settings: RelaySettings) {
            current = settings
        }
    }
}
