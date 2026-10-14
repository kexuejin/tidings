// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

import app.relay.core.protocol.Codec
import app.relay.core.protocol.Envelope
import app.relay.core.protocol.FileCtlOffer
import app.relay.core.protocol.FileCtlOps
import app.relay.core.protocol.FileCtlSimple
import app.relay.core.protocol.FileMeta
import app.relay.core.protocol.MsgTypes
import app.relay.core.protocol.makeEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `file-ctl direct` 的两个方向。
 *
 * 为什么值得单独立一个文件：这条 op 在真机上比"正确路径"更容易走到，而它在两处都可能出错 ——
 * **发送侧**漏发（于是桌面端明明开着「允许同事直接发送文件」却还是要人工点一次接收），
 * **接收侧**错接（`direct` 原本和 `decline` 共用一个分支，于是"请求免确认"被当成"对方拒收"，
 * 邀请被删掉、用户看到的是"对方拒收了"）。
 *
 * 两条都不需要网络：`TransferManager` 的外部依赖（落盘目录 / 发帧 / 查地址 / 对端能力）
 * 本来就是注入的，这里注入记录用的假实现即可 —— 断言的是**我们发了什么 op、有没有误删**。
 *
 * `advanceUntilIdle` 仍是 `@ExperimentalCoroutinesApi`，所以整个类显式 opt-in，
 * 而不是让编译器每次编译都报 8 条警告。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferDirectTest {

    // ---------------------------------------------------------------- 工具

    private fun newManager(
        scope: CoroutineScope,
        sent: MutableList<JsonObject>,
        supportsDirect: Boolean,
    ): TransferManager = TransferManager(
        selfId = "self-node",
        scope = scope,
        tcpPort = 0,
        downloadDir = { "/tmp/relay-test-downloads" },
        // 记下每条出站 `file-ctl`，并声明"发出去了"。
        sendReliable = { _, payload: JsonElement ->
            sent += payload as JsonObject
            true
        },
        peerAddress = { null },
        peerSupportsDirect = { supportsDirect },
    )

    private fun JsonObject.op(): String = (this["op"] as? JsonPrimitive)?.content ?: "?"

    private fun oneFile() = listOf(LocalFile(path = "/tmp/a.bin", name = "a.bin", size = 3L))

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
                files = listOf(FileMeta(fileId = "file-1", path = "a.bin", size = 3L)),
                totalSize = 3L,
                fileCount = 1,
                rootName = "a.bin",
            ),
        ),
    )

    private fun simpleEnvelope(op: String, transferId: String): Envelope = makeEnvelope(
        type = MsgTypes.FILE_CTL,
        from = "peer-node",
        payload = Codec.wireJson.encodeToJsonElement(
            FileCtlSimple.serializer(),
            FileCtlSimple(op = op, transferId = transferId),
        ),
    )

    // ---------------------------------------------------------------- 发送侧

    @Test
    fun directRequestFollowsTheOfferWhenPeerAdvertisesFd1() = runTest {
        val sent = mutableListOf<JsonObject>()
        val manager = newManager(this, sent, supportsDirect = true)

        val transferId = assertNotNull(manager.offerFiles("peer-node", oneFile()))

        // offer 在前、direct 在后：桌面端 onDirectRequest 要求那条 transfer 已经存在。
        assertEquals(listOf(FileCtlOps.OFFER, FileCtlOps.DIRECT), sent.map { it.op() })
        // 两条必须挂在同一个 transferId 上，否则请求的是一条不存在的传输。
        assertEquals(transferId, (sent[0]["transferId"] as JsonPrimitive).content)
        assertEquals(transferId, (sent[1]["transferId"] as JsonPrimitive).content)

        advanceUntilIdle() // 放掉兜底重发那条定时任务，别让它留在测试作用域里
    }

    @Test
    fun noDirectRequestWhenPeerDoesNotAdvertiseFd1() = runTest {
        val sent = mutableListOf<JsonObject>()
        val manager = newManager(this, sent, supportsDirect = false)

        assertNotNull(manager.offerFiles("peer-node", oneFile()))
        advanceUntilIdle()

        // 对方没声明 `fd1`（桌面端把「允许同事直接发送文件」关掉了）就一条都不发，
        // 让它照常问自己的用户 —— 这个决定权在收端，不在我们。
        assertEquals(listOf(FileCtlOps.OFFER), sent.map { it.op() })
    }

    // ---------------------------------------------------------------- 接收侧

    @Test
    fun inboundDirectKeepsTheOfferPendingInsteadOfDroppingIt() = runTest {
        val sent = mutableListOf<JsonObject>()
        val manager = newManager(this, sent, supportsDirect = false)

        manager.onEnvelope(offerEnvelope("t-1"))
        advanceUntilIdle()
        assertEquals(1, manager.pendingOffers().size, "前提：offer 应当先挂成待接收")

        // 这就是那个真缺陷的现场：以前这一条会让 pendingOffers() 变空。
        manager.onEnvelope(simpleEnvelope(FileCtlOps.DIRECT, "t-1"))
        advanceUntilIdle()

        assertEquals(1, manager.pendingOffers().size, "direct 是请求，不是拒收，邀请必须还在")
    }

    @Test
    fun inboundDeclineStillDropsTheOffer() = runTest {
        // 回归护栏：把 direct 从 decline 那条分支拆出去之后，真正的 decline 不能被一起改坏。
        val sent = mutableListOf<JsonObject>()
        val manager = newManager(this, sent, supportsDirect = false)

        manager.onEnvelope(offerEnvelope("t-2"))
        advanceUntilIdle()
        manager.onEnvelope(simpleEnvelope(FileCtlOps.DECLINE, "t-2"))
        advanceUntilIdle()

        assertTrue(manager.pendingOffers().isEmpty(), "decline 之后邀请应当消失")
    }

    // ---------------------------------------------------------------- 兜底重发

    @Test
    fun directIsResentOnceWhenNobodyAcceptedIt() = runTest {
        val sent = mutableListOf<JsonObject>()
        val manager = newManager(this, sent, supportsDirect = true)

        assertNotNull(manager.offerFiles("peer-node", oneFile()))
        advanceUntilIdle()

        // 桌面端可能"先回 ACK、再建 transfer"，第一发因此落空；两秒后补一发。
        assertEquals(
            listOf(FileCtlOps.OFFER, FileCtlOps.DIRECT, FileCtlOps.DIRECT),
            sent.map { it.op() },
        )
    }

    @Test
    fun noResendAfterThePeerAccepted() = runTest {
        val sent = mutableListOf<JsonObject>()
        val manager = newManager(this, sent, supportsDirect = true)

        val transferId = assertNotNull(manager.offerFiles("peer-node", oneFile()))
        manager.onEnvelope(simpleEnvelope(FileCtlOps.ACCEPT, transferId))
        advanceUntilIdle()

        assertEquals(listOf(FileCtlOps.OFFER, FileCtlOps.DIRECT), sent.map { it.op() })
    }
}
