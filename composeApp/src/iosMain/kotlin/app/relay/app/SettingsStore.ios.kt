// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import app.relay.platform.AppPlatform
import platform.Foundation.NSUserDefaults

/**
 * iOS 设置落盘走 `NSUserDefaults`。
 *
 * 不用文件是因为 iOS 的容器路径会随安装/升级变化，而 `NSUserDefaults` 由系统
 * 绑定到 bundle id，升级后仍在。里面含 `nodeId`（协议身份），不能丢 ——
 * 丢了对方看到的就是一个新节点。
 */
internal class UserDefaultsSettingsStore : SettingsStore {

    private val defaults = NSUserDefaults.standardUserDefaults
    private val key = "relay.settings.json"

    override fun load(): RelaySettings? {
        val raw = defaults.stringForKey(key) ?: return null
        return runCatching {
            SettingsStore.json.decodeFromString(RelaySettings.serializer(), raw)
        }.getOrNull()
    }

    override fun save(settings: RelaySettings) {
        runCatching {
            defaults.setObject(
                SettingsStore.json.encodeToString(RelaySettings.serializer(), settings),
                key,
            )
        }
    }
}

actual fun createSettingsStore(platform: AppPlatform): SettingsStore = UserDefaultsSettingsStore()
