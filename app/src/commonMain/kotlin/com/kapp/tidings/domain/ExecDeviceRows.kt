// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.core.protocol.AgentGrantScopes
// ExecDeviceRow 现在住在同一个包（领域侧）：它由 execDeviceRows() 算出来。

/**
 * 一个授权条目中界面需要的部分（与宿主 `AgentGrantStore.Grant` 同形，但只留要用的字段）。
 * 抽出来是为了让 [execDeviceRows] 能被单测直接喂数据，不必起一个真台账。
 */
public data class ExecGrantView(
    val nodeId: String,
    val scope: String,
    val agentKey: String?,
    val isMyDevice: Boolean,
)

/**
 * 算出「谁能让这个 agent 干活」那一屏要显示的行。
 *
 * ## 两个维度的来源不同
 *
 * - **可信设备**（`trusted = true`）：来自 `scope=ALL` 的授权记录 —— 「我批准过这台设备」；
 * - **其他设备**：来自群成员 / 已知对端，默认不在列表里，要靠「添加设备」显式加入。
 *
 * 两者都会被列出来，但**是否勾上**是独立的一件事：
 * 一台可信设备可以不勾（它能看，但不许跑命令）；一台不可信设备也可以勾（单点授权）。
 * 这正是本功能的全部意义 —— 逐设备、逐 agent 的决定。
 *
 * ## 本机用户永远勾上
 *
 * 他本来就在自己机器上，不需要「授权」。`isSelf = true` 让界面把那个勾选框 disable 掉。
 *
 * ## 排序
 *
 * 本机 → 可信 → 已勾 → 其余。让「最该被看到的」排在最上面：
 * 用户来这里通常是想确认「我自己行不行」或「某人到底有没有被授权」。
 *
 * @param selfNodeId 本机 nodeId。
 * @param agentKey 这个 agent 的身份键。
 * @param knownDevices 已知设备：(nodeId, 展示名)。
 * @param grants 全部授权记录（用来判可信与已授权）。
 */
public fun execDeviceRows(
    selfNodeId: String,
    agentKey: String,
    knownDevices: List<Pair<String, String>>,
    grants: List<ExecGrantView>,
): List<ExecDeviceRow> {
    // 可信设备集合：`scope=ALL` 的授权记录。
    val trusted = grants
        .filter { it.scope == AgentGrantScopes.ALL }
        .map { it.nodeId }
        .toSet()
    // 已授权这个 agent 的：`scope=AGENT` 且 agentKey 匹配。
    val granted = grants
        .filter { it.scope == AgentGrantScopes.AGENT && it.agentKey == agentKey }
        .map { it.nodeId }
        .toSet()

    // 名单 = 已知设备 ∪ 授权记录里出现过的人（后者可能还没在 knownDevices 里，
    // 例如授权过但当前不在群里 —— 那台设备仍要显示出来，否则用户看不到也撤不掉）。
    // **空串不算名字**：`toMap` 是后值覆盖前值，一条空 nick 会静默把真名顶掉。
    // 真机上见过：本机明明配了 `settings.nick`，界面却显示成一串 nodeId 前 8 位。
    val labels = knownDevices
        .filter { it.second.isNotBlank() }
        .toMap()
    val nodeIds = LinkedHashSet<String>()
    nodeIds += selfNodeId
    nodeIds += knownDevices.map { it.first }
    nodeIds += trusted
    nodeIds += granted

    return nodeIds
        .filter { it.isNotBlank() }
        .map { nodeId ->
            val isSelf = nodeId == selfNodeId
            ExecDeviceRow(
                nodeId = nodeId,
                // 空串昵称当作「没有昵称」：直接 ":" 会让整行显示成空白，
                // 用户看到一条没有任何标识的设备，既认不出也删不掉。
                label = labels[nodeId]?.trim()?.takeIf { it.isNotEmpty() } ?: nodeId.take(8),
                trusted = nodeId in trusted,
                // 本机永远勾上：他本来就在自己机器上，不需要「被授权」。
                checked = isSelf || nodeId in granted,
                isSelf = isSelf,
            )
        }
        .sortedWith(
            compareByDescending<ExecDeviceRow> { it.isSelf }
                .thenByDescending { it.trusted }
                .thenByDescending { it.checked }
                .thenBy { it.label },
        )
}
