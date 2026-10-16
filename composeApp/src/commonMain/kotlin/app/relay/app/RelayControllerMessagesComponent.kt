// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import app.relay.app.RelayController.Companion.CHAT_INBOUND_TYPES
import app.relay.app.RelayController.Companion.groupIdOf
import app.relay.app.RelayController.Companion.isGroupText
import app.relay.app.RelayController.Companion.mentionsSelf
import app.relay.app.RelayController.Companion.replyToOf
import app.relay.app.RelayController.Companion.textOf
import app.relay.app.model.ConversationUi
import app.relay.app.model.DeliveryState
import app.relay.app.model.MessageUi
import app.relay.app.model.NoticeKind
import app.relay.app.model.parseThinkingContent
import kotlinx.serialization.json.JsonObject
import app.relay.app.model.PeerUi
import app.relay.app.model.TransferState
import app.relay.core.ioDispatcher
import app.relay.core.nowMillis
import app.relay.core.protocol.ConvId
import app.relay.core.protocol.Envelope
import app.relay.core.protocol.MsgKinds
import app.relay.core.protocol.MsgTypes
import app.relay.core.protocol.Protocol
import app.relay.core.session.NodeRuntime
import app.relay.core.transport.PrunedMessage
import app.relay.core.transport.SendOutcome
import app.relay.generated.resources.Res
import app.relay.generated.resources.chat_nudge_peer
import app.relay.platform.localHourOfDay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString

/**
 * 消息本体：入站分发、撤回、补发、落盘，以及那张被崩溃事故光顾过的 threads 表。
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
internal class RelayControllerMessagesComponent(
    private val host: RelayController,
) {

/**
 * [RelayController] 的消息与会话域扩展。
 *
 * 覆盖消息收发、去重、撤回、戳一碰、队列重放与磁盘持久化。
 */

fun handleSend(peerId: String, text: String) {
    val body = text.trim()
    if (body.isEmpty()) return
    val bytes = body.encodeToByteArray().size
    if (bytes > Protocol.TEXT_TCP_LIMIT) {
        host.warn(BannerMessage.MessageTooLong(bytes, Protocol.TEXT_TCP_LIMIT))
        return
    }

    val remoteConfig = host._state.value.settings.remoteRelay
    val isRemotePeer = remoteConfig != null && remoteConfig.enabled &&
        (remoteConfig.nodeId == peerId || (host.runtime?.peerList()?.none { it.profile.nodeId == peerId } == true))

    val rt = host.runtime
    if (rt == null || (isRemotePeer && rt.peerList().none { it.profile.nodeId == peerId })) {
        if (remoteConfig != null && remoteConfig.enabled) {
            val client = host.remoteRelayClient ?: RemoteRelayClient(remoteConfig, host.platform.httpClient).also {
                host.remoteRelayClient = it
            }
            host.scope.launch {
                val id = host.localMessageId()
                val success = client.sendMessage(peerId, body)
                host.handleAppendMessage(
                    MessageUi(
                        id = id,
                        peerId = peerId,
                        mine = true,
                        text = body,
                        at = nowMillis(),
                        delivery = if (success) DeliveryState.Sent else DeliveryState.Failed,
                    ),
                )
                if (!success) {
                    host.warn(BannerMessage.SendFailed("Tidings send failed"))
                }
            }
            return
        }
        host.warn(BannerMessage.NodeNotStarted)
        return
    }
    host.scope.launch {
        val outcome = runCatching { rt.sendTextWithEnvelope(peerId, body) }
            .onFailure {
                host.warn(
                    it.messageOrNull()?.let(BannerMessage::SendFailed) ?: BannerMessage.UnknownError,
                )
            }
            .getOrNull() ?: return@launch
        val (parsedReasoning, cleanedText) = parseThinkingContent(body)
        host.handleAppendMessage(
            MessageUi(
                id = outcome.envelopeId,
                peerId = peerId,
                mine = true,
                text = cleanedText,
                at = nowMillis(),
                delivery = if (outcome.outcome == SendOutcome.SENT) DeliveryState.Sent else DeliveryState.Queued,
                reasoning = parsedReasoning,
            ),
        )
    }
}


