// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.core.crypto.secureRandomBytes
import com.kapp.tidings.core.fs.deleteFile
import com.kapp.tidings.core.fs.fileExists
import com.kapp.tidings.core.fs.pathJoin
import com.kapp.tidings.core.fs.readFileBytes
import com.kapp.tidings.core.persist.PersistStores
import com.kapp.tidings.core.persist.RecoveryBundle
import com.kapp.tidings.core.persist.RecoveryManifest
import com.kapp.tidings.domain.model.MessageUi
import com.kapp.tidings.domain.recovery.RecoveryDataCodec
import com.kapp.tidings.domain.recovery.RecoveryExportResult
import com.kapp.tidings.domain.recovery.RecoveryImportResult
import com.kapp.tidings.domain.recovery.exportRecoveryBundle
import com.kapp.tidings.domain.recovery.importRecoveryBundle
import com.kapp.tidings.domain.recovery.writeIdentityKeyMaterial
import com.kapp.tidings.platform.InMemoryAppPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * L1 **全量快照**的四条底线（方案 §6.2 数据维度）：
 *  1. 端到端：A 导出的包进 B，设置 + 历史 + 身份成套回来，nodeId 与消息逐字段一致，
 *     `mine` 不翻转（本机自己的备份）；full 导入**不**标记 L2 待拉（历史在包里）。
 *  2. messageCount 成套校验：manifest 与载荷咬不上 ⇒ MANIFEST_MISMATCH（半截包进不来）。
 *  3. identity-only 老包仍走同一条入口（kind 分派，行为与从前一致且标记 L2）。
 *  4. codec 往返逐字段一致。
 *
 * 双控制器共享 InMemoryAppPlatform 的固定 dataDirectory（`/tmp/relay-preview`），
 * 种进去的钥匙/pins/导出文件在 finally 里逐个清理 —— 这个目录是全部控制器测试
 * 共用的，留脏会让别的用例读到幽灵数据。
 */
class RecoveryFullBundleTest {

    private val dataDir = "/tmp/relay-preview"
    private val password = "correct horse battery staple"
    private val scopeA = CoroutineScope(Job())
    private val scopeB = CoroutineScope(Job())

    private fun message(id: String, mine: Boolean, at: Long) = MessageUi(
        id = id,
        peerId = "peer-x",
        mine = mine,
        text = "text-$id",
        at = at,
    )

    private fun cleanupPaths(fileName: String?): MutableList<String> = mutableListOf(
        pathJoin(dataDir, "link-identity", "link-key.pk8"),
        pathJoin(dataDir, "link-identity", "link-pub.spki"),
        pathJoin(dataDir, "link-identity", "link-cert.der"),
        pathJoin(dataDir, "stores", "peer-pins.json"),
        pathJoin(dataDir, "stores", "peer-pins.log"),
        pathJoin(dataDir, "stores", "recovery-pending.json"),
    ).also { list ->
        if (fileName != null) list += backupPathInPath(dataDir, fileName)
    }

    private fun backupPathInPath(dir: String, fileName: String) = pathJoin(dir, "downloads", fileName)

    @Test
    fun fullBundleRoundTripsEndToEnd() = runBlocking {
        val scalar = secureRandomBytes(32)
        val spki = ByteArray(91) { (it * 5 + 7).toByte() }
        writeIdentityKeyMaterial(pathJoin(dataDir, "link-identity"), scalar, spki)
        // 先种 pins 再建 A：A 的 FilePeerPinStore 在构造时读盘。
        PersistStores.under(dataDir).peerPins.save(
            mapOf(
                "peer-a" to "sha256:" + "aa".repeat(32),
                "peer-b" to "sha256:" + "bb".repeat(32),
            ),
        )

        val a = RelayController(platform = InMemoryAppPlatform(), store = InMemorySettingsStore(), scope = scopeA)
        val exportedNodeId = a.state.value.settings.nodeId
        a.appendMessage(message("m-1", mine = false, at = 100L))
        a.appendMessage(message("m-2", mine = true, at = 200L))

        val export = a.exportRecoveryBundle(password)
        check(export is RecoveryExportResult.Exported) { "导出必须成功：$export" }
        val fileName = export.fileName
        try {
            val bytes = readFileBytes(backupPathInPath(dataDir, fileName))!!

            val b = RelayController(platform = InMemoryAppPlatform(), store = InMemorySettingsStore(), scope = scopeB)
            // B 在导入前有自己的身份与一条本地消息 —— 导入后身份换成包里的，
            // 本地已有的消息按 id 幂等保留（不同 id 的合入）。
            b.appendMessage(message("m-local", mine = true, at = 50L))
            val result = b.importRecoveryBundle(bytes, password)
            check(result is RecoveryImportResult.Imported) { "full 导入必须成功：$result" }
            assertEquals(exportedNodeId, result.nodeId, "身份必须是包里那个（A 的 nodeId）")
            assertEquals(2, result.peerPinCount)
            assertEquals(2L, result.messageCount, "包里有两条消息")

            assertEquals(exportedNodeId, b.state.value.settings.nodeId, "settings.nodeId 成套换成包里的")
            val thread = b.core.threads.getValue("peer-x")
            assertEquals(listOf("m-local", "m-1", "m-2"), thread.map { it.id }, "按 at 插序合入，本地已有消息保留")
            assertEquals(true, thread.first { it.id == "m-2" }.mine, "mine 不翻转：本机备份的视角就是本机的（A 里是 true，恢复后仍是 true）")
            // full 导入不标记 L2（历史在包里）；identity-only 才标记。
            assertTrue(b.pendingRecoveryPeers.isEmpty(), "full 恢复不得标记 L2 待拉")
            assertEquals(
                setOf("peer-a", "peer-b"),
                PersistStores.under(dataDir).peerPins.load().keys,
                "信任根成套落盘",
            )
        } finally {
            scopeA.cancel()
            scopeB.cancel()
            for (path in cleanupPaths(fileName)) deleteFile(path)
        }
    }

