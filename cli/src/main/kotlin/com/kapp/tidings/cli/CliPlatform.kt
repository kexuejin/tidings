// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.cli

import com.kapp.tidings.platform.AppPlatform
import com.kapp.tidings.platform.DataDirectoryProvider
import com.kapp.tidings.platform.DeviceInfo
import com.kapp.tidings.platform.InMemoryAppPlatform
import java.io.File

/**
 * `:cli` 的平台实现：**真落盘**，其余能力一律走"这台设备没有"。
 *
 * ## 为什么继承 `InMemoryAppPlatform` 而不是从零实现 `AppPlatform`
 *
 * `AppPlatform` 有 20 多个成员，其中绝大多数（选图、相机、mdns、推送、投屏、状态栏）
 * 对一个命令行程序**在语义上就是不存在的**。`InMemoryAppPlatform` 已经把那些槽填成
 * `Unavailable*` 的诚实实现 —— 那正是这里要的答案，不是"暂时没写"。
 *
 * 只覆盖两件真正需要不同的事：
 * 1. **数据目录**：`InMemory` 指向 `/tmp/relay-preview`（预览夹具），这里要真目录；
 * 2. **设备名**：`preview-device` 会让局域网里出现一台叫这个名字的陌生设备。
 *
 * 这也是"领域层零 UI"这条边界的一次实测：`AppPlatform` 抽象到可以被一个
 * 没有窗口、没有相机、没有托盘的程序实现。
 */
internal class CliPlatform(
    private val rootDirectory: String,
) : AppPlatform by InMemoryAppPlatform(
    dataDirectory = object : DataDirectoryProvider {
        override fun dataDirectory(): String = rootDirectory
        override fun cacheDirectory(): String = rootDirectory + "/cache"
    },
    deviceInfo = object : DeviceInfo {
        override fun hostName(): String =
            "relay-cli-" + (System.getenv("HOSTNAME") ?: "host")
    },
) {
    init {
        java.io.File(rootDirectory).mkdirs()
        java.io.File(rootDirectory + "/cache").mkdirs()
    }
}
