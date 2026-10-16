// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.session

import app.relay.core.protocol.Codec
import app.relay.core.protocol.MsgTypes
import app.relay.core.protocol.ConvId
import app.relay.core.protocol.Profile
import app.relay.core.protocol.makeEnvelope
import app.relay.core.transport.DIRECTION_OUT
import app.relay.core.transport.SendOutcome
import app.relay.core.protocol.Envelope
import app.relay.core.protocol.GroupMeta
import app.relay.core.transport.Messenger
import app.relay.core.transport.MessageStore
import app.relay.core.nowMillis

/**
 * 出站：把一条意图变成一条协议帧，并落进消息历史。
 *
 * 切出来的理由：这些方法（22 个 `send*`）的演化动力是**协议在长** —— 每加一种
 * 新的可发送内容就多一个方法。而 [NodeRuntime] 剩下的部分是**生命周期与接线**
 * （start 的顺序即契约、端口自愈、收件箱、剪枝），两者的变化节奏完全不同。
 *
 * ## 为什么 selfId 是 `() -> String`
 *
 * [NodeRuntime.start] 会改写 `profile.tcpPort`（绑不上请求端口时退到系统分配的端口）。
 * 如果把 nodeId 捕获成一个 `String` 传进来，重启后就会用**旧** nodeId 发信 ——
 * 症状是「消息能发出去但被对端静默丢弃」，因为对端节点表里没有这条记录。
 * 传函数、每次现取，是为了让这个陷阱在类型上无法发生。
 *
 * @param resolvePeer 本机节点自己；[NodeRuntime] 传 `this`，`sendTextToHost` 要用它握手。
 */
