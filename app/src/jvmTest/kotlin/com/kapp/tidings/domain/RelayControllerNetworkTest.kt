// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.core.protocol.Codec
import com.kapp.tidings.core.protocol.DecodeResult
import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.Profile
import com.kapp.tidings.core.protocol.makeEnvelope
import com.kapp.tidings.platform.InMemoryAppPlatform
import com.kapp.tidings.platform.MdnsAdvertiser
import com.kapp.tidings.platform.MdnsBrowser
import com.kapp.tidings.platform.NetworkMonitor
import com.kapp.tidings.platform.NetworkSnapshot
import com.kapp.tidings.platform.Transport
import com.kapp.tidings.platform.withoutUsableAddress
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import com.kapp.tidings.testutil.awaitUntil

/**
 * 换 Wi-Fi / 换网段之后，节点**必须自己知道**，而且要**马上重新广播**。
 *
 * 这条不变量对应的真机现象是：带着 App 从家里 Wi-Fi 走到公司 Wi-Fi，或者关掉 Wi-Fi
 * 再开，本机地址只在 `start()` 时取过一次，于是「发现」页一直显示旧 IP、广播也仍按
 * 旧网段发，对端要等 `presenceInterval`（30 秒）之后才可能重新找到我 —— 用户看到的
 * 是"刚才还好好的，换个网就哑了"，而重启 App 又能好。这和
 * "接口在、没人调"那类缺陷同族，所以它需要一条**会咬**的测试。
 *
 * 这里断言的是**控制器的行为**（有没有订阅、收到变化有没有刷新地址并立刻重播、
 * stop 有没有退订），不是"网络通不通"—— 后者没法在单测里验。
 *
 * 放在 `jvmTest` 的理由同 `RelayControllerMdnsTest`：要 `runBlocking` 等一次真实的
 * socket 绑定，`runTest` 的虚拟时间会和真实 IO 打架。
 */
class RelayControllerNetworkTest {

