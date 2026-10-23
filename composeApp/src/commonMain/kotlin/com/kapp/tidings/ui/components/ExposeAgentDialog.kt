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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.kapp.tidings.domain.model.WorkspaceUi
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.common_cancel
import com.kapp.tidings.generated.resources.group_expose_agent_confirm
import com.kapp.tidings.generated.resources.group_expose_agent_empty
import com.kapp.tidings.generated.resources.group_expose_agent_note
import com.kapp.tidings.generated.resources.group_expose_agent_title
import com.kapp.tidings.generated.resources.workspace_unassigned
import com.kapp.tidings.ui.theme.LocalAppSpacing
import org.jetbrains.compose.resources.stringResource

/**
 * 「本机可入驻的 Agent」：勾选本机工作区，把它们暴露进这个群。
 *
 * ## 为什么这是群内加减 agent 的入口
 *
 * 群元数据里的 `virtualAgents` 每一条 = 一个可被 @ 的 agent（`agentId` + 宿主节点 + token 名）。
 * 加一条 = 群里能 @ 到它；减一条 = 不再能 @。而**谁能加**在这里是有限制的：
 * 只能暴露**本机**的工作区 —— 那是机主主权，与「执行名单」同一方向。
 * 群主不能替别人的机器登记 agent（那等于让全群向那台机器发指令）。
 *
 * ## 为什么按工作区而不是按 agent 名字
 *
 * 协议里的暴露单位是 `(agentId, wsId)`：同一台机器可能装多个 agent，
 * 同一个 agent 也可能是多个工作区。按工作区勾选，语义与 `handleExposeWorkspace`
 * 的实现一致，不会出现「勾了 dsh 却不知道绑到哪个目录」。
 *
 * ## 取消勾选
 *
 * 已入驻的项默认勾上；取消 = 调 `onExposeWorkspaces` 时不带它（实现会移除该条）。
 */
@Composable
internal fun ExposeAgentDialog(
    /** 本机工作区（只含自己这台机器的）。 */
    localWorkspaces: List<WorkspaceUi>,
    /** 已经入驻这个群的 `wsId` 集合。 */
    exposedWsIds: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    val sp = LocalAppSpacing.current
    // 初值 = 已入驻的那些：用户看到的是「现在谁在群里」，勾/去勾就是加减。
    val checked = remember(localWorkspaces, exposedWsIds) {
        mutableStateMapOf<String, Boolean>().apply {
            localWorkspaces.forEach { set(it.wsId, it.wsId in exposedWsIds) }
        }
    }

    RelayDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.group_expose_agent_title)) },
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(sp.xs),
            ) {
                Text(
                    text = stringResource(Res.string.group_expose_agent_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = sp.sm),
                )
                if (localWorkspaces.isEmpty()) {
                    // 空态要给出路：不是「没东西可勾」就完了，而是告诉用户去哪儿建。
                    Text(
                        text = stringResource(Res.string.group_expose_agent_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = sp.md),
                    )
                }
                localWorkspaces.forEach { ws ->
                    val isChecked = checked[ws.wsId] == true
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { checked[ws.wsId] = !isChecked }
                            .padding(vertical = sp.xxs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(sp.xs),
                    ) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = { checked[ws.wsId] = it },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                // 工作区名优先；没有就用路径末段？**不** —— 路径不进卡片主位
                                // （父稿 §0 的隐私纪律），这里退到「未命名工作区」。
                                text = ws.wsTitle?.takeIf { it.isNotBlank() }
                                    ?: stringResource(Res.string.workspace_unassigned),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            val kind = ws.agentKind?.takeIf { it.isNotBlank() }
                            if (kind != null) {
                                Text(
                                    text = kind,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            RelayDialogButton(
                text = stringResource(Res.string.group_expose_agent_confirm),
                onClick = {
                    onConfirm(checked.filterValues { it }.keys.toList())
                    onDismiss()
                },
                primary = true,
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
