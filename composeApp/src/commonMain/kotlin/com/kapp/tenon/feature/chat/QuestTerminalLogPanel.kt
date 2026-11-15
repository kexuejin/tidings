// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.jetbrains.compose.resources.stringResource
import com.kapp.tenon.domain.QuestStreamState
import com.kapp.tenon.domain.QuestStreamUnavailableReason
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.quest_terminal_log_empty
import com.kapp.tenon.generated.resources.quest_terminal_log_hint
import com.kapp.tenon.generated.resources.quest_stream_unavailable_channel
import com.kapp.tenon.generated.resources.quest_stream_unavailable_no_host
import com.kapp.tenon.generated.resources.quest_terminal_log_title
import com.kapp.tenon.generated.resources.quest_terminal_log_unavailable
import com.kapp.tenon.ui.theme.LocalAppSpacing
import com.kapp.tenon.ui.SectionCard

/**
 * 支线作战室的实时终端日志面板（Phase 1「轻量流式日志信道」）——**内嵌壳**。
 *
 * 数据来源是 [com.kapp.tenon.domain.QuestStreamHub] 的环形缓冲快照（本机任务读
 * 宿主缓冲、远端任务读订阅缓冲，壳在 [com.kapp.tenon.domain.RelayController.questStreamLines]
 * 里分派）；订阅/退订的生命周期由作战室组合侧管理（进入组合发 sub、离开/切后台
 * 发 cancel），本面板只负责展示。
 *
 * 外壳分两处共用同一份内容 [QuestTerminalContent]（组件复用能力 §内容还是外壳）：
 * 卡片壳（本面板，支线白盒分支内嵌）与整页壳（[WarRoomView]，quest 分支主体）。
 *
 * - [QuestStreamState.UNAVAILABLE]：如实说不可用（宿主不在线 / 旧版 / 链路不通），
 *   绝不画一个「看起来在流」的空壳。
 * - 空行态照实画「暂无输出」—— 任务没跑到、还没产出的情形真实存在。
 * - 自动滚底与聊天列表同一条贴底纪律：用户往上翻时不拽底（ChatScrollFollow 的
 *   derivedStateOf 判据形状，此处是面板内简化版）。
 */
@Composable
fun QuestTerminalLogPanel(
    @Suppress("UNUSED_PARAMETER") questId: String,
    state: QuestStreamState,
    unavailableReason: QuestStreamUnavailableReason?,
    lines: List<String>,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = stringResource(Res.string.quest_terminal_log_title),
        modifier = modifier,
    ) {
        QuestTerminalContent(
            state = state,
            unavailableReason = unavailableReason,
            lines = lines,
        )
    }
}

/**
 * 终端流的**内容**（状态判别 + 用途说明 + 日志行），不含任何外壳 chrome ——
 * 内嵌卡壳与整页壳共用这一份，两处看流的样子必须一样。
 */
@Composable
internal fun QuestTerminalContent(
    state: QuestStreamState,
    unavailableReason: QuestStreamUnavailableReason?,
    lines: List<String>,
    modifier: Modifier = Modifier,
) {
    val sp = LocalAppSpacing.current
    Column(modifier = modifier) {
        // 面板用途说明：这个流是什么（quest 的 CLI stdout/stderr 实时输出）——
        // 真机验收反馈“这个日志流干嘛的”没回答，一行小字补上。
        Text(
            text = stringResource(Res.string.quest_terminal_log_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = sp.md10),
        )
        when (state) {
            QuestStreamState.UNAVAILABLE -> {
                // 原因如实细分：找不到宿主 vs 通道不可达——笼统的“不在线”会让机主
                // 在宿主明明在线时误判（真机验收实测的反馈）。
                val reasonText = when (unavailableReason) {
                    QuestStreamUnavailableReason.NO_HOST ->
                        stringResource(Res.string.quest_stream_unavailable_no_host)
                    else ->
                        stringResource(Res.string.quest_stream_unavailable_channel)
                }
                Text(
                    text = stringResource(Res.string.quest_terminal_log_unavailable, reasonText),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                if (lines.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.quest_terminal_log_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val listState = rememberLazyListState()
                    val autoFollow by remember {
                        derivedStateOf {
                            val info = listState.layoutInfo
                            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                            lastVisible >= info.totalItemsCount - 2
                        }
                    }
                    LaunchedEffect(lines.size, autoFollow) {
                        if (autoFollow && lines.isNotEmpty()) {
                            listState.animateScrollToItem(lines.size - 1)
                        }
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                        contentPadding = PaddingValues(sp.md10),
                    ) {
                        items(count = lines.size, key = { it }) { index ->
                            Text(
                                text = lines[index],
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (index == lines.size - 1) FontWeight.Medium else FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
