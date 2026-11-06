// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.ChainStage
import com.kapp.tenon.agent.DelegationPlan
import com.kapp.tenon.agent.drivers.AgentProfiles

/**
 * 委派计划的**代码门**（编排模型适配设计 §一/§二）：
 * planner（挡位 B）可以推荐分工，但硬约束在这里裁决 —— 冲突时代码门赢。
 *
 * 四条规则：
 * 0. **限流冷却**（最前置）：任一棒处于 429 / 配额冷却期 → 立即拒收并给可用替代，
 *    在位校验与厂商档案都不必查 —— 用户不必等一次注定超时的 CLI 拉起（P2 规格 5）；
 * 1. **在位**：计划点名的每个 agent（键归一后）都必须在本机在位；
 * 2. **异厂商审核**：每一根审核棒与干活棒同厂商 → 审核不独立，拒绝并给出当前可用的
 *    异厂商名单（文案必须可操作：缺谁、现在有什么）；
 * 3. **降级优于吞单**：自动组队找不到第二个厂商时降级为单棒（理由随单走），
 *    不静默吞掉一次委派 —— 与「派不出去就回落单棒」的既有纪律同源。
 *
 * 只作用于委派计划（[com.kapp.tenon.agent.DelegationParser] 的产物）。旧的
 * `角色(驱动键): 任务` 阶段语法是显式的权力用户控制，**不在门内**（行为零变更）；
 * 委派是产品路径，承诺「A 干 B 审核」就得出厂自带这道门。
 */
object StageChainGate {

    sealed interface Decision {
        /** 放行：交给链执行器（≥2 棒）或单棒（=1 棒，仅显式计划可能的防御分支）。 */
        data class Pass(val stages: List<ChainStage>) : Decision

        /** 降级为单棒：自动组队没有第二个厂商可做异厂商审核。 */
        data class Downgrade(val stage: ChainStage, val reason: String) : Decision

        /** 拒绝：reason 必须可操作（缺谁、当前有什么、为什么不行）。 */
        data class Reject(val reason: String) : Decision
    }

    /**
     * @param vendorOf 厂商指纹（缺省取岗位档案表；测试可注入自定义映射）。
     * @param cooldownRemainingMs 限流冷却剩余毫秒（缺省 0 = 无冷却，行为零变更）。
     *   纯函数探针：代码门不自己拉起驱动、不发帧，秒级避坑靠的就是只读这一下。
     */
    fun enforce(
        plan: DelegationPlan,
        availableKeys: Set<String>,
        vendorOf: (String) -> String? = AgentProfiles::vendorOf,
        cooldownRemainingMs: (String) -> Long = { 0L },
    ): Decision {
        // 0. 限流冷却：先于任何档案查询。冷却中的棒连「在位」都不必判 ——
        //    它眼下就是不可用，派出去只会白等一次上游超时。
        val cooling = plan.stages.map { it.agentKey }.distinct().mapNotNull { key ->
            cooldownRemainingMs(key).takeIf { it > 0L }?.let { key to it }
        }
        if (cooling.isNotEmpty()) {
            val workerKey = plan.stages.firstOrNull()?.agentKey
            val workerSlot = workerKey?.let { AgentProfiles.normalizeKey(it) }
            val workerVendor = AgentProfiles.vendorOf(workerKey)
            val alternatives = availableKeys.filter { key ->
                cooldownRemainingMs(key) <= 0L &&
                    AgentProfiles.normalizeKey(key) != workerSlot &&
                    AgentProfiles.vendorOf(key) != null &&
                    AgentProfiles.vendorOf(key) != workerVendor
            }.sorted()
            val coolingText = cooling.joinToString { (key, left) -> "$key (${left / 1000L}s left)" }
            val recommendation = if (alternatives.isEmpty()) {
                "wait for cooldown"
            } else {
                "recommended: " + alternatives.joinToString()
            }
            return Decision.Reject("agent rate-limited: $coolingText; $recommendation")
        }

        // 1. 在位校验：键归一后必须在位；未知键也判不在位。
        val missing = plan.stages
            .map { it.agentKey }
            .filter { (AgentProfiles.normalizeKey(it) ?: it) !in availableKeys }
            .distinct()
        if (missing.isNotEmpty()) {
            val available = availableKeys.sorted().joinToString().ifEmpty { "（无）" }
            return Decision.Reject("agent 不在位：${missing.joinToString()}；当前可用：$available")
        }

        // 2. 单棒计划：自动组队降级；显式计划不该出现这个形状（防御性拒绝）。
        if (plan.stages.size < 2) {
            val only = plan.stages.firstOrNull() ?: return Decision.Reject("委派计划为空")
            return if (plan.source == DelegationPlan.Source.AUTO) {
                Decision.Downgrade(only, "没有第二个厂商的 agent 可做异厂商审核，退化为单棒")
            } else {
                Decision.Reject("委派计划至少需要干活 + 审核两棒")
            }
        }

        // 3. 异厂商审核：第一棒 = 干活，其后**每一棒**都是审查棒（线性多棒，P2 规格 3）。
        //    旧门只查 first()/last()，把同厂商棒塞到中间就能绕过 —— 这条是真缺陷修复。
        val worker = plan.stages.first()
        val workerVendor = vendorOf(worker.agentKey)
        val reviewers = plan.stages.drop(1)
        val offender = reviewers.firstOrNull { stage ->
            val vendor = vendorOf(stage.agentKey)
            vendor == null || vendor == workerVendor
        }
        val reviewer = offender ?: reviewers.last()
        val reviewerVendor = vendorOf(reviewer.agentKey)
        if (workerVendor == null || reviewerVendor == null) {
            return Decision.Reject("agent 厂商档案缺失：${worker.agentKey} / ${reviewer.agentKey}")
        }
        if (workerVendor == reviewerVendor) {
            val cross = AgentProfiles.crossVendorReviewerNames(worker.agentKey, availableKeys)
            val hint = if (cross.isEmpty()) {
                "当前没有异厂商 agent 在位，无法组队审核"
            } else {
                "可用异厂商审核：" + cross.joinToString()
            }
            return Decision.Reject(
                "审核棒（${reviewer.agentKey}）与干活棒（${worker.agentKey}）同厂商（$workerVendor），审核不独立。$hint",
            )
        }
        return Decision.Pass(plan.stages)
    }
}