internal class NodeOutbound(
    private val messenger: Messenger,
    private val messageStore: MessageStore,
    private val selfId: () -> String,
    private val selfProfile: () -> Profile,
    private val resolvePeer: NodeRuntime,
) {
    /**
     * 给一个 nodeId 发文本。
     *
     * 返回值语义（与茶话间一致，勿混）：
     * - [SendOutcome.SENT]：对方已 ACK；
     * - [SendOutcome.QUEUED]：**只代表已入队等对方上线**，不代表对方读过。
     */
    suspend fun sendText(peerId: String, text: String): SendOutcome =
        sendTextWithEnvelope(peerId, text).outcome

    /**
     * 给一个 nodeId 发文本，并把**这条信封本身**交回给调用方。
     *
     * 只想知道成不成功就用 [sendText]。这个重载多返回的那份 JSON 有两个用途：
     * core 自己写 agentNode 的 inbox 日志（见本方法体里的 `messageStore.append`），
     * 以及测试要断言"重放的是同一条信封"。
     *
     * **上层不需要拿它做持久化** —— 待发队列自己落盘（见 `core/persist/PersistStores`），
     * 返回 [SendOutcome.QUEUED] 的那条已经躺在 `<dataDir>/stores/queue.log` 里，
     * 重启后由队列自己继续补发。早期版本要求上层把信封原样存下来、重启时喂回
     * [requeueOutgoing]，那是因为队列一度只是进程内的（[InMemoryQueueStore]）。
     */
    suspend fun sendTextWithEnvelope(peerId: String, text: String): OutgoingText {
        val envelope = makeEnvelope(MsgTypes.MSG, selfId(), Codec.textPayload(text))
        val outcome = messenger.sendUserMessage(peerId, envelope)
        val envelopeJson = Codec.encode(envelope)
        messageStore.append(
            peerId = peerId,
            direction = DIRECTION_OUT,
            envelopeJson = envelopeJson,
            at = nowMillis(),
        )
        return OutgoingText(outcome = outcome, envelopeId = envelope.id, envelopeJson = envelopeJson)
    }

    /**
     * 撤回一条消息：发 `msg(kind:"recall", targetId)`。
     *
     * 投递语义与普通消息**完全一致**（协议文档 §8：「撤回指令与普通消息一样走 ACK / 重传 /
     * 离线补发」），所以走 [Messenger.sendUserMessage]：对端不在线时它会入队，等对端上线补发。
     * 群聊撤回要带上 `groupId`/`groupRev`（两个成对，见 [Codec.recallPayload]）。
     *
     * **不写 messageStore**：撤回是控制帧，不是一条聊天记录。写进去会让它出现在 `inbox()` /
     * `history()` 里，重启后界面上多出一条没有正文的"消息"。
     */
    suspend fun sendRecall(
        peerId: String,
        targetId: String,
        groupId: String? = null,
        groupRev: Int? = null,
    ): SendOutcome = messenger.sendUserMessage(
        peerId,
        makeEnvelope(MsgTypes.MSG, selfId(), Codec.recallPayload(targetId, groupId, groupRev)),
    )

    /**
     * 发一条群文本（`msg(kind:"group-text")`）给 [members] 里的每个人。
     *
     * ### 一条信封，扇出 N 份
     * 群聊在协议上是**逐成员单播**，没有组播地址。但扇出去的是**同一个信封对象** ——
     * 同一个 `id`、同一个 `ts`、同一份载荷：
     * 先 `makeEnvelope` 一次、本地入库一次，再针对成员逐一调用 `sendUserMessage`。
     * 每个成员各自 ACK、各自入队、各自重传，但**消息 id 只有一个**，所以：
     * - 每个收端入库的是同一条消息，多设备/多端看到的 id 一致，撤回才对得上；
     * - 不会出现"同一条话在 N 个成员那里有 N 个不同 id"这种没法撤回的状态。
     *
     * ### 不做按成员回执（简化策略）
     * 群消息不做按成员回执：本端入库即 sent，离线成员由补发队列保送达。本方法同样**不返回逐成员结果**，只回一个
     * [SendOutcome]：全部成员都 ACK 了才是 [SendOutcome.SENT]，否则 [SendOutcome.QUEUED]。
     *
     * 为什么不自己发明一套"3/5 已送达"：那要求给每个成员各造一条记录并逐条跟踪，
     * 而协议里没有任何字段能让对端知道"这是同一条群消息的第几份"。半套回执比没有回执更糟
     * —— 界面会显示一个看起来精确、实际上只统计到"我们发出去的那几份"的数字。
     *
     * [groupRev] 是发送时的群版本：对端据此判断自己的成员表是不是旧的，旧了就回
     * `group{op:"need"}`。**用本地当前 rev** —— 收端只比较大小。
     *
     * 本地历史写进 `group:<groupId>` 这个会话键（与点对点群聊 `conv_id` 规范一致），
     * 而不是收件人的 nodeId：按 nodeId 存会得到 N 条一模一样的记录散在 N 个私聊会话里。
     *
     * @param members 群成员表（含自己）；自己会被跳过。
     */
    suspend fun sendGroupText(
        text: String,
        groupId: String,
        groupRev: Int,
        members: List<String>,
        mentions: List<String>? = null,
        replyTo: String? = null,
    ): SendOutcome {
        val envelope = makeEnvelope(
            MsgTypes.MSG,
            selfId(),
            Codec.groupTextPayload(text, groupId, groupRev, mentions = mentions, replyTo = replyTo),
        )
        messageStore.append(
            peerId = ConvId.group(groupId),
            direction = DIRECTION_OUT,
            envelopeJson = Codec.encode(envelope),
            at = nowMillis(),
        )
        var allSent = true
        for (member in members) {
            if (member == selfId()) continue
            val outcome = messenger.sendUserMessage(member, envelope)
            if (outcome != SendOutcome.SENT) allSent = false
        }
        return if (allSent) SendOutcome.SENT else SendOutcome.QUEUED
    }

    suspend fun sendPk(
        peerId: String,
        game: String,
        value: String,
        groupId: String? = null,
        groupRev: Int? = null,
    ): OutgoingText {
        val envelope = makeEnvelope(
            MsgTypes.MSG,
            selfId(),
            Codec.pkPayload(game, value, groupId, groupRev),
        )
        val outcome = messenger.sendUserMessage(peerId, envelope)
        val envelopeJson = Codec.encode(envelope)
        messageStore.append(
            peerId = peerId,
            direction = DIRECTION_OUT,
            envelopeJson = envelopeJson,
            at = nowMillis(),
        )
        return OutgoingText(outcome = outcome, envelopeId = envelope.id, envelopeJson = envelopeJson)
    }

    suspend fun sendGroupPk(
        game: String,
        value: String,
        groupId: String,
        groupRev: Int,
        members: List<String>,
    ): OutgoingText {
        val envelope = makeEnvelope(
            MsgTypes.MSG,
            selfId(),
            Codec.pkPayload(game, value, groupId, groupRev),
        )
        val envelopeJson = Codec.encode(envelope)
        messageStore.append(
            peerId = ConvId.group(groupId),
            direction = DIRECTION_OUT,
            envelopeJson = envelopeJson,
            at = nowMillis(),
        )
        var allSent = true
        for (member in members) {
            if (member == selfId()) continue
            val outcome = messenger.sendUserMessage(member, envelope)
            if (outcome != SendOutcome.SENT) allSent = false
        }
        val finalOutcome = if (allSent) SendOutcome.SENT else SendOutcome.QUEUED
        return OutgoingText(outcome = finalOutcome, envelopeId = envelope.id, envelopeJson = envelopeJson)
    }

    /**
     * 广播一份群元数据（`group{op:"info"}`）给一个成员。
     *
     * 走 [Messenger.sendUserMessage]（可靠 + 离线入队），与规范广播策略一致。
     * 这个选择是**功能性的**不是顺手：建群时被拉进来的人很可能正离线，"回来即知道自己
     * 进了群"全靠这条队列。换成 [sendGroupInfoReply] 那种不等 ACK 的路子，离线成员就
     * 永远不知道有这个群，症状是"他被拉进群了，但他那边什么都没有"。
     */
    suspend fun sendGroupInfo(peerId: String, meta: GroupMeta): SendOutcome =
        messenger.sendUserMessage(
            peerId,
            makeEnvelope(MsgTypes.GROUP, selfId(), Codec.groupInfoPayload(meta)),
        )

    /**
     * 回一份群元数据给**刚刚**向我们要过的人（`op:"need"` 的应答）。
     *
     * 与 [sendGroupInfo] 的差别是投递语义，不是措辞：对方上一条消息刚到手，说明他此刻
     * 在线，所以不等 ACK、不入离线队列（使用 `sendReliableNoQueue`）。入队反而有害 ——
     * 一份过期快照在 7 天后被补发出去，会把对方的群成员表**改回旧版本**。
     */
    suspend fun sendGroupInfoReply(peerId: String, meta: GroupMeta): Boolean =
        messenger.sendReliableNoQueue(
            peerId,
            makeEnvelope(MsgTypes.GROUP, selfId(), Codec.groupInfoPayload(meta)),
        )

    /**
     * 请对方把某个群的元数据发我（`group{op:"need"}`）。
     *
     * 同样不入队：这条请求的触发条件是"我刚收到一条 rev 比我新的群消息"，对方此刻必然
     * 在线。它还是**幂等**的 —— 丢了就等下一次群消息再问。
     */
    suspend fun sendGroupNeed(peerId: String, groupId: String): Boolean =
        messenger.sendReliableNoQueue(
            peerId,
            makeEnvelope(MsgTypes.GROUP, selfId(), Codec.groupNeedPayload(groupId)),
        )

    /**
     * 不入队发送可靠单播信封（用于 control / scan-ranges / gossip 帧）。
     */
    suspend fun sendReliableNoQueue(peerId: String, envelope: Envelope): Boolean =
        messenger.sendReliableNoQueue(peerId, envelope)

    /**
     * 戳一碰（私聊窗口震动）：发 `msg(kind:"nudge")`。
     *
     * 用 [Messenger.sendReliableNoQueue] 而不是 [Messenger.sendUserMessage]：协议要求它
     * 「若对端无响应则失败且**不进入离线补发队列**」。
     *
     * @return 对端有没有 ACK 这一帧。`false` = 现在戳不到，调用方如实告诉用户。
     */
    suspend fun sendNudge(peerId: String): Boolean = messenger.sendReliableNoQueue(
        peerId,
        makeEnvelope(MsgTypes.MSG, selfId(), Codec.nudgePayload()),
    )

    /**
     * 发一条 agent 会话宣告（PPE-1 §6.1，类型 `agent-session`）。
     *
     * 投递语义与普通消息**一致**（走 ACK / 重传 / 离线补发）：§10 要求任何"已送达"都有真实
     * 回执，而 P0 能拿到的只有传输层 ACK（`agent-ack` 属 P1）。
     *
     * **不写 messageStore**：会话宣告是控制帧，不是一条聊天记录 —— 写进去会让它出现在
     * `inbox()` / `history()` 里，界面上多出一条没有正文的"消息"（与 [sendRecall] 同理）。
     *
     * **本方法不查对端 caps**：§4 要求发 `agent-*` 前先确认对方声明了 `agn1`，但这一层只
     * 负责发送。调用方必须先问 [peerDeclares]，否则对端会静默丢弃、本端一直重传到入队，
     * 表现成"对方离线"这个查不出来的假故障。
     */
    @Suppress("LongParameterList")
    suspend fun sendAgentSession(
        peerId: String,
        sessionId: String,
        alias: String? = null,
        agentKind: String? = null,
        state: String? = null,
        title: String? = null,
        startedAt: Long? = null,
        agentId: String? = null,
        wsId: String? = null,
        wsTitle: String? = null,
        wsPath: String? = null,
        updatedAt: Long? = null,
        origin: String? = null,
        blank: Boolean? = null,
    ): SendOutcome {
        // PPE-1 增补 §2.4 的 cap 门控，全仓只在这一处：四个新键只对声明了 `aws1` 的
        // 对端附带，没声明时**退回旧形状**（会话宣告照发，新键置 null）。
        // 放在这里而不是各调用方：手机、agentNode、桌面内嵌三个发送方共用这一层，
        // 门控写两遍迟早有一处漏 —— 漏了就是把对端没承诺过的能力替它宣称出去。
        val withWs = resolvePeer.peerDeclares(peerId, MsgTypes.CAP_AGENT_WORKSPACE)
        return messenger.sendUserMessage(
            peerId,
            makeEnvelope(
                MsgTypes.AGENT_SESSION,
                selfId(),
                Codec.agentSessionPayload(
                    sessionId, alias, agentKind, state, title, startedAt,
                    agentId = if (withWs) agentId else null,
                    wsId = if (withWs) wsId else null,
                    wsTitle = if (withWs) wsTitle else null,
                    wsPath = if (withWs) wsPath else null,
                    // 不跟 `withWs` 门控：活动时刻属于**会话**，不属于工作区身份，
                    // 与 `startedAt` 同一档（对端若还不认识这个尾键，`tolerantKeys` 会容忍它）。
                    updatedAt = updatedAt,
                    // 与 `updatedAt` 同一档：可见性是**会话自身**的属性，不是工作区身份，
                    // 所以不跟 `withWs` 门控。
                    origin = origin,
                    blank = blank,
                ),
            ),
        )
    }

    /** 发一条 agent 事件（PPE-1 §6.2，类型 `agent-event`）。语义同 [sendAgentSession]。 */
    suspend fun sendAgentEvent(
        peerId: String,
        sessionId: String,
        cursor: Long,
        eventType: String,
        summary: String? = null,
        body: String? = null,
        ref: String? = null,
    ): SendOutcome = messenger.sendUserMessage(
        peerId,
        makeEnvelope(
            MsgTypes.AGENT_EVENT,
            selfId(),
            Codec.agentEventPayload(sessionId, cursor, eventType, summary, body, ref),
        ),
    )

    suspend fun sendAgentQueue(
        peerId: String,
        taskId: String,
        sessionId: String? = null,
        op: String,
        prompt: String? = null,
        queuedAt: Long? = null,
    ): SendOutcome = messenger.sendUserMessage(
        peerId,
        makeEnvelope(
            MsgTypes.AGENT_QUEUE,
            selfId(),
            Codec.agentQueuePayload(taskId, sessionId, op, prompt, queuedAt),
        ),
    )

    suspend fun sendAgentRun(
        peerId: String,
        sessionId: String,
        runId: String,
        state: String,
        at: Long? = null,
        note: String? = null,
    ): SendOutcome = messenger.sendUserMessage(
        peerId,
        makeEnvelope(
            MsgTypes.AGENT_RUN,
            selfId(),
            Codec.agentRunPayload(sessionId, runId, state, at, note),
        ),
    )

    suspend fun sendAgentApproval(
        peerId: String,
        approvalId: String,
        sessionId: String,
        tool: String? = null,
        summary: String? = null,
        expiresAt: Long,
        choices: String? = null,
        riskLevel: String? = null,
        diff: String? = null,
    ): SendOutcome = messenger.sendUserMessage(
        peerId,
        makeEnvelope(
            MsgTypes.AGENT_APPROVAL,
            selfId(),
            Codec.agentApprovalPayload(approvalId, sessionId, tool, summary, expiresAt, choices, riskLevel, diff),
        ),
    )

    suspend fun sendAgentCancel(
        peerId: String,
        sessionId: String,
        runId: String? = null,
        reason: String? = null,
    ): SendOutcome = messenger.sendUserMessage(
        peerId,
        makeEnvelope(
            MsgTypes.AGENT_CANCEL,
            selfId(),
            Codec.agentCancelPayload(sessionId, runId, reason),
        ),
    )

    suspend fun sendAgentAck(
        peerId: String,
        ackOf: String,
        kind: String,
        at: Long? = null,
    ): SendOutcome = messenger.sendUserMessage(
        peerId,
        makeEnvelope(
            MsgTypes.AGENT_ACK,
            selfId(),
            Codec.agentAckPayload(ackOf, kind, at),
        ),
    )

    /**
     * 发一条产物元数据（PPE-1 §6.7，类型 `agent-artifact`，cap `agn1`）。
     *
     * 归 `agn1` 而不是 `agq1`：它和 §6.1/§6.2 一样只描述会话，不带控制语义。
     * **调用方必须先发 `file-msg`**，这里的 `fileId` 只是引用；本方法不代发二进制。
     */
    suspend fun sendAgentArtifact(
        peerId: String,
        sessionId: String,
        artifactId: String,
        name: String,
        fileId: String? = null,
        bytes: Long? = null,
        mime: String? = null,
    ): SendOutcome = messenger.sendUserMessage(
        peerId,
        makeEnvelope(
            MsgTypes.AGENT_ARTIFACT,
            selfId(),
            Codec.agentArtifactPayload(sessionId, artifactId, name, fileId, bytes, mime),
        ),
    )

    /**
     * 发一条工作区宣告（PPE-1 增补 D3，类型 `agent-workspace`，cap `aws1`）。
     *
     * **可靠但不入队**（[Messenger.sendReliableNoQueue]）：这帧必须在 RELIABLE 里才会被
     * 对端投进 `inbound`（`Messenger.onDatagram` 只投 RELIABLE 类型），但它是一份**状态快照**，
     * 不是"必须送达一次"的指令 —— 改名 / 新增 / 移除时本来就会重发。
     *
     * 为什么不能用 `sendUserMessage`：避免在失败时误将对端置为离线。
     *
     * @return 对端有没有 ACK。`false` = 这一台没收到（多半是旧版本），调用方如实回执，
     *         但**台账照记** —— 宣告的是本宿主的工作区，与某一台收没收到无关。
     */
    @Suppress("LongParameterList")
    suspend fun sendAgentWorkspace(
        peerId: String,
        wsId: String,
        wsTitle: String? = null,
        wsPath: String? = null,
        agentId: String? = null,
        agentKind: String? = null,
        online: Boolean? = null,
        sessionCount: Long? = null,
        at: Long? = null,
        order: Long? = null,
    ): Boolean = messenger.sendReliableNoQueue(
        peerId,
        makeEnvelope(
            MsgTypes.AGENT_WORKSPACE,
            selfId(),
            Codec.agentWorkspacePayload(wsId, wsTitle, wsPath, agentId, agentKind, online, sessionCount, at, order),
        ),
    )

    /**
     * 向宿主要一页会话清单（PPE-1 增补 D8，设备 → 宿主，cap `agp1`）。
     *
     * 用 [Messenger.sendReliableNoQueue] 而不是 [Messenger.sendUserMessage]：这帧是请求，
     * 对端不认识它时不该把对方标离线、更不该把一条请求入队到 7 天后再发
     * （与 agent-workspace 同一条纪律，见 ReliableNoQueueTest）。
     */
    suspend fun sendAgentSessionPage(
        peerId: String,
        wsId: String,
        offset: Long? = null,
        limit: Long? = null,
        at: Long? = null,
    ): Boolean = messenger.sendReliableNoQueue(
        peerId,
        makeEnvelope(
            MsgTypes.AGENT_SESSION_PAGE,
            selfId(),
            Codec.agentSessionPagePayload(wsId, offset, limit, at),
        ),
    )

    /** 发一条授权申请（PPE-1 增补 D7，设备 → 宿主，cap `agr1`）。 */
    suspend fun sendAgentGrantRequest(
        peerId: String,
        agentKey: String? = null,
        reason: String? = null,
        at: Long? = null,
    ): SendOutcome = messenger.sendUserMessage(
        peerId,
        makeEnvelope(
            MsgTypes.AGENT_GRANT_REQUEST,
            selfId(),
            Codec.agentGrantRequestPayload(agentKey, reason, at),
        ),
    )

    /** 发一条授权决定（PPE-1 增补 D7，宿主 → 设备，cap `agr1`）。 */
    suspend fun sendAgentGrant(
        peerId: String,
        state: String,
        scope: String,
        agentKey: String? = null,
        at: Long? = null,
    ): SendOutcome = messenger.sendUserMessage(
        peerId,
        makeEnvelope(
            MsgTypes.AGENT_GRANT,
            selfId(),
            Codec.agentGrantPayload(state, scope, agentKey, at),
        ),
    )

    /** 发一条授权清单条目（PPE-1 增补 D7，宿主 → 设备，cap `agr1`）。 */
    suspend fun sendAgentGrantList(
        peerId: String,
        scope: String,
        agentKey: String? = null,
        total: Long? = null,
        at: Long? = null,
    ): SendOutcome = messenger.sendUserMessage(
        peerId,
        makeEnvelope(
            MsgTypes.AGENT_GRANT_LIST,
            selfId(),
            Codec.agentGrantListPayload(scope, agentKey, total, at),
        ),
    )

    suspend fun sendAvatarGet(peerId: String, hash: String): SendOutcome = messenger.sendUserMessage(
        peerId,
        makeEnvelope(
            MsgTypes.AVATAR,
            selfId(),
            Codec.avatarGetPayload(hash),
        ),
    )

    suspend fun sendAvatarData(peerId: String, hash: String, dataBase64: String): SendOutcome = messenger.sendUserMessage(
        peerId,
        makeEnvelope(
            MsgTypes.AVATAR,
            selfId(),
            Codec.avatarDataPayload(hash, dataBase64),
        ),
    )

    suspend fun sendAvatarMiss(peerId: String, hash: String): SendOutcome = messenger.sendUserMessage(
        peerId,
        makeEnvelope(
            MsgTypes.AVATAR,
            selfId(),
            Codec.avatarMissPayload(hash),
        ),
    )
}
