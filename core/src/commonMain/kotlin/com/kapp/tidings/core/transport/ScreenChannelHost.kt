// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

/**
 * 画面信道（luciola）在 `NodeRuntime` 里的接缝。
 *
 * 为什么要一层接口：`NodeRuntime` 在 commonMain，而 luciola 的会话/密钥全在 jvmShared
 * 的 UNIFFI 绑定后面 —— 共享层不该知道这些。实现见 `SecureScreenChannelHost`。
 *
 * 三个动作对应画面链路的两端：
 *  - [opener]：Viewer 侧拨对端的画面端口（返回 null 的 opener 表示这条路不通）；
 *  - [startListening]：Sharer 侧开始接受画面会话（每条连接回调一次，所有权交出）；
 *  - [stopListening]：随运行时一起收摊。
 */
interface ScreenChannelHost {

    /** 拨对端 `host:port` 的一条画面会话。 */
    fun opener(host: String, port: Int): ScreenWireOpener

    /** 开始接受画面会话。[onWire] 拿到的 wire 归调用方所有（用毕由调用方关）。 */
    suspend fun startListening(onWire: (ScreenWire) -> Unit)

    fun stopListening()
}
