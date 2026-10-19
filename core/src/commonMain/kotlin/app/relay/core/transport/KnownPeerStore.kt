// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

import app.relay.core.persist.KnownPeer

/**
 * 「已知对端」的存取（task-80 ①）。
 *
 * core 只认识这个接口，不想知道文件放在哪、怎么序列化 —— 落盘实现在
 * [app.relay.core.persist.FileKnownPeerStore]。
 */
interface KnownPeerStore {
    /** 已落盘的对端（重启时据此恢复成离线态）。 */
    fun load(): List<KnownPeer>

    /** 整份写回。调用方负责去抖。 */
    fun save(peers: List<KnownPeer>)
}

/** 什么都不做的实现：测试与预览的默认，行为与 task-80 之前一致。 */
object NoKnownPeerStore : KnownPeerStore {
    override fun load(): List<KnownPeer> = emptyList()
    override fun save(peers: List<KnownPeer>) = Unit
}