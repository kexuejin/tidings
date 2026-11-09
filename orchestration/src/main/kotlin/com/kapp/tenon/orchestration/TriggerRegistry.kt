// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.domain.TriggerEventRecord

/**
 * webhook 触发路由的定义注册表：sourceId → 派单参数（agentKey / prompt 模板 /
 * 回投群 / filter 规则链）。阶段三由插件 manifest 的 `triggers` 在注册通过后填充；
 * 阶段二先落结构与消费判据（DesktopExtension 的 handler 经这里解析路由，
 * 未注册的 sourceId 如实失败，绝不猜一个执行体）。
 *
 * filter 规则链（提案 §7.2）：**纯代码前置过滤**——type 前缀 + 关键词子串，
 * 毫秒级 0 Token；未命中的事件在消费侧翻 skipped，绝不唤醒模型。
 */
data class TriggerEventRoute(
    val sourceId: String,
    val agentKey: String,
    /** `{{field}}` 模板；占位符取值域在注册校验时锁定为 StandardSourceEvent 6 字段。 */
    val promptTemplate: String,
    /** 结果卡回投的群 id（裸 groupId，宿主必须是成员）；null = 不回投。 */
    val targetGroupId: String?,
    /** type 前缀白名单（如 `mail.inbound`）；空 = 全放行。 */
    val filterTypes: List<String> = emptyList(),
    /** subject/summary 关键词白名单；空 = 全放行。 */
    val filterKeywords: List<String> = emptyList(),
) {

    /**
     * §7.2 规则链判定：任一规则组为空 = 该组放行；两组都非空时必须**同时**命中
     * （类型限定 + 关键词过滤是「且」的关系——「只要类型对就放」会把垃圾邮件也喂给模型）。
     */
    fun matches(event: TriggerEventRecord): Boolean {
        val typeOk = filterTypes.isEmpty() || filterTypes.any { event.type.startsWith(it, ignoreCase = true) }
        if (!typeOk) return false
        if (filterKeywords.isEmpty()) return true
        val haystack = (event.subject + "\n" + event.summary).lowercase()
        return filterKeywords.any { haystack.contains(it.lowercase()) }
    }
}

/**
 * 路由表：读侧永远只看一份**不可变快照**，整表重建是一次 volatile 引用写入。
 *
 * 为什么不用 `ConcurrentHashMap.clear()` + 逐条 `putIfAbsent()`：对账式重建每拍都会
 * 重放整表，`clear()` 与最后一条 `register()` 之间存在一个「空表 / 半张表」窗口。
 * 消费循环（TriggerEventDrain）是另一条协程，恰好在窗口里 `resolve()` 到 null，
 * 就把一条**已经落盘**的事件翻成 `TriggerVerdict.Fail`——插件清单只是刷新了一下，
 * 用户丢的却是一封真实的邮件。并发容器保证不了「多步操作作为一个整体可见」。
 *
 * 现在先在局部把新表算完（含冲突裁决），再一次换引用：读者看到的是完整的旧表或
 * 完整的新表，没有中间态。写入口统一走 [writeLock]，避免两次并发重建互相覆盖。
 */
class TriggerRegistry {
    private val writeLock = Any()

    @Volatile
    private var routes: Map<String, TriggerEventRoute> = emptyMap()

    private fun keyOf(sourceId: String): String = sourceId.trim().lowercase()

    /**
     * 注册一条路由。返回 false = 这个 sourceId 已经被**另一条**路由占用，本次未注册。
     *
     * 旧实现是 `routes[key] = route`：两个插件声明同一个 sourceId 时，后者**静默**
     * 覆盖前者，谁赢取决于清单遍历顺序，而且没有任何日志。webhook 事件于是被派给
     * 一个插件没想到的执行体 —— 症状是「我配的触发器有时不生效」，查不出来。
     *
     * 现在先到者胜、冲突有日志（调用方负责记）。同一对象重复注册仍然算成功：
     * 对账式重建每拍都会重放同一批路由，那不是冲突。
     */
    fun register(route: TriggerEventRoute): Boolean = synchronized(writeLock) {
        val key = keyOf(route.sourceId)
        val existing = routes[key]
        if (existing != null) return@synchronized existing == route
        routes = routes + (key to route)
        true
    }

    /**
     * 整表原子替换（对账式重建的唯一入口）。返回被冲突挤掉的路由，由调用方记日志。
     *
     * [newRoutes] 只被遍历一次；遍历过程中读侧看到的仍是**旧表**。传入空集合等于
     * 卸掉全部路由（插件全注销）。冲突裁决与 [register] 同口径：先到者胜，同一对象
     * 重复出现不算冲突。
     */
    fun replaceAll(newRoutes: Iterable<TriggerEventRoute>): List<TriggerEventRoute> = synchronized(writeLock) {
        val next = LinkedHashMap<String, TriggerEventRoute>()
        val rejected = mutableListOf<TriggerEventRoute>()
        newRoutes.forEach { route ->
            val key = keyOf(route.sourceId)
            val existing = next[key]
            if (existing == null) {
                next[key] = route
            } else if (existing != route) {
                rejected += route
            }
        }
        // 一次引用写入；这份 map 之后再不被修改，读侧无需加锁。
        routes = java.util.Collections.unmodifiableMap(next)
        rejected
    }

    /** 整表清空：对账式重建用（插件注销后路由不残响）；同样是一次引用写入。 */
    fun clear() {
        synchronized(writeLock) { routes = emptyMap() }
    }

    fun resolve(sourceId: String): TriggerEventRoute? = routes[keyOf(sourceId)]

    fun all(): List<TriggerEventRoute> = routes.values.toList()
}
