// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import com.kapp.tidings.core.nowMillis
import com.kapp.tidings.core.crypto.TlsPin
import com.kapp.tidings.core.protocol.LinkFrame
import com.kapp.tidings.core.protocol.LinkFrames
import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.Profile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * task-40 ①：「**谁先关的**」必须能从诊断里读出来。
 *
 * 真机现场：只有手机侧 attempts 在涨、且 fail=0（每次拨号都成功），说明连接**建好之后又掉了**。
 * 光看 linkConnected 分不清是"对端关了""我们判死超时"还是"我们自己替换/复查关的"，
 * 所以这三条各钉一个原因。
 */
class LinkCloseReasonTest {

    private fun profile(nodeId: String) = Profile(
        nodeId = nodeId,
        nick = nodeId,
        company = "",
        dept = "",
        team = "",
        avatar = 0,
        profileRev = 0,
        host = "h",
        platform = "linux",
        tcpPort = 17879,
        ver = "relay-0.1.0",
        caps = listOf(MsgTypes.CAP_LINK),
    )

    /** 首帧给 Hello；之后按 [then] 决定行为。 */
    private class FakeConnection(private val then: suspend () -> ByteArray?) : LinkConnection {
        override val remoteHost: String = "192.168.2.140"
        override val remotePort: Int = 17878
        override val isSecure: Boolean = false
        override val peerPin: TlsPin? = null
        private var first = true
        override suspend fun send(frame: ByteArray) = Unit
        override suspend fun receiveFrame(): ByteArray? {
            if (first) {
                first = false
                return LinkFrames.body(LinkFrame.Hello("peer")).encodeToByteArray()
            }
            return then()
        }

        override fun close() = Unit
    }

    private fun server(scope: CoroutineScope, deadAfterMs: Long = 5_000) = LinkServer(
        registry = PeerRegistry("host").apply {
            touch("peer", "192.168.2.140", 17878, profile("peer"), 1L, confirmed = true)
        },
        scope = scope,
        heartbeatMs = 10_000,
        deadAfterMs = deadAfterMs,
    )

    @Test
    fun aPeerThatClosesTheConnectionIsReportedAsPeerClosed() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val server = server(scope)
        try {
            server.handle(FakeConnection { null })
            val deadline = nowMillis() + 2_000
            while (nowMillis() < deadline && server.closeReasons()["peer"] == null) delay(20)
            assertEquals(CLOSE_PEER_CLOSED, server.closeReasons()["peer"], "对端关了必须记成 peer-closed")
            assertEquals(0, server.closeCounts()["peer"] ?: 0, "不是我们主动关的 ⇒ 计数为 0")
        } finally {
            server.close()
            scope.cancel()
        }
    }

    @Test
    fun aSilentPeerIsReportedAsDeadTimeout() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val server = server(scope, deadAfterMs = 80)
        try {
            // 首帧之后**一直不说话**（挂住）⇒ 80ms 判死。
            server.handle(FakeConnection { delay(5_000); null })
            val deadline = nowMillis() + 2_000
            while (nowMillis() < deadline && server.closeReasons()["peer"] == null) delay(20)
            assertEquals(CLOSE_DEAD_TIMEOUT, server.closeReasons()["peer"], "长时间没字节必须记成 dead-timeout")
        } finally {
            server.close()
            scope.cancel()
        }
    }

    @Test
    fun replacingAConnectionIsReportedAsReplacedAndCountedAsOurOwnClose() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val server = server(scope)
        try {
            // 第一条：对端一直不说话（挂住），好让"被替换"成为它结束的唯一原因。
            server.handle(FakeConnection { delay(5_000); null })
            delay(100)
            // 第二条：同一个 nodeId 再连一次 ⇒ 保留最新、关掉旧的。
            server.handle(FakeConnection { delay(5_000); null })
            delay(150)
            assertEquals(CLOSE_REPLACED, server.closeReasons()["peer"], "被替换必须记成 replaced（不能被旧泵覆盖成 peer-closed）")
            assertTrue((server.closeCounts()["peer"] ?: 0) >= 1, "本端主动关闭必须计数")
        } finally {
            server.close()
            scope.cancel()
        }
    }
}
