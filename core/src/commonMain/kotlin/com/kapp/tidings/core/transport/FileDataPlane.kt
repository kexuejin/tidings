// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

/**
 * 文件字节搬运的**数据面接缝**（第 1 步：先抽缝，行为不变）。
 *
 * 现在只有一种实现 [LegacyTcpDataPlane]（逐字等于今天的 `pullTransfer`：拨对端
 * `tcpPort`/跨网 TLS，收裸流、校验 sha、`.part` 转正）。加密信道的实现
 * （[SecureChannelDataPlane]，下一步）只要满足同一个接口，[TransferManager] 不必改。
 *
 * ## 为什么接口方法长得像 PullOptions
 *
 * 因为续传/校验/去重/取消**全部已经在 legacy 实现里**（`PullSession`），这些语义与
 * 「字节从哪条连接来」无关。抽缝时**刻意不抽这些语义**，只抽"谁来搬字节"，
 * 否则就是一次行为重写（那是 risk，不是 progress）。
 */
interface FileDataPlane {
    /** 读一个 offer 的全部文件到 [saveDir]；失败抛 [PullException]，取消走 [PullCancelRef]。 */
    suspend fun pull(opts: PullOptions)
}

/** 今天的实现：明文 TCP（及跨网 TLS 计划）。行为逐字不变。 */
class LegacyTcpDataPlane : FileDataPlane {
    override suspend fun pull(opts: PullOptions) = pullTransfer(opts)
}