fun handleRecall(message: MessageUi) {
    val rt = host.runtime ?: run { host.warn(BannerMessage.NodeNotStarted); return }
    if (!canRecall(message, nowMillis())) return
    val targetId = recallTargetIdOf(message) ?: return
    val groupId = ConvId.groupIdOf(message.peerId)
    val inFlightTransfers = if (groupId != null && message.file != null) {
        val msgId = message.file.msgId
        val recorded = if (msgId != null) host.core.groupTransferIds[msgId] else null
        recorded ?: listOfNotNull(message.file.takeIf { it.state != TransferState.Completed }?.transferId)
    } else {
        listOfNotNull(message.file?.takeIf { it.state != TransferState.Completed }?.transferId)
    }

    host.scope.launch {
        for (tId in inFlightTransfers) {
            runCatching { rt.cancelTransfer(tId) }
        }
        if (groupId != null) {
            val meta = host._state.value.settings.groups.firstOrNull { it.groupId == groupId }
            if (meta != null) {
                val others = meta.members.filter { it != host._state.value.selfNodeId }
                for (member in others) {
                    runCatching {
                        rt.sendRecall(
                            peerId = member,
                            targetId = targetId,
                            groupId = meta.groupId,
                            groupRev = meta.rev,
                        )
                    }
                }
            }
        } else {
            runCatching { rt.sendRecall(message.peerId, targetId) }
                .onFailure {
                    host.warn(it.messageOrNull()?.let(BannerMessage::SendFailed) ?: BannerMessage.UnknownError)
                    return@launch
                }
        }
        host.handleReplaceMessage(
            message.peerId,
            message.copy(
                text = "",
                file = null,
                notice = NoticeKind.RecalledBySelf,
                delivery = DeliveryState.Recalled,
            ),
        )
    }
}


fun handleNudge(peerId: String) {
    val rt = host.runtime ?: run { host.warn(BannerMessage.NodeNotStarted); return }
    if (!host.core.nudgeThrottle.allow(peerId, nowMillis())) {
        host.warn(BannerMessage.NudgeThrottled)
        return
    }
    host.scope.launch {
        val delivered = runCatching { rt.sendNudge(peerId) }.getOrDefault(false)
        if (!delivered) {
            host.warn(BannerMessage.NudgeFailed)
            return@launch
        }
        host.handleAppendMessage(
            MessageUi(
                id = host.localMessageId(),
                peerId = peerId,
                mine = true,
                text = "",
                at = nowMillis(),
                notice = NoticeKind.NudgedBySelf,
            ),
        )
    }
}


fun handleClearMessages(peerId: String) {
    host.stateLock.withLock {
        host.core.threads.remove(peerId)
        host.core.unread.remove(peerId)
    }
    host.publish()
    host.handlePersistThreads()
}


fun handleClearAllMessages() {
    host.stateLock.withLock {
        host.core.threads.clear()
        host.core.unread.clear()
    }
    host.publish()
    if (!host.persistMessages) return
    val target = host.messageStore
    host.scope.launch {
        host.persistMutex.withLock {
            withContext(ioDispatcher) { runCatching { target.clear() } }
        }
    }
}


