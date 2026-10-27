// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.protocol

import kotlinx.serialization.Serializable

/**
 * 节点档案。字段顺序必须与 TS 侧 `JSON.stringify` 的输出顺序一致
 * （`src/shared/protocol.ts` 的 `Profile` 接口声明顺序），因为我们是按字节对拍的。
 *
 * 注意 `encodeDefaults = false`：**有默认值的属性在等于默认值时会被整个省略**。
 * 所以凡是 golden vector 里必须出现的字段（company/dept/team 的空串、ver、caps）
 * 一律不给默认值；只有真正可选的 `avatarHash` 才是 `String? = null`。
 */
@Serializable
data class Profile(
    val nodeId: String,
    val nick: String,
    val company: String,
    val dept: String,
    val team: String,
    val avatar: Int,
    val avatarHash: String? = null,
    val profileRev: Int,
    val host: String,
    val platform: String,
    val tcpPort: Int,
    /**
     * K2 的 TLS 监听端口（可选键）。null = 这个节点不提供 TLS 长连接。
     *
     * 为什么单独一个端口而不是复用 [tcpPort] 做嗅探：老对端在这条连接上发的是
     * 4 字节长度前缀，而 TLS 记录以 0x16 开头 —— 混在一起是事故（task-13 裁决 ③）。
     * 有默认值 ⇒ 老节点的档案里没有它，读出来是 null，行为一字不变。
     */
    val tlsPort: Int? = null,
    /**
     * 本机的 TLS pin（SPKI 指纹，`sha256:<hex>`）——**配对时随资料带过来**（task-38）。
     *
     * 为什么放在档案里：二维码是**主机→手机单向**的，手机扫完只拿到主机的 pin；主机要验手机的
     * 客户端证书，就必须有手机的 pin。把手机自己的 pin 随 entry/alive/profile 带过去，主机在
     * **首次收到该 nodeId 的合法配对信息**时就能固定正确的 pin ⇒ TOFU 的首次使用竞态窗口消失。
     *
     * 可选键（默认 null）：旧端不认它（[validateProfile] 逐字段校验、不整帧白名单），
     * 老手机不发它 ⇒ 主机退回 TOFU（**如实记为降级路径**，见 `PeerRegistry` 的 pin 来源）。
     */
    val tlsPin: String? = null,
    /**
     * 本机**全部可路由地址**（可选键）。
     *
     * ## 为什么必须有它
     *
     * 对端记录的对端地址原先**只来自「发现包的源地址」**。而 UDP 广播不跨 VPN 网段
     * （Tailscale / ZeroTier 是点对点三层隧道，没有广播语义）。于是：
     *
     * 1. 两边在局域网时互通，各自记下对方的 `192.168.x.x`；
     * 2. 一方切到 VPN ⇒ 那个地址不可达；
     * 3. 广播到不了 VPN 网段 ⇒ **双方都学不到对方的新地址**；
     * 4. 谁也收不到谁的包 ⇒ 连 `rememberSource` 那条兜底（收包即记源地址）也触发不了。
     *
     * 死锁。真机现场：红米 30 分钟一个包都没到本机（`lastSeen` 停在半小时前），
     * 而本机 `ping` 它的 Tailscale 地址 **2.2 秒就通** —— 网络通，是协议不知道怎么走。
     *
     * 带上全部地址后，**任何一条通路收到过一次 profile**，对端就拿到我所有可达地址
     * （含 VPN 段），写进候选池按序尝试 ⇒ 死锁被打破，不依赖广播跨网。
     *
     * ## 兼容性
     *
     * 可选键。`validateProfile` 是**逐字段校验、不是整帧白名单**（`tlsPort` / `tlsPin`
     * 同款先例），老端读了会忽略、不会整帧拒收。null = 老版本不发，行为一字不变。
     */
    val addresses: List<String>? = null,
    val ver: String,
    val caps: List<String>,
    /**
     * luciola 加密信道的 TCP 监听端口（可选键）。
     *
     * 为什么必须单独一格：发现引擎上报的端口不一定是信道端口 —— 真机实测
     * luciola 发现的 udpPort 可能是引擎的**临时端口**（52213），而加密信道在
     * 17878 上监听；拿发现端口去拨号会拨错目标。写进档案后，对端按这个端口发送。
     *
     * 兼容性：可选键、老端忽略（同 tlsPort/tlsPin/addresses 的先例）；
     * null = 这个节点不提供 luciola 信道（legacy 节点），行为一字不变。
     */
    val luciolaPort: Int? = null,
) {
    companion object {
        /**
         * 手机端档案：平台伪装 linux（协议里没有 mobile）。
         *
         * [caps] 默认 [MsgTypes.MOBILE_CAPS]，可由调用方按用户的能力开关逐项收窄
         * （手机设置屏里那两个开关）；两个都不声明也是合法的，对端按老端处理。
         */
        fun mobile(
            nodeId: String,
            nick: String,
            host: String,
            tcpPort: Int = Protocol.DEFAULT_TCP_PORT,
            tlsPort: Int? = null,
            tlsPin: String? = null,
            ver: String,
            profileRev: Int = 0,
            avatar: Int = -1,
            company: String = "",
            dept: String = "",
            team: String = "",
            caps: List<String> = MsgTypes.MOBILE_CAPS,
        ): Profile = build(
            nodeId = nodeId,
            nick = nick,
            host = host,
            tcpPort = tcpPort,
            tlsPort = tlsPort,
            tlsPin = tlsPin,
            ver = ver,
            profileRev = profileRev,
            avatar = avatar,
            company = company,
            dept = dept,
            team = team,
            caps = caps,
            // 协议里没有 mobile，手机端按约定伪装成 linux —— 见 Platforms 的注释。
            platform = Platforms.LINUX,
        )

        /**
         * 本机常驻 agent 节点的档案：比手机端多声明一个 [MsgTypes.CAP_AGENT]，
         * 这样手机 UI 能把它和人类同事分开显示，而**普通桌面节点无需任何改动**
         * （它把 `ag1` 当"看不懂的能力"照原样显示）。
         *
         * [platform] 必须由调用方按**真实宿主系统**传入：这个节点跑在用户的电脑上，
         * 不是手机 —— 早先这里复用了手机端的 linux 伪装，于是 macOS 上跑出来的
         * agentNode 跟桌面端一样报 `platform=linux`，而桌面端报的是 `mac`，
         * 同一个人的两个节点在 UI 上却显示成两种系统。
         */
        fun agent(
            nodeId: String,
            nick: String,
            host: String,
            tcpPort: Int = Protocol.DEFAULT_TCP_PORT,
            tlsPort: Int? = null,
            tlsPin: String? = null,
            ver: String,
            profileRev: Int = 0,
            avatar: Int = -1,
            company: String = "",
            dept: String = "",
            team: String = "",
            platform: String = Platforms.LINUX,
        ): Profile = build(
            nodeId = nodeId,
            nick = nick,
            host = host,
            tcpPort = tcpPort,
            tlsPort = tlsPort,
            tlsPin = tlsPin,
            ver = ver,
            profileRev = profileRev,
            avatar = avatar,
            company = company,
            dept = dept,
            team = team,
            caps = MsgTypes.AGENT_CAPS,
            platform = platform,
        )

        private fun build(
            nodeId: String,
            nick: String,
            host: String,
            tcpPort: Int,
            tlsPort: Int?,
            tlsPin: String?,
            ver: String,
            profileRev: Int,
            avatar: Int,
            company: String,
            dept: String,
            team: String,
            caps: List<String>,
            platform: String,
        ): Profile = Profile(
            nodeId = nodeId,
            nick = nick,
            company = company,
            dept = dept,
            team = team,
            avatar = avatar,
            profileRev = profileRev,
            host = host,
            platform = platform,
            tcpPort = tcpPort,
            tlsPort = tlsPort,
            tlsPin = tlsPin,
            ver = ver,
            caps = caps,
        )
    }
}
