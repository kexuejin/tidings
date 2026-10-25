// SPDX-License-Identifier: GPL-3.0-only

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.kapp.tidings.domain

import com.kapp.tidings.platform.AppPlatform
import kotlinx.cinterop.ExperimentalForeignApi
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
import platform.Foundation.NSUserDefaults
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

/**
 * iOS 设置落盘：**钥匙串**（本地层）。
 *
 * 演进（身份恢复方案 §6.1 前置）：最早用 `NSUserDefaults`，它随卸载被清 —— 里面含
 * `nodeId`（协议身份），卸载重装后丢失。钥匙串项卸载后幸存（I1），所以这里把设置
 * 整体搬进钥匙串：重装后 nick / 通讯录 / 群都不丢，配合 `link-identity` 的钥匙串
 * 回退（P1），iOS 上卸载重装 = 零操作完整回来，不依赖 iCloud。
 *
 * 条目属性：**不做 iCloud 同步**（`kSecAttrSynchronizable` 显式 false）。跨设备同步
 * 身份是恢复身份包（`IosKeychainRecoveryStore`）那条同步层的职责，且收养决策（Q10）
 * 在控制器里按「settings 是否存在」裁决 —— 设置若随 iCloud 漫游，第二台设备会被
 * 当成「已有身份」整体收下全部数据，绕过那条裁决。本机幸存（I1）不需要同步位。
 *
 * 一次性迁移：钥匙串里还没有而 `NSUserDefaults` 里有（老安装升级上来）⇒ 搬进钥匙串
 * 后删掉旧键 —— 单一真身，不留会分叉的第二份。
 */
internal class KeychainSettingsStore : SettingsStore {

    private val defaults = NSUserDefaults.standardUserDefaults
    private val legacyKey = "relay.settings.json"

    override fun load(): RelaySettings? {
        val raw = readKeychain() ?: migrateFromUserDefaults() ?: return null
        return runCatching {
            SettingsStore.json.decodeFromString(RelaySettings.serializer(), raw)
        }.getOrNull()
    }

    override fun save(settings: RelaySettings) {
        writeKeychain(SettingsStore.json.encodeToString(RelaySettings.serializer(), settings))
    }

    /** 钥匙串里没有且旧键里有 ⇒ 搬家并删旧键；返回搬过去的内容（没有可搬则 null）。 */
    private fun migrateFromUserDefaults(): String? {
        val legacy = defaults.stringForKey(legacyKey) ?: return null
        writeKeychain(legacy)
        defaults.removeObjectForKey(legacyKey)
        return legacy
    }

    private fun readKeychain(): String? = memScoped {
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
        if (status != 0) return@memScoped null
        val data = result.value as? CFDataRef ?: return@memScoped null
        val length = CFDataGetLength(data).toInt()
        val pointer = CFDataGetBytePtr(data) ?: return@memScoped null
        runCatching { pointer.readBytes(length).decodeToString() }.getOrNull()
    }

    private fun writeKeychain(json: String) {
        val bytes = json.encodeToByteArray()
        memScoped {
            // 删后加（内容整体变化的整值条目，不值得做差异 update）。
            SecItemDelete(cfDictionary(kSecClass to kSecClassGenericPassword, kSecAttrService to cfString(SERVICE), kSecAttrAccount to cfString(ACCOUNT)))
            SecItemAdd(
                cfDictionary(
                    kSecClass to kSecClassGenericPassword,
                    kSecAttrService to cfString(SERVICE),
                    kSecAttrAccount to cfString(ACCOUNT),
                    // 显式本地层（见类 KDoc：同步层的职责在恢复身份包那边）。
                    kSecAttrSynchronizable to kCFBooleanFalse,
                    kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlock,
                    kSecValueData to bytes.toCFData(),
                ),
                null,
            )
        }
    }

    private fun ByteArray.toCFData(): CFDataRef? = usePinned { pinned ->
        CFDataCreate(null, pinned.addressOf(0).reinterpret(), size.toLong())
    }

    companion object {
        /** 与恢复身份包（com.kapp.tidings.recovery）分开的独立条目。 */
        private const val SERVICE = "com.kapp.tidings.settings"
        private const val ACCOUNT = "settings.json"
    }
}

private fun cfString(text: String): CFTypeRef? = memScoped {
    CFStringCreateWithCString(null, text, kCFStringEncodingUTF8)
}

private fun cfDictionary(vararg pairs: Pair<CFTypeRef?, CFTypeRef?>): CFMutableDictionaryRef? {
    val dict = CFDictionaryCreateMutable(null, pairs.size.toLong(), kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
    if (dict != null) {
        for ((key, value) in pairs) CFDictionaryAddValue(dict, key, value)
    }
    return dict
}

actual fun createSettingsStore(platform: AppPlatform): SettingsStore = KeychainSettingsStore()