    @Test
    fun messageCountMismatchIsRejected() = runBlocking {
        val snapshot = sampleSnapshot()
        val payload = RecoveryDataCodec.encode(snapshot.identity, snapshot.settings, snapshot.threads)
        // 注入：manifest 声称 3 条，载荷里只有 2 条 —— 成套校验必须拦下。
        val bytes = RecoveryBundle.sealBundle(
            manifest = RecoveryManifest(
                kind = RecoveryBundle.KIND_FULL,
                nodeId = snapshot.identity.nodeId,
                createdAtEpochMs = 1L,
                peerPinCount = snapshot.identity.peerPins.size,
                messageCount = 3L,
            ),
            payloadBytes = payload,
            password = password,
            salt = ByteArray(16),
            nonce = ByteArray(12),
        )
        val a = RelayController(platform = InMemoryAppPlatform(), store = InMemorySettingsStore(), scope = scopeA)
        try {
            val result = a.importRecoveryBundle(bytes, password)
            check(result is RecoveryImportResult.Failed && result.reason == com.kapp.tidings.domain.recovery.RecoveryActionFailure.ManifestMismatch) {
                "半截包必须按 MANIFEST_MISMATCH 拒绝：$result"
            }
        } finally {
            scopeA.cancel()
            for (path in cleanupPaths(null)) deleteFile(path)
        }
    }

    @Test
    fun identityOnlyBundleStillImportsAndMarksL2() = runBlocking {
        val scalar = secureRandomBytes(32)
        val spki = ByteArray(91) { (it * 3 + 1).toByte() }
        writeIdentityKeyMaterial(pathJoin(dataDir, "link-identity"), scalar, spki)
        PersistStores.under(dataDir).peerPins.save(mapOf("peer-l2" to "sha256:" + "11".repeat(32)))

        val a = RelayController(platform = InMemoryAppPlatform(), store = InMemorySettingsStore(), scope = scopeA)
        val payload = a.recovery.currentPayload(a.state.value.settings.nodeId)!!
        val bytes = RecoveryBundle.sealBundle(
            manifest = RecoveryManifest(
                kind = RecoveryBundle.KIND_IDENTITY,
                nodeId = payload.nodeId,
                createdAtEpochMs = 1L,
                peerPinCount = payload.peerPins.size,
            ),
            payloadBytes = RecoveryBundle.encodeIdentityPayload(payload),
            password = password,
            salt = ByteArray(16),
            nonce = ByteArray(12),
        )
        try {
            val b = RelayController(platform = InMemoryAppPlatform(), store = InMemorySettingsStore(), scope = scopeB)
            val result = b.importRecoveryBundle(bytes, password)
            check(result is RecoveryImportResult.Imported) { "identity 导入必须成功：$result" }
            assertEquals(0L, result.messageCount, "identity 包不带历史")
            assertEquals(setOf("peer-l2"), b.pendingRecoveryPeers.toSet(), "identity 恢复标记 L2 待拉（老行为）")
            assertTrue(fileExists(pathJoin(dataDir, "stores", "recovery-pending.json")), "L2 待拉集合必须落盘")
        } finally {
            scopeA.cancel()
            scopeB.cancel()
            for (path in cleanupPaths(null)) deleteFile(path)
        }
    }

    @Test
    fun codecRoundTripsEveryDimension() {
        val snapshot = sampleSnapshot()
        val decoded = RecoveryDataCodec.decode(RecoveryDataCodec.encode(snapshot.identity, snapshot.settings, snapshot.threads))
        assertEquals(snapshot.identity, decoded.identity, "身份逐字段一致")
        assertEquals(snapshot.settings, decoded.settings, "设置逐字段一致")
        assertEquals(snapshot.threads.keys, decoded.threads.keys, "会话键一致")
        assertEquals(
            snapshot.threads.values.flatMap { it.map { m -> m.id } },
            decoded.threads.values.flatMap { it.map { m -> m.id } },
            "消息逐字段一致（以 id 序列代表）",
        )
    }

    // ---- 夹具 ----

    private fun sampleSnapshot(): RecoveryDataCodec.FullSnapshot {
        val identity = com.kapp.tidings.core.persist.RecoveryIdentityPayload(
            nodeId = "01234567-89ab-cdef-0123-456789abcdef",
            ecPrivateScalar = ByteArray(32) { (it + 1).toByte() },
            spki = ByteArray(91) { (it * 7 + 3).toByte() },
            peerPins = listOf(
                com.kapp.tidings.core.persist.RecoveryIdentityPayload.PeerPin("peer-a", "sha256:" + "aa".repeat(32)),
            ),
        )
        val settings = RelaySettings(nodeId = identity.nodeId, nick = "备份机")
        val threads = mapOf(
            "peer-x" to listOf(message("m-1", mine = false, at = 100L), message("m-2", mine = true, at = 200L)),
        )
        return RecoveryDataCodec.FullSnapshot(identity, settings, threads)
    }
}
