// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import app.relay.app.model.DeliveryState
import app.relay.app.model.MessageUi
import app.relay.app.model.NoticeKind
import app.relay.core.nowMillis
import app.relay.core.protocol.resolveVirtualAgentKey
import app.relay.core.protocol.Codec
import app.relay.core.protocol.ConvId
import app.relay.core.protocol.Envelope
import app.relay.core.protocol.GroupMerge
import app.relay.core.protocol.GroupMergeDecision
import app.relay.core.protocol.GroupMeta
import app.relay.core.protocol.withLocalExtras
import app.relay.core.protocol.GroupOps
import app.relay.core.protocol.Limits
import app.relay.core.protocol.Protocol
import app.relay.core.protocol.newId
import app.relay.core.session.NodeRuntime
import app.relay.core.transport.SendOutcome
import app.relay.generated.resources.Res
import app.relay.generated.resources.workspace_group_initial_greeting
import app.relay.generated.resources.workspace_group_session_brief
import app.relay.generated.resources.workspace_group_session_busy
import app.relay.generated.resources.workspace_group_session_ready
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.compose.resources.getString
import app.relay.core.protocol.AgentSenderId
import app.relay.core.protocol.VirtualAgentMember
import app.relay.core.protocol.AgentMemberName

/**
 * 群聊：建群、入退群、改群资料、发群公告，以及群消息的入站处理。
 *
 * 切出来的理由：这一族的演化动力与其它 7 个分片互不相干。过去它们都是
 * `internal fun RelayController.` 的顶层扩展，凑在一起约 3000 行；改其中一族要在
 * 另外六族里找它依赖了什么。
 *
 * @param host 提供本族用到的控制器成员与同包顶层扩展。逐个 `host.` 前缀而不是
 *   持有整个控制器，是为了让**依赖面完全显式**：读方法体就知道这一族碰了哪些状态。
 *   只对本族真正用到的名字加前缀 —— 同名的函数形参会遮蔽控制器成员（`state` 就是
 *   一例：控制器有 `state: StateFlow<RelayUiState>`，而 agent 帧形参叫 `state: String?`），
 *   给它加前缀会直接产出语法垃圾。
 */
