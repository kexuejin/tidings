// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.Profile

/**
 * `scr1`（投屏画面走独立加密会话）的协商判据。
 *
 * 规则与 [FileChannelNegotiation] 同一条：**双方都声明 +\(对端还在档案里给出端口\)**
 * 才走新数据面。三条都满足才动，缺一条就继续走 legacy（17879 明文 / 17882 跨网 TLS）——
 * 半升级的两种组合都不会出现"我在等画面、它在等握手"：
 *  - 只有我声明：对端不认识这条能力，它不会拨我的画面端口；
 *  - 只有对端声明：我不认识，我不拨，它按老路等我拨 —— 投屏照旧。
 *
 * 端口必须是**对端声称的**那一个：luciola 的监听是 Rust 绑的，端口不一定是
 * 发现上报的那个（真机踩过：发现端口是引擎临时端口 52213，信道在 17878）。
 */
object ScreenChannelNegotiation {

    fun canUseChannel(self: Profile, peer: Profile?): Boolean {
        if (peer == null) return false
        return hasCap(self) &&
            hasCap(peer) &&
            peer.screenPort != null &&
            peer.screenPort in 1..65535
    }

    /** 便捷重载：只给 caps 与端口（调用方已经有自己的 caps 时用）。 */
    fun canUseChannel(selfCaps: List<String>, peerCaps: List<String>?, peerScreenPort: Int?): Boolean =
        peerCaps != null &&
            selfCaps.contains(MsgTypes.CAP_SCREEN_CHANNEL) &&
            peerCaps.contains(MsgTypes.CAP_SCREEN_CHANNEL) &&
            peerScreenPort != null && peerScreenPort in 1..65535

    private fun hasCap(profile: Profile): Boolean = profile.caps.contains(MsgTypes.CAP_SCREEN_CHANNEL)
}
