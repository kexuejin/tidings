// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.core.protocol.GroupNeedTracker
import com.kapp.tidings.domain.model.MdnsServiceUi
import com.kapp.tidings.domain.model.MessageUi
import kotlinx.coroutines.flow.update
import com.kapp.tidings.domain.withLock

/**
 * 控制器持有的**可变会话表** —— 谁在改哪张表，就该改这个类。
 *
 * ## 为什么把它们从 `RelayController` 里搬出来
 *
 * 这些表过去是 `RelayController` 的 `internal` 字段，而 11 个分片文件
 * （`RelayController{Messages,Transfers,Agent,…}.kt`）通过 `internal fun RelayController.handleXxx()`
 * 这个手法去改它们。问题不在于「能不能改」，而在于**没人能一眼看出谁能改什么**：
 * 一个 `internal` 字段在同一个 Gradle 模块里是任意文件都能碰的，`composeApp` 是单模块，
 * 所以「只有 app 包能用」这条纪律从来没有编译器管过（实测：`handle*` 在包外被引用 0 次，
 * 全靠约定）。
 *
 * 搬进一个类之后，「这张表归谁」就写在了类型上。
 *
 * ## 并发约定（改动这块之前必读）
 *
 * **下面每一张表都受 [lock] 保护，粒度与顺序不能改。** 控制器跑在
 * `Dispatchers.Default`（多线程池）上：轮询协程每 2 秒 `refreshPeers()` 会对
 * [threads] 做 `mapValues`，而 `restoreMessages()` / `appendMessage()` 可能在别的 worker 上
 * `getOrPut` / `+=`；UI 线程的 `publish()` 也走同一段遍历。不加锁就是
 * `java.util.ConcurrentModificationException`（一次启动后 104ms 的崩溃，
 * 栈落在 `RelayController.refreshPeers`）。
 *
 * [lock] 是可重入的（iOS 侧用 `NSRecursiveLock`），因此循环里调用的 `replaceMessage`
 * 之类再取一次锁是安全的；**但持锁期间不得挂起**（不得调 suspend 函数、不得做 IO）。
 * 完整背景见 `StateLock.kt`。
 */
internal class RelayCoreState {
    /**
     * 保护本类里**所有**可变表的结构。粒度是「一次状态变更事务」——
     * 临界区里读改写一组相关字段，然后调 [RelayStateWriter.commit] 推给 UI。
     */
    val lock: StateLock = createStateLock()

    // ---- 会话与线程（消息列表本体）

    /** 会话 id → 该会话的消息列表。这是最大的一张表，也是崩溃事故的主角。 */
    val threads = mutableMapOf<String, MutableList<MessageUi>>()

    /**
     * 会话 id → 摘要。启动时从库里读一份（MessageStore.loadSummaries）。
     *
     * 它是「内存里没有消息的那个会话」在列表里仍然有预览的唯一来源。
     * 内存里**有**消息的会话由 RelayStateWriter.commit 用最新一条覆盖它 ——
     * 那条覆盖是必须的：新消息刚到、异步落盘还没完成时，列表预览不能等。
     */
    val summaries = mutableMapOf<String, ConversationSummaryRow>()

    /** 会话 id → 未读数。 */
    val unread = mutableMapOf<String, Int>()

    /** 当前正在看的会话（手机端用它决定回车落到哪）。 */
    var visibleConversationId: String? = null

    /** 会话 id → 发送中条数。UI 上的「发送中…」角标读它。 */
    val pending = mutableMapOf<String, Int>()

    /** 撤回请求 id → 发起时间戳；超时的撤回由剪枝回收。 */
    val pendingRecalls = mutableMapOf<String, Long>()

    /** 对同一会话的戳一戳限流。 */
    val nudgeThrottle = NudgeThrottle()

    // ---- 传输

    /** 传输 id → 一句话描述，传输卡还没建起来时先占位。 */
    val transferMessages = mutableMapOf<String, String>()

    /** 传输 id → 它归属的会话 id。 */
    val transferConvs = mutableMapOf<String, String>()

    /** 群 id → 该群在途的传输 id 列表（一个文件对多个成员时用）。 */
    val groupTransferIds = mutableMapOf<String, MutableList<String>>()

    // ---- 群

    /** 群元数据的版本追踪器（决定要不要再要一次 info）。 */
    val groupNeeds = GroupNeedTracker()

    // ---- 发现

    /** host/port → mDNS 投回来的服务详情。 */
    val mdnsServices = mutableMapOf<String, MdnsServiceUi>()

    /** 本机自己广播出去的 mDNS label，用于认「哪个是自己」。 */
    var selfMdnsLabel: String? = null

    /** 取消正在进行的网络扫描。 */
    var networkCancel: (() -> Unit)? = null
}

/**
 * 状态**写入方** —— 唯一有权把 [RelayCoreState] 里的表推给 UI 的地方。
 *
 * 切出来的理由：`publish()` 过去是 `RelayController` 的一个 `internal fun`，
 * 任何分片都能调，任何分片都能 `_state.update { }` 自己拼一份新状态。
 * 结果是「谁改了状态、谁负责通知 UI」没有规则：有的路径改了表就 publish，
 * 有的路径改了表但忘了 publish（界面状态过期不自愈 —— 这是 README 里记着的
 * 第三类真机缺陷）。
 *
 * 现在规则是：**改表 → 在同一个临界区里调 [commit]**，没有第二条路。
 */
internal class RelayStateWriter(
    private val state: RelayCoreState,
    private val flow: kotlinx.coroutines.flow.MutableStateFlow<RelayUiState>,
) {
    /**
     * 推一次状态。
     *
     * @param transform 基于当前 UI 状态算新状态；它看到的是刚刚被改完的那批表的快照。
     */
    private data class Snapshot(
        val messages: Map<String, List<MessageUi>>,
        val unread: Map<String, Int>,
        val pending: Map<String, Int>,
        val summaries: List<ConversationSummaryRow>,
    )

    fun commit(transform: (RelayUiState) -> RelayUiState) {
        val snapshot = state.lock.withLock {
            // 摘要 = 启动时从库里读到的那份（内存里没有消息的会话）
            //      + 内存里最新一条（内存里有消息的会话）。
            //
            // 后者是刻意的覆盖：新消息刚到、异步落盘还没完成时，会话列表的预览
            // 必须立刻反映它，不能让用户看到「消息进来了、列表还停在上一句」。
            val merged = LinkedHashMap<String, ConversationSummaryRow>(state.summaries)
            for ((convId, list) in state.threads) {
                val last = list.lastOrNull()
                if (last == null) {
                    // 空列表 = 这个会话已经没有消息了：摘要不能留着，否则列表多一个空行。
                    merged.remove(convId)
                    continue
                }
                merged[convId] = ConversationSummaryRow(
                    convId = convId,
                    lastMessageId = last.id,
                    lastTextPreview = last.text.takeIf { it.isNotBlank() } ?: last.file?.name,
                    lastMessageAt = last.at,
                    lastMessageIsMine = last.mine,
                )
            }
            Snapshot(
                messages = state.threads.mapValues { (_, list) -> list.toList() },
                unread = state.unread.toMap(),
                pending = state.pending.toMap(),
                summaries = merged.values.toList(),
            )
        }
        flow.update {
            transform(it).copy(
                messages = snapshot.messages,
                unreadByPeer = snapshot.unread,
                pendingByPeer = snapshot.pending,
                conversationSummaries = snapshot.summaries,
            )
        }
    }

    /** 不带变换地推一次（表已改完，只是还没通知 UI）。 */
    fun publish() = commit { it }
}