internal class RelayControllerGroupsComponent(
    private val host: RelayController,
) {

fun isWorkspaceGroup(groupId: String): Boolean {
    val meta = host._state.value.settings.groups.firstOrNull { it.groupId == groupId } ?: return false
    val desc = meta.description.orEmpty().trim()
    return host._state.value.agentWorkspaces.values.any { ws ->
        (desc.isNotBlank() && (desc == ws.wsPath || desc == ws.wsId || (ws.wsPath?.isNotBlank() == true && desc.contains(ws.wsPath)))) ||
            (ws.wsTitle?.isNotBlank() == true && meta.name.contains(ws.wsTitle))
    }
}

fun workspaceOfGroup(groupId: String): app.relay.app.model.WorkspaceUi? {
    val meta = host._state.value.settings.groups.firstOrNull { it.groupId == groupId } ?: return null
    val desc = meta.description.orEmpty().trim()
    return host._state.value.agentWorkspaces.values.firstOrNull { ws ->
        (desc.isNotBlank() && (desc == ws.wsPath || desc == ws.wsId || (ws.wsPath?.isNotBlank() == true && desc.contains(ws.wsPath)))) ||
            (ws.wsTitle?.isNotBlank() == true && meta.name.contains(ws.wsTitle))
    }
}

fun handleSyncWorkspaceGroups() {

    val settings = host._state.value.settings
    var changed = false
    val updated = settings.groups.map { group ->
        if (group.description.isNullOrBlank()) {
            val matchedWs = host._state.value.agentWorkspaces.values.firstOrNull { ws ->
                ws.wsTitle?.isNotBlank() == true && group.name.contains(ws.wsTitle)
            }
            if (matchedWs != null) {
                changed = true
                // **不写文件系统路径**：群简介是给群成员看的，本机目录路径
                // 这种本机路径对别人既无意义、也暴露了本机目录结构（真机反馈 #4）。
                // 用工作区展示名，没有名字就不填 —— 空着比填一个实现细节好。
                group.copy(description = matchedWs.wsTitle.orEmpty())
            } else {
                group
            }
        } else {
            group
        }
    }
    if (changed) {
        host.handlePersist(settings.copy(groups = updated))
    }
}

/**
 * [RelayController] 的群组域处理扩展。
 *
 * 覆盖建群、邀请/移出成员、群文本发送、GroupNeed/GroupInfo 协议协商与同步。
 */

fun handleCreateGroup(name: String, memberIds: List<String>, description: String = ""): String? {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) {
        host.warn(BannerMessage.GroupNameRequired)
        return null
    }
    if (trimmed.length > Limits.GROUP_NAME) {
        host.warn(BannerMessage.GroupNameTooLong(Limits.GROUP_NAME))
        return null
    }
    val self = host._state.value.selfNodeId
    val others = memberIds.map { it.trim() }.filter { it.isNotEmpty() && it != self }.distinct()
    if (others.isEmpty()) {
        host.warn(BannerMessage.GroupNeedsMember)
        return null
    }
    val autoDesc = if (description.isNotBlank()) description.trim() else {
        val foundWs = host._state.value.agentWorkspaces.values.firstOrNull { ws ->
            ws.wsTitle?.isNotBlank() == true && trimmed.contains(ws.wsTitle)
        }
        // 同上：简介用**展示名**，不用路径 / wsId（那是内部标识，不是给人读的）。
        foundWs?.wsTitle.orEmpty()
    }
    val meta = GroupMeta.create(
        groupId = newId(),
        name = trimmed,
        members = others,
        selfId = self,
        updatedTs = nowMillis(),
        description = autoDesc,
    )
    host.handlePersist(host._state.value.settings.copy(groups = host._state.value.settings.groups + meta))
    // 建群这条路径也要把 rev 登记进 need 游标：我们**刚刚**有这个群的元数据了。
    // 漏了它会怎样？建完群收到的第一条同 rev 群消息会回一发 `op:need` —— 去问一份
    // 自己手上就有的成员表。对端若还回 host.info，就是白跑一个来回；若不回，游标一直不前进，
    // 则**每条**群消息都带上这一问（对端在线却从不回 host.info 的情形）。
    // 另外三条写元数据的路径（加人/移人/收到 host.info）都登记了，只有这里是漏的。
    host.core.groupNeeds.noteKnown(meta.groupId, meta.rev)

    val targetWs = if (autoDesc.isNotBlank()) {
        host._state.value.agentWorkspaces.values.firstOrNull { ws ->
            autoDesc == ws.wsPath || autoDesc == ws.wsId || (ws.wsPath?.isNotBlank() == true && autoDesc.contains(ws.wsPath))
        }
    } else {
        host._state.value.agentWorkspaces.values.firstOrNull { ws ->
            ws.wsTitle?.isNotBlank() == true && trimmed.contains(ws.wsTitle)
        }
    }

    if (targetWs != null) {
        // `wsId` 在 WorkspaceUi 上是非空的（见 AgentUi 的声明），所以这里不用再兜到描述串 ——
        // 那个兜底是死代码，编译器会报「Elvis 恒取左操作数」。
        val wsName = targetWs.wsTitle ?: targetWs.wsId
        val agentKind = targetWs.agentKind ?: "DSH"
        // agent 的身份是 **(设备, agent)**，不是裸 kind —— 否则两台设备上同名 agent
        // 在 senderId 里完全一样，会被合并成同一个人（见 AgentSenderId 的 KDoc）。
        val agentSenderId = AgentSenderId.of(self, targetWs.agentId, agentKind)
        host.scope.launch {
            val greeting = runCatching {
                getString(Res.string.workspace_group_initial_greeting, agentKind, wsName)
            }.getOrNull() ?: "[$agentKind] $wsName"
            host.handleAppendMessage(
                MessageUi(
                    id = host.localMessageId(),
                    peerId = ConvId.group(meta.groupId),
                    mine = false,
                    text = greeting,
                    at = nowMillis(),
                    delivery = null,
                    senderId = agentSenderId,
                ),
            )
            // 同步工作区当前活跃会话状态到群聊
            val activeSession = host._state.value.agentSessions.values.firstOrNull {
                it.wsId == targetWs.wsId && !it.isArchived && it.state == "busy"
            } ?: host._state.value.agentSessions.values.firstOrNull {
                it.wsId == targetWs.wsId && !it.isArchived && it.title?.isNotBlank() == true
            }
            if (activeSession != null) {
                val stateText = if (activeSession.state == "busy") {
                    getString(Res.string.workspace_group_session_busy)
                } else {
                    getString(Res.string.workspace_group_session_ready)
                }
                val sessionTitle = activeSession.title ?: activeSession.alias ?: activeSession.sessionId
                val brief = getString(Res.string.workspace_group_session_brief, sessionTitle, stateText)
                host.handleAppendMessage(
                    MessageUi(
                        id = host.localMessageId(),
                        peerId = ConvId.group(meta.groupId),
                        mine = false,
                        text = brief,
                        at = nowMillis() + 10,
                        delivery = null,
                        senderId = agentSenderId,
                    ),
                )
            }
        }
    }

    val rt = host.runtime
    if (rt == null) {
        host.info(BannerMessage.GroupCreated(meta.name, others.size))
        host.publish()
        return meta.groupId
    }
    host.scope.launch {
        for (member in others) {
            runCatching { rt.sendGroupInfo(member, host.groupMetaWithNicks(meta)) }
        }
        host.refreshPeers()
    }
    host.info(BannerMessage.GroupCreated(meta.name, others.size))
    host.publish()
    return meta.groupId
}


fun handleAddGroupMembers(groupId: String, newMemberIds: List<String>) {
    val meta = host._state.value.settings.groups.firstOrNull { it.groupId == groupId } ?: run {
        host.warn(BannerMessage.GroupUnknown)
        return
    }
    val self = host._state.value.selfNodeId
    if (!meta.hasMember(self)) {
        host.warn(BannerMessage.GroupLeft)
        return
    }
    val toAdd = newMemberIds.map { it.trim() }.filter { it.isNotEmpty() && !meta.hasMember(it) }.distinct()
    if (toAdd.isEmpty()) return

    val updatedMeta = meta.copy(
        members = meta.members + toAdd,
        rev = meta.rev + 1,
        updatedBy = self,
        updatedTs = nowMillis(),
    )
    val settings = host._state.value.settings
    val updatedGroups = settings.groups.map { if (it.groupId == groupId) updatedMeta else it }
    host.handlePersist(settings.copy(groups = updatedGroups))
    host.core.groupNeeds.noteKnown(updatedMeta.groupId, updatedMeta.rev)
    handleNotice(ConvId.group(groupId), NoticeKind.GroupMembersChanged)
    host.info(BannerMessage.GroupMembersAdded(toAdd.size))
    host.publish()

    val rt = host.runtime ?: return
    val recipients = updatedMeta.recipientsExcluding(self)
    host.scope.launch {
        for (member in recipients) {
            runCatching { rt.sendGroupInfo(member, host.groupMetaWithNicks(updatedMeta)) }
        }
        host.refreshPeers()
    }
}


