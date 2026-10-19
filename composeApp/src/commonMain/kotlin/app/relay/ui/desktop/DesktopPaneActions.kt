// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 捎话 Tidings contributors

package app.relay.ui.desktop

import app.relay.app.ManualPeerEntry
import app.relay.platform.ReceivedFileActions
import app.relay.app.model.MessageUi
import app.relay.app.model.PeerUi

/**
 * 桌面 pane 真正需要的动作面，恰好等于它对 controller 做过的那些方法引用。
 *
 * 为什么不直接把 `RelayController` 传给 pane：那样每个 `@Preview` 都得先造一个真
 * controller，而 `RelayController` 的构造会拉起平台传输和协程 —— 预览不该启动节点。
 * 把这 10 个方法抽成接口之后，生产环境仍然传 `RelayController`（它实现了本接口），
 * 预览传 [DesktopPreviewActions]，两侧走同一条渲染路径。
 *
 * 只收窄这一层，不动 `DesktopShellActions`：后者是**宿主**动作（开设置窗、切主题、
 * 退出、拖放回调），只存在于真实窗口里，不参与预览。
 */
public interface DesktopPaneActions {
    /**
     * "把已收到的文件交给别的应用"的能力；`null` = 本平台没有这条出口。
     *
     * 放进来是因为平板走的就是桌面壳：聊天页 [ChatPane] 也要能打开/分享收到的文件，
     * 只接移动端那条路等于平板永远没有这个入口。它不是动作而是**设备事实**，
     * 所以是只读属性，和 controller 上的那个直通字段同源。
     */
    val receivedFileActions: ReceivedFileActions?

    fun requestRestart()

    fun addManualPeer(rawHost: String, rawPort: String)

    fun removeManualPeer(entry: ManualPeerEntry)

    fun removeUnreachableManualPeers()

    /**
     * 改**本机**给某个节点起的备注名（茶话间 F-DISC-9）。
     *
     * `remark` 传空串 = 删掉备注（`PeerUi.displayName` 会回落到昵称）。键是 `nodeId`，
     * 落盘在 `RelaySettings.peerRemarks`；写成之后控制器会补一次节点表刷新，所以
     * 左栏那一行的名字会立刻跟着变。
     *
     * 签名与 `RelayController.setPeerRemark` **逐字相同**：接口成员不是"可选扩展"，
     * 加一个成员就要求**所有实现者**都动 —— 生产侧 `RelayController` 补 `override`
     * （`RelayController.kt:145`）、预览侧 `DesktopPreviewActions` 补空实现，
     * 两件都躲不掉（我把这条写反过一次、把树弄红，Lead 修好并纠正）。
     */
    fun setPeerRemark(nodeId: String, remark: String)

    /**
     * 往一个**会话**发文本（会话键：私聊就是 nodeId，群是 `group:<groupId>`）。
     *
     * 不叫 `send(peerId, …)` 是因为群会话的键不是节点 id。让每个调用点自己判断
     * "这是群还是私聊"，就是把同一套判断抄到每一处，漏一处就是"在群里说话却发给了某个人"。
     */
    fun sendToConversation(convId: String, text: String, replyTo: String? = null)

    /**
     * 发起 PK 小游戏（猜拳 / 骰子）。
     */
    fun sendPk(convId: String, game: String, value: String)

    /**
     * 转发一条消息到目标会话。
     */
    fun forwardMessage(message: MessageUi, targetConvId: String)

    /**
     * 更新群简介与公告（仅群主/管理员）。
     */
    fun updateGroupMeta(groupId: String, description: String?, announce: String?)

    /**
     * 建一个群：一个名字 + 一份成员表。
     *
     * @return 新群的 groupId；名字为空或一个成员都没选时返回 null（控制器已经弹过横幅）。
     */
    fun createGroup(name: String, memberIds: List<String>, description: String = ""): String?

    /**
     * 邀请新成员加入群聊。
     */
    fun addGroupMembers(groupId: String, newMemberIds: List<String>)

    /**
     * 移出群成员（仅群主或管理员有权）。
     */
    fun removeGroupMember(groupId: String, memberId: String)