    @Test
    fun networkChangeRefreshesTheAddressAndReannouncesImmediately() = runBlocking {
        // 用真实 UDP socket 当"手动节点"：`announce()` 会给每个手动节点单播一份 entry，
        // 所以"收到第二包"就是"它真的重新广播了"的硬证据，而不是看某个内部标志位。
        val peer = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val received = Collections.synchronizedList(mutableListOf<Int>())
        val reader = thread(isDaemon = true) {
            val buf = ByteArray(4096)
            while (!peer.isClosed) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    peer.receive(packet)
                    received.add(packet.length)
                } catch (_: Throwable) {
                    // socket 关掉时 receive 会抛，正常退出路径。
                }
            }
        }

        val monitor = RecordingNetworkMonitor()
        val controller = controller(
            monitor = monitor,
            manualPeers = listOf(ManualPeerEntry("127.0.0.1", peer.localPort)),
            scope = this,
        )

        try {
            controller.start()
            awaitUntil("启动时没有向手动节点打招呼") { received.isNotEmpty() }
            assertEquals(1, monitor.subscribers, "控制器没有订阅网络变化：换网后本机地址会一直是旧的")

            val before = received.size
            monitor.emit(
                NetworkSnapshot(
                    online = true,
                    transport = Transport.Cellular,
                    localAddresses = listOf("10.1.2.3"),
                    vpnActive = false,
                ),
            )

            // 「立刻」= 远小于 presenceInterval（30 秒）。等到 30 秒才重播等于没有这条修复。
            awaitUntil("换网后没有立刻重新广播（对端要等最多 30 秒才重新看到我）") {
                received.size > before
            }
            awaitUntil("换网后本机地址没有刷新（界面会一直显示旧 IP）") {
                controller.state.value.network.localAddresses == listOf("10.1.2.3")
            }
            assertEquals(Transport.Cellular, controller.state.value.network.transport)
            // 端口没变：UDP 绑的是 0.0.0.0，换 IP 不需要重绑，也不该换一个端口
            // 让对端手里那份资料作废。
            assertTrue(controller.state.value.network.boundUdpPort > 0)
        } finally {
            controller.stop()
            peer.close()
            reader.join(1_000)
        }

        assertEquals(0, monitor.subscribers, "停止节点必须退订，否则反复启停会越订越多")
    }

    /**
     * 一键清理只删"从来没有回应过"的地址。
     *
     * 没有学到一个节点时（= 全是死地址），所有条目都该被删掉 —— 这正是换网段之后
     * 「加入」页该有的样子。
     */
    @Test
    fun cleanupRemovesEveryAddressThatNeverAnswered() {
        val store = MemorySettingsStore(
            manualPeers = listOf(
                ManualPeerEntry("192.168.2.99", 17878),
                ManualPeerEntry("192.168.2.82", 17879),
            ),
        )
        val controller = controller(RecordingNetworkMonitor(), store.current!!.manualPeers, store = store)

        controller.removeUnreachableManualPeers()

        assertEquals(emptyList(), controller.state.value.settings.manualPeers)
        assertEquals(emptyList(), store.current?.manualPeers)
        assertEquals(
            BannerMessage.UnreachablePeersRemoved(2),
            controller.state.value.banner?.message,
        )
    }

    /** 没有可清理的东西时要说一句"没有"，而不是静默什么都不发生（用户会以为按钮坏了）。 */
    @Test
    fun cleanupSaysSoWhenThereIsNothingToClean() {
        val store = MemorySettingsStore(manualPeers = emptyList())
        val controller = controller(RecordingNetworkMonitor(), store.current!!.manualPeers, store = store)

        controller.removeUnreachableManualPeers()

        assertEquals(BannerMessage.NoUnreachablePeers, controller.state.value.banner?.message)
    }

    /**
     * 换网之后**学到过但没手动配过**的节点也必须被叫醒。
     *
     * 广播是受限广播（`255.255.255.255`），只在**新**网段里有效；而这类节点（真机上就是
     * agentNode：它跑在 UDP 17891，不在广播端口 17878 上）永远收不到广播。少了这条单播，
     * 换网之后它要等下一次 presence（最多 30 秒）才知道我的新地址 —— 用户看到的是
     * "换了 Wi-Fi，消息要愣半分钟才通"。和"心跳漏掉被动握手的节点"那条同源。
     *
     * 这里一个手动节点都不配，所以"学到过的节点"是 `announce()` 唯一的单播目标。
     *
     * 判据是**帧类型**而不是包数：对方主动来握手时本机会回一个 ALIVE（带随机抖动延迟），
     * 数包会把那个当成"重播了"。只认 ENTRY —— 那才是 `announce()` 发的东西。
     */
    @Test
    fun networkChangeAlsoWakesPeersWeLearnedButNeverConfigured() = runBlocking {
        val peer = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val receivedTypes = Collections.synchronizedList(mutableListOf<String>())
        val reader = thread(isDaemon = true) {
            val buf = ByteArray(4096)
            while (!peer.isClosed) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    peer.receive(packet)
                    val decoded = Codec.decode(packet.data.copyOf(packet.length))
                    receivedTypes.add((decoded as? DecodeResult.Ok)?.env?.type ?: "?")
                } catch (_: Throwable) {
                    // socket 关掉时 receive 会抛，正常退出路径。
                }
            }
        }

        val monitor = RecordingNetworkMonitor()
        val controller = controller(monitor, manualPeers = emptyList(), scope = this)

        try {
            controller.start()
            awaitUntil("节点没有起来") { controller.state.value.network.boundUdpPort > 0 }
            val port = controller.state.value.network.boundUdpPort

            // 冒充一个对端主动来握手：它会用**包的源地址**（这个临时 socket）把我记进
            // registry，而不是用手动配置 —— 这正是 agentNode 被学到的真实路径。
            val profile = Profile.agent(
                nodeId = PEER_ID,
                nick = "跑在 17891 的 agent",
                host = "mac-mini",
                tcpPort = 17891,
                ver = "relay-test",
            )
            val entry = Codec.encodeToBytes(
                makeEnvelope(MsgTypes.ENTRY, PEER_ID, Codec.profilePayload(profile)),
            )
            peer.send(DatagramPacket(entry, entry.size, InetAddress.getByName("127.0.0.1"), port))
            awaitUntil("主动握手之后对方没有进节点表") {
                controller.state.value.peers.any { it.nodeId == PEER_ID }
            }

            // 换网之前一条 ENTRY 都不该有：没有手动节点，启动时的 handshake 不会发。
            assertEquals(0, receivedTypes.count { it == MsgTypes.ENTRY })

            monitor.emit(
                NetworkSnapshot(
                    online = true,
                    transport = Transport.Cellular,
                    localAddresses = listOf("10.1.2.3"),
                    vpnActive = false,
                ),
            )

            awaitUntil("换网后没有单播给学到过的节点（它要等最多 30 秒才知道我的新地址）") {
                receivedTypes.count { it == MsgTypes.ENTRY } > 0
            }
        } finally {
            controller.stop()
            peer.close()
            reader.join(1_000)
        }
    }

    /**
     * 真机缺陷：关掉 Wi-Fi 之后「连接方式」已经改成「没有网络」、本机 IP 已经变成 `—`，
     * 「可被自动发现」却还写着「广播中」，而且**再也不会自己改回来** —— 一个只在某一瞬间
     * 成立的状态被永久留在屏幕上。
     *
     * 这里同时盯住两件事：没有地址时必须说「未广播」；地址回来之后必须**能回到**「广播中」
     * （所以结论不能覆盖进 `mdnsAdvertised` 那个原始字段里，否则改小一次就回不去了）。
     */
    @Test
    fun goingOfflineStopsClaimingWeCanBeDiscovered() = runBlocking {
        val monitor = RecordingNetworkMonitor()
        val controller = controller(monitor = monitor, manualPeers = emptyList(), scope = this)

        try {
            controller.start()
            awaitUntil("启动后没有记录 mDNS 注册结果") {
                controller.state.value.network.discoverable != null
            }
            assertEquals(
                true,
                controller.state.value.network.discoverable,
                "注册成功且有地址时应当说「广播中」",
            )

            // 换网到"没有地址"：真机上这就是 Wi-Fi 刚关掉那一刻。
            monitor.emit(
                NetworkSnapshot(
                    online = false,
                    transport = Transport.None,
                    localAddresses = emptyList(),
                    vpnActive = false,
                ),
            )
            awaitUntil("没有本机地址时还在说「广播中」（广播根本发不出去）") {
                controller.state.value.network.discoverable == false
            }

            // 地址回来。真机复验：重开 Wi-Fi 无需重启即可恢复。
            monitor.emit(
                NetworkSnapshot(
                    online = true,
                    transport = Transport.Wifi,
                    localAddresses = listOf("192.168.2.22"),
                    vpnActive = false,
                ),
            )
            awaitUntil("网络恢复后没有回到「广播中」（结论被写进原始字段就回不来了）") {
                controller.state.value.network.discoverable == true
            }
        } finally {
            controller.stop()
        }
    }

    private fun controller(
        monitor: NetworkMonitor,
        manualPeers: List<ManualPeerEntry>,
        scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
        store: MemorySettingsStore = MemorySettingsStore(manualPeers),
    ): RelayController = RelayController(
        platform = InMemoryAppPlatform(
            networkMonitor = monitor,
            mdnsAdvertiser = NoopAdvertiser,
            mdnsBrowser = NoopBrowser,
        ),
        store = store,
        scope = scope,
        version = "relay-test",
    )

    /** 假订阅源：把回调留住，让测试自己决定"网络刚刚发生了什么"。 */
    private class RecordingNetworkMonitor : NetworkMonitor {
        var subscribers = 0
            private set
        private var onChange: ((NetworkSnapshot) -> Unit)? = null

        override fun rawSnapshot(): NetworkSnapshot = NetworkSnapshot(
            online = true,
            transport = Transport.Wifi,
            localAddresses = listOf("192.168.2.22"),
            vpnActive = false,
        )

        override fun observe(onChange: (NetworkSnapshot) -> Unit): () -> Unit {
            subscribers++
            this.onChange = onChange
            return {
                subscribers--
                this.onChange = null
            }
        }

        /**
         * 真平台是 `onChange(snapshot())`，所以要过一遍接口默认的收口再交出去 ——
         * 否则测试里"地址为空还说 Wifi"这种状态会被原样喂给控制器，替身比真机宽容。
         */
        fun emit(raw: NetworkSnapshot) {
            onChange?.invoke(raw.withoutUsableAddress())
        }
    }

    private class MemorySettingsStore(manualPeers: List<ManualPeerEntry>) : SettingsStore {
        var current: RelaySettings? = RelaySettings(
            nodeId = "abc12345-0000-4000-8000-000000000000",
            nick = "我的手机",
            udpPort = 0,
            tcpPort = 0,
            manualPeers = manualPeers,
        )
            private set

        override fun load(): RelaySettings? = current

        override fun save(settings: RelaySettings) {
            current = settings
        }
    }

    /** 这个测试不关心自动发现，但 `start()` 会去启动它，所以必须给不抛错的替身。 */
    private object NoopAdvertiser : MdnsAdvertiser {
        override suspend fun start(serviceName: String, port: Int): Boolean = true
        override fun stop() = Unit
    }

    private object NoopBrowser : MdnsBrowser {
        override fun startBrowsing(
            onServiceFound: (String, String, Int) -> Unit,
            onServiceLost: (String) -> Unit,
        ) = Unit

        override fun stopBrowsing() = Unit
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val PEER_ID = "22222222-2222-4222-8222-222222222222"
    }
}
