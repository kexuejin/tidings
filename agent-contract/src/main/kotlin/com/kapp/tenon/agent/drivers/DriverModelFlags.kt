// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

/**
 * 「哪些驱动能指定模型、参数长什么样」的查询面 —— 词表本身住在身份卡
 * [CliDriverSpecs]，这里只按它派生出查询接口。
 *
 * 存在理由：岗位执行配置（`TeamExecutionMemberSpec.models`）里存了模型就必须真的生效。
 * 但各 CLI 的传参形态不同，且**不是每个驱动都有通道**——多数走旗标；dsh 没有旗标，
 * 走它自己的 `--patch` 配置覆盖（[CliDriverSpecs.ModelMechanism.CONFIG_PATCH]）；zcode
 * 只有会话内选模型；插件驱动由对方进程决定。若用一个「大概都支持」的默认，症状是
 * 「配了模型、跑的还是默认模型」，而两边都编译通过、都不报错——正是本仓最贵的那类静默失效。
 *
 * **「支持」与「有旗标」是两件事**：前者看 `supportsPerTaskModel`，后者看 `mechanism`。
 *
 * 因此：**缺席即不支持**。不支持却指定了模型，保存期就该拒（`TeamExecutionValidation`），
 * 驱动侧再兜一层响亮失败（[AgentDriver.withModel]）。旗标形态由身份卡的
 * [CliDriverSpecs.Spec.modelFlag] 给出，实测依据随身份卡字段留存，不在此重复。
 */
object DriverModelFlags {

    /**
     * 档案键 → 旗标拼装，从身份卡（[CliDriverSpecs]）派生。
     *
     * **只收 [CliDriverSpecs.ModelMechanism.FLAG] 通道**：dsh 支持按次指定模型，但走的是
     * 配置覆盖（`--patch`），没有旗标 —— 它进 [supportedKeys] 但**不进这张表**。
     * 把「支持」与「有旗标」混为一谈，会让 dsh 要么被误判不支持、要么拼出一个它不认的旗标。
     */
    private val flagsByProfileKey: Map<String, (String) -> List<String>> =
        CliDriverSpecs.builtins
            .filter { it.effectiveModelMechanism == CliDriverSpecs.ModelMechanism.FLAG }
            .associate { spec ->
                val flag = requireNotNull(spec.modelFlag) { "FLAG mechanism but no modelFlag: ${spec.key}" }
                spec.key to { model -> listOf(flag, model) }
            }

    /** 该驱动能不能按本次任务指定模型 —— **与传参通道无关**。[rawKey] 收任意写法。 */
    fun supports(rawKey: String?): Boolean = specOf(rawKey)?.supportsPerTaskModel == true

    /** 该驱动的传参通道；null = 认不出这家。 */
    fun mechanismOf(rawKey: String?): CliDriverSpecs.ModelMechanism? =
        specOf(rawKey)?.effectiveModelMechanism

    /**
     * 能按次指定模型的**档案键**集合（对外只读）。
     *
     * 执行配置页要按它把「模型」格置灰，对账守卫要按它比对档案表 —— 两处都从这一份取，
     * 不许各自 `listOf("claude", …)` 抄一遍：抄两处必错一处。
     *
     * **含 dsh**（走配置覆盖也算支持）；只收真正做不到的（zcode）。
     */
    val supportedKeys: Set<String>
        get() = CliDriverSpecs.builtins.filter { it.supportsPerTaskModel }.map { it.key }.toSet()

    /**
     * 本次任务的模型旗标；空模型 / 不支持的驱动都返回空表。
     *
     * 不支持的驱动返回空表**不是**降级：调用方必须先过 [unsupportedReason]，
     * 否则就是「静默忽略」。
     */
    fun flagsFor(rawKey: String?, model: String?): List<String> {
        val trimmed = model?.trim().orEmpty()
        if (trimmed.isEmpty()) return emptyList()
        return builderOf(rawKey)?.invoke(trimmed).orEmpty()
    }

    /**
     * 不支持却指定了模型时的诊断文本；null = 没问题（没指定，或该驱动支持）。
     *
     * 文案直接进保存拒绝原因 / 驱动 Error 事件，所以要能照着改。
     */
    fun unsupportedReason(rawKey: String?, model: String?): String? {
        val trimmed = model?.trim().orEmpty()
        if (trimmed.isEmpty() || supports(rawKey)) return null
        val key = rawKey?.trim()?.takeIf { it.isNotEmpty() } ?: "?"
        return "driver '" + key + "' cannot select a model per task " +
            "(it only has a model in its own app/config) — leave model empty"
    }

    private fun builderOf(rawKey: String?): ((String) -> List<String>)? {
        val key = DriverKeys.canonicalKey(rawKey) ?: return null
        // `canonicalKey` 只做别名归一（`antigravity → agy`），不解 `-cli` 后缀；
        // 档案键是不带后缀的那个，所以两层都试一次（与 `AgentProfiles.normalizeKey` 同序）。
        return flagsByProfileKey[key] ?: flagsByProfileKey[DriverKeys.stripCliSuffix(key)]
    }

    /** 档案键 → 身份卡（别名与 `-cli` 后缀两层都试，与 [builderOf] 同序）。 */
    private fun specOf(rawKey: String?): CliDriverSpecs.Spec? {
        val key = DriverKeys.canonicalKey(rawKey) ?: return null
        return CliDriverSpecs.byKey[key] ?: CliDriverSpecs.byKey[DriverKeys.stripCliSuffix(key)]
    }
}
