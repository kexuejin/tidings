// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.protocol

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
    val ver: String,
    val caps: List<String>,
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
            ver = ver,
            caps = caps,
        )
    }
}
