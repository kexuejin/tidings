// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import app.relay.platform.CameraGate
import app.relay.platform.CameraUnavailableException
import app.relay.platform.FilePicker
import app.relay.platform.InMemoryAppPlatform
import app.relay.platform.PickedFile
import app.relay.platform.QrScanner
import app.relay.platform.SimpleHttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import app.relay.app.model.DeliveryState

/**
 * 扫码这条路上「什么算一次失败」的判定。
 *
 * 上一版用 `runCatching` 包住 `platform.qrScanner.scan()`，于是**取消也被当成了失败**：
 * 用户按关闭 / 系统返回键离开扫码页，控制器反而留下一条横幅
 * 「扫描失败：Job was cancelled」（英文内部串），还顺手吞掉 `CancellationException`、
 * 破坏结构化并发 —— 而同一段代码的 KDoc 恰好承诺"取消不留横幅"。
 *
 * 这类缺陷截图门禁与真机都看不见（它只在"人正离开这一页"的那一帧发生），
 * 只能靠一个假扫描器**真的挂起、真的被取消**来钉住。所以放在 `jvmTest`：
 * 要 `runBlocking` 真的跑一次协程取消。
 *
 * 后半段（`importQrImage`）钉的是扫码的另一半：从一张**已有的图片**里读码。它不碰相机，
 * 所以"相机被拒也能配对"这条性质必须由用例证明 —— 光看代码看不出它到底有没有偷偷去要权限。
 */
class RelayControllerScanQrTest {

    // ---- 取消不是失败 ----

    @Test
    fun cancellingTheScanLeavesNoBanner() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val controller = controller(
            qrScanner = scanner {
                entered.complete(Unit)
                // 扫不到就永远等着 —— 直到调用方的协程被取消。
                awaitCancellation()
            },
        )

        val job = launch { controller.scanQrCode() }
        entered.await()
        job.cancelAndJoin()

