// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.agents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.kapp.tenon.core.protocol.AgentDuties
import com.kapp.tenon.core.protocol.AgentExecPolicies
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.agent_employee_dialog_title_edit
import com.kapp.tenon.generated.resources.agent_employee_dialog_title_register
import com.kapp.tenon.generated.resources.agent_employee_duty_label
import com.kapp.tenon.generated.resources.agent_employee_name_hint
import com.kapp.tenon.generated.resources.agent_employee_name_label
import com.kapp.tenon.generated.resources.agent_employee_save
import com.kapp.tenon.generated.resources.agent_employee_scope_label
import com.kapp.tenon.generated.resources.agent_employee_scope_members
import com.kapp.tenon.generated.resources.agent_employee_scope_members_hint
import com.kapp.tenon.generated.resources.agent_employee_scope_owner
import com.kapp.tenon.generated.resources.agent_employee_scope_owner_hint
import com.kapp.tenon.generated.resources.agent_employee_token_hint
import com.kapp.tenon.generated.resources.common_cancel
import com.kapp.tenon.ui.components.RelayDialog
import com.kapp.tenon.ui.components.RelayDialogButton
import com.kapp.tenon.ui.theme.LocalAppSpacing
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
@Composable
internal fun EmployeeEditDialog(
    employeeId: String?,
    initialName: String,
    initialDuty: String,
    /**
     * 当前「谁能调用」档位（[AgentExecPolicies] 取值）。
     *
     * null = 这个壳读不到宿主策略（手机）⇒ **整段不画**：策略住在宿主机的
     * `AgentExecPolicyStore` 里，手机上给一个点了不生效的开关是本仓最贵的故障形态。
     */
    initialPolicy: String?,
    onSave: (name: String, duty: String, policy: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var duty by remember { mutableStateOf(AgentDuties.normalize(initialDuty)) }
    var policy by remember { mutableStateOf(AgentExecPolicies.normalize(initialPolicy)) }
    val sp = LocalAppSpacing.current
    val canSave = name.isNotBlank()

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
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(Res.string.agent_employee_name_label)) },
                    placeholder = { Text(stringResource(Res.string.agent_employee_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(Res.string.agent_employee_duty_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = sp.md, bottom = sp.xs),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(sp.xs),
                    verticalArrangement = Arrangement.spacedBy(sp.xs),
                ) {
                    AgentDuties.ALL.forEach { candidate ->
                        DutyChip(
                            label = candidate,
                            selected = candidate == duty,
                            onClick = { duty = candidate },
                        )
                    }
                }
                Text(
                    text = stringResource(Res.string.agent_employee_token_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = sp.sm),
                )
                // 「谁能调用」只在读得到宿主策略时画（手机整段不画）。
                if (initialPolicy != null) {
                    Text(
                        text = stringResource(Res.string.agent_employee_scope_label),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = sp.md, bottom = sp.xs),
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
                        modifier = Modifier.padding(top = sp.xs),
                    )
                }
            }
        },
        confirmButton = {
            RelayDialogButton(
                text = stringResource(Res.string.agent_employee_save),
                primary = true,
                enabled = canSave,
                onClick = { onSave(name.trim(), duty, policy) },
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

/** 职责选项：选中 = 主色底；未选 = 中性底。两态都用主题色，不写死颜色。 */
@Composable
private fun DutyChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val sp = LocalAppSpacing.current
    val background = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = background,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = sp.sm, vertical = sp.xxs),
        )
    }
}
