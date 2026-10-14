// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

import app.relay.core.protocol.Codec
import app.relay.core.protocol.Envelope
import app.relay.core.protocol.FileCtlOffer
import app.relay.core.protocol.FileCtlOps
import app.relay.core.protocol.FileMeta
import app.relay.core.protocol.FilePurpose
import app.relay.core.protocol.MsgTypes
import app.relay.core.protocol.makeEnvelope
import app.relay.core.testing.createTempDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `file-ctl offer` 的**可选字段必须一路走到组装后的 offer**。
 *
 * 为什么值得单独立一个文件：线级模型 `FileCtlOffer` 早就有 `groupId` / `groupRev` /
 * `tableText` / `tableTextTruncated`，但 `TransferManager` 重组出 `IncomingOffer` 时把它们
 * 全丢了 —— 于是两个真实后果同时存在了很久：群图的 10MB 上限判据拿不到 `groupId`（恒按私聊
 * 20MB 走），`tbl1` 的表格文字永远到不了界面。**线级模型有的字段，不等于组装后的模型有。**
 *
 * 这条测试钉的是那一步透传：给一个不会被自动接收的群图 offer（15MB > 10MB），
 * 它必须停在"待接收"，而待接收的那个 `IncomingOffer` 上四个字段一个都不能少。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferOfferFieldsTest {

    private val mb = 1024L * 1024L

    private fun newManager(scope: CoroutineScope, dir: String) = TransferManager(
        selfId = "self-node",
        scope = scope,
        tcpPort = 0,
        downloadDir = { dir },
        sendReliable = { _, _: JsonElement -> true },
        peerAddress = { null },
        peerSupportsDirect = { false },
    )

    private fun tableImageOffer(): Envelope = makeEnvelope(
        type = MsgTypes.FILE_CTL,
        from = "peer-node",
        payload = Codec.wireJson.encodeToJsonElement(
            FileCtlOffer.serializer(),
            FileCtlOffer(
                op = FileCtlOps.OFFER,
                transferId = "t-table",
                seq = 1,
                total = 1,
                files = listOf(FileMeta(fileId = "file-1", path = "sheet.webp", size = 15L * mb)),
                totalSize = 15L * mb,
                fileCount = 1,
                rootName = "sheet.webp",
                purpose = FilePurpose.IMAGE,
                groupId = "g-1",
                groupRev = 7,
                tableText = "name\tqty\n甲\t3",
                tableTextTruncated = true,
            ),
        ),
    )

    @Test
    fun theOptionalOfferFieldsSurviveAssembly() = runTest {
        val manager = newManager(this, createTempDir("relay-offer-fields"))
        manager.onEnvelope(tableImageOffer())
        advanceUntilIdle()

        val offer = manager.pendingOffers().single()
        assertEquals("g-1", offer.groupId, "groupId 丢了，群图上限判据就会退回私聊的 20MB")
        assertEquals(7, offer.groupRev)
        assertEquals("name\tqty\n甲\t3", offer.tableText, "tableText 丢了，tbl1 的文字视图就永远是空的")
        assertEquals(true, offer.tableTextTruncated)
    }

    @Test
    fun anOfferWithoutTheOptionalFieldsLeavesThemNull() = runTest {
        val manager = newManager(this, createTempDir("relay-offer-fields-absent"))
        manager.onEnvelope(
            makeEnvelope(
                type = MsgTypes.FILE_CTL,
                from = "peer-node",
                payload = Codec.wireJson.encodeToJsonElement(
                    FileCtlOffer.serializer(),
                    FileCtlOffer(
                        op = FileCtlOps.OFFER,
                        transferId = "t-plain",
                        seq = 1,
                        total = 1,
                        files = listOf(FileMeta(fileId = "file-1", path = "a.bin", size = 1024L)),
                        totalSize = 1024L,
                        fileCount = 1,
                        rootName = "a.bin",
                    ),
                ),
            ),
        )
        advanceUntilIdle()

        val offer = manager.pendingOffers().single()
        assertNull(offer.groupId, "旧端不带 groupId：必须保持 null，而不是空串")
        assertNull(offer.tableText)
        assertNull(offer.tableTextTruncated)
    }
}
