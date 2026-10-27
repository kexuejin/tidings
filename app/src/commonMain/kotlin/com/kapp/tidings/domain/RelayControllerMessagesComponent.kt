// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.domain.RelayController.Companion.CHAT_INBOUND_TYPES
import com.kapp.tidings.domain.RelayController.Companion.groupIdOf
import com.kapp.tidings.domain.RelayController.Companion.groupSenderIdOf
import com.kapp.tidings.domain.RelayController.Companion.isGroupText
import com.kapp.tidings.domain.RelayController.Companion.mentionsSelf
import com.kapp.tidings.domain.RelayController.Companion.replyToOf
import com.kapp.tidings.domain.RelayController.Companion.textOf
import com.kapp.tidings.domain.model.ConversationUi
import com.kapp.tidings.domain.model.DeliveryState
import com.kapp.tidings.domain.model.MessageUi
import com.kapp.tidings.domain.model.NoticeDetail
import com.kapp.tidings.domain.model.NoticeKind
import com.kapp.tidings.domain.model.parseThinkingContent
import kotlinx.serialization.json.JsonObject
import com.kapp.tidings.domain.model.PeerUi
import com.kapp.tidings.domain.model.TransferState
import com.kapp.tidings.core.ioDispatcher
import com.kapp.tidings.core.nowMillis
import com.kapp.tidings.core.protocol.ConvId
import com.kapp.tidings.core.protocol.Envelope
import com.kapp.tidings.core.protocol.MsgKinds
import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.Protocol
import com.kapp.tidings.core.session.NodeRuntime
import com.kapp.tidings.core.session.OutgoingReplay
import com.kapp.tidings.core.session.OutgoingText
import com.kapp.tidings.core.transport.PrunedMessage
import com.kapp.tidings.core.transport.SendOutcome
import com.kapp.tidings.platform.localHourOfDay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    internal val host: RelayController,
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
                // 乐观插入：**先让气泡出现**，再等网络（见 DeliveryState.Sending 的 KDoc）。
                val optimistic = MessageUi(
                    id = id,
                    peerId = peerId,
                    mine = true,
                    text = body,
                    at = nowMillis(),
                    delivery = DeliveryState.Sending,
                )
                host.handleAppendMessage(optimistic)
                val success = client.sendMessage(peerId, body)
                // 结果就地替换同一条：用户看到的是那个气泡从"发送中"变成"已送达/失败"，
                // 而不是先消失再出现（也绝不会出现两条）。
                host.handleReplaceMessage(
                    peerId,
                    optimistic.copy(delivery = if (success) DeliveryState.Sent else DeliveryState.Failed),
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
        // 乐观插入：**先让气泡出现**，再等发送路径返回（见 [sendTextOptimistic]）。
        val sent = sendTextOptimistic(peerId, body) { rt.sendTextWithEnvelope(peerId, body) }
            ?: return@launch
        // 这条路按构造只服务 1:1（`sendToConversation` 把群会话键路由给 `sendToGroup`）。
        // 仍然过一遍共用判据：万一有人拿群键调进来，规则不该在第二个地方重写一遍。
        val groupId = ConvId.groupIdOf(peerId)
        // 目标是**别的宿主上的 agent** ⇒ 远端在场，本机不执行：那条消息已经按 1:1 协议
        // 送达它，本机再跑一遍就是「@ 了 agent 却收到本地 mock」。
        val targetPeer = host._state.value.peer(peerId)
        val remoteAgentPresent = targetPeer?.isAgent == true
        val localAgentPresent = host.onAgentPrompt != null && !remoteAgentPresent
        // 该宿主上**所有** kind（一台设备可以装多个 agent）。取不到才退回默认集。
        val agentKinds = host._state.value.agentKindsByHost()[peerId]
            ?.takeIf { it.isNotEmpty() }
            ?: RelayController.DEFAULT_AGENT_KINDS
        val route = RelayController.agentPromptRoute(
            text = body,
            groupId = groupId,
            isWorkspaceGroup = groupId != null && host.isWorkspaceGroup(groupId),
            repliedToIsAgent = false,
            localAgentPresent = localAgentPresent,
            remoteAgentPresent = remoteAgentPresent,
            agentKinds = agentKinds,
        )
        if (route == AgentPromptRoute.Local) {
            val prompt = RelayController.extractAgentPrompt(body, agentKinds)
            val self = host._state.value.selfNodeId
            host.scope.launch {
                host.onAgentPrompt?.invoke(peerId, self, prompt, sent.envelopeId)
            }
        }
    }
}

