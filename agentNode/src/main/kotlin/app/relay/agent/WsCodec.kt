// SPDX-License-Identifier: GPL-3.0-only

package app.relay.agent

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64

/**
 * RFC 6455 的最小实现：握手 + 分帧。
 *
 * 为什么单独成文件：字节管道（TCP 流）与数据报桥（UDP）两个端点共用它。
 * **鉴权尤其不能各写一份** —— 两条路都是公网进来的 Upgrade 请求，只校验一边等于没校验。
 *
 * 为什么不用现成库：`ktor-network` 是 core 的 `implementation`，不传递到 agentNode 的编译类路径；
 * ktor-server / OkHttp 这类 WebSocket 实现要么多一个传递依赖，要么写不完整。
 * 而手写要记住的坑只有一处，值不值得全看那一条：**MASK 位在第二个字节的最高位**
 * （byte0 = FIN/RSV/OPCODE，byte1 = MASK/LEN）。写错的话每个正常客户端帧都被判成「未掩码」而断开，
 * 现象是「握手成功、然后立刻断开」，很容易误判成隧道或代理的问题。
 */
internal object Ws {

    const val OP_CONTINUATION = 0x0
    const val OP_TEXT = 0x1
    const val OP_BINARY = 0x2
    const val OP_CLOSE = 0x8
    const val OP_PING = 0x9
    const val OP_PONG = 0xA

    /** 下行切帧粒度。单帧要整帧进内存，太大不划算，太小帧头开销占比高。 */
    const val CHUNK = 64 * 1024

    /**
     * 单条消息上限。协议正文是分块传文件的 base64 段，64 KiB 一帧已经很宽裕；
     * 设上限是为了一个坏长度头不会直接把本进程 OOM。
     */
    const val MAX_FRAME_BYTES = 16L * 1024 * 1024

    const val MAX_HEADER_LINE = 8 * 1024

    private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    /**
     * 服务端发出的帧一律不加掩码（RFC 6455 §5.1）。
     *
     * **127 分支的长度必须先提升成 `Long`，这里踩过一个能把文件传输整段写坏的坑。**
     * JVM 的 `Int` 移位距离按 32 取模，所以 `(n ushr (8 * (7 - it)))` 在 it=0..3 时
     * 请求的 56/48/40/32 会被折成 24/16/8/0，写出来是 `00 00 01 00 00 01 00 00`
     * 这种「高 4 字节重复低 4 字节」的垃圾。对端把它按大端解出来是天文数字，
     * 现象是：小消息一切正常，一传大文件就从第一帧 64 KiB 之后整个失步、
     * 客户端抛长度异常 —— 看起来像网络问题、像内存不够、像代理截断，都不是。
     * 提升成 `Long` 后移位距离按 64 取模，56/48/…/0 全部落在有效范围内。
     *
     * 为什么平时不容易发现：`CHUNK` 正好是 65536，而 socket 读只有在缓冲区填满时
     * 才会返回满 65536 —— 空载和短文本永远走不到 127 分支，文件传输一上来就踩中。
     */
    fun encode(payload: ByteArray, opcode: Int): ByteArray {
        val n = payload.size
        val head = when {
            n < 126 -> byteArrayOf((0x80 or opcode).toByte(), n.toByte())
            n < 65536 -> byteArrayOf((0x80 or opcode).toByte(), 126.toByte()) +
                byteArrayOf((n shr 8).toByte(), (n and 0xFF).toByte())

            else -> {
                val len = n.toLong()
                byteArrayOf((0x80 or opcode).toByte(), 127.toByte()) + ByteArray(8) {
                    (len ushr (8 * (7 - it))).toByte()
                }
            }
        }
        return head + payload
    }

    fun closeBody(code: Int, reason: String): ByteArray {
        val r = reason.encodeToByteArray()
        return ByteArray(2 + r.size).also {
            it[0] = (code shr 8).toByte()
            it[1] = (code and 0xFF).toByte()
            r.copyInto(it, 2)
        }
    }

    fun acceptKey(key: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
        return Base64.getEncoder().encodeToString(digest.digest((key + GUID).toByteArray()))
    }
}

internal class WsFrame(val fin: Boolean, val opcode: Int, val payload: ByteArray)

