// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tenon.core.testing

import com.kapp.tenon.core.protocol.Profile
import com.kapp.tenon.core.transport.DiscoveryEngine
import com.kapp.tenon.core.transport.PeerRegistry
import kotlinx.coroutines.CoroutineScope

/**
 * 测试用发现引擎：**不发广播**，只让测试把对端直接登记进 registry。
 *
 * ## 为什么需要它
 *
 * legacy 发现被删除之前，`AgentChannelRoundTripTest` 这类用例里两台 [NodeRuntime]
 * 是靠 `announceTo` + UDP 广播互相「发现」的 —— 那正是被删掉的那条路。删掉之后
 * `announceTo` 只转发给 extraDiscovery，测试里没有真实发现引擎，两端永远互不认识，
 * 于是所有「发一条消息过去」的用例都卡在超时上。
 *
 * 这些用例要验的是**协议行为**（caps 门、agent 回路、群消息、重连），不是发现机制。
 * 所以这里把「发现」替换成一次显式登记：`introduce(...)` 等价于「刚才收到了它的信标」，
 * 之后的路由、拨号、加密信道、队列全部走生产代码。
 *
 * 真实发现（luciola 信标、二维码配对）由 `LuciolaDiscoveryEngineTest` /
 * `LuciolaSecureChannelTest` 在原生库可用时覆盖。
 */
internal class ManualDiscovery(private val registry: PeerRegistry) : DiscoveryEngine {
    /** 把一台对端登记为在线（等价于「刚收到它的 entry」）。 */
    fun introduce(nodeId: String, host: String, port: Int, profile: Profile) {
        registry.touch(nodeId, host, port, profile, confirmed = true)
    }

    override fun start(scope: CoroutineScope) = Unit

    override fun stop() = Unit

    override suspend fun announce() = Unit

    override suspend fun announceTo(host: String, port: Int) = Unit

    override suspend fun announceExit() = Unit
}
