// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.kapp.tidings.domain.recovery.RecoveryActionFailure
import com.kapp.tidings.domain.recovery.RecoveryExportResult
import com.kapp.tidings.domain.recovery.RecoveryImportResult
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.ui.components.AppButtonTone
import com.kapp.tidings.ui.components.AppOutlinedButton
import com.kapp.tidings.generated.resources.common_cancel
import com.kapp.tidings.generated.resources.common_confirm
import com.kapp.tidings.generated.resources.ic_folder
import com.kapp.tidings.generated.resources.ic_shield
import com.kapp.tidings.generated.resources.recovery_failed_format
import com.kapp.tidings.generated.resources.recovery_failed_password
import com.kapp.tidings.generated.resources.recovery_failed_title
import com.kapp.tidings.generated.resources.recovery_failed_unexpected
import com.kapp.tidings.generated.resources.recovery_failed_version
import com.kapp.tidings.generated.resources.recovery_failed_write
import com.kapp.tidings.generated.resources.recovery_export_success_body
import com.kapp.tidings.generated.resources.recovery_export_success_title
import com.kapp.tidings.generated.resources.recovery_import_success_body
import com.kapp.tidings.generated.resources.recovery_import_success_title
import com.kapp.tidings.generated.resources.recovery_password_empty
import com.kapp.tidings.generated.resources.recovery_password_label
import com.kapp.tidings.generated.resources.recovery_password_title
import com.kapp.tidings.generated.resources.set_luciola_sub
import com.kapp.tidings.generated.resources.set_luciola_title
import com.kapp.tidings.generated.resources.recovery_export_tail_app_dir
import com.kapp.tidings.generated.resources.recovery_export_tail_public
import com.kapp.tidings.generated.resources.recovery_export_tail_public_shared
import com.kapp.tidings.generated.resources.recovery_export_tail_shared_only
import com.kapp.tidings.generated.resources.settings_data_desktop_hint
import com.kapp.tidings.generated.resources.settings_data_phone_hint
import com.kapp.tidings.generated.resources.set_change_dir
import com.kapp.tidings.generated.resources.set_clear_history_action
import com.kapp.tidings.generated.resources.set_clear_history_confirm_body
import com.kapp.tidings.generated.resources.set_clear_history_sub
import com.kapp.tidings.generated.resources.set_clear_history_title
import com.kapp.tidings.generated.resources.set_data_card_title
import com.kapp.tidings.generated.resources.set_data_dir
import com.kapp.tidings.generated.resources.set_data_dir_private
import com.kapp.tidings.generated.resources.set_data_subtitle
import com.kapp.tidings.generated.resources.set_leftover_action
import com.kapp.tidings.generated.resources.set_leftover_confirm_body
import com.kapp.tidings.generated.resources.set_leftover_counting
import com.kapp.tidings.generated.resources.set_leftover_none
import com.kapp.tidings.generated.resources.set_leftover_sub
import com.kapp.tidings.generated.resources.set_leftover_title
import com.kapp.tidings.generated.resources.set_leftover_value
import com.kapp.tidings.generated.resources.set_more_data_title
import com.kapp.tidings.generated.resources.set_open_dir
import com.kapp.tidings.generated.resources.set_persist_sub
import com.kapp.tidings.generated.resources.set_persist_title
import com.kapp.tidings.generated.resources.set_recovery_card_title
import com.kapp.tidings.generated.resources.set_recovery_export_action
import com.kapp.tidings.generated.resources.set_recovery_export_sub
import com.kapp.tidings.generated.resources.set_recovery_export_title
import com.kapp.tidings.generated.resources.set_recovery_hint
import com.kapp.tidings.generated.resources.set_recovery_import_action
import com.kapp.tidings.generated.resources.set_recovery_import_sub
import com.kapp.tidings.generated.resources.set_recovery_import_title
import com.kapp.tidings.generated.resources.set_retention_summary
import com.kapp.tidings.generated.resources.set_storage_usage
import com.kapp.tidings.generated.resources.set_storage_usage_counting
import com.kapp.tidings.generated.resources.set_storage_usage_value
import com.kapp.tidings.ui.SectionCard
import com.kapp.tidings.ui.components.InfoRow
import com.kapp.tidings.ui.components.LabeledField
import com.kapp.tidings.ui.components.RelayDialog
import com.kapp.tidings.ui.components.RelayDialogButton
import com.kapp.tidings.ui.components.formatBytes
import com.kapp.tidings.ui.theme.LocalAppSpacing
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * 设置 · 数据与存储（原型 `screenSetData()`，`prototype/relay.html:3920`）。
 *
 * 原型这一页：卡「目录」＋两条 kv（数据目录 / 占用 `12.4 MB · 其中缓存 3.1 MB`）＋
 * 一句 hint（「移动端取用文件的出口…这里不放「更改目录」「打开」」）＋
 * `.krow blk` 续传残片行 ＋ 三个 `srow`（消息落盘 / 保留天数 `7 天` / 清空聊天记录，危险色）。
 * 标题栏是它自己的「数据与存储 / 占用、残片与保留策略」。
 *
 * 这一页也是**唯一**两张统计量以参数注入的页面：`dataUsageBytes == null` 与
 * `leftoverPartCount == null` 表示"还在数"（不是"数不了"—— `directoryUsageBytes`
 * 三平台都只返回 `Long`，目录不存在返回 0；`leftoverParts()` 返回 `List`），
 * 所以静态帧可以直接拍出在途那一态。
 *
 * 两个目录入口都没有消费者时整行不画：一排永远点不动的按钮就是"留在屏上骗人"。
 * 移动端两个都没接（SAF / security-scoped bookmark 给回来的东西不能直接当数据目录用），
 * 桌面只接「打开所在目录」。
 *
 * 「清空聊天记录」与它们相反：它**不是**按下去没反应的入口，而是**按下去太有效**——
 * 一键清空全部会话的全部本地历史。所以它前面必须有一道二次确认（[ClearAllHistoryConfirmDialog]），
 * 与「清空本会话」的 `ClearConversationDialog` 同一档次：更危险的动作不能保护更少。
 */
