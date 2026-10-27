// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **守住「Kotlin 绑定与本地库是同一版」这件事。**
 *
 * ## 为什么需要这条测试
 *
 * uniffi 的 Kotlin 绑定由 Rust 侧生成，而真正执行的 `.dylib` / `.so` 是另外构建的。
 * 两者版本不一致时**不会**在加载阶段报错，而是在第一次跨 FFI 读结构体时才炸：
 * 2026-10-27 实测报 `BufferUnderflowException`（Kotlin 按 6 字段读，旧引擎只写 4 字段）。
 *
 * 那次事故的代价：`SecureChannelTransport.diagnostics()` 里
 * `runCatching { session.stats() }.getOrNull() ?: return` 把异常吞成"没有会话"，
 * 于是诊断输出是空串 —— 而空串和"真的没有会话"长得一模一样，我据此误判了好几轮。
 *
 * 所以这条测试做两件事：
 * 1. 断言 `stats()` 可调用且字段齐全（布局一致）；
 * 2. 断言 `diagnostics()` **不会**把统计异常吞成空串（失败时要能看见原因）。
 */
class LinkStatsBindingContractTest {

    @Test
    fun statsIsCallableAndExposesAllCounters() {
        val session = uniffi.luciola_uniffi.SecureSession(ByteArray(32) { 7 })
        val stats = session.stats()
        // 只是读一遍：若 Kotlin 绑定比本地库多字段，这里就会抛 BufferUnderflowException。
        assertEquals(0L, stats.framesSent.toLong())
        assertEquals(0L, stats.framesReceived.toLong())
        assertEquals(0L, stats.connectionsEstablished.toLong())
        assertEquals(0L, stats.reconnectAttempts.toLong())
        assertEquals(0L, stats.framesEnqueued.toLong())
        assertEquals(0L, stats.framesDequeued.toLong())
    }

    /**
     * 空闲传输的 `diagnostics()` 必须说明"为什么空"，而不是返回空串。
     *
     * 这条守的是可观测性：空串无法区分「没有会话」与「有会话但读统计失败」，
     * 而后者正是布局不一致的典型表现。
     */
    @Test
    fun diagnosticsExplainsEmptySnapshot() {
        val factory = LuciolaTransportFactory(
            com.kapp.tidings.core.protocol.Profile(
                nodeId = "probe", nick = "probe", company = "", dept = "", team = "", avatar = 1,
                profileRev = 1, host = "h", platform = "jvm", tcpPort = 0, ver = "1", caps = emptyList(),
            ),
            0,
            seedPersist = { "ab".repeat(32) },
        )
        val transport = factory.createTransport(PeerRegistry("probe"))
        try {
            val text = transport.diagnostics()
            assertTrue(text.isNotEmpty(), "diagnostics() 不许返回空串（空串无法诊断）")
            assertTrue(
                text.contains("none(") || text.contains("sent="),
                "空快照必须说明原因（none(inboundSession=...,out=...,inByEp=...)），实际: " + text,
            )
        } finally {
            transport.stop()
        }
    }
}
