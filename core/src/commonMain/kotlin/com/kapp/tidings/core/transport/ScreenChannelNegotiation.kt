// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.Profile

/**
 * `scr1`（投屏画面走独立加密会话）的协商判据（分级声明版，D-018 方案 b）。
 *
 * 规则与 [FileChannelNegotiation] 同一条「**本端事实 × 对端声明**」：本端画面信道真的
 * 起来了（调用方以 selfChannelAvailable 注入），对端声明 scr1 且给出端口 —— 才走新
 * 数据面。缺任何一条就继续走 legacy（17879 明文 / 17882 跨网 TLS）——
 * 半升级的两种组合都不会出现"我在等画面、它在等握手"：
 *  - 只有我起来：对端不认识这条能力，它不会拨我的画面端口；
 *  - 只有对端声明：我不认识，我不拨，它按老路等我拨 —— 投屏照旧。
 *
 * ## 为什么 self 侧不再是 caps 声明（分级声明，task-10/12）
 *
 * 桌面 caps 16/16 满额，scr1 广播会把 luciola-on 桌面撑到超限（存量自拒）。
 * 本端「能不能走画面信道」由**本地事实**承担（screenChannelHost 是否真的起来）。
 *
 * 端口必须是**对端声称的**那一个：luciola 的监听是 Rust 绑的，端口不一定是
 * 发现上报的那个（真机踩过：发现端口是引擎临时端口 52213，信道在 17878）。
 */
object ScreenChannelNegotiation {

    /** 判据：本端画面信道起来 **且** 对端声明 scr1 **且** 对端给出合法端口。 */
    fun canUseChannel(selfChannelAvailable: Boolean, peer: Profile?): Boolean {
        if (peer == null) return false
        return selfChannelAvailable &&
            peer.caps.contains(MsgTypes.CAP_SCREEN_CHANNEL) &&
            peer.screenPort != null &&
            peer.screenPort in 1..65535
    }
}