fun handleOnInbound(envelope: Envelope) {
    if (envelope.type == MsgTypes.AGENT_SESSION) {
        host.agentCtl.handleOnAgentSession(envelope)
        return
    }
    if (envelope.type == MsgTypes.AGENT_EVENT) {
        host.agentCtl.handleOnAgentEvent(envelope)
        return
    }
    if (envelope.type == MsgTypes.AGENT_APPROVAL) {
        host.agentCtl.handleOnAgentApproval(envelope)
        return
    }
    if (envelope.type == MsgTypes.AGENT_ACK) {
        host.agentCtl.handleOnAgentAck(envelope)
        return
    }
    if (envelope.type == MsgTypes.AGENT_QUEUE) {
        host.agentCtl.handleOnAgentQueue(envelope)
        return
    }
    if (envelope.type == MsgTypes.AGENT_RUN) {
        host.agentCtl.handleOnAgentRun(envelope)
        return
    }
    if (envelope.type == MsgTypes.AGENT_CANCEL) {
        host.agentCtl.handleOnAgentCancel(envelope)
        return
    }
    if (envelope.type == MsgTypes.AGENT_ARTIFACT) {
        host.agentCtl.handleOnAgentArtifact(envelope)
        return
    }
    // PPE-1 增补 D3 / D7 的四张帧。这四条必须**在** CHAT_INBOUND_TYPES 那道闸之前：
    // 那条闸只放 `msg`，而 agent 帧的归宿是各自的台账而不是聊天流。
    if (envelope.type == MsgTypes.AGENT_WORKSPACE) {
        host.agentCtl.handleOnAgentWorkspace(envelope)
        return
    }
    if (envelope.type == MsgTypes.AGENT_GRANT) {
        host.agentCtl.handleOnAgentGrant(envelope)
        return
    }
    if (envelope.type == MsgTypes.AGENT_GRANT_LIST) {
        host.agentCtl.handleOnAgentGrantList(envelope)
        return
    }
    // `agent-grant-request` 是**设备 → 宿主**方向的帧：手机端不承载授权决定
    // （父稿 §3.3 的分工表：决定在桌面端、执行在宿主进程），所以这里既不批准也不拒绝。
    // 分支显式写出来而不是让它掉到后面的 `CHAT_INBOUND_TYPES` 里，是为了让"我们知道有这帧、
    // 并且判断过不该在这里处理"这件事留在读得见的地方。
    if (envelope.type == MsgTypes.AGENT_GRANT_REQUEST) {
        return
    }
    if (envelope.type == MsgTypes.GROUP) {
        host.groupsCtl.handleOnGroupControl(envelope)
        return
    }
    if (envelope.type == MsgTypes.SCAN_RANGES) {
        host.cidrCtl.handleOnScanRangesReceived(envelope)
        return
    }
    if (envelope.type == MsgTypes.SCREEN) {
        host.screenCtl.handleOnScreenEnvelope(envelope)
        return
    }
    if (envelope.type == MsgTypes.AVATAR) {
        host.avatarCtl.handleOnAvatarEnvelope(envelope)
        return
    }
    if (envelope.type == MsgTypes.SHARE) {
        host.cabinetCtl.handleOnShareEnvelope(envelope)
        return
    }
    if (envelope.type !in CHAT_INBOUND_TYPES) return
    if (envelope.type == MsgTypes.MSG) {
        when (msgKindOf(envelope)) {
            MsgKinds.RECALL -> {
                handleOnRecallReceived(envelope)
                return
            }

            MsgKinds.NUDGE -> {
                handleOnNudged(envelope)
                return
            }

            MsgKinds.PK -> {
                host.pkCtl.handleOnPkReceived(envelope)
                return
            }

            else -> Unit
        }
    }
    val rawText = textOf(envelope) ?: return
    val (parsedReasoning, cleanedText) = parseThinkingContent(rawText)
    val payload = envelope.payload as? JsonObject
    val explicitReasoning = payload?.let { stringField(it, "reasoning") }
    val finalReasoning = explicitReasoning ?: parsedReasoning
    val reasoningElapsedMs = payload?.let { longField(it, "reasoningElapsedMs") }
    val groupId = if (isGroupText(envelope)) groupIdOf(envelope) else null
    val convId = groupId?.let(ConvId::group) ?: envelope.from
    val peer = host._state.value.peers.firstOrNull { it.nodeId == envelope.from }
    val peerName = peer?.nick ?: envelope.from
    host.handleAppendMessage(
        MessageUi(
            id = envelope.id,
            peerId = convId,
            mine = false,
            text = cleanedText,
            at = envelope.ts,
            delivery = null,
            senderId = if (groupId != null) envelope.from else null,
            replyTo = if (groupId != null) replyToOf(envelope) else null,
            reasoning = finalReasoning,
            reasoningElapsedMs = reasoningElapsedMs,
        ),
        incoming = true,
    )
    if (groupId != null) host.runtime?.let { host.groupsCtl.handleSyncGroupMetaIfNeeded(it, envelope) }
    if (
        shouldNotify(
            settings = host._state.value.settings,
            fromAgent = peer?.isAgent == true,
            isGroup = groupId != null,
            mentioned = mentionsSelf(envelope, host._state.value.selfNodeId),
            localHour = localHourOfDay(),
        )
    ) {
        val group = host._state.value.groupOf(groupId)
        val title = group?.let { "${it.name} · $peerName" } ?: peerName
        host.showNotification(title, cleanedText, convId)
    }
}


