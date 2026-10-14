// SPDX-License-Identifier: GPL-3.0-only

package app.relay.agent

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
/**
 * 一条 WS 通路接出去通向哪里。
 *
 * 两种形状不是"两种实现"，是**点对点传输协议的两条通路**：
 * - [Stream]：TCP 17879，承载 >1200 字节的长文本（[app.relay.core.transport.TcpFallback]）
 *   与文件传输（[app.relay.core.transport.TransferClient] / [TransferServer]）。
 * - [Datagram]：UDP 17878，承载**日常聊天** —— 主发送路径
 *   （`Messenger.sendAwaitAck` 里的 `transport.send(bytes, record.ip, record.udpPort)`）
 *   走的是它，TCP 只是重试耗尽后的兜底。
 *
 * 只做 [Stream] 会得到一个"能传大文件但发不出消息"的跨网通路，这是最容易踩空的地方。
 */
internal sealed class WsRoute {
    abstract val path: String

    data class Stream(
        override val path: String,
        val targetHost: String,
        val targetPort: Int,
    ) : WsRoute()

    data class Datagram(
        override val path: String,
        val targetHost: String,
        val targetPort: Int,
    ) : WsRoute()
}

/**
 * WebSocket 网关：一条 WS 连接 = 一条到内网服务的通路，按路径分派。
 *
 * ## 它替代的是什么
 *
 * 客户端在任意网络下要接入内网桌面节点时，隧道里必须流**原始字节 / 原始报文**，
 * 而不是让中转站读懂内容再转发。本类就是那个中转：
 * `[客户端] →（Cloudflare Tunnel / 自有 relay）→ 本机 → 桌面 17879 或 17878`。
 *
 * 关键在于**协议无知**：这里既不跑 [app.relay.core.session.NodeRuntime]，也不解析任何一帧，
 * 只是把字节/报文原样搬过去。于是「广播了没绑上的端口、消息投进别人的窗口」那类缺陷
 * 的缺陷在结构上无从发生 —— 不是我们记得小心，而是这条路上没有会误解帧的代码。
 *
 * 注意这**不等于**对中转站保密：数据照样经过本进程。端到端保密得在协议里做加密，与选哪条通路无关。
 *
 * ## 为什么必须两条通路
 *
 * 手机在运营商 CGNAT 后面**收不到任何入站连接**，所以桌面 → 手机这个方向也必须由手机主动拉。
 * 一条 WS 连接只能覆盖"手机发出去"和"对这条连接的应答"；桌面主动推来的消息一条也接不住。
 *
 * ## 为什么用 JDK 的 ServerSocket 而不是 ktor
 *
 * `ktor-network` 是 core 的 `implementation`，**不会**传递到 agentNode 的编译类路径 —— 它只对 core 自己可见
 * （agentNode 的依赖只有 `:core`、coroutines、serialization-json）。而 ktor-server / OkHttp 这类 WebSocket
 * 实现要么改构建文件多一个传递依赖（装在别人电脑上的常驻进程，每多一个依赖都是一次「装不起来」的机会），
 * 要么写不完整。WebSocket 的握手与分帧两百来行，用 JDK 自带的 [ServerSocket] 一次写对更省事。
 *
 * ## 阻塞读与取消
 *
 * socket 读是阻塞的，不响应协程取消。所以**取消统一走 [Session.closeAll]**：关掉 socket，
 * 阻塞读立刻抛。一侧自然结束时也调它，让另一侧从阻塞里醒过来 —— 靠 cancel 是叫不醒阻塞读的。
 *
 * ## 安全模型
 *
 * 只绑 [LOOPBACK]，与 [ControlPlane] 同一条规矩：对外暴露由 Cloudflare Tunnel / relay 负责。
 * token 非空时 WS 升级请求必须带同一把钥匙（[WsUpgrade]）—— 升级请求和普通 HTTP 请求是同一个入口，
 * 只校验一边等于没校验。
 *
 * @param port   监听端口，传 0 由系统分配，实际端口读 [boundPort]。
 * @param routes 路径 → 目标的分派表；路径重复时启动即失败，不静默取第一个。
 * @param token  空字符串 = 回环放行（与 [ControlPlane] 一致）。
 */
