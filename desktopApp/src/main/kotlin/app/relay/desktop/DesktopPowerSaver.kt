// SPDX-License-Identifier: GPL-3.0-only

package app.relay.desktop

import app.relay.core.protocol.DEFAULT_TIMINGS
import app.relay.core.protocol.Timings

/**
 * 桌面「省电后台档」。
 *
 * ## 省的是什么
 * 桌面端收进托盘后仍是一个常驻进程，空闲时唯一的开销就是周期性的心跳与扫描：
 * `presenceInterval`（广播自己在）、`sweepInterval`（清理离线）、
 * `profileProbeInterval`（补资料）。档位把这些周期整体放慢，
 * 让托盘里的捎话少唤醒几次 CPU 与网卡。
 *
 * ## 故意不放慢的东西
 * 只动**空闲**周期，不动与一次真实传输有关的时间参数：
 * - `ackRetrySchedule` / `tcpAckTimeout`：改慢会让真正发出去的文件更晚拿到确认；
 * - `directedReplyInterval`、`entryReplyJitter*`：决定握手快慢，用户在等；
 * - `queueTtl` / `dedupTtl` / `recallWindow`：是正确性窗口，不是频率。
 *
 * ## 为什么连 offlineAfter 一起放大
 * `offlineAfter` 必须显著大于 `presenceInterval`，否则对方还没来得及广播下一次心跳，
 * 我们这边就已经把它判成离线了 —— 表现为节点在列表里反复闪进闪出。默认档是
 * 90s / 30s（3 倍），慢档保持同样的 3 倍关系（270s / 90s），只是整体后移。
 *
 * ## 生效时机
 * [Timings] 是 `NodeRuntime` 的构造参数，所以切档必须重建运行时。这不是实现偷懒，
 * 是「一个节点的心跳周期」本来就没有中途改语义的合理定义。
 */
internal object DesktopPowerSaver {

    /** 慢档相对默认档的倍率；心跳与扫描一起放慢，保持两者比例不变。 */
    const val SLOWDOWN: Int = 3

    fun timings(base: Timings = DEFAULT_TIMINGS): Timings = base.copy(
        presenceInterval = base.presenceInterval * SLOWDOWN,
        offlineAfter = base.offlineAfter * SLOWDOWN,
        sweepInterval = base.sweepInterval * SLOWDOWN,
        profileProbeInterval = base.profileProbeInterval * SLOWDOWN,
    )
}