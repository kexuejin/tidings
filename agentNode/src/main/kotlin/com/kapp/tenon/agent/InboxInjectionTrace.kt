// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

import com.kapp.tenon.core.nowMillis
import com.kapp.tenon.core.protocol.InboxUnresolved

/**
 * `/v1/inbox` 上「桥来取件」与「取到的东西能不能注入」的**最近读数**。
 *
 * ## 为什么需要它（而不是去读日志）
 *
 * 「接入与通道」页要回答「这台机器上的外部桥（`dsh-tenon`）最近到底有没有把消息送进
 * agent 的会话」。宿主侧**没有**桥的心跳：它不是 `PluginHost` 的成员，也不留 last-poll
 * 文件。**硬画一个「桥在线」绿灯就是假绿** —— 那正是本仓最贵的一类缺陷（界面比事实更少，
 * 或比事实更满）。
 *
 * 宿主真正持有的事实只有两件，这里也只记这两件：
 *
 *  1. **桥来取过件**（每次 `GET /v1/inbox` 都记一次 [recordPoll]）—— 没有它，「一条可注入
 *     消息都没有」与「桥根本没在跑」在界面上长得一模一样；
 *  2. **每次注入判定**（[record]）—— 包括「@ 到了但投不出去」的**成因**。只给计数不给
 *     成因，用户除了重装什么都做不了。
 *
 * ## 只留有界窗口
 *
 * 环形缓冲 + 窗口内聚合（默认 20 条，与页面「近 20 条」同源）。不做全量累计：这页是
 * 排障仪表，不是计费台账；全量计数会让「昨天好了、今天坏了」被历史稀释成看不见。
 *
 * ## 去重口径
 *
 * `seq` 在**每个会话内**单调（`InMemoryMessageStore` 甚至按 peer 各自从 1 数）。
 * 所以水位按 `peerId` 记，且只在 `seq` 前进时收 —— 桥重启后从 `since=0` 重读一遍时，
 * 窗口不会被同一批历史消息灌满。
 *
 * ## 为什么构造器是 internal
 *
 * 「台账只此一份」不能靠注释和守卫维持：第二份实例的症状（桥在干活、页面说没判定过）
 * 是安静的，写的人不会当场发现。构造器收成 internal 之后，`orchestration` 那侧
 * **造不出**第二份，只能读控制面交出来的那一份 —— 机制替代了断言。
 */
class InboxInjectionTrace internal constructor(private val capacity: Int = DEFAULT_CAPACITY) {

    /** 一条注入判定的读数。**不含正文、不含会话 id 之外的任何标识**（诊断只记结论）。 */
    data class Sample(
        /** 宿主做出这次判定的时刻（本机时钟），不是消息的发送时刻。 */
        val at: Long,
        /**
         * 这一条**真的会被注入**：既算出了目标会话、发送者也有执行权。
         *
         * 只判「有没有算出会话」会把「算得出来、投不出去」算成好消息 —— 见 [record] 的注释。
         */
        val resolved: Boolean,
        /** **没投成**的成因 token（[InboxUnresolved]）；resolved=true 时为 null。 */
        val cause: String?,
    )

    /** 一次快照。空快照（从未取件 / 从未判定）是**一等状态**，调用方据此画空态。 */
    data class Snapshot(
        val windowCapacity: Int,
        /** 桥最近一次来取件的时刻；0 = 从未（这条与「桥没在跑」是同义的）。 */
        val lastPollAt: Long,
        /** 最近一条「@ 到本机 agent」的消息的判定时刻；0 = 从来没有过。 */
        val lastAddressedAt: Long,
        /** 最近一条**真的能投**的判定时刻；0 = 从来没有过（桥装上了也一次没成功）。 */
        val lastInjectableAt: Long,
        val addressedCount: Int,
        val resolvedCount: Int,
        val unresolvedCount: Int,
        /** 未解析成因 → 条数，多的排前面。 */
        val causes: List<Pair<String, Int>>,
    ) {
        /** 窗口里一条判定都没有 —— 页面必须画「还没判定过」，不能画 0/0 的统计。 */
        val isEmpty: Boolean get() = addressedCount == 0
    }

    private val lock = Any()
    private val samples = ArrayDeque<Sample>(capacity)

    /**
     * peerId → 已收过的最大 seq（有界，见类注释的去重口径）。
     *
     * **必须是访问序（accessOrder=true），不是插入序。** 插入序下淘汰的是「最早第一次
     * 说话的会话」，而它很可能正是此刻最活跃的那个（长时间开着的那条群会话）；被挤掉之后
     * 桥下一次重读，它的历史 seq 会被当成新消息重新计数 —— 页面上「近 20 条」的数字
     * 突然虚高，而没有任何人改过什么。
     *
     * 访问序的「访问」包含被去重拒掉的那次读（`watermarks[peerId]`）：一条重复消息
     * 本身就是「这个会话还活着」的证据，理应给它续命。
     */
    private val watermarks = object : LinkedHashMap<String, Long>(MAP_INITIAL_CAPACITY, MAP_LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > MAX_TRACKED_PEERS
    }

    private var lastPollAt: Long = 0L
    private var lastAddressedAt: Long = 0L
    private var lastInjectableAt: Long = 0L

