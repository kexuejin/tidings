// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

import app.relay.core.crypto.TlsPin
import app.relay.core.protocol.LinkFrame
import app.relay.core.protocol.LinkFrames
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSTemporaryDirectory

/**
 * iOS 的 TLS 自检（task-26 ① 第 3 步的落点）：在**App 进程**里跑一次完整双向握手。
 *
 * 为什么必须是 App 进程：Kotlin/Native 的测试二进制不是 app，`SecItemAdd` 直接返回
 * -25291 errSecNotAvailable（没有 app 容器 / keychain-access-group），拿不到 `sec_identity_t`，
 * 于是 `startServer`/`connect` 一律拒绝 —— 那是**刻意的 fail-closed**，不是缺陷。
 * 真机/模拟器的 App 进程里有容器，这一步才跑得起来。
 *
 * 返回人类可读的多行报告（由 Swift 侧按启动参数打印）：监听是否起来、绑到哪个端口；
 * **正确 pin** ⇒ 建连成功且服务端收到一帧；**错误 pin** ⇒ 建连必须失败（fail-closed 的正面证据）。
 */
@Suppress("LongMethod")
fun relayTlsHandshakeSelfTest(): String = runBlocking {
    val report = StringBuilder()
    val dir = NSTemporaryDirectory() + "relay-tls-selftest"
    val server = IosTlsLinkTransport({ dir + "/server" }, { listOf("127.0.0.1") })
    val client = IosTlsLinkTransport({ dir + "/client" }, { listOf("127.0.0.1") })
    try {
        report.appendLine("serverPin=" + server.localPin.wire)
        val received = CompletableDeferred<ByteArray>()
        val started = server.startServer(0) { connection ->
            connection.receiveFrame()?.let { received.complete(it) }
        }
        report.appendLine("listenerStarted=" + started + " port=" + server.boundServerPort)
        if (!started) {
            report.appendLine("FAIL: 监听没起来（多半是 keychain 不可用 —— 这一层必须是 App 进程）")
            return@runBlocking report.toString()
        }
        val port = server.boundServerPort ?: 0
        val good = client.connect("127.0.0.1", port, server.localPin, CONNECT_TIMEOUT_MS)
        report.appendLine("correctPinConnected=" + (good != null))
        if (good != null) {
            good.send(LinkFrames.encode(LinkFrame.Ping))
            val got = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { received.await() }
            report.appendLine("serverReceivedFrame=" + (got != null))
            good.close()
        } else {
            report.appendLine("FAIL: 正确 pin 也没连上")
        }
        val wrongPin = TlsPin.ofSpki(ByteArray(91) { 0x5A })
        val wrong = client.connect("127.0.0.1", port, wrongPin, CONNECT_TIMEOUT_MS)
        report.appendLine("wrongPinConnected=" + (wrong != null) + " (expect false)")
        wrong?.close()
        report.appendLine(if (good != null && wrong == null) "RESULT=PASS" else "RESULT=FAIL")
    } catch (t: Throwable) {
        report.appendLine("EXCEPTION: " + t.message)
        report.appendLine("RESULT=FAIL")
    } finally {
        server.stopServer()
    }
    report.toString()
}

private const val CONNECT_TIMEOUT_MS = 5_000L
