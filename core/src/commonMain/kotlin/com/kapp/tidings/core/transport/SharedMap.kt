// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

import com.kapp.tidings.core.createCoreLock
import com.kapp.tidings.core.withLock

/**
 * 跨平台的线程安全映射（commonMain 可用）。
 *
 * 为什么需要它：JVM 的 `ConcurrentHashMap` 在 Kotlin/Native 不存在，而 luciola 传输层
 * 有三张表（外拨会话、待配对、按端点归属的入站会话）在 FFI 回调线程与协程之间共享。
 * 把它们逐个改成 `HashMap + withLock` 意味着要在 19 个调用点各自记得加锁 —— 只要漏一处
 * 就是"并发写坏哈希表"这种偶发、难复现的故障。
 *
 * 所以把锁**收进类型里**：调用点写起来仍像普通 Map，但每个操作都在锁内。
 * 锁用项目既有的 [createCoreLock]（JVM ReentrantLock / iOS NSRecursiveLock，两端都可重入），
 * 因此"持锁时又调另一个加锁方法"不会死锁。
 *
 * 注意：这里**不**追求 ConcurrentHashMap 那样的无锁并发性能。这些表都很小、访问也不热，
 * 正确性和可移植性优先。
 */
internal class SharedMap<K, V> {
    private val lock = createCoreLock()
    private val map = HashMap<K, V>()

    operator fun get(key: K): V? = lock.withLock { map[key] }

    operator fun set(key: K, value: V) {
        lock.withLock { map[key] = value }
    }

    fun remove(key: K): V? = lock.withLock { map.remove(key) }

    fun containsKey(key: K): Boolean = lock.withLock { map.containsKey(key) }

    /** 原子「没有就放」。两个线程同时拨同一个对端时，只有一个能拿到自己的会话。 */
    fun computeIfAbsent(key: K, create: () -> V): V = lock.withLock {
        map[key] ?: create().also { map[key] = it }
    }

    /** 快照遍历：遍历期间其他线程的增删不会影响这一次（也不会抛并发修改异常）。 */
    fun forEach(action: (K, V) -> Unit) {
        val snapshot = lock.withLock { map.entries.map { it.key to it.value } }
        snapshot.forEach { (k, v) -> action(k, v) }
    }

    fun values(): List<V> = lock.withLock { map.values.toList() }

    /** 按谓词移除（快照匹配、锁内删除两步，避免边遍历边改）。 */
    fun removeValuesWhere(predicate: (V) -> Boolean) {
        lock.withLock {
            val victims = map.filterValues(predicate).keys.toList()
            victims.forEach { map.remove(it) }
        }
    }

    fun clear() {
        lock.withLock { map.clear() }
    }

    val size: Int get() = lock.withLock { map.size }
}

/** [SharedMap] 的集合版：线程安全的添加/包含/移除。 */
internal class SharedSet<V> {
    private val lock = createCoreLock()
    private val set = HashSet<V>()

    fun add(value: V): Boolean = lock.withLock { set.add(value) }

    fun remove(value: V): Boolean = lock.withLock { set.remove(value) }

    fun contains(value: V): Boolean = lock.withLock { value in set }

    fun toList(): List<V> = lock.withLock { set.toList() }

    fun clear() {
        lock.withLock { set.clear() }
    }

    val size: Int get() = lock.withLock { set.size }
}
