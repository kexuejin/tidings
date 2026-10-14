// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import app.relay.core.protocol.Codec
import app.relay.core.protocol.MsgKinds
import app.relay.core.protocol.MsgTypes
import app.relay.core.protocol.Profile
import app.relay.core.protocol.makeEnvelope
import app.relay.platform.InMemoryAppPlatform
import app.relay.platform.MdnsAdvertiser
import app.relay.platform.MdnsBrowser
import app.relay.platform.NetworkMonitor
import app.relay.platform.NetworkSnapshot
import app.relay.platform.PushNotificationHandler
import app.relay.platform.Transport
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 通知必须知道**自己说的是谁**。
 *
 * 真机缺陷：Android 那版 `showNotification` 完全没挂 `setContentIntent`，
 * `dumpsys notification` 里 `contentIntent=null` —— 点通知卡片只会让它展开/收起，应用
 * 一动不动，而 `ACCEPTANCE.md` 的 C8 写的是"点开应用后**直接落在与那个对端的会话里**"。
 * 修完之后，通知要能把对端 id 一路交到壳手里；这条链路跨了控制器、平台接口与 Android 壳
 * 三层，任何一层少传一个值，症状都是"点了通知，落回默认页"，而且**不报错**。
 *
 * 这里钉的是控制器到平台接口那一段：真的起一个节点、真的从 socket 收一帧，然后看
 * 假的通知处理器收到了什么。放在 `jvmTest` 的理由同 `RelayControllerNetworkTest` ——
 * 要 `runBlocking` 等真实的 socket 绑定与真实的帧到达。
 *
 * **没有**覆盖到的：Android 那半段（`PendingIntent` + `MainActivity.onNewIntent`）。
 * 本仓没有 Robolectric 之类的 Android 单测环境，那半段靠真机验收（`runtime-evidence/`）。
 */
class NudgeNotificationTest {

    /**
     * 戳一碰：通知要指向戳我的那个对端，而且**同一帧**还要落进 `focusRequest`。
     *
     * 两个断言缺一不可：`focusRequest` 是"应用在前台时自己切过去"那条路，
     * `focusPeerId` 是"应用在后台、用户点通知回来"那条路。只做一个，另一半就是坏的。
     */
    @Test
    fun aNudgeNotificationKnowsWhichPeerItIsAbout() = runBlocking {
        val recorder = RecordingNotificationHandler()
        val controller = controller(recorder, this)
        val peer = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        try {
            controller.start()
            awaitUntil("节点没有起来") { controller.state.value.network.boundUdpPort > 0 }
            val port = controller.state.value.network.boundUdpPort

            // 先握一次手：通知标题取的是对端昵称，没有这一步标题只能退成 nodeId 前 8 位。
            send(
                peer,
                port,
                makeEnvelope(MsgTypes.ENTRY, PEER_ID, Codec.profilePayload(peerProfile())),
            )
            awaitUntil("握手之后对方没有进节点表") {
                controller.state.value.peers.any { it.nodeId == PEER_ID }
            }

            send(
                peer,
                port,
                makeEnvelope(MsgTypes.MSG, PEER_ID, buildJsonObject { put("kind", MsgKinds.NUDGE) }),
            )
            awaitUntil("戳一碰这一帧没被认出来（focusRequest 一直是空的）") {
                controller.state.value.focusRequest == PEER_ID
            }
            awaitUntil("戳一碰没有变成一条通知") { recorder.calls.isNotEmpty() }

            val call = recorder.calls.single()
            assertEquals("跑在 17891 的 agent", call.title, "通知标题该是对端昵称")
            assertEquals(
                PEER_ID,
                call.focusPeerId,
                "通知不知道自己说的是谁：点开只能落回默认页（这正是真机上那个缺陷）",
            )
            assertEquals(PEER_ID, controller.state.value.focusRequest, "前台那条路也要能定位")
            // 正文在这条测试里是**空的**：`chat_nudge_peer` 是一句 compose 资源文案，而
            // `jvmTest` 的 classpath 上没有 compose 资源（`getString` 抛
            // `ExceptionInInitializerError`，用一条临时探针确认过）。真机上它是有字的。
            //
            // 这恰好钉住了另一件事：文案读不到时**通知照发**。上一版是
            // `?: return@launch` —— 文案一读不到就整条不发，用户那边"被戳了却什么都没发生"。
            assertEquals("", call.body, "读不到文案也要把通知发出去，只是少一行字")
        } finally {
            controller.stop()
            peer.close()
        }
    }

