// SPDX-License-Identifier: GPL-3.0-only

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.kapp.tidings.platform

import com.kapp.tidings.core.crypto.secureRandomBytes
import com.kapp.tidings.core.protocol.Profile
import com.kapp.tidings.core.transport.DatagramTransport
import com.kapp.tidings.core.transport.DiscoveryEngine
import com.kapp.tidings.core.transport.LuciolaTransportFactory
import com.kapp.tidings.core.transport.PeerRegistry
import com.kapp.tidings.core.transport.ScreenChannelHost
import com.kapp.tidings.core.transport.SecureStateStore
import com.kapp.tidings.domain.LuciolaQrInfo
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanFalse
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlock
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecAttrSynchronizable
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import kotlin.io.encoding.Base64

/**
 * iOS 的 luciola 宿主：把共享层（commonMain）的六个 luciola 组件装起来。
 *
 * ## 与 Android / 桌面同一套模式
 *
 * 三端只差「身份 seed 与信任库存哪」：
 *  - Android: AndroidLuciolaHost，落应用私有 filesDir；
 *  - 桌面: DesktopLuciolaHost，落数据目录的 luciola/；
 *  - iOS（本类）: 落**钥匙串**（独立 service，与 link-identity / 恢复包分条目）。
 *
 * 传输、发现、信任判据、配对帧全在 commonMain，三端共用同一份实现 —— 这正是
 * 「luciola 层整体平台化」那一轮换来的。
 *
 * ## 为什么 seed 进钥匙串而不是文件
 *
 * seed 决定 Ed25519 身份（对端的 pin 就是它的公钥）。iOS 沙盒文件会被备份/迁移策略
 * 以不可预期的方式处理，而钥匙串条目卸载后仍在（与 KeychainSettingsStore 同一取舍）。
 * state.json 里是端点公钥与信任指纹，同属身份材料，一并进钥匙串、分不同 account。
 *
 * 条目**不做 iCloud 同步**（kSecAttrSynchronizable = false）：跨设备同步身份是恢复
 * 身份包那条同步层的职责，这里越权会让两台设备争同一份 seed。
 */
