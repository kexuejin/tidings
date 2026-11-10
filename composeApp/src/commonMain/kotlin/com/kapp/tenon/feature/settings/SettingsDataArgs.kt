// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.settings

import com.kapp.tenon.domain.recovery.RecoveryExportResult
import com.kapp.tenon.domain.recovery.RecoveryImportResult

/**
 * 「设置 · 数据与存储」的九个值。
 *
 * 与 [SettingsDataActions] 一起把 `SettingsDataSubpage` 的散参收成两个容器（照
 * `ChatScreenArgs.kt` 的 `ChatScreenActions` / `ComposerSlotArgs` 形状）：值只作搬运，
 * 页面内仍用同名局部 `val` 接住，函数体逐字不动。
 *
 * 全部字段带默认值，与改前 `SettingsDataSubpage` 的散参默认值一一对应；截图夹具
 * 继续走旧签名时行为不变。
 */
data class SettingsDataUiState(
    val dataDirectory: String = DEFAULT_DATA_DIRECTORY,
    val cacheDirectory: String = "",
    val dataUsageBytes: Long? = null,
    val cacheUsageBytes: Long? = null,
    val leftoverPartCount: Int? = null,
    val leftoverPartBytes: Long? = null,
    val persistMessagesEnabled: Boolean = false,
    val retentionDays: Int = DEFAULT_RETENTION_DAYS,
)

/**
 * 「设置 · 数据与存储」的十个可空回调。
 *
 * null = 这条能力本宿主没有接，对应入口整颗/整张卡不画（与目录出口、备份恢复卡
 * 同一条「能力缺失 = 不画」纪律）。可空性逐字照搬改前的散参签名，不做收窄。
 */
data class SettingsDataActions(
    val onBack: (() -> Unit)? = null,
    val onClearLeftoverParts: (() -> Unit)? = null,
    val onChangeDataDirectory: (() -> Unit)? = null,
    val onOpenDataDirectory: (() -> Unit)? = null,
    val onPersistMessagesEnabledChange: ((Boolean) -> Unit)? = null,
    val onClearHistory: (() -> Unit)? = null,
    val onExportRecoveryBundle: (suspend (String) -> RecoveryExportResult)? = null,
    val onPickBackupFile: (suspend () -> ByteArray?)? = null,
    val onImportRecoveryBundle: (suspend (ByteArray, String) -> RecoveryImportResult)? = null,
)
