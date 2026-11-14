// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * 单 Worktree（按 questId 寻址）读写调度器。
 *
 * 互斥保证（以实现为准，不做弱承诺）：
 * - **同一支线**：写与写、写与读、读与读全部经同一把互斥串行 —— 「注册」与「持锁」
 *   之间没有任何检查窗口，读写互斥不含 TOCTOU（此前的「先查 isLocked 再决定要不要
 *   加锁」正是那个窗口：写者刚注册还没持锁时读者直接放行，读到半成品）。
 * - **跨支线**：不同 questId 是不同的锁句柄，完全并发。
 *
 * 同一支线的读读串行是**有意付出的代价**：读是快速检查（status/diff/文件查看），
 * 串行的吞吐损失可忽略，换来的是「读到的永远是合流/写操作前后的完整状态」。
 *
 * 引用计数与锁状态校验（Ref-counted Mutex）：当且仅当无等待者且无持锁者时才回收
 * 锁句柄，杜绝在有等待排队时因提前 releaseLock 导致创建新 Mutex 引发并发写竞争。
 */
class WorktreeLockRegistry {

    private class LockHolder {
        val mutex = Mutex()
        var refCount: Int = 0
    }

    private val lockMap = ConcurrentHashMap<String, LockHolder>()
    private val mapMutex = Mutex()

    /** 执行涉及磁盘写入、代码修改或 git 命令的 Worker（同支线串行排队）。 */
    suspend fun <T> withExclusiveWriteLock(questId: String, action: suspend () -> T): T =
        withQuestLock(questId, action)

    /**
     * 只读检查：与写走**同一把**互斥（注册即持锁，无检查窗口）—— 写锁持有期间读等待，
     * 释放后读执行；跨支线的读完全并发。
     */
    suspend fun <T> runReadOnlyInspection(questId: String, action: suspend () -> T): T =
        withQuestLock(questId, action)

    private suspend fun <T> withQuestLock(questId: String, action: suspend () -> T): T {
        val holder = mapMutex.withLock {
            val current = lockMap.computeIfAbsent(questId) { LockHolder() }
            current.refCount++
            current
        }

        try {
            return holder.mutex.withLock { action() }
        } finally {
            mapMutex.withLock {
                holder.refCount--
                if (holder.refCount == 0 && !holder.mutex.isLocked) {
                    lockMap.remove(questId, holder)
                }
            }
        }
    }

    /** 安全回收锁句柄：若仍有等待者或被持有，则不予删除，保障并发安全。 */
    suspend fun releaseLock(questId: String) {
        mapMutex.withLock {
            val holder = lockMap[questId]
            if (holder != null && holder.refCount == 0 && !holder.mutex.isLocked) {
                lockMap.remove(questId, holder)
            }
        }
    }

    fun hasLock(questId: String): Boolean = lockMap.containsKey(questId)
}
