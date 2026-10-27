// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

import kotlinx.coroutines.CoroutineScope

/**
 * 发现引擎抽象(迁移缝,luciola 仓库 docs/MIGRATION-tidings.md 阶段二):
 * NodeRuntime 只依赖这五个动作;[Discovery] 是自研 legacy 实现(iOS 用),
 * JVM/Android 的 luciola 实现见 LuciolaDiscoveryEngine。
 */
interface DiscoveryEngine {
    /** 启动发现(订阅 inbound 必须在此之前完成——见 Discovery.start 的时序注释)。 */
    fun start(scope: CoroutineScope)

    fun stop()

    /** 全量宣告(entry 广播/多播 + 定向)。 */
    suspend fun announce()

    /** 定向单播 entry:跨网段 / VPN / 本机联调直填 ip:port。 */
    suspend fun announceTo(host: String, port: Int)

    /** 退出宣告(exit)。 */
    suspend fun announceExit()
}
