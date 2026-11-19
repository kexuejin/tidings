// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.domain.EmployeeProfile
import com.kapp.tenon.agent.drivers.AgentProfiles

/**
 * `@某个席位` 到达执行桥时，**这一棒用哪个驱动键** —— 唯一的一处答案。
 *
 * ## 为什么需要它（真机现场）
 *
 * 席位上的 `agentId` 是**路由键**（员工档案的 `employeeId`，如 `emp-…`），驱动注册表认的
 * 是**配置键**（`dsh` / `codex-cli`）。执行桥原先把两者当同一个东西：直接
 * `AgentProfiles.resolveKey(agentId, 在位集合)`，MINT 建档的人一律解析成 null ⇒
 * `cliDriver=null` ⇒ 整段 CLI 分支跳过 ⇒ 群里只有一句「没有完成这次任务」，日志里一行
 * `[OCGO-BRIDGE] … cliDriver=null`。这不是环境问题，是**身份键与配置键混用**。
 *
 * ## 判据顺序（与 §2 身份模型一致）
 *
 * 1. **员工档案的 `driverKey` 优先**（配置是档案说的，不是从键反解出来的）；
 * 2. 档案没有 / 空串 / 解不到在位驱动 ⇒ 退回 `resolveKey(agentId)` 的老口径
 *    （老数据里 `agentId` 就是 `dsh-a030882e` 这种带驱动前缀的席位 ID）；
 * 3. 都解不出 ⇒ 原样回 `agentId`（调用方据此得到 `resolveCliDriver == null` 并如实退单，
 *    **不回落默认驱动** —— A5 的不变量）。
 *
 * **不做的事**：不猜第一个在位驱动。猜出来的那一棒会在用户没配过的底座上跑真实命令。
 */
internal fun dispatchDriverKey(
    agentId: String,
    profiles: List<EmployeeProfile>,
    availableKeys: Set<String>,
): String {
    val key = agentId.trim()
    if (key.isEmpty()) return agentId
    val profile = profiles.firstOrNull { p ->
        p.agentId.trim().equals(key, ignoreCase = true) || p.employeeId.trim() == key
    }
    val declared = profile?.driverKey?.trim().orEmpty()
    if (declared.isNotEmpty()) {
        AgentProfiles.resolveKey(declared, availableKeys)?.let { return it }
        // 档案写了、但这家驱动不在这台机器上：**不改写成别的驱动**，原样带下去，
        // 让执行桥如实报「运行时不在」（DRIVER_MISSING 那一档），而不是偷偷换一家跑。
        return declared
    }
    return AgentProfiles.resolveKey(key, availableKeys) ?: key
}
