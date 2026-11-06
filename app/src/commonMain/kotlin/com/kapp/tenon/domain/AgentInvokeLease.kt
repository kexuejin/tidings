// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.AgentRunStates

/** 租约状态。终态：done / timeout / denied / no-executor / no-plugin / error。 */
object InvokeLeaseStates {
    const val RUNNING = AgentRunStates.RUNNING
    const val DONE = "done"
    const val TIMEOUT = "timeout"
    const val DENIED = "denied"
    const val NO_EXECUTOR = "no-executor"
    const val NO_PLUGIN = "no-plugin"
    const val ERROR = "error"
    val TERMINAL: Set<String> = setOf(DONE, TIMEOUT, DENIED, NO_EXECUTOR, NO_PLUGIN, ERROR)
}

/** 一条跨端派单的租约（发起方视角）。落进 [RelayUiState.agentInvokes]，界面与测试都能读。 */
data class AgentInvokeLease(
    val invokeId: String,
    val groupId: String,
    val agentId: String,
    val agentName: String,
    val targetNode: String,
    val prompt: String,
    val wsId: String?,
    val state: String,
    val createdAt: Long,
    val deadlineAt: Long,
    val finishedAt: Long? = null,
    val summary: String? = null,
    val error: String? = null,
)

/**
 * 租约台账：**终态翻转是原子的**。结果帧与超时巡检并发竞争同一条租约时，
 * 只有一方赢（`compute` 按键加锁）—— 「已完成的任务被改判超时」与
 * 「超时后又报成功」都是谎，两者都进不了台账。
 *
 * 状态的唯一事实来源是这张表；[RelayUiState.agentInvokes] 是它的镜像（UI 读）。
 *
 * @param now 可注入时钟（测试毫秒级验证期限；生产用墙钟）。
 */
internal class AgentInvokeLeaseTable(private val now: () -> Long) {

    private val rows = LockedMap<String, AgentInvokeLease>()

    fun register(
        invokeId: String,
        groupId: String,
        agentId: String,
        agentName: String,
        targetNode: String,
        prompt: String,
        wsId: String?,
        deadlineMs: Long,
    ) {
        val start = now()
        rows[invokeId] = AgentInvokeLease(
            invokeId = invokeId, groupId = groupId, agentId = agentId, agentName = agentName,
            targetNode = targetNode, prompt = prompt, wsId = wsId,
            state = InvokeLeaseStates.RUNNING, createdAt = start, deadlineAt = start + deadlineMs,
        )
    }

    /** 结果帧落终态（成功）。租约不存在或已终态 ⇒ null（幂等，迟到的结果被丢弃）。 */
    fun markDone(invokeId: String, summary: String?, error: String?): AgentInvokeLease? =
        transition(invokeId, InvokeLeaseStates.DONE, summary, error)

    /** 结果帧落终态（失败档，status 是对端如实报的取值）。 */
    fun markFailed(invokeId: String, status: String, summary: String?, error: String?): AgentInvokeLease? {
        val target = if (status in InvokeLeaseStates.TERMINAL) status else InvokeLeaseStates.ERROR
        return transition(invokeId, target, summary, error)
    }

    /**
     * 到期巡检：把所有过期的 RUNNING 租约原子翻 TIMEOUT。
     * @return 本轮翻掉的租约（调用方据此各发一张超时卡）。
     */
    fun markTimeoutDue(): List<AgentInvokeLease> {
        val t = now()
        val flipped = mutableListOf<AgentInvokeLease>()
        for ((id, lease) in rows.entriesSnapshot()) {
            if (lease.state != InvokeLeaseStates.RUNNING || t < lease.deadlineAt) continue
            var won: AgentInvokeLease? = null
            rows.compute(id) { _, current ->
                if (current == null || current.state != InvokeLeaseStates.RUNNING) return@compute current
                won = current.copy(state = InvokeLeaseStates.TIMEOUT, finishedAt = t)
                won
            }
            won?.let { flipped += it }
        }
        return flipped
    }

    fun snapshot(): Collection<AgentInvokeLease> = rows.valuesSnapshot()

    /** 只清**终态且过老**的租约；RUNNING 的不许动（清了就没有超时判定）。 */
    fun pruneTerminalOlderThan(maxAgeMs: Long) {
        val t = now()
        rows.removeIf { (_, lease) ->
            lease.state in InvokeLeaseStates.TERMINAL &&
                (lease.finishedAt ?: lease.createdAt) + maxAgeMs < t
        }
    }

    private fun transition(invokeId: String, toState: String, summary: String?, error: String?): AgentInvokeLease? {
        var won: AgentInvokeLease? = null
        rows.compute(invokeId) { _, current ->
            if (current == null || current.state != InvokeLeaseStates.RUNNING) return@compute current
            won = current.copy(state = toState, finishedAt = now(), summary = summary, error = error)
            won
        }
        return won
    }
}
