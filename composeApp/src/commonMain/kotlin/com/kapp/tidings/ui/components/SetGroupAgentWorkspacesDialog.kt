// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.kapp.tidings.core.protocol.VirtualAgentMember
import com.kapp.tidings.domain.model.WorkspaceUi
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.capsule_entity_team
import com.kapp.tidings.generated.resources.common_cancel
import com.kapp.tidings.generated.resources.group_auth_agents_section
import com.kapp.tidings.generated.resources.group_auth_badge_default
import com.kapp.tidings.generated.resources.group_auth_badge_self
import com.kapp.tidings.generated.resources.group_auth_empty
import com.kapp.tidings.generated.resources.group_auth_note
import com.kapp.tidings.generated.resources.group_auth_path_host_only
import com.kapp.tidings.generated.resources.group_auth_readonly_note
import com.kapp.tidings.generated.resources.group_auth_save
import com.kapp.tidings.generated.resources.group_auth_team_members
import com.kapp.tidings.generated.resources.group_auth_title
import com.kapp.tidings.generated.resources.group_auth_workspaces_section
import com.kapp.tidings.ui.theme.LocalAppSpacing
import com.kapp.tidings.ui.theme.ShapeRoles
import org.jetbrains.compose.resources.stringResource

/**
 * 群设置 · **智能体与工作区授权面板**（方案 §5.1，M3 交付 3，替换 `ExposeAgentDialog`）。
 *
 * ## 授权决定权在机主（§5.1）
 *
 * 面板只在**宿主本机**可编辑 —— 宿主 = 本机工作区表有行、且壳接了保存通路
 * （[onSave] 非 null）。其它成员打开同一面板**只读**：他们看的是声明投影
 * （`virtualAgents` 随 group.info 广播的那份），勾选与保存按钮都不画。
 *
 * ## 路径不进协议（M1-T3 收口），也不进这个面板的非宿主视图
 *
 * 宿主自己的行带路径（机主看自己的机器，天经地义）；非宿主的行来自声明投影，
 * **path 恒 null** —— 渲染层据此显示「仅宿主可见」，隐私收口在 UI 上闭环。
 *
 * ## 保存一次成像（M1-T4 契约）
 *
 * [onSave] 收到的是**最终集合**：域层一次 rev+1、一次广播，没有逐条 toggle 的
 * 中间态。空集合合法 = 撤掉本机全部声明（撤回是机主主权，面板不禁用保存）。
 */
/** 面板里的一个工作区行。 [path] 为 null = 这台设备看不见路径（非宿主）。 */
data class GroupAuthWorkspaceRow(
    val wsId: String,
    val title: String,
    val path: String?,
    val authorized: Boolean,
    val isDefault: Boolean,
    /**
     * 这一行是不是**本机**工作区（宿主的授权对象）。
     *
     * 勾选与保存只认本机行：外来声明进保存集合会被域层的工作区表解析丢弃
     * （`handleSetGroupAgentWorkspaces` 只解析 `peerId == 本机` 的行），但那等于
     * 让面板产出一批「看起来存了、其实不存在」的勾选 —— 不如从源头就只记本机。
     */
    val fromLocalMachine: Boolean,
)

/** 面板里的一个 agent 行（声明投影；对外单一窗口 —— 组员不出现在群里，只报数目）。 */
data class GroupAuthAgentRow(
    val name: String,
    val isTeam: Boolean,
    val teamMemberCount: Int,
    val hostIsSelf: Boolean,
    val defaultWsTitle: String?,
)

/**
 * 面板的工作区行。
 *
 * 宿主视角从**本机工作区表**出发（有路径、可勾选）；本机表为空（非宿主）时从
 * **声明投影**出发（`allowedWorkspaces` 并上各成员的 `defaultWsId` —— M1 模型里
 * 白名单常为空而只有默认绑定，读侧把它也算授权事实，否则非宿主看到的面板是空的，
 * 胶囊却明明能选工作区）。宿主的行集 = 本机表 ∪ 外来声明（别人的声明看得见、动不了）。
 */
