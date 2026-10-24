// SPDX-License-Identifier: GPL-3.0-only

@file:OptIn(ExperimentalForeignApi::class)

package com.kapp.tidings.core.transport

import com.kapp.tidings.core.crypto.TlsPin
import com.kapp.tidings.core.fs.deleteFile
import com.kapp.tidings.core.fs.fileAttributes
import com.kapp.tidings.core.fs.makeDirectories
import com.kapp.tidings.core.fs.openRandomAccess
import com.kapp.tidings.core.fs.pathJoin
import com.kapp.tidings.core.fs.readFileBytes
import com.kapp.tidings.core.fs.use
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
import platform.Security.SecCertificateCopyData
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
 * **恢复语义（P1）**：`ensure` 的读取顺序是 **容器 → 钥匙串 → 才生成**。
 * 容器文件随沙箱删（卸载即丢），钥匙串项在卸载后幸存 —— 所以重装后必须从钥匙串
 * 收养旧私钥，绝不允许"容器一空就生成新钥"。旧实现正是在这里把幸存旧钥删掉换成新钥，
 * 加上 nodeId 已随 NSUserDefaults 死亡，对端只看到一个陌生设备，全程无报错。
 *
 * 本文件只做"密钥 + 证书 + pin"这一层；把它变成 `sec_identity_t` 的 keychain 那一步
 * （`SecItemAdd` + `SecItemCopyMatching(kSecClassIdentity)`）走 [LinkKeychainStore]，
 * 生产实现是 [SecItemKeychainStore]，测试注入假实现（Kotlin/Native 的测试二进制不是 app，
 * 没有 keychain 可用）。
 */

/** 身份是从哪来的。诊断字段，如实带出 —— 恢复是否生效就看它。 */
internal enum class IosIdentitySource {
    /** 容器文件（日常稳态、重签）。 */
    CONTAINER,
    /** 容器丢失/损坏后从钥匙串收养（重装场景的回退）。 */
    KEYCHAIN_FALLBACK,
    /** 全新生成（真首启：容器与钥匙串都没有）。 */
    GENERATED,
}

/** 钥匙串里幸存的身份快照。`certDer` 可为 null（只剩钥匙没有证书）。 */
internal class KeychainIdentitySnapshot(
    val keyExternal: ByteArray,
    val certDer: ByteArray?,
)

internal enum class KeychainWriteMode {
    /**
     * key + cert 都重写：key 项先删后加。**只有"保留身份的钥匙与钥匙串现存的不同"才允许** ——
     * 显式轮换语义（容器/导入是权威）。绝不是每次启动都走它。
     */
    ADD_KEY_AND_CERT,
    /** 只刷新 cert/identity，key 项一个字节都不动（日常稳态、重签、收养后的证书对齐）。 */
    UPDATE_CERT_ONLY,
}

/** keychain 那一步的结果：identity（成功时非空）与状态码（成功时 null）。 */
internal class KeychainWriteResult(val identity: SecIdentityRef?, val status: Int?)

/**
 * 钥匙串操作的可注入接缝：生产实现 [SecItemKeychainStore]；测试用假实现。
 *
 * 为什么必须是接口：Kotlin/Native 的测试二进制不是 app（没有 app 容器 / keychain-access-group
 * 权限），`SecItemAdd` 返回 errSecNotAvailable —— 真实 keychain 行为只能靠真机验收，
 * 恢复决策逻辑则靠假实现测。
 */
internal interface LinkKeychainStore {
    /** 钥匙串里现存的身份；null = 没有 key 项（或读不回来）。 */
    fun load(): KeychainIdentitySnapshot?

    /**
     * 把身份写进钥匙串并取回 SecIdentity。写入口径见 [KeychainWriteMode] ——
     * cert/identity **每次写都换新**（重签之后旧证书若还留在钥匙串里，`SecItemCopyMatching`
     * 可能配到旧的那份，症状和"pin 变了"一样难查）；key 项是否重写由 mode 决定。
     */
    fun write(key: SecKeyRef, certificate: SecCertificateRef, mode: KeychainWriteMode): KeychainWriteResult
}