/**
 * 服务端握手**的头部**部分：读 HTTP 头，校验升级语义与凭据，通过则写出 101。
 *
 * 请求行（方法 / 路径）由调用方读走并校验，因为网关要靠路径把连接分派到不同通路；
 * 所以本类从第一行**头**开始读。调用方读过头之后**必须**调本方法 —— 头校验和升级是同一件事，
 * 分成两处等于给自己留一个"只校验了路径、忘了校验凭据"的口子。
 *
 * 整段有 [deadlineMs] 硬截止 —— 否则一个连上不说话的客户端能占住一条连接直到天荒地老。
 * 错误一律在写出 101 **之前**回，否则客户端会以为连上了再莫名断掉。
 */
internal class WsUpgrade(
    private val input: InputStream,
    private val output: OutputStream,
    private val path: String,
    private val token: String,
    private val queryToken: String = "",
    private val deadlineMs: Long,
) {

    /** @param path 已由调用方从请求行里取出并校验过；本方法**不再读请求行**，只处理头。 */
    fun run(): Boolean {
        var wsKey: String? = null
        var upgrade = false
        var connectionUpgrade = false
        var versionOk = false
        var bearer: String? = null
        var relayToken: String? = null
        var subToken: String? = null
        var selected: String? = null
        while (true) {
            val line = io.readLine() ?: return false
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx < 0) continue
            when (line.substring(0, idx).trim().lowercase()) {
                "upgrade" -> upgrade = line.substring(idx + 1).trim().equals("websocket", ignoreCase = true)
                "connection" -> connectionUpgrade = line.substring(idx + 1).split(',')
                    .any { it.trim().equals("upgrade", ignoreCase = true) }

                "sec-websocket-version" -> versionOk = line.substring(idx + 1).trim() == "13"
                "sec-websocket-key" -> wsKey = line.substring(idx + 1).trim()
                "authorization" -> bearer = line.substring(idx + 1).trim().removePrefix("Bearer ").trim()
                "x-relay-token" -> relayToken = line.substring(idx + 1).trim()
                // 浏览器给 WebSocket 设不了自定义头：new WebSocket(url, protocols) 的第二个参数
                // 是**子协议**而不是请求头，整个 JS API 没有 header 选项。所以网页里的客户端
                // 只能把 token 放进 Sec-WebSocket-Protocol（形如 `new WebSocket(u, ["bearer", tok])`），
                // 或者放进查询串（见构造参数 queryToken）。
                "sec-websocket-protocol" -> {
                    val values = line.substring(idx + 1).split(',')
                        .map { it.trim() }.filter { it.isNotEmpty() }
                    val i = values.indexOfFirst { it.equals("bearer", ignoreCase = true) }
                    if (i >= 0 && i + 1 < values.size) {
                        subToken = values[i + 1]
                        // **必须回显**（RFC 6455 §4.1：服务端从客户端给的清单里挑一个原样送回）。
                        // 我原先以为"不回即不选，浏览器接受"——错的：Node/undici 直接报
                        // "Server did not respond with sent protocols."，浏览器给一个空原因的 1006。
                        // 这个坑的特点是**客户端自己写的探针也会一起挂**（同一个误解），所以只能靠
                        // 「用另一个实现验一次」发现。回显值取清单里原样存在的那一个，浏览器不会因
                        // 「服务端选了协议」而拒绝。
                        selected = "bearer"
                    }
                }
            }
        }
        if (!upgrade || !connectionUpgrade || !versionOk || wsKey.isNullOrBlank()) {
            io.http(400, "Bad Request", "不是合法的 WebSocket 升级请求")
            return false
        }
        // 与 ControlPlane.authorized 同一把钥匙：token 为空 = 回环放行。
        if (token.isNotEmpty() && bearer != token && relayToken != token &&
            subToken != token && queryToken != token
        ) {
            io.http(401, "Unauthorized", "缺少或错误的 token")
            return false
        }
        io.write(
            (
                buildString {
                    append("HTTP/1.1 101 Switching Protocols\r\n")
                    append("Upgrade: websocket\r\n")
                    append("Connection: Upgrade\r\n")
                    append("Sec-WebSocket-Accept: ").append(Ws.acceptKey(wsKey)).append("\r\n")
                    selected?.let { append("Sec-WebSocket-Protocol: ").append(it).append("\r\n") }
                    append("\r\n")
                }).encodeToByteArray(),
        )
        return true
    }

    private val io = WsReader(input, output, deadlineMs)
}

