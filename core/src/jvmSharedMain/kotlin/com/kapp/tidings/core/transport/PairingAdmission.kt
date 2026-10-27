// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import uniffi.luciola_uniffi.identityFingerprint
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * 配对准入:让「手机上扫了主机二维码」的那台设备，能把自己**自证的公钥**交给主机，
 * 主机核对「公钥指纹 == 这条加密会话握手出来的对端指纹」之后才放行。
 *
 * ## 为什么需要它(而不是直接 TOFU)
 *
 * luciola 的入站回调只报**对端指纹**，不报公钥、也不报 socket 地址；而 TrustStore
 * 的条目要公钥。若主机直接按指纹 TOFU 放行，就等于「谁先连进来谁就是我信任的设备」。
 *
 * 这里用一条**传输层控制帧**补上自证:
 *
 * ```
 * TLPAIR1 {"nodeId":"...","pk":"<base64 32B>","secret":"<base64>","name":"可选"}
 * ```
 *
 * - 会话已由 Ed25519 双向签名认证 ⇒ 指纹可信；
 * - 帧里带的公钥必须满足 `identityFingerprint(pk) == 会话指纹`(防止冒名)；
 * - `secret` 来自主机二维码(128 位随机数，不在发现广播里)，证明对方真的扫过码。
 *
 * 三关都过才回 `ok`，并把 (nodeId, pk) 交给宿主落盘信任。控制帧**不进 Messenger**。
 */
interface PairingAdmission {
    /**
     * 本机二维码里带出去的配对密钥;null = 这个宿主不开放配对入站
     * (仍按 deny-by-default 直接断开)。
     */
    fun expectedPairSecret(): ByteArray?

    /** 三关都过:宿主把 (nodeId, publicKey) 写进信任库(通常是 FULL)。 */
    fun onPaired(nodeId: String, publicKey: ByteArray, name: String?)

    /**
     * 被拒绝的一次配对请求(secret 错 / 公钥与指纹不符)。默认忽略 ——
     * 诊断与限流由宿主决定，传输层只保证拒绝。
     */
    fun onPairingRejected(nodeId: String?, reason: String) {}
}

/** 发起侧收到的配对结果(主机对 PairingRequest 的应答)。 */
data class PairingResult(
    val accepted: Boolean,
    val reason: String?,
    val host: String,
    val port: Int,
)

/** 传输层配对控制帧的线格式(magic + JSON)。 */
object PairingFrame {
    private val MAGIC = "TLPAIR1".toByteArray(Charsets.US_ASCII)
    private const val MAX_FRAME_BYTES = 1024

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Serializable
    private data class Request(
        val nodeId: String,
        val pk: String,
        val secret: String,
        val name: String? = null,
    )

    @Serializable
    private data class Reply(val ok: Boolean, val reason: String? = null)

    data class DecodedRequest(
        val nodeId: String,
        val publicKey: ByteArray,
        val secret: ByteArray,
        val name: String?,
    )

    fun looksLikePairingFrame(bytes: ByteArray): Boolean {
        if (bytes.size < MAGIC.size || bytes.size > MAX_FRAME_BYTES) return false
        return MAGIC.indices.all { bytes[it] == MAGIC[it] }
    }

    fun encodeRequest(nodeId: String, publicKey: ByteArray, secret: ByteArray, name: String? = null): ByteArray {
        val payload = json.encodeToString(
            Request.serializer(),
            Request(nodeId, b64(publicKey), b64(secret), name),
        )
        return MAGIC + payload.toByteArray(Charsets.UTF_8)
    }

    fun decodeRequest(bytes: ByteArray): DecodedRequest? {
        if (!looksLikePairingFrame(bytes)) return null
        val body = bytes.copyOfRange(MAGIC.size, bytes.size).toString(Charsets.UTF_8)
        val parsed = runCatching { json.decodeFromString(Request.serializer(), body) }.getOrNull() ?: return null
        val pk = unb64(parsed.pk) ?: return null
        val secret = unb64(parsed.secret) ?: return null
        if (pk.size != ED25519_PUBLIC_KEY_SIZE || parsed.nodeId.isBlank()) return null
        return DecodedRequest(parsed.nodeId, pk, secret, parsed.name)
    }

    fun encodeReply(ok: Boolean, reason: String? = null): ByteArray =
        MAGIC + json.encodeToString(Reply.serializer(), Reply(ok, reason)).toByteArray(Charsets.UTF_8)

    fun decodeReply(bytes: ByteArray): Pair<Boolean, String?>? {
        if (!looksLikePairingFrame(bytes)) return null
        val body = bytes.copyOfRange(MAGIC.size, bytes.size).toString(Charsets.UTF_8)
        val parsed = runCatching { json.decodeFromString(Reply.serializer(), body) }.getOrNull() ?: return null
        return parsed.ok to parsed.reason
    }

    /**
     * 三关校验:公钥指纹 == 会话指纹、secret 常量时间相等。
     * 返回 null 表示通过，否则返回拒绝原因(写进日志/回复)。
     */
    internal fun validate(
        request: DecodedRequest,
        sessionFingerprint: String,
        expectedSecret: ByteArray,
    ): String? {
        val actual = runCatching { identityFingerprint(request.publicKey) }.getOrNull()
            ?: return "bad_public_key"
        if (actual != sessionFingerprint) return "fingerprint_mismatch"
        if (!MessageDigest.isEqual(request.secret, expectedSecret)) return "bad_secret"
        return null
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun b64(bytes: ByteArray): String = Base64.UrlSafe.encode(bytes)

    private fun unb64(text: String): ByteArray? =
        runCatching { Base64.UrlSafe.decode(text) }.getOrNull()

    internal const val ED25519_PUBLIC_KEY_SIZE = 32
}