fun handleOnRecallReceived(envelope: Envelope) {
    val targetId = recallTargetOf(envelope) ?: return
    if (!handleApplyRecallFrom(envelope.from, targetId)) handlePendRecall(targetId)
}


fun handleApplyRecallFrom(from: String, targetId: String): Boolean {
    // host.stateLock 可重入：循环里调用的 handleApplyRecall / handleRecallLocally -> host.handleReplaceMessage 也会取它。
    return host.stateLock.withLock {
        if (handleApplyRecall(from, targetId)) return@withLock true
        for ((convId, thread) in host.core.threads) {
            if (!ConvId.isGroup(convId)) continue
            val target = thread.firstOrNull {
                (it.id == targetId || it.file?.msgId == targetId) && it.senderId == from
            } ?: continue
            if (target.mine) return@withLock true
            handleRecallLocally(convId, target)
            return@withLock true
        }
        false
    }
}


fun handleApplyRecall(peerId: String, targetId: String): Boolean = host.stateLock.withLock {
    val thread = host.core.threads[peerId] ?: return@withLock false
    val target = thread.firstOrNull { it.id == targetId || it.file?.msgId == targetId } ?: return@withLock false
    if (target.mine) return@withLock true
    handleRecallLocally(peerId, target)
    true
}


fun handleRecallLocally(peerId: String, target: MessageUi) {
    val file = target.file
    if (file != null) {
        if (!acceptsMediaRecall(file.purpose, file.state)) return
        if (file.state != TransferState.Completed) {
            host.runtime?.let { rt -> host.scope.launch { runCatching { rt.cancelIncoming(file.transferId) } } }
        }
    }
    host.handleReplaceMessage(
        peerId,
        target.copy(
            text = "",
            file = null,
            notice = NoticeKind.RecalledByPeer,
        ),
    )
}


fun handlePendRecall(targetId: String) {
    val now = nowMillis()
    host.stateLock.withLock {
        host.core.pendingRecalls.entries.removeAll { now - it.value > RECALL_WINDOW_MS }
        host.core.pendingRecalls[targetId] = now
    }
}


fun handleApplyPendingRecall(message: MessageUi) {
    if (message.mine) return
    val hit = host.stateLock.withLock {
        host.core.pendingRecalls.remove(message.id) != null ||
            message.file?.msgId?.let { host.core.pendingRecalls.remove(it) != null } == true
    }
    if (hit) handleApplyRecall(message.peerId, message.id)
}


