// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain.recovery

import com.kapp.tidings.core.fs.deleteFile
import com.kapp.tidings.core.fs.makeDirectories
import com.kapp.tidings.core.fs.openRandomAccess
import com.kapp.tidings.core.fs.pathJoin
import com.kapp.tidings.core.fs.use
import com.kapp.tidings.core.nowMillis
import com.kapp.tidings.core.persist.RecoveryBundle
import com.kapp.tidings.core.persist.RecoveryBundleException
import com.kapp.tidings.core.persist.RecoveryBundleFailure
import com.kapp.tidings.core.persist.RecoveryIdentityPayload
import com.kapp.tidings.core.persist.RecoveryManifest
import com.kapp.tidings.domain.RelayController
import com.kapp.tidings.domain.withLock
import com.kapp.tidings.domain.markRecoveryPullPending
import com.kapp.tidings.domain.mergeRecoveredThreads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * L1 导出结果。失败原因用枚举而不是字符串（文案归资源文件 —— 同
 * [com.kapp.tidings.domain.DiagnosticsExportResult] 的口径）。
 */
sealed interface RecoveryExportResult {
    data class Exported(
        val fileName: String,
        val byteCount: Int,
        /** 是否真的交给了系统分享面板；false = 本平台没有这条出口，文件仍写出来了。 */
        val shared: Boolean,
    ) : RecoveryExportResult

    data class Failed(val reason: RecoveryActionFailure) : RecoveryExportResult
}

sealed interface RecoveryImportResult {
    data class Imported(
        val nodeId: String,
        val peerPinCount: Int,
        /** 恢复回来的消息条数（identity-only 包 = 0，历史靠 L2 对端拉回）。 */
        val messageCount: Long,
    ) : RecoveryImportResult

    data class Failed(val reason: RecoveryActionFailure) : RecoveryImportResult
}

/** 导出/导入失败的程序化原因；用户文案在 `composeResources`（`:cjkLiteralGate`）。 */
enum class RecoveryActionFailure {
    /** 没输口令（Q3：强制口令，禁止明文导出）。 */
    PasswordRequired,

    /** 本机还没有身份（首启前导出无意义）。 */
    IdentityMissing,

    /** 文件不是恢复包（magic/长度/manifest 不对）。 */
    BadFormat,

    /** 包格式版本比本程序新（旧构建不认新包，与 SQLDelight 同款立场）。 */
    UnsupportedVersion,

    /** 口令不对，或文件被改过 —— 在 AEAD 上是同一件事。 */
    AuthFailed,

    /** manifest 与载荷不成套（半截包）。 */
    ManifestMismatch,

    /** 载荷本身非法（缺钥匙、pin 超限等）。 */
    InvalidPayload,

    /** 落盘/读盘失败（磁盘满、目录不可写）。 */
    WriteFailed,

    /** 预期外的错。 */
    Unexpected,
}

/** 备份文件落在 `<dataDir>/downloads/`，与诊断包同一出口（FileProvider 唯一认的目录）。 */
fun backupPathIn(controller: RelayController, fileName: String): String =
    pathJoin(controller.dataDirectory, "downloads", fileName)

/**
 * L1 导出：**全量快照**（身份 + 设置 + 会话历史，方案 §6.2 数据维度），口令加密后写进
 * `<dataDir>/downloads/`，并尽量交给系统分享面板。
 *
 * 内存口径见 `RecoveryDataCodec` 的 KDoc（应用本就全量常驻线程，序列化瞬时翻倍；
 * 流式在应用改窗口化加载之后再做，格式不用动）。首版排除 `downloads/`（Q7）。
 *
 * 整个函数**不抛异常**（诊断包同款纪律）：导出失败是用户点一下按钮的结果，
 * 不该把界面掀掉。
 */
