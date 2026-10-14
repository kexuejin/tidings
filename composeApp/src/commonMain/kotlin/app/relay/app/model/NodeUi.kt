// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app.model

import app.relay.app.BannerMessage
import app.relay.core.protocol.MsgTypes
import app.relay.platform.Transport
enum class NodePhase { Stopped, Starting, Running, Failed }

/** mDNS 发现的服务。 */
data class MdnsServiceUi(
    val serviceName: String,
    val host: String,
    val port: Int,
)

/** 网络与监听状态 —— `design-spec.json` 的 `NetworkStatusCard` 要的全部字段。 */
data class NetworkUi(
    val phase: NodePhase = NodePhase.Stopped,
    val transport: Transport = Transport.None,
    val localAddresses: List<String> = emptyList(),
    val vpnActive: Boolean = false,
    val boundUdpPort: Int = 0,
    val failure: BannerMessage? = null,
    /**
     * mDNS 注册这一步本身的原始结果：`true` 注册成功、`false` 注册失败、`null` 未启动。
     *
     * 这是**输入**，不是给界面看的结论 —— 界面要读 [discoverable]。
     * 之所以分开：注册一次就长期有效，而"能不能被别人发现"还取决于本机有没有可用地址，
     * 后者会随换网来回变；如果这里直接写结论，换网时改小一次就再也回不到"广播中"了。
     */
    val mdnsAdvertised: Boolean? = null,
) {
    val isRunning: Boolean get() = phase == NodePhase.Running

    /**
     * 界面上的「可被自动发现」：注册成功**且**本机有可用地址。
     *
     * 无地址时说"广播中"是假话 —— 广播根本发不出去（真机复验：关掉 Wi-Fi 后
     * 连接方式已改「没有网络」，这一行却还写着"广播中"）。
     * 结论放在这里而不是调用点，是为了让"忘了纠正"变成编译期问题。
     */
    val discoverable: Boolean?
        get() = when {
            mdnsAdvertised == null -> null
            localAddresses.isEmpty() -> false
            else -> mdnsAdvertised
        }
}

/**
 * 一个对端节点。人类同事与 agent 节点共用这一个模型，
 * 由 [isAgent] 区分显示 —— 这正是 `PeerRow.kind_badge[human,agent]` 的落点。
 */
data class PeerUi(
    val nodeId: String,
    val nick: String,
    /**
     * **本机**给这个节点起的备注名（节点备注规范）。空串 = 没起过。
     *
     * 它**不进协议**：备注是"我叫他什么"，不是"他叫什么" —— 两台设备给同一个人
     * 起不同备注是完全正常的，同步过去只会让对方的通讯录被我的叫法污染。
     * 落盘在 `RelaySettings.peerRemarks`（key = `nodeId`），由
     * [RelayController.setPeerRemark] 写。
     *
     * 取值优先级见 [displayName]：备注 > 昵称 > 主机名 > IP > nodeId 前 8 位。
     * 这正是原型 `nodeDetailBody`（`prototype/relay.html:3296`）逐字
     * `const name = p.remark || p.nick;` 的那一条，也是节点备注设计的原始动机
     * —— 局域网里两台机器可能都叫「mac mini」，靠备注才分得清谁是谁。
     */
    val remark: String = "",
    val company: String = "",
    val dept: String = "",
    val team: String = "",
    val avatar: Int = -1,
    val avatarHash: String? = null,
    val host: String = "",
    val ip: String = "",
    /**
     * 对端的 UDP 端口。存在的理由是**把手动节点和真实节点对上号**：
     * 用户填的是 `(host, port)`，判断"这个地址到底有没有人应答"必须两个都比，
     * 只看 IP 会把同一台机器上的两个节点混成一个（见 [manualPeerStatuses]）。
     */
    val udpPort: Int = 0,
    val platform: String = "",
    val tcpPort: Int = 0,
    val caps: List<String> = emptyList(),
    val online: Boolean = false,
    val lastSeenAt: Long = 0L,
) {
    /**
     * 展示名。逐级回落：备注 > 昵称 > 主机名 > IP > nodeId 前 8 位。
     *
     * 原型逐字 `const name = p.remark || p.nick;`（`prototype/relay.html:3297`），
     * 遵循节点显示优先级规范。
     *
     * 空标题是绝对不行的 —— 节点列表里一行没名字，用户不知道那是谁。
     */
    val displayName: String
        get() = remark.ifBlank { nick }.ifBlank { host.ifBlank { ip.ifBlank { nodeId.take(8) } } }

    val subtitle: String
        get() = listOf(company, dept, team).filter { it.isNotBlank() }
            .joinToString(" · ")
            .ifBlank { ip.ifBlank { host } }

    /** 见 `MsgTypes.CAP_AGENT`：用 caps 里的自定义键标注 agent 节点，桌面端零改动。 */
    val isAgent: Boolean get() = MsgTypes.CAP_AGENT in caps
}