@Composable
fun SettingsDataSubpage(
    modifier: Modifier = Modifier,
    dataDirectory: String = DEFAULT_DATA_DIRECTORY,
    cacheDirectory: String = "",
    onBack: (() -> Unit)? = null,
    dataUsageBytes: Long? = null,
    cacheUsageBytes: Long? = null,
    leftoverPartCount: Int? = null,
    leftoverPartBytes: Long? = null,
    onClearLeftoverParts: (() -> Unit)? = null,
    onChangeDataDirectory: (() -> Unit)? = null,
    onOpenDataDirectory: (() -> Unit)? = null,
    persistMessagesEnabled: Boolean = false,
    onPersistMessagesEnabledChange: ((Boolean) -> Unit)? = null,
    experimentalLuciolaEnabled: Boolean = false,
    onExperimentalLuciolaChange: ((Boolean) -> Unit)? = null,
    retentionDays: Int = DEFAULT_RETENTION_DAYS,
    onClearHistory: (() -> Unit)? = null,
    /** L1 导出（口令加密身份包）。null = 本宿主没有接（整张卡不画，与目录出口同一纪律）。 */
    onExportRecoveryBundle: (suspend (String) -> RecoveryExportResult)? = null,
    /** 选备份文件并读回字节；null（取消/读不到）时导入流程静默终止。 */
    onPickBackupFile: (suspend () -> ByteArray?)? = null,
    /** L1 导入。 */
    onImportRecoveryBundle: (suspend (ByteArray, String) -> RecoveryImportResult)? = null,
) {
    var persistMessages by rememberSaveable(persistMessagesEnabled) {
        mutableStateOf(persistMessagesEnabled)
    }
    // 「清空聊天记录」**先确认再删**：它清的是全部会话的全部本地历史、不可恢复，
    // 却比「清空本会话」（有 ClearConversationDialog）保护更少。这个开关就是那道前置闸。
    var showClearHistoryConfirm by rememberSaveable { mutableStateOf(false) }

    if (showClearHistoryConfirm) {
        ClearAllHistoryConfirmDialog(
            onDismiss = { showClearHistoryConfirm = false },
            onConfirm = {
                showClearHistoryConfirm = false
                onClearHistory?.invoke()
            },
        )
    }

    // 「清残片」同一档次：删掉 = 放弃续传、不可恢复，也要先确认。
    var showClearLeftoverConfirm by rememberSaveable { mutableStateOf(false) }
    if (showClearLeftoverConfirm) {
        ClearLeftoverConfirmDialog(
            count = leftoverPartCount ?: 0,
            totalBytes = leftoverPartBytes ?: 0L,
            onDismiss = { showClearLeftoverConfirm = false },
            onConfirm = {
                showClearLeftoverConfirm = false
                onClearLeftoverParts?.invoke()
            },
        )
    }

    SettingsPageScaffold(
        title = stringResource(Res.string.set_more_data_title),
        subtitle = stringResource(Res.string.set_data_subtitle),
        onBack = onBack,
        modifier = modifier,
    ) {
        SectionCard(
            title = stringResource(Res.string.set_data_card_title),
            icon = Res.drawable.ic_folder,
        ) {
            // 手机侧的私有绝对路径（/data/user/0/...）对用户没有信息量，显示「应用内部存储」；
            // 桌面路径用户真的会去访达里找，保留真实值。
            InfoRow(
                label = stringResource(Res.string.set_data_dir),
                value = if (isAppPrivateStoragePath(dataDirectory)) {
                    stringResource(Res.string.set_data_dir_private)
                } else {
                    dataDirectory
                },
            )

            val usage = if (dataUsageBytes == null) {
                // null 只可能是"还在数"：directoryUsageBytes 三平台都不会失败（目录不存在返回 0）。
                stringResource(Res.string.set_storage_usage_counting)
            } else {
                stringResource(Res.string.set_storage_usage_value, formatBytes(dataUsageBytes), formatBytes(cacheUsageBytes ?: 0L))
            }
            InfoRow(label = stringResource(Res.string.set_storage_usage), value = usage)

            // 两个入口是否有人消费：它在两处用到 —— 说明文字（紧随「占用」行）与卡内那排按钮。
            val hasDirExits = onChangeDataDirectory != null || onOpenDataDirectory != null

            // 两句卡内说明**互斥**、同一落点（紧接「占用」行、在卡脚那排按钮之前）：
            //  · 桌面形态（有目录出口）说明「这两个按钮只在桌面有」——原型 prototype/relay.html:5290-5292；
            //    原型那句在 `.mono` 里写了内部键名 set_change_dir / set_open_dir，正式产品不印内部标识符，
            //    故资源里删掉那段括号（登记见 design/fidelity-deviations.yaml「文案/内部键名」）。
            //  · 手机形态（两个回调都是 null）说明「出口在会话里那张文件卡上」——原型 :3930-3931。
            // 位置依据：原型两处的 `.hint` 都紧接「占用」行（`.hint{margin-top:5px}` 被行内 6px 覆盖）。
            if (hasDirExits) {
                Text(
                    text = stringResource(Res.string.settings_data_desktop_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = LocalAppSpacing.current.sm6),
                )
            } else {
                Text(
                    text = stringResource(Res.string.settings_data_phone_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = LocalAppSpacing.current.sm6),
                )
            }

            // 普通失败会留着 .part 以便续传，所以这些残片是"半成品"不是垃圾，
            // 副标题必须说清"删掉就等于放弃续传"，不能让它看起来像清缓存。
            val leftover = when {
                leftoverPartCount == null -> stringResource(Res.string.set_leftover_counting)
                leftoverPartCount == 0 -> stringResource(Res.string.set_leftover_none)
                else -> stringResource(
                    Res.string.set_leftover_value,
                    leftoverPartCount,
                    formatBytes(leftoverPartBytes ?: 0L),
                )
            }
            SettingRow(
                title = stringResource(Res.string.set_leftover_title),
                subtitle = stringResource(Res.string.set_leftover_sub),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm),
                ) {
                    // 原型这一格是 `.tag q`（琥珀底，`prototype/relay.html:3936`），不是 `.tag.ok` 的绿底。
                    TagPill(leftover, TagTone.Queued)
                    AppOutlinedButton(
                        text = stringResource(Res.string.set_leftover_action),
                        // 只负责把确认框叫出来；真正删的动作在确认框的 danger 按钮上。
                        // （与「清空聊天记录」同一条纪律：删掉 = 放弃续传、不可恢复的动作
                        //  不能比更不危险的动作保护更少。）
                        onClick = { showClearLeftoverConfirm = true },
                        enabled = onClearLeftoverParts != null && (leftoverPartCount ?: 0) > 0,
                    )
                }
            }

            // 两个入口都没有消费者时整行不画：一排永远点不动的按钮就是"留在屏上骗人"。
            // 桌面只接「打开所在目录」，所以那边看到的是一个占满整行的按钮（不是灰的）。
            if (hasDirExits) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = LocalAppSpacing.current.xs),
                    horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm),
                ) {
                    val changeDir = onChangeDataDirectory
                    if (changeDir != null) {
                        AppOutlinedButton(
                            text = stringResource(Res.string.set_change_dir),
                            onClick = changeDir,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    val openDir = onOpenDataDirectory
                    if (openDir != null) {
                        AppOutlinedButton(
                            text = stringResource(Res.string.set_open_dir),
                            onClick = openDir,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            SettingSwitchRow(
                title = stringResource(Res.string.set_persist_title),
                subtitle = stringResource(Res.string.set_persist_sub),
                checked = persistMessages,
                onCheckedChange = { next ->
                    persistMessages = next
                    onPersistMessagesEnabledChange?.invoke(next)
                },
            )

            // 实验:luciola 加密信道。默认关;开启后需重启节点生效。
            var experimentalLuciola by rememberSaveable(experimentalLuciolaEnabled) {
                mutableStateOf(experimentalLuciolaEnabled)
            }
            SettingSwitchRow(
                title = stringResource(Res.string.set_luciola_title),
                subtitle = stringResource(Res.string.set_luciola_sub),
                checked = experimentalLuciola,
                onCheckedChange = { next ->
                    experimentalLuciola = next
                    onExperimentalLuciolaChange?.invoke(next)
                },
            )

            // 「跨网通道」开关**已移除**：用户的心智是"移动网 / WiFi / 内网都能无感连上"，
            // 不是"我要不要开一条长连接"。而且关掉它并不回滚到可用状态 —— 是**不拨号**，
            // 只剩等对端连入，在移动网 / NAT 下就是"怎么都连不上"（真机缺陷）。
            // 安全边界（公网必须 TLS + 双向 pin）在传输层，与这个开关无关。

            // 「保留天数」**不是可改的设置**：本仓没有任何修改保留期的入口（天数跟着构建走）。
            // 原来画成 SettingRow + 「7 天」胶囊，长得像能点 —— 降级成一行纯说明，
            // 不占设置行的视觉，也不给"能改"的错觉。
            Hint(stringResource(Res.string.set_retention_summary, retentionDays))

            SettingRow(
                title = stringResource(Res.string.set_clear_history_title),
                subtitle = stringResource(Res.string.set_clear_history_sub),
            ) {
                AppOutlinedButton(
                    text = stringResource(Res.string.set_clear_history_action),
                    // 只负责把确认框叫出来；真正删的动作在确认框的 danger 按钮上。
                    onClick = { showClearHistoryConfirm = true },
                    enabled = onClearHistory != null,
                    tone = AppButtonTone.Danger,
                )
            }
        }

        // 备份与恢复（L1）：三个回调都有人才画 —— 没有消费者的整张卡是"留在屏上骗人"。
        val exportBundle = onExportRecoveryBundle
        val pickBackup = onPickBackupFile
        val importBundle = onImportRecoveryBundle
        if (exportBundle != null && pickBackup != null && importBundle != null) {
            RecoverySection(
                onExport = exportBundle,
                onPickBackupFile = pickBackup,
                onImport = importBundle,
            )
        }
    }
}

/**
 * 手机侧私有数据目录的识别：Android 应用的内部存储路径（`/data/user/0/<pkg>` 与
 * `/data/data/<pkg>` 两个历史形态）。命中就显示「应用内部存储」而不是那串对用户
 * 没有信息量的绝对路径；桌面路径不命中，保留真实值。抽成纯函数是为了可单测。
 */
internal fun isAppPrivateStoragePath(path: String): Boolean =
    path.startsWith("/data/user/0/") || path.startsWith("/data/data/")

/**
 * 备份与恢复卡（L1，原型 screenSetData() 的「备份与恢复」卡）。
 *
 * 流程与「清空聊天记录」同一档次的前置闸纪律：导出/导入都要先过口令对话框，
 * 确认按钮才真正动手。导入多一步选文件 —— 取消选文件**不是错误**，静默终止。
 *
 * 结果一律落对话框（成功报文件名/大小/去向，失败报程序化原因的文案），不给
 * 「点了一下什么都没发生」的入口。
 */
@Composable
private fun RecoverySection(
    onExport: suspend (String) -> RecoveryExportResult,
    onPickBackupFile: suspend () -> ByteArray?,
    onImport: suspend (ByteArray, String) -> RecoveryImportResult,
) {
    val scope = rememberCoroutineScope()
    var password by rememberSaveable { mutableStateOf("") }
    var passwordError by rememberSaveable { mutableStateOf(false) }
    var showExportPassword by rememberSaveable { mutableStateOf(false) }
    var showImportPassword by rememberSaveable { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<ByteArray?>(null) }
    var exportResult by remember { mutableStateOf<RecoveryExportResult?>(null) }
    var importResult by remember { mutableStateOf<RecoveryImportResult?>(null) }

    fun closePasswordDialog() {
        showExportPassword = false
        showImportPassword = false
        passwordError = false
        password = ""
        pendingImport = null
    }

    fun confirmPassword() {
        if (password.isEmpty()) {
            passwordError = true
            return
        }
        val bytes = pendingImport
        if (showImportPassword && bytes != null) {
            val request = bytes
            scope.launch {
                importResult = onImport(request, password)
                closePasswordDialog()
            }
        } else {
            scope.launch {
                exportResult = onExport(password)
                closePasswordDialog()
            }
        }
    }

    SectionCard(
        title = stringResource(Res.string.set_recovery_card_title),
        icon = Res.drawable.ic_shield,
    ) {
        Text(
            text = stringResource(Res.string.set_recovery_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = LocalAppSpacing.current.sm6),
        )
        SettingRow(
            title = stringResource(Res.string.set_recovery_export_title),
            subtitle = stringResource(Res.string.set_recovery_export_sub),
        ) {
            AppOutlinedButton(
                text = stringResource(Res.string.set_recovery_export_action),
                onClick = {
                    password = ""
                    passwordError = false
                    showExportPassword = true
                },
            )
        }
        SettingRow(
            title = stringResource(Res.string.set_recovery_import_title),
            subtitle = stringResource(Res.string.set_recovery_import_sub),
        ) {
            AppOutlinedButton(
                text = stringResource(Res.string.set_recovery_import_action),
                onClick = {
                    scope.launch {
                        val bytes = onPickBackupFile()
                        // 用户取消选文件不是错误，什么都不发生。
                        if (bytes != null) {
                            password = ""
                            passwordError = false
                            pendingImport = bytes
                            showImportPassword = true
                        }
                    }
                },
            )
        }
    }

    if (showExportPassword || showImportPassword) {
        RecoveryPasswordDialog(
            password = password,
            onPasswordChange = {
                password = it
                if (it.isNotEmpty()) passwordError = false
            },
            showError = passwordError,
            onDismiss = { closePasswordDialog() },
            onConfirm = { confirmPassword() },
        )
    }

    exportResult?.let { result ->
        when (result) {
            is RecoveryExportResult.Exported -> RecoveryResultDialog(
                title = stringResource(Res.string.recovery_export_success_title),
                body = stringResource(
                    Res.string.recovery_export_success_body,
                    result.fileName,
                    formatBytes(result.byteCount.toLong()),
                    exportTailText(result),
                ),
                onDismiss = { exportResult = null },
            )
            is RecoveryExportResult.Failed -> RecoveryResultDialog(
                title = stringResource(Res.string.recovery_failed_title),
                body = recoveryFailureText(result.reason),
                onDismiss = { exportResult = null },
            )
        }
    }

    importResult?.let { result ->
        when (result) {
            is RecoveryImportResult.Imported -> RecoveryResultDialog(
                title = stringResource(Res.string.recovery_import_success_title),
                body = stringResource(
                    Res.string.recovery_import_success_body,
                    result.nodeId,
                    result.peerPinCount,
                    result.messageCount,
                ),
                onDismiss = { importResult = null },
            )
            is RecoveryImportResult.Failed -> RecoveryResultDialog(
                title = stringResource(Res.string.recovery_failed_title),
                body = recoveryFailureText(result.reason),
                onDismiss = { importResult = null },
            )
        }
    }
}

@Composable
private fun RecoveryPasswordDialog(
    password: String,
    onPasswordChange: (String) -> Unit,
    showError: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    RelayDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.recovery_password_title)) },
        content = {
            LabeledField(
                label = stringResource(Res.string.recovery_password_label),
                value = password,
                onValueChange = onPasswordChange,
                isError = showError,
                supporting = if (showError) stringResource(Res.string.recovery_password_empty) else null,
                password = true,
            )
        },
        confirmButton = {
            RelayDialogButton(
                text = stringResource(Res.string.common_confirm),
                onClick = onConfirm,
            )
        },
        dismissButton = {
            RelayDialogButton(
                text = stringResource(Res.string.common_cancel),
                onClick = onDismiss,
            )
        },
    )
}