suspend fun RelayController.exportRecoveryBundle(password: String): RecoveryExportResult =
    withContext(Dispatchers.Default) {
        if (password.isEmpty()) return@withContext RecoveryExportResult.Failed(RecoveryActionFailure.PasswordRequired)
        runCatching { exportRecoveryBundleOrThrow(password) }
            .getOrElse { error ->
                if (error is RecoveryWriteFailure) {
                    RecoveryExportResult.Failed(RecoveryActionFailure.WriteFailed)
                } else {
                    RecoveryExportResult.Failed(RecoveryActionFailure.Unexpected)
                }
            }
    }

private class RecoveryWriteFailure : Exception()

private fun RelayController.exportRecoveryBundleOrThrow(password: String): RecoveryExportResult {
    val identity = recovery.currentPayload(state.value.settings.nodeId)
        ?: return RecoveryExportResult.Failed(RecoveryActionFailure.IdentityMissing)
    val threads = stateLock.withLock { core.threads.mapValues { it.value.toList() } }
    val payloadBytes = RecoveryDataCodec.encode(identity, state.value.settings, threads)
    val manifest = RecoveryManifest(
        kind = RecoveryBundle.KIND_FULL,
        nodeId = identity.nodeId,
        createdAtEpochMs = nowMillis(),
        peerPinCount = identity.peerPins.size,
        messageCount = threads.values.sumOf { it.size }.toLong(),
    )
    val bytes = RecoveryBundle.sealBundle(manifest, payloadBytes, password)
    val fileName = RecoveryService.backupFileName(nowMillis())
    val target = backupPathIn(this, fileName)
    makeDirectories(pathJoin(dataDirectory, "downloads"))
    // 同名覆盖：同一秒内连点两次导出，旧包必须被换掉（诊断包同款纪律）。
    deleteFile(target)
    val written = runCatching {
        openRandomAccess(target, forWrite = true).use { file ->
            file.append(bytes, bytes.size)
            file.flush()
        }
        true
    }.getOrDefault(false)
    if (!written) throw RecoveryWriteFailure()

    val actions = receivedFileActions
    val shared = actions != null && runCatching { actions.share(target) }.isSuccess
    return RecoveryExportResult.Exported(fileName, bytes.size, shared)
}

/**
 * L1 导入：**按 kind 分派**（一次开封，不付两次 KDF —— 600k 迭代在低端机上以秒计）。
 *
 *  · `full`：身份 + 设置 + 会话历史成套恢复。设置**整体替换**（备份就是还原点）；
 *    历史按消息 id 幂等合入、`mine` 视角**不翻转**（这是本机自己的备份，与 L2 的
 *    对端副本相反）。**不标记 L2 待拉** —— 历史已经在包里。
 *  · `identity`：身份包（老格式，或有意只带身份、历史走 L2 的场景），行为与从前
 *    逐字一致，并标记信任根里的对端为 L2 待拉。
 *
 * 顺序纪律「恢复先于 ensure()」不变：钥匙先落盘，`restart()` 后的传输层读到的
 * 一定是它。成套校验两层：容器的 manifest 咬合在 :core；full 的 settings.nodeId
 * 与 messageCount 咬合在这里。导入成功后节点以恢复的身份重启 —— 对端用
 * `peer-pins[旧 nodeId]` 验旧证书通过，密码学上就是原来那台（方案 §5.5），
 * 无需人工确认框。
 */
suspend fun RelayController.importRecoveryBundle(bytes: ByteArray, password: String): RecoveryImportResult =
    withContext(Dispatchers.Default) {
        if (password.isEmpty()) return@withContext RecoveryImportResult.Failed(RecoveryActionFailure.PasswordRequired)
        runCatching { importRecoveryBundleOrThrow(bytes, password) }
            .getOrElse { error -> RecoveryImportResult.Failed(failureOf(error)) }
    }

private fun failureOf(error: Throwable): RecoveryActionFailure = when (error) {
    is RecoveryBundleException -> when (error.failure) {
        RecoveryBundleFailure.PASSWORD_REQUIRED -> RecoveryActionFailure.PasswordRequired
        RecoveryBundleFailure.BAD_FORMAT -> RecoveryActionFailure.BadFormat
        RecoveryBundleFailure.UNSUPPORTED_VERSION -> RecoveryActionFailure.UnsupportedVersion
        RecoveryBundleFailure.AUTH_FAILED -> RecoveryActionFailure.AuthFailed
        RecoveryBundleFailure.MANIFEST_MISMATCH -> RecoveryActionFailure.ManifestMismatch
        RecoveryBundleFailure.INVALID_PAYLOAD -> RecoveryActionFailure.InvalidPayload
    }
    else -> RecoveryActionFailure.Unexpected
}

