// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 榫卯 Tenon contributors

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.TeamMemberDraft
import com.kapp.tenon.core.transport.SendOutcome
import com.kapp.tenon.core.transport.LocalFile
import com.kapp.tenon.domain.ManualPeerEntry
import com.kapp.tenon.platform.ReceivedFileActions
// ExecDeviceRow 现在住在同一个包（领域侧）：它由 execDeviceRows() 算出来。
import kotlinx.serialization.json.JsonObject
import com.kapp.tenon.domain.model.MessageUi
import com.kapp.tenon.domain.model.PeerUi

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

    /** M7-T3 2b：产物校验的按需实测桥（平台读下载目录；null = 本设备无法实测）。 */
    val artifactDigestOf: (suspend (fileName: String) -> String?)?
        get() = null

    /**
     * 中断对端上的一次 agent 运行（PPE-1 §6.6）。
     *
     * 默认返回 null = 这个壳没有实现（UI 据此禁用「中断」）；**不要**在默认实现里假装成功 ——
     * 群里曾经用「发一条 /cancel 文本」当替身，结果是所有人看到一条消息、对端没中断。
     */
    suspend fun cancelAgentRun(
        peerId: String,
        sessionId: String,
        runId: String? = null,
        reason: String? = null,
    ): SendOutcome? = null

    /** 文件柜「加载更多」（协议已支持 offset/truncated）。默认空实现 = 这个壳不画该按钮。 */
    fun loadMoreCabinetEntries() {}

    /** 撤销本会话的「免审」授权。默认空实现 = 这个壳没有该入口（不画按钮）。 */
    fun revokeSessionApproval(sessionId: String) {}

    /** 「重连」一条已保存但当前不通的手动地址。默认空实现 = 这个壳没有该入口。 */
    fun reconnectManualPeer(entry: ManualPeerEntry) {}

    fun requestRestart()
    fun addManualPeer(rawHost: String, rawPort: String)

    fun removeManualPeer(entry: ManualPeerEntry)

    /**
     * 「移除节点」：手动地址与发现来的节点都走这一条。
     *
     * 默认实现只处理手动地址（预览/没有节点运行时的壳），把「删身份记忆」留给真正的
     * 控制器 —— 那一侧要动 registry 与四份落盘，不是一个空实现能糊过去的。
     */
    fun removeNode(removal: NodeRemoval) {
        if (removal is NodeRemoval.ManualAddress) removeManualPeer(removal.entry)
    }

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
     * 带意图的发送（方案 §4.2）：上下文唤起胶囊（§5.2）选中目标后，正文与
     * `agentIntent` **一帧**发出（宿主原子化「开会话 + 派单」）。
     *
     * 默认实现**忽略意图**、退回普通发送：预览/截图夹具没有意图通路，
     * 行为与没有这个方法时完全一致 —— 与 [loadOlderMessages] 的默认空实现同一取舍。
     * [RelayController] 覆盖它走群文本的 `agentIntent` 键。
     */
    fun sendToConversation(convId: String, text: String, replyTo: String?, agentIntent: JsonObject?) {
        sendToConversation(convId, text, replyTo)
    }

    /**
     * 「重发」：按**原消息 id** 重放（core 的 `replayOutgoing`），不新发一条。
     *
     * 换 id 重发会让对端多落一条 —— 真机现场同一句话出现两个 id。
     * 与手机端同一个入口（`RelayController.retryMessage`）。
     */
    fun retryMessage(message: MessageUi)
    /**
     * **从「@ 了但没有执行权」那一行直接申请授权。**
     *
     * 与手机端同一个入口（`RelayController.requestAgentGrantFromNotice`）：
     * 用户读到「没有执行」的那一刻就是想授权的时刻。
     */
    fun requestAgentGrantFromNotice(groupId: String, agentName: String)
    /**
     * 向上翻页：把某个会话里更早的一页读进内存。
     *
     * 默认空实现是**正当**的：预览/截图夹具与「不落盘」的壳本来就没有更早的历史可翻。
     * 真正持久化的宿主（RelayController）必须覆盖它，否则用户滚到顶就再也看不到更早的消息。
     */
    fun loadOlderMessages(convId: String) = Unit

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
     * 设 / 换 / 清一个群的项目目录指针（D5：`groupId → dirId`，仅宿主可改）。
     *
     * true = 指针已写、席位投影已重建并广播；false = 本机不是宿主（手机）/ 群不存在 /
     * dirId 悬空 —— 什么都没改。UI 只准走这一条写路径：指针与投影重建、广播必须
     * 同一次完成，单独写指针会让席位声明与本机台账各说各话。
     */
    fun setGroupProjectDirectory(groupId: String, dirId: String?): Boolean

    /**
     * 加智能体进群（D6：不再问目录；席位就地继承群项目目录）。
     *
     * [duty] 是席位职责（[com.kapp.tenon.core.protocol.AgentDuties] 取值域，
     * 域外值归一为单兵默认）。[com.kapp.tenon.domain.AddGroupAgentResult.Rejected]
     * 携带结构化原因，UI 不猜成败。
     */
    fun addGroupAgent(groupId: String, agentId: String, duty: String?): AddGroupAgentResult

    /**
     * 重新解析本机席位的执行投影并广播（「去暴露」/ agent 刚宣告目录之后）。
     * 幂等：没有变化就不产生新 rev。false = 没有目录端口（手机）或群不存在。
     */
    fun rebuildGroupSeatProjections(groupId: String): Boolean

    /**
     * 移除本机的一个席位（撤回入驻；D6 的另一半）。主权边界：只覆盖本机自己的
     * 声明，其它设备的原样保留。false = 群不存在 / 没有这个 agentId 的席位。
     */
    fun removeGroupAgent(groupId: String, agentId: String): Boolean

    /**
     * 保存 / 关闭某个席位的团队编制（团队创建方案 §4.3）。
     *
     * 一次保存 = 一次 rev+1、一次广播，没有中间态。[roster] 为 null = 关闭团队
     * （`entityType` 回 single、组员清空）。定位键 = `(groupId, hostNodeId, agentId)`
     * （D4 的席位身份；本机 host 由 selfNodeId 隐含），席位必须属于本机声明（机主主权）。
     */
    fun setGroupTeamDeclaration(groupId: String, agentId: String, roster: List<TeamMemberDraft>?)

    /**
     * 团队执行档端口（团队执行方案 §6.3）：TeamRosterDialog 执行配置段的读写通路。
     * null = 该壳没有执行面（手机等非宿主）⇒ 只读投影（执行配置由席位宿主管理）。
     * 只有桌面宿主覆写（DesktopExtension 装配 DesktopTeamExecutionPort）。
     */
    val teamExecutionPort: TeamExecutionPort?
        get() = null

    /**
     * 项目目录端口（项目目录决策稿 §5.1）：目录档案 / 群指针 / 白名单的读写通路。
     * null = 该壳没有宿主目录面（手机等非宿主）⇒ 只读投影（档案由目录宿主管理，
     * 手机只能看到群已指向的目录名）。只有桌面宿主覆写
     * （DesktopExtension 装配 DesktopHostDirectoryPort）。
     */
    val hostDirectoryPort: HostDirectoryPort?
        get() = null

    /**
     * 算「谁能用这个 agent 干活」的逐设备勾选状态。
     *
     * null = 这个壳没有台账（手机连别人的机器：那是对方的主权，我配不了）。
     * 只有桌面端实现得出 —— 它持有 `AgentGrantStore`（宿主自己的授权账面）。
     */
    fun execDevicesOf(agentKey: String?): List<ExecDeviceRow>? = null

    /**
     * 勾选 / 取消某台设备对这个 agent 的执行权。
     *
     * **只动 `scope=AGENT` 这一档**：取消时不该碰「这台设备是可信任的」那条记录 ——
     * 「不许它执行这个 agent」与「不再信任这台设备」是两件事，
     * 混在一起会让用户取消一个勾、结果把人整台设备都踢了。
     */
    fun setExecDeviceAllowed(agentKey: String, nodeId: String, allowed: Boolean): Unit = Unit

    /** 「添加设备」：从候选里挑一个加到名单。默认不提供（手机壳没有台账）。 */
    fun addExecDevice(agentKey: String): Unit = Unit

    /**
     * 建一个群：一个名字 + 一份成员表。
     *
     * @return 新群的 groupId；名字为空或一个成员都没选时返回 null（控制器已经弹过横幅）。
     */
    fun createGroup(name: String, memberIds: List<String>, description: String = ""): String?

    /**
     * 给一个**工作区**建协同群（[wsId] 为 null = 「关联本地全新目录」，还没有工作区行）。
     *
     * 与 [createGroup] 的差别只有一条，但正是用户踩到的那个坑：**允许此刻没有任何
     * 其它设备**，并把工作区上的 agent 声明进群。工作区群是「先建出来、再分享码拉设备」，
     * 而 [createGroup] 要求「至少再选一个人」——建群对话框在这一支里根本没有可勾的人，
     * 于是单机用户永远建不出群（真机反馈：「无法快速建群，一直提示要至少一个人」）。
     *
     * @return 新群的 groupId；名字不合法（空 / 超长）时为 null。
     */
    fun createWorkspaceGroup(
        name: String,
        wsId: String?,
        memberIds: List<String>,
        description: String = "",
    ): String? = createGroup(name, memberIds, description)

    /**
     * 邀请新成员加入群聊。
     */
    fun addGroupMembers(groupId: String, newMemberIds: List<String>)

    /**
     * 移出群成员（仅群主或管理员有权）。
     */
    fun removeGroupMember(groupId: String, memberId: String)

    /**
     * 群主**解散**群：对所有成员生效（不是「我退出去」）。
     *
     * 默认空实现 = 这个壳没有解散能力（预览/截图夹具），群信息里不画那颗按钮 ——
     * 而不是给一个按了什么都不发生的动作。
     */
    fun dissolveGroup(groupId: String): Unit = Unit

    fun pickAndSendFiles(peerId: String)

    suspend fun pickFiles(): List<LocalFile> = emptyList()

    fun sendFiles(
        peerId: String,
        files: List<LocalFile>,
        isFolder: Boolean = false,
        folderRootName: String? = null,
    ) {}

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
     * **只删本机**这一条：(会话键, 消息 id)。不动协议、不通知对方。
     *
     * 与 [recall] 是两件事：撤回有时限、会告诉对方；删除是「我自己不想再看到它」。
     */
    fun deleteMessage(convId: String, messageId: String)

    /** 置顶 / 取消置顶一个会话（纯本机偏好，与手机壳同一份语义）。 */
    fun togglePinnedConv(convId: String)

    /** 静音 / 取消静音一个会话（消息照收，只是不再通知）。 */
    fun toggleMutedConv(convId: String)

    /** 删掉一个会话（只删本机：消息、未读、置顶/静音标记）。不动协议、不通知对方。 */
    fun deleteConversation(convId: String)

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
    fun searchHistoricalMessages(query: String, limit: Long = 50): List<com.kapp.tenon.domain.MessageSearchResult> = emptyList()

    /**
     * **全局**检索：一页里的三块（联系人 / 聊天记录 / 文件）。
     *
     * 与 [searchHistoricalMessages] 并存而不是取代它：后者只查聊天记录、返回明细，
     * 是会话列表那个筛选框在用的（`ConversationSidebar` 的 `onSearchMessages`）；这一条是三段聚合，
     * 供全局搜索面板用。合并的话，会话列表为了拿"聊天记录那一段"得去构造一个
     * 只有一块有值的 `GlobalSearchResult`，而那个空壳的三个空 list 又要被它自己忽略。
     */
    fun searchGlobal(query: String): com.kapp.tenon.domain.GlobalSearchResult =
        com.kapp.tenon.domain.GlobalSearchResult(query = query)

    /**
     * **会话内**检索，可带类型与日期条件。
     */
    fun searchInConversation(
        convId: String,
        query: String,
        filter: com.kapp.tenon.domain.HistorySearchFilter = com.kapp.tenon.domain.HistorySearchFilter.None,
        limit: Long = 50,
    ): List<com.kapp.tenon.domain.MessageSearchResult> = emptyList()

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
    fun downloadCabinetEntries(names: List<String>)
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
    fun setScreenViewMode(mode: com.kapp.tenon.core.protocol.ScreenMode)
    fun stopScreenView()

    // ------------------------------------------------------------------ Agent 审批与控制
    fun respondApproval(approvalId: String, allow: Boolean): Unit = Unit
    fun respondApproval(approvalId: String, allow: Boolean, allowSession: Boolean): Unit = Unit

    /**
     * 四态处置：拒绝 / 批准 / 本次免审 / **始终允许**。
     *
     * 默认实现是空的（预览与截图夹具没有宿主可派发）—— 与上面两条同一条纪律：
     * **默认实现不许假装成功**。多出来的那一档（`ALLOW_ALWAYS`）由宿主记免批规则，
     * 壳这边只负责把用户点的那一种原样交上去，不做任何本地推断。
     */
    fun respondApproval(approvalId: String, decision: com.kapp.tenon.domain.model.AgentApprovalDecision): Unit = Unit
    fun cancelAgentTask(taskId: String): Unit = Unit
    fun requestAgentSessionPage(peerId: String, wsId: String, offset: Long = 0, limit: Long? = null): Unit = Unit

    /**
     * 卡片动作真通路（M4 任务 5，§7.3-2）：群里的审批/控制按钮 → 宿主原子记账 + 全群广播。
     *
     * 参数接**原语**（groupId / cardId / rev / actionId）—— 壳的卡片模型不进域层。
     * 默认空实现是**正当**的：预览/截图夹具没有宿主可派发动作，与「默认实现不许假装成功」
     * 的纪律一致 —— 真正的控制面在 [RelayController] 里（原子台账 + 广播）。
     */
    fun handleCardAction(groupId: String, cardId: String, cardRev: Int, actionId: String): Unit = Unit

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
     * [com.kapp.tenon.domain.RelayController] 补 override、预览侧 [DesktopPreviewActions] 补空实现），
     * 与 [setPeerRemark] 同一条规矩 —— 静默继承一个"什么都不做"的默认体，会把
     * "桌面能不能清会话"变成运行期才发现的事。
     */
    fun clearMessages(convId: String)

    // ------------------------------------------------------------------ 群管理
    fun leaveGroup(groupId: String): Unit = Unit

    // ------------------------------------------------------------------ 语音消息
    fun sendVoice(peerId: String, filePath: String): Unit = Unit
}