fun handleRemoveGroupMember(groupId: String, memberId: String) {
    val meta = host._state.value.settings.groups.firstOrNull { it.groupId == groupId } ?: run {
        host.warn(BannerMessage.GroupUnknown)
        return
    }
    val self = host._state.value.selfNodeId
    if (!meta.hasMember(self)) {
        host.warn(BannerMessage.GroupLeft)
        return
    }
    val isOwner = meta.ownerId == self || (meta.ownerId == null && meta.creatorId == self)
    val isAdmin = meta.adminIds?.contains(self) == true
    if (!isOwner && !isAdmin) {
        host.warn(BannerMessage.GroupNoPermission)
        return
    }
    if (memberId == meta.ownerId || (meta.ownerId == null && memberId == meta.creatorId)) {
        host.warn(BannerMessage.GroupNoPermission)
        return
    }
    if (!meta.hasMember(memberId)) return

    val oldMembers = meta.members
    val updatedMeta = meta.copy(
        members = meta.members.filter { it != memberId },
        adminIds = meta.adminIds?.filter { it != memberId },
        rev = meta.rev + 1,
        updatedBy = self,
        updatedTs = nowMillis(),
    )
    val settings = host._state.value.settings
    val updatedGroups = settings.groups.map { if (it.groupId == groupId) updatedMeta else it }
    host.handlePersist(settings.copy(groups = updatedGroups))
    host.core.groupNeeds.noteKnown(updatedMeta.groupId, updatedMeta.rev)
    handleNotice(ConvId.group(groupId), NoticeKind.GroupMembersChanged)
    host.info(BannerMessage.GroupMemberRemoved)
    host.publish()

    val rt = host.runtime ?: return
    val notifyList = oldMembers.filter { it != self }
    host.scope.launch {
        for (m in notifyList) {
            runCatching { rt.sendGroupInfo(m, host.groupMetaWithNicks(updatedMeta)) }
        }
        host.refreshPeers()
    }
}


fun handleLeaveGroup(groupId: String) {
    val meta = host._state.value.settings.groups.firstOrNull { it.groupId == groupId } ?: run {
        host.warn(BannerMessage.GroupUnknown)
        return
    }
    val self = host._state.value.selfNodeId
    if (!meta.hasMember(self)) {
        return
    }
    val oldMembers = meta.members
    val nextMembers = meta.members.filter { it != self }
    val updatedMeta = meta.copy(
        members = nextMembers,
        adminIds = meta.adminIds?.filter { it != self },
        ownerId = if (meta.ownerId == self) nextMembers.firstOrNull() else meta.ownerId,
        rev = meta.rev + 1,
        updatedBy = self,
        updatedTs = nowMillis(),
    )
    val settings = host._state.value.settings
    val updatedGroups = settings.groups.filter { it.groupId != groupId }
    host.handlePersist(settings.copy(groups = updatedGroups))
    host.core.groupNeeds.noteKnown(updatedMeta.groupId, updatedMeta.rev)
    handleNotice(ConvId.group(groupId), NoticeKind.GroupMembersChanged)
    host.info(BannerMessage.GroupLeft)
    host.publish()

    val rt = host.runtime ?: return
    val notifyList = oldMembers.filter { it != self }
    host.scope.launch {
        for (m in notifyList) {
            runCatching { rt.sendGroupInfo(m, host.groupMetaWithNicks(updatedMeta)) }
        }
        host.refreshPeers()
    }
}


fun handleUpdateGroupMeta(
    groupId: String,
    description: String?,
    announce: String?,
) {
    val meta = host._state.value.settings.groups.firstOrNull { it.groupId == groupId } ?: run {
        host.warn(BannerMessage.GroupUnknown)
        return
    }
    val self = host._state.value.selfNodeId
    if (!meta.hasMember(self)) {
        host.warn(BannerMessage.GroupLeft)
        return
    }
    val isOwner = meta.ownerId == self || (meta.ownerId == null && meta.creatorId == self)
    val isAdmin = meta.adminIds?.contains(self) == true
    if (!isOwner && !isAdmin) {
        host.warn(BannerMessage.GroupNoPermission)
        return
    }

    val trimmedDesc = description?.trim()?.take(Limits.GROUP_DESCRIPTION)
    val trimmedAnn = announce?.trim()?.take(Limits.GROUP_ANNOUNCE)

    val updatedMeta = meta.copy(
        description = trimmedDesc ?: meta.description,
        announce = trimmedAnn ?: meta.announce,
        rev = meta.rev + 1,
        updatedBy = self,
        updatedTs = nowMillis(),
    )
    val settings = host._state.value.settings
    val updatedGroups = settings.groups.map { if (it.groupId == groupId) updatedMeta else it }
    host.handlePersist(settings.copy(groups = updatedGroups))
    host.core.groupNeeds.noteKnown(updatedMeta.groupId, updatedMeta.rev)
    host.info(BannerMessage.GroupMetaUpdated)
    host.publish()

    val rt = host.runtime ?: return
    val recipients = updatedMeta.recipientsExcluding(self)
    host.scope.launch {
        for (m in recipients) {
            runCatching { rt.sendGroupInfo(m, host.groupMetaWithNicks(updatedMeta)) }
        }
        host.refreshPeers()
    }
}


