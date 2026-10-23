// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.platform.AppPlatform
import java.io.File

/**
 * 设置落盘：应用私有目录下的 `settings.json`。
 *
 * 写在私有目录而不是外部存储，是因为里面含 `nodeId`（协议身份）。用户**不需要**
 * 知道这个文件的存在 —— 所有字段都在设置页有录入界面，这正是"凭据/配置必须有界面"
 * 那条约束的落点。
 */
internal class FileSettingsStore(private val platform: AppPlatform) : SettingsStore {

    private val file: File
        get() = File(platform.dataDirectory.dataDirectory(), "settings.json")

    override fun load(): RelaySettings? {
        val target = file
        if (!target.isFile) return null
        return runCatching {
            SettingsStore.json.decodeFromString(RelaySettings.serializer(), target.readText())
        }.getOrNull()
    }

    override fun save(settings: RelaySettings) {
        val target = file
        target.parentFile?.mkdirs()
        runCatching {
            target.writeText(SettingsStore.json.encodeToString(RelaySettings.serializer(), settings))
        }
    }
}

actual fun createSettingsStore(platform: AppPlatform): SettingsStore = FileSettingsStore(platform)