    /** 桥来取了一次件（每次 `GET /v1/inbox` 都会走到这里，无论有没有新消息）。 */
    fun recordPoll(at: Long = nowMillis()) {
        synchronized(lock) {
            if (at > lastPollAt) lastPollAt = at
        }
    }

    /**
     * 记一条**注入判定**（只有「@ 到了本机 agent」的消息才有判定，未 @ 的不进窗口）。
     *
     * @param peerId 会话键（群里是 `group:<id>`）；与 [seq] 一起做去重。
     */
    fun record(peerId: String, seq: Long, hint: AgentInboxHint, at: Long = nowMillis()) {
        // **「算出了目标会话」不等于「投得出去」。**
        //
        // `resolveAgentInboxHint` 里 pick 与 authorize 是两条独立的判定：会话照常算出来，
        // 发送者也可能没有执行权。只判 `targetSessionId != null` 的话，这类消息会被算成
        // 「可注入」并顶新 [Snapshot.lastInjectableAt] —— 页面顶部那句「最近一次可注入消息」
        // 就会在「会话正常、无权执行」时**直接说假话**，而桥接侧一条也不会投。
        // 那正是这一页要防的「界面比事实更满」。
        //
        // 连带后果：`causeOf` 的第一档（授权拒）几乎不可达 —— 它只在 target 也为 null 时
        // 才走得到，于是最常见的授权问题反而从成因列表里消失。
        val resolved = hint.targetSessionId != null && hint.senderMayExecute
        val sample = Sample(at = at, resolved = resolved, cause = if (resolved) null else causeOf(hint))
        synchronized(lock) {
            val seen = watermarks[peerId]
            if (seen != null && seq <= seen) return
            watermarks[peerId] = seq
            samples.addLast(sample)
            while (samples.size > capacity) samples.removeFirst()
            if (at > lastAddressedAt) lastAddressedAt = at
            if (resolved && at > lastInjectableAt) lastInjectableAt = at
        }
    }

    fun snapshot(): Snapshot = synchronized(lock) {
        val window = samples.toList()
        val causes = window.asSequence()
            .mapNotNull { it.cause }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }
                .thenBy { InboxUnresolved.ALL.indexOf(it.key).takeIf { i -> i >= 0 } ?: Int.MAX_VALUE })
            .map { it.key to it.value }
        Snapshot(
            windowCapacity = capacity,
            lastPollAt = lastPollAt,
            lastAddressedAt = lastAddressedAt,
            lastInjectableAt = lastInjectableAt,
            addressedCount = window.size,
            resolvedCount = window.count { it.resolved },
            unresolvedCount = window.count { !it.resolved },
            causes = causes,
        )
    }

    companion object {
        /** 页面「近 N 条」的 N —— 只留一处，页面与台账不会各说一个数。 */
        const val DEFAULT_CAPACITY: Int = 20

        /** 去重水位最多跟多少个会话（群多、1:1 多的机器上也不会无界增长）。 */
        const val MAX_TRACKED_PEERS: Int = 64

        /** 水位表的初始容量与负载因子（与上限同量级，避免扩容抖动）。 */
        private const val MAP_INITIAL_CAPACITY: Int = 16
        private const val MAP_LOAD_FACTOR: Float = 0.75f
    }
}

/**
 * 「@ 到了但没有可投的会话」的成因，按**最先挡住它的那一关**取一条 —— 一次只报一条，
 * 因为用户能做的动作只有一个：去解掉那一关。
 *
 * 判据来源：`resolveAgentInboxHint` 已经在 hint 里算好了这几件事，这里只做**归因**，
 * 不重算策略（重算等于把「为什么没投」变成两个答案）。
 */
internal fun causeOf(hint: AgentInboxHint): String {
    // 授权拒了：后面有没有会话都不重要，先说这条 —— 用户要解的是权限，不是去开会话。
    if (!hint.senderMayExecute) return InboxUnresolved.SENDER_NOT_PERMITTED
    // 判定层已经给出稳定 token（点选被拒的两档）就原样上报，不在这里二次归类。
    // 用「非空即上报」而不是逐个 token 比对：以后判定层多一档，页面会自动显示它
    // （认不出就原样显示），而不是被这条 when 悄悄改写成「工作区里没会话」。
    hint.requestRejectReason?.takeIf { it.isNotBlank() }?.let { return it }
    // **「未绑工作区」不等于「投不出去」。**
    //
    // 未绑定的 agent 照样能在「不带工作区的会话」里干活（`AgentSessionPick` 的语义，
    // 也是用户明确说过的）。所以没投成时真正的成因是「连那种会话也没有」，用户要做的
    // 是去开一个 —— 而不是去绑一个目录（绑了也没用，他的会话本来就不在那个目录里）。
    //
    // 空串按「未绑定」算，与 `AgentSessionPick.pick` 的归一化（trim + isNotEmpty）同口径：
    // 两处判据不同源时，同一个 hint 会得到两个答案。
    return if (hint.boundWsId.isNullOrBlank()) {
        InboxUnresolved.NO_UNBOUND_SESSION
    } else {
        InboxUnresolved.NO_SESSION_IN_WORKSPACE
    }
}
