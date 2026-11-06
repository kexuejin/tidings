// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

/**
 * agent 与文件报价域的封闭状态词表，全仓唯一来源。
 *
 * 收口的是字符串取值，不是类型：`OfferDelivery.state` 仍是 String（控制面要按原样序列化），
 * 协议白名单也仍按整帧字节比对。这里把散在投递器、控制面、卡片翻译、编解码器、编排器里的
 * 手写词收成常量 + ALL 取值域，新增一个状态只改这一处。
 *
 * 形状与 ChannelOps / InvokeOps / AvatarOps / FileCtlOps 一致：public object、const val
 * 逐字保留线上字节、ALL 给校验器当封闭清单。跨模块引用，故一律 public。
 */
object OfferDeliveryStates {
    const val OFFERING = "offering"
    const val OFFERED = "offered"
    const val QUEUED_AWAITING_RECONNECT = "queued-awaiting-reconnect"
    const val GAVE_UP = "gave-up"
    const val ACK_TIMEOUT = "ack-timeout"
    val ALL: Set<String> = setOf(OFFERING, OFFERED, QUEUED_AWAITING_RECONNECT, GAVE_UP, ACK_TIMEOUT)
}

/** `agent-queue` 的 op 取值域。 */
object AgentQueueOps {
    const val ENQUEUE = "enqueue"
    const val DEQUEUE = "dequeue"
    val ALL: Set<String> = setOf(ENQUEUE, DEQUEUE)
}

/** `agent-run` 的 state 取值域；TERMINAL 是其中不再变化的那一半。 */
object AgentRunStates {
    const val RUNNING = "running"
    const val SUCCEEDED = "succeeded"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
    val TERMINAL: Set<String> = setOf(SUCCEEDED, FAILED, CANCELLED)
    val ALL: Set<String> = setOf(RUNNING, SUCCEEDED, FAILED, CANCELLED)
}
