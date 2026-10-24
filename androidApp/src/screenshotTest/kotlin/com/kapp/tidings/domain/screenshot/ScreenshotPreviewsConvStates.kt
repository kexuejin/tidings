// 会话列表的**行级状态**与两个弹层：此前全部零帧。
//
// 为什么这一批必须补帧：审计确认「置顶标识 / 静音态 / 99+ / 群预览署名 / 失败空态 /
// 删除确认框 / 长按菜单体」的代码都到位，但它们在 androidApp 里**一张参考图都没有** ——
// 于是「到位」只由读代码得出，没有任何可判红的证据。按 AGENTS §2.6 第五步，一个组件
// 要有两端各一帧、并且改一处两端都变，才算真共享。
//
// 两端如何证明共享：手机帧渲染 `ConversationsScreen`，桌面帧渲染 `ConversationSidebar`
// —— 两者都经由共享的 `ConversationListPanel` → `ConversationRow`。所以同一份夹具下，
// 两帧里那四行（置顶 tag / 静音弱化 / 99+ / 群署名）必须长得一样；谁把行复制回壳里，
// 对应那一帧就会与另一帧分叉。
//
// 桌面帧刻意**不**套 `DesktopShell` 整壳（一壳多视图、渲染目标不是屏的信号，见
// `stateSlots.renderTargets` 的 note）：这里要钉的是"桌面这一列会话"，`ConversationSidebar`
// 正是 DesktopShell 嵌进去的那一块。
package com.kapp.tidings.domain.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.kapp.tidings.core.protocol.ConvId
import com.kapp.tidings.domain.BannerMessage
import com.kapp.tidings.domain.model.NodePhase
import com.kapp.tidings.domain.RelayUiState
import com.kapp.tidings.feature.conversations.ConversationActionsMenuContent
import com.kapp.tidings.feature.conversations.ConversationsScreen
import com.kapp.tidings.feature.conversations.DeleteConversationConfirmDialog
import com.kapp.tidings.ui.desktop.ConversationSidebar
import com.kapp.tidings.ui.desktop.PREVIEW_NOW
import com.kapp.tidings.ui.desktop.desktopPreviewEmptyState
import com.kapp.tidings.ui.desktop.desktopPreviewGroupState
import com.kapp.tidings.ui.desktop.linPeer
import com.kapp.tidings.ui.desktop.zhouPeer
import com.kapp.tidings.ui.theme.AppTheme

/**
 * 四行状态合成一份夹具：置顶（林岚）、静音（周远）、未读 150（林岚 → 99+）、
 * 群会话（最后一条由周远发 → 预览署"周远"）。
 *
 * 为什么合成一份而不是四份：这四个状态都在**同一行组件**里，分开拍会得到四张几乎一样的
 * 列表 —— 合成一张才能在 394dp 宽里看出它们互相不吃宽度（置顶 tag 与未读药丸同时出现时
 * 标题还剩多少）。群那一行同时是"署发送者 + 2/3 人在线"两件事的现场。
 */
private fun conversationRowStates(): RelayUiState {
    val grouped = desktopPreviewGroupState()
    val groupId = grouped.settings.groups.first().groupId
    return grouped.copy(
        settings = grouped.settings.copy(
            pinnedConvIds = listOf(linPeer().nodeId),
            mutedConvIds = listOf(zhouPeer().nodeId),
        ),
        unreadByPeer = mapOf(
            linPeer().nodeId to 150,
            zhouPeer().nodeId to 4,
            ConvId.group(groupId) to 1,
        ),
    )
}

/**
 * 失败空态的夹具：节点没起来、一个对端都没有、也没有一条消息 —— 于是会话列表为空，
 * 屏幕上只剩这一档空态。
 *
 * `failure` 必须是**结构化**的（不是 null）：这一档的全部意义是"把原因如实说出来"，
 * 传 null 拍到的只是通用那句，等于把要钉的东西拍没了。
 */
private fun failedEmptyState(): RelayUiState {
    val base = desktopPreviewEmptyState()
    return base.copy(
        network = base.network.copy(
            phase = NodePhase.Failed,
            localAddresses = emptyList(),
            mdnsAdvertised = false,
            failure = BannerMessage.BindFailed(port = 49_213, reason = "Address already in use"),
        ),
        peers = emptyList(),
        banner = null,
    )
}

// ── 行级四状态（置顶 / 静音 / 99+ / 群署名）──────────────────────────────────

