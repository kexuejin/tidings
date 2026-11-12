// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

/**
 * Agent 岗位档案 —— 自动分配三层的第一层（编排模型适配设计 §一）。
 *
 * 自动组队（[com.kapp.tenon.agent.DelegationParser] 的 `组队:` 挡位）与代码门
 * （编排层的 StageChainGate）都从这里取事实：
 * - **vendor（厂商）**：异厂商审核的硬约束 —— 干活棒与审核棒同厂商时审核不独立；
 * - **role**：该 agent 的天然岗位（WORKER 只干活 / REVIEWER 只审核 / BOTH 都行）；
 * - **key 归一**：`codex-cli` / `codex` / `antigravity → agy` 的别名口径由
 *   `DriverKeys`（:agent-contract）**唯一**定义，这里与 `ControlPlane.resolveCliDriver`
 *   都调用它 —— 两处各写一份迟早分叉。
 *
 * 这是**静态声明表**：驱动是否真的在位（CLI 装没装、登录没登录）由
 * `ControlPlane.resolveCliDriver` 在运行时判定；档案表只回答「它是什么」，不回答「它在不在」。
 * vendor 取值刻意粗粒度（厂商级，不是模型级）：opencode 的上游模型可配置，
 * 单列一个桶比猜它背后是谁诚实。
 */
object AgentProfiles {

    /** 岗位：干活 / 审核 / 都行。 */
    enum class Role { WORKER, REVIEWER, BOTH }

    data class Profile(
        val key: String,
        val vendor: String,
        val displayName: String,
        val defaultRole: Role,
    )

    private val known: Map<String, Profile> = listOf(
        Profile("codex", "openai", "Codex", Role.WORKER),
        Profile("claude", "anthropic", "Claude Code", Role.WORKER),
        Profile("agy", "google", "Antigravity", Role.WORKER),
        Profile("opencode", "opencode", "OpenCode", Role.BOTH),
        Profile("dsh", "deepseek", "DeepSeek Harness", Role.BOTH),
    ).associateBy { it.key }

    /**
     * 外部插件共用的 vendor。不能用 pluginId 当 vendor：同一厂商的两个插件 ID
     * 会被误当成「异厂商」，从而绕过 StageChainGate 的独立审核判据。
     */
    const val EXTERNAL_PLUGIN_VENDOR: String = "external-plugin"

    /** 动态注册的外部插件或自定义扩展驱动档案（线程安全）。 */
    private val dynamicProfiles = java.util.concurrent.ConcurrentHashMap<String, Profile>()

    /** 动态注册外部或插件驱动档案。 */
    fun registerProfile(profile: Profile) {
        val canonical = DriverKeys.canonicalKey(profile.key) ?: profile.key
        // 内置档案不可被外部插件覆盖（否则可伪造 displayName/vendor/角色）。
        if (known.containsKey(canonical)) return
        dynamicProfiles[canonical] = profile
    }

    /** 移除动态注册的驱动档案。 */
    fun unregisterProfile(key: String) {
        val canonical = DriverKeys.canonicalKey(key) ?: key
        dynamicProfiles.remove(canonical)
    }

    fun knownKeys(): Set<String> = known.keys + dynamicProfiles.keys

    fun profileOf(rawKey: String?): Profile? {
        val norm = normalizeKey(rawKey) ?: return null
        return dynamicProfiles[norm] ?: known[norm]
    }

    fun vendorOf(rawKey: String?): String? = profileOf(rawKey)?.vendor

    /**
     * 任意写法 → 档案键：`codex-cli`/`codex` 命中，`antigravity` 归一成 `agy`；
     * 未知键 → null。
     *
     * 归一规则本身在 [DriverKeys] 里（:agent-contract），与
     * [com.kapp.tenon.agent.ControlPlane.resolveCliDriver] 用的是同一个对象；
     * 这里只补「档案表里有没有这个键」这一步。
     */
    fun normalizeKey(rawKey: String?): String? {
        val key = DriverKeys.canonicalKey(rawKey) ?: return null
        if (known.containsKey(key) || dynamicProfiles.containsKey(key)) return key
        val stripped = DriverKeys.stripCliSuffix(key)
        if (known.containsKey(stripped) || dynamicProfiles.containsKey(stripped)) return stripped
        return null
    }

    /**
     * 把「群里点名的 token」解析成**在位档案键**。
     *
     * 群里的 `@` token 有两种形态：裸档案键（`@dsh`）与席位全名
     * （`@dsh-<host>-<workspace>`，来自 `virtualAgents[].name`）。后者不是档案键，
     * [normalizeKey] 会返回 null —— 触发判据认得它、执行侧却找不到驱动，症状是
     * 「@ 了、帧也到了，工单卡永远不出现」。解析只在 [availableKeys]（在位集合）里做，
     * 前缀命中取最长的一个，绝不猜、绝不回落到第一个：解析不出来就返回 null，
     * 由调用方走既有默认路径。
     */
    fun resolveKey(rawKey: String?, availableKeys: Set<String>): String? {
        val key = DriverKeys.canonicalKey(rawKey) ?: return null
        normalizeKey(key)?.takeIf { it in availableKeys }?.let { return it }
        return availableKeys.filter { key == it || key.startsWith("$it-") }
            .maxByOrNull { it.length }
    }

    /** 自动组队的干活棒优先序（真机验证过的 dev(codex) 打头）。 */
    private val workerPreference = listOf("codex", "claude", "opencode", "agy", "dsh")

    /** 自动组队的审核棒优先序（qa(dsh) 真机验证过；默认与干活棒异厂商）。 */
    private val reviewerPreference = listOf("dsh", "opencode", "claude", "agy")

    /** 在位集合里按优先序选默认干活棒；一个都不在 → null。 */
    fun defaultWorkerKey(availableKeys: Set<String>): String? =
        workerPreference.firstOrNull { it in availableKeys }

    /** 第一个与 [workerKey] 异厂商且在位的审核棒；没有 → null（门会据此降级）。 */
    fun defaultReviewerKey(workerKey: String?, availableKeys: Set<String>): String? {
        val workerVendor = vendorOf(workerKey) ?: return null
        return reviewerPreference.firstOrNull { key ->
            key != workerKey && key in availableKeys && vendorOf(key) != workerVendor
        }
    }

    /** 拒绝文案用的「当前可用的异厂商 agent」显示名清单。 */
    fun crossVendorReviewerNames(workerKey: String?, availableKeys: Set<String>): List<String> {
        val workerVendor = vendorOf(workerKey)
        return availableKeys.mapNotNull { key ->
            val profile = profileOf(key) ?: return@mapNotNull null
            profile.displayName.takeIf { profile.vendor != workerVendor }
        }
    }
}
