// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import app.relay.platform.InMemoryAppPlatform
import app.relay.platform.MdnsAdvertiser
import app.relay.platform.MdnsBrowser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import app.relay.app.model.NodePhase

/**
 * 启动节点**必须真的去广播**，而发现回来的服务**必须真的进状态**。
 *
 * 这两条不变量都不是理论问题，各自对应一个真机缺陷：
 * 1. `mdnsAdvertiser` 在两端都实现好了却一次都没被调用 ⇒ 自动发现端到端恒为零；
 * 2. Android 的 `onServiceFound` 给出的 `NsdServiceInfo` 还没有地址，必须再 `resolveService`
 *    —— 上一版直接读 `host`，拿不到就 `return`，于是发现结果被静默丢光。
 * 单测与截图门禁都看不见这两个（它们不碰网卡），所以这里用可注入的假实现把
 * "控制器有没有去调 / 有没有把结果收下"钉住 —— 断言的是控制器的行为，不是网络通不通。
 *
 * 放在 `jvmTest` 而不是 `commonTest`，是因为要 `runBlocking` 等一次真实的 socket 绑定：
 * `runTest` 的虚拟时间会和真实 IO 互相打架（测试协程一空闲就把时钟跳到超时）。
 * 端口传 0 让系统分配（见 `NodeRuntime` 的 KDoc），所以测试不会和真机上的 17878 抢端口。
 */
class RelayControllerMdnsTest {

    @Test
    fun startingTheNodeBothAdvertisesAndBrowses() = runBlocking {
        val advertiser = RecordingAdvertiser()
        val browser = RecordingBrowser()
        val controller = controller(advertiser, browser, scope = this)

        try {
            controller.start()
            awaitUntil("广播器没有被调用（自动发现会静默失效）") { advertiser.startedWith != null }

            val (label, port) = assertNotNull(advertiser.startedWith)
            assertEquals("我的手机-abc123", label)
            // 必须是**实际绑定**的端口，不是设置里的 0：报 0 等于让对端来敲一扇没人听的窗。
            val bound = controller.state.value.network.boundUdpPort
            assertTrue(bound > 0, "期望系统分配到一个真实端口，实际 $bound")
            assertEquals(bound, port)
            assertTrue(browser.browsing, "浏览也要开：只广播不浏览等于单向发现")
            assertEquals(true, controller.state.value.network.discoverable)

            // 发现回来的服务要真的进状态（对应缺陷 2）。
            browser.emitFound("另一台-999999", "192.168.2.9", 17878)
            awaitUntil("发现的服务没有进状态") { controller.state.value.mdnsServices.size == 1 }
            assertEquals("192.168.2.9", controller.state.value.mdnsServices.single().host)

            browser.emitLost("另一台-999999")
            awaitUntil("服务消失没有从状态里移除") { controller.state.value.mdnsServices.isEmpty() }
        } finally {
            controller.stop()
        }

        assertTrue(advertiser.stopped, "停止节点必须撤销发布，否则下次启动会撞上一次的注册")
        assertFalse(browser.browsing)
        assertNull(controller.state.value.network.discoverable)
        assertEquals(NodePhase.Stopped, controller.state.value.network.phase)
    }

    @Test
    fun advertiseFailureIsVisibleButDoesNotStopTheNode() = runBlocking {
        val advertiser = RecordingAdvertiser(result = false)
        val browser = RecordingBrowser()
        val controller = controller(advertiser, browser, scope = this)

        try {
            controller.start()
            awaitUntil("广播失败没有被记录") { controller.state.value.network.discoverable != null }

            // 失败只影响自动发现，手动填 IP 仍然可用 —— 所以节点照跑，但状态必须是 false。
            assertEquals(false, controller.state.value.network.discoverable)
            assertEquals(NodePhase.Running, controller.state.value.network.phase)
            assertTrue(browser.browsing)
        } finally {
            controller.stop()
        }
    }

    /**
     * Android 的 `NsdManager` 会把本机刚发布的服务也回报给浏览者。
     * 照着渲染就会出现"发现了一个和我同名的设备"，点下去还会连到自己。
     */
    @Test
    fun ownAdvertisedServiceIsNotListedAsDiscovered() = runBlocking {
        val advertiser = RecordingAdvertiser()
        val browser = RecordingBrowser()
        val controller = controller(advertiser, browser, scope = this)

        try {
            controller.start()
            awaitUntil("广播器没有被调用") { advertiser.startedWith != null }

            browser.emitFound("我的手机-abc123", "192.168.2.22", 17878) // 就是自己
            browser.emitFound("另一台-999999", "192.168.2.9", 17878)
            awaitUntil("没有收下别人的服务") { controller.state.value.mdnsServices.size == 1 }

            assertEquals(listOf("另一台-999999"), controller.state.value.mdnsServices.map { it.serviceName })
        } finally {
            controller.stop()
        }
    }

    private fun controller(
        advertiser: MdnsAdvertiser,
        browser: MdnsBrowser,
        scope: CoroutineScope,
    ): RelayController = RelayController(
        platform = InMemoryAppPlatform(mdnsAdvertiser = advertiser, mdnsBrowser = browser),
        store = MemorySettingsStore(),
        scope = scope,
        version = "relay-test",
    )

    private suspend fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = kotlin.time.TimeSource.Monotonic.markNow()
        while (!condition()) {
            if (deadline.elapsedNow().inWholeMilliseconds > TIMEOUT_MS) error("等了 ${TIMEOUT_MS}ms 仍未发生：$what")
            delay(10)
        }
    }

    private class RecordingAdvertiser(private val result: Boolean = true) : MdnsAdvertiser {
        var startedWith: Pair<String, Int>? = null
        var stopped = false

        override suspend fun start(serviceName: String, port: Int): Boolean {
            startedWith = serviceName to port
            return result
        }

        override fun stop() {
            stopped = true
        }
    }

    /** 假浏览器：把回调留住，让测试自己决定"网络上发现了什么"。 */
    private class RecordingBrowser : MdnsBrowser {
        var browsing = false
        private var onFound: ((String, String, Int) -> Unit)? = null
        private var onLost: ((String) -> Unit)? = null

        override fun startBrowsing(
            onServiceFound: (String, String, Int) -> Unit,
            onServiceLost: (String) -> Unit,
        ) {
            browsing = true
            onFound = onServiceFound
            onLost = onServiceLost
        }

        override fun stopBrowsing() {
            browsing = false
            onFound = null
            onLost = null
        }

        fun emitFound(name: String, host: String, port: Int) {
            onFound?.invoke(name, host, port)
        }

        fun emitLost(name: String) {
            onLost?.invoke(name)
        }
    }

    private class MemorySettingsStore : SettingsStore {
        private var current: RelaySettings? = RelaySettings(
            nodeId = "abc12345-0000-4000-8000-000000000000",
            nick = "我的手机",
            udpPort = 0,
            tcpPort = 0,
        )

        override fun load(): RelaySettings? = current

        override fun save(settings: RelaySettings) {
            current = settings
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
