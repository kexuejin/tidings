// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.agents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.kapp.tenon.domain.TeamScaffoldSpec
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.agent_team_confirm
import com.kapp.tenon.generated.resources.agent_team_exec_rejected
import com.kapp.tenon.generated.resources.agent_team_generate
import com.kapp.tenon.generated.resources.agent_team_generate_failed
import com.kapp.tenon.generated.resources.agent_team_generating
import com.kapp.tenon.generated.resources.agent_team_groups_label
import com.kapp.tenon.generated.resources.agent_team_kind_reviewer
import com.kapp.tenon.generated.resources.agent_team_kind_worker
import com.kapp.tenon.generated.resources.agent_team_need_hint
import com.kapp.tenon.generated.resources.agent_team_need_label
import com.kapp.tenon.generated.resources.agent_team_preview_leader
import com.kapp.tenon.generated.resources.agent_team_preview_members
import com.kapp.tenon.generated.resources.agent_team_prompt_hint
import com.kapp.tenon.generated.resources.agent_team_prompt_label
import com.kapp.tenon.generated.resources.agent_team_regenerate
import com.kapp.tenon.generated.resources.agent_team_title
import com.kapp.tenon.generated.resources.common_cancel
import com.kapp.tenon.generated.resources.team_profile_groups_none
import com.kapp.tenon.generated.resources.team_profile_no_leaders
import com.kapp.tenon.ui.components.RelayDialog
import com.kapp.tenon.ui.components.RelayDialogButton
import com.kapp.tenon.ui.theme.LocalAppSpacing
import com.kapp.tenon.ui.theme.LocalCardShape
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * 「AI 建队」助手对话框：一句需求 → 生成 → **预览确认** → 走与手动建队同一条保存路径。
 *
 * ## 三条纪律
 *
 * 1. **预览确认，不直建**：生成的方案只是预览（[onGenerate] 返回 [TeamScaffoldSpec]），
 *    用户看得见每一棒（名字 / 角色 / 驱动 / 干活还是审查）并挑好进哪些群，点确认才落盘。
 *    建队可回退但会吵（席位 + 广播），自动落盘等于替用户做决定。
 * 2. **落盘 100% 复用**：确认走 [onConfirm] —— 与 [TeamProfileDialog] 的 `onSave` 同一条
 *    `saveTeamProfile` 路径 + 执行档端口，助手不发明第二条写路径。
 * 3. **失败如实**：生成失败给一句重试提示（编排层内部已回落模板，走到这里还失败 =
 *    连模板都组不出合法方案）；确认后执行档有拒绝项就地列出原因，不静默。
 */
