// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.Profile

/**
 * `fch1`（文件字节走加密信道）的协商判据。
 *
 * 规则刻意简单且**双向**：只有当**双方**都声明了 [MsgTypes.CAP_FILE_CHANNEL] 时才走新数据面。
 * 单向声明的后果是"发送端推、收端等"——两侧都看起来正常，实际文件永远不来。
 *
 * 注意与 [MsgTypes.CAP_FILE_DIRECT]（`fd1`）无关：那条管"要不要弹确认框"，这条管
 * "字节走哪条路"。把两者混用会让老端收到它读不懂的字节帧。
 */
object FileChannelNegotiation {

    fun canUseChannel(self: Profile, peer: Profile?): Boolean {
        if (peer == null) return false
        return hasCap(self) && hasCap(peer)
    }

    /** 便捷重载：只给 peer 的 caps（调用方已经有自己的 caps 时用）。 */
    fun canUseChannel(selfCaps: List<String>, peerCaps: List<String>?): Boolean =
        peerCaps != null &&
            selfCaps.contains(MsgTypes.CAP_FILE_CHANNEL) &&
            peerCaps.contains(MsgTypes.CAP_FILE_CHANNEL)

    private fun hasCap(profile: Profile): Boolean = profile.caps.contains(MsgTypes.CAP_FILE_CHANNEL)
}