    fun pickAndSendFiles(peerId: String)

    fun acceptTransfer(transferId: String)

    fun declineTransfer(transferId: String)

    /**
     * 撤回自己发出的、还在进行中的传输。
     *
     * 桌面壳也需要它：文件卡片上的「取消」以前在桌面上不画，因为动作面里没有这一条，
     * 结果是发了就只能等它自己失败。core 侧本来就有 `cancelOutgoing`，缺的只是这条线。
     */
    fun cancelTransfer(transferId: String)

    /**
     * 续传一个失败的入站文件。
     */
    fun resumeTransfer(transferId: String)

    /**
     * 清空最近传输记录。
     */
    fun clearTransferLogs()

    /**
     * 撤回自己发的一条消息（协议 §8：自己的文本/群文本/PK/图片/文件，5 分钟窗口内）。
     *
     * 桌面壳也要这条线：撤回是"发错了"的唯一出路，而它在两栏里比在手机上更常被用到。
     * 界面只在 `canRecall` 为真时长按才给这一项，控制器里还会再挡一道。
     */
    fun recall(message: MessageUi)

    /**
     * 戳一碰（协议里**仅单聊**、不入离线队列、按对端限流）。
     *
     * 与 [recall] 同一理由进这一层：桌面和平板走的是同一个聊天页。
     */
    fun nudge(peerId: String)

    fun dismissBanner()

    /**
     * 告诉控制器"用户此刻正看着哪个会话"（`null` = 没选中任何一个）。
     *
     * 桌面壳必须自己推：它不走 `App.kt` 的 `nav`（选中项归壳独占，见
     * [DesktopShell] 里 `initialPeerId` 的说明），所以 `nav.activeSessionId`
     * 那条线在这里不成立 —— 少了这条，桌面窗口里停在一个会话上收消息
     * 同样会留下一个不消的未读。平板走的就是桌面壳，一并受益。
     */
    fun setVisibleConversation(convId: String?)

    /**
     * 历史消息全文检索。
     */
    fun searchHistoricalMessages(query: String, limit: Long = 50): List<app.relay.app.MessageSearchResult> = emptyList()

    /**
     * **全局**检索：一页里的三块（联系人 / 聊天记录 / 文件）。
     *
     * 与 [searchHistoricalMessages] 并存而不是取代它：后者只查聊天记录、返回明细，
     * 是会话列表那个筛选框在用的（`ConversationSidebar` 的 `onSearchMessages`）；这一条是三段聚合，
     * 供全局搜索面板用。合并的话，会话列表为了拿"聊天记录那一段"得去构造一个
     * 只有一块有值的 `GlobalSearchResult`，而那个空壳的三个空 list 又要被它自己忽略。
     */
    fun searchGlobal(query: String): app.relay.app.GlobalSearchResult =
        app.relay.app.GlobalSearchResult(query = query)

    /**
     * **会话内**检索，可带类型与日期条件。
     */
    fun searchInConversation(
        convId: String,
        query: String,
        filter: app.relay.app.HistorySearchFilter = app.relay.app.HistorySearchFilter.None,
        limit: Long = 50,
    ): List<app.relay.app.MessageSearchResult> = emptyList()

    // ------------------------------------------------------------------ 共享文件柜 (Cabinet)
    fun setShareRoot(path: String?)
    fun pickShareRoot()
    fun setShareMode(mode: String)
    fun setShareGrant(nodeId: String, mode: String)
    fun removeShareGrant(nodeId: String)
    fun selectCabinetPeer(nodeId: String?)
    fun loadCabinetPath(path: String)
    fun toggleCabinetSelection(name: String)
    fun clearCabinetSelection()
    fun downloadCabinetEntries(names: List<String>, saveAs: Boolean = false)
    fun uploadToCabinet(peerId: String)
    fun setCabinetViewMode(mode: String)

    // ------------------------------------------------------------------ CIDR 网段探测与同步
    fun addScanRange(cidr: String)
    fun removeScanRange(cidr: String)
    fun triggerCidrScan(cidr: String? = null)

