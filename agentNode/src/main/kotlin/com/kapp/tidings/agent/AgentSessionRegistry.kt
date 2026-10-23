// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * agentNode 进程内的 agent 会话台账（PPE-1 §6.1 `agent-session` / §6.2 `agent-event`，
 * P1 追加 §6.3 `agent-queue` / §6.4 `agent-run` / §6.5 `agent-approval` /
 * §6.6 `agent-cancel` / §6.7 `agent-artifact` / §6.8 `agent-ack`）。
 *
 * ## 为什么要有它
 *
 * agentNode 只**转发** agent 帧，不持有会话：
 *  - 出站帧走 `messenger.sendUserMessage`，**不写** `messageStore`（`NodeRuntime` 里那句
 *    「不写 messageStore」的注释就是这条纪律）；
 *  - 入站帧写的是 `messageStore`，但 `GET /v1/inbox` 是给宿主 agent 的**聊天增量**，
 *    里面混进 agent 帧对消费方就是一批没人认领的噪声。
 *
 * 于是「我这个节点上有哪些 agent 会话」在控制面上**根本问不到**，面板也就画不出来 ——
 * 会话确实在跑（客户端的 `relay.db` 里有 `AgentSessionEntity`/`AgentEventEntity`），
 * 但本机这一侧看不见。这个台账补的就是这个缺口：让 `GET /v1/agent/sessions` 有东西可答。
 *
 * ## P1 的六类帧为什么要一起记
 *
 * `agq1` 一旦宣告（见 `MsgTypes.AGENT_CAPS`），对端就会真的把 `agent-queue` / `agent-approval`
 * 之类发过来。如果本端只是"解码通过然后丢掉"，那 `agq1` 就是一句空头承诺 —— 用户看到的是
 * 审批请求石沉大海，日志里却一切正常。所以宣告与记账必须成对：这几张表就是「收到过、
 * 发过」的事实，也是 `GET /v1/agent/sessions` 的读数来源。
 *
 * ## 只活在内存里（有意）
 *
 * 重启即清空。这不是省事，是取舍：会话列表是**当前态**的视图，一个跨重启累积的表会越长
 * 越假（会话早就结束了，列表还留着「busy」），那比空列表更误导。要长期留存是客户端
 * `relay.db` 的职责，不是这里的。
 *
 * 线程安全：控制面用固定线程池（4 线程）处理请求，`runtime.inbound` 又在另一个协程里
 * 投递，两边都会写。故全部字段走 [ConcurrentHashMap]，并按 `(nodeId, sessionId)` 映射到
 * 一条**固定存在**的锁（见 [lockFor]）—— 锁不随会话淘汰而删除，理由见那里的 KDoc。
 *
 * @param maxSessions 会话台账上限；超出时淘汰 `updatedAt` 最老的会话，防止长时间运行下无界增长。
 * @param maxEventsPerSession 单会话保留的**每类**帧条数上限（事件、任务、执行、审批、取消各算一份）；
 *   只保留**最新**的若干条，因为面板展示的是「最近发生了什么」，而不是完整历史（完整流在客户端）。
 * @param maxAcks 全局保留的 `agent-ack` 条数上限。回执的键是 `(nodeId, ackOf)`，
 *   `ackOf` 是对方给的 msgId，**不落在任何会话下**，所以它没法跟着会话一起淘汰，
 *   只能单独按到达顺序做 FIFO —— 否则一条长跑的链路会把这张表撑成无界。
 */
