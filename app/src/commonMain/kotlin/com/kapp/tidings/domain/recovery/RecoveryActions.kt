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
import com.kapp.tidings.domain.RelayController
import com.kapp.tidings.domain.markRecoveryPullPending
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * L1 导出结果。失败原因用枚举不 用字符串（文案归资源文件 —— 同 [com.kapp.tidings.domain.DiagnosticsExportResult] 的口径）。
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
 * L1 导出：把当前身份 + 信任根摘要打包成口令加密文件，写进
 * `<dataDir>/downloads/`，并尽量交给系统分享面板。
 *
 * 整个函数**不抛异常**（诊断包同款纪律）：导出失败是用户点一下按钮的结果，
 * 不该把界面掀掉。
 */
suspend fun RelayController.exportIdentityBundle(password: String): RecoveryExportResult =
    withContext(Dispatchers.Default) {
        if (password.isEmpty()) return@withContext RecoveryExportResult.Failed(RecoveryActionFailure.PasswordRequired)
        runCatching { exportIdentityBundleOrThrow(password) }
            .getOrElse { RecoveryExportResult.Failed(RecoveryActionFailure.Unexpected) }
    }

private fun RelayController.exportIdentityBundleOrThrow(password: String): RecoveryExportResult {
    val payload = recovery.currentPayload(state.value.settings.nodeId)
        ?: return RecoveryExportResult.Failed(RecoveryActionFailure.IdentityMissing)
    val bytes = RecoveryBundle.exportIdentity(payload, password, nowMillis())
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
    if (!written) return RecoveryExportResult.Failed(RecoveryActionFailure.WriteFailed)

    val actions = receivedFileActions
    val shared = actions != null && runCatching { actions.share(target) }.isSuccess
    return RecoveryExportResult.Exported(fileName, bytes.size, shared)
}

/**
 * L1 导入：成套校验 → 落钥匙与 pins → 换 settings.nodeId → **重启节点**生效。
 *
 * 顺序就是方案 §9 的两条纪律：
 *  · 「恢复先于 ensure()」由 [com.kapp.tidings.domain.recovery.RecoveryService.applyPayload]
 *    与 [RelayController.restart] 的先后关系保证 —— 新钥匙先落盘，重启后的
 *    传输层读到的一定是它；
 *  · 成套校验在 `RecoveryBundle.importIdentity` 里（manifest 与载荷咬合，
 *    只对上 nodeId 的半截包进不来）。
 *
 * 导入成功后节点以**恢复的身份**重启：对端用 `peer-pins[旧 nodeId]` 验旧证书
 * 通过 ⇒ 密码学上就是原来那台（方案 §5.5），无需人工确认框。
 */
suspend fun RelayController.importIdentityBundle(bytes: ByteArray, password: String): RecoveryImportResult =
    withContext(Dispatchers.Default) {
        if (password.isEmpty()) return@withContext RecoveryImportResult.Failed(RecoveryActionFailure.PasswordRequired)
        runCatching { importIdentityBundleOrThrow(bytes, password) }
            .getOrElse { error -> RecoveryImportResult.Failed(failureOf(error)) }
    }

private fun failureOf(error: Throwable): RecoveryActionFailure = when (error) {
    is RecoveryBundleException -> when (error.failure) {
        com.kapp.tidings.core.persist.RecoveryBundleFailure.PASSWORD_REQUIRED -> RecoveryActionFailure.PasswordRequired
        com.kapp.tidings.core.persist.RecoveryBundleFailure.BAD_FORMAT -> RecoveryActionFailure.BadFormat
        com.kapp.tidings.core.persist.RecoveryBundleFailure.UNSUPPORTED_VERSION -> RecoveryActionFailure.UnsupportedVersion
        com.kapp.tidings.core.persist.RecoveryBundleFailure.AUTH_FAILED -> RecoveryActionFailure.AuthFailed
        com.kapp.tidings.core.persist.RecoveryBundleFailure.MANIFEST_MISMATCH -> RecoveryActionFailure.ManifestMismatch
        com.kapp.tidings.core.persist.RecoveryBundleFailure.INVALID_PAYLOAD -> RecoveryActionFailure.InvalidPayload
    }
    else -> RecoveryActionFailure.Unexpected
}

private suspend fun RelayController.importIdentityBundleOrThrow(bytes: ByteArray, password: String): RecoveryImportResult {
    val payload = RecoveryBundle.importIdentity(bytes, password)
    recovery.applyPayload(payload)
    // settings 可能是全新的一份（重装后恢复），也可能覆盖已有身份（显式 L1 导入，
    // 用户意志优先于 Q10 —— 那条裁决管的是**自动**收养，不管手动导入）。
    val base = store.load() ?: com.kapp.tidings.domain.RelaySettings.firstRun(platform.deviceDefaultNick)
    val next = base.copy(nodeId = payload.nodeId)
    store.save(next)
    _state.update { it.copy(settings = next) }
    // L2 触发（Q11）：信任根里的对端就是待拉历史的一方；restart() 里的 start()
    // 会看到非空的 pending 集合并逐个发起（ACK 不到就保留，下次启动再试）。
    markRecoveryPullPending(payload.peerPins.map { it.nodeId })
    restart()
    return RecoveryImportResult.Imported(payload.nodeId, payload.peerPins.size)
}
