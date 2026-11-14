// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.subagent_empty_output
import com.kapp.tenon.generated.resources.subagent_intervene_action
import com.kapp.tenon.generated.resources.subagent_intervene_synced
import com.kapp.tenon.generated.resources.subagent_output_title
import com.kapp.tenon.generated.resources.subagent_thought_title
import com.kapp.tenon.ui.SectionCard
import com.kapp.tenon.ui.components.AppButtonSize
import com.kapp.tenon.ui.components.AppTextButton
import com.kapp.tenon.ui.theme.LocalAppSpacing
import org.jetbrains.compose.resources.stringResource

/**
 * 第 2 层 Subagent 独立会话白盒审计流视图 (对齐 Spec §4.3 / LLD §1.1 & §6.2)。
 *
 * 展示内容：
 * 1. 原始 Prompt
 * 2. 逐轮思考链 (Thought Chain)
 * 3. 终端与工具执行输出日志
 * 4. 【⏸️ 暂停并介入】打字微调，并向 Leader 同步
 */
@Composable
internal fun SubagentWhiteboxView(
    questId: String,
    subagentId: String,
    prompt: String = "",
    thoughtChain: String = "",
    executionLog: String = "",
    onIntervene: (questId: String, subagentId: String, text: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sp = LocalAppSpacing.current
    var showInterveneDialog by remember { mutableStateOf(false) }
    var showSuccessBanner by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(sp.md),
            verticalArrangement = Arrangement.spacedBy(sp.md),
        ) {
            if (showSuccessBanner) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(sp.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(Res.string.subagent_intervene_synced),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        AppTextButton(
                            text = "OK",
                            onClick = { showSuccessBanner = false },
                            size = AppButtonSize.Small,
                        )
                    }
                }
            }

            // 头部与介入按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Subagent: $subagentId",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                AppTextButton(
                    text = stringResource(Res.string.subagent_intervene_action),
                    onClick = { showInterveneDialog = true },
                    size = AppButtonSize.Small,
                )
            }

            // 原始 Prompt 块
            if (prompt.isNotBlank()) {
                SectionCard(title = "Instruction Prompt") {
                    Text(
                        text = prompt,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(sp.md),
                    )
                }
            }

            // 思考推演链 (Thought Chain)
            SectionCard(title = stringResource(Res.string.subagent_thought_title)) {
                Text(
                    text = thoughtChain.ifBlank { stringResource(Res.string.subagent_empty_output) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(sp.md),
                )
            }

            // 终端与执行输出日志 (Terminal Output)
            SectionCard(title = stringResource(Res.string.subagent_output_title)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(sp.md)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                        )
                        .padding(sp.sm),
                ) {
                    Text(
                        text = executionLog.ifBlank { stringResource(Res.string.subagent_empty_output) },
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        if (showInterveneDialog) {
            QuestInterventionDialog(
                questTitle = "Subagent: $subagentId",
                prompt = null,
                onDismiss = { showInterveneDialog = false },
                onSubmit = { text ->
                    onIntervene(questId, subagentId, text)
                    showInterveneDialog = false
                    showSuccessBanner = true
                },
            )
        }
    }
}
