// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import app.relay.db.createDatabaseDriver
import app.relay.platform.AppPlatform
import java.io.File
import app.relay.app.model.MessageUi

/**
 * 聊天记录落盘：旧版 JSON 文件存储与向后兼容。
 */
internal class FileMessageStore(private val platform: AppPlatform) : MessageStore {

    private val file: File
        get() = File(platform.dataDirectory.dataDirectory(), "messages.json")

    override fun load(): Map<String, List<MessageUi>> {
        val target = file
        if (!target.isFile) return emptyMap()
        return runCatching {
            MessageStore.json.decodeFromString(
                MessageSnapshot.serializer(),
                target.readText(),
            )
        }.getOrNull()?.threads.orEmpty()
    }

    override fun save(threads: Map<String, List<MessageUi>>) {
        val target = file
        target.parentFile?.mkdirs()
        runCatching {
            target.writeText(
                MessageStore.json.encodeToString(
                    MessageSnapshot.serializer(),
                    MessageSnapshot(threads = threads.mapValues { (_, list) -> list.map { it.toStorable() } }),
                ),
            )
        }
    }

    override fun clear() {
        runCatching { if (file.isFile) file.delete() }
    }
}

actual fun createMessageStore(platform: AppPlatform): MessageStore {
    val driver = createDatabaseDriver(platform)
    val legacy = FileMessageStore(platform)
    return SqlDelightMessageStore(driver, legacyStore = legacy)
}

actual fun createAgentStore(platform: AppPlatform): AgentStore {
    val driver = createDatabaseDriver(platform)
    return SqlDelightAgentStore(driver)
}