@OptIn(ExperimentalForeignApi::class)
internal class SecItemKeychainStore : LinkKeychainStore {

    override fun load(): KeychainIdentitySnapshot? {
        val keyExternal = copyKeyExternal() ?: return null
        return KeychainIdentitySnapshot(keyExternal, copyCertDer())
    }

    /** 查 key 项并导出外部表示（收养比对的口径）；查不到或 keychain 不可用都按"没有"处理（null）。 */
    private fun copyKeyExternal(): ByteArray? = memScoped {
        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(
            cfDictionary(
                kSecClass to kSecClassKey,
                kSecAttrApplicationTag to TAG.encodeToByteArray().toCFData(),
                kSecReturnRef to kCFBooleanTrue,
                kSecMatchLimit to kSecMatchLimitOne,
            ),
            result.ptr,
        )
        if (status != 0) return@memScoped null
        val key = result.value as? SecKeyRef ?: return@memScoped null
        runCatching { exportData(SecKeyCopyExternalRepresentation(key, null)) }.getOrNull()
    }

    /** 查 cert 项并导出 DER；没有证书（只剩钥匙）返回 null。 */
    private fun copyCertDer(): ByteArray? = memScoped {
        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(
            cfDictionary(
                kSecClass to kSecClassCertificate,
                kSecAttrApplicationTag to TAG.encodeToByteArray().toCFData(),
                kSecReturnRef to kCFBooleanTrue,
                kSecMatchLimit to kSecMatchLimitOne,
            ),
            result.ptr,
        )
        if (status != 0) return@memScoped null
        val cert = result.value as? SecCertificateRef ?: return@memScoped null
        runCatching { exportData(SecCertificateCopyData(cert)) }.getOrNull()
    }

    override fun write(
        key: SecKeyRef,
        certificate: SecCertificateRef,
        mode: KeychainWriteMode,
    ): KeychainWriteResult = memScoped {
        val tag = TAG.encodeToByteArray().toCFData()
        for (cls in listOf(kSecClassCertificate, kSecClassIdentity)) {
            SecItemDelete(cfDictionary(kSecClass to cls, kSecAttrApplicationTag to tag))
        }
        if (mode == KeychainWriteMode.ADD_KEY_AND_CERT) {
            SecItemDelete(cfDictionary(kSecClass to kSecClassKey, kSecAttrApplicationTag to tag))
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
            if (keyStatus != 0) return@memScoped KeychainWriteResult(null, keyStatus.toInt())
        }
        val certStatus = SecItemAdd(
            cfDictionary(
                kSecClass to kSecClassCertificate,
                kSecValueRef to certificate,
                kSecAttrApplicationTag to tag,
            ),
            null,
        )
        if (certStatus != 0) return@memScoped KeychainWriteResult(null, certStatus.toInt())

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
        if (status != 0) return@memScoped KeychainWriteResult(null, status.toInt())
        KeychainWriteResult(result.value as? SecIdentityRef, null)
    }

    companion object {
        /** keychain 里的 application tag：key 与 cert **必须共用它**，identity 才查得到。 */
        private const val TAG = "com.kapp.tidings.link.identity"
    }
}

