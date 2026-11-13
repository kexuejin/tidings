// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.kapp.tenon.domain.model.PeerUi
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.set_host_auth_desc
import com.kapp.tenon.generated.resources.set_host_auth_empty
import com.kapp.tenon.generated.resources.set_host_auth_offline
import com.kapp.tenon.generated.resources.set_host_auth_title
import com.kapp.tenon.ui.theme.LocalAppSpacing
import org.jetbrains.compose.resources.stringResource

/**
 * 「宿主授权」子页（手机设置）：**每台声明了 `atr1` 的宿主一块免批规则**。
 *
 * ## 为什么它存在
 *
 * 免批规则的读撤原先只有「智能体 → 工作区 → 详情」一条入口（两层深，藏在授权块
 * 中间）；桌面在「设置 → 可信设备」有一份。设置页这一行补的是**发现性**：用户想
 * 收回一个「始终允许」，不必记得它藏在哪个工作区里（真机走查的发现）。
 *
 * ## 为什么只放免批规则，不放执行设备 / 授权清单
 *
 * 后两者的键是 **agent × 宿主**（勾选的是"谁能用这个 agent 干活"、授权是"谁被
 * 授权看这个 agent"），放进设置页还得先挑一个 agent 才有的说 —— 它们的家在
 * 智能体页（agent 语境）。免批规则是**纯宿主事实**（这张表不按 agent 分页），
 * 是唯一不需要 agent 上下文的授权面。写编制的授权档位（request/auto）**有意
 * 不进这一页**：它是机主策略，机主席位在桌面（该裁决的理由见 RosterWriteSection
 * 的 KDoc）；且 caps 预算 16/16 满额，再开一族帧必须再让一项出去
 * （见 RelaySettings.caps 的 att1 注）。
 *
 * @param hosts 声明了 `atr1` 的已配对宿主（调用方过滤 —— 与免批规则 source 工厂
 *   同一个判据，两处各写一遍迟早漂，所以都从 App.kt 的同一个谓词出发）。
 */
@Composable
fun SettingsHostAuthSubpage(
    hosts: List<PeerUi>,
    toolApprovalsSourceOf: ((String) -> ToolApprovalsSource?)?,
    onRequestToolRules: ((String) -> Unit)?,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    SettingsPageScaffold(
        title = stringResource(Res.string.set_host_auth_title),
        subtitle = stringResource(Res.string.set_host_auth_desc),
        onBack = onBack,
        modifier = modifier,
    ) {
        if (hosts.isEmpty()) {
            Text(
                text = stringResource(Res.string.set_host_auth_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(LocalAppSpacing.current.lg),
            )
            return@SettingsPageScaffold
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md),
        ) {
            hosts.forEach { host ->
                // source 按宿主记住：区块的 addChangeListener 注册在 source 上，
                // 每次重组换一个实例 = 通知永远收不到。
                val source = remember(host.nodeId) { toolApprovalsSourceOf?.invoke(host.nodeId) }
                LaunchedEffect(host.nodeId) { onRequestToolRules?.invoke(host.nodeId) }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = LocalAppSpacing.current.xs),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = host.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (!host.online) {
                            Text(
                                text = stringResource(Res.string.set_host_auth_offline),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = LocalAppSpacing.current.sm),
                            )
                        }
                    }
                    ToolApprovalsSection(
                        source = source,
                        peerLabel = { nodeId ->
                            hosts.firstOrNull { it.nodeId == nodeId }?.displayName ?: nodeId.take(8)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = LocalAppSpacing.current.sm),
                    )
                }
            }
        }
    }
}