private suspend fun RelayController.importRecoveryBundleOrThrow(bytes: ByteArray, password: String): RecoveryImportResult {
    val opened = RecoveryBundle.openBundle(bytes, password)
    return when (opened.manifest.kind) {
        RecoveryBundle.KIND_IDENTITY -> {
            val payload = RecoveryBundle.identityPayloadOf(opened)
            applyIdentityOnly(payload)
            RecoveryImportResult.Imported(payload.nodeId, payload.peerPins.size, messageCount = 0L)
        }
        RecoveryBundle.KIND_FULL -> {
            val snapshot = try {
                RecoveryDataCodec.decode(opened.payload)
            } catch (e: IllegalArgumentException) {
                throw RecoveryBundleException(RecoveryBundleFailure.BAD_FORMAT, "full payload rejected: ${e.message}", e)
            }
            // 成套校验（full）：manifest 的每个计数都必须与载荷咬合，settings 的 nodeId
            // 也必须是同一个身份 —— 只对上一半的包在这里死，不流进恢复流程。
            val manifest = opened.manifest
            if (manifest.nodeId != snapshot.identity.nodeId || snapshot.settings.nodeId != snapshot.identity.nodeId) {
                throw RecoveryBundleException(
                    RecoveryBundleFailure.MANIFEST_MISMATCH,
                    "nodeId mismatch: manifest=${manifest.nodeId} identity=${snapshot.identity.nodeId} settings=${snapshot.settings.nodeId}",
                )
            }
            if (manifest.peerPinCount != snapshot.identity.peerPins.size) {
                throw RecoveryBundleException(
                    RecoveryBundleFailure.MANIFEST_MISMATCH,
                    "peerPinCount mismatch: manifest=${manifest.peerPinCount} payload=${snapshot.identity.peerPins.size}",
                )
            }
            if (manifest.messageCount == null || manifest.messageCount != snapshot.messageCount) {
                throw RecoveryBundleException(
                    RecoveryBundleFailure.MANIFEST_MISMATCH,
                    "messageCount mismatch: manifest=${manifest.messageCount} payload=${snapshot.messageCount}",
                )
            }
            recovery.applyPayload(snapshot.identity)
            // 设置整体替换（备份 = 还原点）。落盘在合并之前：进程若在半路死掉，
            // 下次启动读到的是已恢复的 settings，身份与设置不会劈叉。
            store.save(snapshot.settings)
            _state.update { it.copy(settings = snapshot.settings) }
            mergeRecoveredThreads(snapshot.threads)
            restart()
            RecoveryImportResult.Imported(snapshot.identity.nodeId, snapshot.identity.peerPins.size, snapshot.messageCount)
        }
        else -> throw RecoveryBundleException(RecoveryBundleFailure.MANIFEST_MISMATCH, "kind ${opened.manifest.kind}")
    }
}

/**
 * identity-only 恢复（老路径，行为与从前一致）：落钥匙与 pins、换 nodeId、标记 L2、重启。
 * settings 可能是全新的一份（重装后恢复），也可能覆盖已有身份（显式 L1 导入，
 * 用户意志优先于 Q10 —— 那条裁决管的是**自动**收养，不管手动导入）。
 */
private suspend fun RelayController.applyIdentityOnly(payload: RecoveryIdentityPayload) {
    recovery.applyPayload(payload)
    val base = store.load() ?: com.kapp.tidings.domain.RelaySettings.firstRun(platform.deviceDefaultNick)
    val next = base.copy(nodeId = payload.nodeId)
    store.save(next)
    _state.update { it.copy(settings = next) }
    markRecoveryPullPending(payload.peerPins.map { it.nodeId })
    restart()
}
