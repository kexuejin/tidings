// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.jetbrains.compose.resources.stringResource
import com.kapp.tenon.domain.QuestDiffParser
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.quest_diff_collapsed
import com.kapp.tenon.generated.resources.quest_diff_review_title
import com.kapp.tenon.generated.resources.quest_diff_too_large
import com.kapp.tenon.ui.theme.LocalAppSpacing
import com.kapp.tenon.ui.SectionCard

/**
 * 移动端 Diff 审查面板（Phase 4）：quest 产物补丁（patch.diff）的文件树概览 +
 * 分文件懒渲染。
 *
 * - **文件树概览优先**（方案 v3 §Phase 4.1）：每文件一行（路径 + ±行数），点开
 *   才渲染该文件的 diff 行 —— 手机上先给全貌，不给一屏天书。
 * - **上下文折叠**：连续 ≥ [QuestDiffParser.CONTEXT_COLLAPSE_MIN] 行未改动上下文
 *   折叠成一条占位；未折叠的上下文弱化着色。
 * - **行帽如实**：单文件超 [QuestDiffParser.MAX_LINES_PER_FILE] 行截尾并提示
 *   去桌面看完整补丁 —— 不假装审完了。
 *
 * 数据源是壳注入的整包补丁文本（`RelayController.questPatchText`，平台文件 IO
 * 在壳层）；null = 壳未接或补丁还没落到本机 —— 调用方不挂面板（文件卡的
 * 「接收」入口在会话流里，那里的空态才诚实）。
 */
@Composable
fun QuestDiffReviewPanel(
    patchText: String,
    modifier: Modifier = Modifier,
) {
    val sp = LocalAppSpacing.current
    SectionCard(
        title = stringResource(Res.string.quest_diff_review_title),
        modifier = modifier,
    ) {
        val diff = remember(patchText) { QuestDiffParser.parse(patchText) }
        var selected by remember(diff) { mutableStateOf(diff.files.firstOrNull()?.path) }
        val selectedFile = diff.files.firstOrNull { it.path == selected } ?: diff.files.firstOrNull()
        if (selectedFile == null) return@SectionCard

        // 文件树概览：每文件 ±行数；点击切换选中。
        Column(Modifier.padding(bottom = sp.md10)) {
            diff.files.forEach { file ->
                val isSelected = file.path == selectedFile?.path
                Text(
                    text = "+" + file.additions + " −" + file.deletions + "  " + file.path,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selected = file.path }
                        .padding(vertical = sp.xs),
                )
            }
        }

        val trimmed = remember(selectedFile) { QuestDiffParser.trimForRender(selectedFile.lines) }
        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
            contentPadding = PaddingValues(sp.md10),
        ) {
            items(count = trimmed.lines.size, key = { it }) { index ->
                val line = trimmed.lines[index]
                val isCollapsed = line.type == QuestDiffParser.LineType.COLLAPSED
                val (color, weight) = when (line.type) {
                    QuestDiffParser.LineType.ADD -> MaterialTheme.colorScheme.primary to FontWeight.Medium
                    QuestDiffParser.LineType.DEL -> MaterialTheme.colorScheme.error to FontWeight.Medium
                    QuestDiffParser.LineType.HUNK -> MaterialTheme.colorScheme.tertiary to FontWeight.SemiBold
                    QuestDiffParser.LineType.COLLAPSED -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) to FontWeight.Normal
                    QuestDiffParser.LineType.CONTEXT -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) to FontWeight.Normal
                }
                Text(
                    text = if (isCollapsed) {
                        stringResource(Res.string.quest_diff_collapsed, line.text.toIntOrNull() ?: 0)
                    } else {
                        line.text
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = weight,
                    color = color,
                )
            }
        }
        if (trimmed.truncated) {
            Text(
                text = stringResource(Res.string.quest_diff_too_large),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = sp.md10),
            )
        }
    }
}
