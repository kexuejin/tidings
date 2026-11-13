// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.settings

import com.kapp.tenon.domain.AgentSlice
import com.kapp.tenon.domain.RelayController
import com.kapp.tenon.domain.model.ToolApprovalRuleUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 免批表读撤的**跨端实现**（本端增补 cap `atr1`）：读从 [RelayController.agentFlow]
 * 的快照 slice 来，撤经 `controller.revokeToolRule` 发帧。桌面壳的那一份
 * （`StoreToolApprovalsSource`，desktopApp）是同进程直读宿主 store —— 两份实现面向
 * 同一条 [ToolApprovalsSource] 缝，区块 UI 只有一份（§2.6：内容共享，接缝参数化）。
 *
 * ## 读侧为什么是快照而不是订阅
 *
 * 缝的 `snapshotRows()` 是同步取值（桌面那份就是 store 的同步快照）；这里返回
 * [hostNodeId] 宿主的最近一份快照（slice 的 `toolApprovalRules`，**整键覆盖**
 * 语义 —— 每次入站快照都替换旧的那份，合并会复活宿主刚撤掉的行）。变更通知走
 * [addChangeListener]：收集 slice 流，只在**这一份宿主的 rules 值变了**时发 ——
 * 与 store 的 `addChangeListener`（写/撤都发）同一口径，数据源换成了帧。
 *
 * @param scope 变更通知的收集域（组合根给 `rememberCoroutineScope()`；缝的返回值
 *   是注销函数，离开组合随 dispose 停掉收集）。
 */
class RelayToolApprovalsSource(
    private val controller: RelayController,
    private val hostNodeId: String,
    private val scope: CoroutineScope,
    /** 规则快照的观察面；默认接 [RelayController.agentFlow]，测试可注入假流。 */
    private val sliceFlow: StateFlow<AgentSlice> = controller.agentFlow,
) : ToolApprovalsSource {

    private fun rulesOf(slice: AgentSlice): List<ToolApprovalRuleUi> =
        slice.toolApprovalRules[hostNodeId].orEmpty()

    override fun snapshotRows(): List<ToolApprovalRow> =
        rulesOf(sliceFlow.value).map {
            ToolApprovalRow(
                nodeId = it.nodeId,
                agentKey = it.agentKey,
                wsId = it.wsId,
                tool = it.tool,
                // grantedAt 只用于年龄文案；宿主没报（null）时兜 0 —— 显示层把它当
                // "未知时刻"处理，不猜一个具体时间。
                grantedAt = it.grantedAt ?: 0L,
            )
        }

    override fun revokeExactly(nodeId: String, agentKey: String?, wsId: String?, tool: String): Int =
        controller.revokeToolRule(
            hostNodeId,
            ToolApprovalRuleUi(
                nodeId = nodeId,
                tool = tool,
                agentKey = agentKey,
                wsId = wsId,
                grantedAt = null,
                grantedBy = null,
            ),
        )

    override fun hasLoadedSnapshot(): Boolean = sliceFlow.value.toolApprovalRules.containsKey(hostNodeId)

    override fun lastReadFailed(): Boolean = hostNodeId in sliceFlow.value.toolRulesReadFailed

    override fun addChangeListener(listener: () -> Unit): () -> Unit {
        var last: List<ToolApprovalRuleUi>? = null
        val job: Job = scope.launch {
            sliceFlow.collect { slice ->
                val current = rulesOf(slice)
                if (current != last) {
                    last = current
                    listener()
                }
            }
        }
        return { job.cancel() }
    }
}
