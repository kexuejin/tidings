// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * 存储契约。
 *
 * 这一层刻意先只有接口 + 内存实现：协议与传输的正确性可以完全用内存实现测出来，
 * 而 SQLDelight 的 `.sq` 代码生成（外加 JVM 侧还要 sqlite-jdbc）会拖慢第一次构建。
 * 真实持久化实现随后替换，接口不变。
 *
 * 与茶话间 TS 侧 `src/main/store/queue-repo.ts` 的语义逐条对齐：
 * - 队列以 (msg_id, peer_id) 为复合主键（同一条消息对不同 peer 是两行）；
 * - 按 `created` 升序取出，保证补发保序；
 * - 剪枝先按 TTL，再按每 peer 上限丢最旧。
 */

/** 队列里一条待补发的消息。 */
data class QueuedMessage(
    val msgId: String,
    val peerId: String,
    val envelopeJson: String,
    val created: Long,
)

/** 被剪枝掉的队列项 —— 上层据此把这些消息标记为 failed（延迟失败，不是即时失败）。 */
data class PrunedMessage(
    val msgId: String,
    val peerId: String,
    val reason: String,
)

const val PRUNE_REASON_TTL: String = "ttl"
const val PRUNE_REASON_OVERFLOW: String = "overflow"

/** 出站可靠消息的持久队列（对端离线时暂存，7 天内有效）。 */
interface QueueStore {
    fun enqueue(msgId: String, peerId: String, envelopeJson: String, created: Long)

    fun listByPeer(peerId: String): List<QueuedMessage>

    fun remove(msgId: String, peerId: String)

    fun countByPeer(): Map<String, Int>

    /**
     * 全部待发项（按入队先后升序）。
     *
     * 给「重启后重建 UI 的排队态」用：上层启动时要知道哪些消息还等着，才能把它们
     * 显示成「已排队」而不是「已发送」。没有它就只能靠 UI 自己在消息表里另存一份
     * 凭据 —— 那正是 `MessageUi.outgoingEnvelope` 曾经做的事。
     */
    fun pendingAll(): List<QueuedMessage>

    /**
     * 剪枝：先丢弃 `created < now - ttl` 的项，再对超过 `maxPerPeer` 的 peer 按 `created` 升序丢最旧。
     * @return 被剪掉的项，供上层标记 failed。
     */
    fun prune(now: Long, ttl: Long, maxPerPeer: Int): List<PrunedMessage>

    /**
     * **淘汰「目标设备已消失」的条目**；返回删掉的条数。
     *
     * ## 为什么它与 [prune] 是两件事
     *
     * [prune] 只看**时间**与**每 peer 条数**。两者都拦不住真机上的一类积压：目标节点
     * 早就不存在了，但条目不满 TTL、也没超过每 peer 上限 —— 于是它永远留在队列里。
     * 真机读数：`pending: 476`，其中 201 条的目标节点既不在 `peers`、
     * 也不在 `known-peers` 里；它恰好卡在 200 条限额上，TTL 也没到期。
     *
     * @param knownPeer 该 peerId 是不是一台**还可能回来**的设备（由上层用它掌握的
     *   peers + known-peers 回答）。
     * @param ttl 超过这么久没送出去才算「真的没了」—— 只是长期离线的设备回来时，
     *   那条消息本该还在。
     * @return 删掉的条数
     */
    fun pruneVanishedPeers(now: Long, ttl: Long, knownPeer: (String) -> Boolean): Int = 0
}

/** 入站去重（同一 msg_id + peer_id 只投递一次）。 */
interface DedupStore {
    fun has(msgId: String, peerId: String): Boolean

    fun add(msgId: String, peerId: String, at: Long)

    fun prune(now: Long, ttl: Long): Int
}

/** 会话历史。`seq` 是本地单调递增序号，供 `/v1/inbox?since=` 增量拉取。 */
interface MessageStore {
    fun append(peerId: String, direction: String, envelopeJson: String, at: Long): Long

    fun since(peerId: String, sinceSeq: Long, limit: Int = 200): List<StoredMessage>

    fun latestSeq(peerId: String): Long

    /**
     * 跨会话增量拉取，供 agentNode 的 `GET /v1/inbox?since=` 使用。
     *
     * `sinceSeq` 与 [since] 同一序号空间（每 peer 各自单调），所以跨 peer 的全局拉取
     * 只能按 `at`（写入时刻）排序后整体裁剪 —— 这里保持"按 at 升序 + take(limit)"的
     * 朴素语义，够用即可；真正的全量同步属于 P1 之后的事。
     */
    fun sinceAll(sinceAt: Long, limit: Int = 200): List<StoredMessage>

    /**
     * 丢掉**过期的**与**超量的**行（按 `at` 升序丢最旧的），返回丢掉的条数。
     *
     * ## 为什么必须有人调用它
     *
     * 这份日志是**追加写**的，之前没有任何上限：真机桌面端一天就长到
     * **12.9 万行 / 72 MB**，而同一时刻消息表只有 1235 条 —— 多出来的是 Agent 事件信封
     * （`agent-event` 的 body 可以到 64 KiB）。它只服务 `GET /v1/inbox?since=` 这一个
     * 只读出口，没有理由留全量历史。
     *
     * `ttl <= 0` = 不做时间裁剪（只按 [maxRows] 卡）。默认空实现：内存实现不需要上限。
     */
    fun trim(now: Long, ttl: Long, maxRows: Int): Int = 0
}

