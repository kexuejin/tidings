// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tenon.core.transport

import com.kapp.tenon.core.protocol.Codec
import com.kapp.tenon.core.protocol.FileCtlOffer
import com.kapp.tenon.core.protocol.FileCtlOps
import com.kapp.tenon.core.protocol.FileMeta
import com.kapp.tenon.core.protocol.FilePurpose
import com.kapp.tenon.core.protocol.MsgTypes
import com.kapp.tenon.core.protocol.makeEnvelope
import com.kapp.tenon.core.testing.createTempDir
import com.kapp.tenon.core.fs.pathJoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 第 1 步的接缝验收：`TransferManager` 的字节搬运走 [FileDataPlane]。
 *
 * 两条边界：
 *  - **不传 dataPlane ⇒ 今天的明文实现**（`LegacyTcpDataPlane`），行为必须一字不变；
 *  - 传入自定义 dataPlane ⇒ TransferManager 真的用它（不是摆设），参数（transferId /
 *    files / saveDir）原样传下去 —— 这是后续接加密信道的前提。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferDataPlaneSeamTest {

    private class RecordingDataPlane(
        private val fail: Boolean = false,
        private val landed: List<String> = emptyList(),
    ) : FileDataPlane {
        var calls = 0
        var lastOptions: PullOptions? = null
        override suspend fun pull(opts: PullOptions): List<String> {
            calls++
            lastOptions = opts
            if (fail) throw PullException("injected-failure", PullStage.Pull)
            return landed
        }
    }

    private fun newManager(
        scope: CoroutineScope,
        dir: String,
        dataPlane: FileDataPlane? = null,
    ) = TransferManager(
        selfId = "self-node",
        scope = scope,
        tcpPort = 0,
        downloadDir = { dir },
        sendReliable = { _, _: JsonElement -> true },
        // 必须给一个地址:pull() 在 peerAddress 为 null 时会直接报 peer-offline,
        // 根本走不到数据面 —— 那样这个用例测的就不是接缝,而是"对端离线"。
        peerAddress = { PeerEndpoint("127.0.0.1", 1) },
        peerSupportsDirect = { true },
        dataPlane = dataPlane,
    )

    private fun offer(transferId: String): com.kapp.tenon.core.protocol.Envelope = makeEnvelope(
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
                // 图片 + 上限内 ⇒ 自动接收,从而真的走到数据面(pendingOffers 会为空)。
                purpose = FilePurpose.IMAGE,
            ),
        ),
    )

    @Test
    fun autoAcceptedImageGoesThroughDefaultLegacyPlane() = runTest {
        val dir = createTempDir("relay-dp-default")
        val manager = newManager(this, dir)
        manager.onEnvelope(offer("t-default"))
        advanceUntilIdle()
        // 默认路径不抛异常地走完（没有真实对端，pull 会失败并报 Failed —— 但**不是崩溃**）。
        assertTrue(manager.pendingOffers().isEmpty(), "报价必须被处理掉，不能悬着")
    }

    @Test
    fun injectedPlaneReceivesPullOptionsForAutoAcceptedImage() = runTest {
        val dir = createTempDir("relay-dp-injected")
        val plane = RecordingDataPlane(fail = true)
        val manager = newManager(this, dir, plane)
        manager.onEnvelope(offer("t-injected"))
        // pull 在 scope.launch 里跑,runTest 的 advanceUntilIdle 会把它推到底。
        advanceUntilIdle()
        assertEquals(1, plane.calls, "注入的数据面必须被真的调用")
        val opts = plane.lastOptions
        assertTrue(opts != null && opts.transferId == "t-injected", "transferId 必须原样传下去")
        assertTrue(opts!!.files.isNotEmpty() && opts.saveDir.isNotEmpty(), "files/saveDir 必须传下去")
    }

    /**
     * `savedPaths` 必须原样带上数据面说的「真的落到哪」，不许自己用 `saveDir + relPath` 再拼。
     *
     * 现场（2026-11-10 真机）：文件确实在 `downloads/prototype(1).html`，界面点「打开」却报
     * 「文件不在了」—— 因为经理层重算出来的是**去重前**的名字。这里让数据面返回一个
     * 与 `relPath` 完全不同的路径：如果实现退了回去重算，事件里会是 `a.txt`，这条就红。
     */
    @Test
    fun savedPathsComeFromThePlaneNotRecomputedFromRelPath() = runTest {
        val dir = createTempDir("relay-dp-landed")
        val landed = pathJoin(dir, "prototype(1).html")
        val manager = newManager(this, dir, RecordingDataPlane(landed = listOf(landed)))
        val events = Channel<TransferEvent>(Channel.UNLIMITED)
        // UNDISPATCHED：先同步挂订阅，SharedFlow(replay=0) 否则直接丢事件。
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            manager.events.collect { events.send(it) }
        }
        try {
            manager.onEnvelope(offer("t-landed"))
            advanceUntilIdle()

            var completed: TransferEvent.Completed? = null
            while (true) {
                val event = events.tryReceive().getOrNull() ?: break
                if (event is TransferEvent.Completed && event.transferId == "t-landed") completed = event
            }
            assertEquals(
                listOf(landed),
                assertNotNull(completed, "收完必须发 Completed").savedPaths,
            )
        } finally {
            collector.cancel()
        }
    }
}
