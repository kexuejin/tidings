// SPDX-License-Identifier: GPL-3.0-only

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.relay.app

import app.relay.core.fs.pathJoin
import app.relay.platform.AppPlatform
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSFileManager
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.fwrite
import platform.posix.ftell
import app.relay.app.model.MessageUi

/**
 * 聊天记录文件名。与 `MessageStore.android.kt` / `MessageStore.jvm.kt` 里的
 * `File(platform.dataDirectory.dataDirectory(), "messages.json")` 同一个名字，
 * 三端落在同一个相对位置。
 */
private const val MESSAGES_FILE = "messages.json"

/**
 * iOS 聊天记录落盘：`Application Support/relay/messages.json`。
 *
 * 目录**不自己算**，走 [AppPlatform.dataDirectory] —— 它是全 App 唯一的容器内数据根：
 * 收到的文件落在 `<root>/downloads`（`RelayController` 的 `downloadDir`），聊天记录落在
 * `<root>/messages.json`。以前这里自己算 `Application Support/relay`，而
 * `IosAppPlatform.dataDirectory` 返回的是占位串 `"NSUserDefaults://app.relay"` ——
 * 同一个「数据目录」概念在两处各有一份值，一份对、一份错，谁也不知道以哪个为准。
 * 现在两处都由 `IosPlatform.containerDirectory` 现算（见 `IosPlatform.kt` 的该函数），
 * 路径只有一处定义。
 *
 * **不**用 `SettingsStore.ios.kt` 那套 `NSUserDefaults`：设置是一小块、永远读得起的
 * 偏好，聊天记录是随时间线性增长的整段历史，塞进偏好存储既不合尺寸（系统会为它做同步与备份）
 * 也迟早撑不住。文件才是这个量级该待的地方。
 *
 * 路径**每次现取**而不是构造时缓存：iOS 会在系统升级或容量不足时搬动整个容器，
 * 缓存下来的绝对路径会指向一个已经不存在的地方，症状是"重启后历史全没了"，
 * 而且极难定位。Application Support 同时享受 iCloud 备份 —— 换手机时聊天记录跟着走。
 *
 * 读写走 POSIX `stdio` 而不是 `NSData`：Foundation 那层在 Kotlin/Native 里
 * 是 `NSDataMeta` 的扩展方法，`NSURL.dataWithContentsOfFile()` 的接收者对不上，
 * `toNSData()` 又不是可导入的顶层函数（Kotlin 2.2.21 实测）。POSIX 稳定、
 * 无 Objective-C 桥接开销，语义也更好推理（读全、一次写完）。
 */
internal class IosMessageStore(
    /** 数据根目录。是函数不是值 —— 每次落盘都重新问一次，见类文档的"路径每次现取"。 */
    private val dataDirectory: () -> String,
) : MessageStore {

    override fun load(): Map<String, List<MessageUi>> {
        val file = existingFile() ?: return emptyMap()
        return runCatching {
            val raw = readFile(file) ?: return emptyMap()
            MessageStore.json.decodeFromString(
                MessageSnapshot.serializer(),
                raw.decodeToString(),
            )
        }.getOrNull()?.threads.orEmpty()
    }

    override fun save(threads: Map<String, List<MessageUi>>) {
        val file = dataFile()
        runCatching {
            val payload = MessageStore.json.encodeToString(
                MessageSnapshot.serializer(),
                MessageSnapshot(threads = threads.mapValues { (_, list) -> list.map { it.toStorable() } }),
            )
            writeFile(file, payload.encodeToByteArray())
        }
    }

    override fun clear() {
        runCatching {
            existingFile()?.let { NSFileManager.defaultManager.removeItemAtPath(it, null) }
        }
    }

    /**
     * 目标文件路径。目录由 [AppPlatform.dataDirectory] 在给出路径时建出来，
     * 这里不再自己建（原先那个 `createDirectory` 开关也随之消失）。
     */
    private fun dataFile(): String = pathJoin(dataDirectory(), MESSAGES_FILE)

    /** 只读路径也要先问目录：容器被搬动之后，缓存的旧路径必须作废。 */
    private fun existingFile(): String? =
        dataFile().takeIf { NSFileManager.defaultManager.fileExistsAtPath(it) }

    /** 整文件读成字节。读不到（不存在、被截断成 0 字节、权限不足）一律返回 null，让调用方当作没有历史。 */
    private fun readFile(path: String): ByteArray? {
        val handle = fopen(path, "rb") ?: return null
        return try {
            if (fseek(handle, 0, SEEK_END) != 0) return null
            val size = ftell(handle)
            when {
                size < 0L -> null
                size == 0L -> ByteArray(0)
                else -> {
                    if (fseek(handle, 0, SEEK_SET) != 0) return null
                    val bytes = ByteArray(size.toInt())
                    val count: ULong = bytes.size.convert()
                    bytes.usePinned { pinned ->
                        if (fread(pinned.addressOf(0), 1uL, count, handle) != count) {
                            return null
                        }
                    }
                    bytes
                }
            }
        } finally {
            fclose(handle)
        }
    }

    /** 整个字节数组一次写完。写不满视为失败；失败只是不落盘，与 Android/JVM 侧的静默降级一致。 */
    private fun writeFile(path: String, bytes: ByteArray): Boolean {
        val handle = fopen(path, "wb") ?: return false
        return try {
            if (bytes.isEmpty()) {
                // usePinned 对空数组没有可用的地址，先开后关照样得到一个空文件。
                true
            } else {
                bytes.usePinned { pinned ->
                    val count: ULong = bytes.size.convert()
                    fwrite(pinned.addressOf(0), 1uL, count, handle) == count
                }
            }
        } finally {
            fclose(handle)
        }
    }
}

/**
 * iOS 的 [MessageStore]。目录由平台层提供：`IosAppPlatform.dataDirectory` 返回
 * 容器内 `Application Support/relay` 并在给出路径时建好目录（与 Android/JVM 端
 * `MessageStore.android.kt` 拿 `platform.dataDirectory` 的用法一致）。
 */
actual fun createMessageStore(platform: AppPlatform): MessageStore {
    val driver = app.relay.db.createDatabaseDriver(platform)
    val legacy = IosMessageStore { platform.dataDirectory.dataDirectory() }
    return SqlDelightMessageStore(driver, legacyStore = legacy)
}

actual fun createAgentStore(platform: AppPlatform): AgentStore {
    val driver = app.relay.db.createDatabaseDriver(platform)
    return SqlDelightAgentStore(driver)
}