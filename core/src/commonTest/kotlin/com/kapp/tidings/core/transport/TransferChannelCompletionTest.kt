// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

import com.kapp.tidings.core.crypto.Sha256
import com.kapp.tidings.core.protocol.Codec
import com.kapp.tidings.core.protocol.FileCtlOffer
import com.kapp.tidings.core.protocol.FileCtlOps
import com.kapp.tidings.core.protocol.FileMeta
import com.kapp.tidings.core.protocol.FilePurpose
import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.makeEnvelope
import com.kapp.tidings.core.testing.createTempDir
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 验证信道文件传输数据面收齐后向上层派发 [TransferEvent.Completed] 事件。
 *
 * 避免「文件已经收下落盘，但界面卡片因缺事件而停留在等你接收」。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferChannelCompletionTest {

    private fun shaOf(bytes: ByteArray): String {
        val s = Sha256()
        s.update(bytes)
        return s.hexDigest()
    }

    @Test
    fun channelInboundEmitsCompletedEventWhenFinished() = runTest {
        val dir = createTempDir("relay-channel-comp")
        val payload = ByteArray(1024) { (it % 100).toByte() }
        val sha = shaOf(payload)

        var registeredOffer: IncomingOffer? = null
        var completedCallback: ((String, String) -> Unit)? = null

        val manager = TransferManager(
            selfId = "self-node",
            scope = this,
            tcpPort = 0,
            downloadDir = { dir },
            sendReliable = { _, _: JsonElement -> true },
            peerAddress = { PeerEndpoint("127.0.0.1", 1) },
            peerSupportsDirect = { true },
            registerChannelInbound = { offer, saveDir, _, onCompleted, _ ->
                registeredOffer = offer
                completedCallback = onCompleted
                true
            },
        )

        val offerEnvelope = makeEnvelope(
            type = MsgTypes.FILE_CTL,
            from = "peer-node",
            payload = Codec.wireJson.encodeToJsonElement(
                FileCtlOffer.serializer(),
                FileCtlOffer(
                    op = FileCtlOps.OFFER,
                    transferId = "t-channel-1",
                    seq = 1,
                    total = 1,
                    files = listOf(FileMeta(fileId = "f1", path = "photo.png", size = payload.size.toLong())),
                    totalSize = payload.size.toLong(),
                    fileCount = 1,
                    rootName = "photo.png",
                    purpose = FilePurpose.IMAGE,
                ),
            ),
        )

        val events = mutableListOf<TransferEvent>()
        val job = launch {
            manager.events.collect { events.add(it) }
        }

        manager.onEnvelope(offerEnvelope)
        advanceUntilIdle()

        assertTrue(registeredOffer != null, "信道数据面必须被登记接管")
        assertEquals("t-channel-1", registeredOffer!!.transferId)

        // 模拟路由器收齐并转正了文件
        val savedPath = dir + "/photo.png/photo.png"
        completedCallback?.invoke("f1", savedPath)
        advanceUntilIdle()

        val completedEvent = events.filterIsInstance<TransferEvent.Completed>().firstOrNull()
        assertTrue(completedEvent != null, "收齐后必须发出 TransferEvent.Completed 事件")
        assertEquals("t-channel-1", completedEvent.transferId)
        assertTrue(completedEvent.incoming, "必须为入站标记")
        assertEquals(listOf(savedPath), completedEvent.savedPaths)

        job.cancel()
    }
}
