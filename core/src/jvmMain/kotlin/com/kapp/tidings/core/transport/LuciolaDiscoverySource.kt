// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

import io.github.kexuejin.luciola.DiscoveryEventUi
import io.github.kexuejin.luciola.PeerUiModel
import kotlinx.coroutines.flow.SharedFlow

/**
 * 阶段一适配器脚手架:luciola 发现事件 → Tidings 消费面。
 * 完整 Tidings Profile 还原走 peer.extra(宿主 JSON,luciola 原样透传);
 * 具体反序列化在阶段二接线时对齐 Codec(此处刻意不猜协议 API)。
 * 见 luciola 仓库 docs/MIGRATION-tidings.md。
 */
class LuciolaDiscoverySource(private val events: SharedFlow<DiscoveryEventUi>) {
    val stream: SharedFlow<DiscoveryEventUi> get() = events
    fun nodeIdOf(peer: PeerUiModel): String = peer.nodeId
    fun extraOf(peer: PeerUiModel): String? = peer.extra
}