/**
 * 一条 WS 连接上的字节层：出站写串行化，入站读还原掩码。
 *
 * [deadlineMs] 可变：握手阶段用短截止（15s），进入帧阶段后置为 [Long.MAX_VALUE]，
 * 之后由心跳与空闲回收来判定死活 —— socket 的读超时只用来周期性醒来，不作为断线判据。
 */
internal class WsReader(
    private val input: InputStream,
    private val output: OutputStream,
    @Volatile var deadlineMs: Long,
) {

    private val writeLock = Any()

    fun write(bytes: ByteArray) {
        synchronized(writeLock) {
            output.write(bytes)
            output.flush()
        }
    }

    fun readLine(): String? {
        val sb = StringBuilder()
        while (true) {
            val c = try {
                input.read()
            } catch (_: java.net.SocketTimeoutException) {
                if (System.currentTimeMillis() > deadlineMs) return null
                continue
            }
            if (c < 0) return null
            if (c == '\n'.code) return sb.toString().removeSuffix("\r")
            if (c != '\r'.code) sb.append(c.toChar())
            if (sb.length > Ws.MAX_HEADER_LINE) return null
        }
    }

    /** @return null 表示对端断了，或帧长得不合法（已就地发关闭帧）。 */
    fun readFrame(): WsFrame? {
        val head = readFully(2) ?: return null
        val fin = (head[0].toInt() and 0x80) != 0
        val opcode = head[0].toInt() and 0x0F
        // RFC 6455 §5.2：byte0 是 FIN/RSV/OPCODE，**byte1 才是 MASK/长度**。
        val masked = (head[1].toInt() and 0x80) != 0
        var length = (head[1].toInt() and 0x7F).toLong()
        if (length == 126L) {
            val ext = readFully(2) ?: return null
            // 括号必须包住整个 `or`：写成 `a or b.toLong()` 会让 `or` 取 Int 重载，
            // 整个表达式退化成 Int，再赋给 Long 的 length 就编译不过。
            length = (((ext[0].toInt() and 0xFF) shl 8) or (ext[1].toInt() and 0xFF)).toLong()
        } else if (length == 127L) {
            val ext = readFully(8) ?: return null
            length = 0L
            for (b in ext) length = (length shl 8) or (b.toLong() and 0xFF)
        }
        if (length > Ws.MAX_FRAME_BYTES) {
            closeWith(1009, "frame too large")
            return null
        }
        val mask = if (masked) readFully(4) else null
        val payload = if (length > 0) readFully(length.toInt()) ?: return null else ByteArray(0)
        if (mask != null) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            }
        }
        return WsFrame(fin, opcode, payload)
    }

    /** 读满 n 字节才返回；中途断流返回 null —— 半个帧绝不能当成功。 */
    private fun readFully(n: Int): ByteArray? {
        if (n == 0) return ByteArray(0)
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = try {
                input.read(buf, off, n - off)
            } catch (_: java.net.SocketTimeoutException) {
                if (System.currentTimeMillis() > deadlineMs) return null
                continue
            }
            if (r < 0) return null
            off += r
        }
        return buf
    }

    fun sendFrame(payload: ByteArray, opcode: Int) {
        write(Ws.encode(payload, opcode))
    }

    /** 握手失败时的普通 HTTP 错误响应。reason 必须是 ASCII，中文放 body。 */
    fun http(code: Int, reason: String, message: String) {
        val body = message.encodeToByteArray()
        write(
            (
                "HTTP/1.1 $code $reason\r\n" +
                    "Content-Type: text/plain; charset=utf-8\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Connection: close\r\n\r\n"
                ).encodeToByteArray() + body,
        )
    }

    fun closeWith(code: Int, reason: String) {
        runCatching { write(Ws.encode(Ws.closeBody(code, reason), Ws.OP_CLOSE)) }
    }
}