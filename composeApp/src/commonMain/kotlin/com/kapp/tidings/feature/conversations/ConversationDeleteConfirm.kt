// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.conversations

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.common_cancel
import com.kapp.tidings.generated.resources.common_remove
import com.kapp.tidings.generated.resources.conv_delete_confirm_body
import com.kapp.tidings.generated.resources.conv_delete_confirm_title
import com.kapp.tidings.ui.components.RelayDialog
import com.kapp.tidings.ui.components.RelayDialogButton
import org.jetbrains.compose.resources.stringResource

/**
 * 「删除会话（仅本机）」的**前置确认**（挂在 `ConversationRow` 的删除菜单项后面）。
 *
 * 为什么必须有这一步：删除是**一次点击就直接发生**的破坏性动作 —— 菜单项上写
 * 「仅本机」挡不住误触，删掉的本地历史没有回收站。形状照设置页「清空全部聊天记录」
 * 那条先例（`SettingsDataSubpage` 的 `ClearAllHistoryConfirmDialog`）：正文逐条说清
 * 「只删本机（对方不受影响）」「不可恢复」，确认按钮用 `danger` 描边红字，
 * 让破坏性动作与「取消」在形状上就分开。
 *
 * ## 为什么住在独立文件里（而不在 `ConversationRow.kt`）
 *
 * `ConversationRow.kt` 是 `design-screen-diff` 的**共享组件文件**（`SHARED_COMPONENT_FILES`）：
 * 它的取值会并进每个调用它的屏的切片。文件里一旦出现 `RelayDialog(` 调用，对话框骨架的
 * `RoundedCornerShape(20.dp)` 就会跟着调用链被卷进 `m-home-*` 那几屏的代码取值集 ——
 * 原型的**列表帧**里没有弹层，于是量具报「radius 只代码 20」（第三十六个洞的同族形状：
 * 闭包里的东西不该算进这一帧）。独立成文件后，行的切片不再直接含对话框调用。
 *
 * public 的理由与 `ConversationActionsMenuContent` 同一条：截图门禁在 `:androidApp` 的
 * `screenshotTest` 源集（另一个模块），看不到 `internal`；而 `Dialog` 族在 Layoutlib 下
 * **能完整组合**（见 `ScreenshotPreviewsDialogs.kt` 文件头），所以给它一帧的代价只有可见性。
 * 这一帧正是"删除前必须先确认"这条纪律唯一的视觉证据。
 */
@Composable
public fun DeleteConversationConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    RelayDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.conv_delete_confirm_title)) },
        content = {
            Text(
                text = stringResource(Res.string.conv_delete_confirm_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            RelayDialogButton(
                text = stringResource(Res.string.common_remove),
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
