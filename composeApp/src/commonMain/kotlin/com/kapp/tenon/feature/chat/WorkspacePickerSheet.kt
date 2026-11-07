// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.kapp.tenon.domain.model.WorkspaceUi
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.ic_folder
import com.kapp.tenon.generated.resources.workspace_picker_sheet_title
import com.kapp.tenon.ui.theme.IconSizes
import com.kapp.tenon.ui.theme.LocalAppSpacing
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 1:1 私聊顶栏「项目药丸」(Project Pill) 点击弹出的工作区单选抽屉。
 *
 * 极简单选设计：点击即选中并生效，秒级收起抽屉，不增加多级确认负担。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkspacePickerSheet(
    workspaces: List<WorkspaceUi>,
    activeWsId: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sp = LocalAppSpacing.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = sp.lg, vertical = sp.sm6),
            verticalArrangement = Arrangement.spacedBy(sp.sm6),
        ) {
            Text(
                text = stringResource(Res.string.workspace_picker_sheet_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            workspaces.forEach { ws ->
                val isSelected = ws.wsId == activeWsId
                Surface(
                    onClick = {
                        onSelect(ws.wsId)
                        onDismiss()
                    },
                    shape = MaterialTheme.shapes.small,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(sp.md),
                        modifier = Modifier.padding(horizontal = sp.md, vertical = sp.sm),
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_folder),
                            contentDescription = null,
                            tint = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(IconSizes.Title),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = ws.wsTitle ?: ws.wsId,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                            val path = ws.wsPath
                            if (!path.isNullOrBlank()) {
                                Text(
                                    text = path,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(IconSizes.Body),
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(sp.xxl))
        }
    }
}