        assertTrue(job.isCancelled, "取消必须原样传出去，不能被 runCatching 吞掉")
        assertNull(
            controller.state.value.banner,
            "取消 = 用户离开了这一页，弹一句『扫描失败』是在编造一次失败",
        )
    }

    @Test
    fun aScanThatEndsWithoutAResultLeavesNoBanner() = runBlocking {
        // 契约里"这次扫描没拿到结果就结束了"就是 null，不是异常。
        val controller = controller(qrScanner = scanner { null })

        assertFalse(controller.scanQrCode())
        assertNull(controller.state.value.banner)
    }

    // ---- 真失败要带真实原因 ----

    @Test
    fun aPlatformFailureKeepsItsRealReason() = runBlocking {
        val controller = controller(
            qrScanner = scanner { throw IllegalStateException("camera is busy") },
        )

        assertFalse(controller.scanQrCode())
        assertEquals(
            BannerMessage.ScanFailed("camera is busy"),
            controller.state.value.banner?.message,
        )
    }

    @Test
    fun noCameraInputIsItsOwnBanner() = runBlocking {
        val controller = controller(
            qrScanner = scanner { throw CameraUnavailableException() },
        )

        assertFalse(controller.scanQrCode())
        // 与「权限被拒」分开：权限没问题、就是拿不到画面，出路也不同。
        assertEquals(BannerMessage.CameraUnavailable, controller.state.value.banner?.message)
    }

    @Test
    fun deniedPermissionSaysSoAndNeverTouchesTheCamera() = runBlocking {
        var scanned = false
        val controller = controller(
            cameraGate = object : CameraGate {
                override suspend fun ensureAccess(): Boolean = false
            },
            qrScanner = scanner {
                scanned = true
                null
            },
        )

        assertFalse(controller.scanQrCode())
        assertEquals(BannerMessage.CameraPermissionDenied, controller.state.value.banner?.message)
        assertFalse(scanned, "没授权就不该去开相机")
    }

    // ---- 扫到了真的加进节点表 ----

    @Test
    fun aScannedPayloadIsAddedAsAManualPeer() = runBlocking {
        val controller = controller(
            qrScanner = scanner { "relay://192.168.1.9:17878" },
        )

        assertTrue(controller.scanQrCode(), "配对成功必须回 true，扫码页据此关页")
        assertEquals(
            listOf(ManualPeerEntry("192.168.1.9", 17878)),
            controller.state.value.settings.manualPeers,
        )
    }

    @Test
    fun someoneElsesQrCodeIsRefusedWithItsOwnBanner() = runBlocking {
        val controller = controller(
            qrScanner = scanner { "https://example.com/not-ours" },
        )

        assertFalse(controller.scanQrCode())
        assertEquals(BannerMessage.InvalidQrCode, controller.state.value.banner?.message)
        assertTrue(controller.state.value.settings.manualPeers.isEmpty())
    }

    // ---- 从图片导入：扫码的另一半 ----

    @Test
    fun anImportedImageIsAddedAsAManualPeer() = runBlocking {
        val controller = controller(
            qrScanner = scanner(scanImage = { "relay://192.168.1.9:17878" }),
            filePicker = picking("/tmp/pair.png"),
        )

        assertTrue(controller.importQrImage(), "读到了就该和扫到一样：回 true、关页")
        assertEquals(
            listOf(ManualPeerEntry("192.168.1.9", 17878)),
            controller.state.value.settings.manualPeers,
        )
    }

    @Test
    fun importingAnImageNeverAsksForTheCamera() = runBlocking {
        // 这条是本功能存在的理由：相机被拒、或者压根没有相机的设备，照样要能把对方加进来。
        // 所以相机门做成"一碰就抛"，它只要被碰一下就红。
        val controller = controller(
            cameraGate = object : CameraGate {
                override suspend fun ensureAccess(): Boolean =
                    throw AssertionError("从图片读码不该碰相机权限")
            },
            qrScanner = scanner(scanImage = { "relay://192.168.1.9:17878" }),
            filePicker = picking("/tmp/pair.png"),
        )

        assertTrue(controller.importQrImage())
    }

    @Test
    fun anImageWithoutACodeBlamesTheImageNotTheCode() = runBlocking {
        // 与「码不是我们的格式」分开：那句会让用户去怀疑码，而这里该做的是换一张图。
        val controller = controller(
            qrScanner = scanner(scanImage = { null }),
            filePicker = picking("/tmp/not-a-qr.png"),
        )

        assertFalse(controller.importQrImage())
        assertEquals(BannerMessage.NoQrInImage, controller.state.value.banner?.message)
        assertTrue(controller.state.value.settings.manualPeers.isEmpty())
    }

    @Test
    fun anImageWithSomeoneElsesCodeIsStillAnInvalidCode() = runBlocking {
        val controller = controller(
            qrScanner = scanner(scanImage = { "https://example.com/not-ours" }),
            filePicker = picking("/tmp/not-ours.png"),
        )

        assertFalse(controller.importQrImage())
        assertEquals(BannerMessage.InvalidQrCode, controller.state.value.banner?.message)
    }

    @Test
    fun cancellingThePickerLeavesNoBanner() = runBlocking {
        // 用户在系统选图器里按了返回：那不是一次失败。
        val controller = controller(
            qrScanner = scanner(scanImage = { error("不该走到这里：用户已经取消了") }),
            filePicker = object : FilePicker {
                override suspend fun pick(): PickedFile? = null
            },
        )

        assertFalse(controller.importQrImage())
        assertNull(controller.state.value.banner)
    }

    @Test
    fun cancellingTheImportLeavesNoBanner() = runBlocking {
        // 与 scanQrCode 同一条规矩：取消要原样抛出去，不能变成"导入失败"。
        val entered = CompletableDeferred<Unit>()
        val controller = controller(
            qrScanner = scanner(
                scanImage = {
                    entered.complete(Unit)
                    awaitCancellation()
                },
            ),
            filePicker = picking("/tmp/pair.png"),
        )

        val job = launch { controller.importQrImage() }
        entered.await()
        job.cancelAndJoin()

        assertTrue(job.isCancelled, "取消必须原样传出去")
        assertNull(controller.state.value.banner, "取消不是失败，不该留横幅")
    }

    @Test
    fun aDecoderFailureKeepsItsRealReason() = runBlocking {
        val controller = controller(
            qrScanner = scanner(scanImage = { throw IllegalStateException("decoder is busy") }),
            filePicker = picking("/tmp/pair.png"),
        )

        assertFalse(controller.importQrImage())
        assertEquals(
            BannerMessage.ScanFailed("decoder is busy"),
            controller.state.value.banner?.message,
        )
    }

    @Test
    fun aPickerFailureIsReportedAsAPickFailure() = runBlocking {
        // 选图失败与读码失败是两件事，报错时也不能混：前者是"没拿到文件"，后者是"文件里没码"。
        val controller = controller(
            qrScanner = scanner(scanImage = { error("文件都没拿到，不该去读码") }),
            filePicker = object : FilePicker {
                override suspend fun pick(): PickedFile? =
                    throw IllegalStateException("no such activity")
            },
        )

        assertFalse(controller.importQrImage())
        assertEquals(
            BannerMessage.PickFailed("no such activity"),
            controller.state.value.banner?.message,
        )
    }

    // ---- 远程中继配对与通信 ----

    @Test
    fun scanningRemoteRelayQrConfiguresSettingsAndShowsBanner() = runBlocking {
        val qrPayload = """{"type":"relay","url":"https://openai.auroramedia.icu/relay","token":"tok_secret","nodeId":"agent-srv","nick":"ServerAgent"}"""
        val controller = controller(
            qrScanner = scanner { qrPayload },
        )

        assertTrue(controller.scanQrCode())
        val remoteConfig = controller.state.value.settings.remoteRelay
        assertEquals(
            RemoteRelayConfig(
                url = "https://openai.auroramedia.icu/relay",
                token = "tok_secret",
                nodeId = "agent-srv",
                nick = "ServerAgent",
                enabled = true,
            ),
            remoteConfig,
        )
        assertEquals(
            BannerMessage.RemoteRelayConfigured("ServerAgent"),
            controller.state.value.banner?.message,
        )
    }

    @Test
    fun importingRemoteRelayImageConfiguresSettings() = runBlocking {
        val qrPayload = "relay-pair://v1?id=agent-srv&nick=ServerAgent&relay=https://openai.auroramedia.icu/relay&token=tok_secret&lan=192.168.1.100:17878"
        val controller = controller(
            qrScanner = scanner(scanImage = { qrPayload }),
            filePicker = picking("/tmp/remote-relay-qr.png"),
        )

        assertTrue(controller.importQrImage())
        val remoteConfig = controller.state.value.settings.remoteRelay
        assertEquals(
            RemoteRelayConfig(
                url = "https://openai.auroramedia.icu/relay",
                token = "tok_secret",
                nodeId = "agent-srv",
                nick = "ServerAgent",
                enabled = true,
            ),
            remoteConfig,
        )
        assertEquals(
            listOf(ManualPeerEntry("192.168.1.100", 17878)),
            controller.state.value.settings.manualPeers,
        )
        assertEquals(
            BannerMessage.RemoteRelayConfigured("ServerAgent"),
            controller.state.value.banner?.message,
        )
    }

    @Test
    fun clearRemoteRelayRemovesConfigAndShowsBanner() = runBlocking {
        val controller = controller(qrScanner = scanner { null })
        controller.setRemoteRelay(
            url = "https://relay.example.com",
            token = "tok",
            nodeId = "n1",
            nick = "agent1",
        )
        assertEquals("https://relay.example.com", controller.state.value.settings.remoteRelay?.url)
        controller.clearRemoteRelay()
        assertNull(controller.state.value.settings.remoteRelay)
        assertEquals(BannerMessage.RemoteRelayCleared, controller.state.value.banner?.message)
    }

    @Test
    fun sendToRemotePeerUsesRemoteRelayClientWhenNoLocalNode() = runBlocking {
        val platform = InMemoryAppPlatform()
        var postUrl: String? = null
        var postBody: String? = null
        platform.httpClient.handler = { method, url, body, _ ->
            if (method == "POST" && url.endsWith("/v1/send")) {
                postUrl = url
                postBody = body
                SimpleHttpResponse(200, """{"ok":true}""")
            } else {
                SimpleHttpResponse(200, "{}")
            }
        }
        val controller = RelayController(
            platform = platform,
            store = ScanMemorySettingsStore(),
            scope = this,
            version = "relay-test",
        )
        controller.setRemoteRelay(
            url = "https://relay.example.com",
            token = "secret-token",
            nodeId = "remote-node-1",
            nick = "RemoteAgent",
        )
        controller.send("remote-node-1", "Hello Remote Agent")
        kotlinx.coroutines.yield()
        assertEquals("https://relay.example.com/v1/send", postUrl)
        assertTrue(postBody?.contains("Hello Remote Agent") == true)
        val msgs = controller.state.value.messages["remote-node-1"]
        assertEquals(1, msgs?.size)
        assertEquals("Hello Remote Agent", msgs?.first()?.text)
        assertEquals(DeliveryState.Sent, msgs?.first()?.delivery)
    }

    // ---- 装置 ----

    /**
     * 一个假的二维码读取器。
     *
     * 两个方向各给一个默认值，用例只写自己关心的那一半 —— 但**没有默认的"成功"**：
     * 不传就是"读不到"（`null`），这样漏写一个参数的用例不会悄悄变成一条通过的假证据。
     *
     * 参数顺序是**故意**的：`scan` 在最后，所以 `scanner { "relay://…" }` 那种尾随 lambda
     * 写的是相机那一半。反过来写的话尾随 lambda 会绑到 `scanImage` 上，
     * 于是"扫到了"的用例其实一次相机都没碰（踩过：两条用例就是这么红的）。
     */
    private fun scanner(
        scanImage: suspend (String) -> String? = { null },
        scan: suspend () -> String? = { null },
    ): QrScanner = object : QrScanner {
        override suspend fun scan(): String? = scan()

        override suspend fun scanImage(path: String): String? = scanImage(path)
    }

    /** 用户选中了某个文件。 */
    private fun picking(path: String): FilePicker = object : FilePicker {
        override suspend fun pick(): PickedFile = PickedFile(
            name = path.substringAfterLast('/'),
            sizeBytes = 1L,
            path = path,
        )
    }

    private fun CoroutineScope.controller(
        qrScanner: QrScanner,
        cameraGate: CameraGate = object : CameraGate {
            override suspend fun ensureAccess(): Boolean = true
        },
        filePicker: FilePicker = object : FilePicker {
            override suspend fun pick(): PickedFile? = null
        },
    ): RelayController = RelayController(
        platform = InMemoryAppPlatform(
            qrScanner = qrScanner,
            cameraGate = cameraGate,
            filePicker = filePicker,
        ),
        store = ScanMemorySettingsStore(),
        scope = this,
        version = "relay-test",
    )

    private class ScanMemorySettingsStore : SettingsStore {
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
}
