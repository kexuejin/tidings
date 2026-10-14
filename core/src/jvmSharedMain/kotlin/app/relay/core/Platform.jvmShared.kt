// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * JVM 与 Android **共用**的一份 actual。
 *
 * 这三对文件（`Platform` / `fs/FileIo` / `transport/HostAddress`）原来在
 * `jvmMain/` 与 `androidMain/` 下各留一份，实测差异是：
 * `Platform` 逐字节相同、`HostAddress` 只差一行注释、`FileIo` 只差一个内部类名
 * —— 也就是说第二份是抄出来的，而**抄出来的两份没有任何机制保证它们一起改**。
 * 一旦只改了 `FileIo.jvm.kt` 而忘了 `FileIo.android.kt`，症状是
 * 「桌面端续传正常、安卓上 resume 从 0 开始重新拉」这类只有真机能暴露的问题，
 * 而且两边都能编译通过。
 *
 * 所以这里用中间源集 `jvmShared`（声明在 `core/build.gradle.kts`）：
 * 两端共用一份实现，iOS 那三份仍然是各自的 actual —— 它们真不一样。
 */

actual fun nowMillis(): Long = System.currentTimeMillis()

actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

private class JvmCoreLock : CoreLock {
    private val delegate = java.util.concurrent.locks.ReentrantLock()
    override fun lock() = delegate.lock()
    override fun unlock() = delegate.unlock()
}

actual fun createCoreLock(): CoreLock = JvmCoreLock()