// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.persist

import app.relay.core.transport.MessageStore
import app.relay.core.transport.QueueStore

/**
 * 三个落盘 store 的**一次性持有者**。
 *
 * 为什么不直接在注入点写 `FileQueueStore(paths.queueLog)`：
 * 这些对象在**构造时就把整个日志读进内存**，构造两次等于把文件白读一遍，
 * 而「读两遍」在文件正被写时读到的是两个不同的快照 —— 队列会丢一条。
 * 控制器与 agentNode 都只创建节点运行时一次，所以持有者建一次就够。
 *
 * [dedup] 不在这里：[DedupStore] 的落盘实现（`FileDedupStore`）是**按需开关**的
 * —— 关掉「持久化聊天记录」时它应该整个退化到内存，所以那一层留在应用侧。
 */
class PersistStores(
    /** 出站可靠消息队列。 */
    val queue: QueueStore,
    /** 会话历史（供 `GET /v1/inbox?since=` 增量拉取）。 */
    val messages: MessageStore,
) {
    companion object {
        fun under(dataDir: String): PersistStores {
            val paths = PersistPaths.under(dataDir)
            return PersistStores(
                queue = FileQueueStore(paths.queueLog),
                messages = FileMessageStore(paths.messageLog),
            )
        }
    }
}
