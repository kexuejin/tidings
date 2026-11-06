// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.testsupport.GuardSourceScan
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 阻塞锁的**使用方式**守卫：除实现与包装层之外，生产源码只许用 `StateLock.withLock`，
 * 不许裸调 `lock()` / `unlock()`。
 *
 * ## 为什么需要它
 *
 * [StateLock] 是一把**跨线程阻塞锁**，它的正确性依赖每一对 lock/unlock 都成对执行 ——
 * 中途抛出异常就漏解锁，而漏解锁的症状不是崩溃，是**整条链路静默卡死**（下一次取锁
 * 永远等下去），比 `ConcurrentModificationException` 难查得多。`withLock` 用 try/finally
 * 把这件事变成结构性的；裸调用则把它交回给人。
 *
 * 编译器对它无能为力：`lock()` 就在接口上，任何一个分片都能直接调。所以只能在源码层守住。
 *
 * 允许名单只有四个理由明确的文件：接口与包装（`StateLock.kt`）、两个平台实现
 * （`StateLock.jvmShared.kt` / `StateLock.ios.kt`）、以及 `SerializedAccessDriver`
 * —— 后者要在 SQLDelight 的驱动层包住同步查询/事务，必须把 lock/unlock 挂在事务的
 * afterCommit/afterRollback 上（那里没有 withLock 能覆盖的形状，见该文件的 KDoc）。
 *
 * 反向验证：在任一分片里写一处 `host.stateLock.lock()`，本用例变红。
 */
class StateLockUsageGuardTest {

    private val domainPrefix = "app/src/commonMain/kotlin/com/kapp/tenon/"

    private val allowed = setOf(
        "domain/StateLock.kt",
        "domain/StateLock.jvmShared.kt",
        "domain/StateLock.ios.kt",
        "db/SerializedAccessDriver.kt",
    )

    /** 裸调用的两种形态：`x.lock()` / `x.unlock()`（不是 `withLock`）。 */
    private val rawCall = Regex("""\.(lock|unlock)\(\)""")

    @Test
    fun blockingLockIsOnlyUsedThroughWithLock() {
        val root = File(GuardSourceScan.scaffoldRoot(), domainPrefix)
        assertTrue(root.isDirectory, "扫描根不存在：$domainPrefix（缺输入 = 没验到，不能当通过）")
        val offenders = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file -> allowed.none { file.relativeTo(root).path.endsWith(it) } }
            .forEach { file ->
                val source = GuardSourceScan.stripComments(file.readText())
                for (match in rawCall.findAll(source)) {
                    val line = source.take(match.range.first).count { it == '\n' } + 1
                    offenders += file.relativeTo(root).path + ":" + line + " " + match.value
                }
            }
        assertTrue(
            offenders.isEmpty(),
            "生产源码不许裸调 StateLock.lock()/unlock()（异常路径会漏解锁，症状是链路静默卡死）。" +
                "改用 withLock，或把该处加进本守卫的允许名单并写清理由。命中：" + offenders.joinToString(),
        )
    }
}
