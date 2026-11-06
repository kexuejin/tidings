// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.agent_thought_process
import com.kapp.tenon.generated.resources.agent_thought_process_elapsed
import com.kapp.tenon.generated.resources.agent_thought_thinking
import com.kapp.tenon.generated.resources.ic_chevd
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

import androidx.compose.foundation.shape.CircleShape
import com.kapp.tenon.ui.theme.IconSizes
import com.kapp.tenon.ui.theme.LocalAppSpacing
import com.kapp.tenon.ui.theme.RelayShapes

/**
 * 思考流与推理交互折叠组件（Goal 2）。
 *
 * 针对 DeepSeek-R1、Claude 等思考模型的 <think> 推理内容：
 * - 思考中：微型转圈指示 + 动态「思考中…」；
 * - 生成完毕：收起为极简折叠条（显示耗时，如「思索过程 (4.2s)」）；
 * - 用户点击：平滑展开完整逻辑链，左侧带垂直指示线，支持文字选择与复制。
 */
@Composable
fun ThinkingBlock(
    reasoning: String,
    elapsedMs: Long? = null,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier,
    initialExpanded: Boolean = false,
) {
    if (reasoning.isBlank() && !isStreaming) return

    val sp = LocalAppSpacing.current
    var expanded by remember { mutableStateOf(initialExpanded) }
    val arrowRotation by animateFloatAsState(targetValue = if (expanded) 180f else 0f)

    val infiniteTransition = rememberInfiniteTransition()
    val streamingAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
    )

    val elapsedText = if (elapsedMs != null && elapsedMs > 0L) {
        formatElapsedSeconds(elapsedMs)
    } else {
        null
    }

    val label = when {
        isStreaming -> stringResource(Res.string.agent_thought_thinking)
        elapsedText != null -> stringResource(Res.string.agent_thought_process_elapsed, elapsedText)
        else -> stringResource(Res.string.agent_thought_process)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
            shape = RelayShapes.small,
            border = null,
            modifier = Modifier
                .clip(RelayShapes.small)
                .clickable { expanded = !expanded },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(sp.xs),
                modifier = Modifier.padding(horizontal = sp.sm, vertical = sp.xs),
            ) {
                if (isStreaming) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(ThinkingIndicatorSize),
                        strokeWidth = ThinkingIndicatorStroke,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Icon(
                        painter = painterResource(Res.drawable.ic_chevd),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(IconSizes.Inline)
                            .rotate(arrowRotation),
                    )
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = if (isStreaming) Modifier.alpha(streamingAlpha) else Modifier,
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.25f),
                shape = RelayShapes.small,
                border = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = sp.xs),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .padding(horizontal = sp.sm, vertical = sp.sm6),
                ) {
                    // 左侧纵向引用指示线
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(ThinkingGuidelineWidth)
                            .background(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                shape = CircleShape,
                            ),
                    )
                    SelectionContainer {
                        Text(
                            text = reasoning,
                            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = sp.sm),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 格式化耗时毫秒数为友好秒数字符串（整数算术，跨平台绝对稳定）。
 */
internal fun formatElapsedSeconds(elapsedMs: Long): String {
    val sec10 = (elapsedMs + 50L) / 100L
    val whole = sec10 / 10L
    val frac = sec10 % 10L
    return if (frac == 0L) "${whole}s" else "$whole.${frac}s"
}

private val ThinkingIndicatorSize = 12.dp
private val ThinkingIndicatorStroke = 1.5.dp
private val ThinkingGuidelineWidth = 2.dp
