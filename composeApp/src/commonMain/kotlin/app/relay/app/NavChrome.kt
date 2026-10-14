// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import app.relay.navigation.AppRoutes

/**
 * 导航外壳（页签徽标）的数据来源。
 *
 * 为什么单独放在这里而不是留在 `App.kt` 里当私有函数：截图门禁在 `androidApp` 模块，
 * 它渲染的是**同一个** `CompactScaffold`，也必须喂**同一份**徽标数字。私有函数
 * 跨不了模块，于是门禁只能自己算一遍 —— 那样"门禁绿了但应用上是另一回事"就又回来了。
 * 放在 `app` 包（引入 `navigation`，而不是反过来）保持依赖方向朝上。
 *
 * 这里原来还有一个 `sessionTabsOf()`：把打开过的会话映射成聊天详情顶部标签条的模型。
 * 那条标签条已整体移除。
 * 它 KDoc 里记着的一条教训**没有跟着消失**，已搬到 [RelayUiState.convTitle]：
 * 会话键不是节点 id，群会话的键是 `group:<groupId>`，别再拿它去查节点表。
 */

/**
 * 底栏徽标：消息用未读总数，Agent 用**属于 Agent 的**待处理数。
 *
 * 没有直接用 `state.totalPending`：那个数是所有对端的待处理之和，含真人同事，
 * 挂在 Agent 页签上会虚高。两个数都为 0 时不放进 map，页签就不会画出空徽标。
 */
fun navBadges(state: RelayUiState): Map<String, Int> = buildMap {
    val unread = state.unreadByPeer.values.sum()
    if (unread > 0) put(AppRoutes.CONVERSATIONS, unread)
    val agentPending = state.agentPeers.sumOf { state.pendingByPeer[it.nodeId] ?: 0 }
    if (agentPending > 0) put(AppRoutes.AGENTS, agentPending)
}
