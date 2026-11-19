// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kapp.tenon.domain.DriverModelCatalogEntryUi
import com.kapp.tenon.domain.DriverModelCatalogStateUi
import com.kapp.tenon.domain.TeamExecutionPort
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.team_exec_model_placeholder
import com.kapp.tenon.generated.resources.team_model_catalog_loading
import com.kapp.tenon.generated.resources.team_model_catalog_unknown
import com.kapp.tenon.generated.resources.team_model_custom_toggle
import com.kapp.tenon.generated.resources.team_model_follow_default
import com.kapp.tenon.ui.theme.LocalAppSpacing
import org.jetbrains.compose.resources.stringResource

/**
 * 模型目录的**组合级单源**（与 `LocalDriverRoster` 同形）：打开编制时探一次，
 * 全应用读同一份。null = 这个壳没有目录能力（手机 / 预览），或还没探完。
 *
 * ## 为什么在组合层探，而不是端口层常驻
 *
 * 判据与登录态检测同一套：**自动预检不做**（冷启动多背十几秒），**打开编制才探**。
 * 探针侧按驱动缓存（TTL 见 `DriverModelCatalog`），反复打开编制不会反复起子进程。
 *
 * ## 为什么失败不弹框
 *
 * 目录是「让手填少犯错」的辅助面，不是派单前提。读不到就退化成手填 —— 探针异常一律
 * 吞成 UNKNOWN，绝不冒到 UI 变成模态错误框（与 agy 登录探针同一条纪律）。
 */
@Composable
internal fun rememberDriverModelCatalog(
    port: TeamExecutionPort?,
    driverKeys: Set<String>,
): Map<String, DriverModelCatalogEntryUi>? {
    // 只按「端口 + 在位驱动集合」重探：装了/卸了驱动才需要重读；改某个成员的模型不触发。
    val keysKey = remember(driverKeys) { driverKeys.map { it.lowercase() }.sorted().joinToString(",") }
    var catalog by remember(port, keysKey) {
        mutableStateOf<Map<String, DriverModelCatalogEntryUi>?>(null)
    }
    LaunchedEffect(port, keysKey) {
        val p = port ?: return@LaunchedEffect
        catalog = runCatching { p.driverModelCatalog() }.getOrNull()
    }
    return catalog
}

/**
 * 该驱动能不能按次指定模型 —— 唯一来源是名册投影（UI 不许 import `CliDriverSpecs`）。
 *
 * 名册不在（手机 / 预览 / 老装配）时**放行**：宁可多给一档，也不在缺失事实时凭空禁掉配置。
 * 这个函数只此一份：编辑入口都读它，别再各写一条 if 链。
 */
@Composable
internal fun driverSupportsPerTaskModel(driverKey: String): Boolean {
    val roster = LocalDriverRoster.current ?: return true
    return roster.firstOrNull { it.key.equals(driverKey.trim(), ignoreCase = true) }
        ?.supportsPerTaskModel ?: true
}

/**
 * 目录里**确定已知**的模型 id；null = 这家没探到（放行）。
 *
 * 只收 KNOWN 且非空：UNKNOWN 缺键是「不知道」，空集不许冒充「一个都没有」。
 */
internal fun knownModelIdsOf(
    catalog: Map<String, DriverModelCatalogEntryUi>?,
    driverKey: String,
): Set<String>? {
    val key = driverKey.trim().lowercase()
    val entry = catalog?.get(key) ?: return null
    if (entry.state != DriverModelCatalogStateUi.KNOWN || entry.models.isEmpty()) return null
    return entry.models.map { it.id }.toSet()
}

