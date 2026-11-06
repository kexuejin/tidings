// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

/**
 * `commonMain` 版的并发容器：普通 [LinkedHashMap] / [LinkedHashSet] 外面套一把
 * [StateLock]。只实现本仓真正用到的那几个操作，不做通用集合库。
 *
 * ## 为什么需要它
 *
 * 这几张表原先直接用 `java.util.concurrent.ConcurrentHashMap`。它在 JVM / Android
 * 上好好的，到了 Kotlin/Native 就是 `Unresolved reference 'java'` —— 而默认验收链
 * 只跑 JVM/Android，所以这类回流会静默积累。`:app` 里已经有 `expect/actual` 的
 * [StateLock]（JVM 是 `ReentrantLock`、iOS 是 `NSRecursiveLock`），这里只是把它
 * 用在原来靠 JDK 容器拿原子性的地方。
 *
 * ## 语义等价，不是「加个锁就行」
 *
 * - [LockedMap.putIfAbsent] 必须返回**旧值**：`AgentCardActionLedger.firstWin` 靠
 *   「返回 null 才算赢」做跨设备首赢，写成 `containsKey` + `set` 两步会有窗口。
 * - [LockedMap.compute] 与原 `ConcurrentHashMap.compute` 同义：回调返回 null 时
 *   删键。`AgentInvokeLeaseTable` 的终态迁移靠它在「同一把锁内读-判-写」。
 * - 遍历一律走**快照**（[LockedMap.entriesSnapshot] / [LockedMap.valuesSnapshot]）：
 *   原 `ConcurrentHashMap` 的迭代器是弱一致的，而 `LinkedHashMap` 一边遍历一边改
 *   会 `ConcurrentModificationException`（本仓真机崩过一次，见 [StateLock] KDoc）。
 * - 可重入是必需的：`compute` 的回调里可能再问同一张表。
 *
 * 这些容器只护**单次操作的原子性**，不提供跨多次操作的复合事务；需要那种语义的
 * 调用方自己取 [locked] 块（与 `AgentInvokeLeaseTable` 的写法一致）。
 */
internal class LockedMap<K : Any, V : Any> {

    private val lock: StateLock = createStateLock()
    private val rows = LinkedHashMap<K, V>()

    operator fun get(key: K): V? = lock.withLock { rows[key] }

    operator fun set(key: K, value: V): V? = lock.withLock { rows.put(key, value) }

    /** 首个写入返回 null；同键后续调用返回旧值（幂等）。 */
    fun putIfAbsent(key: K, value: V): V? = lock.withLock {
        val existing = rows[key]
        if (existing == null) {
            rows[key] = value
            null
        } else {
            existing
        }
    }

    /** 与 `ConcurrentHashMap.compute` 同义：锁内读-算-写，回调返回 null 则删键。 */
    fun compute(key: K, block: (K, V?) -> V?): V? = lock.withLock {
        val next = block(key, rows[key])
        if (next == null) rows.remove(key) else rows[key] = next
        next
    }

    fun remove(key: K): V? = lock.withLock { rows.remove(key) }

    /** 按快照删除，避免遍历时改结构。 */
    fun removeIf(predicate: (Map.Entry<K, V>) -> Boolean) = lock.withLock {
        val doomed = rows.entries.filter(predicate).map { it.key }
        doomed.forEach { rows.remove(it) }
    }

    fun entriesSnapshot(): List<Pair<K, V>> = lock.withLock { rows.entries.map { it.key to it.value } }

    fun valuesSnapshot(): List<V> = lock.withLock { rows.values.toList() }

    /** 复合操作用：在同一把锁内跑整段临界区。 */
    fun <T> locked(block: (LinkedHashMap<K, V>) -> T): T = lock.withLock { block(rows) }
}

/** `ConcurrentHashMap.newKeySet<T>()` 的等价物：[add] 返回「是否真的新增」。 */
internal class LockedSet<T : Any> {

    private val lock: StateLock = createStateLock()
    private val items = LinkedHashSet<T>()

    fun add(item: T): Boolean = lock.withLock { items.add(item) }

    fun contains(item: T): Boolean = lock.withLock { items.contains(item) }
}
