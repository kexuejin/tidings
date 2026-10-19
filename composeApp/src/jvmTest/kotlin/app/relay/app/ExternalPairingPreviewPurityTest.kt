// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import app.relay.platform.AppPlatform
import app.relay.platform.DataDirectoryProvider
import app.relay.platform.InMemoryAppPlatform
import app.relay.platform.MdnsAdvertiser
import app.relay.platform.MdnsBrowser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `previewExternalPairing` 的**只读性**（task-54 ③，lead 点名的断言）。
 *
 * 确认框要先知道"这条外部载荷会让本机与谁配对、会不会固定一枚密钥"，才谈得上让用户确认。
 * 那个预览因此必须在**任何落盘之前**就能回答 —— 它的 KDoc 写死了"只读、无副作用、不得落盘"，
 * 这条测试就是那句话的机器化版本：跑完真实控制器，`peer-pins.json` / 候选池 / 手动节点
 * 一个字节都不许变。
 *
 * 第二条是**正向对照**：同一条载荷一旦真的应用（`handleQrCode`），同样的快照方式必须能看见
 * 写入 —— 否则第一条只是"断言方式本身看不见任何东西"的空转。
 */
class ExternalPairingPreviewPurityTest {

    private lateinit var directory: File
    private lateinit var platform: AppPlatform
    private val scope = CoroutineScope(Job())

    @BeforeTest
    fun setUp() {
        directory = Files.createTempDirectory("relay-external-pairing-preview").toFile()
        platform = InMemoryAppPlatform(
            dataDirectory = object : DataDirectoryProvider {
                override fun dataDirectory(): String = directory.absolutePath
                override fun cacheDirectory(): String = File(directory, "cache").absolutePath
            },
            mdnsAdvertiser = NoopAdvertiser,
            mdnsBrowser = NoopBrowser,
        )
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        directory.deleteRecursively()
    }

    /**
     * 起一个**真实**控制器：设置用落盘实现（`settings.json` 是真文件），端口 0 = 系统分配
     * （避免撞上测试机上已占用的 17878）。
     */
    private fun seededController(): RelayController {
        File(directory, "settings.json").writeText(
            """{"nodeId":"node-self","udpPort":0,"tcpPort":0}""",
        )
        return RelayController(
            platform = platform,
            store = createSettingsStore(platform),
            scope = scope,
            version = "relay-test",
            messageStore = createMessageStore(platform),
        )
    }

    private fun fileSnapshot(relative: String): String? =
        File(directory, relative).takeIf { it.isFile }?.readText()

    @Test
    fun previewingAnExternalPayloadWritesNothing() = runBlocking {
        val controller = seededController()
        controller.start()
        try {
            val pinsBefore = fileSnapshot("stores/peer-pins.json")
            val endpointsBefore = fileSnapshot("stores/peer-endpoints.json")
            val settingsBefore = fileSnapshot("settings.json")
            val manualBefore = controller.state.value.settings.manualPeers

            val preview = controller.previewExternalPairing(PAYLOAD_WITH_PIN)
            assertNotNull(preview, "这条载荷必须能解析出预览")
            assertEquals("node-ext", preview.nodeId)
            assertTrue(preview.hasPin, "载荷里带 pin ⇒ 预览要如实说带")

            assertEquals(pinsBefore, fileSnapshot("stores/peer-pins.json"), "预览不得写 peer-pins.json")
            assertEquals(endpointsBefore, fileSnapshot("stores/peer-endpoints.json"), "预览不得写候选池")
            assertEquals(settingsBefore, fileSnapshot("settings.json"), "预览不得写 settings.json（手动节点在里面）")
            assertEquals(manualBefore, controller.state.value.settings.manualPeers, "预览不得改手动节点")
        } finally {
            controller.stop()
        }
    }

    @Test
    fun applyingTheSamePayloadDoesWriteProvingTheSnapshotsCanSeeWrites() = runBlocking {
        val controller = seededController()
        controller.start()
        try {
            val manualBefore = controller.state.value.settings.manualPeers
            val settingsBefore = fileSnapshot("settings.json")

            controller.handleQrCode(PAYLOAD_WITH_PIN)

            assertNotEquals(
                manualBefore,
                controller.state.value.settings.manualPeers,
                "正向对照：真的应用时必须写手动节点（否则第一条测试是空转）",
            )
            assertNotEquals(
                settingsBefore,
                fileSnapshot("settings.json"),
                "正向对照：真的应用时 settings.json 必须变",
            )
        } finally {
            controller.stop()
        }
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
        /** 合法 pin：`sha256:` + 64 位十六进制（`TlsPin.parse` 的形状）。 */
        val PAYLOAD_WITH_PIN =
            """{"type":"direct","pin":"sha256:${"ab".repeat(32)}","nodeId":"node-ext","nick":"外部节点","addrs":[{"ip":"192.168.1.77","port":17878}]}"""
    }
}
