// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.nowMillis
import com.kapp.tenon.core.protocol.AgentExecAuthorizer
import com.kapp.tenon.core.protocol.AgentSenderId
import com.kapp.tenon.core.protocol.ConvId
import com.kapp.tenon.core.protocol.Envelope
import com.kapp.tenon.core.protocol.VirtualAgentMember
import com.kapp.tenon.core.protocol.newId
import com.kapp.tenon.domain.model.MessageUi
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 看板卡的**动作域**：资格判定（§4.8）、原子记账（§7.3-2）、rev 串行分配、全群广播、
 * 终态翻面、合流反查。
 *
 * ## 为什么单独成类
 *
 * 这一块原来长在 `RelayControllerAgentInvokeComponent`（1557 行）的后半截。它与派单
 * 侧共用同一个宿主缝 [AgentDomainHost]，但**判据完全不同**：派单管「谁去执行、租约
 * 什么时候到期」，动作域管「这一次点击算不算数、卡片该翻到哪一版」。混在一个类里
 * 时，rev 分配的串行纪律（见 [cardBroadcastMutex]）永远藏在 900 行之外，改派单的人
 * 看不出自己动到了卡片收敛的命门。
 *
 * 搬移是逐字的：除了「读本类字段」变成「读 [port]」（见 [Port] 的 KDoc）与三处
 * 取值器化，方法体一字未改 —— 计划书 ARP-7 的判据是「既有判据零改动」。
 *
 * ## 它不碰派单
 *
 * 重试/降级要重派任务，但派单（登记租约、发帧、本机执行）不在本类：一律经 [Port]
 * 回调给派单侧。反过来派单侧要落一张标签看板卡，也走 [rememberTagBoard] 这一条写入口 ——
 * 卡片的生命周期只有本类一个主人。
 */
