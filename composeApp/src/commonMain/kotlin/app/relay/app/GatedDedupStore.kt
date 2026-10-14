// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import app.relay.core.transport.DedupStore
import app.relay.core.transport.FileDedupStore
import app.relay.core.transport.InMemoryDedupStore

/**
 * 去重表：内存里总有一份，磁盘上那份由开关决定要不要写。
 *
 * 三件事各自有理由：
 *
 * 1. **内存那份永远在**。开关关着时它就是全部；开关打开后它也不会被丢掉，因为
 *    `has` 两边都查、`add` 两边都写。反过来只留磁盘那份的话，开关一关，刚收到的
 *    那几条就"忘了"，对端重发又会被当成新消息。
 * 2. **磁盘那份按需构造**（`by lazy`）。开关关着时连文件都不打开 —— 用户关掉「消息落盘」
 *    之后这个应用不该去读一份记着"谁什么时候发过什么 id"的日志。
 * 3. **查的是当下这个开关**，不是启动那一刻的快照。[NodeRuntime] 的去重表是构造期定死的，
 *    而开关可以随时改，所以判断必须发生在每次调用里，不能发生在构造里。
 *
 * 从开到关只**停止写**，已经落盘的文件保留 —— 与 `updatePersistMessages` 对历史消息的
 * 语义一致（那边也是"只是不再写"），不搞两套规则。
 */
internal class GatedDedupStore(
    path: String,
    private val enabled: () -> Boolean,
) : DedupStore {

    private val file: DedupStore by lazy { FileDedupStore(path) }

    private val memory: DedupStore = InMemoryDedupStore()

    override fun has(msgId: String, peerId: String): Boolean =
        memory.has(msgId, peerId) || (enabled() && file.has(msgId, peerId))

    override fun add(msgId: String, peerId: String, at: Long) {
        memory.add(msgId, peerId, at)
        // 关着时**不补写**：开关打开之前收到的那几条不该被追溯落盘。
        if (enabled()) file.add(msgId, peerId, at)
    }

    override fun prune(now: Long, ttl: Long): Int {
        val dropped = memory.prune(now, ttl)
        return if (enabled()) dropped + file.prune(now, ttl) else dropped
    }
}