fun groupAuthWorkspaceRows(
    localWorkspaces: List<WorkspaceUi>,
    declaredAgents: List<VirtualAgentMember>,
    exposedWsIds: Set<String>,
    defaultWsId: String?,
    wsTitleFallback: (wsId: String) -> String? = { null },
): List<GroupAuthWorkspaceRow> {
    val byWsId = LinkedHashMap<String, GroupAuthWorkspaceRow>()
    // 1) 本机工作区：机主自己的授权对象（带路径）。
    localWorkspaces.forEach { ws ->
        val wsId = ws.wsId.trim()
        if (wsId.isEmpty() || wsId in byWsId) return@forEach
        byWsId[wsId] = GroupAuthWorkspaceRow(
            wsId = wsId,
            title = ws.wsTitle?.takeIf { it.isNotBlank() } ?: wsId,
            path = ws.wsPath?.takeIf { it.isNotBlank() },
            authorized = wsId in exposedWsIds,
            isDefault = wsId == defaultWsId,
            fromLocalMachine = true,
        )
    }
    // 2) 声明投影：本机表没有的（非宿主的全部、宿主的外来声明）补进来，无路径。
    declaredAgents.forEach { member ->
        val entries = member.allowedWorkspaces.map { it.wsId to it.title } +
            (member.defaultWsId?.let { it to null }?.let { listOf(it) } ?: emptyList())
        entries.forEach { (wsId, declaredTitle) ->
            val key = wsId.trim()
            if (key.isEmpty() || key in byWsId) return@forEach
            byWsId[key] = GroupAuthWorkspaceRow(
                wsId = key,
                title = declaredTitle?.takeIf { it.isNotBlank() }
                    ?: wsTitleFallback(key)?.takeIf { it.isNotBlank() }
                    ?: key,
                path = null,
                authorized = true, // 声明里的白名单条目 / 默认绑定本身就是授权事实
                isDefault = key == defaultWsId,
                fromLocalMachine = false,
            )
        }
    }
    return byWsId.values.toList()
}

/** 面板的 agent 行：名字来自声明（@ token 与气泡署名同一份事实），组员只报数目。 */
fun groupAuthAgentRows(
    declaredAgents: List<VirtualAgentMember>,
    selfNodeId: String,
    wsTitleOf: (wsId: String) -> String?,
): List<GroupAuthAgentRow> = declaredAgents.map { member ->
    GroupAuthAgentRow(
        name = member.name,
        isTeam = member.teamMembers.isNotEmpty() || member.entityType == "team",
        teamMemberCount = member.teamMembers.size,
        hostIsSelf = member.hostNodeId == selfNodeId,
        // 默认工作区的标题：先查声明白名单，再问本机工作区表；都查不到就不显示
        // —— wsId 是内部 id，不冒充名字（WorkspaceUi.wsTitle 的同一条纪律）。
        defaultWsTitle = member.defaultWsId?.let { wsId ->
            member.allowedWorkspaces.firstOrNull { it.wsId == wsId }?.title?.takeIf { it.isNotBlank() }
                ?: wsTitleOf(wsId)?.takeIf { it.isNotBlank() }
        },
    )
}

/** 群默认工作区：声明表里第一条带 `defaultWsId` 的声明（与 workspaceOfGroup 同一解析方向）。 */
fun groupDefaultWsIdOf(declaredAgents: List<VirtualAgentMember>): String? =
    declaredAgents.firstOrNull { it.defaultWsId != null }?.defaultWsId

/** 面板打开时的勾选集合 = **本机**行的当前授权（外来声明不可勾，进不了保存集合）。 */
fun initialCheckedWsIds(rows: List<GroupAuthWorkspaceRow>): Set<String> =
    rows.filter { it.fromLocalMachine && it.authorized }.map { it.wsId }.toSet()

