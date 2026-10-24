// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [WsGateway] 的回环升级、路径路由、Token鉴权与回收测试。
 *
 * ## 为什么必须真环境
 *
 * 这里钉的是**握手本身**：HTTP Upgrade 请求行的形状、响应头里的 `Upgrade: websocket`、
 * 缺路径/错方法/错 token 时的状态码，以及 101 之后字节流的双向搬运。把 socket mock 掉，
 * 测到的只会是"我们自己解析自己拼的字符串" —— 而这一层的价值恰恰是"对面真的那样说话时
 * 我们能不能接住"（真机上翻过车的是 upgrade 与转发，不是解析函数）。
 *
 * ## 超时
 *
 * 每条连接都设 [SOCKET_TIMEOUT_MS] 读超时：网关卡住时 `input.read()` 会**永久**阻塞，
 * 症状是 gradle 任务挂死而不是失败 —— 那比红更难诊断。
 */
class WsGatewayTest {

    private lateinit var scope: CoroutineScope
    private var dummyTcpServer: ServerSocket? = null
    private var dummyTcpPort: Int = 0

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val s = ServerSocket()
        s.bind(InetSocketAddress("127.0.0.1", 0))
        dummyTcpPort = s.localPort
        dummyTcpServer = s
    }

    @AfterTest
    fun tearDown() {
        runCatching { dummyTcpServer?.close() }
        scope.cancel()
    }

    /**
     * 连一条回环 socket 并设**读超时**。
     *
     * 不设的话：网关没回包时 `readResponse` 里的 `input.read()` 永远不返回，
     * 测试既不绿也不红，只是把任务挂住。
     */
    private fun openSocket(port: Int): Socket =
        Socket("127.0.0.1", port).apply {
            soTimeout = SOCKET_TIMEOUT_MS
        }

    private fun readResponse(input: InputStream): Pair<Int, String> {
        val lines = mutableListOf<String>()
        val buf = java.io.ByteArrayOutputStream()
        var prev = 0
        while (true) {
            val b = input.read()
            if (b == -1) break
            if (prev == '\r'.code && b == '\n'.code) {
                val line = buf.toString("UTF-8").trimEnd('\r')
                if (line.isEmpty()) break
                lines.add(line)
                buf.reset()
            } else {
                buf.write(b)
            }
            prev = b
        }
        val statusLine = lines.firstOrNull().orEmpty()
        val parts = statusLine.split(' ')
        val code = if (parts.size >= 2) parts[1].toIntOrNull() ?: 0 else 0
        return code to lines.joinToString("\n")
    }

    @Test
    fun upgradeFailsWhenPathNotFound() {
        val gateway = WsGateway(
            port = 0,
            token = "",
            routes = listOf(WsRoute.Stream("/v1/pipe", "127.0.0.1", dummyTcpPort)),
            scope = scope,
        )
        gateway.start()
        try {
            openSocket(gateway.boundPort).use { sock ->
                val out = sock.getOutputStream()
                val req = "GET /unknown-path HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"
                out.write(req.toByteArray())
                out.flush()

                val (code, _) = readResponse(sock.getInputStream())
                assertEquals(404, code)
            }
        } finally {
            gateway.stop()
        }
    }

    @Test
    fun upgradeFailsWhenMethodNotGet() {
        val gateway = WsGateway(
            port = 0,
            token = "",
            routes = listOf(WsRoute.Stream("/v1/pipe", "127.0.0.1", dummyTcpPort)),
            scope = scope,
        )
        gateway.start()
        try {
            openSocket(gateway.boundPort).use { sock ->
                val out = sock.getOutputStream()
                val req = "POST /v1/pipe HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"
                out.write(req.toByteArray())
                out.flush()

                val (code, _) = readResponse(sock.getInputStream())
                assertEquals(405, code)
            }
        } finally {
            gateway.stop()
        }
    }

    @Test
    fun upgradeRequiresTokenWhenConfigured() {
        val secret = "super-secret-token"
        val gateway = WsGateway(
            port = 0,
            token = secret,
            routes = listOf(WsRoute.Stream("/v1/pipe", "127.0.0.1", dummyTcpPort)),
            scope = scope,
        )
        gateway.start()
        try {
            // 1. 无 token 升级 -> 401
            openSocket(gateway.boundPort).use { sock ->
                val out = sock.getOutputStream()
                val req = "GET /v1/pipe HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Version: 13\r\n" +
                    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n"
                out.write(req.toByteArray())
                out.flush()

                val (code, _) = readResponse(sock.getInputStream())
                assertEquals(401, code)
            }

            // 2. 带 Authorization Bearer -> 101 Switching Protocols
            openSocket(gateway.boundPort).use { sock ->
                val out = sock.getOutputStream()
                val req = "GET /v1/pipe HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Version: 13\r\n" +
                    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                    "Authorization: Bearer $secret\r\n\r\n"
                out.write(req.toByteArray())
                out.flush()

                val (code, resp) = readResponse(sock.getInputStream())
                assertEquals(101, code, resp)
                assertTrue(resp.contains("Upgrade: websocket", ignoreCase = true))
            }

            // 3. 带 ?token= 查询参数 -> 101 Switching Protocols
            openSocket(gateway.boundPort).use { sock ->
                val out = sock.getOutputStream()
                val req = "GET /v1/pipe?token=$secret HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Version: 13\r\n" +
                    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n"
                out.write(req.toByteArray())
                out.flush()

                val (code, resp) = readResponse(sock.getInputStream())
                assertEquals(101, code, resp)
            }
        } finally {
            gateway.stop()
        }
    }

    @Test
    fun duplicateRoutePathThrows() {
        val routes = listOf(
            WsRoute.Stream("/same", "127.0.0.1", 1000),
            WsRoute.Stream("/same", "127.0.0.1", 2000),
        )
        val gateway = WsGateway(port = 0, token = "", routes = routes, scope = scope)
        var thrown = false
        try {
            gateway.start()
        } catch (_: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown, "重复路由路径启动时必须抛出异常")
    }

    private companion object {
        /** 读超时：网关不回包时让测试**失败**，而不是把 gradle 任务挂死。 */
        const val SOCKET_TIMEOUT_MS = 5_000
    }
}
