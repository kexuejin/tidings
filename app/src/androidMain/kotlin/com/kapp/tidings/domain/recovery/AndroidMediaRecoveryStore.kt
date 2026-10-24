// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain.recovery

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import com.kapp.tidings.core.nowMillis
import com.kapp.tidings.core.persist.RecoveryBundle
import com.kapp.tidings.core.persist.RecoveryBundleException
import com.kapp.tidings.core.persist.RecoveryIdentityPayload
import com.kapp.tidings.core.persist.RecoveryManifest

/**
 * Android 的 L0 存活存储：身份包写进**公共 Downloads**（A5：文件随卸载存活），
 * 用 `KDF(ANDROID_ID)` 做混淆加密（Q1：定位是防误触，不是抵御恶意应用）。
 *
 * ## 读取的现实（A6）
 *
 * targetSdk 36 上，卸载使属主断裂：重装后读非媒体文件会抛 `SecurityException`。
 * 所以 [load] 的三条出路都如实返回：
 *  · 读到了 → 收养（调用方保证只在 settings 不存在时走到这）；
 *  · `SecurityException` → diagnosis 写明走设置页导入（SAF 兜底 = Android 主预期路径，Q6）；
 *  · 没有 / API < 29 → null。
 *
 * ## 为什么 KDF 迭代数低
 *
 * 这个包的密钥是**任何应用都能读到的 ANDROID_ID**（A2），KDF 本来就不构成安全边界
 * （Q1 裁决）——它只防"文件管理器里看到一份裸私钥"。而 [load] 只发生在重装后的
 * 第一次冷启动（同步路径，方案 §9.2），迭代数压到 [L0_ITERATIONS] 把启动代价
 * 控制在几十毫秒；真正的安全由 L1 的强制口令（600k）承担。
 *
 * API 边界：`MediaStore.Downloads` 要 API 29+；更低版本没有免权限的公共下载目录，
 * 直接不提供 L0（恢复出厂/换机场景由 L1 覆盖）。
 */
class AndroidMediaRecoveryStore(private val context: Context) : PlatformRecoveryStore {

    private var lastDiagnosis: String? = null

    override val readDiagnosis: String? get() = lastDiagnosis

    override fun load(): RecoveryIdentityPayload? {
        lastDiagnosis = null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            lastDiagnosis = "public Downloads needs API 29+; L0 unavailable on this device"
            return null
        }
        val bytes = try {
            readFromMediaStore()
        } catch (e: SecurityException) {
            lastDiagnosis = "owner broken after reinstall; use the settings import (SAF) instead"
            return null
        } catch (e: Exception) {
            lastDiagnosis = "read failed: ${e.message}"
            return null
        }
        if (bytes == null) return null
        return try {
            val password = l0Password(context)
            RecoveryBundle.importIdentity(bytes, password)
        } catch (e: RecoveryBundleException) {
            lastDiagnosis = "bundle rejected: ${e.failure}"
            null
        }
    }

    override fun save(payload: RecoveryIdentityPayload) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val bytes = RecoveryBundle.sealBundle(
                manifest = RecoveryManifest(
                    kind = RecoveryBundle.KIND_IDENTITY,
                    nodeId = payload.nodeId,
                    createdAtEpochMs = nowMillis(),
                    peerPinCount = payload.peerPins.size,
                ),
                payloadBytes = RecoveryBundle.encodeIdentityPayload(payload),
                password = l0Password(context),
                iterations = L0_ITERATIONS,
            )
            writeToMediaStore(bytes)
        } catch (_: Exception) {
            // 备份是尽力而为：写失败不惊动任何人，L1 永远是兜底。
        }
    }

    private fun l0Password(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        return PASSWORD_PREFIX + androidId
    }

    private fun readFromMediaStore(): ByteArray? {
        val resolver = context.contentResolver
        val uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        resolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(BACKUP_FILE_NAME),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val id = cursor.getLong(0)
            val item = android.content.ContentUris.withAppendedId(uri, id)
            return resolver.openInputStream(item)?.use { it.readBytes() }
        }
        return null
    }

    private fun writeToMediaStore(bytes: ByteArray) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, BACKUP_FILE_NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val item = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        try {
            resolver.openOutputStream(item)?.use { out ->
                out.write(bytes)
                out.flush()
            }
        } finally {
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(item, values, null, null)
        }
    }

    companion object {
        /** 固定文件名：重装后靠名字找回（内容里有 nonce，每次保存覆盖同名条目）。 */
        const val BACKUP_FILE_NAME = "relay-identity-backup.bin"

        private const val PASSWORD_PREFIX = "rl0:"

        /** L0 混淆档迭代数（理由见类 KDoc）。 */
        const val L0_ITERATIONS = 10_000
    }
}
