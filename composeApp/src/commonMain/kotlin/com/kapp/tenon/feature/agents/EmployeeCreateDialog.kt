// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.kapp.tenon.core.protocol.AgentDuties
import com.kapp.tenon.domain.DriverAuthCheckUi
import com.kapp.tenon.domain.DriverModelCatalogEntryUi
import com.kapp.tenon.domain.DriverOptionStateUi
import com.kapp.tenon.domain.DriverRosterEntryUi
import com.kapp.tenon.domain.DeclarationRejection
import com.kapp.tenon.domain.defaultEmployeeToken
import com.kapp.tenon.domain.driverOptionStateOf
import com.kapp.tenon.domain.employeeDriverOptions
import com.kapp.tenon.ui.components.AppButtonSize
import com.kapp.tenon.ui.components.AppTextButton
import com.kapp.tenon.ui.components.DriverModelPicker
import com.kapp.tenon.ui.components.RelayDialog
import com.kapp.tenon.ui.components.RelayDialogButton
import com.kapp.tenon.ui.components.formatDriverName
import com.kapp.tenon.ui.theme.DriverBrand
import com.kapp.tenon.ui.theme.IconSizes
import com.kapp.tenon.ui.theme.LocalAppSpacing
import com.kapp.tenon.ui.theme.LocalCardShape
import com.kapp.tenon.ui.theme.accents
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.common_unknown_member
import com.kapp.tenon.generated.resources.ic_info
import com.kapp.tenon.generated.resources.ic_chevd
import com.kapp.tenon.generated.resources.agent_employee_name_hint
import com.kapp.tenon.generated.resources.employee_create_title
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.FlowRow
import com.kapp.tenon.core.protocol.AVATAR_LEGACY_INITIAL_VALUE
import com.kapp.tenon.ui.components.PeerAvatar
import com.kapp.tenon.generated.resources.employee_create_preset_label
import com.kapp.tenon.generated.resources.employee_preset_coder
import com.kapp.tenon.generated.resources.employee_preset_coder_duty
import com.kapp.tenon.generated.resources.employee_preset_reviewer
import com.kapp.tenon.generated.resources.employee_preset_reviewer_duty
import com.kapp.tenon.generated.resources.employee_preset_tester
import com.kapp.tenon.generated.resources.employee_preset_tester_duty
import com.kapp.tenon.generated.resources.employee_preset_architect
import com.kapp.tenon.generated.resources.employee_preset_architect_duty
import com.kapp.tenon.generated.resources.employee_create_field_driver
import com.kapp.tenon.generated.resources.employee_create_token_taken
import com.kapp.tenon.generated.resources.employee_create_field_model
import com.kapp.tenon.generated.resources.employee_create_model_of_driver
import com.kapp.tenon.generated.resources.employee_create_dir_hint
import com.kapp.tenon.generated.resources.employee_create_submit
import com.kapp.tenon.generated.resources.employee_create_failed
import com.kapp.tenon.generated.resources.employee_create_name_required
import com.kapp.tenon.generated.resources.employee_create_no_driver
import com.kapp.tenon.generated.resources.employee_create_driver_single
import com.kapp.tenon.generated.resources.employee_create_driver_placeholder
import com.kapp.tenon.generated.resources.driver_state_available
import com.kapp.tenon.generated.resources.driver_state_login
import com.kapp.tenon.generated.resources.driver_state_unknown
import com.kapp.tenon.generated.resources.employee_create_driver_need_login
import com.kapp.tenon.generated.resources.employee_create_driver_more
import com.kapp.tenon.generated.resources.common_cancel
import com.kapp.tenon.generated.resources.exec_config_entry

