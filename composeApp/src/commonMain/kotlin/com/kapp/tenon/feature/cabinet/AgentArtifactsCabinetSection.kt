// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.cabinet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.kapp.tenon.domain.AgentSlice
import com.kapp.tenon.domain.CabinetArtifactGroupUi
import com.kapp.tenon.domain.CabinetArtifactRowUi
import com.kapp.tenon.domain.SettingsSlice
import com.kapp.tenon.domain.cabinetArtifactGroups
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.agent_artifact_no_file
import com.kapp.tenon.generated.resources.agent_count_items
import com.kapp.tenon.generated.resources.cabinet_agent_artifacts_hint
import com.kapp.tenon.generated.resources.cabinet_agent_artifacts_no_ws
import com.kapp.tenon.generated.resources.cabinet_agent_artifacts_title
import com.kapp.tenon.generated.resources.conv_inbox_ws_row_unnamed
import com.kapp.tenon.generated.resources.ic_file
import com.kapp.tenon.generated.resources.ic_folder
import com.kapp.tenon.ui.components.SectionHeader
import com.kapp.tenon.ui.components.formatBytes
import com.kapp.tenon.ui.components.formatCount
import com.kapp.tenon.ui.components.relativeTime
import com.kapp.tenon.ui.theme.IconSizes
import com.kapp.tenon.ui.theme.LocalAppSpacing
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 文件柜 Tab 2 的「智能体产物」段（UX 规范 §2.5③，**只做视图、不搬文件**那一档）。
 *
 * ## 它解决的是哪一句话
 *
 * 规范原文是「所有生成的非代码成果物，底层自动归档到底栏 Tab 2【文件柜】」。
 * 真正的「归档」（把文件搬进共享目录）要一次网络写 + 一条防重记账，而且是
 * **未经询问就把用户的文件放进共享目录** —— 那是产品决策，不是 UI 优化。
 * 这一档只做前半句：把散在几十条消息里的产物**在一个地方列出来**，按项目目录分组，
 * 点一行回到产出它的那条会话。零迁移、零协议键、**不动任何人的磁盘**。
 *
 * ## 为什么「点开」不是打开文件
 *
 * `AgentArtifactEntity` 只有 `file_id`，**没有本地路径**（产物帧按协议不带二进制）。
 * 所以这一行点开的是**产出它的那条会话** —— 文件气泡在那儿，由既有文件动作去开。
 * 把一个 `fileId` 当可打开路径是编造；给这一行挂个"打开"按钮更糟（按下去没反应）。
 *
 * ## 壳没有会话入口时整段不画
 *
 * [onOpenSession] 为 null ⇒ 这段**一行都不画**。与「打开共享目录」那颗按钮同一条规矩：
 * 能力缺失 = 不画，不是"点了没反应"。一列点不动的文件名在文件柜里只是噪音。
 */
@Composable
internal fun AgentArtifactsCabinetSection(
    agentSlice: AgentSlice,
    settingsSlice: SettingsSlice,
    now: Long,
    /** 点一行 → 打开产出它的会话。null = 这个壳没有会话入口，整段不画。 */
    onOpenSession: ((peerId: String, wsId: String?) -> Unit)?,
) {
    if (onOpenSession == null) return
    val groups = remember(agentSlice, settingsSlice) { cabinetArtifactGroups(agentSlice, settingsSlice) }
    if (groups.isEmpty()) return
    val sp = LocalAppSpacing.current
    val total = groups.sumOf { it.rows.size }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(sp.sm6),
    ) {
        SectionHeader(
            text = stringResource(Res.string.cabinet_agent_artifacts_title),
            action = {
                Text(
                    text = stringResource(Res.string.agent_count_items, formatCount(total.toLong())),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        Text(
            text = stringResource(Res.string.cabinet_agent_artifacts_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        groups.forEach { group ->
            CabinetArtifactGroup(group = group, now = now, onOpenSession = onOpenSession)
        }
    }
}

/** 一组 = 一个项目目录。组头写目录名，副位写它绑定的群（归因不了就不写）。 */
@Composable
private fun CabinetArtifactGroup(
    group: CabinetArtifactGroupUi,
    now: Long,
    onOpenSession: (peerId: String, wsId: String?) -> Unit,
) {
    val sp = LocalAppSpacing.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(sp.xxs),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(sp.sm6),
            modifier = Modifier.padding(top = sp.sm6),
        ) {
            Icon(
                painter = painterResource(Res.drawable.ic_folder),
                contentDescription = null,
                modifier = Modifier.size(IconSizes.Label),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                // 目录名缺失时如实写「未命名项目目录」，不拿 wsId 顶替（身份键不是名字）。
                text = group.wsTitle
                    ?: if (group.wsId == null) {
                        stringResource(Res.string.cabinet_agent_artifacts_no_ws)
                    } else {
                        // 与消息页同一个目录行同一句（`conv_inbox_ws_row_unnamed` 就是
                        // 「未命名项目目录」）：同一个事实只有一份文案。
                        stringResource(Res.string.conv_inbox_ws_row_unnamed)
                    },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            group.groupName?.let { name ->
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        group.rows.forEach { row ->
            CabinetArtifactRow(row = row, now = now, onOpenSession = onOpenSession)
        }
    }
}

/**
 * 一件产物一行：文件名 + 「mime · 大小」（没有关联文件时如实说只有元数据）+ 相对时刻。
 *
 * 整行可点 → 回到产出它的会话。这里**不画**打开文件的入口：本地没有路径可给。
 */
@Composable
private fun CabinetArtifactRow(
    row: CabinetArtifactRowUi,
    now: Long,
    onOpenSession: (peerId: String, wsId: String?) -> Unit,
) {
    val sp = LocalAppSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenSession(row.peerId, row.wsId) }
            .padding(horizontal = sp.sm6, vertical = sp.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(sp.sm6),
    ) {
        Icon(
            painter = painterResource(Res.drawable.ic_file),
            contentDescription = null,
            modifier = Modifier.size(IconSizes.Label),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = artifactRowSubtitle(row),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = relativeTime(row.at, now),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** 副行：没有关联文件时明说「只有元数据」，不把 `mime · 大小` 印成半截空话。 */
@Composable
private fun artifactRowSubtitle(row: CabinetArtifactRowUi): String {
    if (!row.hasFile) return stringResource(Res.string.agent_artifact_no_file)
    val parts = listOfNotNull(row.mime, row.bytes?.let { formatBytes(it) })
    // 有文件但两端都没报 mime/大小：整句不画（返回空串由 maxLines=1 的 Text 撑住），
    // 而不是留一行只剩文件名的空副行。
    return parts.joinToString(" · ")
}
