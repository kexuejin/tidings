// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * L2 待拉集合的**并发形状**守卫：它必须是 `MutableStateFlow<Set<String>>` 上的原子读改写，
 * 不许再借 [RelayCoreState] 那把阻塞锁，也不许在持锁块里落盘。
 *
 * ## 为什么换它（这次替换的依据）
 *
 * 这份集合与消息表/传输表毫无关系 —— 它借 `stateLock` 只是历史巧合：
 *  - 借锁的两处还在**持锁期间写文件**（`RecoveryPendingPeers.save`），违反本仓既有的
 *    「持锁期间不得挂起 / 不做 IO」约束（见 [StateLock] 的 KDoc）；
 *  - 阻塞锁会把一件纯内存的集合更新绑到最重的那把锁上（UI 线程的 `selectConversation`
 *    与入站处理都要等它），而这份集合的读者是启动路径与 drain 协程，根本不需要阻塞语义。
 *
 * 换成不可变集合 + `update { }` 之后：更新是原子的、读是快照、落盘在锁外。
 *
 * ## 为什么这条只能用守卫钉
 *
 * 行为用例区分不出「借锁」与「StateFlow」—— 两种写法都对。能区分的是**源码形态**：
 * 文件里还找不找得到 `stateLock`、`RecoveryPendingPeers.save` 还在不在锁块里。
 * 反向验证：往 drain/mark 里塞一句 `stateLock.withLock { RecoveryPendingPeers.save(...) }`，
 * 本用例变红（可编译，见提交说明）。
 */
class RecoveryPendingStateGuardTest {

    private val recoveryPath = "app/src/commonMain/kotlin/com/kapp/tenon/domain/RelayControllerRecoveryComponent.kt"
    private val controllerPath = "app/src/commonMain/kotlin/com/kapp/tenon/domain/RelayController.kt"

    @Test
    fun pendingSetNoLongerBorrowsTheCoreLock() {
        val source = live(recoveryPath)
        // 只钉「读改写这份集合」的两个入口；同文件的 mergeRecoveredThreads 仍然合法地借
        // 核心表锁（它改的是 threads 表，那是另一件事）。
        for (marker in listOf("fun RelayController.drainRecoveryPull(", "fun RelayController.markRecoveryPullPending(")) {
            val body = declaration(source, marker)
            assertTrue(
                !body.contains("stateLock") && !body.contains("withLock"),
                "L2 待拉集合已改成 MutableStateFlow<Set> 的原子读改写，`$marker` 不许再借 RelayCoreState 的阻塞锁：" +
                    "重新出现 stateLock 等于把纯内存集合更新绑回最重的锁上，还会把落盘拖回临界区",
            )
        }
    }

    /** 从声明处切到它的结束大括号（顶层扩展函数的 `\n}\n`）。 */
    private fun declaration(source: String, marker: String): String {
        val start = source.indexOf(marker)
        assertTrue(start >= 0, "源码里找不到入口：$marker")
        val end = source.indexOf("\n}\n", start)
        return if (end < 0) source.substring(start) else source.substring(start, end)
    }

    @Test
    fun pendingSetIsAtomicStateFlow() {
        val source = live(controllerPath)
        assertTrue(
            source.contains("pendingRecoveryPeers: MutableStateFlow<Set<String>>"),
            "pendingRecoveryPeers 必须声明为 MutableStateFlow<Set<String>>（不可变集合上的原子读改写）",
        )
    }

    @Test
    fun noPersistenceInsideAnyLockBlock() {
        for (path in listOf(recoveryPath, controllerPath)) {
            val source = live(path)
            for (block in lockBlocks(source)) {
                assertTrue(
                    !block.contains("RecoveryPendingPeers.save"),
                    "持锁块里出现 RecoveryPendingPeers.save：落盘必须移到锁外（持锁期间不做 IO 是既有约束，" +
                        "真机上它会让 UI 线程与入站处理一起等一次文件写）",
                )
            }
        }
    }

    /** 取出源码里每一个 `stateLock.withLock { ... }` 的平衡大括号块体。 */
    private fun lockBlocks(source: String): List<String> {
        val blocks = mutableListOf<String>()
        var from = 0
        while (true) {
            val at = source.indexOf("withLock", from)
            if (at < 0) break
            val open = source.indexOf('{', at)
            if (open < 0) break
            val close = matchingBrace(source, open)
            if (close < 0) break
            blocks += source.substring(open + 1, close)
            from = close + 1
        }
        return blocks
    }

    private fun matchingBrace(source: String, open: Int): Int {
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    private fun live(relativePath: String): String =
        GuardSourceScan.stripComments(GuardSourceScan.read(relativePath))
}
