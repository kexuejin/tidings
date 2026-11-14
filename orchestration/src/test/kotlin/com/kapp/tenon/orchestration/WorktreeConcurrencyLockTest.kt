// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class WorktreeConcurrencyLockTest {

    @Test
    fun writeLockSerializesExecutionAndReleaseCleansMemory(): Unit = runBlocking {
        val registry = WorktreeLockRegistry()
        val questId = "quest-worktree-1"

        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()

        val job1 = async {
            registry.withExclusiveWriteLock(questId) {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }

        firstEntered.await()
        assertTrue("第一个任务已进入互斥写锁", registry.hasLock(questId))

        val job2 = async {
            registry.withExclusiveWriteLock(questId) {
                secondEntered.complete(Unit)
            }
        }

        assertFalse("第一个任务持有锁时，第二个任务不得进入", secondEntered.isCompleted)

        releaseFirst.complete(Unit)
        job1.await()

        secondEntered.await()
        job2.await()

        assertTrue("第二个任务已正常完成", secondEntered.isCompleted)

        registry.releaseLock(questId)
        assertFalse("调用 releaseLock 后锁句柄必须从内存移除", registry.hasLock(questId))
    }

    @Test
    fun releaseLockDoesNotCorruptQueuedWaitersAndPreventsConcurrentAccess(): Unit = runBlocking {
        val registry = WorktreeLockRegistry()
        val questId = "quest-worktree-concurrency-safe"

        val aEntered = CompletableDeferred<Unit>()
        val aRelease = CompletableDeferred<Unit>()

        val bEntered = CompletableDeferred<Unit>()
        val bRelease = CompletableDeferred<Unit>()

        val cEntered = CompletableDeferred<Unit>()

        val activeCount = AtomicInteger(0)
        var maxConcurrent = 0

        val jobA = async {
            registry.withExclusiveWriteLock(questId) {
                val current = activeCount.incrementAndGet()
                if (current > maxConcurrent) maxConcurrent = current
                aEntered.complete(Unit)
                aRelease.await()
                activeCount.decrementAndGet()
            }
        }

        aEntered.await()

        val jobB = async {
            registry.withExclusiveWriteLock(questId) {
                val current = activeCount.incrementAndGet()
                if (current > maxConcurrent) maxConcurrent = current
                bEntered.complete(Unit)
                bRelease.await()
                activeCount.decrementAndGet()
            }
        }

        // 此时 A 持锁，B 在队列中排队。尝试在此时调用 releaseLock
        registry.releaseLock(questId)
        // 关键断言：因为 B 还在排队，releaseLock 绝对不能把锁误删导致后续任务并发持锁
        assertTrue("等待队列非空时，releaseLock 必须安全保留锁", registry.hasLock(questId))

        // 释放 A，B 应该拿到锁
        aRelease.complete(Unit)
        jobA.await()

        bEntered.await()

        // 启动任务 C
        val jobC = async {
            registry.withExclusiveWriteLock(questId) {
                val current = activeCount.incrementAndGet()
                if (current > maxConcurrent) maxConcurrent = current
                cEntered.complete(Unit)
                activeCount.decrementAndGet()
            }
        }

        // 当 B 还在执行时，C 绝不能并发进入
        assertFalse("B 在持锁执行时，C 绝不能并发进入", cEntered.isCompleted)

        bRelease.complete(Unit)
        jobB.await()

        cEntered.await()
        jobC.await()

        assertEquals("全程最大并发持有写锁数必须恒等于 1", 1, maxConcurrent)

        // 全部完成后再次调用 releaseLock
        registry.releaseLock(questId)
        assertFalse("无活跃等待者后，releaseLock 成功清理内存", registry.hasLock(questId))
    }

    @Test
    fun readOnlyInspectionWaitsForExclusiveWriteLockWhenHeld(): Unit = runBlocking {
        val registry = WorktreeLockRegistry()
        val questId = "quest-worktree-readonly-wait"

        val writeEntered = CompletableDeferred<Unit>()
        val writeRelease = CompletableDeferred<Unit>()
        val readEntered = CompletableDeferred<Unit>()

        val writeJob = async {
            registry.withExclusiveWriteLock(questId) {
                writeEntered.complete(Unit)
                writeRelease.await()
            }
        }

        writeEntered.await()
        assertTrue("写锁已被持有", registry.hasLock(questId))

        val readJob = async {
            registry.runReadOnlyInspection(questId) {
                readEntered.complete(Unit)
                "read-success"
            }
        }

        // 写锁未释放时，只读检查必须等待，不得并发进入读到脏数据
        assertFalse("写锁持有期间，只读检查不得执行", readEntered.isCompleted)

        writeRelease.complete(Unit)
        writeJob.await()

        val readResult = readJob.await()
        assertTrue("写锁释放后，只读检查顺利完成", readEntered.isCompleted)
        assertEquals("read-success", readResult)
    }

    @Test
    fun readOnlyInspectionExcludesConcurrentWriterEvenWhenReaderEnteredFirst(): Unit = runBlocking {
        // 反向验证钉子（§4）：旧实现的读者「先查 isLocked 再决定加不加锁」——
        // 读者在注册表还没有条目时进入 action（无锁跑），写者此刻注册并持锁写入，
        // 两者重叠读到/写到半成品（TOCTOU）。本测试用确定性时序复现那个窗口：
        // 读者先进入 action，写者随后启动 —— 新实现读者持同一把互斥，写者必须等。
        val registry = WorktreeLockRegistry()
        val questId = "quest-registry-toctou-1"

        val readerEntered = CompletableDeferred<Unit>()
        val readerRelease = CompletableDeferred<Unit>()
        val writerEntered = CompletableDeferred<Unit>()

        val readerJob = async {
            registry.runReadOnlyInspection(questId) {
                readerEntered.complete(Unit)
                readerRelease.await()
                "read-done"
            }
        }
        readerEntered.await()

        val writerJob = async {
            registry.withExclusiveWriteLock(questId) {
                writerEntered.complete(Unit)
            }
        }

        // runBlocking 是单线程事件循环：不 yield 的话 writer 根本没被调度，
        // 断言会空过。yield 之后：新实现 writer 挂在互斥上（读者持锁）；
        // 旧实现（读者无锁跑）writer 立即进入 —— 断言变红，这正是反向验证。
        kotlinx.coroutines.yield()
        kotlinx.coroutines.yield()

        // 读者还在读：写者绝不能并发进入（旧实现此断言会红 —— 读者无锁，写者直接进）
        assertFalse("读者执行期间，写者不得并发进入同一支线", writerEntered.isCompleted)

        readerRelease.complete(Unit)
        assertEquals("read-done", readerJob.await())
        writerJob.await()
        assertTrue("读者退出后写者才被放行", writerEntered.isCompleted)
    }
}