fun handleSendToGroup(
    groupId: String,
    text: String,
    mentions: List<String>? = null,
    replyTo: String? = null,
) {
    val body = text.trim()
    if (body.isEmpty()) return
    val meta = host._state.value.settings.groups.firstOrNull { it.groupId == groupId }
    if (meta == null) {
        host.warn(BannerMessage.GroupUnknown)
        return
    }
    if (!meta.hasMember(host._state.value.selfNodeId)) {
        host.warn(BannerMessage.GroupLeft)
        return
    }
    val bytes = body.encodeToByteArray().size
    if (bytes > Protocol.TEXT_TCP_LIMIT) {
        host.warn(BannerMessage.MessageTooLong(bytes, Protocol.TEXT_TCP_LIMIT))
        return
    }
    val rt = host.runtime ?: run { host.warn(BannerMessage.NodeNotStarted); return }
    host.scope.launch {
        // 本地行 id **从一开始就等于信封 id**：重发要按原 id 重放（换 id = 对端多一条），
        // 重启时的排队对账也按信封 id 匹配历史行 —— 合成 id 会让这两件事永远对不上。
        val envelopeId = newId()
        val outcome = sendGroupTextOptimistic(groupId, body, replyTo, envelopeId) {
            rt.sendGroupText(
                text = body,
                groupId = groupId,
                groupRev = meta.rev,
                members = meta.members,
                mentions = mentions,
                replyTo = replyTo,
                envelopeId = envelopeId,
            )
        } ?: return@launch
        val repliedToIsAgent = replyTo != null && RelayController.isAgentAuthored(
            host._state.value.messages[ConvId.group(groupId)].orEmpty().firstOrNull { it.id == replyTo },
        )
        // 触发 ≠ 本机执行：远端 agent 在场时这条消息已经按群协议送达那台设备，
        // 本机不跑、也不代替它回话（见 [RelayController.agentPromptRoute]）。
        val routeFacts = host.agentRouteFactsOf(groupId)
        val route = RelayController.agentPromptRoute(
            text = body,
            groupId = groupId,
            isWorkspaceGroup = isWorkspaceGroup(groupId),
            repliedToIsAgent = repliedToIsAgent,
            localAgentPresent = routeFacts.localAgentPresent,
            remoteAgentPresent = routeFacts.remoteAgentPresent,
            agentKinds = routeFacts.agentKinds,
        )
        if (route == AgentPromptRoute.Local) {
            val prompt = RelayController.extractAgentPrompt(body, routeFacts.agentKinds)
            val self = host._state.value.selfNodeId
            host.scope.launch {
                host.onAgentPrompt?.invoke(ConvId.group(groupId), self, prompt, null)
            }
        }
    }
}


/**
 * 群文本的乐观插入：**先让气泡出现**，再等发送路径返回，然后按临时 id 就地改写。
 *
 * 与私聊那条路（见 `RelayControllerMessagesComponent.handleSend`）同形，理由也同一条：
 * 群扇出最坏要等一个慢成员的退避 + TCP 兜底，那段时间界面原本**什么都不显示** ——
 * 用户以为没点上而重复发送。先插一个 [DeliveryState.Sending] 气泡，结果回来后就地改写它，
 * 全程只有一条。
 *
 * ## 本地 id 必须**等于信封 id**
 *
 * 以前这里用 `group-local-…` 合成 id。代价是「重发」只能按合成 id 重建信封 —— 对端按
 * `(id,from)` 去重，换一个 id 就是**又一条消息**（真机桌面库读数：同一句话出现
 * `f93a27cf…` 与 `0137bff5…` 两条）；启动时的排队对账（按信封 id 匹配历史行）也永远对不上。
 * 现在调用方先生成信封 id（[envelopeId]）传进来，本地行从第一帧起就用它。
 *
 * ## 为什么终态仍恒为 [DeliveryState.Sent]（不按 [SendOutcome] 折 Queued）
 *
 * 群扇出**没有逐成员回执**：`QUEUED` 只表示「至少有一个成员的那一份还在队列里」，
 * 而另一些成员可能已经 ACK。把它折成「未送达」会让已经收到的人被标错；而重发入口
 * 只挂在 [DeliveryState.Failed] 上，群消息不会因为排队变成 Failed。逐成员回执要改协议，
 * 不在这条修复的范围里。
 *
 * @param envelopeId 这次发送的信封 id；null = 没有信封的场景（预览/单测），退回本地 id。
 * @param send 真正的发送动作（生产路径传 `rt.sendGroupText` 的调用）。抽成形参是为了让
 *   「先插后等」这个**时序**能被闸门式测试钉住，而不是只能断言终态。
 * @return 发送路径的结论；抛异常时为 null（那条气泡已收尾成 [DeliveryState.Failed]）。
 */

internal suspend fun sendGroupTextOptimistic(
    groupId: String,
    body: String,
    replyTo: String?,
    envelopeId: String? = null,
    /**
     * 兜底硬超时（毫秒）。默认 [SEND_HARD_TIMEOUT_MS]。
     *
     * 做成形参而不是直接读常量，是为了让「挂死必须折成失败」这条能用**毫秒级**验证 ——
     * 用真超时测要等 60 秒，那种用例没人会在本地跑，等于没有。
     */
    hardTimeoutMs: Long = SEND_HARD_TIMEOUT_MS,
    send: suspend () -> SendOutcome,
): SendOutcome? {
    val convId = ConvId.group(groupId)
    val optimistic = MessageUi(
        id = envelopeId ?: host.localMessageId(),
        peerId = convId,
        mine = true,
        text = body,
        at = nowMillis(),
        delivery = DeliveryState.Sending,
        senderId = host._state.value.selfNodeId,
        replyTo = replyTo,
    )
    host.handleAppendMessage(optimistic)
    // **兜底硬超时**：`runCatching` 只接异常，**接不住「永不返回」。**
    //
    // 真机现场：一条群消息停在 `Sending` 转了 5 分半仍是 `Sending`；
    // 而 `DeliveryState.Sending` 的 KDoc 承诺过「不会永远转下去」，且它**没有重发出口**
    // （只有 `Failed` 才挂重发）。所以「永远转」既欺骗又不可恢复。
    //
    // 值取 [GROUP_FANOUT_BUDGET_MS] 的 6 倍：内层 10 秒预算到点会把未完成的记成
    // `QUEUED` 正常返回，所以**正常路径用不到这条兜底**；能走到这里的只有真正的挂死。
    // 给足 60 秒是为了不误伤慢网（UDP 退避 1/2/4s 再逐端点试 TCP 各 7s，
    // 跨网最坏在 40 秒量级），一旦超时就走下面的 `outcome == null` 分支收尾成失败。
    val outcome = runCatching {
        withTimeoutOrNull(hardTimeoutMs) { send() }
    }
        .onFailure {
            host.warn(
                it.messageOrNull()?.let(BannerMessage::SendFailed) ?: BannerMessage.UnknownError,
            )
        }
        .getOrNull()
    if (outcome == null) {
        // 发送路径自己抛了：**必须**把那个「发送中」收尾成失败，否则它会永远转下去
        // （那是比「不显示气泡」更糟的谎）。
        host.replaceMessage(convId, optimistic.id, optimistic.copy(delivery = DeliveryState.Failed))
        return null
    }
    // 按**同一个 id** 就地改写终态：id 从插入那一刻就等于信封 id，不需要再换。
    host.replaceMessage(convId, optimistic.id, optimistic.copy(delivery = DeliveryState.Sent))
    return outcome
}


