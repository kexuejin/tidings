// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.core.protocol.Codec
import com.kapp.tidings.core.protocol.Envelope
import com.kapp.tidings.domain.model.MessageUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement

/**
 * 身份恢复 L2（对端拉回）的入站处理与触发（方案 §6.3 / Q5 / Q11）。
 *
 * ## 路径安全边界（为什么可以直接回历史）
 *
 * `recovery-req` 只回**请求方自己的那段会话**（`envelope.from` 就是会话键），
 * 不可能借道拉别人的。传输面与聊天同等级：局域网档本来就是明文（能 soliciting 的
 * 攻击者本来就能嗅探到同样的明文聊天流），公网档是 TLS + 双向 pin（fail-closed）——
 * L2 没有新增暴露面。
 *
 * ## 为什么进得了这里就值得回
 *
 * 请求帧在 `RELIABLE` 集里：到得了这里 = 对端已 ACK + 去重。旧版本对端把未知类型
 * 静默丢掉，根本走不到 ACK，请求方按 Q5 的窗口放弃 —— 「旧端黑洞」在发送侧就已经
 * 止损，不需要应答方的任何兼容逻辑。
 */
internal fun RelayControllerMessagesComponent.handleOnRecoveryReq(envelope: Envelope) {
    val requester = envelope.from
    host.scope.launch {
        serveRecoveryHistory(requester)
    }
}

/**
 * 桌面端服务：把双边副本里**这个会话**的历史分页发给恢复方。
 *
 * 分页预算 [RECOVERY_DATA_BATCH_BYTES]（64 KiB 整帧硬上限内的保守值），
 * 批间 [RECOVERY_BATCH_PACE_MS] 节流 —— fire-and-forget 不背拥塞控制的债，
 * 但也不对着一条链路倾泻。空会话回一个 `more=false` 的空批：
 * 让请求方的等待窗口**正常关闭**（"我什么都没有"也是答案）。
 */
private suspend fun RelayControllerMessagesComponent.serveRecoveryHistory(requester: String) {
    val thread = host.messageStore.load()[requester].orEmpty()
        .sortedBy { it.seq ?: Long.MAX_VALUE }
    var batch = 0
    var index = 0
    while (true) {
        val elements = ArrayList<kotlinx.serialization.json.JsonElement>()
        var bytes = 0
        while (index < thread.size) {
            val element = Codec.wireJson.encodeToJsonElement(MessageUi.serializer(), thread[index])
            val size = element.toString().encodeToByteArray().size
            if (bytes > 0 && bytes + size > RECOVERY_DATA_BATCH_BYTES) break
            elements += element
            bytes += size
            index++
        }
        val more = index < thread.size
        val sent = host.activeRuntime()?.sendRecoveryData(requester, batch, more, elements) == true
        if (!sent) return
        batch++
        if (!more) return
        delay(RECOVERY_BATCH_PACE_MS)
    }
}

/**
 * 恢复方合并：`recovery-data` 批次按消息 id 幂等合入（[handleAppendMessage] 自带
 * id 去重与按 `at` 插序），不进去重表以外的任何队列。
 *
 * 成套性防线：批次里每条消息的 `peerId` 必须等于请求方会话键（`envelope.from`）——
 * 桌面端只服务这一段会话，对不上的条目按坏批丢弃，不合入。
 * `mine` 翻转：桌面副本里的 `mine` 是**桌面视角**（它发的 = true），恢复方视角
 * 恰好相反；不翻转会让"谁说的"在恢复后整体颠倒。
 */
internal fun RelayControllerMessagesComponent.handleOnRecoveryData(envelope: Envelope) {
    val payload = envelope.payload as? JsonObject ?: return
    val messagesElement = payload["messages"] ?: return
    val messages = runCatching {
        Codec.wireJson.decodeFromJsonElement(ListSerializer(MessageUi.serializer()), messagesElement)
    }.getOrNull() ?: return
    for (message in messages) {
        if (message.peerId != envelope.from) continue
        host.handleAppendMessage(message.copy(mine = !message.mine), incoming = false)
    }
    host.publish()
}

/** L2 触发（Q5）：逐个对端发 `recovery-req`；ACK 不到（旧版/离线）就保留待下次。 */
internal suspend fun RelayController.drainRecoveryPull() {
    val peers = stateLock.withLock { pendingRecoveryPeers.toList() }
    for (peer in peers) {
        val acked = activeRuntime()?.sendRecoveryReq(peer) == true
        if (acked) {
            stateLock.withLock { pendingRecoveryPeers.remove(peer) }
        }
    }
}

internal fun RelayController.markRecoveryPullPending(peerIds: Collection<String>) {
    if (peerIds.isEmpty()) return
    stateLock.withLock { pendingRecoveryPeers += peerIds }
}

/** 单批字节预算：`recovery-data` 各批序列化后的实体总量上限（整帧 64 KiB 内的保守值）。 */
internal const val RECOVERY_DATA_BATCH_BYTES = 24 * 1024

/** 批间节流。 */
internal const val RECOVERY_BATCH_PACE_MS = 100L
