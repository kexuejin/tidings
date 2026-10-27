// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

import com.kapp.tidings.core.protocol.Codec
import com.kapp.tidings.core.protocol.DecodeResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import uniffi.luciola_uniffi.SecureSession
import uniffi.luciola_uniffi.SecureSessionListener
import java.util.concurrent.ConcurrentHashMap

/**
 * 阶段三:UdpTransport 的加密信道替换(接口同形,Messenger 零改动)。
 *
 * ## 形态
 *
 * luciola 的 SecureSession 是 **TCP** 上的认证加密信道(Ed25519 双向签名 +
 * X25519 + AES-GCM),所以本类有两个面:
 *
 *  - **外拨**:[send] 按 (host, port) 懒建一条 [SecureSession.connect],首次
 *    发送触发握手;断线由库内 1s→60s 指数退避自愈。对端公钥来自 [peerKey]
 *    (配对过的才能连 —— deny-by-default)。
 *  - **入站**:[listenPort] > 0 时 [start] 起一条 [SecureSession.listen] 接受
 *    循环。对端身份由握手回调的**指纹**给出;不在信任集里的对端立刻断开。
 *
 * ## 入站帧怎么归到对端(为什么需要 [endpointOf])
 *
 * 信道的回调只给「对端指纹 + 明文帧」,**不给对端 socket 地址**;而 Tidings 的
 * Messenger 用 ReceivedDatagram(host, port) 做两件事:回 ACK、以及
 * `registry.touch(from, host, port)` 的地址一致性校验(已在线节点不接受源地址
 * 漂移 —— 地址不符整帧丢弃)。所以入站帧必须带着**该对端在 registry 里的地址**
 * 递上去,否则一条也过不了。
 *
 * 做法:用帧里的 `from`(nodeId)查 [endpointOf],把这条入站信道登记到该端点
 * 名下;同一端点之后的 [send](ACK、回复)优先走这条已建立的入站信道
 * (移动端不监听,拨回去是拨不通的)。
 *
 * 查不到端点(还没被发现在线)的入站帧**丢弃** —— 与 Messenger「未知节点不处理
 * 可靠帧」的纪律一致,也避免把未认证的发现信息当成地址来源。
 *
 * ## 已知边界
 *
 * - 监听面是 Rust 的 `TcpListener::bind(("0.0.0.0", port))` —— **仅 IPv4 入站**;
 *   外拨支持多地址竞速(含 IPv6)。IPv6 入站要在 luciola 侧补。
 * - 监听端口复用发现 UDP 端口号(同一数字,TCP/UDP 互不冲突):对端资料里的
 *   `udpPort` 就是它该拨的 TCP 端口,配对码里的 port 同理。
 */