/**
 * 用户点「重发」：按**原消息 id** 重放那条消息，而不是以原文本再发一条。
 *
 * ## 为什么不能「再发一条」
 *
 * 收端按 `(id, from)` 去重：新 id 就是**另一条消息**。真机现场：同一条 231 字的消息在
 * 桌面 `MessageEntity` 里是 `f93a27cf…` 与 `0137bff5…` 两条 —— 用户说的是「点重发，
 * 又多出一条」。本仓 `MessageStore` 的 KDoc
 * 早就把「原样重放、让对端去重挡掉第二条」写成不变量，这里是它在 UI 侧的落点。
 *
 * ## 两条路（在 core 里）
 *
 * 队列里还有这条的原信封 ⇒ 原样重放（连入队时刻都不变）；没有了（已 ACK / 被剪枝 /
 * 换过进程）⇒ 用原 id 重建并打 `resend = true`。两条路都不写 messageStore —— 历史行
 * 已经在库里，再 append 就是本地的第二条。
 *
 * 群聊要额外给 `groupId / groupRev / members`：群消息在队列里是**逐成员**一行，没有
 * 「一条群消息」的行可查，core 一律按成员重建并继续做 `gta1` 门控。
 *
 * 界面全程只有一条气泡：点下去立刻回到「发送中」，结果回来再改成终态。
 */
fun handleRetryMessage(message: MessageUi) {
    if (!message.mine || message.text.isBlank()) return
    val rt = host.runtime ?: run { host.warn(BannerMessage.NodeNotStarted); return }
    host.handleReplaceMessage(message.peerId, message.copy(delivery = DeliveryState.Sending))
    host.scope.launch {
        val convId = message.peerId
        val group = ConvId.groupIdOf(convId)?.let { groupId ->
            host._state.value.settings.groups.firstOrNull { it.groupId == groupId }
        }
        val outcome = runCatching {
            rt.replayOutgoing(
                OutgoingReplay(
                    peerId = convId,
                    msgId = message.id,
                    text = message.text,
                    groupId = group?.groupId,
                    groupRev = group?.rev,
                    members = group?.members,
                    replyTo = message.replyTo,
                    senderId = message.senderId,
                    reasoning = message.reasoning,
                ),
            )
        }.onFailure {
            host.warn(
                it.messageOrNull()?.let(BannerMessage::SendFailed) ?: BannerMessage.UnknownError,
            )
        }.getOrNull()
        host.handleReplaceMessage(
            message.peerId,
            message.copy(
                delivery = when (outcome) {
                    SendOutcome.SENT -> DeliveryState.Sent
                    SendOutcome.QUEUED -> DeliveryState.Queued
                    null -> DeliveryState.Failed
                },
            ),
        )
    }
}

/**
 * 私聊文本的乐观插入：**先让气泡出现**，再等发送路径返回，然后按临时 id 就地改写。
 *
 * 发送路径最坏要等 40 秒量级（多端点 UDP 退避 + 每端点 TCP 7s），那段时间界面原本
 * **什么都不显示** —— 用户会以为没点上而重复发送。先插一个 [DeliveryState.Sending] 气泡，
 * 结果回来后就地改写它：用户看到的是同一个气泡从「发送中」变成终态，而不是先消失再出现
 * （也绝不会出现两条）。
 *
 * 临时 id 用 `local-` 前缀（[RelayController.localMessageId]）：真实 id 要等发送路径返回
 * 才有（[OutgoingText.envelopeId]）。成功后按临时 id 找到这一条、换成真实 id 与终态 ——
 * 仍然只有一条气泡，且 id 从此与协议一致（回执 / 撤回都靠它）。
 *
 * @param send 真正的发送动作（生产路径传 `rt.sendTextWithEnvelope` 的调用）。抽成形参是
 *   为了让「先插后等」这个**时序**能被闸门式测试钉住，而不是只能断言终态。
 * @return 发送路径的结论；抛异常时为 null（那条气泡已收尾成 [DeliveryState.Failed]）。
 */
