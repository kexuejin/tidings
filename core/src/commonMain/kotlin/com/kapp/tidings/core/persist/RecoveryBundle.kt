// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.persist

import com.kapp.tidings.core.crypto.AES_GCM_TAG_BYTES
import com.kapp.tidings.core.crypto.AeadException
import com.kapp.tidings.core.crypto.aes256GcmOpen
import com.kapp.tidings.core.crypto.aes256GcmSeal
import com.kapp.tidings.core.crypto.pbkdf2HmacSha256
import com.kapp.tidings.core.crypto.secureRandomBytes
import com.kapp.tidings.core.crypto.TlsPin
import com.kapp.tidings.core.protocol.Codec
import kotlinx.serialization.Serializable

/** 恢复包失败的程序化原因（message 仅供日志）。 */
enum class RecoveryBundleFailure {
    PASSWORD_REQUIRED,
    BAD_FORMAT,
    UNSUPPORTED_VERSION,
    AUTH_FAILED,
    MANIFEST_MISMATCH,
    INVALID_PAYLOAD,
}

class RecoveryBundleException(
    val failure: RecoveryBundleFailure,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * 恢复包的 manifest：与载荷**一起**进 AEAD —— 完整性由同一个 tag 覆盖，
 * 导入时的"成套校验"靠它（manifest 与载荷必须互相咬合，见 [RecoveryBundle.importIdentity]）。
 */
@Serializable
data class RecoveryManifest(
    val kind: String,
    val nodeId: String,
    val createdAtEpochMs: Long,
    val peerPinCount: Int,
)

/**
 * 身份载荷：nodeId + link 私钥（**平台无关的 P-256 标量**，32 字节）+ SPKI + 对端 pin 摘要。
 *
 * 为什么装标量不装各平台的原始文件：桌面/Android 的私钥文件是 PKCS#8，iOS 是 X9.63
 * 外部表示 —— 只有标量才能让同一份身份包在任意两端导入（JVM 侧往返包装见
 * `EcP256Pkcs8`，iOS 的外部表示本身就是标量）。
 *
 * 对端 pin 摘要是为了恢复后公网重连不触发 fail-closed 拒连（Q4，硬上限 [MAX_PEER_PINS]）。
 */
class RecoveryIdentityPayload(
    val nodeId: String,
    val ecPrivateScalar: ByteArray,
    val spki: ByteArray,
    val peerPins: List<PeerPin> = emptyList(),
) {
    /** 一个对端的信任根摘要（nodeId → pin wire，`sha256:<hex>`）。 */
    class PeerPin(val nodeId: String, val pinWire: String)

    override fun equals(other: Any?): Boolean =
        other is RecoveryIdentityPayload &&
            other.nodeId == nodeId &&
            other.ecPrivateScalar.contentEquals(ecPrivateScalar) &&
            other.spki.contentEquals(spki) &&
            other.peerPins.size == peerPins.size &&
            other.peerPins.zip(peerPins).all { (a, b) -> a.nodeId == b.nodeId && a.pinWire == b.pinWire }

    override fun hashCode(): Int {
        var h = nodeId.hashCode()
        h = h * 31 + ecPrivateScalar.contentHashCode()
        h = h * 31 + spki.contentHashCode()
        return h
    }

    override fun toString(): String =
        "RecoveryIdentityPayload(nodeId=$nodeId, scalar=${ecPrivateScalar.size}B, spki=${spki.size}B, peerPins=${peerPins.size})"
}

/**
 * 口令加密的恢复包容器（L1；L0 的 Android/iOS 身份包复用同一份 [RecoveryIdentityPayload]）。
 *
 * 布局（多字节整数一律大端）：
 * ```
 * magic "RLRB"(4) | formatVersion(u16) | kdfId(u8) | iterations(u32) | salt(16) | nonce(12)
 * | AEAD( manifestLen(u32) | manifest JSON | payload )
 * ```
 * 头部（magic..nonce）作为 **AAD** 参与认证：头部被篡改 = 整帧认证失败，不存在
 * "换一组 KDF 参数再试"的空间。manifest 与载荷一起进 AEAD（放外面的 manifest
 * 没有任何机制支撑"篡改拒绝"）。
 *
 * 密钥 = PBKDF2-HMAC-SHA256(口令, salt, iterations)（纯 Kotlin 实现，跨端逐字节一致；
 * 迭代次数是**格式字段**，随文件存储，将来调参不破坏旧文件）。
 *
 * 兼容性口径：**旧构建不认识新版本的包**（version 只升不降，SQLDelight 不做降级迁移的
 * 同款立场）—— 导入端见到不认识的 version 直接 [RecoveryBundleFailure.UNSUPPORTED_VERSION]。
 */
object RecoveryBundle {

    const val FORMAT_VERSION: Int = 1

    /** kdfId：PBKDF2-HMAC-SHA-256。 */
    const val KDF_ID_PBKDF2_HMAC_SHA256: Int = 1

    /** 首版迭代次数。OWASP 2023 对 PBKDF2-HMAC-SHA256 的推荐量级；导出/导入各付一次。 */
    const val KDF_ITERATIONS: Int = 600_000

    const val KIND_IDENTITY: String = "identity"

    /** 对端 pin 摘要硬上限（Q4）。 */
    const val MAX_PEER_PINS: Int = 50

    private const val MAGIC = "RLRB"
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val KDF_KEY_BYTES = 32

    private const val HEADER_BYTES = MAGIC.length + 2 + 1 + 4 + SALT_BYTES + NONCE_BYTES

    private const val MAX_NODE_ID_BYTES = 64
    private const val MAX_SPKI_BYTES = 1024
    private const val MAX_PIN_WIRE_BYTES = 80
    private const val MAX_MANIFEST_BYTES = 4096
    private const val MAX_ITERATIONS = 10_000_000

    /** 导出身份包。口令强制非空（Q3：禁止明文导出），nowEpochMs 由调用方给（测试可钉死）。 */
    fun exportIdentity(
        payload: RecoveryIdentityPayload,
        password: String,
        nowEpochMs: Long,
    ): ByteArray {
        if (password.isEmpty()) {
            throw RecoveryBundleException(RecoveryBundleFailure.PASSWORD_REQUIRED, "password is required")
        }
        validatePayloadForExport(payload)

        val manifest = RecoveryManifest(
            kind = KIND_IDENTITY,
            nodeId = payload.nodeId,
            createdAtEpochMs = nowEpochMs,
            peerPinCount = payload.peerPins.size,
        )
        return sealBundle(manifest, encodeIdentityPayload(payload), password)
    }

    /**
     * 封包原语（internal：成套校验的注入测试要从这里塞不一致的 manifest/payload 组合）。
     * 生产路径的 salt/nonce 在这里生成；测试传什么由调用方决定 —— 见测试文件的用法。
     */
    fun sealBundle(
        manifest: RecoveryManifest,
        payloadBytes: ByteArray,
        password: String,
        salt: ByteArray = secureRandomBytes(SALT_BYTES),
        nonce: ByteArray = secureRandomBytes(NONCE_BYTES),
        iterations: Int = KDF_ITERATIONS,
    ): ByteArray {
        require(iterations in 1..MAX_ITERATIONS) { "iterations out of range: $iterations" }
        require(salt.size == SALT_BYTES) { "salt must be $SALT_BYTES bytes" }
        require(nonce.size == NONCE_BYTES) { "nonce must be $NONCE_BYTES bytes" }
        val manifestBytes = Codec.wireJson
            .encodeToString(RecoveryManifest.serializer(), manifest)
            .encodeToByteArray()
        val plaintext = BytesOut(64)
            .u32(manifestBytes.size.toLong())
            .bytes(manifestBytes)
            .bytes(payloadBytes)
            .toArray()

        val header = buildHeader(salt, nonce, iterations)
        val key = pbkdf2HmacSha256(password.encodeToByteArray(), salt, iterations, KDF_KEY_BYTES)
        val sealed = aes256GcmSeal(key, nonce, plaintext, header)
        return header + sealed
    }

    /**
     * 导入身份包。成功 = 口令正确 **且** 完整性通过 **且** manifest 与载荷成套咬合
     * （kind 一致、nodeId 一致、pin 计数一致）—— 只对上 nodeId 的半截包在这里就死，
     * 不会带着"看起来能用的身份"流到恢复流程里。
     */
    fun importIdentity(bytes: ByteArray, password: String): RecoveryIdentityPayload {
        if (password.isEmpty()) {
            throw RecoveryBundleException(RecoveryBundleFailure.PASSWORD_REQUIRED, "password is required")
        }
        if (bytes.size < HEADER_BYTES + 4 + AES_GCM_TAG_BYTES) {
            throw RecoveryBundleException(RecoveryBundleFailure.BAD_FORMAT, "too short: ${bytes.size}")
        }
        for (i in MAGIC.indices) {
            if (bytes[i] != MAGIC[i].code.toByte()) {
                throw RecoveryBundleException(RecoveryBundleFailure.BAD_FORMAT, "bad magic")
            }
        }
        val version = BytesIn(bytes).apply { pos = MAGIC.length }.u16()
        if (version != FORMAT_VERSION) {
            throw RecoveryBundleException(RecoveryBundleFailure.UNSUPPORTED_VERSION, "format version $version")
        }
        val kdfId = bytes[MAGIC.length + 2].toInt() and 0xff
        if (kdfId != KDF_ID_PBKDF2_HMAC_SHA256) {
            throw RecoveryBundleException(RecoveryBundleFailure.UNSUPPORTED_VERSION, "kdf id $kdfId")
        }
        val iterations = BytesIn(bytes).apply { pos = MAGIC.length + 3 }.u32().toInt()
        if (iterations !in 1..MAX_ITERATIONS) {
            throw RecoveryBundleException(RecoveryBundleFailure.BAD_FORMAT, "iterations out of range: $iterations")
        }
        val salt = bytes.copyOfRange(MAGIC.length + 7, MAGIC.length + 7 + SALT_BYTES)
        val nonce = bytes.copyOfRange(HEADER_BYTES - NONCE_BYTES, HEADER_BYTES)
        val header = bytes.copyOfRange(0, HEADER_BYTES)

        val key = pbkdf2HmacSha256(password.encodeToByteArray(), salt, iterations, KDF_KEY_BYTES)
        val plaintext = try {
            aes256GcmOpen(key, nonce, bytes.copyOfRange(HEADER_BYTES, bytes.size), header)
        } catch (e: AeadException) {
            throw RecoveryBundleException(RecoveryBundleFailure.AUTH_FAILED, "authentication failed", e)
        }

        val reader = BytesIn(plaintext)
        val manifestLen = reader.u32()
        if (manifestLen !in 1..MAX_MANIFEST_BYTES || manifestLen > plaintext.size - 4) {
            throw RecoveryBundleException(RecoveryBundleFailure.BAD_FORMAT, "manifest length $manifestLen")
        }
        val manifest = try {
            Codec.wireJson.decodeFromString(
                RecoveryManifest.serializer(),
                reader.bytes(manifestLen.toInt()).decodeToString(),
            )
        } catch (e: Exception) {
            throw RecoveryBundleException(RecoveryBundleFailure.BAD_FORMAT, "manifest is not valid JSON", e)
        }
        val payload = try {
            decodeIdentityPayload(reader.bytes(reader.remaining()))
        } catch (e: IllegalArgumentException) {
            // 载荷缺字段/超上限/带尾巴 = 半截包，一律 BAD_FORMAT，不许流出导入层。
            throw RecoveryBundleException(RecoveryBundleFailure.BAD_FORMAT, "payload rejected: ${e.message}", e)
        }

        if (manifest.kind != KIND_IDENTITY) {
            throw RecoveryBundleException(RecoveryBundleFailure.MANIFEST_MISMATCH, "kind ${manifest.kind}")
        }
        if (manifest.nodeId != payload.nodeId) {
            throw RecoveryBundleException(
                RecoveryBundleFailure.MANIFEST_MISMATCH,
                "nodeId mismatch: manifest=${manifest.nodeId} payload=${payload.nodeId}",
            )
        }
        if (manifest.peerPinCount != payload.peerPins.size) {
            throw RecoveryBundleException(
                RecoveryBundleFailure.MANIFEST_MISMATCH,
                "peerPinCount mismatch: manifest=${manifest.peerPinCount} payload=${payload.peerPins.size}",
            )
        }
        return payload
    }

    private fun validatePayloadForExport(payload: RecoveryIdentityPayload) {
        if (payload.nodeId.isEmpty() || payload.nodeId.encodeToByteArray().size > MAX_NODE_ID_BYTES) {
            throw RecoveryBundleException(RecoveryBundleFailure.INVALID_PAYLOAD, "nodeId length out of range")
        }
        if (payload.ecPrivateScalar.size !in 1..32) {
            throw RecoveryBundleException(RecoveryBundleFailure.INVALID_PAYLOAD, "scalar must be 1..32 bytes")
        }
        if (payload.spki.isEmpty() || payload.spki.size > MAX_SPKI_BYTES) {
            throw RecoveryBundleException(RecoveryBundleFailure.INVALID_PAYLOAD, "spki length out of range")
        }
        if (payload.peerPins.size > MAX_PEER_PINS) {
            throw RecoveryBundleException(RecoveryBundleFailure.INVALID_PAYLOAD, "too many peer pins")
        }
        for (pin in payload.peerPins) {
            if (pin.nodeId.isEmpty() || pin.nodeId.encodeToByteArray().size > MAX_NODE_ID_BYTES ||
                TlsPin.parse(pin.pinWire) == null
            ) {
                throw RecoveryBundleException(RecoveryBundleFailure.INVALID_PAYLOAD, "invalid peer pin entry")
            }
        }
    }

    private fun buildHeader(salt: ByteArray, nonce: ByteArray, iterations: Int): ByteArray =
        BytesOut(HEADER_BYTES)
            .bytes(MAGIC.encodeToByteArray())
            .u16(FORMAT_VERSION)
            .u8(KDF_ID_PBKDF2_HMAC_SHA256)
            .u32(iterations.toLong())
            .bytes(salt)
            .bytes(nonce)
            .toArray()

    // ---- 载荷二进制编码（确定性；解码端所有字段都有上限与必填校验） ----

    fun encodeIdentityPayload(payload: RecoveryIdentityPayload): ByteArray {
        val out = BytesOut(64)
        val nodeIdBytes = payload.nodeId.encodeToByteArray()
        out.u16(nodeIdBytes.size).bytes(nodeIdBytes)
        out.u8(payload.ecPrivateScalar.size).bytes(payload.ecPrivateScalar)
        out.u16(payload.spki.size).bytes(payload.spki)
        out.u16(payload.peerPins.size)
        for (pin in payload.peerPins) {
            val pinNode = pin.nodeId.encodeToByteArray()
            val pinWire = pin.pinWire.encodeToByteArray()
            out.u16(pinNode.size).bytes(pinNode)
            out.u8(pinWire.size).bytes(pinWire)
        }
        return out.toArray()
    }

    fun decodeIdentityPayload(bytes: ByteArray): RecoveryIdentityPayload {
        val reader = BytesIn(bytes)
        val nodeId = reader.bytes(reader.u16()).decodeToString()
        require(nodeId.isNotEmpty() && nodeId.encodeToByteArray().size <= MAX_NODE_ID_BYTES) { "nodeId length" }
        // 标量统一归一化成 32 字节（左补零），导入方拿到的永远是定长。
        val scalar = reader.bytes(reader.u8())
        require(scalar.size in 1..32) { "scalar length" }
        val spki = reader.bytes(reader.u16())
        require(spki.isNotEmpty() && spki.size <= MAX_SPKI_BYTES) { "spki length" }
        val pinCount = reader.u16()
        require(pinCount <= MAX_PEER_PINS) { "too many peer pins: $pinCount" }
        val pins = ArrayList<RecoveryIdentityPayload.PeerPin>(pinCount)
        repeat(pinCount) {
            val pinNode = reader.bytes(reader.u16()).decodeToString()
            val pinWire = reader.bytes(reader.u8()).decodeToString()
            require(pinNode.isNotEmpty() && pinNode.encodeToByteArray().size <= MAX_NODE_ID_BYTES) { "pin nodeId length" }
            require(pinWire.encodeToByteArray().size <= MAX_PIN_WIRE_BYTES) { "pin wire length" }
            require(TlsPin.parse(pinWire) != null) { "pin wire format: $pinWire" }
            pins.add(RecoveryIdentityPayload.PeerPin(pinNode, pinWire))
        }
        require(!reader.hasRemaining()) { "trailing bytes in payload" }
        return RecoveryIdentityPayload(
            nodeId = nodeId,
            ecPrivateScalar = ByteArray(32).also { scalar.copyInto(it, 32 - scalar.size) },
            spki = spki,
            peerPins = pins,
        )
    }
}

/** 定长编码的小工具：全部大端（网络序，与协议线格式同口径）。 */
private class BytesOut(capacity: Int) {
    private var buf = ByteArray(capacity)
    private var size = 0

    fun u8(v: Int): BytesOut {
        ensure(1)
        buf[size++] = v.toByte()
        return this
    }

    fun u16(v: Int): BytesOut {
        ensure(2)
        buf[size++] = (v ushr 8).toByte()
        buf[size++] = v.toByte()
        return this
    }

    fun u32(v: Long): BytesOut {
        ensure(4)
        buf[size++] = (v ushr 24).toByte()
        buf[size++] = (v ushr 16).toByte()
        buf[size++] = (v ushr 8).toByte()
        buf[size++] = v.toByte()
        return this
    }

    fun bytes(b: ByteArray): BytesOut {
        ensure(b.size)
        b.copyInto(buf, size)
        size += b.size
        return this
    }

    fun toArray(): ByteArray = buf.copyOf(size)

    private fun ensure(n: Int) {
        if (size + n > buf.size) {
            buf = buf.copyOf(maxOf(buf.size * 2, size + n))
        }
    }
}

private class BytesIn(private val bytes: ByteArray) {
    var pos = 0

    fun u8(): Int {
        require(pos + 1 <= bytes.size) { "truncated u8" }
        return bytes[pos++].toInt() and 0xff
    }

    fun u16(): Int {
        require(pos + 2 <= bytes.size) { "truncated u16" }
        val v = ((bytes[pos].toInt() and 0xff) shl 8) or (bytes[pos + 1].toInt() and 0xff)
        pos += 2
        return v
    }

    fun u32(): Long {
        require(pos + 4 <= bytes.size) { "truncated u32" }
        var v = 0L
        repeat(4) {
            v = (v shl 8) or (bytes[pos++].toLong() and 0xff)
        }
        return v
    }

    fun bytes(n: Int): ByteArray {
        require(n >= 0 && pos + n <= bytes.size) { "truncated bytes: want $n at $pos of ${bytes.size}" }
        val out = bytes.copyOfRange(pos, pos + n)
        pos += n
        return out
    }

    fun remaining(): Int = bytes.size - pos

    fun hasRemaining(): Boolean = pos < bytes.size
}