@Composable
internal fun SetGroupAgentWorkspacesDialog(
    /** 群里已声明的 agent（读侧唯一来源：`GroupMeta.virtualAgents`）。 */
    declaredAgents: List<VirtualAgentMember>,
    /** 本机工作区表（宿主的编辑对象）；空 = 非宿主（或宿主还没登记工作区），面板只读。 */
    localWorkspaces: List<WorkspaceUi>,
    /** 本机当前的授权集合（= 本机声明里 defaultWsId 的集合）。 */
    exposedWsIds: Set<String>,
    /** 本机自己是谁（判 agent 行的「本机声明」徽标）。 */
    selfNodeId: String,
    /** 本机工作区 id → 标题（声明没带标题时的兜底；查不到就不显示，不编）。 */
    wsTitleOf: (wsId: String) -> String?,
    onDismiss: () -> Unit,
    /**
     * 保存 = 把**最终集合**整体交给 `handleSetGroupAgentWorkspaces`（一次成像、
     * 一次 rev+1 广播）。null = 这个壳没有保存通路 ⇒ 面板只读（勾选与保存按钮不画）。
     */
    onSave: ((wsIds: List<String>) -> Unit)? = null,
) {
    val sp = LocalAppSpacing.current
    val editable = onSave != null && localWorkspaces.isNotEmpty()
    val defaultWsId = groupDefaultWsIdOf(declaredAgents)
    val rows = groupAuthWorkspaceRows(localWorkspaces, declaredAgents, exposedWsIds, defaultWsId, wsTitleOf)
    val agentRows = groupAuthAgentRows(declaredAgents, selfNodeId, wsTitleOf)
    // 初值 = 当前授权；勾/去勾就是加减，保存交最终集合。
    val checked = remember(rows) {
        mutableStateMapOf<String, Boolean>().apply {
            rows.filter { it.fromLocalMachine }.forEach { put(it.wsId, it.authorized) }
        }
    }

    RelayDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.group_auth_title)) },
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(sp.xs),
            ) {
                Text(
                    text = stringResource(Res.string.group_auth_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = sp.sm),
                )
                if (!editable) {
                    Surface(
                        shape = ShapeRoles.DayCell,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(Res.string.group_auth_readonly_note),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = sp.sm, vertical = sp.sm6),
                        )
                    }
                }

                // ── 群内 agent 与团队 ───────────────────────────────────
                if (agentRows.isNotEmpty()) {
                    Text(
                        text = stringResource(Res.string.group_auth_agents_section),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = sp.sm6),
                    )
                    agentRows.forEach { agent ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(sp.sm6),
                            modifier = Modifier.fillMaxWidth().padding(vertical = sp.xxs),
                        ) {
                            Text(
                                text = "@" + agent.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (agent.isTeam) {
                                RelayTagBadge(stringResource(Res.string.capsule_entity_team))
                            }
                            if (agent.isTeam && agent.teamMemberCount > 0) {
                                Text(
                                    text = stringResource(Res.string.group_auth_team_members, agent.teamMemberCount),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (agent.hostIsSelf) {
                                RelayTagBadge(stringResource(Res.string.group_auth_badge_self))
                            }
                            agent.defaultWsTitle?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                }

                // ── 工作区白名单 ────────────────────────────────────────
                Text(
                    text = stringResource(Res.string.group_auth_workspaces_section),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = sp.sm6),
                )
                if (rows.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.group_auth_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = sp.md),
                    )
                }
                rows.forEach { row ->
                    val isChecked = checked[row.wsId] == true
                    val canToggle = editable && row.fromLocalMachine
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (canToggle) {
                                    Modifier.clickable { checked[row.wsId] = !isChecked }
                                } else {
                                    Modifier
                                },
                            )
                            .padding(vertical = sp.xxs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(sp.sm6),
                    ) {
                        if (canToggle) {
                            Checkbox(checked = isChecked, onCheckedChange = { checked[row.wsId] = it })
                        } else {
                            // 只读态用勾形符号表达授权事实：画 Checkbox 会暗示可点。
                            Text(
                                text = if (row.authorized) "✓" else "—",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(sp.sm6),
                            ) {
                                Text(
                                    text = row.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (row.isDefault) {
                                    RelayTagBadge(stringResource(Res.string.group_auth_badge_default))
                                }
                            }
                            Text(
                                // 路径只在看得见时出现；看不见就是一句如实的话，不是空着。
                                text = row.path ?: stringResource(Res.string.group_auth_path_host_only),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (onSave != null) {
                RelayDialogButton(
                    text = stringResource(Res.string.group_auth_save),
                    onClick = {
                        // 一次成像：勾选集合就是最终集合（空集合 = 撤掉本机全部声明）。
                        onSave(checked.filterValues { it }.keys.toList())
                        onDismiss()
                    },
                    primary = true,
                )
            } else {
                RelayDialogButton(
                    text = stringResource(Res.string.common_cancel),
                    onClick = onDismiss,
                )
            }
        },
        dismissButton = {
            if (onSave != null) {
                RelayDialogButton(text = stringResource(Res.string.common_cancel), onClick = onDismiss)
            }
        },
    )
}

/** 面板里的轻量徽标（团队 / 默认 / 本机声明）：同一形状一处定义。 */
@Composable
private fun RelayTagBadge(text: String) {
    Surface(
        shape = ShapeRoles.DayCell,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = LocalAppSpacing.current.sm6, vertical = LocalAppSpacing.current.xxs),
        )
    }
}