internal class WsGateway(
    private val port: Int,
    private val token: String,
    private val routes: List<WsRoute>,
    private val scope: CoroutineScope,
    private val logger: (String) -> Unit = { println(it) },
) {

    private var server: ServerSocket? = null
    private var acceptJob: Job? = null
    private var reaperJob: Job? = null
    private val live = AtomicInteger(0)
    private val sessions: MutableSet<Session> = Collections.synchronizedSet(mutableSetOf())

    /** 实际监听端口。[port] 传 0 时只有 [start] 之后才有效。 */
    var boundPort: Int = port
        private set

    /** 当前活跃的通路连接数。 */
    val activeConnections: Int get() = live.get()

    fun start() {
        if (server != null) return
        // 路径重复要在开端口**之前**查：静默取第一个会让两个端点共用一条路径，
        // 而调用方以为它们是分开的。
        val dup = routes.groupBy { it.path }.filterValues { it.size > 1 }.keys
        require(dup.isEmpty()) { "WS 路径重复：$dup" }
        val s = ServerSocket()
        // 不设 SO_REUSEADDR：重复启动时宁可失败并说清楚，也不要两个进程抢同一个端口。
        s.bind(InetSocketAddress(LOOPBACK, port), BACKLOG)
        s.soTimeout = ACCEPT_POLL_MS
        server = s
        boundPort = s.localPort
        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val incoming = try {
                    s.accept()
                } catch (_: SocketTimeoutException) {
                    continue // 只是为了让 stop() 能被下面的 isActive 观察到
                } catch (_: SocketException) {
                    break // stop() 关掉了 ServerSocket
                }
                // 路径不在表里就 404。这必须在握手阶段做，因为那时才读得到 path。
                if (live.get() >= MAX_CONNECTIONS) {
                    runCatching { incoming.close() }
                    continue
                }
                live.incrementAndGet()
                launch(Dispatchers.IO) {
                    val session = Session(incoming, token, logger)
                    sessions.add(session)
                    try {
                        when (val route = session.handshake(routes)) {
                            is WsRoute.Stream -> session.serveStream(route.targetHost, route.targetPort)
                            is WsRoute.Datagram -> session.serveDatagram(route.targetHost, route.targetPort)
                            null -> Unit // 握手已经回过错误并关掉了
                        }
                    } catch (t: Throwable) {
                        if (t !is CancellationException) {
                            logger("  WS 网关 : 会话异常 ${t.javaClass.simpleName}: ${t.message}")
                        }
                    } finally {
                        sessions.remove(session)
                        session.closeAll()
                        live.decrementAndGet()
                    }
                }
            }
        }
        // 挂死的通路会一直占着一条到桌面的连接，而桌面不会因为它沉默就断开。
        reaperJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(REAP_INTERVAL_MS)
                sessions.toList().filter { it.idleMs() > IDLE_TIMEOUT_MS }.forEach { stale ->
                    logger("  WS 网关 : 回收空闲会话（${stale.idleMs() / 1000}s 无字节）")
                    stale.closeAll()
                }
            }
        }
    }

    fun stop() {
        reaperJob?.cancel()
        acceptJob?.cancel()
        server?.close()
        reaperJob = null
        acceptJob = null
        server = null
        sessions.toList().forEach { runCatching { it.closeAll() } }
    }

    // ------------------------------------------------------------------ 会话

    /**
     * 一条 WS 连接。[path] / [token] 必须显式传进来：这不是 inner 类，拿不到外层类的实例属性
     * （编译期报 "Outer class cannot be used as receiver"）。
     */
    private class Session(
        private val socket: Socket,
        private val token: String,
        private val logger: (String) -> Unit,
    ) {

        private val input: InputStream = socket.getInputStream()
        private val output: OutputStream = socket.getOutputStream()
        private val lastTouchedMs = AtomicLong(System.currentTimeMillis())
        private val ended = AtomicInteger(0)

        /** 心跳与空闲回收共用；出站写由 [WsReader] 自己串行化。 */
        private var io: WsReader? = null
        private var upstreamBytes = 0L
        private var downstreamBytes = 0L

        /** 与桌面对接的 socket（TCP 或 UDP），关闭时一并关掉。 */
        private var peer: AutoCloseable? = null

        fun idleMs(): Long = System.currentTimeMillis() - lastTouchedMs.get()

        fun closeAll() {
            if (ended.getAndIncrement() != 0) return
            runCatching { peer?.close() }
            runCatching { socket.close() }
        }

        /**
         * 握手并按路径分派。
         *
         * @param routes 分派表由外层显式传入 —— 这是非 inner 的嵌套类，拿不到外层的实例属性
         * （编译期报 "Outer class cannot be used as a receiver"）。
         * @return 该连接该走哪条通路；握手失败返回 null（此时已回过错误并关掉）。
         */
        fun handshake(routes: List<WsRoute>): WsRoute? {
            val knownPaths = routes.map { it.path }
            socket.soTimeout = READ_POLL_MS
            val reader = WsReader(input, output, deadlineMs = System.currentTimeMillis() + HANDSHAKE_TIMEOUT_MS)
            val requestLine = reader.readLine() ?: return null
            val parts = requestLine.split(' ').filter { it.isNotEmpty() }
            if (parts.size < 3 || !parts[0].equals("GET", ignoreCase = true)) {
                reader.http(405, "Method Not Allowed", "只接受 GET")
                closeAll()
                return null
            }
            val target = parts[1]
            val path = target.substringBefore('?')
            val route = routes.firstOrNull { it.path == path }
            if (route == null) {
                reader.http(404, "Not Found", "未知路径，可用：$knownPaths")
                closeAll()
                return null
            }
            // 凭据的第三、四种载体（见 WsUpgrade 注释）：浏览器给 WebSocket 设不了请求头，
            // 网页客户端只能走 ?token= 或子协议。查询串在分派前剥掉，所以不影响路径匹配。
            val queryToken = target.substringAfter('?', "")
                .split('&')
                .firstOrNull { it.substringBefore('=') == "token" }
                ?.substringAfter('=', "")
                ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
                .orEmpty()
            // WsUpgrade 会把 deadline 当构造参数收下，这里复用同一个 reader 的输出流即可。
            if (!WsUpgrade(input, output, path, token, queryToken, reader.deadlineMs).run()) {
                closeAll()
                return null
            }
            reader.deadlineMs = Long.MAX_VALUE
            io = reader
            return route
        }

        // ------------------------------------------------------------ 通路一：TCP 流

        /** 把 WS 二进制帧与一条到 `host:port` 的 TCP 连接对缝起来。只搬字节，不解释。 */
        suspend fun serveStream(host: String, port: Int) {
            val reader = io ?: return
            val target = Socket()
            try {
                target.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            } catch (t: Throwable) {
                runCatching { target.close() }
                logger("  WS 网关 : 连不上 $host:$port（${t.message}）—— 目标桌面节点在跑吗？")
                reader.closeWith(1011, "target unreachable")
                closeAll()
                return
            }
            target.soTimeout = READ_POLL_MS
            peer = target
            logger("  WS 网关 : TCP 通路建立 → $host:$port")

            val targetIn = runCatching { target.getInputStream() }.getOrNull()
            val targetOut = runCatching { target.getOutputStream() }.getOrNull()
            if (targetIn == null || targetOut == null) {
                closeAll()
                return
            }

            val pumps = CoroutineScope(Dispatchers.IO + Job())
            val up = pumps.launch(Dispatchers.IO) { pumpStreamUp(targetOut) }
            val down = pumps.launch(Dispatchers.IO) { pumpStreamDown(targetIn) }
            val beat = pumps.launch(Dispatchers.IO) { heartbeat() }
            // 谁先结束就拆掉整个会话：另外两个的阻塞读不响应协程取消，只能靠 closeAll 叫醒。
            listOf(up, down, beat).forEach { job ->
                job.invokeOnCompletion { closeAll() }
            }
            runCatching { up.join(); down.join() }
            logger("  WS 网关 : TCP 通路结束  上行 $upstreamBytes B  下行 $downstreamBytes B")
        }

        private suspend fun pumpStreamUp(toTarget: OutputStream) {
            val reader = io ?: return
            var fragment = ByteArrayOutputStream()
            var fragmenting = false
            while (true) {
                // readFrame() 在收尾时会因为我们关了自己的 socket 而抛，而不是返回 null ——
                // 两条通路的上行读都是这个形状，统一当正常收尾。
                val frame = try {
                    reader.readFrame()
                } catch (_: IOException) {
                    return
                } ?: return
                touch()
                when (frame.opcode) {
                    Ws.OP_PING -> reader.sendFrame(frame.payload, Ws.OP_PONG)
                    Ws.OP_PONG -> Unit
                    Ws.OP_CLOSE -> {
                        closeAll()
                        return
                    }

                    Ws.OP_TEXT -> {
                        // 协议正文是二进制帧。收到文本帧说明对端实现有 bug，静默丢掉会让它苦等超时。
                        reader.closeWith(1003, "binary frames only")
                        closeAll()
                        return
                    }

                    Ws.OP_CONTINUATION -> {
                        if (!fragmenting) {
                            reader.closeWith(1002, "continuation without start")
                            closeAll()
                            return
                        }
                        fragment.write(frame.payload)
                        if (fragment.size() > Ws.MAX_FRAME_BYTES) {
                            reader.closeWith(1009, "message too large")
                            closeAll()
                            return
                        }
                        if (frame.fin) {
                            writeThrough(toTarget, fragment.toByteArray())
                            fragment = ByteArrayOutputStream()
                            fragmenting = false
                        }
                    }

                    Ws.OP_BINARY -> {
                        if (fragmenting) {
                            reader.closeWith(1002, "new data frame while fragmented")
                            closeAll()
                            return
                        }
                        if (frame.fin) {
                            writeThrough(toTarget, frame.payload)
                        } else {
                            fragmenting = true
                            fragment.write(frame.payload)
                        }
                    }

                    else -> {
                        reader.closeWith(1002, "bad opcode ${frame.opcode}")
                        closeAll()
                        return
                    }
                }
            }
        }

        private suspend fun pumpStreamDown(fromTarget: InputStream) {
            val reader = io ?: return
            val buf = ByteArray(Ws.CHUNK)
            while (true) {
                val n = try {
                    fromTarget.read(buf)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: IOException) {
                    // closeAll() 会关掉这条 TCP，阻塞中的 read 于是抛异常 —— 正常收尾，见 pumpDatagramDown。
                    return
                }
                if (n < 0) {
                    closeAll()
                    return
                }
                if (n == 0) continue
                touch()
                reader.sendFrame(buf.copyOf(n), Ws.OP_BINARY)
                downstreamBytes += n
            }
        }

        private fun writeThrough(toTarget: OutputStream, bytes: ByteArray) {
            if (bytes.isEmpty()) return
            synchronized(toTarget) {
                toTarget.write(bytes)
                toTarget.flush()
            }
            upstreamBytes += bytes.size
        }

        // ------------------------------------------------------- 通路二：UDP 数据报

        /**
         * 把 WS 二进制帧与一条到 `host:port` 的 UDP 通路对缝起来：
         * 手机 → 桌面是逐帧逐报文，桌面 → 手机是本地 [DatagramSocket] 收谁就推给谁。
         *
         * **报文原样透传，不夹带元数据**：手机侧仍然从注册表取对端的 `ip`/`udpPort`，
         * 于是桥两侧看到的东西与真实 UDP 完全一致，不需要为"桥"发明第二种线格式。
         *
         * 数据报的语义天然是丢的：桥上手机读得慢，`DatagramSocket` 的缓冲区满了就丢，
         * 和真实网络丢包一样 —— 而传输层本来就有 ACK + 重试兜底，不需要桥去补。
         */
        suspend fun serveDatagram(host: String, port: Int) {
            val reader = io ?: return
            val udp = try {
                DatagramSocket(InetSocketAddress(LOOPBACK, 0))
            } catch (t: Throwable) {
                logger("  WS 网关 : UDP 通路开不了本地 socket（${t.message}）")
                reader.closeWith(1011, "no datagram socket")
                closeAll()
                return
            }
            udp.soTimeout = UDP_POLL_MS
            peer = udp
            val targetAddr = InetSocketAddress(host, port)
            logger("  WS 网关 : UDP 通路建立 → $host:$port（本机源端口 ${udp.localPort}）")

            val pumps = CoroutineScope(Dispatchers.IO + Job())
            val up = pumps.launch(Dispatchers.IO) { pumpDatagramUp(udp, targetAddr, port) }
            val down = pumps.launch(Dispatchers.IO) { pumpDatagramDown(udp) }
            val beat = pumps.launch(Dispatchers.IO) { heartbeat() }
            listOf(up, down, beat).forEach { job -> job.invokeOnCompletion { closeAll() } }
            runCatching { up.join(); down.join() }
            logger("  WS 网关 : UDP 通路结束  上行 $upstreamBytes 报文  下行 $downstreamBytes 报文")
        }

        private suspend fun pumpDatagramUp(udp: DatagramSocket, target: InetSocketAddress, port: Int) {
            val reader = io ?: return
            while (true) {
                val frame = try {
                    reader.readFrame()
                } catch (_: IOException) {
                    return
                } ?: return
                touch()
                when (frame.opcode) {
                    Ws.OP_PING -> reader.sendFrame(frame.payload, Ws.OP_PONG)
                    Ws.OP_PONG -> Unit
                    Ws.OP_CLOSE -> {
                        closeAll()
                        return
                    }
                    // 一条 WS 二进制消息 = 一个 UDP 报文，没有"分片"这种说法。
                    // 客户端要是发了续帧，那是实现有 bug，按协议错误关掉比默默拼起来安全。
                    Ws.OP_CONTINUATION -> {
                        reader.closeWith(1002, "datagrams are never fragmented")
                        closeAll()
                        return
                    }
                    Ws.OP_TEXT -> {
                        reader.closeWith(1003, "binary frames only")
                        closeAll()
                        return
                    }
                    Ws.OP_BINARY -> {
                        if (!frame.fin) {
                            reader.closeWith(1002, "fragmented datagram")
                            closeAll()
                            return
                        }
                        val payload = frame.payload
                        if (payload.isEmpty() || payload.size > MAX_DATAGRAM_BYTES) {
                            reader.closeWith(1009, "datagram out of range")
                            closeAll()
                            return
                        }
                        try {
                            // 取 .address/.port 而不是直接把 InetSocketAddress 传进去：
                            // DatagramPacket 的地址参数是 InetAddress，SocketAddress 那条重载不存在。
                            udp.send(DatagramPacket(payload, payload.size, target.address, target.port))
                        } catch (t: Throwable) {
                            // 单个报文发不出去（目标暂时不可达）不该拆掉整条通路：
                            // 点对点传输的重试机制本来就会重发，这里丢一个和真实网络丢包等价。
                            continue
                        }
                        upstreamBytes++
                    }

                    else -> {
                        reader.closeWith(1002, "bad opcode ${frame.opcode}")
                        closeAll()
                        return
                    }
                }
            }
        }

        private suspend fun pumpDatagramDown(udp: DatagramSocket) {
            val reader = io ?: return
            val buf = ByteArray(MAX_DATAGRAM_BYTES)
            while (true) {
                val packet = DatagramPacket(buf, buf.size)
                // 长度从 packet 读，不从 receive() 的返回值读：receive() 的返回类型在不同 JDK 上不一致
                // （本机 Temurin 21.0.9 的 java.net.DatagramSocket.receive 签名是 public void），
                // 而 getLength() 是规范里稳定的那个。写成 `val n = udp.receive(packet)` 直接编不过。
                try {
                    udp.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: IOException) {
                    // 客户端断开时我们主动 close(udp)，阻塞中的 receive 会抛 SocketException /
                    // AsynchronousCloseException。这是**正常收尾**，不是故障：
                    // 不吃掉的话每个会话断开都会在 worker 线程上喷一整段堆栈，
                    // 而真正的原因（对端走了）反而被淹掉。
                    return
                }
                val payload = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                touch()
                reader.sendFrame(payload, Ws.OP_BINARY)
                downstreamBytes++
            }
        }

        // -------------------------------------------------------------- 共用

        /**
         * 心跳。Cloudflare 会对空闲的代理 WebSocket 断连（约 100s 量级），而局域网通讯在空闲时可以长时不发报文，
         * 所以不能靠「有数据才算活着」。
         */
        private suspend fun heartbeat() {
            while (true) {
                delay(HEARTBEAT_MS)
                val reader = io ?: return
                if (ended.get() != 0) return
                reader.sendFrame(ByteArray(0), Ws.OP_PING)
                touch()
            }
        }

        private fun touch() {
            lastTouchedMs.set(System.currentTimeMillis())
        }
    }

    companion object {
        const val LOOPBACK: String = "127.0.0.1"

        /** WS 网关与控制面 [ControlPlane] 分开端口：升级连接是长连接，混在一起会占满控制面线程池。 */
        const val DEFAULT_WS_PORT: Int = 8789
        const val DEFAULT_STREAM_PATH: String = "/v1/pipe"
        const val DEFAULT_DATAGRAM_PATH: String = "/v1/dgram"

        /** 同时在线的通路数：每条都占一条到桌面的连接，不是可以无限开的。 */
        private const val MAX_CONNECTIONS = 16

        /** 单个 UDP 报文上限（IPv4 理论上限 65507）。协议正文只有 1200 字节，这个上限是防呆。 */
        private const val MAX_DATAGRAM_BYTES = 65507

        private const val BACKLOG = 16
        private const val ACCEPT_POLL_MS = 1_000

        /** socket 读超时只用来周期性醒来，不作为断线判据 —— 判据是握手截止时间与 closeAll。 */
        private const val READ_POLL_MS = 30_000
        private const val UDP_POLL_MS = 1_000
        private const val HANDSHAKE_TIMEOUT_MS = 15_000
        private const val CONNECT_TIMEOUT_MS = 5_000

        private const val HEARTBEAT_MS = 30_000L
        private const val IDLE_TIMEOUT_MS = 10 * 60_000L
        private const val REAP_INTERVAL_MS = 60_000L
    }
}