fun handleOnNudged(envelope: Envelope) {
    if (!host.core.nudgeThrottle.allow(envelope.from, nowMillis())) return
    host.handleAppendMessage(
        MessageUi(
            id = envelope.id,
            peerId = envelope.from,
            mine = false,
            text = "",
            at = envelope.ts,
            notice = NoticeKind.NudgedByPeer,
        ),
    )
    host.requestFocus(envelope.from)
    if (!shouldNotifyNudge(host._state.value.settings, localHourOfDay())) return
    val peer = host._state.value.peers.firstOrNull { it.nodeId == envelope.from }
    val title = peer?.nick?.takeIf { it.isNotBlank() } ?: envelope.from
    host.scope.launch {
        val body = runCatching { getString(Res.string.chat_nudge_peer) }.getOrNull().orEmpty()
        host.showNotification(title, body, envelope.from)
    }
}
fun handleRestoreMessages(): Job {
    if (host.messagesRestored) return host.completedJob()
    host.messagesRestored = true
    if (!host.persistMessages) return host.completedJob()
    return host.scope.launch {
        val loaded = withContext(ioDispatcher) {
            runCatching { host.messageStore.load() }.getOrNull()
        } ?: return@launch
        if (!host.persistMessages || loaded.isEmpty()) return@launch
        var changed = false
        host.stateLock.withLock {
            for ((peerId, list) in loaded) {
                if (list.isEmpty()) continue
                val thread = host.core.threads.getOrPut(peerId) { mutableListOf() }
                var added = false
                for (message in list) {
                    if (thread.any { it.id == message.id }) continue
                    thread += message.toRestored()
                    added = true
                    changed = true
                }
                if (added) thread.sortBy { it.at }
            }
        }
        if (changed) host.publish()
    }
}


/**
 * 启动时把「排队态」重新贴回 UI 的消息行。
 *
 * ## 为什么这件事还存在
 *
 * 队列落盘之后，**凭据只有一份**：core 的 `QueueStore`。UI 不再自己存信封
 * （`MessageUi.outgoingEnvelope` 已删除）。但两边的知识不重叠 ——
 * core 知道「msgId=m-7 还在等」，UI 知道「m-7 是跟 peer-a 的会话里那条 10:02 的
 * 消息」。所以仍要在这里做一次映射。
 *
 * ## 方向反过来了
 *
 * 旧实现是「UI 有信封 → 喂回队列」。新实现是「队列有 msgId → 找 UI 的那一行」。
 * 后者更好：它不要求 UI 为每条排队消息保存一份可能过期的大字段，
 * 也就不存在「信封留在 UI 里但队列已经出队」这种不一致状态。
 *
 * ## 锁纪律
 *
 * 锁内只读 `core.threads` 取候选（不挂起），锁外做 `rt.pendingOutgoingMessages()`
 * 的 suspend 调用。
 */
suspend fun handleRestoreQueuedFrom(rt: NodeRuntime) {
    val stillQueued = rt.pendingOutgoingMessages().map { it.msgId }.toHashSet()
    if (stillQueued.isEmpty()) {
        markStaleQueuedAsFailed(null)
        return
    }

    host.stateLock.withLock {
        var changed = false
        for ((peerId, thread) in host.core.threads) {
            for (i in thread.indices) {
                val m = thread[i]
                if (!m.mine || m.delivery != DeliveryState.Sent) continue
                if (m.id !in stillQueued) continue
                thread[i] = m.copy(delivery = DeliveryState.Queued)
                changed = true
            }
        }
        if (changed) host.publish()
    }

    // 队列里已经没有的旧「已排队」行要改成失败，否则永远停在这个状态。
    markStaleQueuedAsFailed(stillQueued)
}

/**
 * 把 UI 说「已排队」但队列里已经没有的行改成失败。
 *
 * @param stillQueued 队列里还在等的 msgId；null 表示队列整个空了。
 */
private fun markStaleQueuedAsFailed(stillQueued: Set<String>?) {
    host.stateLock.withLock {
        var changed = false
        for (thread in host.core.threads.values) {
            for (i in thread.indices) {
                val m = thread[i]
                if (!m.mine || m.delivery != DeliveryState.Queued) continue
                if (stillQueued != null && m.id in stillQueued) continue
                thread[i] = m.copy(delivery = DeliveryState.Failed)
                changed = true
            }
        }
        if (changed) host.publish()
    }
    host.handlePersistThreads()
}

