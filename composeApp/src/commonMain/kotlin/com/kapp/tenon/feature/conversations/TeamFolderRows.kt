// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.conversations

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.kapp.tenon.domain.model.TeamFolderUi
import com.kapp.tenon.domain.model.TeamMemberUi
import com.kapp.tenon.domain.model.teamGroupKey
import com.kapp.tenon.ui.theme.LocalAppSpacing
import com.kapp.tenon.ui.components.badgeCountText
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.team_folder_collapse
import com.kapp.tenon.generated.resources.team_folder_expand
import com.kapp.tenon.generated.resources.team_folder_idle
import com.kapp.tenon.generated.resources.team_folder_members
import com.kapp.tenon.generated.resources.team_folder_running
import org.jetbrains.compose.resources.stringResource

/**
 * Team Folder 的组头与成员行（阶段 1 / C4 渲染侧）。
 *
 * 这两个组件**只画**：分组结构由 [foldTeamRows] 决定，本文件不判断"哪些会话属于哪个团队"，
 * 也不自己算活跃态（[TeamFolderUi.isRunning] 由域层按 `agent-session.state` 聚合后传入）。
 *
 * 为什么成员行不是 `ConversationRow`：后者的首参是 `ConversationUi`（`convId` / `pendingCount`
 * / 草稿 / 发送者署名一套契约），而成员只有 `TeamMemberInfo(agentId, name, role)`。硬造一个
 * `ConversationUi` 会把假数据带进行里，还会让"会话行只有一份实现"这条纪律变糊。
 *
 * 状态只有两态（Running / Idle）：Pending Approval 由现有审批卡片表达，不进呼吸点。
 * 颜色一律走 MaterialTheme 语义色，不新增 token、不写字面色值。
 *
 * ## 交互语义（别想当然）
 *
 * - **点组头整行 = 进入该群会话**（与原型里"点文件夹行进文件夹屏"同一直觉）；
 *   **右侧 `+`/`−` 才折叠**，它的热区是独立的一小块，不冒泡到整行。
 * - 折叠态下组头**替代**会话行，所以这是进群的唯一入口；展开后会话行会补回来，
 *   再点它也进同一个会话。
 * - 成员行点击 = 打开所属群会话并预填 `@<leader>`（壳提供草稿写入口时才有此行为；
 *   预览或没有草稿能力的壳传 null，行就不可点）。
 */

@Composable
internal fun TeamFolderHeader(
    folder: TeamFolderUi,
    expanded: Boolean,
    /**
     * 被替代的会话行的未读数；>0 时画徽标。
     *
     * 折叠态下组头替代了会话行，不画就等于把这一格的未读吞了（见 `FeedRow.Folder.unread`）。
     */
    unread: Int,
    /** 整行点击：进入该群会话（折叠态下这是唯一入口）。null = 这个壳没有打开会话的能力。 */
    onOpenChat: ((String) -> Unit)?,
    /** 右侧符号点击：折叠/展开。null = 不可折叠（只展示）。 */
    onToggle: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val sp = LocalAppSpacing.current
    val toggleLabel = stringResource(
        if (expanded) Res.string.team_folder_collapse else Res.string.team_folder_expand,
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onOpenChat == null) {
                    Modifier
                } else {
                    Modifier.clickable { onOpenChat(folder.convId) }
                },
            )
            .padding(start = sp.lg14, end = sp.xxs, top = sp.sm6, bottom = sp.sm6),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TeamStateDot(running = folder.isRunning)
        Spacer(Modifier.width(sp.sm6))
        Text(
            text = folder.title,
            // 用 titleMedium(17sp) 而不是 titleSmall(15sp)：15 不在该屏原型声明过的字号里，
            // 会作为"代码侧新增取值"把 designScreenDiff 的差集从 13 组顶到 14 组。
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(Res.string.team_folder_members, folder.members.size),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (unread > 0) {
            Spacer(Modifier.width(sp.sm6))
            // 与 ConversationRow 的未读徽标同一套写法（primary 圆角胶囊 + badgeCountText）：
            // 同一个未读数在会话行与组头显示成两个样子，比不显示更糟。
            Surface(color = MaterialTheme.colorScheme.primary, shape = CircleShape) {
                Text(
                    text = badgeCountText(unread),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = sp.sm, vertical = sp.xxs),
                )
            }
        }
        Spacer(Modifier.width(sp.sm6))
        Box(
            modifier = Modifier
                .size(sp.xl)
                .clip(CircleShape)
                .then(
                    if (onToggle == null) {
                        Modifier
                    } else {
                        Modifier
                            // 内层 clickable 会消费事件，不会冒泡到整行（整行是进会话）。
                            .clickable { onToggle(teamGroupKey(folder.convId, folder.pluginId)) }
                            // 只说「展开团队」读屏不知道是哪一个 —— 团队名要拼进去。
                            .semantics { contentDescription = folder.title + " " + toggleLabel }
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                // 符号不是文案：折叠指示随状态变，读屏用 contentDescription。
                text = if (expanded) "−" else "+",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun TeamMemberRow(
    member: TeamMemberUi,
    /** 点击整行：打开所属群会话并预填 `@<leader>`。null = 不可点（预览 / 壳没有草稿能力）。 */
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val sp = LocalAppSpacing.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick == null) Modifier else Modifier.clickable { onClick() })
            .padding(start = sp.xl, end = sp.lg14, top = sp.xxs, bottom = sp.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(sp.xl)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = member.name.take(1),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Spacer(Modifier.width(sp.sm6))
        Text(
            text = member.name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(sp.sm6))
        Text(
            text = member.role,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 两态呼吸点：Running = 主色实心，Idle = 描边色。不做动画（首帧就要正确，不等呼吸）。 */
@Composable
private fun TeamStateDot(running: Boolean) {
    val sp = LocalAppSpacing.current
    val label = stringResource(
        if (running) Res.string.team_folder_running else Res.string.team_folder_idle,
    )
    Box(
        modifier = Modifier
            .size(sp.sm6)
            .clip(CircleShape)
            .background(
                if (running) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
            )
            .semantics { contentDescription = label },
    )
}
