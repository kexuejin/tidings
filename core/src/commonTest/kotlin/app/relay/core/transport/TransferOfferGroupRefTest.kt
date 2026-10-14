// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

import app.relay.core.protocol.Codec
import app.relay.core.protocol.FileCtlOffer
import app.relay.core.protocol.FilePurpose
import app.relay.core.testing.createTempDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **发送端**的群引用：`offerFiles` 的 `groupId`/`groupRev` 必须真的上线。
 *
 * 为什么单独立一个文件：收端一直读得到 `groupId`（`IncomingOffer` 有这两个字段，
 * `TransferAutoAcceptTest` 也钉了 10MB 那条阈值），但发端恒发 null —— 于是本客户端
 * **永远发不出一条协议认可的群图**：一张带群 id 的图，收端会当成私聊图，20MB 以内直接
 * 自动收下。收端规则正确 ≠ 发端会按规则标注。
 *
 * 另一条同样重要：不传群引用时，帧里**不能出现** `groupId`/`groupRev` 这两个键。
 * 老端按 `hasOnlyKeys` 严格比对 key 集合，多一个键整帧丢弃 —— 这条靠
 * `Codec.wireJson` 的 `encodeDefaults = false` + `explicitNulls = false` 成立，
 * 但它很容易被一次「顺手改成显式 null」破坏，所以钉住。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferOfferGroupRefTest {

    /** 抓住 `sendReliable` 收到的 payload —— 这就是线上那一帧。 */
    private fun capturingManager(scope: CoroutineScope, dir: String, sink: MutableList<JsonElement>) =
        TransferManager(
            selfId = "self-node",
            scope = scope,
            tcpPort = 0,
            downloadDir = { dir },
            sendReliable = { _, payload: JsonElement -> sink += payload; true },
            peerAddress = { null },
            peerSupportsDirect = { false },
        )

    private fun offerIn(payloads: List<JsonElement>): FileCtlOffer {
        assertEquals(1, payloads.size, "一次 offerFiles 应当只发一帧（一个文件）")
        val obj = payloads.single()
        assertTrue(obj is JsonObject, "file-ctl 的 payload 应当是个对象，实际是 $obj")
        return Codec.wireJson.decodeFromJsonElement(FileCtlOffer.serializer(), obj)
    }

    @Test
    fun aGroupReferenceOnTheOfferActuallyReachesTheWire() = runTest {
        val payloads = mutableListOf<JsonElement>()
        val manager = capturingManager(this, createTempDir("relay-groupref-send"), payloads)

        val transferId = manager.offerFiles(
            peerId = "peer-node",
            files = listOf(LocalFile("/tmp/photo.webp", "photo.webp", 15L * 1024 * 1024)),
            purpose = FilePurpose.IMAGE,
            groupId = "g-1",
            groupRev = 7,
        )

        assertNotNull(transferId, "报价应当发出去")
        val offer = offerIn(payloads)
        assertEquals(transferId, offer.transferId)
        assertEquals("g-1", offer.groupId, "群 id 没上线，收端只会当成私聊图")
        assertEquals(7, offer.groupRev)
        assertEquals(FilePurpose.IMAGE, offer.purpose)

        // 光看解析后的对象还不够：键必须真的在 JSON 里（解析器会把缺失的默认值补成 null）。
        val raw = payloads.single() as JsonObject
        assertEquals("g-1", raw["groupId"]?.jsonPrimitive?.content)
        assertEquals("7", raw["groupRev"]?.jsonPrimitive?.content)
    }

    @Test
    fun anOfferWithoutAGroupReferenceDoesNotCarryTheKeys() = runTest {
        val payloads = mutableListOf<JsonElement>()
        val manager = capturingManager(this, createTempDir("relay-groupref-none"), payloads)

        val transferId = manager.offerFiles(
            peerId = "peer-node",
            files = listOf(LocalFile("/tmp/photo.webp", "photo.webp", 15L * 1024 * 1024)),
            purpose = FilePurpose.IMAGE,
        )

        assertNotNull(transferId)
        val raw = payloads.single() as JsonObject
        assertFalse("groupId" in raw, "没传群引用就不该有这个键：老端按 key 集合严格比对，多一个键整帧丢弃")
        assertFalse("groupRev" in raw, "同上")
        // 私聊图这条路径本身没被动过。
        assertEquals(FilePurpose.IMAGE, offerIn(payloads).purpose)
    }
}
