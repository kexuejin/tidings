// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.action_back
import com.kapp.tenon.generated.resources.ic_arrow_back
import com.kapp.tenon.generated.resources.ic_chev
import com.kapp.tenon.generated.resources.nav_breadcrumb_main
import com.kapp.tenon.generated.resources.nav_breadcrumb_quest
import com.kapp.tenon.generated.resources.nav_breadcrumb_subagent
import com.kapp.tenon.ui.components.AppIconButton
import com.kapp.tenon.ui.theme.IconSizes
import com.kapp.tenon.ui.theme.LocalAppSpacing
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 分形层级面包屑顶栏导航 (对齐 Spec §4.3 / LLD §1.1 & §6.1)。
 *
 * 展示结构：
 * 第 1 层：[主线] > [支线: questId]
 * 第 2 层：[主线] > [支线: questId] > [Subagent: subagentId]
 * 支持点击各层级面包屑一键回跳。
 */
@Composable
internal fun FractalBreadcrumbBar(
    questId: String?,
    subagentId: String? = null,
    onNavigateBackToMain: () -> Unit,
    onNavigateBackToQuest: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (questId == null) return

    val sp = LocalAppSpacing.current

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = sp.sm, vertical = sp.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(sp.xs),
        ) {
            AppIconButton(
                icon = Res.drawable.ic_arrow_back,
                contentDescription = stringResource(Res.string.action_back),
                onClick = {
                    if (subagentId != null && onNavigateBackToQuest != null) {
                        onNavigateBackToQuest()
                    } else {
                        onNavigateBackToMain()
                    }
                },
            )

            Text(
                text = stringResource(Res.string.nav_breadcrumb_main),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onNavigateBackToMain() },
            )

            Icon(
                painter = painterResource(Res.drawable.ic_chev),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(IconSizes.Inline),
            )

            Text(
                text = stringResource(Res.string.nav_breadcrumb_quest, questId),
                style = MaterialTheme.typography.labelMedium,
                color = if (subagentId != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.then(
                    if (subagentId != null && onNavigateBackToQuest != null) {
                        Modifier.clickable { onNavigateBackToQuest() }
                    } else {
                        Modifier
                    },
                ),
            )

            if (subagentId != null) {
                Icon(
                    painter = painterResource(Res.drawable.ic_chev),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(IconSizes.Inline),
                )

                Text(
                    text = stringResource(Res.string.nav_breadcrumb_subagent, subagentId),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
