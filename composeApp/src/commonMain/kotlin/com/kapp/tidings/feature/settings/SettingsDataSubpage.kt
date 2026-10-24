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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.ui.components.AppButtonTone
import com.kapp.tidings.ui.components.AppOutlinedButton
import com.kapp.tidings.generated.resources.common_cancel
import com.kapp.tidings.generated.resources.ic_folder
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
import com.kapp.tidings.generated.resources.set_retention_summary
import com.kapp.tidings.generated.resources.set_storage_usage
import com.kapp.tidings.generated.resources.set_storage_usage_counting
import com.kapp.tidings.generated.resources.set_storage_usage_value
import com.kapp.tidings.ui.SectionCard
import com.kapp.tidings.ui.components.InfoRow
import com.kapp.tidings.ui.components.RelayDialog
import com.kapp.tidings.ui.components.RelayDialogButton
import com.kapp.tidings.ui.components.formatBytes
import com.kapp.tidings.ui.theme.LocalAppSpacing
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
    retentionDays: Int = DEFAULT_RETENTION_DAYS,
    onClearHistory: (() -> Unit)? = null,
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
