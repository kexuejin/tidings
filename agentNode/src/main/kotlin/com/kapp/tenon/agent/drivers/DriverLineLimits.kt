// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

/**
 * 驱动行流的单行长度上限（触发式编排提案 §8.2 传输防火墙 / §9 阶段三）。
 *
 * 三处读取循环共用这一份常量：`ProcessAgentDriver`（CLI 子进程 stdout）、
 * `ExternalPluginDriver`（插件 NDJSON 流）、`WorktreeSandboxExecutor`（沙箱命令
 * 输出泵）。`readLine()`/`useLines` 对行长零限制——一个失控子进程输出一行 1 GB
 * 的「JSON」会整行读进宿主堆里，OOM 的现场连一行日志都留不下。
 *
 * 预算依据：协议侧最大事件形状是 64 KiB 帧（Limits.MAX_FRAME_BYTES），卡 24 KiB；
 * 这里给 4 倍余量。超限不是静默跳过——调用方必须以各自的方式**响亮地**失败
 * （DriverEvent.Error / 异常），与「认不出的行优雅跳过」的安全红线区分开：
 * 超长行是宿主无法理解的事件，继续读等于把 OOM 风险留给下一行。
 */
object DriverLineLimits {
    /** 单行字符数上限（≈ 1 MiB 的 UTF-8，事件帧预算的 4 倍余量）。 */
    const val MAX_LINE_CHARS: Int = 256 * 1024
}
