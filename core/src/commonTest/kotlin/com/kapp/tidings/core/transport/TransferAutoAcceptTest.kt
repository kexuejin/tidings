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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 图片自动接收的**两条上限**：私聊 20MB、群聊 10MB。
 *
 * 为什么值得单独立一个文件：`GROUP_IMG_AUTO_ACCEPT` 在仓库里存在了很久，却**没有任何读取点**
 * —— 判据恒用 20MB，于是群里的 15MB 图会被自动收下，而发送端按协议根本没把它标成免确认图片
 * （群聊图片只在上限内才带 `purpose:"image"`，超限走普通文件、要手动领取）。一个没人读的常量
 * 不是"以后会用"，是"这条规则今天不存在"。
 *
 * 判据用 `file-ctl offer` 自带的 `groupId`：协议 §3 说它表示"该 transfer 是群聊媒体的一次
 * 点对点投递" —— 这是收端唯一能分辨群聊与私聊的地方。
 *
 * 这里钉的是**阈值行为**，不是实现：超限的图必须停在"待接收"，上限以内的直接收下。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferAutoAcceptTest {

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

    private fun imageOffer(transferId: String, size: Long, groupId: String? = null): Envelope = makeEnvelope(
        type = MsgTypes.FILE_CTL,
        from = "peer-node",
        payload = Codec.wireJson.encodeToJsonElement(
            FileCtlOffer.serializer(),
            FileCtlOffer(
                op = FileCtlOps.OFFER,
                transferId = transferId,
                seq = 1,
                total = 1,
                files = listOf(FileMeta(fileId = "file-1", path = "photo.webp", size = size)),
                totalSize = size,
                fileCount = 1,
                rootName = "photo.webp",
                purpose = FilePurpose.IMAGE,
                groupId = groupId,
                groupRev = if (groupId == null) null else 1,
            ),
        ),
    )

    @Test
    fun aPrivateImageInsideTwentyMegabytesIsTakenWithoutAsking() = runTest {
        val manager = newManager(this, createTempDir("relay-autoaccept-private"))
        manager.onEnvelope(imageOffer("t-private", 15L * mb))
        advanceUntilIdle()
        assertTrue(manager.pendingOffers().isEmpty(), "15MB 私聊图片在 20MB 以内，应当直接收下")
    }

    @Test
    fun thePrivateLimitIsInclusiveAtItsBoundary() = runTest {
        val manager = newManager(this, createTempDir("relay-autoaccept-private-edge"))
        manager.onEnvelope(imageOffer("t-private-edge", 20L * mb))
        advanceUntilIdle()
        assertTrue(manager.pendingOffers().isEmpty(), "正好 20MB 仍算在阈值内（`<=`）")
    }

    @Test
    fun aPrivateImageOverTheLimitWaitsForTheUser() = runTest {
        val manager = newManager(this, createTempDir("relay-autoaccept-private-big"))
        manager.onEnvelope(imageOffer("t-private-big", 21L * mb))
        advanceUntilIdle()
        assertEquals(1, manager.pendingOffers().size, "超限图片退化成普通文件，要用户点头")
    }

    /**
     * 回归：群图的上限是 10MB，不是 20MB。
     *
     * 这条以前会挂 —— 判据只读 `IMG_AUTO_ACCEPT`，15MB 的群图被自动收下。
     */
    @Test
    fun aGroupImageOverTenMegabytesWaitsForTheUser() = runTest {
        val manager = newManager(this, createTempDir("relay-autoaccept-group-big"))
        manager.onEnvelope(imageOffer("t-group-big", 15L * mb, groupId = "g-1"))
        advanceUntilIdle()
        assertEquals(
            1,
            manager.pendingOffers().size,
            "群聊图片超过 10MB 按普通文件投递，收端要手动领取",
        )
    }

    @Test
    fun aGroupImageInsideTenMegabytesIsTakenWithoutAsking() = runTest {
        val manager = newManager(this, createTempDir("relay-autoaccept-group"))
        manager.onEnvelope(imageOffer("t-group", 9L * mb, groupId = "g-1"))
        advanceUntilIdle()
        assertTrue(manager.pendingOffers().isEmpty(), "9MB 群图仍在群聊上限以内")
    }

    @Test
    fun theGroupLimitIsInclusiveAtItsBoundary() = runTest {
        val manager = newManager(this, createTempDir("relay-autoaccept-group-edge"))
        manager.onEnvelope(imageOffer("t-group-edge", 10L * mb, groupId = "g-1"))
        advanceUntilIdle()
        assertTrue(manager.pendingOffers().isEmpty(), "正好 10MB 仍算在阈值内（`<=`）")
    }
}
