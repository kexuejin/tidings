// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

actual fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

/** Kotlin/Native 没有 `Dispatchers.IO`；native 的 Default 本身就是多线程调度器。 */
actual val ioDispatcher: CoroutineDispatcher = Dispatchers.Default

private class IosCoreLock : CoreLock {
    private val delegate = platform.Foundation.NSRecursiveLock()
    override fun lock() = delegate.lock()
    override fun unlock() = delegate.unlock()
}

actual fun createCoreLock(): CoreLock = IosCoreLock()

