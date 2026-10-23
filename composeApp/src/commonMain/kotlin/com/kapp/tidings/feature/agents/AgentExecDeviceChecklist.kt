// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.agents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.agent_exec_who_title
import com.kapp.tidings.generated.resources.agent_exec_who_note
import com.kapp.tidings.generated.resources.agent_exec_who_add
import com.kapp.tidings.generated.resources.agent_exec_who_empty
import com.kapp.tidings.generated.resources.agent_exec_who_trusted_badge
import com.kapp.tidings.ui.theme.LocalAppSpacing
import org.jetbrains.compose.resources.stringResource

/**
 * [ExecDeviceRow] 的声明已移到领域侧（`app/ExecDeviceRow.kt`）。
 *
 * 判据是**谁算出它**：`execDeviceRows()` 在 `app/` 里按授权台账与群成员算出来，
 * 这一屏只是把它画成勾选列表。所以「一行有几个字段、各是什么含义」属于领域事实；
 * 留在 `feature/agents` 会让控制器反向依赖 UI 层。
 *
 * 这里留一个 typealias 而不是让每个调用点改 import：**声明只有一份**。
 */
public typealias ExecDeviceRow = com.kapp.tidings.domain.ExecDeviceRow

/**
 * 「谁能让这个 agent 干活」——**逐设备勾选**。
 *
 * ## 为什么是勾选列表，不是四档选择器
 *
 * 四档（只有我 / 可信设备 / 加群主管理员 / 群内任何人）表达的是**整体宽严**，
 * 做不到「A 设备能用、B 设备不能用」。而后者才是真实需求：
 * 你批准了同事的设备做项目协作，但不等于让他用你所有的 agent。
 *
 * 勾选列表还能让用户**一眼看到谁能用**，档位选择器只能说「放宽到某一档了」。
 *
 * ## 两个维度的来源不同，但终点相同
 *
 * - **可信设备**：已知集合（`scope=ALL` 的授权记录），列出来直接勾；
 * - **其他设备**：从群成员里选，`＋ 添加设备` 之后才出现在列表里。
 *
 * 两者最终都是同一条 `grant(scope=AGENT, agentKey)` —— 界面的差别只是**发现方式**。
 *
 * ## 本机用户永远勾上
 *
 * 他本来就在自己机器上，这条不是「授权」而是「不必再问」。disable 掉勾选框是为了
 * 让「它为什么是勾的、为什么点不掉」一眼可见，而不是让他点了没反应。
 */
@Composable
public fun AgentExecDeviceChecklist(
    devices: List<ExecDeviceRow>,
    onToggle: (nodeId: String, checked: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onAddDevice: (() -> Unit)? = null,
) {
    val sp = LocalAppSpacing.current
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(Res.string.agent_exec_who_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = sp.xxs),
        )
        Text(
            text = stringResource(Res.string.agent_exec_who_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = sp.sm),
        )
        if (devices.isEmpty()) {
            Text(
                text = stringResource(Res.string.agent_exec_who_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        devices.forEach { device ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 本机用户没有可点的动作（永远勾上），所以不挂 clickable —— 
                    // 一个点了没反应的行与一个明确不可点的行，后者更诚实。
                    .then(
                        if (device.isSelf) {
                            Modifier
                        } else {
                            Modifier.clickable { onToggle(device.nodeId, !device.checked) }
                        }
                    )
                    .padding(vertical = sp.xxs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(sp.xs),
            ) {
                Checkbox(
                    checked = device.checked,
                    // 本机用户不可取消：他本来就在自己机器上，没有「取消自己」这回事。
                    onCheckedChange = if (device.isSelf) null else { next -> onToggle(device.nodeId, next) },
                    enabled = !device.isSelf,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(sp.xxs),
                    ) {
                        Text(
                            text = device.label,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (device.trusted) {
                            Text(
                                text = stringResource(Res.string.agent_exec_who_trusted_badge),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
        if (onAddDevice != null) {
            Text(
                // 加号放进资源里：门禁把全角「＋」判成硬编码中文，
                // 而它确实是**用户可见**的一部分（那是这颗入口的视觉标记）。
                text = stringResource(Res.string.agent_exec_who_add),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onAddDevice() }
                    .padding(vertical = sp.xs),
            )
        }
    }
}
