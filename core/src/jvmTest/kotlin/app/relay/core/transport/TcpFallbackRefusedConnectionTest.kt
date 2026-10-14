// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

import app.relay.core.protocol.Envelope
import app.relay.core.protocol.MsgTypes
import app.relay.core.protocol.makeEnvelope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * TCP 回退在**对端端口没开**时只能返回 false，绝不许抛异常。
 *
 * 为什么单独立一个测试（而且只能放 jvmTest：借端口要 `java.net.ServerSocket`）：这条路上的
 * 异常不是"发送失败"，是**崩溃**。2026-10-10 在 Pixel 6 上实测三次
 * `FATAL EXCEPTION: DefaultDispatcher-worker-N` + `java.net.ConnectException: Connection refused`
 * （`io.ktor.network.sockets.SocketImpl.connect$ktor_network`），现场是手机点「屏幕协助」→
 * UDP 重传耗尽 → `Messenger.sendViaTcp` 回退到夹具自报的 TCP 端口（没人监听）→
 * `TcpFramedChannel.sendAndAwaitAck` 里的 `connect` 在 try 之外抛出 → 穿过 `sendAwaitAck`
 * 直达 `RelayControllerScreen` 的 `scope.launch` → app 被打死（投屏页随进程消失，夹具随后
 * 看到的是启动器）。
 *
 * 断言只有一条：**不抛**，返回 false。若 `connect` 又跑回 try 之外，这个测试会以异常失败。
 */
class TcpFallbackRefusedConnectionTest {

    /** 借一个必定没人监听的端口：绑上再立刻放掉，系统短期内不会把它分配给别的进程。 */
    private fun refusedPort(): Int {
        val probe = ServerSocket(0)
        val port = probe.localPort
        probe.close()
        return port
    }

    @Test
    fun refusedTcpConnectionIsAFailureNotACrash() = runBlocking {
        val channel = TcpFramedChannel(timeoutMs = 2_000L)
        try {
            val envelope: Envelope = makeEnvelope(
                type = MsgTypes.MSG,
                from = "fixture-node",
                payload = JsonObject(emptyMap()),
            )
            val ok = channel.sendAndAwaitAck("127.0.0.1", refusedPort(), envelope)
            assertFalse(ok, "对端 TCP 端口没开 ⇒ 必须判失败（false），而不是抛出去")
        } finally {
            channel.close()
        }
    }
}
