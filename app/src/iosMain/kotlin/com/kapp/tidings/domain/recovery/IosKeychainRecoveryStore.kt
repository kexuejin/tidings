// SPDX-License-Identifier: GPL-3.0-only

@file:OptIn(ExperimentalForeignApi::class)

package com.kapp.tidings.domain.recovery

import com.kapp.tidings.core.persist.RecoveryBundle
import com.kapp.tidings.core.persist.RecoveryIdentityPayload
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecItemNotFound
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

/**
 * iOS 的 L0 存活存储：身份包（nodeId + 私钥标量 + SPKI + pin 摘要）进**钥匙串**。
 *
 * 与传输身份（`com.kapp.tidings.link.identity`，core 的 SecItem 那一层）**分两个条目**：
 * 那一层管 TLS 身份本身且随证书重签频繁重写；这一层存恢复语义的整包，只在身份
 * 稳定时刷新。分开还避免了两边对 `kSecAttrSynchronizable` 的口径打架（传输身份
 * 保持本机语义，恢复包走同步）。
 *
 * 条目属性（方案 §6.1 / I3）：`kSecAttrSynchronizable = true` —— iCloud 钥匙串的
 * 同步层，卸载重装随本地层（I1）与同步层（I2）两条路都能回来；
 * accessibility 用 `AfterFirstUnlock`（同步项不能用 `ThisDeviceOnly` 类）。
 * **收养决策不在这里**：settings 存在时调用方绝不 load（Q10），见
 * `RelayController` 的 `_state` 初始化。
 */
@OptIn(ExperimentalForeignApi::class)
class IosKeychainRecoveryStore : PlatformRecoveryStore {

    private var lastDiagnosis: String? = null

    override val readDiagnosis: String? get() = lastDiagnosis

    override fun load(): RecoveryIdentityPayload? = memScoped {
        lastDiagnosis = null
        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(
            cfDictionary(
                kSecClass to kSecClassGenericPassword,
                kSecAttrService to cfString(SERVICE),
                kSecAttrAccount to cfString(ACCOUNT),
                kSecReturnData to kCFBooleanTrue,
                kSecMatchLimit to kSecMatchLimitOne,
            ),
            result.ptr,
        )
        if (status != 0) {
            lastDiagnosis = if (status.toInt() == errSecItemNotFound) {
                null
            } else {
                "keychain read failed: $status"
            }
            return@memScoped null
        }
        val data = result.value as? CFDataRef ?: run {
            lastDiagnosis = "keychain item is not CFData"
            return@memScoped null
        }
        val bytes = exportBytes(data)
        try {
            RecoveryBundle.decodeIdentityPayload(bytes)
        } catch (e: IllegalArgumentException) {
            lastDiagnosis = "keychain bundle rejected: ${e.message}"
            null
        }
    }

    override fun save(payload: RecoveryIdentityPayload) {
        val bytes = RecoveryBundle.encodeIdentityPayload(payload)
        memScoped {
            // 先删后加：条目内容随身份/pins 变化，update 语义用删+加最省事（条目 KB 级）。
            SecItemDelete(cfDictionary(kSecClass to kSecClassGenericPassword, kSecAttrService to cfString(SERVICE), kSecAttrAccount to cfString(ACCOUNT)))
            val status = SecItemAdd(
                cfDictionary(
                    kSecClass to kSecClassGenericPassword,
                    kSecAttrService to cfString(SERVICE),
                    kSecAttrAccount to cfString(ACCOUNT),
                    kSecAttrSynchronizable to kCFBooleanTrue,
                    kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlock,
                    kSecValueData to bytes.toCFData(),
                ),
                null,
            )
            if (status != 0) {
                lastDiagnosis = "keychain write failed: $status"
            }
        }
    }

    private fun exportBytes(data: CFDataRef): ByteArray {
        val length = CFDataGetLength(data).toInt()
        val pointer = CFDataGetBytePtr(data) ?: return ByteArray(0)
        return pointer.readBytes(length)
    }

    companion object {
        /** 钥匙串条目的 service/account：与传输身份的 application tag 区分开（见类 KDoc）。 */
        private const val SERVICE = "com.kapp.tidings.recovery"
        private const val ACCOUNT = "identity-bundle"
    }
}

private fun cfString(text: String): CFTypeRef? = memScoped {
    CFStringCreateWithCString(null, text, kCFStringEncodingUTF8)
}

private fun ByteArray.toCFData(): CFDataRef? = usePinned { pinned ->
    CFDataCreate(null, pinned.addressOf(0).reinterpret(), size.toLong())
}

private fun cfDictionary(vararg pairs: Pair<CFTypeRef?, CFTypeRef?>): CFMutableDictionaryRef? {
    val dict = CFDictionaryCreateMutable(null, pairs.size.toLong(), kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
    if (dict != null) {
        for ((key, value) in pairs) CFDictionaryAddValue(dict, key, value)
    }
    return dict
}