data class StoredMessage(
    val seq: Long,
    val peerId: String,
    val direction: String,
    val envelopeJson: String,
    val at: Long,
)

const val DIRECTION_IN: String = "in"
const val DIRECTION_OUT: String = "out"

// ---------------------------------------------------------------------------
// 内存实现
// ---------------------------------------------------------------------------

/**
 * 内存队列（测试/preview 用）。
 *
 * **必须线程安全**：群发是并发的 —— `sendGroupTextFanOut` 同时向每个成员各写一行，
 * 而那几行可能来自不同协程。此前这里是裸 `LinkedHashMap`，并发入队会丢更新：
 * 实测「两个成员的队列行」这条用例在全量跑里永远只看到 1 行（隔离跑恒绿），
 * 一度被误判成测试在赌时序。
 *
 * 用 `MutableStateFlow` 的 `update`（原子 CAS 循环）而不是锁：这是 commonMain，
 * 没有 `synchronized`；StateFlow 在 JVM/Native 上都是线程安全的。
 */
class InMemoryQueueStore : QueueStore {
    private val rows = MutableStateFlow<Map<String, QueuedMessage>>(emptyMap())

    private fun key(msgId: String, peerId: String) = "$peerId|$msgId"

    override fun enqueue(msgId: String, peerId: String, envelopeJson: String, created: Long) {
        rows.update { it + (key(msgId, peerId) to QueuedMessage(msgId, peerId, envelopeJson, created)) }
    }

    override fun listByPeer(peerId: String): List<QueuedMessage> =
        rows.value.values.filter { it.peerId == peerId }.sortedBy { it.created }

    override fun remove(msgId: String, peerId: String) {
        rows.update { it - key(msgId, peerId) }
    }

    override fun countByPeer(): Map<String, Int> =
        rows.value.values.groupingBy { it.peerId }.eachCount()

    override fun pendingAll(): List<QueuedMessage> =
        rows.value.values.sortedBy { it.created }

    override fun prune(now: Long, ttl: Long, maxPerPeer: Int): List<PrunedMessage> {
        // 整段在 `update` 里算：并发 enqueue 与 prune 交错时，先取快照再逐条 remove 会删到
        // 不存在的行（也漏删）。`update` 的 CAS 会在冲突时重跑 lambda，所以结果也要
        // 在 lambda 里重算，不能在外面累加。
        var pruned: List<PrunedMessage> = emptyList()
        rows.update { current ->
            val removed = mutableListOf<PrunedMessage>()
            var next = current
            for (row in current.values.filter { it.created < now - ttl }) {
                next = next - key(row.msgId, row.peerId)
                removed += PrunedMessage(row.msgId, row.peerId, PRUNE_REASON_TTL)
            }
            for ((_, list) in next.values.groupBy { it.peerId }) {
                if (list.size <= maxPerPeer) continue
                for (row in list.sortedBy { it.created }.take(list.size - maxPerPeer)) {
                    next = next - key(row.msgId, row.peerId)
                    removed += PrunedMessage(row.msgId, row.peerId, PRUNE_REASON_OVERFLOW)
                }
            }
            pruned = removed
            next
        }
        return pruned
    }
}

/** 内存去重表。同样**必须线程安全**：并发入站会同时 add（见 InMemoryQueueStore 的说明）。 */
class InMemoryDedupStore : DedupStore {
    private val seen = MutableStateFlow<Map<String, Long>>(emptyMap())

    private fun key(msgId: String, peerId: String) = "$peerId|$msgId"

    override fun has(msgId: String, peerId: String): Boolean = seen.value.containsKey(key(msgId, peerId))

    override fun add(msgId: String, peerId: String, at: Long) {
        seen.update { it + (key(msgId, peerId) to at) }
    }

    override fun prune(now: Long, ttl: Long): Int {
        var removed = 0
        seen.update { current ->
            val expired = current.filterValues { it < now - ttl }.keys
            removed = expired.size
            current - expired
        }
        return removed
    }
}

/** 内存消息存储。与 Queue/Dedup 同一条纪律：**必须线程安全**（收帧协程写、测试线程读）。 */
class InMemoryMessageStore : MessageStore {
    private data class State(
        val rows: List<StoredMessage> = emptyList(),
        val seqByPeer: Map<String, Long> = emptyMap(),
    )

    private val state = MutableStateFlow(State())

    override fun append(peerId: String, direction: String, envelopeJson: String, at: Long): Long {
        var seq = 0L
        state.update { current ->
            seq = (current.seqByPeer[peerId] ?: 0L) + 1L
            State(
                rows = current.rows + StoredMessage(seq, peerId, direction, envelopeJson, at),
                seqByPeer = current.seqByPeer + (peerId to seq),
            )
        }
        return seq
    }

    override fun since(peerId: String, sinceSeq: Long, limit: Int): List<StoredMessage> =
        state.value.rows.filter { it.peerId == peerId && it.seq > sinceSeq }
            .sortedBy { it.seq }
            .take(limit)

    override fun latestSeq(peerId: String): Long = state.value.seqByPeer[peerId] ?: 0L

    override fun sinceAll(sinceAt: Long, limit: Int): List<StoredMessage> =
        state.value.rows.filter { it.at > sinceAt }
            .sortedBy { it.at }
            .take(limit)
}
