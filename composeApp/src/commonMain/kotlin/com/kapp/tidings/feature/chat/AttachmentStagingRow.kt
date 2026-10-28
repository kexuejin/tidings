// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.feature.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kapp.tidings.core.transport.LocalFile
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.common_remove
import com.kapp.tidings.generated.resources.ic_attachment
import com.kapp.tidings.ui.components.AppIconButton
import com.kapp.tidings.ui.components.formatBytes
import com.kapp.tidings.ui.theme.IconSizes
import com.kapp.tidings.ui.theme.LocalAppSpacing
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

public const val MAX_STAGED_ATTACHMENTS: Int = 10

/**
 * 累积并截断暂存文件：按路径去重，最多保留 [max] 个。
 */
public fun mergeStagedFiles(
    current: List<LocalFile>,
    incoming: List<LocalFile>,
    max: Int = MAX_STAGED_ATTACHMENTS,
): List<LocalFile> {
    return (current + incoming).distinctBy { it.path }.take(max)
}

/**
 * 输入框上方的附件暂存 chip 行（移动端与桌面端共用）。
 *
 * 展示已选待发的文件列表：
 * - 支持累积暂存（最多 [MAX_STAGED_ATTACHMENTS] 个）；
 * - 单个文件通过删除按钮单独移除；
 * - 文件名过长时省略号截断，并展示格式化大小。
 */
@Composable
internal fun AttachmentStagingRow(
    files: List<LocalFile>,
    onRemove: (LocalFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (files.isEmpty()) return
    val scrollState = rememberScrollState()
    val sp = LocalAppSpacing.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(vertical = sp.xs),
        horizontalArrangement = Arrangement.spacedBy(sp.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        files.forEach { file ->
            AttachmentChip(
                file = file,
                onRemove = { onRemove(file) },
            )
        }
    }
}

@Composable
private fun AttachmentChip(
    file: LocalFile,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sp = LocalAppSpacing.current
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
    ) {
        Row(
            modifier = Modifier.padding(start = sp.sm, end = sp.xxs, top = sp.xxs, bottom = sp.xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(sp.xs),
        ) {
            Icon(
                painter = painterResource(Res.drawable.ic_attachment),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(IconSizes.Inline),
            )
            Text(
                text = file.name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 140.dp),
            )
            Text(
                text = formatBytes(file.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
            )
            AppIconButton(
                iconVector = Icons.Default.Close,
                contentDescription = stringResource(Res.string.common_remove),
                onClick = onRemove,
                modifier = Modifier.size(IconSizes.Title),
            )
        }
    }
}
