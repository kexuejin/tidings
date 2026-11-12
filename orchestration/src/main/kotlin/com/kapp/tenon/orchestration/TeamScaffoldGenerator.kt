// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.drivers.AgentProfiles
import com.kapp.tenon.domain.TeamScaffoldDefaults
import com.kapp.tenon.domain.TeamScaffoldFallback
import com.kapp.tenon.domain.TeamScaffoldParser
import com.kapp.tenon.domain.TeamScaffoldSpec
import com.kapp.tenon.domain.newTeamScaffoldAgentId

/**
 * 「AI 建队」的编排侧一条龙：LLM（[TeamBuilderLlm]）→ 过门（[TeamScaffoldParser]）→
 * 回落模板（[TeamScaffoldFallback]）。这就是 `TeamScaffoldBridge` 的实现体 ——
 * UI 只面对「null = 生成不了」一个出口，不感知 LLM 与模板的分层。
 *
 * 驱动名册与兜底驱动对在这里定（在位事实 = `availableDrivers`，与
 * `DesktopTeamExecutionPort` 同一份名册）：worker 取第一个有厂商的键，reviewer 取第一个
 * **异厂商**键 —— 执行档保存校验要求 reviewer 与 worker 异厂商，兜底对先天合法；
 * 没有第二个厂商 = 组不出合法执行档，如实返回 null（不造一个注定派不出去的方案）。
 */
object TeamScaffoldGenerator {

    fun generate(
        need: String,
        leaderKeys: Set<String>,
        defaults: TeamScaffoldDefaults,
        availableDrivers: () -> Set<String>?,
    ): TeamScaffoldSpec? {
        if (need.isBlank() || leaderKeys.isEmpty()) return null
        val drivers = availableDrivers() ?: return null
        if (drivers.isEmpty()) return null
        val leader = leaderKeys.first()
        val workerDriver = drivers.firstOrNull { AgentProfiles.vendorOf(it) != null } ?: return null
        val workerVendor = AgentProfiles.vendorOf(workerDriver)
        val reviewerDriver = drivers.firstOrNull {
            it != workerDriver && AgentProfiles.vendorOf(it) != workerVendor
        }
        val fromLlm = TeamBuilderLlm.draft(need, leaderKeys, drivers)?.let { raw ->
            TeamScaffoldParser.parse(
                raw = raw,
                leaderKeys = leaderKeys,
                driverKeys = drivers,
                fallbackWorkerDriver = workerDriver,
                fallbackReviewerDriver = reviewerDriver,
                defaultTeamName = defaults.teamTitle,
                defaultRole = defaults.role,
            )
        }
        if (fromLlm != null) return fromLlm
        // 回落模板需要合法兜底对；reviewer 驱动缺位 = 连模板都组不合法。
        val fallbackReviewer = reviewerDriver ?: return null
        return TeamScaffoldFallback.template(
            need = need,
            leaderAgentId = leader,
            workerDriver = workerDriver,
            reviewerDriver = fallbackReviewer,
            defaults = defaults,
            newAgentId = ::newTeamScaffoldAgentId,
        )
    }
}
