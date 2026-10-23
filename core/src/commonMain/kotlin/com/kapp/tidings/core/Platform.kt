// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core

import kotlinx.coroutines.CoroutineDispatcher

/**
 * 墙钟毫秒时间戳（对应 TS 侧 `Date.now()`）。
 *
 * 刻意不用 `kotlin.time.Clock`：它在 Kotlin 2.4.0 里的稳定状态未核实，而 expect/actual
 * 是各平台都保证可用的写法 —— Discovery / Messenger 后面也要按平台取时间。
 */
expect fun nowMillis(): Long

/**
 * 跑阻塞式 socket IO 的调度器。
 *
 * 为什么需要这个 expect：`Dispatchers.IO` **只在 JVM 上存在**。它在 Kotlin/Native 上是
 * `internal`，写进 `commonMain` 会让 `:core:compileKotlinIosSimulatorArm64` 直接编译失败
 * （已实测：`Cannot access 'val IO: CoroutineDispatcher': it is internal`）。
 * iOS 侧退回 `Dispatchers.Default` —— native 的协程调度器本身就是多线程的，
 * 不像 JVM 需要单独给阻塞调用留一个池。
 */
expect val ioDispatcher: CoroutineDispatcher

/**
 * 保护传输层核心集合（[com.kapp.tidings.core.transport.PeerRegistry]、[com.kapp.tidings.core.transport.Messenger]）的同步锁。
 */
interface CoreLock {
    fun lock()
    fun unlock()
}

expect fun createCoreLock(): CoreLock

inline fun <T> CoreLock.withLock(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}