@PreviewTest
@Preview(name = "conv-row-states", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ConversationsRowStatesPhonePreview() {
    AppTheme(darkTheme = false) {
        ConversationsScreen(
            state = conversationRowStates(),
            onOpenChat = {},
            onGoToPairing = {},
            onDismissBanner = {},
            now = PREVIEW_NOW,
            onTogglePin = {},
            onToggleMute = {},
            onDeleteConversation = {},
        )
    }
}

@PreviewTest
@Preview(name = "desk-conv-row-states", widthDp = 1280, heightDp = 800, showBackground = true, locale = "zh-rCN")
@Composable
private fun ConversationsRowStatesDeskPreview() {
    val state = conversationRowStates()
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            ConversationSidebar(
                sessions = state.conversations,
                phase = state.network.phase,
                failure = state.network.failure,
                selectedPeerId = linPeer().nodeId,
                query = "",
                onClearQuery = {},
                onSelect = {},
                onGoToPairing = {},
                now = PREVIEW_NOW,
                modifier = Modifier.width(300.dp),
                onTogglePin = {},
                onToggleMute = {},
                onDeleteConversation = {},
                previewSenderNameOf = state::previewSenderNameOf,
            )
        }
    }
}

// ── Failed 空态（手机 / 桌面，同一份实现）────────────────────────────────────

@PreviewTest
@Preview(name = "conv-failed-empty", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ConversationsFailedEmptyPhonePreview() {
    AppTheme(darkTheme = false) {
        ConversationsScreen(
            state = failedEmptyState(),
            onOpenChat = {},
            onGoToPairing = {},
            onDismissBanner = {},
            // 这两条可空闸门决定长按菜单里「静音 / 删除」是否成立；不传的话
            // 配对门禁会认为这一屏根本没能力渲染那两支（欠武装的夹具会骗过覆盖）。
            onDeleteConversation = {},
            onToggleMute = {},
            now = PREVIEW_NOW,
        )
    }
}

@PreviewTest
@Preview(name = "desk-conv-failed-empty", widthDp = 1280, heightDp = 800, showBackground = true, locale = "zh-rCN")
@Composable
private fun ConversationsFailedEmptyDeskPreview() {
    val state = failedEmptyState()
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            ConversationSidebar(
                sessions = emptyList(),
                phase = state.network.phase,
                failure = state.network.failure,
                selectedPeerId = null,
                query = "",
                onClearQuery = {},
                onSelect = {},
                onGoToPairing = {},
                now = PREVIEW_NOW,
                modifier = Modifier.width(300.dp),
            )
        }
    }
}

// ── 长按菜单体（Popup 内容在 Layoutlib 下拍不到，所以直接渲染菜单体）──────────

@PreviewTest
@Preview(name = "conv-row-menu", widthDp = 393, heightDp = 300, showBackground = true, locale = "zh-rCN")
@Composable
private fun ConversationsRowMenuPhonePreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth()) {
                // isPinned = true ⇒ 首项必须是「取消置顶」：菜单文案与行的真实状态同源。
                ConversationActionsMenuContent(
                    isPinned = true,
                    isMuted = false,
                    onTogglePin = {},
                    onToggleMute = {},
                    onDelete = {},
                )
            }
        }
    }
}

@PreviewTest
@Preview(name = "desk-conv-row-menu", widthDp = 1280, heightDp = 300, showBackground = true, locale = "zh-rCN")
@Composable
private fun ConversationsRowMenuDeskPreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.width(260.dp)) {
                ConversationActionsMenuContent(
                    isPinned = false,
                    isMuted = true,
                    onTogglePin = {},
                    onToggleMute = {},
                    onDelete = {},
                )
            }
        }
    }
}

// ── 删除确认框（Dialog 族在 Layoutlib 下能完整组合）──────────────────────────

@PreviewTest
@Preview(name = "conv-delete-confirm", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ConversationsDeleteConfirmDialogPhonePreview() {
    DialogBackdropForConv {
        DeleteConversationConfirmDialog(onDismiss = {}, onConfirm = {})
    }
}

@PreviewTest
@Preview(name = "desk-conv-delete-confirm", widthDp = 1280, heightDp = 800, showBackground = true, locale = "zh-rCN")
@Composable
private fun ConversationsDeleteConfirmDialogDeskPreview() {
    DialogBackdropForConv {
        DeleteConversationConfirmDialog(onDismiss = {}, onConfirm = {})
    }
}

/**
 * 弹层的统一底衬：弹层自带遮罩，底衬用纯色 surface 即可（与
 * `ScreenshotPreviewsDialogs.kt` 的 `DialogBackdrop` 同一条口径）。
 * 单独写一份是因为那个 helper 是 private，跨文件不可见。
 */
@Composable
private fun DialogBackdropForConv(content: @Composable () -> Unit) {
    AppTheme(darkTheme = false) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
        ) {
            content()
        }
    }
}