    // ------------------------------------------------------------------ 桌面截图标注
    fun triggerScreenCapture(targetConvId: String? = null)

    // ------------------------------------------------------------------ 屏幕协助 (Remote View)
    fun requestScreenView(peerId: String)
    fun respondScreenView(accept: Boolean)
    fun setScreenViewMode(mode: app.relay.core.protocol.ScreenMode)
    fun stopScreenView()

    // ------------------------------------------------------------------ Agent 审批与控制
    fun respondApproval(approvalId: String, allow: Boolean): Unit = Unit
    fun respondApproval(approvalId: String, allow: Boolean, allowSession: Boolean): Unit = Unit
    fun cancelAgentTask(taskId: String): Unit = Unit
    fun requestAgentSessionPage(peerId: String, wsId: String, offset: Long = 0, limit: Long? = null): Unit = Unit

    // ------------------------------------------------------------------ 会话与节点
    fun selectConversation(convId: String): Unit = Unit
    fun refreshPeers(): Unit = Unit

    /**
     * 「清空本会话聊天记录」：只清**本机**这个会话的历史，对方那里不受影响。
     *
     * 键是**会话键**（私聊 = 对方 nodeId、群 = `group:<groupId>`），与 [sendToConversation] /
     * [selectConversation] 同一口径 —— 参数名因此叫 convId，不叫 peerId。
     *
     * 没有默认体：它是不可恢复的破坏性动作，必须由每个实现者明确表态（生产侧
     * [app.relay.app.RelayController] 补 override、预览侧 [DesktopPreviewActions] 补空实现），
     * 与 [setPeerRemark] 同一条规矩 —— 静默继承一个"什么都不做"的默认体，会把
     * "桌面能不能清会话"变成运行期才发现的事。
     */
    fun clearMessages(convId: String)

    // ------------------------------------------------------------------ 群管理
    fun leaveGroup(groupId: String): Unit = Unit

    // ------------------------------------------------------------------ 语音消息
    fun sendVoice(peerId: String, filePath: String): Unit = Unit
}


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

    override fun sendPk(convId: String, game: String, value: String): Unit = Unit

    override fun forwardMessage(message: MessageUi, targetConvId: String): Unit = Unit

    override fun updateGroupMeta(groupId: String, description: String?, announce: String?): Unit = Unit

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

    override fun nudge(peerId: String): Unit = Unit

    override fun dismissBanner(): Unit = Unit

    // 预览里没有真控制器，也就没有未读可清；不做事，参考图的未读数字由夹具给定。
    override fun setVisibleConversation(convId: String?): Unit = Unit

    override fun clearMessages(convId: String): Unit = Unit

    override fun searchHistoricalMessages(query: String, limit: Long): List<app.relay.app.MessageSearchResult> = emptyList()

    override fun setShareRoot(path: String?): Unit = Unit
    override fun pickShareRoot(): Unit = Unit
    override fun setShareMode(mode: String): Unit = Unit
    override fun setShareGrant(nodeId: String, mode: String): Unit = Unit
    override fun removeShareGrant(nodeId: String): Unit = Unit
    override fun selectCabinetPeer(nodeId: String?): Unit = Unit
    override fun loadCabinetPath(path: String): Unit = Unit
    override fun toggleCabinetSelection(name: String): Unit = Unit
    override fun clearCabinetSelection(): Unit = Unit
    override fun downloadCabinetEntries(names: List<String>, saveAs: Boolean): Unit = Unit
    override fun uploadToCabinet(peerId: String): Unit = Unit
    override fun setCabinetViewMode(mode: String): Unit = Unit

    override fun addScanRange(cidr: String): Unit = Unit
    override fun removeScanRange(cidr: String): Unit = Unit
    override fun triggerCidrScan(cidr: String?): Unit = Unit

    override fun triggerScreenCapture(targetConvId: String?): Unit = Unit

    override fun requestScreenView(peerId: String): Unit = Unit
    override fun respondScreenView(accept: Boolean): Unit = Unit
    override fun setScreenViewMode(mode: app.relay.core.protocol.ScreenMode): Unit = Unit
    override fun stopScreenView(): Unit = Unit
}