internal suspend fun sendTextOptimistic(
    peerId: String,
    body: String,
    send: suspend () -> OutgoingText,
): OutgoingText? {
    val (optimisticReasoning, optimisticText) = parseThinkingContent(body)
    val optimistic = MessageUi(
        id = host.localMessageId(),
        peerId = peerId,
        mine = true,
        text = optimisticText,
        at = nowMillis(),
        delivery = DeliveryState.Sending,
        reasoning = optimisticReasoning,
    )
    host.handleAppendMessage(optimistic)
    val outcome = runCatching { send() }
        .onFailure {
            host.warn(
                it.messageOrNull()?.let(BannerMessage::SendFailed) ?: BannerMessage.UnknownError,
            )
        }
        .getOrNull()
    if (outcome == null) {
        // 发送路径自己抛了：**必须**把那个「发送中」收尾成失败，否则它会永远转下去
        // （那是比「不显示气泡」更糟的谎）。
        host.replaceMessage(peerId, optimistic.id, optimistic.copy(delivery = DeliveryState.Failed))
        return null
    }
    val (parsedReasoning, cleanedText) = parseThinkingContent(body)
    // 按**临时 id** 定位再换 id：只按新 id 找会落空，气泡就永远停在「发送中」。
    host.replaceMessage(
        peerId,
        optimistic.id,
        optimistic.copy(
            id = outcome.envelopeId,
            text = cleanedText,
            delivery = if (outcome.outcome == SendOutcome.SENT) DeliveryState.Sent else DeliveryState.Queued,
            reasoning = parsedReasoning,
        ),
    )
    return outcome
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


/**
 * **只删本机的这一条**（不动协议、不通知对方）。
 *
 * 与「撤回」是两件事，用户经常要的是后者做不到的那个：
 * - 撤回有时限（自己的、5 分钟内）、**会告诉对方**、且协议里有它的位置；
 * - 删除是纯本机行为：对方库里那条照旧，只是「我自己不想再看到它」。
 *
 * 为什么必须有这条路：现在用户想清掉一条只有两个选择 —— 撤回（超时就没了）
 * 或清空整个会话（那是核弹）。中间那一档是缺的。
 *
 * **FTS 不变量靠库层的触发器兜住**：`ALWAYS` 走 `deleteMessage` 的
 * `DELETE FROM MessageEntity` 会激活 `message_after_delete`，它把该行从
 * `MessageFts` 里按 'delete' 命令删掉（见 RelayDatabase.sq 的触发器）。
 * 所以这里**不要**再手写一次索引清理 —— 那是第二条真相来源，漂了就是一具孤儿索引。
 */
fun handleDeleteMessage(convId: String, messageId: String) {
    var removed = false
    host.stateLock.withLock {
        val thread = host.core.threads[convId] ?: return@withLock
        // 原地删（threads 的值是 MutableList，且它已被 publish 引用过）；
        // 删空了就把整个会话键摘掉，与 `handleClearMessages` 的收尾保持一致。
        val removedAny = thread.removeAll { it.id == messageId }
        if (!removedAny) return@withLock
        removed = true
        if (thread.isEmpty()) host.core.threads.remove(convId)
        // 删掉的是最后一条未读时，会话的未读数也要跟着降 —— 否则红点挂着一个
        // 永远点不掉的数字（消息已经不在列表里了）。
        val unread = host.core.unread[convId] ?: 0
        if (unread > 0) host.core.unread[convId] = (unread - 1).coerceAtLeast(0)
    }
    if (removed) {
        host.publish()
        host.handlePersistThreads()
    }
    // **删除必须显式打到存储层。** 内存里删掉只是让界面立刻消失；
    // 落盘那一步不再由 save() 反推（见 MessageStore.deleteMessage 的 KDoc）。
    //
    // 这条也顺带修掉一个分页之后才会暴露的形态：要删的那条可能**不在内存窗口里**
    // （更早的历史），此时上面 removeAll 返回 false、removed 为 false，
    // 但库里那条仍然必须删掉 —— 否则用户看到的是「删了还在」。
    if (host.persistMessages) {
        val target = host.messageStore
        host.scope.launch {
            host.persistMutex.withLock {
                withContext(ioDispatcher) { runCatching { target.deleteMessage(messageId) } }
            }
        }
    }
}


fun handleClearMessages(peerId: String) {
    host.stateLock.withLock {
        host.core.threads.remove(peerId)
        host.core.unread.remove(peerId)
    }
    host.publish()
    // 同 handleDeleteMessage：清会话也要显式打到存储层，不能指望 save() 反推。
    if (host.persistMessages) {
        val target = host.messageStore
        host.scope.launch {
            host.persistMutex.withLock {
                withContext(ioDispatcher) { runCatching { target.deleteConversation(peerId) } }
            }
        }
    }
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
    // 入站消息的可观测点（三端共用这一处）。一行日志就能把
    // 「对方说发了、我这边没收到」与「收到了但没渲染」区分开 ——
    // 在模拟器等难以直接触控取证的环境中，靠它确认消息真的落库。
    println("[msg] inbound from=" + envelope.from + " conv=" + convId + " len=" + cleanedText.length)
    host.handleAppendMessage(
        MessageUi(
            id = envelope.id,
            peerId = convId,
            mine = false,
            text = cleanedText,
            at = envelope.ts,
            delivery = null,
            // **说话者归属**（真机缺陷）：群消息不能直接用信封 `from`。
            //
            // `from` 永远是**发送这台设备**，而设备可能在代别人说话 —— DSH 的 agent 事件由
            // 桌面端转发到群里，`from` 是桌面端的 nodeId，于是手机把 agent 说的话显示成
            // 「桌面端发的」。而桌面端自己按 `agent:<kind>` 落库 ⇒ 同一个群两端发送者不一致。
            //
            // payload 里的 `senderId` 才是真正说话的那一方（旧端不发 ⇒ 退回 `from`，
            // 与以前完全一致）。形态与桌面端本地落库同一套：真人 = nodeId，agent = `agent:<kind>`。
            senderId = if (groupId != null) (groupSenderIdOf(envelope) ?: envelope.from) else null,
            // 会话归属：协议字段（旧端不发 ⇒ null ⇒ 分段线按 id 前缀回退，与以前一致）。
            // 有它之后，手机端才画得出桌面端一直有的那条「任务分段线」。
            agentSessionId = payload?.let { stringField(it, "agentSessionId") },
            // 标题跟着消息来（不是另一次会话元数据同步）：分段线要显示「任务：xxx」。
            // 缺省 null ⇒ 界面显示「新会话」那句中性说明，而不是静默不画线。
            agentSessionTitle = payload?.let { stringField(it, "agentSessionTitle") },
            replyTo = if (groupId != null) replyToOf(envelope) else null,
            reasoning = finalReasoning,
            reasoningElapsedMs = reasoningElapsedMs,
        ),
        incoming = true,
    )
    if (groupId != null) host.runtime?.let { host.groupsCtl.handleSyncGroupMetaIfNeeded(it, envelope) }
    // 接话判据：这条是不是在回复 agent 自己发的那条（只有群文本才有 `replyTo`）。
    val replyToId = if (groupId != null) replyToOf(envelope) else null
    val repliedToIsAgent = replyToId != null && RelayController.isAgentAuthored(
        host._state.value.messages[convId].orEmpty().firstOrNull { it.id == replyToId },
    )
    // 触发判据（要不要叫 agent）只有一个；这里再问「谁执行」：
    // 远端 agent 在场时本机让路 —— 这条消息已经送达那台设备，本机不替它回话。
    val routeFacts = if (groupId != null) {
        host.agentRouteFactsOf(groupId)
    } else {
        val senderPeer = host._state.value.peer(envelope.from)
        val remote = senderPeer?.isAgent == true
        GroupAgentRouteFacts(
            agentKinds = RelayController.DEFAULT_AGENT_KINDS,
            localAgentPresent = host.onAgentPrompt != null && !remote,
            remoteAgentPresent = remote,
        )
    }
    val route = RelayController.agentPromptRoute(
        text = cleanedText,
        groupId = groupId,
        isWorkspaceGroup = groupId != null && host.isWorkspaceGroup(groupId),
        repliedToIsAgent = repliedToIsAgent,
        localAgentPresent = routeFacts.localAgentPresent,
        remoteAgentPresent = routeFacts.remoteAgentPresent,
        agentKinds = routeFacts.agentKinds,
    )
    if (route == AgentPromptRoute.Local) {
        val prompt = RelayController.extractAgentPrompt(cleanedText, routeFacts.agentKinds)
        host.scope.launch {
            host.onAgentPrompt?.invoke(convId, envelope.from, prompt, envelope.id)
        }
    }
    // **无权限提示必须独立于路由判定。**
    //
    // 第一版把它挂在 `route != Local` 的 else 分支上，真机验证时立刻暴露出错：`route`
    // 回答的是「**谁执行**」（本机有 agent 宿主就是 `Local`），它**根本不看授权**。
    // 于是「发送者没有执行权」这个事实被路由掩盖了 —— 桌面端自己是宿主 ⇒ route=Local ⇒
    // else 永不执行 ⇒ 提示永不出现。这正好复现了原来那个 bug 的形态（静默）。
    //
    // 所以这里单独问一句：这条 @ 命中的 agent，**发送者有没有权让它干活**。
    run {
        // groupId 为 null（私聊）时这条判据不适用 —— 私聊里的 @ 是 1:1 语义。
        val blockedAgent = groupId?.let { host.unathorizedAgentMention(cleanedText, it, envelope.from) }
        if (blockedAgent != null) {
            host.handleAppendMessage(
                MessageUi(
                    id = host.localMessageId(),
                    peerId = convId,
                    mine = false,
                    text = "",
                    at = nowMillis(),
                    notice = NoticeKind.AgentNotAuthorized,
                    noticeDetail = NoticeDetail(added = emptyList(), removed = emptyList(), actor = blockedAgent),
                ),
            )
        }
    }

    if (
        shouldNotify(
            settings = host._state.value.settings,
            fromAgent = peer?.isAgent == true,
            isGroup = groupId != null,
            mentioned = mentionsSelf(envelope, host._state.value.selfNodeId),
            localHour = localHourOfDay(),
            // 会话级静音：用户说过「这个会话别吵我」，就不弹。convId 在上面已算好
            // （私聊 = 对端 nodeId、群 = group:<id>），与置顶/删除用的是同一个键。
            convId = convId,
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
        // 文案经桥取（见 [RelayUiTexts]）：领域侧不直接碰 composeResources。
        val body = runCatching { host.uiTexts.nudgeNotice() }.getOrNull().orEmpty()
        host.showNotification(title, body, envelope.from)
    }
}
fun handleRestoreMessages(): Job {
    if (host.messagesRestored) return host.completedJob()
    host.messagesRestored = true
    if (!host.persistMessages) return host.completedJob()
    return host.scope.launch {
        // 分页读：每个会话只取最近 MessageStore.MESSAGE_PAGE_SIZE 条。
        //
        // 为什么不是"只读当前打开的会话"：会话列表的预览、最后消息时间与排序，
        // 全部依赖「每个会话的最后一条」。只加载一个会话，其余的会从列表里消失、
        // 或预览变成空白 —— 那是功能回退，不是优化。
        val loaded = withContext(ioDispatcher) {
            runCatching { host.messageStore.loadRecent(MessageStore.MESSAGE_PAGE_SIZE) }.getOrNull()
        } ?: return@launch
        if (!host.persistMessages) return@launch
        // 会话摘要基线：内存里没有消息的会话，靠它才在列表里出现。
        // 读的是 ConversationSummary 表（几十行），与消息的分页窗口无关。
        val summaries = withContext(ioDispatcher) {
            runCatching { host.messageStore.loadSummaries() }.getOrNull().orEmpty()
        }
        var changed = false
        host.stateLock.withLock {
            host.core.summaries.clear()
            for (summary in summaries) host.core.summaries[summary.convId] = summary
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
        // 摘要非空也要 publish：消息窗口可能一条都没有（老库刚迁移），
        // 但会话列表仍要按摘要把行画出来。
        if (changed || summaries.isNotEmpty()) host.publish()
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
/**
 * 向上翻页：把某会话里比当前窗口更早的一页读进来，插到窗口头部。
 *
 * 游标是**当前窗口最老那条的 seq**（不是时间戳）：同一毫秒多条消息时，
 * 时间戳游标会重复或漏，而那正是 agent 流式回复的常态。
 *
 * 边界：
 * - 没有持久化（persistMessages=false）⇒ 内存就是全部，没什么可翻的；
 * - 窗口最老那条没有 seq（本地刚构造、还没落库）⇒ 不翻，等它落库后自然会带上；
 * - 读回来 0 条 ⇒ 到历史尽头了，什么都不做（不要在 UI 上谎报「还有更多」）。
 */
fun handleLoadOlderMessages(convId: String) {
    if (!host.persistMessages) return
    val oldestSeq = host.stateLock.withLock {
        host.core.threads[convId]?.firstOrNull()?.seq
    } ?: return
    val target = host.messageStore
    host.scope.launch {
        val older = withContext(ioDispatcher) {
            runCatching {
                target.loadOlder(convId, oldestSeq, MessageStore.MESSAGE_PAGE_SIZE)
            }.getOrNull()
        } ?: return@launch
        if (older.isEmpty()) return@launch
        val added = host.stateLock.withLock {
            val thread = host.core.threads.getOrPut(convId) { mutableListOf() }
            val known = thread.mapTo(HashSet()) { it.id }
            // 去重：翻页与实时消息可能同时到达，同一条不能出现两次。
            val fresh = older.filter { it.id !in known }
            if (fresh.isEmpty()) {
                0
            } else {
                // 插到头部：older 已按时间升序，接在现有窗口之前即可保持整体有序。
                thread.addAll(0, fresh)
                fresh.size
            }
        }
        if (added > 0) host.publish()
    }
}

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
fun handleSearchGlobalInternal(
    query: String,
    /**
     * 只查哪一段。默认 [GlobalSearchScope.All] —— 老调用点（与桌面全局搜索）行为不变。
     *
     * 不要的段**直接不查**：先查出来再丢掉等于白付一次全库扫描的钱，
     * 而这个搜索是同步跑在 UI 路径上的。
     */
    scope: GlobalSearchScope = GlobalSearchScope.All,
): GlobalSearchResult {
    val q = query.trim()
    if (q.isEmpty()) return GlobalSearchResult(query = query)

    val state = host._state.value

    // 联系人段：规则在 [matchContacts] 里，单独抽出去是为了能直接测"不含 host"那一条
    // （见那个函数的 KDoc）。
    val contacts = if (scope.includes(GlobalSearchScope.Contacts)) {
        matchContacts(state.peers, q)
    } else {
        emptyList()
    }

    // 聊天记录段：存储层按会话聚合好了，这里只把会话名与摘要拼上。
    val chats = if (scope.includes(GlobalSearchScope.Chats)) {
        host.messageStore.searchGrouped(q, GlobalSearchResult.CHAT_LIMIT.toLong()).map { group ->
            GlobalSearchChatHit(
                convId = group.convId,
                title = state.convTitle(group.convId),
                matchCount = group.matchCount,
                latestAt = group.latestAt,
                // 文件消息的 text 是空串，而它不可能在按 text 聚合的那条 SQL 里命中 ——
                // 所以这里空串只可能是「会话里最后一条命中恰好没有正文」的边角，退回空串即可。
                snippet = group.latestText,
            )
        }
    } else {
        emptyList()
    }

    // 文件段：存储层按展示名匹配，这里补上会话名与图片判据。
    val files = if (scope.includes(GlobalSearchScope.Files)) {
        host.messageStore.searchFiles(q, GlobalSearchResult.FILE_LIMIT.toLong()).map { hit ->
            GlobalSearchFileHit(
                messageId = hit.id,
                convId = hit.convId,
                convTitle = state.convTitle(hit.convId),
                name = hit.fileName.orEmpty(),
                at = hit.timestamp,
                isImage = hit.isImage,
            )
        }
    } else {
        emptyList()
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