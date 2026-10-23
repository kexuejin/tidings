// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import com.kapp.tidings.core.ioDispatcher
import com.kapp.tidings.core.testing.createTempDir
import com.kapp.tidings.core.testing.removeRecursively
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * F1 失败现场的**可归因性**（task-61）：真机上一次成功、一次「传输失败：timeout」+ 0 字节
 * .part，而当时看不出**拨了哪个候选、卡在哪一步**。这两条测试钉住的就是那两件事：
 *
 *  1. 拨号全失败时，失败原因里必须**逐条列出**拨过的 host:port / 传输类型 / 耗时；
 *  2. 连上了但对端不说话时，必须区分成 timeout-waiting-pull-ok（等第一帧），而不是笼统的
 *     timeout —— 「0 字节 .part」与「传到一半断」是两种完全不同的现场。
 */
class PullFailureDiagnosticsTest {

    private val dirs = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        dirs.forEach { removeRecursively(it) }
        dirs.clear()
    }

    private fun saveDir(): String = createTempDir("relay-pull-diag").also { dirs += it }

    @Test
    fun everyDialAttemptIsNamedInTheFailureReason() = runBlocking {
        val saveDir = saveDir()
        // 两个必然连不上的候选：一个明文、一个 TLS（connectRaw 直接返回 null）。
        val attempts = listOf(
            PullAttempt("127.0.0.1", 1, PullAttemptTransport.Plaintext),
            PullAttempt("127.0.0.1", 1, PullAttemptTransport.Tls),
        )
        val failure = assertFailsWith<PullException> {
            pullTransfer(
                PullOptions(
                    host = "127.0.0.1",
                    port = 1,
                    selfId = "self",
                    transferId = "t-diag",
                    files = listOf(IncomingFilePlan("f1", "a.bin", 16L)),
                    saveDir = saveDir,
                    attempts = attempts,
                    connectTimeoutMs = 300L,
                    connectRaw = { _, _ -> null },
                ),
            )
        }
        assertTrue(
            failure.reason.startsWith("socket-error"),
            "基础原因仍是 socket-error（UI 与既有映射依赖它）：" + failure.reason,
        )
        assertTrue(
            failure.reason.contains("127.0.0.1:1 plain"),
            "失败原因里必须写明拨过的明文候选：" + failure.reason,
        )
        assertTrue(
            failure.reason.contains("127.0.0.1:1 tls"),
            "失败原因里必须写明拨过的 TLS 候选：" + failure.reason,
        )
        assertTrue(
            failure.reason.contains("ms"),
            "每条候选都要有耗时（判断是不是拨到超时）：" + failure.reason,
        )
    }

    @Test
    fun aPeerThatAcceptsButNeverAnswersIsWaitingForPullOk() = runBlocking {
        val saveDir = saveDir()
        // 一个只会 accept、永远不说话的「哑对端」：真机上「0 字节 .part + timeout」的现场。
        val selector = SelectorManager(ioDispatcher)
        val server = aSocket(selector).tcp().bind(InetSocketAddress("127.0.0.1", 0))
        val port = (server.localAddress as InetSocketAddress).port
        try {
            val failure = assertFailsWith<PullException> {
                pullTransfer(
                    PullOptions(
                        host = "127.0.0.1",
                        port = port,
                        selfId = "self",
                        transferId = "t-silent",
                        files = listOf(IncomingFilePlan("f1", "a.bin", 16L)),
                        saveDir = saveDir,
                        idleTimeoutMs = 400L,
                    ),
                )
            }
            assertTrue(
                failure.reason == "timeout-waiting-pull-ok",
                "连上了但等不到第一帧 ⇒ 必须点名是哪一步，而不是笼统的 timeout：" + failure.reason,
            )
        } finally {
            runCatching { server.close() }
            runCatching { selector.close() }
        }
    }
}