@Composable
internal fun TeamBuilderDialog(
    leaders: List<TeamLeaderOption>,
    groups: List<TeamGroupOption>,
    onGenerate: suspend (need: String) -> TeamScaffoldSpec?,
    onConfirm: (spec: TeamScaffoldSpec, groupIds: List<String>) -> List<String>,
    onDismiss: () -> Unit,
) {
    val sp = LocalAppSpacing.current
    val scope = rememberCoroutineScope()
    var need by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var spec by remember { mutableStateOf<TeamScaffoldSpec?>(null) }
    var failed by remember { mutableStateOf(false) }
    var pickedGroups by remember { mutableStateOf(emptySet<String>()) }
    var rejected by remember { mutableStateOf(emptyList<String>()) }

    RelayDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(Res.string.agent_team_title)) },
        content = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(sp.sm),
            ) {
                val current = spec
                if (current == null) {
                    OutlinedTextField(
                        value = need,
                        onValueChange = { need = it },
                        label = { Text(stringResource(Res.string.agent_team_need_label)) },
                        placeholder = { Text(stringResource(Res.string.agent_team_need_hint)) },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (failed) {
                        Text(
                            text = stringResource(Res.string.agent_team_generate_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (leaders.isEmpty()) {
                        Text(
                            text = stringResource(Res.string.team_profile_no_leaders),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (busy) {
                        Text(
                            text = stringResource(Res.string.agent_team_generating),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    BuilderPreview(
                        spec = current,
                        leaders = leaders,
                        onPromptChange = { agentId, text ->
                            // prompt 编辑直接改 spec（确认走 onConfirm(current, …)）：
                            // 预览即事实，不存在「对话框里一份、保存时又一份」。
                            val base = spec
                            spec = base?.copy(
                                members = base.members.map {
                                    if (it.agentId == agentId) {
                                        it.copy(prompt = text.trim().take(2_000).ifBlank { null })
                                    } else {
                                        it
                                    }
                                },
                            )
                        },
                    )
                    BuilderGroupPicker(
                        groups = groups,
                        picked = pickedGroups,
                        onToggle = { id ->
                            pickedGroups = if (id in pickedGroups) pickedGroups - id else pickedGroups + id
                        },
                    )
                    if (rejected.isNotEmpty()) {
                        Text(
                            text = stringResource(Res.string.agent_team_exec_rejected) +
                                "\n" + rejected.joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            val current = spec
            if (current == null) {
                RelayDialogButton(text = stringResource(Res.string.common_cancel), onClick = onDismiss)
                RelayDialogButton(
                    text = stringResource(Res.string.agent_team_generate),
                    primary = true,
                    enabled = !busy && need.isNotBlank() && leaders.isNotEmpty(),
                    onClick = {
                        scope.launch {
                            busy = true
                            failed = false
                            spec = onGenerate(need)
                            busy = false
                            if (spec == null) failed = true
                        }
                    },
                )
            } else {
                RelayDialogButton(
                    text = stringResource(Res.string.agent_team_regenerate),
                    enabled = !busy,
                    onClick = {
                        spec = null
                        rejected = emptyList()
                        failed = false
                    },
                )
                RelayDialogButton(
                    text = stringResource(Res.string.agent_team_confirm),
                    primary = true,
                    enabled = pickedGroups.isNotEmpty() && !busy,
                    onClick = {
                        val reasons = onConfirm(current, pickedGroups.toList())
                        if (reasons.isEmpty()) {
                            onDismiss()
                        } else {
                            rejected = reasons
                        }
                    },
                )
            }
        },
    )
}

/** 预览段：队名 + 领队 + 每一棒的「名字 · 角色 · 驱动 · 干活/审查」+ 可编辑的人设提示词。 */
@Composable
private fun BuilderPreview(
    spec: TeamScaffoldSpec,
    leaders: List<TeamLeaderOption>,
    onPromptChange: (agentId: String, text: String) -> Unit,
) {
    val sp = LocalAppSpacing.current
    SectionLabelOfBuilder(stringResource(Res.string.agent_team_preview_leader))
    Text(
        text = spec.teamTitle + " · " +
            (leaders.firstOrNull { it.agentKey == spec.leaderAgentId }?.label ?: spec.leaderAgentId),
        style = MaterialTheme.typography.bodyMedium,
    )
    SectionLabelOfBuilder(stringResource(Res.string.agent_team_preview_members))
    Surface(
        shape = LocalCardShape.current,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(sp.sm), verticalArrangement = Arrangement.spacedBy(sp.xs)) {
            spec.members.forEach { member ->
                Text(
                    text = member.name + " · " + member.role + " · " + member.driverKey + " · " +
                        stringResource(
                            if (member.kind == com.kapp.tenon.domain.TeamExecutionKindsUi.WORKER) {
                                Res.string.agent_team_kind_worker
                            } else {
                                Res.string.agent_team_kind_reviewer
                            },
                        ),
                    style = MaterialTheme.typography.bodySmall,
                )
                // 人设提示词可编辑：LLM 生成 / 模板内置的都是**起点**——
                // 用户确认前能逐个改，确认时原样落执行档 customPrompt。
                var prompt by remember(member.agentId, member.prompt) {
                    mutableStateOf(member.prompt.orEmpty())
                }
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { text ->
                        prompt = text
                        onPromptChange(member.agentId, text)
                    },
                    label = { Text(stringResource(Res.string.agent_team_prompt_label)) },
                    placeholder = { Text(stringResource(Res.string.agent_team_prompt_hint)) },
                    minLines = 2,
                    maxLines = 6,
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** 群多选：与 [TeamProfileDialog] 同一形状（新建必须至少进一个群，否则没有席位）。 */
@Composable
private fun BuilderGroupPicker(
    groups: List<TeamGroupOption>,
    picked: Set<String>,
    onToggle: (String) -> Unit,
) {
    val sp = LocalAppSpacing.current
    SectionLabelOfBuilder(stringResource(Res.string.agent_team_groups_label))
    val addable = groups.filterNot { it.joined }
    if (addable.isEmpty()) {
        Text(
            text = stringResource(Res.string.team_profile_groups_none),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(sp.xs),
        verticalArrangement = Arrangement.spacedBy(sp.xs),
    ) {
        addable.forEach { option ->
            SelectChipOfBuilder(label = option.name, selected = option.groupId in picked) {
                onToggle(option.groupId)
            }
        }
    }
}

/** 段标题：与 `TeamProfileDialog.SectionLabel` 同形（那份是 private，这里按同款重写一份）。 */
@Composable
private fun SectionLabelOfBuilder(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = LocalAppSpacing.current.sm6),
    )
}

/** 单选 / 多选 chip：与 `TeamProfileDialog.SelectChip` 同形（CircleShape = 既有 token 档）。 */
@Composable
private fun SelectChipOfBuilder(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = CircleShape,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = LocalAppSpacing.current.sm, vertical = LocalAppSpacing.current.xs),
        )
    }
}
