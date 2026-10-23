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
            Socket("127.0.0.1", gateway.boundPort).use { sock ->
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
            Socket("127.0.0.1", gateway.boundPort).use { sock ->
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
            Socket("127.0.0.1", gateway.boundPort).use { sock ->
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
            Socket("127.0.0.1", gateway.boundPort).use { sock ->
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
            Socket("127.0.0.1", gateway.boundPort).use { sock ->
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
}
