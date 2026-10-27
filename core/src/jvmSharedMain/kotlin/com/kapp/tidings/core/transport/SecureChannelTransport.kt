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
) : DatagramTransport {
    private val outbound = ConcurrentHashMap<String, SecureSession>()

    /**
     * 握手完成前发出的配对帧(扫码后立刻触发时几乎必然遇到):
     * send 在未连接时返回 false,帧会丢 —— 这里先存下来,等 "connected" 再补发。
     * 真机实测:不补发时主机永远收不到 TLPAIR1,表现为"扫码后一直不配对"。
     */
    private val pendingPairing = ConcurrentHashMap<String, ByteArray>()
    private val inboundByEndpoint = ConcurrentHashMap<String, SecureSession>()

    private val _incoming = MutableSharedFlow<ReceivedDatagram>(extraBufferCapacity = 256)
    override val incoming: SharedFlow<ReceivedDatagram> = _incoming

    private val _pairingResults = MutableSharedFlow<PairingResult>(extraBufferCapacity = 16)

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
                            if (state == "connected") {
                                // 扫码后立刻发出的配对帧可能早于握手完成:连上就补发一次。
                                pendingPairing.remove(key)?.let { queued ->
                                    runCatching { session.send(queued) }
                                }
                            }
                            if (state == "error" || state == "closed") {
                                outbound.remove(key, session)
                            }
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
                            _incoming.tryEmit(ReceivedDatagram(message, host, port))
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

        override fun onState(state: String, detail: String?) {
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
            _incoming.tryEmit(ReceivedDatagram(message, endpoint.host, endpoint.port))
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
