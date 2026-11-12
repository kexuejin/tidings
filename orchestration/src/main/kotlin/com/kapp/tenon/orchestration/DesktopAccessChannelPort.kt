// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.InboxInjectionTrace
import com.kapp.tenon.domain.AccessChannelPort
import com.kapp.tenon.domain.BridgeCauseUi
import com.kapp.tenon.domain.BridgeChannelUi

/**
 * [AccessChannelPort] 的桌面实现：「接入与通道」页读的两块宿主事实。
 *
 * 两块数据都**只读**，且都住在宿主进程：
 *  - 桥的注入判定台账 —— 控制面的 `InboxInjectionTrace`（**同一个实例**，见
 *    [InboxInjectionTrace] 的 KDoc：两份实例的症状是「桥在干活、页面说没判定过」）；
 *  - 本机信任库的 pin —— `NodeRuntime.pinOfPeer`（与 TLS 拨号读的是同一张表）。
 *
 * 没有任何写入口是刻意的：这一页回答「谁在跟我说话」，改权限在「信任的设备」页，
 * 两件事混在一页会让「我只是想看看」变成一次有副作用的点击。
 */
class DesktopAccessChannelPort(
    /** 控制面在跑时才有台账；控制面没起 ⇒ null（页面画「宿主没起」而不是 0/0/0）。 */
    private val trace: () -> InboxInjectionTrace?,
    private val pinLookup: (nodeId: String) -> String?,
    /**
     * 事件路由计数（`EventRouterDiagnostics.snapshot()`）。
     *
     * 参数名不叫 `routingCounts`：那会与重写的方法同名，读代码时 `routingCounts()` 到底
     * 是属性还是方法靠猜（同样的坑在 `pinLookup` 上踩过一次）。
     */
    private val routingLookup: () -> Map<String, Long>? = { null },
) : AccessChannelPort {

    override fun bridge(): BridgeChannelUi? {
        val snapshot = trace()?.snapshot() ?: return null
        return BridgeChannelUi(
            windowCapacity = snapshot.windowCapacity,
            lastPollAt = snapshot.lastPollAt,
            lastAddressedAt = snapshot.lastAddressedAt,
            lastInjectableAt = snapshot.lastInjectableAt,
            addressedCount = snapshot.addressedCount,
            resolvedCount = snapshot.resolvedCount,
            unresolvedCount = snapshot.unresolvedCount,
            causes = snapshot.causes.map { (reason, count) -> BridgeCauseUi(reason = reason, count = count) },
        )
    }

    override fun pinOf(nodeId: String): String? =
        nodeId.takeIf { it.isNotBlank() }?.let(pinLookup)

    override fun routingCounts(): Map<String, Long>? = routingLookup()
}