/**
 * **新建员工**表单（方案 §3.3）：一张单页表单 —— 名字、`@` token、职责，然后是
 * **运行时与其模型**（成组放末尾）。**没有**「项目目录」「用哪个实例」字段：目录由群
 * 决定，实例是路由线索。
 *
 * **运行时与模型成组、放最后**：
 * - 成组：模型候选依赖运行时（换一家，整组候选换掉），是全表单唯一有跨字段依赖的一对；
 * - 放最后：这两格是「用什么跑」的技术选型，用户建档时先想的是「这人叫什么、干什么」。
 *
 * ## 为什么提交走回调而不是直接写档案
 *
 * 写路径在 `:app`（[com.kapp.tenon.domain.EmployeeProfileWritePath]），本层只有回调
 * 形状的端口；预校验失败要**落回字段**（不弹 toast），所以回调返回拒绝枚举。
 *
 * ## 反例定死（方案 §3.3）
 * - 只有一家在位 ⇒ **不画下拉**（点开只有一个选项的下拉是假控件）—— 画只读一行；
 * - 零家在位 ⇒ 表单不展开，主行动去执行配置；
 * - 未登录的运行时**仍可选**（黄字提示），先建人、后登录；
 * - 按钮恒可点：缺谁就标在谁下面，不置灰不说原因。
 */
