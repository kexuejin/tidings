// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

import kotlinx.coroutines.CoroutineScope

/**
 * [ScreenChannelHost] 的 luciola 实现：把 [SecureScreenChannel] 接到 `NodeRuntime` 的接缝上。
 *
 * 一条 [SecureScreenChannel] 同时承担两端：Sharer 用它的监听面接受连接，Viewer 用
 * [SecureScreenChannel.dial] 拨对端 —— 与消息/文件共用同一份身份与 pin（deny-by-default）。
 */
class SecureScreenChannelHost(
    private val channel: SecureScreenChannel,
    private val scope: CoroutineScope,
) : ScreenChannelHost {

    override fun opener(host: String, port: Int): ScreenWireOpener =
        ScreenWireOpener { channel.dial(host, port, scope) }

    override suspend fun startListening(onWire: (ScreenWire) -> Unit) {
        channel.start()
        channel.accept(scope) { wire -> onWire(wire) }
    }

    override fun stopListening() {
        channel.stop()
    }
}
