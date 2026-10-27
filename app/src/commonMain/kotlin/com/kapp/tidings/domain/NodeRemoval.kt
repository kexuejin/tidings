// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.domain

/**
 * 「移除节点」要删的那一条。
 *
 * ## 为什么需要这个类型
 *
 * 节点表里其实混着两种东西：**用户手填的地址**（[ManualAddress]）与**被发现/配对来的
 * 节点身份**（[DiscoveredNode]）。它们此前只有前者能删 —— 「移除节点」这颗按钮在详情页
 * 被 `item.manualEntry != null` 挡着，于是「局域网里看到一台设备」这种最常见的一行
 * 反而没有任何删除入口（真机：manualPeers = 0 / known-peers = 16，按钮一次都不出现）。
 *
 * 两种删除的后果不同，所以必须由界面显式选一种交给控制器，而不是让 UI 猜：
 * - 手动地址：只删本机这条 `host:port` 记录（对端不受影响）；
 * - 发现的节点：清掉本机对它的身份记忆（registry + known-peers/地址池/pin），
 *   **它下次被发现在网上时会重新出现**。
 */
sealed interface NodeRemoval {
    /** 用户手填的 `host:port` 记录。 */
    data class ManualAddress(val entry: ManualPeerEntry) : NodeRemoval

    /** 局域网发现/扫码配对来的节点身份（本机没有手动记录）。 */
    data class DiscoveredNode(val nodeId: String) : NodeRemoval
}
