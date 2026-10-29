// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

import com.kapp.tidings.core.protocol.Codec
import com.kapp.tidings.core.protocol.Envelope
import com.kapp.tidings.core.protocol.FileCtlOffer
import com.kapp.tidings.core.protocol.FileCtlOps
import com.kapp.tidings.core.protocol.FileMeta
import com.kapp.tidings.core.protocol.FilePurpose
import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.makeEnvelope
import com.kapp.tidings.core.testing.createTempDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 信道入站的 **stall 看门狗**：caps 声明与现实不符时（对端降级回 legacy，本端还拿着
 * 它声明过 fch1 的旧资料），登记上信道的收器一个字节都等不到，两端互等 —— 文件永远
 * 不来（真机死锁现场：桌面日志 `accept-in … push=false`、手机日志
 * `accept … channel=true`，桌面→手机图片永远停在「等你接收」）。
 *
 * 钉住的契约：
 * 1. 信道接管后窗口内 **零字节** ⇒ 摘掉信道登记、回退 legacy 拨号拉取（数据面被调用）；
 * 2. 窗口内来过字节（哪怕探针只报"有"）⇒ 继续等信道，**不**回退；
 * 3. 宿主没注入探针（无路由器的宿主/旧测试）⇒ 看门狗整条不启用，行为与从前一致。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferChannelStallFallbackTest {

    private class RecordingDataPlane : FileDataPlane {
        var calls = 0
        override suspend fun pull(opts: PullOptions) {
            calls++
        }
    }

    private fun offerEnvelope(transferId: String): Envelope = makeEnvelope(
        type = MsgTypes.FILE_CTL,
        from = "peer-node",
        payload = Codec.wireJson.encodeToJsonElement(
            FileCtlOffer.serializer(),
            FileCtlOffer(
                op = FileCtlOps.OFFER,
                transferId = transferId,
                seq = 1,
                total = 1,
                files = listOf(FileMeta(fileId = "f1", path = "a.txt", size = 9)),
                totalSize = 9,
                fileCount = 1,
                rootName = "a.txt",
                // share-get ⇒ 手动接收路径（accept()），与真机上点「接收」那条一致。
                purpose = FilePurpose.SHARE_GET,
            ),
        ),
    )

    private fun newManager(
        scope: CoroutineScope,
        dir: String,
        dataPlane: FileDataPlane,
        hasBytes: (suspend (String) -> Boolean)?,
        stallMs: Long,
        forgotten: MutableList<String>,
    ) = TransferManager(
        selfId = "self-node",
        scope = scope,
        tcpPort = 0,
        downloadDir = { dir },
        sendReliable = { _, _: JsonElement -> true },
        peerAddress = { PeerEndpoint("127.0.0.1", 1) },
        peerSupportsDirect = { true },
        dataPlane = dataPlane,
        registerChannelInbound = { _, _, _, _, _ -> true },
        channelInboundHasBytes = hasBytes,
        channelForgetInbound = { forgotten.add(it) },
        channelStallFallbackMs = stallMs,
    )

    @Test
    fun stalledChannelFallsBackToLegacyPull() = runTest {
        val dir = createTempDir("relay-stall-fallback")
        val dataPlane = RecordingDataPlane()
        val forgotten = mutableListOf<String>()
        val manager = newManager(
            scope = this,
            dir = dir,
            dataPlane = dataPlane,
            hasBytes = { false },
            stallMs = 1_000L,
            forgotten = forgotten,
        )
        manager.onEnvelope(offerEnvelope("t-stall-1"))
        runCurrent()
        manager.accept("t-stall-1")
        runCurrent()
        assertEquals(0, dataPlane.calls, "接管后不该立刻走 legacy")
        assertEquals(0, forgotten.size, "没 stall 之前不许摘信道登记")

        advanceTimeBy(1_500L)
        runCurrent()

        assertEquals(1, dataPlane.calls, "窗口内零字节 ⇒ 必须回退 legacy 拨号拉取")
        assertEquals(listOf("t-stall-1"), forgotten, "回退前必须先摘信道登记（防双写）")
    }

    @Test
    fun channelWithBytesDoesNotFallBack() = runTest {
        val dir = createTempDir("relay-stall-live")
        val dataPlane = RecordingDataPlane()
        val forgotten = mutableListOf<String>()
        val manager = newManager(
            scope = this,
            dir = dir,
            dataPlane = dataPlane,
            hasBytes = { true },
            stallMs = 1_000L,
            forgotten = forgotten,
        )
        manager.onEnvelope(offerEnvelope("t-live-1"))
        runCurrent()
        manager.accept("t-live-1")
        runCurrent()
        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals(0, dataPlane.calls, "信道有字节 ⇒ 继续等信道，不回退")
        assertTrue(forgotten.isEmpty(), "不回退就不该摘登记")
    }

    @Test
    fun noProbeMeansNoWatchdog() = runTest {
        val dir = createTempDir("relay-stall-noprobe")
        val dataPlane = RecordingDataPlane()
        val manager = newManager(
            scope = this,
            dir = dir,
            dataPlane = dataPlane,
            hasBytes = null,
            stallMs = 1_000L,
            forgotten = mutableListOf(),
        )
        val events = mutableListOf<TransferEvent>()
        val job = launch { manager.events.collect { events.add(it) } }
        manager.onEnvelope(offerEnvelope("t-noprobe-1"))
        runCurrent()
        manager.accept("t-noprobe-1")
        runCurrent()
        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals(0, dataPlane.calls, "没有探针 ⇒ 看门狗不启用（宿主自行负责）")
        assertTrue(events.any { it is TransferEvent.Offered }, "卡片照常报 Offered")
        job.cancel()
    }
}
