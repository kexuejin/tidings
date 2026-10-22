// SPDX-License-Identifier: GPL-3.0-only

package app.relay.feature.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.relay.generated.resources.Res
import app.relay.generated.resources.action_back
import app.relay.generated.resources.capability_bash
import app.relay.generated.resources.capability_bash_desc
import app.relay.generated.resources.capability_category_mcp
import app.relay.generated.resources.capability_category_system
import app.relay.generated.resources.capability_codesearch
import app.relay.generated.resources.capability_codesearch_desc
import app.relay.generated.resources.capability_filesystem
import app.relay.generated.resources.capability_filesystem_desc
import app.relay.generated.resources.capability_settings_subtitle
import app.relay.generated.resources.capability_settings_title
import app.relay.generated.resources.capability_status_disabled
import app.relay.generated.resources.capability_status_enabled
import app.relay.generated.resources.capability_websearch
import app.relay.generated.resources.capability_websearch_desc
import app.relay.generated.resources.ic_arrow_back
import app.relay.ui.ContentWidths
import app.relay.ui.centerCapped
import app.relay.ui.theme.LocalAppSpacing
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import app.relay.ui.components.AppIconButton

/**
 * 手机端移动能力治理与 MCP 动态 ACL 开关面板。
 */
data class CapabilityItemState(
    val id: String,
    val titleRes: StringResource,
    val descRes: StringResource,
    val categoryRes: StringResource,
    val enabled: Boolean,
)

@Composable
fun CapabilitySettingsScreen(
    onBack: () -> Unit,
    onToggleCapability: (id: String, enabled: Boolean) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val initialItems = remember {
        listOf(
            CapabilityItemState(
                id = "bash",
                titleRes = Res.string.capability_bash,
                descRes = Res.string.capability_bash_desc,
                categoryRes = Res.string.capability_category_system,
                enabled = true,
            ),
            CapabilityItemState(
                id = "filesystem",
                titleRes = Res.string.capability_filesystem,
                descRes = Res.string.capability_filesystem_desc,
                categoryRes = Res.string.capability_category_system,
                enabled = true,
            ),
            CapabilityItemState(
                id = "codesearch",
                titleRes = Res.string.capability_codesearch,
                descRes = Res.string.capability_codesearch_desc,
                categoryRes = Res.string.capability_category_system,
                enabled = true,
            ),
            CapabilityItemState(
                id = "websearch",
                titleRes = Res.string.capability_websearch,
                descRes = Res.string.capability_websearch_desc,
                categoryRes = Res.string.capability_category_mcp,
                enabled = true,
            ),
        )
    }

    val stateMap = remember {
        mutableStateMapOf<String, Boolean>().apply {
            initialItems.forEach { put(it.id, it.enabled) }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .centerCapped(ContentWidths.Pane)
            .background(MaterialTheme.colorScheme.background),
    ) {
        // 顶部栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = LocalAppSpacing.current.md, vertical = LocalAppSpacing.current.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIconButton(
                icon = Res.drawable.ic_arrow_back,
                contentDescription = stringResource(Res.string.action_back),
                onClick = onBack,
            )
            Column(modifier = Modifier.padding(start = LocalAppSpacing.current.xs)) {
                Text(
                    text = stringResource(Res.string.capability_settings_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(Res.string.capability_settings_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(LocalAppSpacing.current.sm))

        // 能力清单
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = LocalAppSpacing.current.lg),
            verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md),
        ) {
            items(initialItems, key = { it.id }) { item ->
                val isChecked = stateMap[item.id] ?: item.enabled
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(LocalAppSpacing.current.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.xs),
                            ) {
                                Text(
                                    text = stringResource(item.titleRes),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "[${stringResource(item.categoryRes)}]",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(item.descRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = stringResource(
                                    if (isChecked) Res.string.capability_status_enabled
                                    else Res.string.capability_status_disabled
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isChecked) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline,
                            )
                        }

                        Switch(
                            checked = isChecked,
                            onCheckedChange = { checked ->
                                stateMap[item.id] = checked
                                onToggleCapability(item.id, checked)
                            },
                        )
                    }
                }
            }
        }
    }
}