fun handleSyncPending(rt: NodeRuntime) {
    val next = rt.pendingByPeer()
    val changed = host.stateLock.withLock {
        if (next == host.core.pending.toMap()) return@withLock false
        host.core.pending.clear()
        host.core.pending.putAll(next)
        true
    }
    if (!changed) return
    host.publish()
}


fun handleOnPruned(pruned: List<PrunedMessage>) {
    if (pruned.isEmpty()) return
    val changed = host.stateLock.withLock {
        var any = false
        for (item in pruned) {
            val thread = host.core.threads[item.peerId] ?: continue
            val index = thread.indexOfFirst { it.id == item.msgId }
            if (index < 0) continue
            val next = thread[index].afterPruned()
            if (next == thread[index]) continue
            thread[index] = next
            any = true
        }
        any
    }
    if (!changed) return
    host.publish()
    host.handlePersistThreads()
}

// ------------------------------------------------------------------ 检索门面
//
// 存储层（[MessageStore]）只管「怎么找」，这一层管「找出来的东西摆成什么样」——
// 名字、副标题、图标这些要读 [RelayUiState] 才有的东西都在这里拼。
// 放这一层而不是放存储层，是因为存储层刻意只依赖 commonMain 的数据类型、
// 不认识 [PeerUi]/[ConversationUi]（见 `MessageStore` 的 KDoc）。

/**
 * **全局**检索：一页里的三块（联系人 / 聊天记录 / 文件），对齐原型
 * `prototype/relay.html:4454` 的 `screenDSearch`。
 *
 * 三段不是三页，也不是三个函数 —— 一次调用把三块都填好，因为界面上它们
 * 是同一次输入的结果（原型 `:3491`：「三段不是三页，是一页里的三块」）。
 * 拆成三个函数会让界面自己拼三次、并且各自处理一次空态，最后"三段都空"这个
 * 判据会在三个地方各写一遍。
 *
 * **不含主机名**（`PeerUi.host`）：主机名是本机给对端起的外号，两台机器可能
 * 各叫各的，用它检索会让同一个人在两台设备上搜出不同结果。IP 反而是双方共见的。
 * 计划明写了这条。
 */


// ------------------------------------------------------------------ 检索门面
//
// 存储层（[MessageStore]）只管「怎么找」，这一层管「找出来的东西摆成什么样」——
// 名字、副标题、图标这些要读 [RelayUiState] 才有的东西都在这里拼。
// 放这一层而不是放存储层，是因为存储层刻意只依赖 commonMain 的数据类型、
// 不认识 [PeerUi]/[ConversationUi]（见 `MessageStore` 的 KDoc）。

/**
 * **全局**检索：一页里的三块（联系人 / 聊天记录 / 文件），对齐原型
 * `prototype/relay.html:4454` 的 `screenDSearch`。
 *
 * 三段不是三页，也不是三个函数 —— 一次调用把三块都填好，因为界面上它们
 * 是同一次输入的结果（原型 `:3491`：「三段不是三页，是一页里的三块」）。
 * 拆成三个函数会让界面自己拼三次、并且各自处理一次空态，最后"三段都空"这个
 * 判据会在三个地方各写一遍。
 *
 * **不含主机名**（`PeerUi.host`）：主机名是本机给对端起的外号，两台机器可能
 * 各叫各的，用它检索会让同一个人在两台设备上搜出不同结果。IP 反而是双方共见的。
 * 计划明写了这条。
 */