internal class RelayControllerAgentCardActions(
    private val host: AgentDomainHost,
    private val port: Port,
) {

    /**
     * 动作域对外部世界（派单侧与壳层桥）的**全部**依赖。
     *
     * 十一个成员没有一个是「构造期拷贝的值」——全部是现读的取值器。这不是洁癖：
     * `peerTagRouter` / `channelDispatch` / `grantAllows` / `execPolicyOf` /
     * `onHostCardAction` 都是壳层在装配完成**之后**才注入的可写桥（有的还会被重挂），
     * 构造期把它们拍成快照，卡片动作就会永远看到一个 `null` 桥，且表现出来是
     * 「点了没反应」而不是异常 —— 与控制面重挂那次是同一类坑。
     *
     * 名字全部与实现类的字段区分开（`leaseTable` vs `leases`、`publishAgentState` vs
     * `broadcastAgentState` …）：同名 `override` 会写成自递归，编译器只报栈溢出、
     * 不报错。
     */
    internal interface Port {
        /** 派单租约表的现读视图（重试/降级按 `invoke-<id>` 反查 lease）。 */
        val leaseTable: AgentInvokeLeaseTable

        /** 按算力标签选一个远端节点；null = 没节点挂着这个标签。 */
        fun routeTag(tag: String): String?

        /** 发起侧 agent-channel 帧出口；桥没装时静默（与既有降级一致）。 */
        fun dispatchChannelToNode(nodeId: String, channelId: String, tag: String, task: String, deadlineMs: Long)

        /** 台账放行判定（null 桥 = 谁都不放行，与 `grantAllows?.invoke(...) == true` 同义）。 */
        fun grantAllowsNode(agentKey: String, nodeId: String): Boolean

        /** 该 agent 的执行策略档（null = 台账没给，交给授权器按最严档判）。 */
        fun execPolicyKey(agentKey: String): String?

        /** 把「这次没做成」播成 `agentState=failed` 轻横幅。 */
        fun announceGaveUp(groupId: String, agentId: String, agentName: String, dispatchId: String?, unavailable: Boolean)

        /** 重派一次任务（登记租约 + 发帧 / 本机执行），卡片由被派方另行翻面。 */
        fun dispatchTaskToAgent(groupId: String, agentId: String, prompt: String, wsId: String?)

        /** 1:1 直派重派（无群容器）：目标与 agent 显式给定，租约状态走镜像。 */
        fun dispatchDirectInvoke(peerId: String?, agentId: String, prompt: String, wsId: String?)

        /** 降级本机执行，终态翻租约；有群则广播结果卡，1:1 只翻镜像。 */
        suspend fun runLocallyAndMark(
            invokeId: String,
            agentId: String,
            prompt: String,
            wsId: String?,
            cardGroupId: String?,
        )

        /** 以 agent 署名发一条执行状态群文本。 */
        suspend fun publishAgentState(groupId: String, agentId: String, state: String, text: String)

        /** 判定帧落地后交给壳层（插件 / 合并 / 终止……），见 `onHostCardAction`。 */
        fun notifyHostCardAction(agentId: String, cardId: String, actionId: String, decidedBy: String)
    }

    /** 审批防重台账（§7.3-2）：键 = `(cardId, actionId)`，首个 agent-ack 原子生效。 */
    internal val actionLedger = AgentCardActionLedger()

    /** 标签看板卡（T-G4）：cardId → 重试/降级所需的全量上下文。 */
    private val tagBoards = LockedMap<String, TagBoardEntry>()

    private data class TagBoardEntry(
        val groupId: String,
        val agentId: String,
        val seat: VirtualAgentMember,
        val task: String,
        val wsId: String?,
        val tag: String,
    )

    /**
     * 标签无匹配时派单侧落一张兜底卡（R3，T-G4）—— 卡片的写入口在本类，读（重试
     * 路由 / 降级本机）也在这里，所以上下文跟着卡片走而不是跟着派单走。
     */
    fun rememberTagBoard(
        cardId: String,
        groupId: String,
        agentId: String,
        seat: VirtualAgentMember,
        task: String,
        wsId: String?,
        tag: String,
    ) {
        tagBoards[cardId] = TagBoardEntry(groupId, agentId, seat, task, wsId, tag)
    }

    // ---------------------------------------------------------------- 看板卡广播

    /**
     * 卡片广播的写入闸：**rev 分配 + 本机落库**是同一个原子段落。
     *
     * 没有它的时候，两条并发广播会各自从同一个 `renderState` 快照算出同一个 rev。
     * 现场：点「合并此补丁」后宿主同时发两张 —
     * `broadcastDecision` 的「已由…处理」帧（rev 5→6）与合并失败的如实翻面帧
     * （同样 5→6）。手机端只认**严格递增**的 rev，后到的诚实翻面被当旧帧丢掉，
     * 于是宿主日志写着 `patch merge FAILED`、用户面前的卡却永远停在「待合并」。
     *
     * 锁只包「读 rev → 算 rev → 落库」，不包网络发送：发送顺序不需要与 rev 同序，
     * 手机端最终停在最高 rev 那一帧，这才是收敛判据（也避免把慢端口的 I/O 串起来）。
     */
    private val cardBroadcastMutex = Mutex()

    /**
     * 把一张看板卡以 agent 署名的群文本发进群（`card` 键已在白名单，不新增协议键）。
     * 本机落库与线上同帧同 id —— 补发与去重都按信封 id 对账。
     */
    internal suspend fun broadcastBoardCard(groupId: String, agentId: String, card: JsonObject) {
        val st = host.state.value
        val isGroup = ConvId.isGroup(groupId) || st.settings.groups.any { it.groupId == groupId }
        val rawGroupId = ConvId.groupIdOf(groupId) ?: groupId
        val meta = if (isGroup) {
            st.settings.groups.firstOrNull { it.groupId == rawGroupId } ?: run {
                println("[CARD] dropped: group " + groupId + " not in settings")
                return
            }
        } else null

        if (meta != null && st.selfNodeId !in meta.members) {
            println(
                "[CARD] dropped: self " + st.selfNodeId + " is not a member of " + groupId +
                    " (members=" + meta.members.joinToString(",") + ")",
            )
            return
        }
        val title = card.strField("title").orEmpty().ifBlank { card.strField("cardId").orEmpty() }
        if (title.isBlank()) {
            println("[CARD] dropped: card has neither title nor cardId")
            return
        }
        val envelopeId = newId()
        val convKey = if (meta != null) ConvId.group(meta.groupId) else groupId
        // T-2d 单卡槽（§6.2 / 不变量 3）：本机广播的 rev 推进帧，**本机的落库**也走
        // 原位替换 —— 找同 cardId 的既有槽位，同 id 重写（card 首帧原文不动、
        // cardPayload = 最新），不是追加新行。远端成员的替换由他们的入站拦截
        // （RelayControllerMessagesComponent 的 T-2d 分支）对称完成。
        // 没有 cardId（理论不发生：看板卡必带）或首帧（无同 cardId 槽位）才走追加。
        //
        // rev 的分配与落库必须落在**同一把锁**里（见 [cardBroadcastMutex]）。
        val cardId = (card["cardId"] as? JsonPrimitive)?.contentOrNull
        val finalCard = cardBroadcastMutex.withLock {
            val now = host.state.value
            val existingSlot = cardId?.let { id ->
                now.messages[convKey].orEmpty().lastOrNull { row ->
                    (row.renderCard?.get("cardId") as? JsonPrimitive)?.contentOrNull == id
                }
            }
            val existingRev = (existingSlot?.renderCard?.get("rev") as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
            val passedRev = (card["rev"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 1
            // **有槽位就严格递增**：`maxOf(传入 rev, 既有 + 1)`。
            // 只判 `passedRev <= existingRev` 时，两个并发广播会各自从同一个既有 rev
            // 算出同一个新 rev（真机现场：判定帧与终态翻面帧同为 rev 6），手机端只认
            // 严格递增，后到的诚实翻面被当旧帧丢弃 —— 宿主日志写着合并失败，卡面却
            // 永远停在「待合并」。保留 `maxOf` 是为了不压回放/补发带进来的更高 rev。
            //
            // 台账是第三个下限（root cause #3）：`existingSlot` 来自启动恢复的 200 条
            // 窗口 + 「恢复已跑完」的时机，重启后卡片行落在窗口之外时它就是 null，
            // 旧逻辑会把传入的低 rev 原样放行，手机判旧帧丢弃且宿主无失败日志。
            // 库里的台账（`host.messageStore.loadBoardCardRev`）不依赖窗口与恢复时机。
            val storedRev = cardId?.let { id ->
                runCatching { host.messageStore.loadBoardCardRev(id) }.getOrNull()
            } ?: 0
            val finalRev = if (existingSlot != null) {
                maxOf(passedRev, existingRev + 1, storedRev + 1)
            } else {
                maxOf(passedRev, storedRev + 1)
            }
            val out = if (finalRev != passedRev) {
                JsonObject(card.toMap() + ("rev" to JsonPrimitive(finalRev)))
            } else {
                card
            }
            if (cardId == null || existingSlot == null) {
                // 首帧：本机落库 id 与线上信封 id **同一个** —— 补发与去重按信封 id 对账
                // （同帧同 id 是既有判据，拆开就丢掉这层对账）。
                val lastAt = now.messages[convKey].orEmpty().lastOrNull()?.at ?: 0L
                val effectiveAt = maxOf(nowMillis(), lastAt + 1L)
                host.handleAppendMessage(
                    MessageUi(
                        id = envelopeId,
                        peerId = convKey,
                        mine = false,
                        text = title,
                        at = effectiveAt,
                        delivery = null,
                        senderId = AgentSenderId.of(st.selfNodeId, agentId),
                        card = out,
                    ),
                    incoming = true,
                )
            } else {
                host.handleReplaceMessage(
                    existingSlot.peerId,
                    existingSlot.copy(
                        text = title,
                        at = maxOf(existingSlot.at, nowMillis()),
                        cardPayload = out,
                    ),
                )
            }
            // 台账与落库同段落（同一把锁）：rev 推进了多少，台账就记多少。
            // 台账写失败不许打断广播（它只是下限来源之一），但必须留痕 —— 静默失败
            // 会让下一次重启退化回「窗口外 rev 归零」的旧缺陷而无从排查。
            if (cardId != null) {
                runCatching { host.messageStore.saveBoardCardRev(cardId, finalRev) }.onFailure { t ->
                    println(
                        "[CARD] rev ledger save FAILED card=" + cardId + " rev=" + finalRev +
                            " : " + (t.message ?: t::class.simpleName),
                    )
                }
            }
            out
        }
        // **先落库、再发帧**（与 `broadcastAgentState` 同一条纪律）。
        if (meta != null) {
            val rt = host.runtime ?: run {
                println(
                    "[CARD] persisted locally, not broadcast: no runtime attached (card " +
                        cardId + ")",
                )
                return
            }
            runCatching {
                println(
                    "[CARD] broadcast card=" + cardId + " rev=" +
                        (finalCard["rev"] as? kotlinx.serialization.json.JsonPrimitive)?.content +
                        " bytes=" + finalCard.toString().encodeToByteArray().size,
                )
                rt.sendGroupText(
                    text = title,
                    groupId = meta.groupId,
                    groupRev = meta.rev,
                    members = meta.members,
                    senderId = AgentSenderId.of(st.selfNodeId, agentId),
                    envelopeId = envelopeId,
                    card = finalCard,
                )
            }.onFailure { t ->
                println(
                    "[CARD] broadcast FAILED card=" + cardId + " : " +
                        (t.message ?: t::class.simpleName),
                )
            }
        } else {
            // **1:1 直派（无群容器）也必须发到发起方。**
            //
            // 这里原先只打一行 `persisted locally` 就结束 —— 卡落进了宿主自己的库，
            // 发起方手机上一条都没有：工单真执行 20.8 s、五帧 rev 全部「落库成功」，
            // 用户界面一字不变（真机 A22 现场：手机端零入站行）。
            //
            // 单聊文本帧在协议层**不做字段白名单**（`CodecMsg.validateMsg` 对非
            // `group-text` 直接放行），卡片挂同一帧即可；收端
            // `RelayControllerMessagesComponent` 的 `payload?.get("card")` 不看群/单聊，
            // 所以这条路不需要新增协议键、也不需要动白名单。
            //
            // 信封 id 与本地槽位同一个（与群路径、`broadcastAgentState` 同一条纪律）。
            val peer = convKey.takeIf { !it.startsWith("agent:") && !it.startsWith("ws-") }
            val rt = host.runtime
            if (peer == null || rt == null) {
                println(
                    "[CARD] 1:1 card persisted locally, not sent: " +
                        (if (rt == null) "no runtime" else "conv $convKey is not a peer") +
                        " (card=" + cardId + ")",
                )
            } else {
                runCatching {
                    rt.sendDirectText(
                        peerId = peer,
                        text = title,
                        card = finalCard,
                        envelopeId = envelopeId,
                        // 卡片动作的回程地址：接收端从 `senderId` 解出宿主 nodeId，
                        // 才知道「合并/打回」该发回哪台机器。不带 = 按钮是死的。
                        senderId = AgentSenderId.of(st.selfNodeId, agentId),
                    )
                    println("[CARD] 1:1 direct card sent (conv=" + convKey + " card=" + cardId + ")")
                }.onFailure { t ->
                    println(
                        "[CARD] 1:1 direct card send FAILED (conv=" + convKey + " card=" + cardId +
                            ") : " + (t.message ?: t::class.simpleName),
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------- 卡片动作（任务 4/5）

    /**
     * 卡片动作的领域入口（两壳同源，参数接原语）：
     * 本机是卡片宿主 → 原子记账 + 插件 + 广播；宿主在别处 → `op=action` 的 invoke 帧直达。
     * 控制意图**绝不**拼成群文本（§8 的零中间文本判据同样管动作）。
     */
    fun handleCardAction(groupId: String, cardId: String, cardRev: Int, actionId: String) {
        val st = host.state.value
        val self = st.selfNodeId
        val cardRow = cardRowOf(cardId, groupId) ?: return
        val (hostNode, agentId) = AgentSenderId.parse(cardRow.senderId) ?: return
        if (hostNode == null || agentId == null) return
        if (hostNode == self) {
            handleHostCardAction(groupId, agentId, cardId, actionId, decidedBy = self)
        } else {
            val rt = host.runtime ?: return
            host.scope.launch {
                runCatching {
                    rt.sendAgentInvoke(
                        peerId = hostNode,
                        invokeId = newId(),
                        op = com.kapp.tenon.core.protocol.CodecInvoke.InvokeOps.ACTION,
                        agentId = agentId,
                        requestedBy = self,
                        deadlineMs = ACTION_INVOKE_DEADLINE_MS,
                        actionId = actionId,
                        cardId = cardId,
                        cardRev = cardRev,
                        decidedBy = self,
                        groupId = groupId,
                    )
                }
            }
        }
    }

    /** 入站 `op=action`：决定人必须与发送节点同源，然后走与本地点击同一条宿主处理。 */
    internal fun handleInvokeAction(envelope: Envelope, p: JsonObject, invokeId: String) {
        val agentId = p.strField("agentId") ?: return
        val cardId = p.strField("cardId") ?: return
        val actionId = p.strField("actionId") ?: return
        val decidedBy = p.strField("decidedBy") ?: return
        val rawGroupId = p.strField("groupId") ?: return
        // 冒名判定：说「X 批了」的帧必须由 X 自己的节点发出 —— 否则任何人都能替全群批准。
        if (decidedBy != envelope.from) return
        // **帧里的 groupId 是发起方视角的会话 id**：1:1 时它就是**本机 nodeId**。
        // 本机拿它当自己的会话键，卡片行永远查不到（`broadcastDecision` 的 `?: return`），
        // 点击落到「静默丢弃」，而发起方界面上早已是「已提交，等待宿主确认」——
        // 真机 A22 现场：点「合并此补丁」，宿主 dedup 里有这条帧，什么都没发生。
        // 真群照旧；不是本机已知的群 ⇒ 归一成「与发起方的 1:1 会话」。
        val groupId = if (host.state.value.settings.groups.any { it.groupId == rawGroupId }) {
            rawGroupId
        } else {
            com.kapp.tenon.core.protocol.ConvId.single(envelope.from)
        }
        handleHostCardAction(groupId, agentId, cardId, actionId, decidedBy)
        // 动作帧不需要逐帧回执：全群广播的那张 rev+1 卡就是结果（§7.3-2）。
        run { invokeId }
    }

    /**
     * 宿主侧动作处理：**原子**吃下首个判定（§7.3-2），转发插件，rev+1 新帧广播全群。
     * 晚到的并发点击在这里被幂等丢弃 —— 各端按钮的最终一致由广播保证。
     */
    internal fun handleHostCardAction(groupId: String, agentId: String, cardId: String, actionId: String, decidedBy: String) {
        // **资格门（§4.8）**：判定在宿主侧 —— 资格台账在宿主手上，客户端算不出远端资格。
        // 此前这里只判「decidedBy 是不是帧的发送者」（防冒名），于是任何群成员都能点
        // 别人卡片上的审批/暂停按钮，宿主照单全收。
        if (!mayDriveCard(groupId, agentId, decidedBy)) {
            announceCardActionDenied(groupId, agentId, cardId, actionId, decidedBy)
            return
        }
        val won = actionLedger.firstWin(cardId, actionId, decidedBy)
        if (!won) return
        // 看板卡的网格级动作（重试 / 降级）不走插件：它们是 Leader 自己的命令。
        when (actionId) {
            TagBoardActions.RETRY -> {
                val board = tagBoards[cardId]
                if (board != null) {
                    broadcastDecision(cardId, actionId, groupId, agentId, decidedBy)
                    // 重试 = 重新过一遍路由：节点可能已上线；仍无匹配 ⇒ 再次挂起成新卡。
                    val routed = port.routeTag(board.tag)
                    if (routed != null) {
                        val channelId = "ch-" + newId().take(20)
                        port.dispatchChannelToNode(
                            routed,
                            channelId,
                            board.tag,
                            board.task,
                            RelayControllerAgentInvokeComponent.DEFAULT_INVOKE_DEADLINE_MS,
                        )
                    } else {
                        // 仍无匹配：如实再报一次（去重键用 cardId —— 同一张卡的重试只出一条横幅）。
                        host.scope.launch {
                            runCatching {
                                port.announceGaveUp(board.groupId, board.agentId, board.seat.name, cardId, unavailable = true)
                            }
                        }
                    }
                    return
                }
            }
            TagBoardActions.FALLBACK_LOCAL -> {
                val board = tagBoards[cardId]
                if (board != null) {
                    broadcastDecision(cardId, actionId, groupId, agentId, decidedBy)
                    host.scope.launch {
                        port.runLocallyAndMark(
                            invokeId = cardId + "-local",
                            agentId = board.agentId,
                            prompt = board.task,
                            wsId = board.wsId,
                            cardGroupId = board.groupId,
                        )
                    }
                    return
                }
            }
            InvokeBoardActions.RETRY -> {
                val lease = port.leaseTable.snapshot().firstOrNull { boardCardIdOf(it.invokeId) == cardId }
                if (lease != null) {
                    broadcastDecision(cardId, actionId, groupId, agentId, decidedBy)
                    val group = lease.groupId
                    if (group != null) {
                        port.dispatchTaskToAgent(group, lease.agentId, lease.prompt, lease.wsId)
                    } else {
                        // 1:1 直派的租约：重派回原目标，不经过群席位。
                        port.dispatchDirectInvoke(lease.targetNode, lease.agentId, lease.prompt, lease.wsId)
                    }
                    return
                }
            }
            InvokeBoardActions.FALLBACK_LOCAL -> {
                val lease = port.leaseTable.snapshot().firstOrNull { boardCardIdOf(it.invokeId) == cardId }
                if (lease != null) {
                    broadcastDecision(cardId, actionId, groupId, agentId, decidedBy)
                    host.scope.launch {
                        port.runLocallyAndMark(
                            invokeId = lease.invokeId + "-local",
                            agentId = lease.agentId,
                            prompt = lease.prompt,
                            wsId = lease.wsId,
                            cardGroupId = lease.groupId,
                        )
                    }
                    return
                }
            }
        }
        // **判定帧先于宿主处理**：`broadcastDecision` 发的是「已由 <设备> 处理」那张
        // 同 cardId 的 rev+1 帧，而 `onHostCardAction` 往往会紧接着发一张**终态翻面卡**
        // （合并成功 / 受阻 / 滞留失效）。两者的 rev 由 [cardBroadcastMutex] 串行分配，
        // 谁先入队谁拿小号；翻面卡必须压在判定帧之上，否则手机上停在「已由…处理」的
        // 中间态，终态永远追不上来（真机现场见 [cardBroadcastMutex] 的注释）。
        broadcastDecision(cardId, actionId, groupId, agentId, decidedBy)
        port.notifyHostCardAction(agentId, cardId, actionId, decidedBy)
    }

    /**
     * 谁能驱动这张卡上的动作（§4.8）：**宿主机主永远可以，其他成员必须过
     * [AgentExecAuthorizer]**（与派单同一份判定、同一份台账，不维护第二份名单）。
     *
     * 本机点击（`decidedBy == self`）直接放行：`handleHostCardAction` 只在本机是卡片
     * 宿主时被调用，而回环帧在运行时就被丢弃（`env.from == selfId` 不回环），
     * 所以「decidedBy == self」只可能是本人点的。
     */
    private fun mayDriveCard(groupId: String, agentId: String, decidedBy: String): Boolean {
        val st = host.state.value
        if (decidedBy == st.selfNodeId) return true
        val meta = st.settings.groups.firstOrNull { it.groupId == groupId }
        if (meta == null) {
            // **1:1 直派的卡没有群**：判据与 `handleInvokeTask` 的 1:1 分支同一条 ——
            // 发起方对本宿主持有该 agent 的执行授权就够（`grantAllows` 收窄到节点维度）。
            // 群成员身份与席位在这里都不适用：没有群可查。此前这里直接 `return false`，
            // 于是手机上点「合并此补丁」被宿主判「没有权限」，而 1:1 的拒绝横幅
            // （`broadcastAgentState` 需要群）又发不出去 —— 点了彻底没反应。
            return port.grantAllowsNode(agentId, decidedBy)
        }
        // **编排器代发的卡没有真实席位**：多棒链的卡署名 `tenon-chain`（DesktopStageChain），
        // 它不在 `virtualAgents` 里。按假名查台账/策略必然双双落空 → 掉进最严档 LIST →
        // 连本可以驱动工作区 agent 的人都被拒（现场：点链卡的「合并此补丁」，
        // 群里回「对端设备没有权限驱动 tenon-chain」，合并永远点不动）。
        //
        // 这与当年工单卡写死 `tenon-worker` 是同一个坑的镜像：那次是卡面写假名被
        // `AgentExecAuthorizer` 拒，改成了卡挂真实 agentKey；链卡天生没有单一 agent，
        // 所以在**这一侧**兜 —— 认不出的 agentId 按本机托管给这个群的 agent 判资格
        // （卡本身是这个群工作区的产物，谁能驱动工作区 agent 谁就能操作它的卡）。
        // 认得出（真实席位）⇒ 行为一字不变。
        val effectiveAgentId = if (meta.virtualAgents.orEmpty().any { it.agentId == agentId }) {
            agentId
        } else {
            meta.virtualAgents.orEmpty().firstOrNull { it.hostNodeId == st.selfNodeId }?.agentId ?: agentId
        }
        val verdict = AgentExecAuthorizer.authorize(
            senderId = decidedBy,
            group = meta,
            grantAllows = { node -> port.grantAllowsNode(effectiveAgentId, node) },
            policy = port.execPolicyKey(effectiveAgentId),
            selfNodeId = st.selfNodeId,
        )
        return with(AgentExecAuthorizer) { verdict.permitsExecution() }
    }

    /**
     * 被拒的点击**不许沉默**（M3 的既有裁决：按下去没反应比没有更糟）。
     *
     * 回一条 `agentState=failed` 轻横幅，指名「谁被拒了、驱动的是谁」—— 复用 S4-4 的
     * 同一条通路，不新增协议键、不动卡片的 rev（把卡片写成「已决」是假事实，
     * 比不决更糟）。
     *
     * **同一个人的同一次尝试只回一条**：否则任何人拿它就能刷屏。
     */
    private fun announceCardActionDenied(groupId: String, agentId: String, cardId: String, actionId: String, decidedBy: String) {
        if (!deniedAttempts.add("$cardId|$actionId|$decidedBy")) return
        host.scope.launch {
            val st = host.state.value
            val meta = st.settings.groups.firstOrNull { it.groupId == groupId }
            val actorName = decidedDisplayName(decidedBy, meta?.memberNicks, st.selfNodeId, st.settings.nick)
            val agentName = meta?.virtualAgents.orEmpty().firstOrNull { it.agentId == agentId }?.name ?: agentId
            val text = runCatching { host.uiTexts.agentActionDeniedNotice(actorName, agentName) }
                .getOrNull().orEmpty()
            port.publishAgentState(groupId, agentId, RelayControllerAgentInvokeComponent.AGENT_STATE_FAILED, text)
        }
    }

    /** 已经公告过的「被拒尝试」（键 = `cardId|actionId|decidedBy`），见 [announceCardActionDenied]。 */
    private val deniedAttempts = LockedSet<String>()

    /**
     * 判定结果的广播：**同 cardId、rev+1 的新帧**（原位刷新的存储语义，M1 定死：
     * 追加式新行，不重写旧行）。决定人显示名见 [decidedDisplayName]。
     */
    private fun broadcastDecision(cardId: String, actionId: String, groupId: String, agentId: String, decidedBy: String) {
        val st = host.state.value
        // T-2d：最新载荷看 renderCard（cardPayload ?: card）—— 原位刷新后 row.card
        // 永远停在首帧 rev，读它会算出重复的 currentRev（rev 永不推进的真 bug 现场）。
        val latest = cardRowOf(cardId, groupId)?.renderCard ?: return
        val currentRev = (latest["rev"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: return
        val meta = st.settings.groups.firstOrNull { it.groupId == groupId }
        val displayName = decidedDisplayName(decidedBy, meta?.memberNicks, st.selfNodeId, st.settings.nick)
        val next = JsonObject(
            latest.toMap() +
                ("rev" to JsonPrimitive(currentRev + 1)) +
                ("decidedBy" to JsonPrimitive(displayName)) +
                ("decidedActionId" to JsonPrimitive(actionId)),
        )
        host.scope.launch { broadcastBoardCard(groupId, agentId, next) }
    }

    /**
     * 按 cardId 找卡片行 —— **群会话与 1:1 会话两处都查**。
     *
     * 群卡落在 `group:<groupId>`，1:1 直派（`dispatchDirectInvoke`）的卡落在
     * **与对端的会话**（convKey = 对端 nodeId，没有 `group:` 前缀）。只查前者时，
     * 1:1 上的每一次卡片动作都走「查不到 ⇒ 静默 return」：手机上按钮已被壳层乐观置成
     * 「已提交，等待宿主确认」，宿主那边连一行日志都没有，合并永远不发生。
     */
    private fun cardRowOf(cardId: String, convKey: String): MessageUi? {
        val st = host.state.value
        return (st.messages[ConvId.group(convKey)].orEmpty() + st.messages[convKey].orEmpty())
            .lastOrNull { row -> (row.renderCard?.get("cardId") as? JsonPrimitive)?.contentOrNull == cardId }
    }

    private fun boardCardIdOf(invokeId: String): String = "invoke-" + invokeId

    private companion object {
        /** 卡片动作请求的期限：动作必须立刻被处理，等不了任务那个量级。 */
        const val ACTION_INVOKE_DEADLINE_MS: Long = 15_000
    }
}
