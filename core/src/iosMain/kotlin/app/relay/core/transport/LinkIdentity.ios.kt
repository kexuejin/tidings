// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

import app.relay.core.crypto.TlsPin
import app.relay.core.fs.deleteFile
import app.relay.core.fs.fileAttributes
import app.relay.core.fs.makeDirectories
import app.relay.core.fs.openRandomAccess
import app.relay.core.fs.pathJoin
import app.relay.core.fs.readFileBytes
import app.relay.core.fs.use
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.kCFNumberSInt32Type
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Security.SecCertificateCreateWithData
import platform.Security.SecIdentityRef
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.kSecAttrApplicationTag
import platform.Security.kSecAttrIsPermanent
import platform.Security.kSecClass
import platform.Security.kSecClassCertificate
import platform.Security.kSecClassIdentity
import platform.Security.kSecClassKey
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnRef
import platform.Security.kSecValueRef
import platform.Security.sec_identity_create
import platform.Security.sec_identity_t
import platform.Security.SecCertificateRef
import platform.Security.SecKeyCopyExternalRepresentation
import platform.Security.SecKeyCopyPublicKey
import platform.Security.SecKeyCreateRandomKey
import platform.Security.SecKeyCreateSignature
import platform.Security.SecKeyCreateWithData
import platform.Security.SecKeyRef
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecAttrKeyClass
import platform.Security.kSecAttrKeyClassPrivate
import platform.Security.kSecAttrKeySizeInBits
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecKeyAlgorithmECDSASignatureMessageX962SHA256
import platform.Security.kSecRandomDefault

