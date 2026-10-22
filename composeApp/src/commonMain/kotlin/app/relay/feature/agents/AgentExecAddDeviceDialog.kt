// SPDX-License-Identifier: GPL-3.0-only

package app.relay.feature.agents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.relay.generated.resources.Res
import app.relay.generated.resources.agent_exec_add_title
import app.relay.generated.resources.agent_exec_add_empty
import app.relay.ui.components.RelayDialog
import app.relay.ui.theme.LocalAppSpacing
import org.jetbrains.compose.resources.stringResource

/**
 * 「添加设备」：从候选人里挑一个，把它加进这个 agent 的执行名单。
 *
 * ## 为什么需要这一步，而不是把所有人都列出来
 *
 * 主列表只显示**已经在名单里**和**可信设备**。群成员可能有十几个，全列出来会把
 * 「谁能用这个 agent」这个核心信息淹掉 —— 那一屏要回答的是「现在谁能」，
 * 而不是「谁可以能」。
 *
 * 所以新增是一个**显式动作**：点「添加设备」→ 看到还没列入的人 → 选一个。
 * 这个摩擦是有意的：给一台设备执行权是不可撤销的（它能在此之后跑任何命令），
 * 不该发生在一次「顺手点开」里。
 *
 * ## 空候选
 *
 * 所有人都已在名单里时，这一屏说「都已经在名单里了」，而不是画一个空列表 ——
 * 空列表会让用户以为加载失败。
 */
@Composable
public fun AgentExecAddDeviceDialog(
    candidates: List<ExecDeviceRow>,
    onPick: (nodeId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sp = LocalAppSpacing.current
    RelayDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.agent_exec_add_title)) },
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(sp.xs),
            ) {
                if (candidates.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.agent_exec_add_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = sp.md),
                    )
                }
                candidates.forEach { device ->
                    Text(
                        text = device.label,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            // 点一行 = 选中并关闭。不做多选：一次给一台设备授权，
                            // 批量勾多台会让「我刚给了谁」变得难以确认。
                            .clickable { onPick(device.nodeId) }
                            .padding(vertical = sp.sm),
                    )
                }
            }
        },
    )
}