fun handleOnGroupInfo(envelope: Envelope, incoming: GroupMeta) {
    val self = host._state.value.selfNodeId
    val local = host._state.value.settings.groups.firstOrNull { it.groupId == incoming.groupId }
    when (val decision = GroupMerge.decide(local, incoming, self)) {
        is GroupMergeDecision.Apply -> handleApplyGroupMeta(local, decision.meta, envelope.from)

        is GroupMergeDecision.Removed -> {
            handleApplyGroupMeta(local, decision.meta, envelope.from)
            handleNotice(ConvId.group(decision.meta.groupId), NoticeKind.RemovedFromGroup)
        }

        is GroupMergeDecision.Reject -> {
            // **可观测**：群元数据被拒时把原因说出来。
            //
            // 这条链路以前是静默的 —— 真机上「手机上看不到 agent」排查了几轮都没结论，
            // 因为「帧被拒」与「帧没到」在外部看起来完全一样（本地 virtualAgents 都是空）。
            // 一行日志就能把这两种情况分开。
            // 拒绝原因不丢：用 noteRequested 的同一份台账之外的路径记不下来（没有
            // 可用的日志通道），所以这里只保留判据本身；排查时看 GroupMerge 的 why。
            host.core.groupNeeds.noteRequested(incoming.groupId, maxOf(incoming.rev, local?.rev ?: 0))
        }
    }
}


fun handleApplyGroupMeta(local: GroupMeta?, next: GroupMeta, from: String) {
    val convId = ConvId.group(next.groupId)
    val settings = host._state.value.settings
    val replaced = if (local == null) settings.groups + next
    else settings.groups.map { if (it.groupId == next.groupId) next else it }
    host.handlePersist(settings.copy(groups = replaced))
    host.core.groupNeeds.noteKnown(next.groupId, next.rev)
    if (local != null) {
        val kind = if (local.name != next.name) NoticeKind.GroupRenamed
        else NoticeKind.GroupMembersChanged
        handleNotice(convId, kind)
    }
    host.refreshPeers()
    if (from.isEmpty()) return
}


fun handleOnGroupNeed(envelope: Envelope, groupId: String) {
    val meta = host._state.value.settings.groups.firstOrNull { it.groupId == groupId } ?: return
    if (!meta.hasMember(host._state.value.selfNodeId)) return
    val rt = host.runtime ?: return
    host.scope.launch { runCatching { rt.sendGroupInfoReply(envelope.from, meta) } }
}