@OptIn(ExperimentalMaterial3Api::class)
// 公开面：androidApp 的 screenshotTest 跨模块组装预览；生产入口仍在本模块。
@Composable
public fun EmployeeCreateDialog(
    roster: List<DriverRosterEntryUi>?,
    authStates: Map<String, DriverAuthCheckUi>?,
    /**
     * 各驱动当下的模型目录（null = 这个壳没有目录能力 / 还没探完 ⇒ 只给手填）。
     * 与员工编辑、团队编制读同一份事实。
     */
    modelCatalog: Map<String, DriverModelCatalogEntryUi>? = null,
    takenTokens: Set<String>,
    onSubmit: (driverKey: String, model: String?, name: String, token: String, duty: String) -> DeclarationRejection?,
    onOpenExecutionConfig: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val sp = LocalAppSpacing.current
    val options = employeeDriverOptions(roster)
    val hasRuntime = options.isNotEmpty()

    var selectedKey by remember { mutableStateOf(options.singleOrNull()?.key.orEmpty()) }
    var name by remember { mutableStateOf(TextFieldValue("")) }
    var model by remember { mutableStateOf(TextFieldValue("")) }
    var duty by remember { mutableStateOf(AgentDuties.DEFAULT_MINT) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var driverError by remember { mutableStateOf<String?>(null) }
    var formError by remember { mutableStateOf<String?>(null) }
    var dropdownOpen by remember { mutableStateOf(false) }

    // onClick 是普通回调（非组合作用域）—— stringResource 必须先在这里取好。
    val submitText = stringResource(Res.string.employee_create_submit)
    val noDriverText = stringResource(Res.string.employee_create_no_driver)
    // 「没选」与「一台都没有」是两件事：有得选而没选时，说「这台电脑没有运行时」是错的。
    val pickDriverText = stringResource(Res.string.employee_create_driver_placeholder)
    val tokenTakenText = stringResource(Res.string.employee_create_token_taken)
    val nameRequiredText = stringResource(Res.string.employee_create_name_required)
    val formFailedText = stringResource(Res.string.employee_create_failed)

    // token 缺省 = defaultEmployeeToken（自动去重与 slug 归一，完全静默，无需用户理解）。
    val finalToken = remember(name.text, selectedKey, duty, takenTokens) {
        defaultEmployeeToken(name.text, selectedKey, "", duty, takenTokens)
    }

    RelayDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.employee_create_title)) },
        content = {
            if (!hasRuntime) {
                Text(
                    text = stringResource(Res.string.employee_create_no_driver),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (onOpenExecutionConfig != null) {
                    RelayDialogButton(
                        text = stringResource(Res.string.exec_config_entry),
                        onClick = { onDismiss(); onOpenExecutionConfig() },
                    )
                }
                return@RelayDialog
            }
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(sp.sm),
            ) {
                // 1. 头像 + 员工姓名
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(sp.md),
                ) {
                    PeerAvatar(
                        name = name.text.ifBlank { stringResource(Res.string.common_unknown_member) },
                        avatar = AVATAR_LEGACY_INITIAL_VALUE,
                        size = 48.dp,
                        shape = LocalCardShape.current,
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = {
                            name = it
                            if (nameError != null && it.text.isNotBlank()) nameError = null
                        },
                        placeholder = {
                            Text(
                                text = stringResource(Res.string.agent_employee_name_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            )
                        },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                            unfocusedBorderColor = Color.Transparent,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        isError = nameError != null,
                        supportingText = nameError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                        modifier = Modifier.weight(1f),
                    )
                }

                // 2. 常用角色灵感
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(sp.xs),
                ) {
                    Text(
                        text = stringResource(Res.string.employee_create_preset_label),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    val presets = listOf(
                        Triple(Res.string.employee_preset_coder, "coder", Res.string.employee_preset_coder_duty),
                        Triple(Res.string.employee_preset_reviewer, "reviewer", Res.string.employee_preset_reviewer_duty),
                        Triple(Res.string.employee_preset_tester, "tester", Res.string.employee_preset_tester_duty),
                        Triple(Res.string.employee_preset_architect, "lead", Res.string.employee_preset_architect_duty),
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(sp.xs),
                        verticalArrangement = Arrangement.spacedBy(sp.xs),
                    ) {
                        presets.forEach { (labelRes, dutyCode, _) ->
                            val label = stringResource(labelRes)
                            val isSelected = duty == dutyCode
                            Surface(
                                shape = CircleShape,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                },
                                modifier = Modifier.clickable {
                                    // 预设只改职责，不替用户命名：角色标签是中文灵感词，
                                    // 填进姓名框会生成一条 @ token 与显示名错位的档案。
                                    duty = dutyCode
                                },
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.padding(horizontal = sp.sm, vertical = sp.xxs),
                                )
                            }
                        }
                    }
                    presets.firstOrNull { it.second == duty }?.third?.let { hintRes ->
                        Text(
                            text = stringResource(hintRes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // 3. 运行时
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(sp.xs),
                ) {
                    Text(
                        text = stringResource(Res.string.employee_create_field_driver),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (options.size == 1) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Box(modifier = Modifier.padding(horizontal = sp.sm, vertical = sp.sm6)) {
                                DriverOptionRow(options.single(), authStates, showOnlyOneHint = true)
                            }
                        }
                    } else {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { dropdownOpen = true },
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = sp.sm, vertical = sp.sm6),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    val selectedOption = options.firstOrNull { it.key == selectedKey }
                                    if (selectedOption != null) {
                                        DriverOptionRow(selectedOption, authStates, showOnlyOneHint = false)
                                    } else {
                                        Text(
                                            text = pickDriverText,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Icon(
                                        painter = painterResource(Res.drawable.ic_chevd),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(sp.sm),
                                    )
                                }
                            }
                            DropdownMenu(
                                expanded = dropdownOpen,
                                onDismissRequest = { dropdownOpen = false },
                            ) {
                                options.forEach { option ->
                                    DropdownMenuItem(
                                        text = { DriverOptionRow(option, authStates, showOnlyOneHint = false) },
                                        onClick = {
                                            selectedKey = option.key
                                            driverError = null
                                            dropdownOpen = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                    if (driverError != null) {
                        Text(
                            text = driverError.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                val selectedState = driverOptionStateOf(selectedKey, authStates)
                if (selectedState == DriverOptionStateUi.NEEDS_LOGIN) {
                    Text(
                        text = stringResource(Res.string.employee_create_driver_need_login),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                // —— 模型 ——（紧跟运行时成组）
                if (selectedKey.isNotBlank()) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(sp.xs),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(sp.xs),
                        ) {
                            Text(
                                text = stringResource(Res.string.employee_create_field_model),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(
                                    Res.string.employee_create_model_of_driver,
                                    formatDriverName(selectedKey),
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        DriverModelPicker(
                            entry = modelCatalog?.get(selectedKey.trim().lowercase()),
                            currentModel = model.text.trim().ifBlank { null },
                            onModelChange = { model = TextFieldValue(it.orEmpty()) },
                        )
                    }
                }

                // —— 提示与更多运行时入口（整合进精致 Tip 容器）——
                Surface(
                    shape = LocalCardShape.current,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth().padding(top = sp.xs),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = sp.md, vertical = sp.sm6),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(sp.xs),
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_info),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.size(IconSizes.Inline),
                        )
                        Text(
                            text = stringResource(Res.string.employee_create_dir_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        if (onOpenExecutionConfig != null) {
                            AppTextButton(
                                text = stringResource(Res.string.employee_create_driver_more),
                                onClick = { onDismiss(); onOpenExecutionConfig() },
                                size = AppButtonSize.Compact,
                            )
                        }
                    }
                }

                // 全局错误提示
                formError?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            RelayDialogButton(
                // 按钮恒可点（§3.3：置灰且不说原因是最差的一档）——点了再逐字段校验。
                text = submitText,
                primary = true,
                onClick = {
                    formError = null
                    val trimmedName = name.text.trim()
                    if (trimmedName.isEmpty()) {
                        nameError = nameRequiredText
                        return@RelayDialogButton
                    }
                    if (selectedKey.isEmpty()) {
                        driverError = pickDriverText
                        return@RelayDialogButton
                    }
                    when (val rejection = onSubmit(selectedKey, model.text.trim().ifEmpty { null }, trimmedName, finalToken, duty)) {
                        null -> onDismiss()
                        // token 撞名与 slug 非法同一条出路：换个 @ 名。域层用同一个
                        // NAME_INVALID 报这两件事，界面这边不该编出两种说法。
                        DeclarationRejection.EMPLOYEE_EXISTS, DeclarationRejection.NAME_INVALID -> {
                            nameError = tokenTakenText
                        }
                        DeclarationRejection.DRIVER_UNAVAILABLE -> driverError = noDriverText
                        else -> formError = formFailedText
                    }
                },
            )
        },
        dismissButton = {
            RelayDialogButton(text = stringResource(Res.string.common_cancel), onClick = onDismiss)
        },
    )
}

/**
 * 下拉项 / 选择行的内容：色块首字 + 友好名 +（有检测结果时的）状态词。
 *
 * 色块用 `accents.surface3` 打底 + 品牌色作字色 —— 与盘面卡同一形态。实底 + `onPrimary`
 * 的做法在浅色品牌（Antigravity 的蓝、Zhipu 的靛）上会把首字糊掉，深色主题下更糟。
 */
@Composable
internal fun DriverOptionRow(
    option: DriverRosterEntryUi,
    authStates: Map<String, DriverAuthCheckUi>?,
    showOnlyOneHint: Boolean,
) {
    val sp = LocalAppSpacing.current
    val state = driverOptionStateOf(option.key, authStates)
    val tint = DriverBrand.tintOf(option.vendor)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(sp.sm6),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .size(sp.xl)
                .background(MaterialTheme.accents.surface3, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = option.shortName.take(1).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = tint,
            )
        }
        Text(
            text = option.displayName,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (showOnlyOneHint) {
            Text(
                text = stringResource(Res.string.employee_create_driver_single),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (state) {
            DriverOptionStateUi.AVAILABLE -> Text(
                text = stringResource(Res.string.driver_state_available),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            DriverOptionStateUi.NEEDS_LOGIN -> Text(
                text = stringResource(Res.string.driver_state_login),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            DriverOptionStateUi.UNKNOWN -> Text(
                text = stringResource(Res.string.driver_state_unknown),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            null -> Unit // 没检测过 = 沉默一档（不是「可用」）
        }
    }
}