@Composable
private fun RecoveryResultDialog(
    title: String,
    body: String,
    onDismiss: () -> Unit,
) {
    RelayDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        content = {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            RelayDialogButton(
                text = stringResource(Res.string.common_confirm),
                onClick = onDismiss,
            )
        },
    )
}

/**
 * 导出结果对话框的收尾一句，按「公共目录存没存上 × 分享面板开没开」四个组合说实话：
 * 文件在哪、用户还要做什么。`publicName == null` 时**不许**说"已保存到下载目录"——
 * 那份只在应用沙箱里，用户在系统文件应用里找不到。
 */
@Composable
private fun exportTailText(result: RecoveryExportResult.Exported): String = when {
    result.publicName != null && result.shared ->
        stringResource(Res.string.recovery_export_tail_public_shared)
    result.publicName != null ->
        stringResource(Res.string.recovery_export_tail_public)
    result.shared ->
        stringResource(Res.string.recovery_export_tail_shared_only)
    else ->
        stringResource(Res.string.recovery_export_tail_app_dir)
}

@Composable
private fun recoveryFailureText(reason: RecoveryActionFailure): String = when (reason) {
    RecoveryActionFailure.PasswordRequired,
    RecoveryActionFailure.AuthFailed,
    -> stringResource(Res.string.recovery_failed_password)

    RecoveryActionFailure.BadFormat,
    RecoveryActionFailure.ManifestMismatch,
    RecoveryActionFailure.InvalidPayload,
    -> stringResource(Res.string.recovery_failed_format)

    RecoveryActionFailure.UnsupportedVersion -> stringResource(Res.string.recovery_failed_version)
    RecoveryActionFailure.WriteFailed -> stringResource(Res.string.recovery_failed_write)
    RecoveryActionFailure.IdentityMissing,
    RecoveryActionFailure.Unexpected,
    -> stringResource(Res.string.recovery_failed_unexpected)
}

