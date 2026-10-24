// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.chat_delete_confirm_body
import com.kapp.tidings.generated.resources.chat_delete_confirm_title
import com.kapp.tidings.generated.resources.chat_delete_local
import com.kapp.tidings.generated.resources.common_cancel
import com.kapp.tidings.ui.components.RelayDialog
import com.kapp.tidings.ui.components.RelayDialogButton
import org.jetbrains.compose.resources.stringResource

/**
 * 「删除这一条（仅本机）」的前置确认。
 *
 * ## 为什么必须有
 *
 * 长按菜单里「删除本机」此前是**一次点击就直接删**：没有回收站、不能撤销，
 * 而长按菜单恰恰是最容易点错相邻项的地方（上/下两项分别是「复制」和「转发」）。
 * 会话列表那边的删除早有确认（`DeleteConversationConfirmDialog`），消息这一侧漏了 ——
 * 同一类破坏性动作在两个层级上给了两种待遇。
 *
 * 形状照 `DeleteConversationConfirmDialog` 的先例：正文说清「只在这台设备上删」
 * 「不可恢复」，确认键用 danger 描边红字，与「取消」在形状上就分开。
 *
 * ## 为什么住在独立文件里
 *
 * 与 `DeleteConversationConfirmDialog` 同一条理由：气泡文件是设计量具的共享组件文件，
 * 在它里面直接展开对话框骨架会把 `RelayDialog` 的圆角/尺寸取值带进气泡那几屏的切片，
 * 而原型的气泡帧里没有弹层 ⇒ 量具会报「只代码」。独立成文件后气泡只**调用**它。
 *
 * public 的理由也同一条：截图门禁在 `:androidApp` 的 `screenshotTest` 源集（另一个模块），
 * 看不到 internal；而 Dialog 族在 Layoutlib 下能完整组合。
 */
@Composable
public fun DeleteChatMessageConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    RelayDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.chat_delete_confirm_title)) },
        content = {
            Text(
                text = stringResource(Res.string.chat_delete_confirm_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            RelayDialogButton(
                text = stringResource(Res.string.chat_delete_local),
                danger = true,
                onClick = onConfirm,
            )
        },
        dismissButton = {
            RelayDialogButton(
                text = stringResource(Res.string.common_cancel),
                onClick = onDismiss,
            )
        },
    )
}