fun handleSyncGroupMetaIfNeeded(rt: NodeRuntime, envelope: Envelope) {
    val payload = envelope.payload as? JsonObject ?: return
    val groupId = (payload["groupId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: return
    // groupRev 在协议里是**数字**：`Codec.intOf` 对字符串一律返回 null，
    // 群文本消息的载荷校验（`CodecMsg.validateMsg` → `GROUP_TEXT_KEYS` 里的 `groupRev`）
    // 就是用它判的，发送侧也写数字（`Codec.groupTextPayload(groupRev = meta.rev)`）。
    // 这里原来写成 `takeIf { it.isString }` ⇒ 真机上的数字 rev 一律读成 0 ⇒
    // `beginRequest(groupId, 0)` 在游标已经登记过本地 rev 后恒为假 ⇒ **need 索取这条路径
    // 在实机上从来没跑过**。2026-10-10 group 夹具 `group-need-on-newer-rev` 逮到它：
    // 手机 ACK 了 rev=2 的群消息（本地 rev=1）却不回 need。
    val rev = Codec.intOf(payload["groupRev"])?.toInt() ?: 0
    // beginRequest = 「该不该问」+「问过就记」，成对做。只判不记的话，对端没回 host.info 时
    // 游标停在原地，下一条同 rev 的群消息会再问一次（见 GroupNeedTracker.beginRequest）。
    if (!host.core.groupNeeds.beginRequest(groupId, rev)) return
    host.scope.launch { runCatching { rt.sendGroupNeed(envelope.from, groupId) } }
}


fun handleOnGroupControl(envelope: Envelope) {
    val payload = envelope.payload as? JsonObject ?: return
    val op = (payload["op"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return
    when (op) {
        GroupOps.INFO -> Codec.decodeGroupInfo(envelope.payload)?.let { handleOnGroupInfo(envelope, it) }
        GroupOps.NEED -> Codec.decodeGroupNeed(envelope.payload)?.let { handleOnGroupNeed(envelope, it) }
        else -> Unit
    }
}




fun handleNotice(convId: String, kind: NoticeKind) {
    host.handleAppendMessage(
        MessageUi(
            id = host.localMessageId(),
            peerId = convId,
            mine = false,
            text = "",
            at = nowMillis(),
            notice = kind,
        ),
    )
}
}

/**
 * 触发路由要的**群内 agent 事实**：有哪些 agent kind、agent 在本机还是远端。
 *
 * - `localAgentPresent` 要求**本机真的挂了执行器**（`RelayController.onAgentPrompt` 非空）
 *   且本机是这个群的 agent 宿主 —— 只凭"群里有 agent"就在本机跑，正是「@dsh 收到本地
 *   mock」那条路；
 * - `remoteAgentPresent` 只看**群成员里的 agent 宿主**，不看消息发送者：本机 agent 的
 *   历史回复（`agent:native`）也在消息里，拿它判"远端在场"会让本机永久让路。
 */
internal class GroupAgentRouteFacts(
    val agentKinds: Set<String>,
    val localAgentPresent: Boolean,
    val remoteAgentPresent: Boolean,
)

internal fun RelayController.agentRouteFactsOf(groupId: String): GroupAgentRouteFacts {
    val st = state.value
    val ctx = st.senderContext(ConvId.group(groupId))
    val hosts = agentHostsInGroup(ctx)
    val self = st.selfNodeId
    return GroupAgentRouteFacts(
        agentKinds = agentKindsInGroup(ctx),
        localAgentPresent = onAgentPrompt != null && hosts.contains(self),
        remoteAgentPresent = hosts.any { it != self },
    )
}


/**
 * 把**本机的一个 workspace** 暴露/收起进这个群（`virtualAgents` 的成员入口）。
 *
 * 为什么单独一个方法而不是复用 `updateGroupMeta`：那条路的语义是「改简介/公告」，
 * 而「这个群用不用我机器上这个项目」是另一件事 —— 混在一起会让权限判断、rev 递增
 * 与提示文案都说不清。
 *
 * @param wsId 要暴露的 workspace id；传 null 表示**收起**（移除本机这一条声明）。
 * @param agentId 该 workspace 上的 agent 身份；null 时按本机 workspace 表查。
 */
fun RelayController.handleExposeWorkspace(groupId: String, wsId: String?, agentId: String?) {
    val meta = _state.value.settings.groups.firstOrNull { it.groupId == groupId } ?: return
    val self = _state.value.selfNodeId
    // 暴露自己的机器是**本机主权**，不需要群主/管理员身份；但要能改元数据就得在群里。
    if (self !in meta.members) return
    val current = meta.virtualAgents.orEmpty()
    val next = if (wsId == null) {
        // 收起：只移除「本机 + 这个 ws」那一条，别的设备与别的 ws 原样保留。
        current.filterNot { it.hostNodeId == self && it.wsId == agentId }
    } else {
        val ws = _state.value.agentWorkspaces.values
            .firstOrNull { it.peerId == self && it.wsId == wsId } ?: return
        // 身份键的兜底规则抽在 core 里（`resolveVirtualAgentKey`），这里只消费：
        // 显式传入 > 工作区自报 agentId > agentKind。真机数据里工作区的 agentId 常为空，
        // 原先那条「为空就静默放弃」让入驻按钮看起来没用。
        val resolvedAgentId = resolveVirtualAgentKey(agentId, ws.agentId, ws.agentKind)
        if (resolvedAgentId.isNullOrBlank()) return
        val base = AgentMemberName.base(ws.agentKind, resolvedAgentId, ws.wsTitle, wsId)
        // 名字冲突范围 = 群内其它声明（不含本机这一条，它会被替换）。
        val taken = current
            .filterNot { it.hostNodeId == self && it.wsId == wsId }
            .map { it.name }
            .toMutableSet()
        val name = AgentMemberName.unique(base, taken)
        current.filterNot { it.hostNodeId == self && it.wsId == wsId } +
            VirtualAgentMember(agentId = resolvedAgentId, hostNodeId = self, name = name, wsId = wsId)
    }
    val updated = meta.copy(
        virtualAgents = next.ifEmpty { null },
        rev = meta.rev + 1,
        updatedBy = self,
        updatedTs = nowMillis(),
    )
    handlePersist(
        _state.value.settings.copy(
            groups = _state.value.settings.groups.map { if (it.groupId == groupId) updated else it },
        ),
    )
    // 广播新元数据给所有成员（复用既有发送路径，它会补 memberNicks）。
    // 先在扩展接收者上算好带昵称的那份，再进 launch：lambda 里的 this 不是它。
    val broadcast = groupMetaWithNicks(updated)
    scope.launch {
        for (m in updated.members.filter { it != self }) {
            runCatching { runtime?.sendGroupInfo(m, broadcast) }
        }
    }
    publish()
}

/**
 * 发群元数据时**带上本机已知的成员昵称**（`GroupMeta.memberNicks`）。
 *
 * ## 为什么在发送侧补，而不是在元数据生成时写死
 *
 * 昵称不是群的属性，是**本机知识**：同一份 `GroupMeta` 在不同设备上能填出来的成员昵称不一样
 * （谁认识谁取决于各自的发现历史）。写进群元数据并随 `group.info` 同步，正是为了让**跨网、
 * 发现不到对方的设备**也能显示出真名 —— 真机现场（红米走蜂窝）此前只能显示 `f6a7b8c9`
 * 这样的 nodeId 前 8 位，而桌面端四个成员都正常。
 *
 * 这里只填**本机确实知道**的那些；不知道的不编（对方会用自己的知识补，或者退回 nodeId —— 
 * 那是诚实的降级，不是谎）。自己一定在 `members` 里，所以自己的昵称也带上。
 */
internal fun RelayController.groupMetaWithNicks(meta: GroupMeta): GroupMeta {
    val state = _state.value
    val known = buildMap {
        put(state.selfNodeId, state.settings.nick.ifBlank { "" })
        for (peer in state.peers) put(peer.nodeId, peer.displayName)
    }
    val nicks = meta.members.mapNotNull { id ->
        val nick = known[id]?.trim().orEmpty()
        if (nick.isEmpty()) null else id to nick
    }.toMap()
    // agent 的名字来源（PPE-2 的 `virtualAgents`）：这里是**唯一**的写入点。
    //
    // 之前这个字段只有读、没有写 —— 于是 `SenderIdentity` 里那条「显示声明名」的分支
    // 永远取不到值，agent 只能显示成标识符（`dsh` / `native`），用户看到的就是
    // 「一群机器人名字都是驱动类型」。名字只能由**宿主**知道（它才知道自己跑着什么），
    // 所以和昵称一样在发送侧补、随 group.info 同步给其它设备。
    val agents = virtualAgentsFor(meta)
    // **`agents == null` 必须保持原值，不能 copy 成 null。**
    //
    // 真机事故：`virtualAgentsFor` 的 KDoc 写着「返回 null 表示本机没有要声明的，
    // 调用方据此保持 `meta.virtualAgents` 原样（不覆盖别的设备已经声明进来的）」，
    // 而这里无条件 `copy(virtualAgents = agents)` —— 于是**入驻声明被这一行抹掉**。
    //
    // 现场：桌面端 `settings.json` 里明明有 `[dsh-teahouse-mobile]`，但队列里的
    // `group.info` 帧 rev=7 却没有 `virtualAgents` 字段；手机端收到后群里 0 个 agent，
    // 列表空、@ 候选空、气泡署名退回 kind（`dsh`）。用户问「是不是清除数据就好了」——
    // 不是，发出去的帧本身就是坏的，清完重收还是同样缺字段的帧。
    //
    // 两种 null 是不同的事实：`agents != null` = 本机有要声明的（覆盖）；
    // `agents == null` = 本机没有（**不动**，别人的声明还在里面）。
    //
    // 判据本体在 core 的 [withLocalExtras]：这里不再重写一遍。它必须能被单独测 ——
    // 写在这里时只能靠真机撞见，而它已经撞见过两次（昵称空、localAgents 空）。
    return meta.withLocalExtras(nicks, agents)
}

/**
 * 本机为群里这些 agent 声明的身份（`virtualAgents`）。
 *
 * 只声明**本机真的在跑**的那些：`agentWorkspaces` 里属于本机 peer 的工作区。
 * 名字优先级 `wsTitle`（宿主给工作区起的名字）> `agentId`；两者都没有就不声明 ——
 * 编一个名字比显示标识符更糟。
 *
 * 返回 null 表示「本机没有要声明的」，调用方据此保持 `meta.virtualAgents` 原样
 * （不覆盖别的设备已经声明进来的）。
 */
private fun RelayController.virtualAgentsFor(meta: GroupMeta): List<VirtualAgentMember>? {
    val state = _state.value
    val self = state.selfNodeId
    // **按 (agentId, wsId) 逐条声明**：一个 agent 在 15 个项目目录里，就是 15 个可被 @ 的
    // 对象（真机数据：`dsh-029bbbf3` 有 15 个 ws）。以前 `distinctBy { agentId }` 把它们
    // 压成一条，于是用户只能 @ 到一个"没有具体项目"的 agent。
    //
    // 名字走 AgentMemberName：默认 `<agent>-<workspace>`，群内不冲突（后面再加的自动加序号）。
    val candidates = state.agentWorkspaces.values
        .filter { it.peerId == self }
        .mapNotNull { ws ->
            // **必须要求 `agentId` 非空 —— 不能用 agentKind 兜底。**
            //
            // 一度改成 `resolveVirtualAgentKey(ws.agentId, ws.agentId, ws.agentKind)`，想让
            // 「agentId 空、agentKind 有值」的工作区也能被声明。真机立刻暴露：本机 25 个
            // 工作区的 agentKind 都是 `DSH`，于是**一条入驻声明被展开成 25 条**（`va=25`），
            // 每个工作区各生成一个名字。
            //
            // `virtualAgents` 装的是**用户显式入驻**的那几条（`handleExposeWorkspace` 写入），
            // 不是「本机所有工作区清单」。这里补全它的职责是「把已声明的那几条的最新状态
            // 带上」，不是替用户去入驻。
            val agentId = ws.agentId?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val wsId = ws.wsId.trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            agentId to wsId to ws
        }
        // 按 **(agentId, wsId)** 去重，不是只按 agentId ——
        // 一个 agent 在多个项目目录里就是多个可 @ 的对象（KDoc 上面那段）。
        .distinctBy { it.first }
    if (candidates.isEmpty()) return null

    // 名字冲突的判定范围 = **本群已声明的所有名字**（含别的设备），因为 @ 是群内语法。
    val taken = meta.virtualAgents.orEmpty().map { it.name }.toMutableSet()
    val mine = candidates.map { (key, ws) ->
        val (agentId, wsId) = key
        val base = AgentMemberName.base(ws.agentKind, agentId, ws.wsTitle, wsId)
        val name = AgentMemberName.unique(base, taken)
        taken += name
        VirtualAgentMember(
            agentId = agentId,
            hostNodeId = self,
            name = name,
            wsId = wsId,
        )
    }
    // 合并：本机的声明覆盖**同一 (agentId, wsId)** 的旧声明，其它设备的原样保留。
    val kept = meta.virtualAgents.orEmpty().filterNot { va ->
        va.hostNodeId == self && mine.any { it.agentId == va.agentId && it.wsId == va.wsId }
    }
    return kept + mine
}

/*
 * 群消息发送的**兜底硬超时**。
 *
 * 内层 fan-out 有 10 秒预算（`GROUP_FANOUT_BUDGET_MS`），到点会把未完成的成员记成
 * `QUEUED` 并正常返回 —— 所以正常路径**根本用不到**这条超时。
 *
 * 它是给「send() 因为某个 await 永不完成而挂死」这种形态准备的：那种情况下
 * `runCatching` 接不住（它只处理异常），消息会永远停在 `Sending`，
 * 而 `Sending` 在界面上**没有重发出口**（只有 `Failed` 才挂）。
 *
 * 取 60 秒：UDP 退避 1/2/4s 再逐端点试 TCP 各 7s，跨网最坏在 40 秒量级，
 * 留出余量以免误伤慢网。
 */
internal const val SEND_HARD_TIMEOUT_MS: Long = 60_000L

/**
 * **补发台账：（群 × 对端）→ 已经发到哪个 rev。**
 *
 * ## 为什么需要它（真机事故）
 *
 * 第一版 `rebroadcastGroupMeta` 挂在「在线对端集合」的状态流上，没有任何去重，于是
 * 形成了**自我触发的循环**：补发 → 队列变化 → 对端 `lastSeen` 抖动 → 在线集合变 →
 * 又补发。真机现场：每 15 秒一轮，队列瞬间涨到 286 条 `group.info` 帧，全是同一个 rev。
 *
 * 而那个抖动是真实存在的：`v1/peers` 连查三次，在线集合在
 * `[A,B]` 与 `[A,B,C,D]` 之间来回跳 —— `distinctUntilChanged()` 因此每次放行。
 *
 * ## 判据
 *
 * 记 `(nodeId, groupId) → 已发送的 rev`。同一个 rev 对同一个对端**只发一次**：
 *  · 对端上线 → 补一次 ✅
 *  · 状态抖动（同 rev）→ 已记过 → 跳过 ✅
 *  · 群元数据真的变了（rev 更大）→ 重新补 ✅
 *
 * 与 `GroupNeedTracker` 的「问过就记」是同一条思路，只是方向相反（那边记「我问过谁」，
 * 这边记「我发给谁」）。
 *
 * 只活在内存里：重启后重新补一次是**正确**的（对端确实可能错过了），代价是每端一帧。
 */
private val groupMetaSentRev = mutableMapOf<Pair<String, String>, Int>()

/**
 * 与 [groupMetaSentRev] 配套：**上次发出去的那份带不带 virtualAgents**。
 *
 * 只按 rev 去重时，「发过一次 rev 7」就把这个对端永久标记成已发 —— 即使那次发出去的是
 * **修复前编出来的、缺 virtualAgents 的那一份**。真机现场：红米收到过 100 条 group.info，
 * 一条都不带 agent 声明；而补发台账坚持「rev 7 已经发过了」，不再补。
 *
 * 同一个 rev 的内容**可以升过级**。判据要带上这一位，内容升级就能再发一次。
 */
private val groupMetaSentHadAgents = mutableMapOf<Pair<String, String>, Boolean>()

/**
 * **同一个 rev 对同一个成员只补发一次。**
 *
 * 抽成纯函数是为了能被单测钉住 —— 这条判据是「补发不会变成无限循环」的**唯一**保障。
 * 它一旦破掉，症状是每 15 秒一轮、队列在几分钟内涨到几百帧（真机现场 286 条），
 * 而只看代码是看不出来的。
 *
 * @param alreadySent 台账查询；默认读进程级台账，测试传自己的实现。
 * @return 本轮真正该发的成员。
 */
internal fun freshRecipients(
    targets: List<String>,
    groupId: String,
    rev: Int,
    /** 这一次要发的那份**带不带** agent 声明。 */
    hasAgents: Boolean,
    alreadySent: (String) -> Int? = { member -> groupMetaSentRev[member to groupId] },
    /** 上次发出那份带不带 agent 声明；缺省 false（修复前发出的都不带）。 */
    alreadySentHadAgents: (String) -> Boolean? = { member -> groupMetaSentHadAgents[member to groupId] },
): List<String> = targets.filter { member ->
    val sameRev = alreadySent(member) == rev
    val sameAgents = (alreadySentHadAgents(member) ?: false) == hasAgents
    !(sameRev && sameAgents)
}
/**
 * **把本机已知的群元数据发给需要的成员。**
 *
 * 与变更时那次广播的区别：这里带**去重**，可以安全地挂在「对端上线」这类频繁触发点上。
 *
 * 触发点见 `DesktopExtension` 的补发协程（启动 / 对端上线 / 换网）。
 */
fun RelayController.rebroadcastGroupMeta() {
    val st = _state.value
    val self = st.selfNodeId
    val runtime = runtime ?: return
    // **不按「本机认为它在线」筛目标。**
    //
    // 第一版写成 filter { it.online }，理由看似无懈可击：「离线对端的帧会进队列，
    // 队列会在它回来时补发，这里再发一份就是重复」。**那个理由不成立** —— 补发是在
    // 判定它离线的那一刻执行的，帧根本没排进队列，所以它回来时队列里没有这一条：
    // 离线对端永远拿不到。
    //
    // 真机现场（Pixel）：它自己的节点表显示本机在线，而本机 /v1/peers 说它 online=false
    // —— 单向在线。按 online 筛就把一个活着的、能收包的对端排除在外。
    //
    // 去重靠的是 (对端, 群) -> 已发 rev 台账，不是在线判定。
    for (group in st.settings.groups) {
        // 只发自己确实在里面的群：不在群里却有它的元数据（被移出后残留）不该继续外发。
        if (self !in group.members) continue
        val targets = group.members.filter { it != self }
        if (targets.isEmpty()) continue
        val payload = groupMetaWithNicks(group)
        val rev = group.rev
        val hasAgents = !payload.virtualAgents.isNullOrEmpty()
        val fresh = freshRecipients(targets, group.groupId, rev, hasAgents)
        if (fresh.isEmpty()) continue
        // 先记账再发：发失败也算「试过了」，否则一次失败会让它每轮重试 —— 那正是循环的入口。
        for (member in fresh) {
            groupMetaSentRev[member to group.groupId] = rev
            groupMetaSentHadAgents[member to group.groupId] = hasAgents
        }
        scope.launch {
            for (member in fresh) {
                runCatching { runtime.sendGroupInfo(member, payload) }
            }
        }
    }
}
