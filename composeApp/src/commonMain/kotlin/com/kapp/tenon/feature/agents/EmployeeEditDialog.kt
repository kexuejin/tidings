// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.agents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import org.jetbrains.compose.resources.painterResource
import com.kapp.tenon.generated.resources.ic_chevd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kapp.tenon.core.protocol.AVATAR_LEGACY_INITIAL_VALUE
import com.kapp.tenon.core.protocol.AgentDuties
import com.kapp.tenon.core.protocol.AgentExecPolicies
import com.kapp.tenon.domain.DriverAuthCheckUi
import com.kapp.tenon.domain.DriverModelCatalogEntryUi
import com.kapp.tenon.domain.DriverRosterEntryUi
import com.kapp.tenon.domain.employeeDriverOptions
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.common_unknown_member
import com.kapp.tenon.generated.resources.agent_duty_analyst
import com.kapp.tenon.generated.resources.agent_duty_assistant
import com.kapp.tenon.generated.resources.agent_duty_coder
import com.kapp.tenon.generated.resources.agent_duty_lead
import com.kapp.tenon.generated.resources.agent_duty_ops
import com.kapp.tenon.generated.resources.agent_duty_reviewer
import com.kapp.tenon.generated.resources.agent_duty_tester
import com.kapp.tenon.generated.resources.agent_duty_writer
import com.kapp.tenon.generated.resources.agent_employee_dialog_title_edit
import com.kapp.tenon.generated.resources.agent_employee_dialog_title_register
import com.kapp.tenon.generated.resources.agent_employee_duty_label
import com.kapp.tenon.generated.resources.agent_employee_name_hint
import com.kapp.tenon.generated.resources.agent_employee_save
import com.kapp.tenon.generated.resources.agent_employee_scope_label
import com.kapp.tenon.generated.resources.agent_employee_scope_members
import com.kapp.tenon.generated.resources.agent_employee_scope_members_hint
import com.kapp.tenon.generated.resources.agent_employee_scope_owner
import com.kapp.tenon.generated.resources.agent_employee_scope_owner_hint
import com.kapp.tenon.generated.resources.common_cancel
import com.kapp.tenon.generated.resources.employee_create_field_driver
import com.kapp.tenon.generated.resources.employee_create_driver_placeholder
import com.kapp.tenon.generated.resources.employee_create_field_model
import com.kapp.tenon.ui.components.DriverModelPicker
import com.kapp.tenon.ui.components.PeerAvatar
import com.kapp.tenon.ui.components.RelayDialog
import com.kapp.tenon.ui.components.RelayDialogButton
import com.kapp.tenon.ui.theme.LocalAppSpacing
import com.kapp.tenon.ui.theme.LocalCardShape
import org.jetbrains.compose.resources.stringResource

/**
 * 「登记为员工 / 编辑员工」：给本机某个 agent 一份**业务名与职责**。
 *
 * ## 为什么是"登记"而不是"创建"
 *
 * agent 是「这台机器上真的有一个进程在跑」，用户不能凭空造一个。这个对话框做的是
 * **登记 + 命名**：把本机台账里已有的这个 agent 记成一名员工，给它一个人叫得出来的名字。
 * 素材（agentId）来自点开的那张卡，所以这里不给"选 agent"这一步 —— 选了也选不出
 * 台账里不存在的 agent。
 *
 * ## 名字与 `@` token 是两件事
 *
 * [employeeName] 可含中文；群里 `@` 用的 token 由它派生（`slug`），并且在**建档那一刻定下来**。
 * 所以这里改名**不会**改变群里的 `@` 名字 —— 副行那句提示就是讲这件事，避免用户
 * 改完名字发现 `@` 没变而以为没生效。真要换 token 走写路径的显式参数（界面暂不给）。
 *
 * ## 职责是有限取值
 *
 * 选项来自 [AgentDuties.ALL]（与生成默认名的同一份清单）。不给自由文本：域外值会被
 * `normalize` 悄悄折回 `assistant`，用户以为设了、其实没设。
 *
 * @param employeeId null = 这个 agent 还没档案（登记）；非 null = 编辑（按它定位，不按名字）。
 */