/**
 * 模型选择：**下拉列表**（与「运行时」下拉同形）+ 手填兜底。唯一实现，编辑入口共用。
 *
 * ## 为什么是下拉而不是 chips
 *
 * 试过 chips（FlowRow 内容宽度），问题在于：目录实测可到百余条（某家一次 137 个），
 * chips 必然要「只画前 N 条 + 展开全部」，于是「看得见的少数」和「真实可选的多数」
 * 分成两档，用户得先点一次「展开」才敢确认没有漏。而下拉是一份完整列表、可滚动，
 * 且与紧邻的「运行时」是同一个交互形状 —— 两格连着放时，同形比好看更重要。
 *
 * ## 三态各有下一个动作
 *
 * - [DriverModelCatalogStateUi.KNOWN]：下拉列出目录 + 首项「跟随驱动默认」+ 末项「手填」；
 * - [DriverModelCatalogStateUi.UNKNOWN]：下拉里只有「跟随默认」，另给手填输入框。读不到 ≠ 没有；
 * - 目录为 null（壳没有探针 / 还没探完）：只留手填输入框，不画状态行。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DriverModelPicker(
    entry: DriverModelCatalogEntryUi?,
    currentModel: String?,
    onModelChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
    /** true = 提供「跟随驱动默认」那一档（该驱动不支持按次指定模型时由调用方置 false）。 */
    showFollowDefault: Boolean = true,
) {
    val sp = LocalAppSpacing.current
    val models = entry?.models.orEmpty()
    val isKnown = entry?.state == DriverModelCatalogStateUi.KNOWN && models.isNotEmpty()
    var expanded by remember { mutableStateOf(false) }
    // 手填框：目录已知时默认收起（不占版面）；未知 / 无目录时直接给输入框。
    // 有值且不在目录里 ⇒ 展开（那是用户自己填的，得看得见）。
    var customExpanded by remember(currentModel, entry) {
        mutableStateOf(
            !currentModel.isNullOrBlank() && (!isKnown || models.none { it.id == currentModel }),
        )
    }

    val followLabel = if (showFollowDefault) {
        stringResource(Res.string.team_model_follow_default)
    } else {
        null
    }
    // 收起态显示的文本：什么都没选 = 「跟随默认」；选过 = 该模型的可读名（找不到就显示 id）。
    val display = when {
        currentModel.isNullOrBlank() -> followLabel ?: stringResource(Res.string.team_exec_model_placeholder)
        else -> models.firstOrNull { it.id == currentModel }?.label?.ifBlank { currentModel }
            ?: currentModel
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(sp.xs)) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = display,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
                singleLine = true,
                supportingText = when {
                    entry?.state == DriverModelCatalogStateUi.UNKNOWN -> {
                        { Text(stringResource(Res.string.team_model_catalog_unknown)) }
                    }
                    entry?.state == DriverModelCatalogStateUi.NOT_PROBED -> {
                        { Text(stringResource(Res.string.team_model_catalog_loading)) }
                    }
                    else -> null
                },
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                // 长目录（实测百余条）必须有高度上限，否则菜单会顶出屏幕。
                modifier = Modifier.heightIn(max = MENU_MAX_HEIGHT),
            ) {
                if (followLabel != null) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = followLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        onClick = {
                            onModelChange(null)
                            customExpanded = false
                            expanded = false
                        },
                    )
                }
                models.forEach { model ->
                    val selected = currentModel == model.id
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = model.label.ifBlank { model.id },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        onClick = {
                            onModelChange(model.id)
                            customExpanded = false
                            expanded = false
                        },
                    )
                }
                // 手填入口放在列表末尾：别处找不到时（CLI 别名、新上线模型）仍能进去。
                //
                // **不许在 DropdownMenuItem 里再套 TextButton**：两层可点击元素会让
                // 语义树里出现「按钮套按钮」，无障碍工具（与本机的 AX 桥）读不到外层项，
                // 表现为「菜单里点了没反应」。整行就是一个 item，文字用 labelSmall 区分层级。
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(Res.string.team_model_custom_toggle),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                    onClick = {
                        customExpanded = true
                        expanded = false
                    },
                )
            }
        }

        // 手填输入框：只在「未知/无目录」或用户显式选了「手填」时出现。
        val alwaysShowInput = !isKnown
        if (alwaysShowInput || customExpanded) {
            OutlinedTextField(
                value = currentModel.orEmpty(),
                onValueChange = { raw -> onModelChange(raw.trim().take(MODEL_ID_MAX).ifBlank { null }) },
                placeholder = {
                    Text(
                        text = stringResource(Res.string.team_exec_model_placeholder),
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
                singleLine = true,
            )
        }
    }
}

/** 下拉菜单最大高度：长目录可滚，不把弹窗顶穿。 */
private val MENU_MAX_HEIGHT = 280.dp

/** 与 `Limits.AGENT_MODEL_ID` 同值：UI 侧只做长度防呆，权威上限在保存校验。 */
private const val MODEL_ID_MAX: Int = 64