    /**
     * 普通文本消息的通知同样要带上发件人。
     *
     * 与戳一碰分开测是有原因的：这是**另一处**调用点（`onInbound` 的文本分支）。
     * 只给戳一碰接上 focusPeerId、文本那条忘了传，是很容易发生的事 —— 而"点消息通知
     * 落在别的会话"比"点戳一碰通知没反应"更常见。
     */
    @Test
    fun aTextMessageNotificationAlsoCarriesTheSender() = runBlocking {
        val recorder = RecordingNotificationHandler()
        val controller = controller(recorder, this)
        val peer = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        try {
            controller.start()
            awaitUntil("节点没有起来") { controller.state.value.network.boundUdpPort > 0 }
            val port = controller.state.value.network.boundUdpPort

            send(
                peer,
                port,
                makeEnvelope(MsgTypes.ENTRY, PEER_ID, Codec.profilePayload(peerProfile())),
            )
            awaitUntil("握手之后对方没有进节点表") {
                controller.state.value.peers.any { it.nodeId == PEER_ID }
            }

            send(
                peer,
                port,
                makeEnvelope(MsgTypes.MSG, PEER_ID, Codec.textPayload("在吗")),
            )
            awaitUntil("文本消息没有变成一条通知") { recorder.calls.isNotEmpty() }

            val call = recorder.calls.single()
            assertEquals("跑在 17891 的 agent", call.title)
            assertEquals(PEER_ID, call.focusPeerId, "点消息通知也要落到发件人的会话里")
        } finally {
            controller.stop()
            peer.close()
        }
    }

    // ---------------------------------------------------------------- 脚手架

    private fun controller(
        recorder: PushNotificationHandler,
        scope: CoroutineScope,
    ): RelayController = RelayController(
        platform = InMemoryAppPlatform(
            networkMonitor = AlwaysOnlineMonitor,
            mdnsAdvertiser = NoopAdvertiser,
            mdnsBrowser = NoopBrowser,
            pushNotificationHandler = recorder,
        ),
        // 端口必须显式给 0（临时端口）：默认值是 17878，而开发机上那个端口被上游茶话间
        // 占着，绑不上 —— 症状是 `boundUdpPort` 永远是 0，测试只看到"节点没起来"。
        store = InMemorySettingsStore(
            RelaySettings(
                nodeId = "abc12345-0000-4000-8000-000000000000",
                nick = "我的手机",
                udpPort = 0,
                tcpPort = 0,
            ),
        ),
        scope = scope,
        version = "relay-test",
    )

    private fun peerProfile(): Profile = Profile.agent(
        nodeId = PEER_ID,
        nick = "跑在 17891 的 agent",
        host = "mac-mini",
        tcpPort = 17891,
        ver = "relay-test",
    )

    private fun send(peer: DatagramSocket, port: Int, envelope: app.relay.core.protocol.Envelope) {
        val bytes = Codec.encodeToBytes(envelope)
        peer.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("127.0.0.1"), port))
    }

    private suspend fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = kotlin.time.TimeSource.Monotonic.markNow()
        while (!condition()) {
            if (deadline.elapsedNow().inWholeMilliseconds > TIMEOUT_MS) error("等了 ${TIMEOUT_MS}ms 仍未发生：$what")
            delay(10)
        }
    }

    /** 记下每一次通知**原样**收到的三个参数。 */
    private class RecordingNotificationHandler : PushNotificationHandler {
        data class Call(val title: String, val body: String, val focusPeerId: String?)

        val calls = Collections.synchronizedList(mutableListOf<Call>())

        override fun showNotification(title: String, body: String, focusPeerId: String?) {
            calls.add(Call(title, body, focusPeerId))
        }

        override suspend fun requestPermission(): Boolean = true
    }

    private object AlwaysOnlineMonitor : NetworkMonitor {
        override fun rawSnapshot(): NetworkSnapshot = NetworkSnapshot(
            online = true,
            transport = Transport.Wifi,
            localAddresses = listOf("192.168.2.22"),
            vpnActive = false,
        )
    }

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
        const val PEER_ID = "33333333-3333-4333-8333-333333333333"
    }
}
