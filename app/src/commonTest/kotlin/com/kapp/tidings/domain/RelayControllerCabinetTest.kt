// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.domain

import com.kapp.tidings.core.nowMillis
import com.kapp.tidings.core.protocol.Codec
import com.kapp.tidings.core.protocol.FilePurpose
import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.ShareDenyReasons
import com.kapp.tidings.core.protocol.ShareEntry
import com.kapp.tidings.core.protocol.makeEnvelope
import com.kapp.tidings.core.transport.IncomingFilePlan
import com.kapp.tidings.core.transport.IncomingOffer
import com.kapp.tidings.core.transport.TransferEvent
import com.kapp.tidings.platform.InMemoryAppPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RelayControllerCabinetTest {
    private val scope = CoroutineScope(Job())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun controller() = RelayController(
        platform = InMemoryAppPlatform(),
        store = InMemorySettingsStore(),
        scope = scope,
    )

    @Test
    fun testShareSettingsAndGrants() {
        val controller = controller()
        controller.setShareRoot("/tmp/shared")
        assertEquals("/tmp/shared", controller.state.value.settings.shareRoot)
        assertTrue(controller.state.value.settings.isShareEnabled())

        controller.setShareMode("write")
        assertEquals("write", controller.state.value.settings.shareMode)

        controller.setShareGrant("peer-1", "read")
        assertEquals(1, controller.state.value.settings.shareGrants.size)
        assertEquals("peer-1", controller.state.value.settings.shareGrants.first().nodeId)
        assertEquals("read", controller.state.value.settings.shareGrants.first().mode)

        controller.removeShareGrant("peer-1")
        assertTrue(controller.state.value.settings.shareGrants.isEmpty())

        controller.setShareRoot(null)
        assertEquals(null, controller.state.value.settings.shareRoot)
    }

    @Test
    fun testSelectCabinetPeerWithoutRuntimeShowsErrorNotMockData() {
        val controller = controller()
        controller.selectCabinetPeer("peer-42")
        assertEquals("peer-42", controller.state.value.cabinet.activePeerId)
        assertEquals("", controller.state.value.cabinet.currentPath)
        // 节点未启动 = error 态 + 零条目。这一分支过去填的是编造的演示数据
        // （docs/、contract-v2.pdf…、totalEntries=1280）—— 界面上是一个根本不存在的目录。
        assertTrue(controller.state.value.cabinet.entries.isEmpty())
        assertEquals(0, controller.state.value.cabinet.totalEntries)
        assertEquals(CABINET_ERR_NODE_OFFLINE, controller.state.value.cabinet.errorMessage)
        assertEquals(false, controller.state.value.cabinet.loading)

        controller.toggleCabinetSelection("contract-v2.pdf")
        assertTrue("contract-v2.pdf" in controller.state.value.cabinet.selectedNames)

        controller.clearCabinetSelection()
        assertTrue(controller.state.value.cabinet.selectedNames.isEmpty())
    }

    @Test
    fun testInboundShareListOk() {
        val controller = controller()
        controller.selectCabinetPeer("peer-42")

        val entries = listOf(
            ShareEntry(name = "reports", size = 0, isDir = true, mtime = 1700000000L),
            ShareEntry(name = "plan.pdf", size = 1024, isDir = false, mtime = 1700000100L),
        )
        val envelope = makeEnvelope(
            type = MsgTypes.SHARE,
            id = "env-share-1",
            from = "peer-42",
            ts = nowMillis(),
            payload = Codec.shareListOkPayload(
                reqId = "req-1",
                path = "",
                perm = "write",
                snapshotId = "snap-1",
                offset = 0,
                total = 2,
                truncated = false,
                entries = entries,
            ),
        )

        controller.messagesCtl.handleOnInbound(envelope)

        val cabinet = controller.state.value.cabinet
        assertEquals("write", cabinet.currentPerm)
        assertEquals(2, cabinet.totalEntries)
        assertEquals(2, cabinet.entries.size)
        assertEquals("reports", cabinet.entries[0].name)
        assertTrue(cabinet.entries[0].isDir)
        assertEquals("plan.pdf", cabinet.entries[1].name)
        assertEquals(false, cabinet.loading)
    }

    @Test
    fun testInboundShareDeny() {
        val controller = controller()
        controller.selectCabinetPeer("peer-42")

        val envelope = makeEnvelope(
            type = MsgTypes.SHARE,
            id = "env-deny-1",
            from = "peer-42",
            ts = nowMillis(),
            payload = Codec.shareDenyPayload(
                reqId = "req-2",
                reason = ShareDenyReasons.NO_PERM,
            ),
        )

        controller.messagesCtl.handleOnInbound(envelope)

        val cabinet = controller.state.value.cabinet
        assertEquals(ShareDenyReasons.NO_PERM, cabinet.errorMessage)
        assertEquals(false, cabinet.loading)
    }

    @Test
    fun testRecentUploadsRecordingOnTransferCompleted() {
        val controller = controller()
        val offer = IncomingOffer(
            transferId = "t-upload-1",
            peerId = "peer-uploader",
            rootName = "file.txt",
            files = listOf(IncomingFilePlan("f1", "file.txt", 1024L)),
            totalSize = 1024L,
            purpose = FilePurpose.SHARE_PUT,
            msgId = null,
            expiresAt = null,
        )

        controller.transfersCtl.handleOnTransfer(TransferEvent.Offered(offer))
        controller.transfersCtl.handleOnTransfer(TransferEvent.Completed("t-upload-1", "peer-uploader", incoming = true, savedPaths = listOf("/tmp/file.txt")))

        val recents = controller.state.value.settings.cabinetRecentUploads
        assertEquals(1, recents.size)
        assertEquals("t-upload-1", recents.first().transferId)
        assertEquals("peer-uploader", recents.first().uploaderNodeId)
        assertEquals(1, recents.first().fileCount)
        assertEquals(1024L, recents.first().totalSize)
    }
}
