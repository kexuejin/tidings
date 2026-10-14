// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import app.relay.app.model.MessageUi
import kotlinx.coroutines.flow.update
import app.relay.core.ioDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 横切操作：落在 [RelayController] 本身，不属于任何一个组件。
 *
 * 这几件事的共同点是**横跨多个功能族**：改设置要落盘（persist），落盘要写
 * messages 表（persistThreads 调 appendMessage），传输卡片要插进消息流
 * （appendMessage），撤回要改消息再重排（replaceMessage）。按功能分家时它们
 * 必然被切碎 —— Groups 与 Transfers 都要落盘、Cidr 与 Cabinet 都要改设置。
 *
 * 所以它们留在顶层扩展里，与 8 个组件平级。组件内部仍用 `host.xxx(...)` 调，
 * 靠的是 [RelayController] 上那几条同名单行委托。
 */
object RelayControllerOps


/**
 * [RelayController] 的设置与偏好配置域扩展。
 *
 * 覆盖个人资料、端口、主题、通知免打扰策略、持久化配置与 Remote Tidings 中继通信。
 */
internal fun RelayController.handlePersist(settings: RelaySettings) {
    store.save(settings)
    _state.update { it.copy(settings = settings) }
}



internal fun RelayController.handleAppendMessage(message: MessageUi, incoming: Boolean = false) {
    // 可重入：下面的 messagesCtl.handleApplyPendingRecall / refreshPeers / handlePersistThreads 也会取这把锁。
    stateLock.withLock {
        val thread = core.threads.getOrPut(message.peerId) { mutableListOf() }
        if (thread.any { it.id == message.id }) return
        // 按 `at` 插到该在的位置，不是无脑追加。
        //
        // 为什么：`handleRestoreMessages()` 读回来时会 `sortBy { it.at }`，而无脑追加只在"新消息就是
        // 最新的"时才与排序一致。屏幕协助历史卡的 `at` 取的是 `requestedAt`（见
        // `screenRecordMessage`），它常早于流里后来到的消息 —— 独立验证者实测到
        // 同一条记录在内存里是 [200,100]、重启后变 [100,200]。一条消息的位置不该取决于它
        // 经历过几次重启。
        //
        // 常见路径（`at` 最大）下这个循环一次都不进，落在末尾，与 `+=` 完全等价 ⇒
        // 不改"最后一条就是最新"这个既有假设（`refreshPeers` 等按 `.lastOrNull()` 取预览）。
        var index = thread.size
        while (index > 0 && thread[index - 1].at > message.at) index--
        thread.add(index, message)
        if (incoming && message.peerId != core.visibleConversationId) {
            core.unread[message.peerId] = (core.unread[message.peerId] ?: 0) + 1
        }
    }
    messagesCtl.handleApplyPendingRecall(message)
    refreshPeers()
    handlePersistThreads()
}



internal fun RelayController.handleReplaceMessage(peerId: String, message: MessageUi) {
    val found = stateLock.withLock {
        val thread = core.threads[peerId] ?: return@withLock false
        val index = thread.indexOfFirst { it.id == message.id }
        if (index < 0) return@withLock false
        thread[index] = message
        true
    }
    if (!found) return
    publish()
    handlePersistThreads()
}



internal fun RelayController.handlePersistThreads() {
    if (!persistMessages) return
    // 快照在锁内取（遍历 core.threads），launch 在锁外 —— 挂起绝不能在持锁期间发生。
    val snapshot = stateLock.withLock {
        core.threads.mapValues { (_, list) -> list.map { it.toStorable() } }
    }
    val target = messageStore
    scope.launch {
        persistMutex.withLock {
            withContext(ioDispatcher) { runCatching { target.save(snapshot) } }
        }
    }
}


