// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.chat_clear_local_body
import com.kapp.tidings.generated.resources.chat_clear_local_confirm
import com.kapp.tidings.generated.resources.chat_clear_local_title
import com.kapp.tidings.generated.resources.common_cancel
import com.kapp.tidings.ui.components.RelayDialog
import com.kapp.tidings.ui.components.RelayDialogButton
import com.kapp.tidings.ui.theme.LocalAppSpacing
import org.jetbrains.compose.resources.stringResource

/**
 * 「清空本会话聊天记录」的二次确认。
 *
 * ## 为什么这条入口必须二次确认，且文案要写全三件事
 *
 * 它删的是**不可恢复**的本地历史（[com.kapp.tidings.domain.RelayController.clearMessages] 直接把
 * 这条会话从内存快照里摘掉并落盘），而且它在头部动作区里跟「复制节点 ID」这种无副作用
 * 动作挨着 —— 一次误触的代价和一次误点「复制」完全不是一个量级。
 * 所以正文逐条说清：① 只删**本机**；② 对方那里不受影响（否则用户会以为在替对方删，
 * 不敢用；或者以为删了就没人能看见，误解了隐私边界）；③ 不可恢复。
 *
 * 会话名（[conversationName]）写进正文，是为了让用户核对**清的是哪一个**：桌面上
 * 多窗口/多会话并排时，"当前选中的到底是哪个"很容易看错。
 *
 * ## 为什么放在 feature/chat 而不是 ui/components
 *
 * 它是聊天页的动作，两个壳（手机 [ChatScreen] 的头部 ⋮ / 桌面 DesktopChatHeader 的动作区）
 * 共用这一个确认框。放这里，删改文案与交互时两边一起动。
 */
@Composable
internal fun ClearConversationDialog(
    conversationName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val sp = LocalAppSpacing.current
    RelayDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.chat_clear_local_title)) },
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(sp.sm),
            ) {
                Text(
                    text = stringResource(Res.string.chat_clear_local_body, conversationName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            // danger = 描边红字（原型 .btn.danger-o）：破坏性动作与「取消」在形状上就分开，
            // 不靠位置区分，免得宽窄两档下顺手点错。
            RelayDialogButton(
                text = stringResource(Res.string.chat_clear_local_confirm),
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
