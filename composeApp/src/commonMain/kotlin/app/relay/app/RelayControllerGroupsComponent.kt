// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import app.relay.app.model.DeliveryState
import app.relay.app.model.MessageUi
import app.relay.app.model.NoticeKind
import app.relay.core.nowMillis
import app.relay.core.protocol.Codec
import app.relay.core.protocol.ConvId
import app.relay.core.protocol.Envelope
import app.relay.core.protocol.GroupMerge
import app.relay.core.protocol.GroupMergeDecision
import app.relay.core.protocol.GroupMeta
import app.relay.core.protocol.GroupOps
import app.relay.core.protocol.Limits
import app.relay.core.protocol.Protocol
import app.relay.core.protocol.newId
import app.relay.core.session.NodeRuntime
import app.relay.core.transport.SendOutcome
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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

/**
 * [RelayController] 的群组域处理扩展。
 *
 * 覆盖建群、邀请/移出成员、群文本发送、GroupNeed/GroupInfo 协议协商与同步。
 */

fun handleCreateGroup(name: String, memberIds: List<String>): String? {
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
    val meta = GroupMeta.create(
        groupId = newId(),
        name = trimmed,
        members = others,
        selfId = self,
        updatedTs = nowMillis(),
    )
    host.handlePersist(host._state.value.settings.copy(groups = host._state.value.settings.groups + meta))
    // 建群这条路径也要把 rev 登记进 need 游标：我们**刚刚**有这个群的元数据了。
    // 漏了它会怎样？建完群收到的第一条同 rev 群消息会回一发 `op:need` —— 去问一份
    // 自己手上就有的成员表。对端若还回 host.info，就是白跑一个来回；若不回，游标一直不前进，
    // 则**每条**群消息都带上这一问（对端在线却从不回 host.info 的情形）。
    // 另外三条写元数据的路径（加人/移人/收到 host.info）都登记了，只有这里是漏的。
    host.core.groupNeeds.noteKnown(meta.groupId, meta.rev)
    val rt = host.runtime
    if (rt == null) {
        host.info(BannerMessage.GroupCreated(meta.name, others.size))
        host.publish()
        return meta.groupId
    }
    host.scope.launch {
        for (member in others) {
            runCatching { rt.sendGroupInfo(member, meta) }
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
            runCatching { rt.sendGroupInfo(member, updatedMeta) }
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
            runCatching { rt.sendGroupInfo(m, updatedMeta) }
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
            runCatching { rt.sendGroupInfo(m, updatedMeta) }
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
            runCatching { rt.sendGroupInfo(m, updatedMeta) }
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
        val outcome = runCatching {
            rt.sendGroupText(
                text = body,
                groupId = groupId,
                groupRev = meta.rev,
                members = meta.members,
                mentions = mentions,
                replyTo = replyTo,
            )
        }.onFailure {
            host.warn(
                it.messageOrNull()?.let(BannerMessage::SendFailed) ?: BannerMessage.UnknownError,
            )
        }.getOrNull() ?: return@launch
        host.handleAppendMessage(
            MessageUi(
                id = handleGroupMessageId(outcome, body),
                peerId = ConvId.group(groupId),
                mine = true,
                text = body,
                at = nowMillis(),
                delivery = DeliveryState.Sent,
                senderId = host._state.value.selfNodeId,
                replyTo = replyTo,
            ),
        )
    }
}


fun handleGroupMessageId(outcome: SendOutcome, body: String): String =
    "group-${host.localMessageId()}-${outcome.name}-${body.length}"


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