// 公开面：androidApp 的 screenshotTest 跨模块组装预览；生产入口仍在本模块。
@Composable
public fun EmployeeEditDialog(
    employeeId: String?,
    initialName: String,
    initialDuty: String,
    initialDriverKey: String = "",
    initialModel: String? = null,
    roster: List<DriverRosterEntryUi>? = null,
    authStates: Map<String, DriverAuthCheckUi>? = null,
    /**
     * 各驱动当下的模型目录（null = 这个壳没有目录能力 / 还没探完 ⇒ 只给手填）。
     *
     * 与团队编制同一条数据：端口 `driverModelCatalog()` 的结果，按驱动键查。员工档案与
     * 团队执行档是**两个存储**，但「这家有什么模型」是同一个事实，所以读同一份目录。
     */
    modelCatalog: Map<String, DriverModelCatalogEntryUi>? = null,
    /**
     * 当前「谁能调用」档位（[AgentExecPolicies] 取值）。
     *
     * null = 这个壳读不到宿主策略（手机）⇒ **整段不画**：策略住在宿主机的
     * `AgentExecPolicyStore` 里，手机上给一个点了不生效的开关是本仓最贵的故障形态。
     */
    initialPolicy: String?,
    /**
     * @param driverKey null = **运行时这一栏没动**（老数据本来就没绑、用户也只改了名字）；
     *   非空 = 绑到这一家。**不许在这里替用户选一个默认值**：把一个 `driverKey` 为空的
     *   档案静默绑到「名册第一家」，等于替用户改了「这个人用什么跑」。
     * @param model null = 没动这一栏；空串 = 清空（回到驱动默认）；非空 = 设成它。
     */
    onSave: (name: String, duty: String, policy: String, driverKey: String?, model: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sp = LocalAppSpacing.current
    val options = employeeDriverOptions(roster)
    // 初值就是档案里的值，**不回落**到 options.first()：见 onSave 的 @param。
    var selectedDriverKey by remember { mutableStateOf(initialDriverKey.trim()) }
    var modelText by remember { mutableStateOf(initialModel.orEmpty()) }
    var dropdownOpen by remember { mutableStateOf(false) }

    var name by remember { mutableStateOf(initialName) }
    var duty by remember { mutableStateOf(AgentDuties.normalize(initialDuty)) }
    var policy by remember { mutableStateOf(AgentExecPolicies.normalize(initialPolicy)) }
    val canSave = name.isNotBlank()
    val selectedOption = options.firstOrNull { it.key == selectedDriverKey }
    val supportsModel = selectedOption?.supportsPerTaskModel == true

    RelayDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (employeeId == null) {
                        Res.string.agent_employee_dialog_title_register
                    } else {
                        Res.string.agent_employee_dialog_title_edit
                    },
                ),
            )
        },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(sp.sm)) {
                // 1. 头像 + 员工姓名
                val avatarName = name.trim().ifBlank { initialName.ifBlank { stringResource(Res.string.common_unknown_member) } }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(sp.md),
                ) {
                    PeerAvatar(
                        name = avatarName,
                        avatar = AVATAR_LEGACY_INITIAL_VALUE,
                        size = 48.dp,
                        shape = LocalCardShape.current,
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
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
                        modifier = Modifier.weight(1f),
                    )
                }

                // 2. 运行时
                if (roster != null && options.isNotEmpty()) {
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
                        Box(modifier = Modifier.fillMaxWidth()) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (options.size == 1) {
                                            selectedDriverKey = options.single().key
                                        } else {
                                            dropdownOpen = true
                                        }
                                    },
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = sp.sm, vertical = sp.sm6),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    if (selectedOption != null) {
                                        DriverOptionRow(selectedOption, authStates, showOnlyOneHint = options.size == 1)
                                    } else {
                                        Text(
                                            text = stringResource(Res.string.employee_create_driver_placeholder),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (options.size > 1) {
                                        Icon(
                                            painter = painterResource(Res.drawable.ic_chevd),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(sp.sm),
                                        )
                                    }
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
                                            if (option.key != selectedDriverKey) modelText = ""
                                            selectedDriverKey = option.key
                                            dropdownOpen = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                    if (supportsModel) {
                        Column(modifier = Modifier.fillMaxWidth().padding(top = sp.xs)) {
                            Text(
                                text = stringResource(Res.string.employee_create_field_model),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = sp.xs),
                            )
                            DriverModelPicker(
                                entry = modelCatalog?.get(selectedDriverKey.trim().lowercase()),
                                currentModel = modelText.takeIf { it.isNotBlank() },
                                onModelChange = { modelText = it.orEmpty() },
                                showFollowDefault = true,
                            )
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(sp.xs)) {
                    Text(
                        text = stringResource(Res.string.agent_employee_duty_label),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(sp.xs),
                        verticalArrangement = Arrangement.spacedBy(sp.xs),
                    ) {
                        AgentDuties.ALL.forEach { candidate ->
                            DutyChip(
                                label = formatDutyLabel(candidate),
                                selected = candidate == duty,
                                onClick = { duty = candidate },
                            )
                        }
                    }
                }

                // 「谁能调用」只在读得到宿主策略时画（手机整段不画）。
                if (initialPolicy != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(sp.xs)) {
                        Text(
                            text = stringResource(Res.string.agent_employee_scope_label),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(sp.xs),
                            verticalArrangement = Arrangement.spacedBy(sp.xs),
                        ) {
                            DutyChip(
                                label = stringResource(Res.string.agent_employee_scope_owner),
                                selected = policy == AgentExecPolicies.LIST,
                                onClick = { policy = AgentExecPolicies.LIST },
                            )
                            DutyChip(
                                label = stringResource(Res.string.agent_employee_scope_members),
                                selected = policy == AgentExecPolicies.ANY_MEMBER,
                                onClick = { policy = AgentExecPolicies.ANY_MEMBER },
                            )
                        }
                        Text(
                            text = stringResource(
                                if (policy == AgentExecPolicies.LIST) {
                                    Res.string.agent_employee_scope_owner_hint
                                } else {
                                    Res.string.agent_employee_scope_members_hint
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            RelayDialogButton(
                text = stringResource(Res.string.agent_employee_save),
                primary = true,
                enabled = canSave,
                onClick = {
                    onSave(
                        name.trim(),
                        duty,
                        policy,
                        selectedDriverKey.trim().ifEmpty { null },
                        if (supportsModel) modelText.trim() else null,
                    )
                },
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
private fun formatDutyLabel(duty: String): String = when (duty.trim().lowercase()) {
    AgentDuties.LEAD -> stringResource(Res.string.agent_duty_lead)
    AgentDuties.CODER -> stringResource(Res.string.agent_duty_coder)
    AgentDuties.REVIEWER -> stringResource(Res.string.agent_duty_reviewer)
    AgentDuties.TESTER -> stringResource(Res.string.agent_duty_tester)
    AgentDuties.ANALYST -> stringResource(Res.string.agent_duty_analyst)
    AgentDuties.WRITER -> stringResource(Res.string.agent_duty_writer)
    AgentDuties.OPS -> stringResource(Res.string.agent_duty_ops)
    AgentDuties.ASSISTANT -> stringResource(Res.string.agent_duty_assistant)
    else -> duty
}

/** 职责选项：选中 = 主色底；未选 = 中性底。两态都用主题色，不写死颜色。 */
@Composable
private fun DutyChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val sp = LocalAppSpacing.current
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = LocalCardShape.current,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = sp.sm, vertical = sp.xs),
        )
    }
}