fun handleSearchGlobalInternal(query: String): GlobalSearchResult {
    val q = query.trim()
    if (q.isEmpty()) return GlobalSearchResult(query = query)

    val state = host._state.value

    // 联系人段：规则在 [matchContacts] 里，单独抽出去是为了能直接测"不含 host"那一条
    // （见那个函数的 KDoc）。
    val contacts = matchContacts(state.peers, q)

    // 聊天记录段：存储层按会话聚合好了，这里只把会话名与摘要拼上。
    val chats = host.messageStore.searchGrouped(q, GlobalSearchResult.CHAT_LIMIT.toLong()).map { group ->
        GlobalSearchChatHit(
            convId = group.convId,
            title = state.convTitle(group.convId),
            matchCount = group.matchCount,
            latestAt = group.latestAt,
            // 文件消息的 text 是空串，而它不可能在按 text 聚合的那条 SQL 里命中 ——
            // 所以这里空串只可能是"会话里最后一条命中恰好没有正文"的边角，退回空串即可。
            snippet = group.latestText,
        )
    }

    // 文件段：存储层按展示名匹配，这里补上会话名与图片判据。
    val files = host.messageStore.searchFiles(q, GlobalSearchResult.FILE_LIMIT.toLong()).map { hit ->
        GlobalSearchFileHit(
            messageId = hit.id,
            convId = hit.convId,
            convTitle = state.convTitle(hit.convId),
            name = hit.fileName.orEmpty(),
            at = hit.timestamp,
            isImage = hit.isImage,
        )
    }

    return GlobalSearchResult(query = query, contacts = contacts, chats = chats, files = files)
}

/**
 * **会话内**检索：只在 [convId] 这一个会话里找，可带类型与日期条件。
 *
 * 与 [handleSearchGlobalInternal] 分成两个函数而不是加一个可空 `convId`，是因为原型把这两条
 * 写成两套代码（`:3545`）：「全局走 FTS5 全库，这里是 `searchMessages(convId, kw)`
 * 在当前会话里 LIKE 匹配，只取 50 条。所以在会话内搜得到不等于全局搜得到」。
 *
 * 返回的是命中明细而不是聚合 —— 会话内那屏列的就是一条条消息（原型 `HITS` 四行），
 * 会话本身是已知的，不需要再告诉用户"在哪个会话里"。
 */


/**
 * **会话内**检索：只在 [convId] 这一个会话里找，可带类型与日期条件。
 *
 * 与 [handleSearchGlobalInternal] 分成两个函数而不是加一个可空 `convId`，是因为原型把这两条
 * 写成两套代码（`:3545`）：「全局走 FTS5 全库，这里是 `searchMessages(convId, kw)`
 * 在当前会话里 LIKE 匹配，只取 50 条。所以在会话内搜得到不等于全局搜得到」。
 *
 * 返回的是命中明细而不是聚合 —— 会话内那屏列的就是一条条消息（原型 `HITS` 四行），
 * 会话本身是已知的，不需要再告诉用户"在哪个会话里"。
 */
fun handleSearchInConversationInternal(
    convId: String,
    query: String,
    filter: HistorySearchFilter = HistorySearchFilter.None,
    limit: Long = HISTORY_SEARCH_LIMIT,
): List<MessageSearchResult> = host.messageStore.searchInConversation(convId, query, filter, limit)

/**
 * 会话内检索的条数上限。
 *
 * 50 是原型 `:3545` 注释里写死的数字（「只取 50 条」），并且原型把那句话
 * 直接画在结果区底部当终态：「已列出这 4 条 · LIKE 最多取 50 条」——
 * 用户看得见这个数，所以它不能只在存储层当默认值、界面另写一个。
 */
}


/**
 * 会话内检索的条数上限。
 *
 * 50 是原型 `:3545` 注释里写死的数字（「只取 50 条」），并且原型把那句话
 * 直接画在结果区底部当终态：「已列出这 4 条 · LIKE 最多取 50 条」——
 * 用户看得见这个数，所以它不能只在存储层当默认值、界面另写一个。
 */
internal const val HISTORY_SEARCH_LIMIT: Long = 50
