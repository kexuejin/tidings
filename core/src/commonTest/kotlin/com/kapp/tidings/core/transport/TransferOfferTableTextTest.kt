// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import com.kapp.tidings.core.protocol.Codec
import com.kapp.tidings.core.protocol.FileCtlOffer
import com.kapp.tidings.core.protocol.FilePurpose
import com.kapp.tidings.core.protocol.FileTransfer
import com.kapp.tidings.core.testing.createTempDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 表格图片的文字视图（`tbl1`）在**发送端**的两条规则。
 *
 * 一、字段要真的上线：`FileCtlOffer` 早就有 `tableText`/`tableTextTruncated`，收端也一路
 * 组装进了 `IncomingOffer`（`TransferOfferFieldsTest` 钉了那一步），但发端 `offerFiles`
 * 根本没有这个参数 —— 于是本客户端从来发不出带文字视图的表格图，界面上那个
 * 「看表格文字」开关永远没机会出现。**收端读得到 ≠ 发端写得出。**
 *
 * 二、超上限必须在**发送端**按 UTF-8 边界截断并置 `tableTextTruncated`：这是协议对
 * 这条字段的要求（`FileTransfer.TABLE_TEXT_LIMIT_BYTES` = 4096）。按字符数截在中文表格上
 * 会切出超限的帧，按字节硬切会切出半个汉字 —— 收端拿到的是一段乱码，比截断更难查。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferOfferTableTextTest {

    private val limit = FileTransfer.TABLE_TEXT_LIMIT_BYTES

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

    private suspend fun offerWith(scope: CoroutineScope, dir: String, sink: MutableList<JsonElement>, tableText: String?) {
        val manager = capturingManager(scope, dir, sink)
        assertNotNull(
            manager.offerFiles(
                peerId = "peer-node",
                files = listOf(LocalFile("/tmp/sheet.webp", "sheet.webp", 1024L)),
                purpose = FilePurpose.IMAGE,
                tableText = tableText,
            ),
        )
    }

    private fun rawOffer(payloads: List<JsonElement>): JsonObject {
        assertEquals(1, payloads.size, "一次 offerFiles 应当只发一帧")
        return payloads.single() as JsonObject
    }

    private fun decodedOffer(payloads: List<JsonElement>): FileCtlOffer =
        Codec.wireJson.decodeFromJsonElement(FileCtlOffer.serializer(), rawOffer(payloads))

    @Test
    fun tableTextUnderTheLimitGoesOutUnchangedAndUnflagged() = runTest {
        val payloads = mutableListOf<JsonElement>()
        // 制表符分隔、带中文列名 —— 这段文字存在的理由就是"列对得齐、数字能复制"。
        val tsv = "名称\t数量\t单价\n甲\t3\t12.50\n乙\t1\t8.00"
        offerWith(this, createTempDir("relay-tabletext-ok"), payloads, tsv)

        val offer = decodedOffer(payloads)
        assertEquals(tsv, offer.tableText)
        // null 而不是 false：没截过就不该在帧上多一个键（老端按 key 集合严格比对）。
        assertFalse("tableTextTruncated" in rawOffer(payloads))
    }

    @Test
    fun tableTextOverTheLimitIsCutOnACharacterBoundaryAndFlagged() = runTest {
        // 每个汉字 3 字节：1366 字 = 4098 字节 > 4096，1365 字 = 4095 字节 ≤ 4096。
        // 上限**不是** 3 的整数倍，所以"按字符数截"和"按字节截"在这里给出不同答案。
        val tooLong = "表".repeat(1366)
        assertEquals(4098, tooLong.encodeToByteArray().size)

        val payloads = mutableListOf<JsonElement>()
        offerWith(this, createTempDir("relay-tabletext-cut"), payloads, tooLong)

        val offer = decodedOffer(payloads)
        val sent = assertNotNull(offer.tableText, "截断后仍应带上文字，而不是整段丢掉")
        assertTrue(
            sent.encodeToByteArray().size <= limit,
            "上线的一定不能超过 $limit 字节，实际 ${sent.encodeToByteArray().size}",
        )
        assertEquals(1365, sent.length, "应当在字符边界上停住：1365 个整字 = 4095 字节")
        assertFalse(sent.contains('\uFFFD'), "不能切出半个汉字：解出来不该有替换字符")
        assertEquals("表".repeat(1365), sent)
        assertEquals(true, offer.tableTextTruncated, "截过就必须如实说截过")
    }

    @Test
    fun noTableTextMeansNeitherKeyIsOnTheWire() = runTest {
        val payloads = mutableListOf<JsonElement>()
        offerWith(this, createTempDir("relay-tabletext-none"), payloads, null)

        val raw = rawOffer(payloads)
        assertFalse("tableText" in raw, "没给文字视图就不该有这个键")
        assertFalse("tableTextTruncated" in raw, "同上")
    }

    @Test
    fun truncateTableTextLeavesShortTextAlone() {
        // 直接钉那个 helper 的边界：null、上限正好、超一字节三种输入。
        // 第二个值只有"真的截过"才是 true，其余是 null —— 没截断就不该在帧上多一个键，
        // 老端按 key 集合严格比对，多一个键整帧丢弃。
        assertEquals(null to null, truncateTableText(null))
        val exact = "x".repeat(limit)
        assertEquals(exact to null, truncateTableText(exact))
        val oneOver = "x".repeat(limit + 1)
        assertEquals("x".repeat(limit) to true, truncateTableText(oneOver))
    }
}