@OptIn(ExperimentalForeignApi::class)
internal class IosLinkIdentity(
    val privateKey: SecKeyRef,
    val certificate: SecCertificateRef,
    val spki: ByteArray,
    val certDer: ByteArray,
    val pin: TlsPin,
    /** 本次身份的来源（诊断：恢复是否生效看它）。 */
    val source: IosIdentitySource,
    /**
     * keychain 里查回来的 SecIdentity。
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

        fun ensure(
            dir: String,
            addresses: List<String>,
            keychain: LinkKeychainStore = SecItemKeychainStore(),
        ): IosLinkIdentity {
            makeDirectories(dir)
            val keyPath = pathJoin(dir, KEY_FILE)
            val publicPath = pathJoin(dir, PUBLIC_FILE)
            val certPath = pathJoin(dir, CERT_FILE)
            val sanIps = addresses.mapNotNull { LinkCertificateDer.ipBytes(it) }

            val containerKey = readFileBytes(keyPath)
            val storedSpki = readFileBytes(publicPath)
            val storedCert = readFileBytes(certPath)
            val certAge = fileAttributes(certPath)?.mtime?.let { now() - it } ?: Long.MAX_VALUE
            val chain = keychain.load()

            // 容器里的私钥解析失败（损坏）与"没有"同罪；钥匙串里幸存的旧钥不受影响。
            val containerPrivateKey = containerKey?.let { runCatching { createKeyFromData(it) }.getOrNull() }
            val containerFresh = containerPrivateKey != null && storedSpki != null &&
                storedCert != null && certAge < RENEW_AFTER_MS

            // 读顺序（P1 修复）：容器 → 钥匙串 → 才生成。
            //  · 容器可用 → 用容器（权威：显式轮换与导入的用户意志都在容器这边）；
            //  · 容器不可用、钥匙串有幸存身份 → 收养（重装场景的回退，pin 不变）；
            //  · 都没有 → 真首启，生成。
            var source = IosIdentitySource.CONTAINER
            val privateKey = when {
                containerPrivateKey != null -> containerPrivateKey
                chain != null -> runCatching { createKeyFromData(chain.keyExternal) }.getOrNull()
                    ?.also { source = IosIdentitySource.KEYCHAIN_FALLBACK }
                    ?: generateKey().also { source = IosIdentitySource.GENERATED }
                else -> generateKey().also { source = IosIdentitySource.GENERATED }
            }

            val fresh = containerFresh && source == IosIdentitySource.CONTAINER
            val spki = if (fresh && storedSpki != null) storedSpki else publicSpki(privateKey)
            val certDer = if (fresh && storedCert != null) {
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

            // 钥匙串对账（取代旧的无条件三连删）：
            //  · 钥匙串没有 key 项，或钥匙与保留身份不同 → 全量写入（后者 = 显式轮换语义，
            //    这是**唯一**允许删 key 项的分支）；
            //  · 钥匙与保留身份一致 → 只刷证书 —— 收养路径走这里，幸存的旧钥绝不被动。
            //    （旧实现无条件先删后加：重装后收养无从谈起，幸存旧钥直接被换成新钥。）
            val keyExternal = exportData(SecKeyCopyExternalRepresentation(privateKey, null))
            val mode = if (chain != null && chain.keyExternal.contentEquals(keyExternal)) {
                KeychainWriteMode.UPDATE_CERT_ONLY
            } else {
                KeychainWriteMode.ADD_KEY_AND_CERT
            }
            val keychainResult = keychain.write(privateKey, certificate, mode)

            return IosLinkIdentity(
                privateKey = privateKey,
                certificate = certificate,
                spki = spki,
                certDer = certDer,
                pin = TlsPin.ofSpki(spki),
                source = source,
                identity = keychainResult.identity,
                secIdentity = keychainResult.identity?.let { sec_identity_create(it) },
                keychainStatus = keychainResult.status,
            )
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

        private fun now(): Long = com.kapp.tidings.core.nowMillis()
    }
}

// ---- 共享的 cinterop 小工具（ensure 与 SecItemKeychainStore 都要用，放文件级） ----

private fun ByteArray.toCFData(): CFDataRef? = usePinned { pinned ->
    CFDataCreate(null, pinned.addressOf(0).reinterpret(), size.toLong())
}

internal fun exportData(data: CFDataRef?): ByteArray {
    val cfData = data ?: error("期望 CFData，拿到 null")
    val length = CFDataGetLength(cfData).toInt()
    val pointer = CFDataGetBytePtr(cfData) ?: error("CFData 没有字节")
    return pointer.readBytes(length)
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
