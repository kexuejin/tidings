// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

/**
 * 候选端点的落盘契约（按 nodeId 索引）。
 *
 * 值的类型是既有的 [PeerEndpoint]（"对端在节点表里的可达地址"），不另立一个同形类型。
 *
 * ## 为什么需要它
 *
 * 配对码里带的是一**池**地址（`lan_v4` / `lan_v6` / `public_v6`），但握手之后 registry 里
 * 每个 peer 只留**一个**地址（最后一次被采纳的那个）。手机从局域网切到移动网时，那个地址
 * 就不可达了；而对端的另一个地址（公网 IPv6）明明通 —— 只是没人再去试。
 *
 * 真机现场（task-20）：手机只留移动网时，它给桌面发 presence 只发到桌面的**局域网**地址
 * （广播过不了网段、单播也不可达），于是桌面永远学不到手机的运营商地址 —— 两边互相显示
 * 离线、消息全进队列。所以候选池必须**存下来**，并在发送时按序兜底。
 *
 * 按 nodeId 而不是按地址索引：地址会变，nodeId 不会 —— 换网之后我们要找的还是**同一个**身份。
 */
interface PeerEndpointStore {
    fun load(): Map<String, List<PeerEndpoint>>

    fun save(endpoints: Map<String, List<PeerEndpoint>>)
}

/** 内存实现：测试、预览、以及「不落盘」的宿主用。 */
class InMemoryPeerEndpointStore(
    initial: Map<String, List<PeerEndpoint>> = emptyMap(),
) : PeerEndpointStore {
    private var current: Map<String, List<PeerEndpoint>> = initial

    override fun load(): Map<String, List<PeerEndpoint>> = current

    override fun save(endpoints: Map<String, List<PeerEndpoint>>) {
        current = endpoints
    }
}
