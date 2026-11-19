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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.kapp.tenon.core.protocol.AVATAR_LEGACY_INITIAL_VALUE
import com.kapp.tenon.domain.TeamExecutionKindsUi
import com.kapp.tenon.ui.components.PeerAvatar
import com.kapp.tenon.generated.resources.agent_team_inspirations_title
import com.kapp.tenon.generated.resources.agent_team_template_fullstack
import com.kapp.tenon.generated.resources.agent_team_template_fullstack_prompt
import com.kapp.tenon.generated.resources.agent_team_template_quality
import com.kapp.tenon.generated.resources.agent_team_template_quality_prompt
import com.kapp.tenon.generated.resources.agent_team_template_test
import com.kapp.tenon.generated.resources.agent_team_template_test_prompt
import com.kapp.tenon.generated.resources.hub_team_size_format
import com.kapp.tenon.domain.TeamScaffoldSpec
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.agent_empty_exit_title
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
import com.kapp.tenon.generated.resources.team_builder_reuse_summary
import com.kapp.tenon.generated.resources.team_builder_reuse_badge
import com.kapp.tenon.generated.resources.team_builder_new_badge
import com.kapp.tenon.generated.resources.agent_team_prompt_label
import com.kapp.tenon.generated.resources.agent_team_regenerate
import com.kapp.tenon.generated.resources.agent_team_title
import com.kapp.tenon.generated.resources.common_cancel
import com.kapp.tenon.generated.resources.team_profile_groups_none
import com.kapp.tenon.generated.resources.team_profile_no_leaders
import com.kapp.tenon.generated.resources.team_profile_prereq_body
import com.kapp.tenon.ui.components.RelayDialog
import com.kapp.tenon.ui.components.MutedTag
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
// 公开面：androidApp 的 screenshotTest 跨模块组装预览；生产入口仍在本模块。
@Composable
public fun TeamBuilderDialog(
    leaders: List<TeamLeaderOption>,
    groups: List<TeamGroupOption>,
    onGenerate: suspend (need: String) -> TeamScaffoldSpec?,
    onConfirm: (spec: TeamScaffoldSpec, groupIds: List<String>) -> List<String>,
    onDismiss: () -> Unit,
    /** 没有领队候选时的出路：去节点页确认宿主在线。null = 预览壳没有节点页。 */
    onFindNodes: (() -> Unit)? = null,
    initialSpec: TeamScaffoldSpec? = null,
) {
    val sp = LocalAppSpacing.current
    // 与手动建队同一条前置条件：没有本机领队就不展开需求输入框。
    // 生成按钮此前只是置灰，用户仍可先写一大段需求再发现按不动 —— 那是假操作。
    if (leaders.isEmpty()) {
        RelayDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(Res.string.agent_team_title)) },
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(sp.sm6)) {
                    Text(
                        text = stringResource(Res.string.team_profile_prereq_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(Res.string.team_profile_no_leaders),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                if (onFindNodes != null) {
                    RelayDialogButton(
                        text = stringResource(Res.string.agent_empty_exit_title),
                        primary = true,
                        onClick = {
                            onDismiss()
                            onFindNodes()
                        },
                    )
                }
            },
            dismissButton = {
                RelayDialogButton(text = stringResource(Res.string.common_cancel), onClick = onDismiss)
            },
        )
        return
    }
    val scope = rememberCoroutineScope()
    var need by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var spec by remember(initialSpec) { mutableStateOf(initialSpec) }
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
                    // —— 建队灵感示例 ——
                    Column(verticalArrangement = Arrangement.spacedBy(sp.xxs)) {
                        Text(
                            text = stringResource(Res.string.agent_team_inspirations_title),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(sp.xs),
                            verticalArrangement = Arrangement.spacedBy(sp.xs),
                        ) {
                            listOf(
                                Pair(Res.string.agent_team_template_fullstack, Res.string.agent_team_template_fullstack_prompt),
                                Pair(Res.string.agent_team_template_quality, Res.string.agent_team_template_quality_prompt),
                                Pair(Res.string.agent_team_template_test, Res.string.agent_team_template_test_prompt),
                            ).forEach { (labelRes, promptRes) ->
                                val label = stringResource(labelRes)
                                val promptText = stringResource(promptRes)
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.clickable { need = promptText },
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = sp.sm, vertical = sp.xxs),
                                    )
                                }
                            }
                        }
                    }
                    if (failed) {
                        Surface(
                            shape = LocalCardShape.current,
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = stringResource(Res.string.agent_team_generate_failed),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(sp.sm),
                            )
                        }
                    }
                    if (busy) {
                        Surface(
                            shape = LocalCardShape.current,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(sp.sm),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(sp.md10),
                                    strokeWidth = sp.xxs,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = stringResource(Res.string.agent_team_generating),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = sp.sm),
                                )
                            }
                        }
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
                        Surface(
                            shape = LocalCardShape.current,
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = stringResource(Res.string.agent_team_exec_rejected) +
                                    "\n" + rejected.joinToString("\n"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(sp.sm),
                            )
                        }
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
    // —— 团队名片卡 ——
    Surface(
        shape = LocalCardShape.current,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(sp.md),
            modifier = Modifier.padding(sp.sm),
        ) {
            PeerAvatar(
                name = spec.teamTitle,
                avatar = AVATAR_LEGACY_INITIAL_VALUE,
                size = sp.xxl,
                shape = MaterialTheme.shapes.medium,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(sp.xxs),
            ) {
                Text(
                    text = spec.teamTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(sp.xs),
                ) {
                    val leaderLabel = leaders.firstOrNull { it.agentKey == spec.leaderAgentId }?.label ?: spec.leaderAgentId
                    MutedTag(text = stringResource(Res.string.agent_team_preview_leader) + ": " + leaderLabel)
                    MutedTag(text = stringResource(Res.string.hub_team_size_format, spec.members.size + 1))
                }
            }
        }
    }

    SectionLabelOfBuilder(stringResource(Res.string.agent_team_preview_members))
    Column(verticalArrangement = Arrangement.spacedBy(sp.xs)) {
        // 汇总行（§3.4.1）：复用/新建数字摆出来
        val reusedCount = spec.members.count { it.reused }
        Text(
            text = stringResource(
                Res.string.team_builder_reuse_summary,
                reusedCount,
                spec.members.size - reusedCount,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        spec.members.forEach { member ->
            Surface(
                shape = LocalCardShape.current,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(sp.sm),
                    verticalArrangement = Arrangement.spacedBy(sp.xs),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(sp.sm),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        PeerAvatar(
                            name = member.name,
                            avatar = AVATAR_LEGACY_INITIAL_VALUE,
                            size = sp.xl,
                            shape = MaterialTheme.shapes.medium,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(sp.xs),
                            ) {
                                Text(
                                    text = member.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                MutedTag(
                                    text = stringResource(
                                        if (member.reused) Res.string.team_builder_reuse_badge
                                        else Res.string.team_builder_new_badge,
                                    ),
                                )
                            }
                            Text(
                                text = member.role + " · " + member.driverKey,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Surface(
                            shape = CircleShape,
                            color = if (member.kind == TeamExecutionKindsUi.WORKER) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer
                            },
                        ) {
                            Text(
                                text = stringResource(
                                    if (member.kind == TeamExecutionKindsUi.WORKER) {
                                        Res.string.agent_team_kind_worker
                                    } else {
                                        Res.string.agent_team_kind_reviewer
                                    },
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (member.kind == TeamExecutionKindsUi.WORKER) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                },
                                modifier = Modifier.padding(horizontal = sp.sm6, vertical = sp.xxs),
                            )
                        }
                    }

                    // 工作指导指令与人设规范可编辑
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
                        maxLines = 5,
                        textStyle = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
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