class IosLuciolaHost(
    private val enabled: Boolean = true,
) : LuciolaHost {

    private val lock = IosHostLock()
    private var factory: LuciolaTransportFactory? = null
    private var configuredProfile: Profile? = null

    override fun configure(profile: Profile, udpPort: Int) {
        if (!enabled) return
        lock.withLock {
            if (factory != null && configuredProfile == profile) return
            factory = LuciolaTransportFactory(
                profile = profile,
                udpPort = udpPort,
                seedPersist = { readOrCreateSeedHex() },
                stateStore = object : SecureStateStore {
                    override fun load(): String? = keychainRead(ACCOUNT_STATE)
                    override fun save(value: String) {
                        keychainWrite(ACCOUNT_STATE, value)
                    }
                },
            )
            configuredProfile = profile
            // 启动证据（与桌面端同款）：日志里能看到这行，就能判断信道到底有没有装配。
            println("[luciola] ios host configured port=" + udpPort + " fp=" + (factory?.fingerprint?.take(12) ?: "?"))
        }
    }

    override fun pairingInfo(): LuciolaQrInfo? =
        factory?.let { LuciolaQrInfo.of(it.publicKey, it.pairSecretBytes()) }

    override fun pairPeer(host: String, port: Int, info: LuciolaQrInfo): Boolean {
        val f = factory ?: return false
        // 二维码里是 base64url 无填充（省字节形态），不是标准 base64。
        val publicKey = runCatching { Base64.UrlSafe.decode(info.publicKey) }.getOrNull() ?: return false
        val secret = runCatching { Base64.UrlSafe.decode(info.secret) }.getOrNull() ?: return false
        return runCatching { f.pairPeer(host, port, publicKey, secret = secret) }.isSuccess
    }

    override fun transportFactory(): ((PeerRegistry) -> DatagramTransport)? =
        factory?.let { f -> ({ registry: PeerRegistry -> f.createTransport(registry) }) }

    override fun discoveryFactory(): ((PeerRegistry, () -> Profile) -> DiscoveryEngine)? =
        factory?.let { f ->
            ({ registry: PeerRegistry, profile: () -> Profile -> f.createDiscovery(registry, profile) })
        }

    override fun screenChannelHostFactory(
        scope: CoroutineScope,
        listenPort: Int?,
    ): ((PeerRegistry) -> ScreenChannelHost)? =
        factory?.let { f ->
            ({ registry: PeerRegistry -> f.createScreenChannelHost(registry, scope, listenPort ?: -1) })
        }

    // ------------------------------------------------------------------ seed

    private fun readOrCreateSeedHex(): String {
        keychainRead(ACCOUNT_SEED)?.trim()?.takeIf { it.length == SEED_HEX_CHARS }?.let { return it }
        val hex = secureRandomBytes(SEED_BYTES).toHex()
        if (!keychainWrite(ACCOUNT_SEED, hex)) {
            // 存不住就明说：否则每启动一次就是一台"新设备"，对端会看到无穷多个自己。
            println("[luciola] seed NOT persisted — identity will change on next launch")
        }
        return hex
    }

    private fun ByteArray.toHex(): String {
        val digits = "0123456789abcdef"
        val out = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            out.append(digits[v ushr 4]).append(digits[v and 0x0F])
        }
        return out.toString()
    }

    // ------------------------------------------------------------------ 钥匙串

    private fun keychainRead(account: String): String? =
        keychainReadRaw(account) ?: fallbackRead(account)

    private fun keychainReadRaw(account: String): String? = memScoped {
        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(
            cfDictionary(
                kSecClass to kSecClassGenericPassword,
                kSecAttrService to cfString(SERVICE),
                kSecAttrAccount to cfString(account),
                kSecReturnData to kCFBooleanTrue,
                kSecMatchLimit to kSecMatchLimitOne,
            ),
            result.ptr,
        )
        // 读失败（模拟器上常见 errSecMissingEntitlement=-34018）→ 走回退存储。
        if (status != 0) return@memScoped null
        val data = result.value as? CFDataRef ?: return@memScoped null
        val length = CFDataGetLength(data).toInt()
        val pointer = CFDataGetBytePtr(data) ?: return@memScoped null
        runCatching { pointer.readBytes(length).decodeToString() }.getOrNull()
    }

    /**
     * 写钥匙串，**检查并上报 OSStatus**。
     *
     * 为什么必须查：第一版这里忽略了返回值，结果 SecItemAdd 失败时毫无痕迹 ——
     * 表现是"每次启动 nodeId 都不一样"（模拟器上实测到 09f33d47… → 1d8314c5…）。
     * 身份不持久化不会报错，只会让所有对端把你当成新设备，是最难归因的一类故障。
     */
    private fun keychainWrite(account: String, value: String): Boolean {
        val bytes = value.encodeToByteArray()
        var ok = false
        memScoped {
            // 删后加：整值条目，不值得做差异 update。
            SecItemDelete(
                cfDictionary(
                    kSecClass to kSecClassGenericPassword,
                    kSecAttrService to cfString(SERVICE),
                    kSecAttrAccount to cfString(account),
                ),
            )
            val status = SecItemAdd(
                cfDictionary(
                    kSecClass to kSecClassGenericPassword,
                    kSecAttrService to cfString(SERVICE),
                    kSecAttrAccount to cfString(account),
                    // 本地层：显式关掉 iCloud 同步（见类 KDoc）。
                    kSecAttrSynchronizable to kCFBooleanFalse,
                    kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlock,
                    kSecValueData to bytes.toCFData(),
                ),
                null,
            )
            ok = status == 0
            if (!ok) {
                println("[luciola] keychain unavailable account=" + account + " status=" + status + " -> fallback")
            }
        }
        if (!ok) {
            fallbackWrite(account, value)
            // 回退写成功也算"存住了"：否则日志会说"没持久化"，而身份其实稳定，
            // 排障时会朝完全错误的方向走（模拟器上实测到过这条误导）。
            return fallbackRead(account) == value
        }
        return true
    }

    /**
     * 回退存储：**仅**在钥匙串不可用时使用（典型场景：模拟器上 App 未签名，
     * SecItemAdd 返回 errSecMissingEntitlement=-34018）。
     *
     * 为什么一定要有回退：没有它，每次启动都会生成新 seed ⇒ nodeId 每次变 ⇒
     * 对端看到无穷多个"自己"，配对与信任全部失效（模拟器上实测到
     * 09f33d47… → 1d8314c5… 两分钟内变两次）。身份不持久化不报错，只让所有
     * 对端把你当新设备，是最难归因的一类故障。
     *
     * 强度取舍：NSUserDefaults 没有钥匙串的加密与卸载幸存性，**真机上不要走这条路**
     * （真机有签名、钥匙串可用 ⇒ 正常路径就是钥匙串）。这里只是让模拟器可测。
     */
    private fun fallbackRead(account: String): String? =
        platform.Foundation.NSUserDefaults.standardUserDefaults.stringForKey(FALLBACK_PREFIX + account)

    private fun fallbackWrite(account: String, value: String) {
        platform.Foundation.NSUserDefaults.standardUserDefaults.setObject(value, FALLBACK_PREFIX + account)
    }

    private fun ByteArray.toCFData(): CFDataRef? = usePinned { pinned ->
        CFDataCreate(null, pinned.addressOf(0).reinterpret(), size.toLong())
    }

    private fun cfString(value: String): CFTypeRef? =
        CFStringCreateWithCString(null, value, kCFStringEncodingUTF8)

    private fun cfDictionary(vararg pairs: Pair<CFTypeRef?, CFTypeRef?>): CFMutableDictionaryRef? {
        val dict = CFDictionaryCreateMutable(
            null,
            pairs.size.toLong(),
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        )
        pairs.forEach { (k, v) -> CFDictionaryAddValue(dict, k, v) }
        return dict
    }

    private companion object {
        /** 独立条目：与 link-identity、恢复身份包都不共用，撤销/清理互不牵连。 */
        const val SERVICE = "com.kapp.tidings.luciola"
        const val ACCOUNT_SEED = "ed25519-seed-hex"
        const val ACCOUNT_STATE = "trust-state-json"
        const val SEED_BYTES = 32
        const val SEED_HEX_CHARS = 64
        const val FALLBACK_PREFIX = "luciola.fallback."
    }
}

/** 极小的可重入锁：iOS 侧用 NSRecursiveLock（与 core 的 CoreLock 同语义）。 */
private class IosHostLock {
    private val delegate = platform.Foundation.NSRecursiveLock()
    // inline：configure() 里在锁块内用 `return` 提前退出，非 inline 会报
    // "'return' is prohibited here"。
    inline fun <T> withLock(block: () -> T): T {
        delegate.lock()
        try {
            return block()
        } finally {
            delegate.unlock()
        }
    }
}