// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui.desktop

import com.kapp.tidings.domain.DesktopPaneActions
import com.kapp.tidings.domain.ManualPeerEntry
import com.kapp.tidings.domain.model.MessageUi
import com.kapp.tidings.platform.ReceivedFileActions

/**
 * 预览专用的空实现：每个方法都不做事。
 *
 * 存在的意义是让截图参考图可复现 —— 预览必须渲染真实界面，但绝不能碰网络或磁盘。
 */
public object DesktopPreviewActions : DesktopPaneActions {
    // 预览里没有真平台，也就没有"交给别的应用"这条路 —— 按钮不画，
    // 免得参考图上出现一个预览环境根本执行不了的入口。
    override val receivedFileActions: ReceivedFileActions? = null

    override fun requestRestart(): Unit = Unit

    override fun addManualPeer(rawHost: String, rawPort: String): Unit = Unit

    override fun removeManualPeer(entry: ManualPeerEntry): Unit = Unit

    override fun removeUnreachableManualPeers(): Unit = Unit

    // 预览里改备注只改这一份夹具状态，不落盘（`DesktopPreviewActions` 是无状态 object，
    // 截图帧各自给 `state.peers` 里想要的 `remark` 初值即可）。
    override fun setPeerRemark(nodeId: String, remark: String): Unit = Unit

    override fun sendToConversation(convId: String, text: String, replyTo: String?): Unit = Unit

    override fun retryMessage(message: MessageUi): Unit = Unit

    override fun requestAgentGrantFromNotice(groupId: String, agentName: String): Unit = Unit

    override fun sendPk(convId: String, game: String, value: String): Unit = Unit

    override fun forwardMessage(message: MessageUi, targetConvId: String): Unit = Unit

    override fun updateGroupMeta(groupId: String, description: String?, announce: String?): Unit = Unit
    override fun setGroupAgentWorkspaces(groupId: String, wsIds: List<String>): Unit = Unit

    /**
     * 预览里建群不做事，但**必须回一个 id**：`DesktopShell` 的会话侧栏拿到 null
     * 会当成"没建成"，而参考图要拍的正是"对话框关掉、群出现在列表里"之后的画面。
     * 回一个固定的假 id，让这条路径在预览里也能走完。
     */
    override fun createGroup(name: String, memberIds: List<String>, description: String): String? = "preview-group"

    override fun addGroupMembers(groupId: String, newMemberIds: List<String>): Unit = Unit

    override fun removeGroupMember(groupId: String, memberId: String): Unit = Unit

    override fun pickAndSendFiles(peerId: String): Unit = Unit

    override fun acceptTransfer(transferId: String): Unit = Unit

    override fun declineTransfer(transferId: String): Unit = Unit

    override fun cancelTransfer(transferId: String): Unit = Unit

    override fun resumeTransfer(transferId: String): Unit = Unit

    override fun clearTransferLogs(): Unit = Unit

    override fun recall(message: MessageUi): Unit = Unit

    override fun deleteMessage(convId: String, messageId: String): Unit = Unit

    override fun togglePinnedConv(convId: String): Unit = Unit

    override fun toggleMutedConv(convId: String): Unit = Unit

    override fun deleteConversation(convId: String): Unit = Unit

    override fun nudge(peerId: String): Unit = Unit

    override fun dismissBanner(): Unit = Unit

    // 预览里没有真控制器，也就没有未读可清；不做事，参考图的未读数字由夹具给定。
    override fun setVisibleConversation(convId: String?): Unit = Unit

    override fun clearMessages(convId: String): Unit = Unit

    override fun searchHistoricalMessages(query: String, limit: Long): List<com.kapp.tidings.domain.MessageSearchResult> = emptyList()

    override fun setShareRoot(path: String?): Unit = Unit
    override fun pickShareRoot(): Unit = Unit
    override fun setShareMode(mode: String): Unit = Unit
    override fun setShareGrant(nodeId: String, mode: String): Unit = Unit
    override fun removeShareGrant(nodeId: String): Unit = Unit
    override fun selectCabinetPeer(nodeId: String?): Unit = Unit
    override fun loadCabinetPath(path: String): Unit = Unit
    override fun toggleCabinetSelection(name: String): Unit = Unit
    override fun clearCabinetSelection(): Unit = Unit
    override fun downloadCabinetEntries(names: List<String>): Unit = Unit
    override fun uploadToCabinet(peerId: String): Unit = Unit
    override fun setCabinetViewMode(mode: String): Unit = Unit

    override fun addScanRange(cidr: String): Unit = Unit
    override fun removeScanRange(cidr: String): Unit = Unit
    override fun triggerCidrScan(cidr: String?): Unit = Unit

    override fun triggerScreenCapture(targetConvId: String?): Unit = Unit

    override fun requestScreenView(peerId: String): Unit = Unit
    override fun respondScreenView(accept: Boolean): Unit = Unit
    override fun setScreenViewMode(mode: com.kapp.tidings.core.protocol.ScreenMode): Unit = Unit
    override fun stopScreenView(): Unit = Unit
}
