// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain.screenshot

import com.kapp.tidings.domain.HistorySearchFilter
import com.kapp.tidings.domain.MessageSearchResult
import com.kapp.tidings.domain.model.MessageUi
import com.kapp.tidings.feature.chat.ChatScreenActions

/**
 * 聊天预览共用的那一组空回调。
 *
 * 这些预览钉的是**入口画不画**（头部那个戳一碰图标、撤回出现在长按菜单里、
 * 附件图标在不在），不是回调的行为 —— 生产代码在 `App.kt` 与
 * `ui/desktop/DesktopChatPane.kt` 里传的是真实现，预览传空 lambda 才有意义。
 * 34 个预览原本各写一份 `ChatScreenActions(...)`，其中三个必写项
 * （onSend / onOpenAgentConsole / onDismissBanner）**34 处逐字相同**。
 *
 * **除 onSend 外的十项默认值一律是 `null`，不是 `{}`** —— `null` 与 `{}` 在这里
 * 是两种不同的画面：`onAttachFile = null` 意味着「这个壳没有选文件的能力」，
 * 输入栏就不画那个附件图标（见 `ChatScreen.kt` 里 `onAttachFile?.let { … }`）。
 * 第一版把这几项默认成 `{}`，截图门禁立刻红了 9 帧（chat-send-folder /
 * chat-folder-in / chat-group / chat-transfer-resume / chat-long-text /
 * chat-transfer-failed / chat-empty / chat-folder-phone / chat-transfer-active）
 * —— 全是那些**刻意不写**回调、因此不画图标的帧。
 */
internal fun chatPreviewActions(
    onAttachFile: ((String) -> Unit)? = null,
    onRecallMessage: ((MessageUi) -> Unit)? = null,
    onForwardMessage: ((message: MessageUi, targetConvId: String) -> Unit)? = null,
    onNudge: ((String) -> Unit)? = null,
    onSendPk: ((targetId: String, game: String, value: String) -> Unit)? = null,
    onSendVoice: ((peerId: String, filePath: String, durationMs: Long) -> Unit)? = null,
    onSearchInConversation: ((query: String, filter: HistorySearchFilter) -> List<MessageSearchResult>)? = null,
    onCancelTransfer: ((String) -> Unit)? = null,
    onRequestScreenView: ((String) -> Unit)? = null,
    onResumeTransfer: ((String) -> Unit)? = null,
): ChatScreenActions = ChatScreenActions(
    onSend = { _, _, _ -> },
    onOpenAgentConsole = {},
    onDismissBanner = {},
    onAttachFile = onAttachFile,
    onRecallMessage = onRecallMessage,
    onForwardMessage = onForwardMessage,
    onNudge = onNudge,
    onSendPk = onSendPk,
    onSendVoice = onSendVoice,
    onSearchInConversation = onSearchInConversation,
    onCancelTransfer = onCancelTransfer,
    onRequestScreenView = onRequestScreenView,
    onResumeTransfer = onResumeTransfer,
)