class SecureChannelTransport(
    private val identitySeed: ByteArray,
    /** (host, port) → pinned Ed25519 public key;null = 未配对,拒绝。 */
    private val peerKey: (String, Int) -> ByteArray?,
    /** 监听端口;<= 0 = 纯外拨(手机/测试)。 */
    private val listenPort: Int = -1,
    /** 对端指纹是否可信(入站信道的手续)。默认全信 —— 只有测试/纯拨号端会这么用。 */
    private val isTrustedFingerprint: (String) -> Boolean = { true },
    /** nodeId → registry 里的地址(入站帧归属)。 */
    private val endpointOf: (String) -> PeerEndpoint? = { null },
    /** 配对准入;null = 不开放配对入站(陌生对端直接断开)。 */
    private val pairing: PairingAdmission? = null,
    /** 陌生入站会话等配对帧的上限;超时断开,避免占住 accept 循环。 */
    private val pendingPairingTimeoutMs: Long = 30_000,
    /** 发起侧:该端点二维码里的配对密钥(扫过码才有)。 */
    private val pairingSecretOf: (String, Int) -> ByteArray? = { _, _ -> null },
    /** 本端 Ed25519 公钥(配对帧自证用;由工厂传入)。 */
    private val selfPublicKey: ByteArray = ByteArray(0),
    /**
     * 会话指纹 → 对端在 registry 里的地址。二进制数据帧(FileChunkFrame)没有 Envelope,
     * 不能靠帧里的 from 归属;只能靠握手出来的指纹反查(TrustStore → nodeId → registry)。
     */
    private val peerOfFingerprint: (String) -> PeerEndpoint? = { null },
    /**
     * 「这帧是二进制数据面帧、不是 Envelope」的判据。
     *
     * 默认只认文件块（[FileChunkFrame]）—— 行为与加这个参数之前逐字一致。投屏会
     * 建**自己的一条**信道实例并传入 [ScreenChannelFrame.isScreenFrame]：两条数据面
     * 各有各的魔数，于是一条信道上的帧不会被另一条误认。
     */
    private val isBinaryFrame: (ByteArray) -> Boolean = { FileChunkFrame.isChunkFrame(it) },
) : DatagramTransport {
    private val outbound = ConcurrentHashMap<String, SecureSession>()

    /**
     * 握手完成前发出的配对帧(扫码后立刻触发时几乎必然遇到):
     * send 在未连接时返回 false,帧会丢 —— 这里先存下来,等 "connected" 再补发。
     * 真机实测:不补发时主机永远收不到 TLPAIR1,表现为"扫码后一直不配对"。
     */
    private val pendingPairing = ConcurrentHashMap<String, ByteArray>()
    private val inboundByEndpoint = ConcurrentHashMap<String, SecureSession>()

    /**
     * 入站帧流。
     *
     * `onBufferOverflow = SUSPEND` + 下面的 [emitOrBlock] 是**必须的**：回调来自 Rust
     * 的连接线程（非挂起），而 64KiB 的文件块会瞬间把缓冲打满；默认的 DROP_OLDEST
     * 会在接收方协程还没调度过来时**静默丢帧**。回环 5MB 验收实测：ACK 涨到 4MB 就停住，
     * 因为丢掉的块永远补不上（发送端只重传"未确认的窗口"，而它以为已经发出去了）。
     */
    private val _incoming = MutableSharedFlow<ReceivedDatagram>(
        extraBufferCapacity = 1024,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.SUSPEND,
    )
    override val incoming: SharedFlow<ReceivedDatagram> = _incoming

    /** 从非挂起线程投递：满了就**阻塞提供背压**，绝不丢帧。 */
    private fun emitOrBlock(datagram: ReceivedDatagram) {
        if (_incoming.tryEmit(datagram)) return
        blockedEmits.incrementAndGet()
        try {
            synchronized(emitLock) {
                kotlinx.coroutines.runBlocking { _incoming.emit(datagram) }
            }
        } catch (t: Throwable) {
            // 回调线程上抛异常会让 uniffi 把整个 Rust 链接线程结束掉（静默断链）——
            // 这里必须收住，并且**如实记账**，不许装成投递成功。
            emitFailures.incrementAndGet()
            println("[luciola] inbound emit failed: " + t)
        }
    }

    private val emitLock = Any()

    private companion object {
        /** 状态迁移只留最后这么多条：再多就不是诊断而是日志洪泛了。 */
        const val STATE_LOG_LIMIT = 40
    }

    /** 入站缓冲满而阻塞在回调线程上的次数（诊断用；正常稳态应当很小）。 */
    val blockedEmits = java.util.concurrent.atomic.AtomicLong()

    /** 入站投递失败次数（回调线程上不容许抛异常）。 */
    val emitFailures = java.util.concurrent.atomic.AtomicLong()

    /**
     * 最近若干次会话状态迁移（有界，只留最后 [STATE_LOG_LIMIT] 条）。
     *
     * 为什么要有：信道"连上又断"是最难归因的一类故障 —— 应用层只看到 send 返回 false
     * 或帧少了几个。把 closed/error 的 detail 留下来，就能把"谁先关的、为什么关"
     * 变成一行可复核的证据，而不是猜。
     */
    private val stateLog = java.util.ArrayDeque<String>()

    private fun recordState(tag: String, key: String, state: String, detail: String?) {
        synchronized(stateLog) {
            stateLog.addLast(tag + "[" + key + "]=" + state + (detail?.let { "(" + it + ")" } ?: ""))
            while (stateLog.size > STATE_LOG_LIMIT) stateLog.removeFirst()
        }
    }

    /** 会话状态迁移记录（新→旧顺序返回，逗号分隔）。 */
    fun stateHistory(): String = synchronized(stateLog) { stateLog.joinToString(",") }

    /**
     * 一条可诊断的快照：每条会话的 Rust 侧计数（连接/重连/帧数）。
     *
     * 为什么要有：信道出问题时最贵的成本是「猜」。connectionsEstablished 大于 1
     * 就是**在传数据的过程中断线重连过**（会丢掉尚在出站队列里的帧）；帧数与
     * 应用层计数对不上，就说明丢在信道里而不是落在应用层。真机排障直接读这一行。
     */
    fun diagnostics(): String {
        val sb = StringBuilder()
        fun one(tag: String, key: String, session: SecureSession) {
            val s = runCatching { session.stats() }.getOrNull() ?: return
            sb.append(tag).append('[').append(key).append("] sent=").append(s.framesSent.toLong())
                .append(" recv=").append(s.framesReceived.toLong())
                .append(" conns=").append(s.connectionsEstablished.toLong())
                .append(" retries=").append(s.reconnectAttempts.toLong())
                .append(" connected=").append(session.isConnected())
                .append(';')
        }
        inboundSession?.let { one("listen", "", it) }
        outbound.forEach { (k, s) -> one("out", k, s) }
        inboundByEndpoint.forEach { (k, s) -> one("in", k, s) }
        return sb.toString()
    }

    /**
     * 配对结果。
     *
     * **必须 replay = 1**：应答可能在调用方订阅之前就到了（扫码后"先发请求、再挂着听"
     * 这条时序里，回环上 RTT 只有毫秒级）。replay=0 时那一帧直接丢弃 ——
     * 症状是"扫了码什么都没发生"，既没成功提示也没失败提示。订阅方按
     * (host, port) 过滤自己关心的那一次即可，旧应答不会串台。
     */
    private val _pairingResults = MutableSharedFlow<PairingResult>(replay = 1, extraBufferCapacity = 16)

    /** 发起侧:主机对配对请求的应答(控制帧**不进** Messenger)。 */
    val pairingResults: SharedFlow<PairingResult> = _pairingResults

    private val listenLock = Any()

    @Volatile
    private var inboundSession: SecureSession? = null

    /** 绑定失败等致命监听错误(由 Rust 线程回调写入,UI/日志可读)。 */
    @Volatile
    var listenError: String? = null
        private set

    /** 被信任策略拒绝的入站次数(诊断用;含版本不匹配/未配对)。 */
    @Volatile
    var rejectedInbound: Int = 0
        private set

    private val unattributedDrops = java.util.concurrent.atomic.AtomicInteger()

    override val boundPort: Int get() = if (listenPort > 0) listenPort else -1

    override suspend fun start() {
        if (listenPort <= 0) return
        synchronized(listenLock) {
            if (inboundSession != null) return
            val session = SecureSession(identitySeed)
            inboundSession = session
            listenError = null
            session.listen(listenPort.toUShort(), /* expectedPeer = */ null, InboundListener(session))
        }
    }

    /** 加密信道自己会重连;这里只负责在绑定失败后重新起监听。 */
    override suspend fun restart() {
        stop()
        start()
    }

    override fun stop() {
        val current = synchronized(listenLock) {
            val s = inboundSession
            inboundSession = null
            s
        }
        current?.let { runCatching { it.disconnect() } }
        inboundByEndpoint.clear()
        outbound.values.forEach { runCatching { it.disconnect() } }
        outbound.clear()
    }

    /**
     * 发起侧配对:用主机二维码里的 secret 自证「扫过码」。返回 false = 没扫过/未配对,
     * 或信道还没连上(调用方退避重试,与普通发送同一套语义)。
     */
    override suspend fun requestPairing(host: String, port: Int, selfNodeId: String): Boolean {
        val secret = pairingSecretOf(host, port) ?: return false
        if (selfPublicKey.size != PairingFrame.ED25519_PUBLIC_KEY_SIZE) return false
        val frame = PairingFrame.encodeRequest(selfNodeId, selfPublicKey, secret)
        pendingPairing[endpointKey(host, port)] = frame
        return send(frame, host, port)
    }

    override suspend fun send(bytes: ByteArray, host: String, port: Int): Boolean {
        val key = endpointKey(host, port)
        // 对端主动连进来的信道优先:移动端不监听,拨回去必然失败。
        inboundByEndpoint[key]?.takeIf { it.isConnected() }?.let { session ->
            return runCatching { session.send(bytes) }.getOrDefault(false)
        }
        val session = runCatching { channelFor(host, port, key) }.getOrNull() ?: return false
        return runCatching { session.send(bytes) }.getOrDefault(false)
    }

    private fun channelFor(host: String, port: Int, key: String): SecureSession =
        outbound.computeIfAbsent(key) {
            val pinned = peerKey(host, port)
                ?: throw IllegalStateException("unpaired peer $key — deny by default")
            SecureSession(identitySeed).also { session ->
                session.connect(
                    listOf(host), port.toUShort(), pinned, /*autoReconnect=*/true,
                    object : SecureSessionListener {
                        override fun onState(state: String, detail: String?) {
                            recordState("out", key, state, detail)
                            if (state == "connected") {
                                // 扫码后立刻发出的配对帧可能早于握手完成:连上就补发一次。
                                pendingPairing.remove(key)?.let { queued ->
                                    runCatching { session.send(queued) }
                                }
                            }
                            // **不把会话从表里摘掉**。库里的 connect(autoReconnect=true) 自带
                            // 1s→60s 退避重连；一摘掉，下一次 send 就再造一条会话，两条链路
                            // 同时拨同一个对端 —— 对端的 accept 循环一次只收一条，于是
                            // 新旧连接互相顶替。回环 50MB 验收实测：传数据途中对端
                            // conns=3、本站 retries=5，每次顶替都把尚在出站队列里的帧丢掉
                            // （应用层看到的是"send 成功但少了几十帧"，ACK 前缀永远停住）。
                            // 会话对象留着，断线由它自己去连；send 在未连上时返回 false，
                            // 上层按退避重试 —— 这与网关队列满时的语义完全一致。
                        }

                        override fun onMessage(message: ByteArray) {
                            // 配对应答是传输层控制帧:交给 [pairingResults],不许进 Messenger
                            // (它的 Envelope 解码会把控制帧整条判成坏帧)。
                            if (PairingFrame.looksLikePairingFrame(message)) {
                                PairingFrame.decodeReply(message)?.let { (ok, reason) ->
                                    _pairingResults.tryEmit(PairingResult(ok, reason, host, port))
                                }
                                return
                            }
                            emitOrBlock(ReceivedDatagram(message, host, port))
                        }
                    },
                )
            }
        }

    private fun senderOf(message: ByteArray): String? {
        val decoded = Codec.decode(message)
        return (decoded as? DecodeResult.Ok)?.env?.from
    }

    private inner class InboundListener(private val session: SecureSession) : SecureSessionListener {
        @Volatile
        private var trusted = false

        @Volatile
        private var sessionFingerprint: String? = null

        /** 该会话已解析出的对端地址(二进制帧与 Envelope 帧共用,解析一次缓存)。 */
        @Volatile
        private var inboundPeer: PeerEndpoint? = null

        override fun onState(state: String, detail: String?) {
            recordState("listen", "", state, detail)
            when (state) {
                "connected" -> {
                    sessionFingerprint = detail
                    trusted = detail != null && isTrustedFingerprint(detail)
                    if (!trusted) {
                        if (pairing?.expectedPairSecret() == null) {
                            rejectedInbound += 1
                            runCatching { session.disconnect() }
                        } else {
                            // 陌生对端:给它一个窗口用配对帧自证;窗口内不许进应用层。
                            armPendingDeadline()
                        }
                    }
                }
                "closed", "error" -> {
                    trusted = false
                    sessionFingerprint = null
                    if (state == "error" && detail != null) listenError = detail
                    inboundByEndpoint.values.removeAll { it === session }
                }
                else -> Unit
            }
        }

        override fun onMessage(message: ByteArray) {
            if (PairingFrame.looksLikePairingFrame(message)) {
                handlePairingFrame(message)
                return
            }
            // 未通过信任手续的对端,一帧业务数据都不许进入应用层。
            if (!trusted) return
            // 二进制数据帧(文件/投屏)没有 Envelope:按会话指纹反查对端地址后原样投递。
            if (isBinaryFrame(message)) {
                val fp = sessionFingerprint ?: return
                val endpoint = inboundPeer ?: peerOfFingerprint(fp) ?: return
                inboundPeer = endpoint
                inboundByEndpoint[endpointKey(endpoint.host, endpoint.port)] = session
                emitOrBlock(ReceivedDatagram(message, endpoint.host, endpoint.port))
                return
            }
            val from = senderOf(message) ?: return
            val endpoint = endpointOf(from)
            if (endpoint == null) {
                // 没有 registry 记录就无法把帧交上去(也回不了 ACK)。默认只计数,
                // 每 25 次打一行 —— 真机上"配对成功但消息一直发送中"就是这一条。
                val n = unattributedDrops.incrementAndGet()
                if (n % 25 == 1) println("[luciola] drop frame: no endpoint for " + from + " (total=" + n + ")")
                return
            }
            inboundByEndpoint[endpointKey(endpoint.host, endpoint.port)] = session
            emitOrBlock(ReceivedDatagram(message, endpoint.host, endpoint.port))
        }

        /** 配对帧的三关校验:会话指纹绑定公钥、二维码里的 secret、再落信任。 */
        private fun handlePairingFrame(bytes: ByteArray) {
            val admission = pairing ?: return
            val expected = admission.expectedPairSecret() ?: return
            val request = PairingFrame.decodeRequest(bytes) ?: return
            val fingerprint = sessionFingerprint ?: return

            val failure = PairingFrame.validate(request, fingerprint, expected)
            if (failure != null) {
                deny(request.nodeId, failure)
                return
            }
            val accepted = runCatching { admission.onPaired(request.nodeId, request.publicKey, request.name) }
            if (accepted.isFailure) {
                deny(request.nodeId, "trust_persist_failed")
                return
            }
            trusted = true
            runCatching { session.send(PairingFrame.encodeReply(true)) }
        }

        private fun deny(nodeId: String?, reason: String) {
            rejectedInbound += 1
            runCatching { pairing?.onPairingRejected(nodeId, reason) }
            runCatching { session.send(PairingFrame.encodeReply(false, reason)) }
            // 先让应答出队再断:立刻 disconnect 会把排队中的 deny 帧丢掉,对端只能看到"超时"。
            Thread({
                try {
                    Thread.sleep(200)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                runCatching { session.disconnect() }
            }, "tl-pairing-deny").apply {
                isDaemon = true
                start()
            }
        }

        /** 没有超时保护时,一个陌生连接就能把 accept 循环占死(一次只接一条)。 */
        private fun armPendingDeadline() {
            Thread({
                try {
                    Thread.sleep(pendingPairingTimeoutMs)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (!trusted) {
                    rejectedInbound += 1
                    runCatching { pairing?.onPairingRejected(null, "pairing_timeout") }
                    runCatching { session.disconnect() }
                }
            }, "tl-pairing-deadline").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun endpointKey(host: String, port: Int): String = "$host:$port"
}