/**
 * iOS 的本地 TLS 身份（K2 第一步，task-26 ①）：EC P-256 密钥 + 自签 IP 证书 + pin。
 *
 * 与 JVM 侧**同一套口径**（task-13 裁决）：
 *  · 证书字节由共享的 [LinkCertificateDer] 生成 —— 两端逐字节同布局，pin 才可能一致；
 *  · pin = sha256(SPKI)，SPKI 由 iOS 只给的 X9.63 原始点经
 *    [LinkCertificateDer.ecP256SpkiFromRawPoint] 拼出；
 *  · 私钥不变时**重签不换 pin**（自动续签）；私钥/公钥/证书分别落盘。
 *
 * 本文件只做"密钥 + 证书 + pin"这一层；把它变成 `sec_identity_t` 的 keychain 那一步
 * （`SecItemAdd` + `SecItemCopyMatching(kSecClassIdentity)`）在下一步，
 * 因为那一步要往 keychain 写东西、需要单独的验证。
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosLinkIdentity(
    val privateKey: SecKeyRef,
    val certificate: SecCertificateRef,
    val spki: ByteArray,
    val certDer: ByteArray,
    val pin: TlsPin,
    /**
     * keychain 里查回来的 SecIdentity（第 2 步）。
     *
     * 为什么非要走 keychain：`sec_protocol_options_set_local_identity` 要的是 `sec_identity_t`，
     * 而 **SecIdentity 没有公开的"用 key+cert 现造一个"构造函数** —— 只能把 key 与 cert 以
     * 同一个 application tag 放进 keychain，再用 `SecItemCopyMatching(kSecClassIdentity)` 取回
     * （task-26 裁决走 (b)：不手写 PKCS#12 容器）。
     */
    val identity: SecIdentityRef?,
    /** 第 3 步：Network.framework 认的那个 `sec_identity_t`（`sec_identity_create`）。 */
    val secIdentity: sec_identity_t?,
    /**
     * keychain 那一步的 OSStatus（null = 成功）。
     *
     * **实测（模拟器测试进程）**：`SecItemAdd(key)` 返回 **-25291 errSecNotAvailable** ——
     * Kotlin/Native 的测试二进制不是 app（没有 app 容器 / keychain-access-group 权限），
     * 系统直接说"没有可用的 keychain"。真机/模拟器**App 进程**里这一步是正常的（有容器）。
     * 所以这里把状态**如实带出来**：拿不到 identity 时 `secIdentity` 为 null，
     * 传输层据此**拒绝启动**（fail-closed），而不是"没有客户端证书也去连"。
     */
    val keychainStatus: Int?,
) {
    companion object {
        private const val KEY_FILE = "link-key.x963"
        private const val PUBLIC_FILE = "link-pub.spki"
        private const val CERT_FILE = "link-cert.der"

        /** 证书超过这个年龄就重签（同一把私钥 ⇒ pin 不变）。 */
        private const val RENEW_AFTER_MS = 300L * 24 * 3600 * 1000

        fun ensure(dir: String, addresses: List<String>): IosLinkIdentity {
            makeDirectories(dir)
            val keyPath = pathJoin(dir, KEY_FILE)
            val publicPath = pathJoin(dir, PUBLIC_FILE)
            val certPath = pathJoin(dir, CERT_FILE)
            val sanIps = addresses.mapNotNull { LinkCertificateDer.ipBytes(it) }

            val keyData = readFileBytes(keyPath)
            val storedSpki = readFileBytes(publicPath)
            val storedCert = readFileBytes(certPath)
            val certAge = fileAttributes(certPath)?.mtime?.let { now() - it } ?: Long.MAX_VALUE

            val fresh = keyData != null && storedSpki != null && storedCert != null && certAge < RENEW_AFTER_MS
            val privateKey = if (fresh) {
                createKeyFromData(keyData)
            } else {
                keyData?.let { runCatching { createKeyFromData(it) }.getOrNull() } ?: generateKey()
            }
            val spki = if (fresh) storedSpki else publicSpki(privateKey)
            val certDer = if (fresh) {
                storedCert
            } else {
                LinkCertificateDer.selfSigned(
                    spki = spki,
                    serial = randomBytes(16),
                    sanIps = sanIps,
                    notBeforeMillis = now() - 300_000L,
                    notAfterMillis = now() + 365L * 24 * 3600 * 1000,
                    sign = { tbs -> sign(privateKey, tbs) },
                )
            }
            if (!fresh) {
                writeFile(keyPath, exportData(SecKeyCopyExternalRepresentation(privateKey, null)))
                writeFile(publicPath, spki)
                writeFile(certPath, certDer)
            }
            val certificate = SecCertificateCreateWithData(null, certDer.toCFData())
                ?: error("SecCertificateCreateWithData 失败（证书 DER 形状不对）")
            val keychain = keychainIdentity(privateKey, certificate)
            return IosLinkIdentity(
                privateKey = privateKey,
                certificate = certificate,
                spki = spki,
                certDer = certDer,
                pin = TlsPin.ofSpki(spki),
                identity = keychain.identity,
                secIdentity = keychain.identity?.let { sec_identity_create(it) },
                keychainStatus = keychain.status,
            )
        }

        /** keychain 里的 application tag：key 与 cert **必须共用它**，identity 才查得到。 */
        private val TAG = "app.relay.link.identity".encodeToByteArray()

        /**
         * 第 2 步：把 key + cert 以同一个 tag 放进 keychain，再取回 SecIdentity。
         *
         * 先删同 tag 的旧项是**必需**的：证书重签之后旧的 cert 还在 keychain 里，
         * `SecItemCopyMatching(kSecClassIdentity)` 可能配到旧的那一份（本地身份"看起来换了"
         * 但 TLS 层还是旧证书）—— 症状和"pin 变了"一样难查。
         */
        /** keychain 那一步的结果：identity（成功时非空）与状态码（成功时 null）。 */
        private class KeychainResult(val identity: SecIdentityRef?, val status: Int?)

        private fun keychainIdentity(key: SecKeyRef, certificate: SecCertificateRef): KeychainResult = memScoped {
            val tag = TAG.toCFData()
            for (cls in listOf(kSecClassKey, kSecClassCertificate, kSecClassIdentity)) {
                SecItemDelete(cfDictionary(kSecClass to cls, kSecAttrApplicationTag to tag))
            }
            val keyStatus = SecItemAdd(
                cfDictionary(
                    kSecClass to kSecClassKey,
                    kSecValueRef to key,
                    kSecAttrApplicationTag to tag,
                    kSecAttrIsPermanent to kCFBooleanTrue,
                ),
                null,
            )
            // 拿不到 keychain（测试进程没有 app 容器）时**不抛**：把状态带出去，让上层 fail-closed。
            if (keyStatus != 0) return@memScoped KeychainResult(null, keyStatus.toInt())
            val certStatus = SecItemAdd(
                cfDictionary(
                    kSecClass to kSecClassCertificate,
                    kSecValueRef to certificate,
                    kSecAttrApplicationTag to tag,
                ),
                null,
            )
            if (certStatus != 0) return@memScoped KeychainResult(null, certStatus.toInt())

            val result = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(
                cfDictionary(
                    kSecClass to kSecClassIdentity,
                    kSecAttrApplicationTag to tag,
                    kSecReturnRef to kCFBooleanTrue,
                    kSecMatchLimit to kSecMatchLimitOne,
                ),
                result.ptr,
            )
            if (status != 0) return@memScoped KeychainResult(null, status.toInt())
            KeychainResult(result.value as? SecIdentityRef, null)
        }

        // ---- 平台原语 ----

        /**
         * EC P-256 **私钥**的属性。
         *
         * `kSecAttrKeyClass` 不能省：没有它，`SecKeyCreateWithData` 不知道这串字节是私钥
         * （默认按公钥解释），于是"落盘的私钥读不回来"——第一次签发成功、**第二次（模拟重启）**
         * 就失败；更糟的是续签路径会静默退回"重新生成密钥"，**pin 跟着变**（模拟器上实测到：
         * 续签后 pin 从 c7cd… 变成 0b4a…）。这两个症状同源，都是缺这一个属性。
         */
        private fun keyAttributes(): CFMutableDictionaryRef? = memScoped {
            val bits = alloc<IntVar>().apply { value = 256 }
            cfDictionary(
                kSecAttrKeyType to kSecAttrKeyTypeECSECPrimeRandom,
                kSecAttrKeySizeInBits to CFNumberCreate(null, kCFNumberSInt32Type, bits.ptr),
                kSecAttrKeyClass to kSecAttrKeyClassPrivate,
            )
        }

        private fun generateKey(): SecKeyRef =
            SecKeyCreateRandomKey(keyAttributes(), null)
                ?: error("SecKeyCreateRandomKey 失败（EC P-256）")

        private fun createKeyFromData(data: ByteArray): SecKeyRef =
            SecKeyCreateWithData(data.toCFData(), keyAttributes(), null)
                ?: error("SecKeyCreateWithData 失败（落盘的私钥读不回来）")

        /** 公钥 → X9.63 原始点 → 共享代码拼出 SPKI（与 JVM 的 `PublicKey.encoded` 同布局）。 */
        private fun publicSpki(key: SecKeyRef): ByteArray {
            val public = SecKeyCopyPublicKey(key) ?: error("SecKeyCopyPublicKey 失败")
            val raw = exportData(SecKeyCopyExternalRepresentation(public, null))
            return LinkCertificateDer.ecP256SpkiFromRawPoint(raw)
        }

        private fun sign(key: SecKeyRef, tbs: ByteArray): ByteArray {
            val signature = SecKeyCreateSignature(
                key,
                kSecKeyAlgorithmECDSASignatureMessageX962SHA256,
                tbs.toCFData(),
                null,
            ) ?: error("SecKeyCreateSignature 失败")
            return exportData(signature)
        }

        private fun cfDictionary(vararg pairs: Pair<CFTypeRef?, CFTypeRef?>): CFMutableDictionaryRef? {
            val dict = CFDictionaryCreateMutable(
                null,
                pairs.size.toLong(),
                kCFTypeDictionaryKeyCallBacks.ptr,
                kCFTypeDictionaryValueCallBacks.ptr,
            )
            for ((key, value) in pairs) CFDictionaryAddValue(dict, key, value)
            return dict
        }

        private fun ByteArray.toCFData(): CFDataRef? = usePinned { pinned ->
            CFDataCreate(null, pinned.addressOf(0).reinterpret(), size.toLong())
        }

        private fun exportData(data: CFDataRef?): ByteArray {
            val cfData = data ?: error("期望 CFData，拿到 null")
            val length = CFDataGetLength(cfData).toInt()
            val pointer = CFDataGetBytePtr(cfData) ?: error("CFData 没有字节")
            return pointer.readBytes(length)
        }

        private fun randomBytes(size: Int): ByteArray {
            val out = ByteArray(size)
            out.usePinned { pinned ->
                SecRandomCopyBytes(kSecRandomDefault, size.toULong(), pinned.addressOf(0).reinterpret<UByteVar>())
            }
            return out
        }

        private fun writeFile(path: String, bytes: ByteArray) {
            deleteFile(path)
            openRandomAccess(path, forWrite = true).use { it.append(bytes, bytes.size) }
        }

        private fun now(): Long = app.relay.core.nowMillis()
    }
}
