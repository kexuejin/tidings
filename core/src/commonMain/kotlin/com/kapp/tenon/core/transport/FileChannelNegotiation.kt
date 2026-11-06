// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tenon.core.transport

import com.kapp.tenon.core.protocol.MsgTypes

/**
 * `fch1`（文件字节走加密信道）的协商判据（分级声明版，D-018 方案 b）。
 *
 * 规则是「**本端事实 × 对端声明**」：本端 luciola 数据面真的起来了（开关 + 绑定成功，
 * 由调用方以 selfChannelAvailable 注入），**且**对端声明了 [MsgTypes.CAP_FILE_CHANNEL]
 * —— 才走新数据面。单向不匹配的后果是"发送端推、收端等"——两侧都看起来正常，实际
 * 文件永远不来（真机死锁现场见 TransferManager.scheduleChannelStallFallback 的 KDoc）。
 *
 * ## 为什么 self 侧不再是 caps 声明（分级声明，task-10/12）
 *
 * 桌面 caps 16/16 满额（Limits.CAPS=16），luciola-on 时 fch1/scr1 把广播撑到 18 项 ⇒
 * 对端整份拒收 profile（存量自拒，自 56688102 起）。fch1/scr1 停止广播后，本端
 * 「能不能走信道」由**本地事实**承担（数据面是否真的起来），广播面减到 ≤16；
 * 对端解不解决得动仍看它的 caps —— 语义分工：对端声明 =「它解得动」，本端事实 =
 * 「我这条数据面真的起来了」。
 *
 * 注意与 [MsgTypes.CAP_FILE_DIRECT]（`fd1`）无关：那条管"要不要弹确认框"，这条管
 * "字节走哪条路"。把两者混用会让老端收到它读不懂的字节帧。
 */
object FileChannelNegotiation {

    /**
     * 判据：本端数据面起了 **且** 对端解得动。
     *
     * 「对端解得动」有两条证据，任一条成立即可：
     * 1. 它广播了 `fch1`（旧端，D-018 之前）；
     * 2. 它**已经用信道把 offer 推过来了**（[peerPushedOverChannel]）—— 事实比声明硬。
     *
     * 第 2 条是 D-018 之后唯一可行的路：`fch1` 为压 caps 到 16 项已停止广播，
     * 两端都是新端时第 1 条**恒为 false**，只认它会让信道文件面永远协商失败，
     * 退回早已退役的 legacy 拨号端口 ⇒ 表现为「patch.diff 传输失败：网络连接中断」（产物永远到不了手机）。
     */
    fun canUseChannel(
        selfChannelAvailable: Boolean,
        peerCaps: List<String>?,
        peerPushedOverChannel: Boolean = false,
    ): Boolean = selfChannelAvailable &&
        (peerPushedOverChannel || peerCaps?.contains(MsgTypes.CAP_FILE_CHANNEL) == true)
}
