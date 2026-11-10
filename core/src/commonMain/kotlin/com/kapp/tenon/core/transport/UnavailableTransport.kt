// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tenon.core.transport

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * 「没有任何传输可用」时的占位实现。
 *
 * ## 它为什么存在
 *
 * luciola 成为唯一传输形态之后，宿主侧可能装配失败（平台没接线宿主 / 原生库缺失或版本不符）。
 * 旧代码在这种情况下的出路是 `?: UdpTransport(...)` —— 也就是**静默跑明文**。那条路已经删了。
 *
 * 剩下的选择只有两个：让整个节点构造抛异常（App 起不来，用户连历史消息和设置都打不开），
 * 或者让节点起来但**发不出也收不到**，由上层显示「加密信道不可用」。
 * 后者对用户更诚实也更有用：能看历史、能改设置、能照着横幅去重装，而不是启动即崩。
 *
 * ## 它不是 legacy 的替代品
 *
 * [send] 恒 false、[incoming] 永无元素、[boundPort] 为 -1：这条通路**不存在**，
 * 没有任何明文出入。任何依赖它「至少能通」的假设都会立刻暴露，而不是悄悄降级。
 */
class UnavailableTransport(private val reason: String) : DatagramTransport {
    override val incoming: SharedFlow<ReceivedDatagram> = MutableSharedFlow()

    /** 没有监听任何端口 —— 上层据此不会向对端宣告一个假端口。 */
    override val boundPort: Int = -1

    override suspend fun start() {
        // 留一行日志：真机排障时「节点起来了但一个包都没发」必须能追到这一句。
        println("[transport] unavailable reason=$reason")
    }

    override fun stop() = Unit

    override suspend fun restart() = Unit

    override suspend fun send(bytes: ByteArray, host: String, port: Int): Boolean = false
}
