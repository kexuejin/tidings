// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import kotlinx.serialization.json.JsonObject

/**
 * 「已由 **X** 处理」里的 X 怎么取（卡片上给人看的那一格）。
 *
 * 顺序：**群成员昵称 → 本机自己的昵称 → nodeId 兜底**。
 *
 * 中间那一档是补上的：传进来的 `decidedBy` 是 nodeId，而**本机自己**既不在对端
 * 表里（那张表装的是「别人」），也不一定被写进本群的 `memberNicks` —— 建群方点
 * 自己那张卡时，两处都查不到，于是界面上直接出现「已由 46c23456-6e74-4a7c-… 处理」，
 * 而用户明明给这台机器起过名字。`desktopApp` 的「可信设备」列表早就为同一个坑
 * 补过一档本机昵称，卡片这一格当时漏了。
 *
 * 判据只认「名字」这一件事：台账、权限、防重仍然用 nodeId —— 把显示名当身份会
 * 让两个同名设备互相当成对方。
 */
internal fun decidedDisplayName(
    decidedBy: String,
    memberNicks: Map<String, String>?,
    selfNodeId: String,
    selfNick: String,
): String {
    memberNicks?.get(decidedBy)?.takeIf { it.isNotBlank() }?.let { return it }
    if (decidedBy == selfNodeId) selfNick.takeIf { it.isNotBlank() }?.let { return it }
    return decidedBy
}

/**
 * 宿主侧的审批防重台账（§7.3-2 Atomic Action Lock）。
 *
 * 判定的生效是 [LockedMap.putIfAbsent] 的原子性：跨设备的多个并发
 * `agent-ack` 里**只有第一个**返回 true，其余被幂等丢弃。台账在**宿主**这一侧 ——
 * 全群只认宿主广播的那张 rev+1 卡，本地乐观置灰只是界面礼貌，不是一致性来源。
 */
internal class AgentCardActionLedger {

    private val decided = LockedMap<String, String>()

    /** 首个判定生效返回 true；同键的后续判定（无论谁发）幂等丢弃。 */
    fun firstWin(cardId: String, actionId: String, decidedBy: String): Boolean =
        decided.putIfAbsent(key(cardId, actionId), decidedBy) == null

    fun decidedBy(cardId: String, actionId: String): String? = decided[key(cardId, actionId)]

    /**
     * 从落库卡片行回填（重启后台账不丢）：带 `decidedBy` + `decidedActionId` 的行
     * 就是宿主当年判过的证据；没有这两个键的行不许凭空多出判定。
     */
    fun rebuildFrom(cardRows: List<JsonObject>) {
        for (card in cardRows) {
            val cardId = card.strField("cardId") ?: continue
            val decidedActionId = card.strField("decidedActionId") ?: continue
            val by = card.strField("decidedBy") ?: continue
            decided.putIfAbsent(key(cardId, decidedActionId), by)
        }
    }

    private fun key(cardId: String, actionId: String): String = cardId + "|" + actionId
}