/**
 * 「清理续传残片」的二次确认，与 [ClearAllHistoryConfirmDialog] 同一档次：
 * 删掉 = 放弃这些文件的续传、不可恢复。正文报**个数与总大小** —— 用户得知道
 * 放弃的是多少东西才能做决定。
 */
@Composable
private fun ClearLeftoverConfirmDialog(
    count: Int,
    totalBytes: Long,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    RelayDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.set_leftover_title)) },
        content = {
            Text(
                text = stringResource(
                    Res.string.set_leftover_confirm_body,
                    count,
                    formatBytes(totalBytes),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            RelayDialogButton(
                text = stringResource(Res.string.set_leftover_action),
                danger = true,
                onClick = onConfirm,
            )
        },
        dismissButton = {
            RelayDialogButton(
                text = stringResource(Res.string.common_cancel),
                onClick = onDismiss,
            )
        },
    )
}

/**
 * 「清空**全部**聊天记录」的二次确认。
 *
 * 与 `ClearConversationDialog`（清空本会话）是同一档次的前置闸，区别只在范围。
 * 正文逐条说清三件事，一条都不能省：
 * 1. **全部会话**——不是当前这一个，否则用户会以为只清眼前这段；
 * 2. **只删本机、对方不受影响**——不说清的话，用户要么以为在替对方删（不敢用），
 *    要么以为删了就没人看得见（误解了隐私边界）；
 * 3. **不可恢复**——它删的是本地历史，没有回收站。
 *
 * 确认按钮复用 `RelayDialogButton(danger = true)`（描边红字）：破坏性动作与「取消」
 * 在形状上就分开，不靠位置区分，宽窄两档下都不会顺手点错。
 */
@Composable
private fun ClearAllHistoryConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    RelayDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.set_clear_history_title)) },
        content = {
            Text(
                text = stringResource(Res.string.set_clear_history_confirm_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            RelayDialogButton(
                text = stringResource(Res.string.set_clear_history_action),
                danger = true,
                onClick = onConfirm,
            )
        },
        dismissButton = {
            RelayDialogButton(
                text = stringResource(Res.string.common_cancel),
                onClick = onDismiss,
            )
        },
    )
}
