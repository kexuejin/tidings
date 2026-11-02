// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kapp.tidings.ui.theme.ComponentSizes
import com.kapp.tidings.ui.theme.IconSizes
import com.kapp.tidings.ui.theme.LocalAppSpacing
import com.kapp.tidings.ui.components.AppOutlinedButton
import com.kapp.tidings.ui.components.AppButton

/**
 * 空态的图形头：64dp 软色圆底 + 28dp 主色图标。
 *
 * 为什么是「圆底图标」而不是插画：插画要有插画师与多主题/多语言产出线，
 * 圆底图标是同一份 sprite 体系里的既有字形，不新增资产也能把
 * 「孤字悬在大片空白里」变成「有视觉锚点的空态」——这是成熟产品
 * （如系统设置、邮件客户端）空态的标准做法。
 * internal 而非 private：桌面壳的 `DesktopNoSelectionEmpty`（`.emptyd` 逐值对齐的
 * 居中空态）走的是自己的 Column 而不是这条 [EmptyState]，但图形头必须是
 * **同一颗**——两处各画一份迟早漂移。
 */
@Composable
internal fun EmptyGlyph(icon: Painter, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(ComponentSizes.EmptyGlyphCircle)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(IconSizes.Hero),
        )
    }
}

/**
 * 空态：**居中**的图形锚点 + 标题 + 一句副文 + 一个出路按钮。
 *
 * 与 [MessageState]（左对齐，服务错误类）分工：空态是「页面级结论」，
 * 居中排版把它从「一段没排版的文字」变成「有设计感的状态页」。
 * [icon] 传 null 时退化为无图形的居中版——小空态（筛选无结果）不必硬配图标。
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    icon: Painter? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = LocalAppSpacing.current.xl, vertical = LocalAppSpacing.current.xl),
        verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon?.let { EmptyGlyph(it) }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            AppOutlinedButton(text = actionLabel, onClick = onAction)
        }
    }
}

@Composable
fun ErrorState(
    error: AppError,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    MessageState(
        title = error.message,
        // 不是每次失败都有机器码：绑定失败这类，原因已经写在人话那句里了。
        // 空串不该在界面上画成一行空白。
        message = error.code.takeIf { it.isNotBlank() },
        actionLabel = actionLabel,
        onAction = onAction,
        modifier = modifier,
    )
}

@Composable
fun LoadingState(
    label: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(LocalAppSpacing.current.lg),
        horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.padding(LocalAppSpacing.current.xxs),
            strokeWidth = 2.dp,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MessageState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(LocalAppSpacing.current.xl),
        verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (actionLabel != null && onAction != null) {
            AppOutlinedButton(text = actionLabel, onClick = onAction)
        }
    }
}

@Composable
fun AsyncButton(
    label: String,
    loading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    loadingLabel: String = label,
    enabled: Boolean = true,
) {
    AppButton(
        text = if (loading) loadingLabel else label,
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier,
    )
}