class AgentSessionRegistry(
    private val maxSessions: Int = 200,
    private val maxEventsPerSession: Int = 200,
    private val maxAcks: Int = 500,
    private val onRecordSession: ((SessionRecord) -> Unit)? = null,
    private val onRecordEvent: ((EventRecord) -> Unit)? = null,
) {

    /** 一条 agent 会话的当前态。字段名对齐 PPE-1 §6.1 与客户端 `AgentSessionEntity`。 */
    data class SessionRecord(
        val nodeId: String,
        val sessionId: String,
        val alias: String?,
        val agentKind: String?,
        val state: String?,
        val title: String?,
        val startedAt: Long?,
        // PPE-1 增补 D1/D6 的四个可选键（agentId / wsId / wsTitle / wsPath）。
        val agentId: String? = null,
        val wsId: String? = null,
        val wsTitle: String? = null,
        val wsPath: String? = null,
        /** 本节点**发出**过这个会话的宣告；与「收到过对方的宣告」是两件事。 */
        val announcedOut: Boolean,
        /** 收到过对方对这个会话的宣告。 */
        val announcedIn: Boolean,
        val updatedAt: Long,
    ) {
        /**
         * 「合成出来的会话」：两边都没宣告过它，它只是**从事件 / 队列 / 执行等帧推断**出来的
         * 占位（见 [ensureSession]）。它的 `state` 通常为 null —— 面板应据此降级显示，
         * 而不是当成一个正常会话（计数时也不该与真实会话同权）。
         *
         * 这是**派生**信号，不是独立字段：两个 announced 标记本来就已经透出给
         * `GET /v1/agent/sessions`，多存一个布尔只会多一个可能不同步的真源。
         */
        val synthesized: Boolean get() = !announcedOut && !announcedIn
    }

    /** 一条 agent 事件（PPE-1 §6.2）。 */
    data class EventRecord(
        val nodeId: String,
        val sessionId: String,
        val cursor: Long,
        val eventType: String,
        val summary: String?,
        val body: String?,
        val ref: String?,
        val at: Long,
    )

    /** 一条 `agent-queue` 任务（§6.3）。同一 `taskId` 重投就地替换。 */
    data class QueueRecord(
        val nodeId: String,
        val taskId: String,
        /** 为空表示「待分配到新会话」（§6.3 原文），此时它挂在空串键下。 */
        val sessionId: String?,
        val op: String,
        val prompt: String?,
        val queuedAt: Long?,
        val at: Long,
        val outbound: Boolean,
    )

    /**
     * 一条 `agent-run` 执行状态（§6.4）。
     *
     * 同一 `(sessionId, runId)` 重投就地替换：§6.4 的状态变更就是同一 runId 的再次宣告，
     * 追加会让面板把一个 run 显示成四条互相矛盾的记录。
     */
    data class RunRecord(
        val nodeId: String,
        val sessionId: String,
        val runId: String,
        val state: String,
        val at: Long?,
        val note: String?,
        val updatedAt: Long,
        val outbound: Boolean,
    )

    /**
     * 一条 `agent-approval` 审批请求（§6.5）。
     *
     * `status` 是**本端**的处置结果（`approved`/`denied`），由 §6.8 的 `agent-ack` 推进；
     * 对端没回执时它一直是 `null` —— 这正是 §10 要的「未确认优于已送达」。
     */
    data class ApprovalRecord(
        val nodeId: String,
        val sessionId: String,
        val approvalId: String,
        val tool: String?,
        val summary: String?,
        val expiresAt: Long,
        val choices: String?,
        val status: String?,
        val updatedAt: Long,
        val outbound: Boolean,
    )

    /** 一条 `agent-cancel`（§6.6）。取消是**动作**不是状态，故只追加、不去重。 */
    data class CancelRecord(
        val nodeId: String,
        val sessionId: String,
        /** 为空 = 取消整个会话（§6.6）。 */
        val runId: String?,
        val reason: String?,
        val at: Long,
        val outbound: Boolean,
    )

    /** 一条 `agent-ack`（§6.8）。 */
    data class AckRecord(
        val nodeId: String,
        val ackOf: String,
        val kind: String,
        val at: Long?,
        val recordedAt: Long,
        val outbound: Boolean,
    )

    /**
     * 一条 `agent-artifact` 产物元数据（§6.7）。
     *
     * **只有元数据，没有二进制** —— §6.7 原文「只描述产物，不传二进制」，内容按 [fileId]
     * 走 `file-msg`。所以这里存的是一行可展示的记录，不是一个可下载的文件。
     */
    data class ArtifactRecord(
        val nodeId: String,
        val sessionId: String,
        val artifactId: String,
        val fileId: String?,
        val name: String,
        val bytes: Long?,
        val mime: String?,
        val at: Long,
        val outbound: Boolean,
    )

    private data class Key(val nodeId: String, val sessionId: String)

    private val sessions = ConcurrentHashMap<Key, SessionRecord>()
    private val events = ConcurrentHashMap<Key, MutableList<EventRecord>>()
    private val queues = ConcurrentHashMap<Key, MutableList<QueueRecord>>()
    private val runs = ConcurrentHashMap<Key, MutableList<RunRecord>>()
    private val approvals = ConcurrentHashMap<Key, MutableList<ApprovalRecord>>()
    private val cancels = ConcurrentHashMap<Key, MutableList<CancelRecord>>()
    private val artifacts = ConcurrentHashMap<Key, MutableList<ArtifactRecord>>()
    /** `agent-ack` 的键是 `(nodeId, ackOf)` —— `ackOf` 是被确认的 msgId，不是 sessionId。 */
    private val acks = ConcurrentHashMap<Key, AckRecord>()
    private val ackOrder = ConcurrentLinkedQueue<Key>()
    /**
     * 每个键固定映射到一条锁。
     *
     * 以前这里是 `ConcurrentHashMap<Key, Any>` + `computeIfAbsent`，而 `evictIfNeeded` 会在
     * **锁外** `locks.remove(key)`：一个正在持锁的写者仍拿着旧对象，下一个调用者 `computeIfAbsent`
     * 出的却是**新**对象 —— 同一张 `MutableList` 于是被两把锁各自保护，`upsert`/`append` 里的
     * `list += ...` 与 `sortBy` 可能并发执行（`ConcurrentModificationException` 或丢写）。
     *
     * 条带化把这条竞态连根去掉：同一个键**永远**落在同一条带上（互斥语义与「每键一把锁」等价），
     * 条带的**数量固定** ⇒ 内存 O(1)、不需要任何删除。代价只是不同键偶尔共享一条带，略降并发。
     */
    private val lockStripes = Array(LOCK_STRIPES) { Any() }

    private fun lockFor(key: Key): Any =
        lockStripes[(key.hashCode() and Int.MAX_VALUE) % LOCK_STRIPES]

    /**
     * 记下一次会话宣告（§6.1）。
     *
     * 同 `(nodeId, sessionId)` 重复宣告是**正常**的：§6.1 明说宣告同时承担「状态变更」，
     * 所以这里做合并而不是新增一行 —— 后到的非空字段覆盖，空字段保留旧值（对端只报
     * `state` 变化时不该把 `title` 抹掉）。
     */
    @Suppress("LongParameterList")
    fun recordSession(
        nodeId: String,
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
        outbound: Boolean,
        at: Long = System.currentTimeMillis(),
    ) {
        val key = Key(nodeId, sessionId)
        synchronized(lockFor(key)) {
            val old = sessions[key]
            val record = SessionRecord(
                nodeId = nodeId,
                sessionId = sessionId,
                alias = alias ?: old?.alias,
                agentKind = agentKind ?: old?.agentKind,
                state = state ?: old?.state,
                title = title ?: old?.title,
                startedAt = startedAt ?: old?.startedAt,
                agentId = agentId ?: old?.agentId,
                wsId = wsId ?: old?.wsId,
                wsTitle = wsTitle ?: old?.wsTitle,
                wsPath = wsPath ?: old?.wsPath,
                announcedOut = (old?.announcedOut ?: false) || outbound,
                announcedIn = (old?.announcedIn ?: false) || !outbound,
                // `updatedAt` 是 `evictIfNeeded` 唯一的淘汰依据，故**只前进不后退**：
                // 乱序/重放到达的旧宣告（at 更小）不该把一个活跃会话拉回「最旧」那一端。
                updatedAt = maxOf(at, old?.updatedAt ?: at),
            )
            sessions[key] = record
            onRecordSession?.invoke(record)
        }
        evictIfNeeded()
    }

    /**
     * 记下一条会话事件（§6.2）。
     *
     * `cursor` 只在同一个 `(node, session)` 内单调（§8.2），所以去重键是
     * `(nodeId, sessionId, cursor)` —— 用时间戳或全局自增都会串会话。
     */
    fun recordEvent(
        nodeId: String,
        sessionId: String,
        cursor: Long,
        eventType: String,
        summary: String? = null,
        body: String? = null,
        ref: String? = null,
        at: Long = System.currentTimeMillis(),
    ) {
        val key = Key(nodeId, sessionId)
        val record = EventRecord(nodeId, sessionId, cursor, eventType, summary, body, ref, at)
        synchronized(lockFor(key)) {
            val list = events.computeIfAbsent(key) { mutableListOf() }
            // 同一游标重投是协议允许的（§8.2 的续读语义），就地替换而不是追加。
            val existing = list.indexOfFirst { it.cursor == cursor }
            if (existing >= 0) list[existing] = record else list += record
            list.sortBy { it.cursor }
            while (list.size > maxEventsPerSession) list.removeAt(0)
        }
        // 会话还没宣告就先来了事件（乱序到达）：补一条占位会话，否则面板只看到事件、看不到会话。
        ensureSession(nodeId, sessionId, at = at)
        evictIfNeeded()
        onRecordEvent?.invoke(record)
    }

    /**
     * 按 **(nodeId, sessionId)** 查找会话 —— 台账的键就是这两个（见 [Key]），查找也必须同键。
     *
     * task-60 审查的裁决：**签名必须带 nodeId**。理由不是「多一层保险」，而是三条硬的：
     *  1. 只按 sessionId 查与数据模型不一致 —— 跨设备的同 id 记录会被查出来，
     *     调用方拿它补 wsPath 再去匹配群 ⇒ 甲仓库的活干进乙仓库的群；
     *  2. 「只在调用点 fail-closed」只修了今天那一个调用点，却**留下 API 能把同一个 bug
     *     再引进来**，而且是静默的；
     *  3. 带 nodeId 的签名让错误调用**编译不过** —— 比任何运行时检查都早、都强。
     *
     * 优先返回带非空 wsPath 的那条（同一个 (node, session) 可能有多条宣告，命名帧通常更全）。
     */
    fun findSession(nodeId: String, sessionId: String): SessionRecord? =
        sessions.values.firstOrNull {
            it.nodeId == nodeId && it.sessionId == sessionId && !it.wsPath.isNullOrBlank()
        } ?: sessions.values.firstOrNull { it.nodeId == nodeId && it.sessionId == sessionId }

    /** 记下一次任务入队 / 出队（§6.3）。同一 `taskId` 重投就地替换。 */
    fun recordQueue(
        nodeId: String,
        taskId: String,
        sessionId: String? = null,
        op: String,
        prompt: String? = null,
        queuedAt: Long? = null,
        outbound: Boolean,
        at: Long = System.currentTimeMillis(),
    ) {
        // sessionId 为空 = 「待分配到新会话」，此时它不挂在任何会话下，用空串键收着。
        val key = Key(nodeId, sessionId ?: "")
        upsert(key, queues, { it.taskId == taskId }) {
            QueueRecord(nodeId, taskId, sessionId, op, prompt, queuedAt, at, outbound)
        }
        ensureSession(nodeId, sessionId, at)
        evictIfNeeded()
    }

    /** 记下一次执行状态（§6.4）。同一 `(sessionId, runId)` 重投就地替换。 */
    fun recordRun(
        nodeId: String,
        sessionId: String,
        runId: String,
        state: String,
        at: Long? = null,
        note: String? = null,
        outbound: Boolean,
        recordedAt: Long = System.currentTimeMillis(),
    ) {
        val key = Key(nodeId, sessionId)
        upsert(key, runs, { it.runId == runId }) {
            RunRecord(nodeId, sessionId, runId, state, at, note, recordedAt, outbound)
        }
        ensureSession(nodeId, sessionId, recordedAt)
        evictIfNeeded()
    }

    /**
     * 记下一次审批请求（§6.5）。
     *
     * 同一 `approvalId` 重投就地替换，但**已处置的状态不会被抹掉**：对端重复宣告同一个
     * `approvalId`（重传、补发）时，本端已经点过的「同意」不该退回 `null`。
     */
    fun recordApproval(
        nodeId: String,
        sessionId: String,
        approvalId: String,
        tool: String? = null,
        summary: String? = null,
        expiresAt: Long,
        choices: String? = null,
        status: String? = null,
        outbound: Boolean,
        at: Long = System.currentTimeMillis(),
    ) {
        val key = Key(nodeId, sessionId)
        upsert(key, approvals, { it.approvalId == approvalId }) { old ->
            ApprovalRecord(
                nodeId = nodeId,
                sessionId = sessionId,
                approvalId = approvalId,
                tool = tool ?: old?.tool,
                summary = summary ?: old?.summary,
                expiresAt = expiresAt,
                choices = choices ?: old?.choices,
                status = status ?: old?.status,
                updatedAt = at,
                outbound = outbound || (old?.outbound ?: false),
            )
        }
        ensureSession(nodeId, sessionId, at)
        evictIfNeeded()
    }

    /**
     * 用 §6.8 的回执推进审批状态。
     *
     * `ackOf` 是 `approvalId` 时才有意义；找不到对应审批（例如它已经被淘汰）就什么都不做，
     * 而不是凭空造一条 —— 回执描述的是**已经存在**的那件事。
     */
    fun applyAckToApproval(nodeId: String, ackOf: String, kind: String, at: Long = System.currentTimeMillis()) {
        val status = when (kind) {
            "approved" -> "approved"
            "allow_session" -> "allow_session"
            "denied" -> "denied"
            "cancelled" -> "cancelled"
            else -> return
        }
        approvals.forEach { (key, list) ->
            if (key.nodeId != nodeId) return@forEach
            synchronized(lockFor(key)) {
                val i = list.indexOfFirst { it.approvalId == ackOf }
                if (i >= 0) list[i] = list[i].copy(status = status, updatedAt = at)
            }
        }
    }

    /** 记下一次取消（§6.6）。只追加，不去重（取消是动作）。 */
    fun recordCancel(
        nodeId: String,
        sessionId: String,
        runId: String? = null,
        reason: String? = null,
        outbound: Boolean,
        at: Long = System.currentTimeMillis(),
    ) {
        val key = Key(nodeId, sessionId)
        append(key, cancels) { CancelRecord(nodeId, sessionId, runId, reason, at, outbound) }
        ensureSession(nodeId, sessionId, at)
        evictIfNeeded()
    }

    /** 记下一件产物元数据（§6.7）。同一 `(sessionId, artifactId)` 重投就地替换。 */
    fun recordArtifact(
        nodeId: String,
        sessionId: String,
        artifactId: String,
        name: String,
        fileId: String? = null,
        bytes: Long? = null,
        mime: String? = null,
        outbound: Boolean,
        at: Long = System.currentTimeMillis(),
    ) {
        val key = Key(nodeId, sessionId)
        upsert(key, artifacts, { it.artifactId == artifactId }) {
            ArtifactRecord(nodeId, sessionId, artifactId, fileId, name, bytes, mime, at, outbound)
        }
        ensureSession(nodeId, sessionId, at)
        evictIfNeeded()
    }

    /**
     * 记下一次回执（§6.8）。
     *
     * 回执同时用来推进 `agent-approval` 的处置状态（见 [applyAckToApproval]）—— §6.5 的
     * 「审批不得降级为已送达」要靠这条真实回执兑现。
     */
    fun recordAck(
        nodeId: String,
        ackOf: String,
        kind: String,
        at: Long? = null,
        outbound: Boolean,
        recordedAt: Long = System.currentTimeMillis(),
    ) {
        val key = Key(nodeId, ackOf)
        synchronized(lockFor(key)) {
            if (!acks.containsKey(key)) ackOrder += key
            acks[key] = AckRecord(nodeId, ackOf, kind, at, recordedAt, outbound)
            while (ackOrder.size > maxAcks) {
                val doomed = ackOrder.poll() ?: break
                acks.remove(doomed)
            }
        }
        applyAckToApproval(nodeId, ackOf, kind, recordedAt)
        evictIfNeeded()
    }

    /** 会话快照，`updatedAt` 降序（最近有动静的排前面），供面板列表直接用。 */
    fun sessions(): List<SessionRecord> =
        sessions.values.sortedByDescending { it.updatedAt }

    /** 某个会话的事件，按 `cursor` 升序。 */
    fun events(nodeId: String, sessionId: String): List<EventRecord> =
        events[Key(nodeId, sessionId)]?.toList() ?: emptyList()

    /** 某个会话（或「待分配」，`sessionId` 传 `null`）的任务，按 `queuedAt` 升序。 */
    fun queues(nodeId: String, sessionId: String? = null): List<QueueRecord> =
        queues[Key(nodeId, sessionId ?: "")]?.sortedBy { it.queuedAt ?: it.at } ?: emptyList()

    /** 某个会话的执行状态，按 `updatedAt` 降序（最近有动静的排前面）。 */
    fun runs(nodeId: String, sessionId: String): List<RunRecord> =
        runs[Key(nodeId, sessionId)]?.sortedByDescending { it.updatedAt } ?: emptyList()

    /** 某个会话的审批，按 `updatedAt` 降序。 */
    fun approvals(nodeId: String, sessionId: String): List<ApprovalRecord> =
        approvals[Key(nodeId, sessionId)]?.sortedByDescending { it.updatedAt } ?: emptyList()

    /** 某个会话的取消记录，按 `at` 降序。 */
    fun cancels(nodeId: String, sessionId: String): List<CancelRecord> =
        cancels[Key(nodeId, sessionId)]?.sortedByDescending { it.at } ?: emptyList()

    /** 某个会话的产物，按 `at` 降序（最近产出的排前面）。 */
    fun artifacts(nodeId: String, sessionId: String): List<ArtifactRecord> =
        artifacts[Key(nodeId, sessionId)]?.sortedByDescending { it.at } ?: emptyList()

    /** 某条被确认对象的回执（一般只有一条，重投会就地替换）。 */
    fun ack(nodeId: String, ackOf: String): AckRecord? = acks[Key(nodeId, ackOf)]

    /** 事件总数，供 healthz/诊断。 */
    fun eventCount(): Int = events.values.sumOf { it.size }

    /**
     * 数一个工作区名下的会话（PPE-1 增补 D3 的派生读数，供 AgentWorkspaceRegistry 用）。
     *
     * 匹配规则照父稿 §1 的归组键 `(nodeId, agentId ?? agentKind, wsId)`：
     * [agentKey] 为 null 时只按 wsId 数（对端没报 agent 身份，不猜归组）；
     * 报了就必须逐字相等 —— 同类两个 agent 的会话不许并成一个工作区的计数。
     */
    fun countWorkspaces(nodeId: String, agentKey: String?, wsId: String): Int =
        sessions.values.count { s ->
            s.nodeId == nodeId && s.wsId == wsId &&
                (agentKey == null || (s.agentId ?: s.agentKind) == agentKey)
        }

    // ------------------------------------------------------------- internals

    /**
     * 会话没宣告就先来了帧（乱序到达）：补一条占位会话，否则面板只看到帧、看不到会话。
     *
     * 除了「补一条」，这里还承担一件事：**给已存在的会话刷新 `updatedAt`**。
     * 它是 `evictIfNeeded` 唯一的淘汰依据，而以前**只有 `recordSession` 会推进它** —— 于是
     * 一个长时间只流事件、不重发宣告的会话，淘汰时被当成「最旧」的优先删掉，连它的事件一起丢，
     * 也就是**最活跃的会话最先消失**。六类帧都经过这里，所以刷新放在这里是全覆盖的
     * （回归测试：`AgentSessionRegistryEvictionProbe`）。
     *
     * 占位会话**不带任何「宣告过」的标记**：它是「从帧推断出这个会话存在」，不是对方宣告过它。
     * 旧写法把它当 `outbound = false` 的宣告走 `recordSession`，`announcedIn` 因此被置真 ——
     * 面板会以为对方主动宣告过。现在两个标记双双为假，正好就是「合成出来的会话」这个信号
     * （[SessionRecord.synthesized]，已由 `GET /v1/agent/sessions` 透出）。
     */
    private fun ensureSession(nodeId: String, sessionId: String?, at: Long) {
        if (sessionId == null) return
        val key = Key(nodeId, sessionId)
        synchronized(lockFor(key)) {
            val old = sessions[key]
            if (old == null) {
                sessions[key] = SessionRecord(
                    nodeId = nodeId,
                    sessionId = sessionId,
                    alias = null,
                    agentKind = null,
                    state = null,
                    title = null,
                    startedAt = null,
                    announcedOut = false,
                    announcedIn = false,
                    updatedAt = at,
                )
                // 占位会话的工作区四键留空：它是「从帧推断存在」，不是对端宣告过什么。
            } else if (at > old.updatedAt) {
                sessions[key] = old.copy(updatedAt = at)
            }
        }
        // 不在这里调 evictIfNeeded：六个调用方都在本函数之后各自调了一次。
    }

    /** 就地替换（有 `replaceIf` 命中的那条）或追加，并施加单会话条数上限。 */
    private fun <T> upsert(
        key: Key,
        map: ConcurrentHashMap<Key, MutableList<T>>,
        replaceIf: (T) -> Boolean,
        record: (old: T?) -> T,
    ) {
        synchronized(lockFor(key)) {
            val list = map.computeIfAbsent(key) { mutableListOf() }
            val i = list.indexOfFirst(replaceIf)
            if (i >= 0) list[i] = record(list[i]) else list += record(null)
            while (list.size > maxEventsPerSession) list.removeAt(0)
        }
    }

    /** 只追加（用于「取消」这类动作），并施加单会话条数上限。 */
    private fun <T> append(key: Key, map: ConcurrentHashMap<Key, MutableList<T>>, record: () -> T) {
        synchronized(lockFor(key)) {
            val list = map.computeIfAbsent(key) { mutableListOf() }
            list += record()
            while (list.size > maxEventsPerSession) list.removeAt(0)
        }
    }

    private fun evictIfNeeded() {
        if (sessions.size <= maxSessions) return
        val doomed = sessions.values.sortedBy { it.updatedAt }.take(sessions.size - maxSessions)
        doomed.forEach {
            val key = Key(it.nodeId, it.sessionId)
            sessions.remove(key)
            events.remove(key)
            queues.remove(key)
            runs.remove(key)
            approvals.remove(key)
            cancels.remove(key)
            artifacts.remove(key)
        }
    }

    private companion object {
        /**
         * 锁条带数。同一个键永远落在同一条带上 ⇒ 与「每键一把锁」的互斥语义等价；
         * 条带固定存在、永不删除 ⇒ 不会有「锁对象被换掉」的竞态（见 [lockFor]）。
         * 64 条足以让 4 线程控制面与入站协程几乎不撞车，同时保证内存 O(1)。
         */
        const val LOCK_STRIPES = 64
    }
}
