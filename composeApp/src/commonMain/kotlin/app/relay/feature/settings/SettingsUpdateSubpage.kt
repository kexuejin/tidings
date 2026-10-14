// SPDX-License-Identifier: GPL-3.0-only

package app.relay.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import app.relay.app.RelayUiState
import app.relay.feature.update.UpdateCheckStatus
import app.relay.generated.resources.Res
import app.relay.generated.resources.set_update_app_version
import app.relay.generated.resources.set_update_available
import app.relay.generated.resources.set_update_check_action
import app.relay.generated.resources.set_update_checking
import app.relay.generated.resources.set_update_failed
import app.relay.generated.resources.set_update_latest
import app.relay.generated.resources.set_update_open_action
import app.relay.generated.resources.set_update_subtitle
import app.relay.generated.resources.set_update_title
import app.relay.ui.SectionCard
import app.relay.ui.components.InfoRow
import app.relay.ui.theme.LocalAppSpacing
import org.jetbrains.compose.resources.stringResource

/**
 * 设置 · 版本与更新（**原型里没有这一页**）。
 *
 * 这一页与另外五个子页不同：原型 `screenSetNotify/Appear/Caps/Data/Diag`
 * 五张页里没有更新页，所以它**没有原型对应物**，是代码自有段。
 * 拆出来只是为了让「版本与更新」不再寄生在别的页上——合并页时代它排在
 * 「诊断」之后，任何一张诊断页的帧都会连带拍到它，反过来它自己的分支
 * （检查中 / 已最新 / 有新版 / 失败 / 空闲 五支）也就没有一张帧是**为它拍的**。
 *
 * `UpdateCheckStatus.Checking` 时「检查更新」按钮禁用：一个正在跑的检查
 * 不该能被再点一次。
 */
@Composable
fun SettingsUpdateSubpage(
    state: RelayUiState,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    appVersion: String? = null,
    onCheckForUpdates: (() -> Unit)? = null,
    onOpenUpdate: (() -> Unit)? = null,
) {
    SettingsPageScaffold(
        title = stringResource(Res.string.set_update_title),
        subtitle = stringResource(Res.string.set_update_subtitle),
        onBack = onBack,
        modifier = modifier,
    ) {
        SectionCard(title = stringResource(Res.string.set_update_title)) {
            val currentVer = appVersion ?: "relay-0.1.0"
            InfoRow(
                label = stringResource(Res.string.set_update_app_version),
                value = currentVer,
            )

            val updateState = state.updateState
            when (updateState.status) {
                UpdateCheckStatus.Checking -> {
                    Text(
                        text = stringResource(Res.string.set_update_checking),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = LocalAppSpacing.current.xs),
                    )
                }
                UpdateCheckStatus.UpToDate -> {
                    Text(
                        text = stringResource(Res.string.set_update_latest),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = LocalAppSpacing.current.xs),
                    )
                }
                UpdateCheckStatus.UpdateAvailable -> {
                    val newVer = updateState.info?.latestVersion.orEmpty()
                    Text(
                        text = stringResource(Res.string.set_update_available, newVer),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = LocalAppSpacing.current.xs),
                    )
                    updateState.info?.releaseNotes?.takeIf { it.isNotBlank() }?.let { notes ->
                        Text(
                            text = notes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = LocalAppSpacing.current.xs),
                        )
                    }
                }
                UpdateCheckStatus.Error -> {
                    Text(
                        text = stringResource(Res.string.set_update_failed, updateState.errorMessage.orEmpty()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = LocalAppSpacing.current.xs),
                    )
                }
                UpdateCheckStatus.Idle -> Unit
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = LocalAppSpacing.current.sm),
                horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm),
            ) {
                OutlinedButton(
                    onClick = { onCheckForUpdates?.invoke() },
                    enabled = onCheckForUpdates != null && updateState.status != UpdateCheckStatus.Checking,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(Res.string.set_update_check_action))
                }
                if (updateState.status == UpdateCheckStatus.UpdateAvailable && onOpenUpdate != null) {
                    Button(
                        onClick = { onOpenUpdate.invoke() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(Res.string.set_update_open_action))
                    }
                }
            }
        }
    }
}
