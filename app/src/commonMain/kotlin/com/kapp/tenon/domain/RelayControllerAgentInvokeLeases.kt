// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.nowMillis
import com.kapp.tenon.core.protocol.Envelope
import com.kapp.tenon.core.protocol.Limits
import com.kapp.tenon.core.protocol.newId
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 派单的**租约与执行**：登记、发帧、本机执行、结果帧落终态、超时巡检与超时卡。
 *
 * ## 为什么单独成类
 *
 * 这一簇原来是 `RelayControllerAgentInvokeComponent`（1557 行）的头尾两段 —— 发起方
 * 的登记/发帧在前，巡检与超时卡在后，中间夹着入站帧分发与 intent 路由。读过它的人
 * 都要来回翻 500 行才知道「一条超时卡是谁发的」。搬到一起之后，租约的一生
 * （register → markDone/markFailed → markTimeoutDue → prune）在一个文件里从头读到尾。
 *
 * ## 与卡片动作域的分工
 *
 * 本类**只判租约状态**，不碰卡片的 rev 与落库：翻卡一律经 [broadcastCard] 交给
 * `RelayControllerAgentCardActions`（唯一持有 `cardBroadcastMutex` 的地方）。反过来
 * 卡片上的「重试 / 降级」按钮要重派任务，也回到本类的 [dispatchCrossHostInvoke] ——
 * 两边各持自己那一半状态，谁也不读对方的表。
 */
internal class RelayControllerAgentInvokeLeases(
    private val host: AgentDomainHost,
    /**
     * 执行器桥的**取值器**，不是值：壳层在 `start` 之前才装它（手机与未装配的宿主
     * 永远不装）。构造期拍进快照的话，本机执行那条路会永远看到 `null`，表现成
     * 「派给自己也说没有执行器」。
     */
    private val executorOf: () -> AgentInvokeExecutor?,
    /**
     * 翻卡出口（`suspend` 取值器）：卡片动作域的实现，rev 串行分配在那一侧。
     */
    private val broadcastCard: suspend (groupId: String, agentId: String, card: JsonObject) -> Unit,
) {

    private val leases = AgentInvokeLeaseTable { nowMillis() }

    /**
     * 租约表的**现读视图**：卡片动作域按 `invoke-<id>` 反查 lease 来做重试/降级。
     * 表本身不外借（写入口只在本类），这里给出的是同一个对象 —— 它只暴露查询与
     * 状态迁移方法，没有「从外面塞一条租约」的口子。
     */
    internal val leaseTable: AgentInvokeLeaseTable get() = leases

    // ---------------------------------------------------------------- 派单（发起方）

    /**
     * 把一个子任务派给 [agentId] 的宿主（跨端或本机）。
     *
     * @return invokeId；null = 群里没有这个 agent 的声明（派单无从谈起）。
     * 目标不接派单（不声明 `agi1`）**不**在这里拦 —— 派单照发、租约照走：
     * 旧端会 ACK 后丢弃，租约到期给出如实的超时卡，比发送侧静默猜强。
     */
    fun dispatchCrossHostInvoke(
        groupId: String,
        agentId: String,
        prompt: String,
        wsId: String?,
        deadlineMs: Long = RelayControllerAgentInvokeComponent.DEFAULT_INVOKE_DEADLINE_MS,
    ): String? {
        val st = host.state.value
        val meta = st.settings.groups.firstOrNull { it.groupId == groupId } ?: return null
        val seat = meta.virtualAgents.orEmpty().firstOrNull { it.agentId == agentId } ?: return null
        val self = st.selfNodeId
        val rt = host.runtime ?: return null
        val invokeId = newId()

        if (seat.hostNodeId == self) {
            // 目标就是本机：不过网道，直接本机执行（卡片照常进群 —— 这不是跨端交接）。
            leases.register(invokeId, groupId, agentId, seat.name, self, prompt, wsId, deadlineMs)
            mirrorLeases()
            host.scope.launch {
                executeLocallyAndMark(invokeId, agentId, prompt, wsId, cardGroupId = groupId)
            }
            return invokeId
        }

        // 跨端派单：登记租约（先记账再发帧 —— 发送失败也有一条可超时的租约，而不是无声消失）。
        leases.register(invokeId, groupId, agentId, seat.name, seat.hostNodeId, prompt, wsId, deadlineMs)
        mirrorLeases()
        host.scope.launch {
            runCatching {
                rt.sendAgentInvoke(
                    peerId = seat.hostNodeId,
                    invokeId = invokeId,
                    op = com.kapp.tenon.core.protocol.CodecInvoke.InvokeOps.TASK,
                    agentId = agentId,
                    requestedBy = self,
                    deadlineMs = deadlineMs,
                    prompt = prompt,
                    groupId = groupId,
                    groupRev = meta.rev,
                    wsId = wsId,
                )
            }
        }
        return invokeId
    }

    /** 入站结果帧（B → A）：租约落终态 + Leader 拼看板卡上群（§6.2 的唯一一张卡）。 */
    fun handleOnAgentInvokeResult(envelope: Envelope) {
        val p = envelope.payload as? JsonObject ?: return
        val invokeId = p.strField("invokeId") ?: return
        val ok = (p["ok"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: return
        val status = p.strField("status") ?: return
        val summary = p.strField("summary")
        val error = p.strField("error")
        val workerCard = p["card"] as? JsonObject
        val lease = when {
            ok -> leases.markDone(invokeId, summary, error)
            else -> leases.markFailed(invokeId, status, summary, error)
        } ?: return
        // 结果必须来自当初的承接节点：别的节点（或重放）冒名报结果，直接忽略。
        if (envelope.from != lease.targetNode) return
        mirrorLeases()
        host.scope.launch {
            // 1:1 直派（groupId == null）没有群卡落点：结果翻租约镜像即可。
            lease.groupId?.let { group ->
                broadcastCard(
                    group,
                    lease.agentId,
                    buildInvokeBoardCard(
                        invokeId = lease.invokeId,
                        prompt = lease.prompt,
                        agentName = lease.agentName,
                        state = lease.state,
                        timeoutTitle = timeoutTitleOf(lease),
                        retryLabel = retryLabel(),
                        fallbackLabel = fallbackLabel(),
                        summary = summary,
                        error = error,
                        workerCard = workerCard,
                    ),
                )
            }
        }
    }

    /** 本机执行（派单目标 = 自己，以及卡片上的「降级本机」）：终态翻租约、卡片进群。 */
    internal suspend fun executeLocallyAndMark(
        invokeId: String,
        agentId: String,
        prompt: String,
        wsId: String?,
        /** 看板卡的落点群；null = 1:1 直派，没有群卡落点，反馈面是租约镜像本身。 */
        cardGroupId: String?,
    ) {
        val ex = executorOf()
        if (ex == null) {
            leases.markFailed(invokeId, com.kapp.tenon.core.protocol.CodecInvoke.InvokeResultStates.NO_EXECUTOR, null, "no local executor")
            mirrorLeases()
            return
        }
        val outcome = try {
            ex.execute(AgentInvokeTaskRequest(agentId = agentId, prompt = prompt, wsId = wsId, groupId = cardGroupId))
        } catch (t: Throwable) {
            leases.markFailed(
                invokeId, com.kapp.tenon.core.protocol.CodecInvoke.InvokeResultStates.ERROR, null,
                (t.message ?: "executor failed").take(Limits.AGENT_NOTE),
            )
            mirrorLeases()
            null
        } ?: return
        val done = outcome.status == com.kapp.tenon.core.protocol.CodecInvoke.InvokeResultStates.DONE
        if (done) leases.markDone(invokeId, outcome.summary, outcome.error)
        else leases.markFailed(invokeId, outcome.status, outcome.summary, outcome.error)
        mirrorLeases()
        outcome.card?.let { card -> cardGroupId?.let { broadcastCard(it, agentId, card) } }
    }

    /**
     * **1:1 直派**（无群容器）：目标与 agent 都显式给定，跳过群席位查找。
     *
     * 与 [dispatchCrossHostInvoke] 的分工：那条路绑定群（席位解析 + 看板卡上群）；
     * 这条路服务「跟某个宿主的单聊里派一单、指名工作区」的形态——租约照记（超时、
     * 镜像、终态翻转全都在），只是没有群卡广播的落点，反馈面是租约镜像本身。
     *
     * @return invokeId；null = 没有运行时（未连网），派不出去如实返回。
     */
    fun dispatchDirectInvoke(
        peerId: String?,
        agentId: String,
        prompt: String,
        wsId: String?,
        convId: String? = null,
        deadlineMs: Long = RelayControllerAgentInvokeComponent.DEFAULT_INVOKE_DEADLINE_MS,
    ): String? {
        val st = host.state.value
        val self = st.selfNodeId
        val target = peerId?.takeIf { it.isNotBlank() } ?: self
        // 本机执行不碰网道；跨端必须有运行时（没连网就派不出去，如实返回 null）。
        val runtime = host.runtime ?: return null
        val invokeId = newId()
        leases.register(
            invokeId = invokeId, groupId = null, agentId = agentId, agentName = agentId,
            targetNode = target, prompt = prompt, wsId = wsId, deadlineMs = deadlineMs,
        )
        mirrorLeases()
        host.scope.launch {
            if (target == self) {
                executeLocallyAndMark(invokeId, agentId, prompt, wsId, cardGroupId = null)
            } else {
                runCatching {
                    runtime.sendAgentInvoke(
                        peerId = target,
                        invokeId = invokeId,
                        op = com.kapp.tenon.core.protocol.CodecInvoke.InvokeOps.TASK,
                        agentId = agentId,
                        requestedBy = self,
                        deadlineMs = deadlineMs,
                        prompt = prompt,
                        groupId = null,
                        wsId = wsId,
                        convId = convId,
                    )
                }
            }
        }
        return invokeId
    }

    // ---------------------------------------------------------------- 租约 ticker（§7.3-1）

    /** 租约巡检协程；由 [RelayController.start] 挂进 jobs（stop 随之取消）。 */
    fun startLeaseTicker() {
        host.jobs += host.scope.launch {
            while (isActive) {
                delay(LEASE_TICK_MS)
                tickTimeouts()
            }
        }
    }

    private suspend fun tickTimeouts() {
        val flipped = leases.markTimeoutDue()
        if (flipped.isEmpty()) return
        mirrorLeases()
        for (lease in flipped) {
            // 1:1 直派（groupId == null）超时只翻租约镜像，没有群卡可上。
            lease.groupId?.let { group ->
                broadcastCard(
                    group,
                    lease.agentId,
                    buildInvokeBoardCard(
                        invokeId = lease.invokeId,
                        prompt = lease.prompt,
                        agentName = lease.agentName,
                        state = InvokeLeaseStates.TIMEOUT,
                        timeoutTitle = timeoutTitleOf(lease),
                        retryLabel = retryLabel(),
                        fallbackLabel = fallbackLabel(),
                        summary = null,
                        error = null,
                        workerCard = null,
                    ),
                )
            }
        }
        leases.pruneTerminalOlderThan(LEASE_TTL_MS)
    }

    private suspend fun timeoutTitleOf(lease: AgentInvokeLease): String =
        runCatching { host.uiTexts.invokeTimeoutTitle(lease.agentName) }.getOrNull().orEmpty()

    private suspend fun retryLabel(): String =
        runCatching { host.uiTexts.invokeActionRetryLabel() }.getOrNull().orEmpty()

    private suspend fun fallbackLabel(): String =
        runCatching { host.uiTexts.invokeActionFallbackLabel() }.getOrNull().orEmpty()

    private fun mirrorLeases() {
        val snap = leases.snapshot().associateBy { it.invokeId }
        host.updateAgentState { it.copy(agentInvokes = snap) }
    }

    private companion object {
        /** 租约巡检间隔。1s 的粒度让超时卡的迟到上限可预期（deadline + 1s）。 */
        const val LEASE_TICK_MS: Long = 1_000L

        /** 终态租约的保留时长：超时卡随时可能被点重试，太早清掉就没法重派。 */
        const val LEASE_TTL_MS: Long = 60L * 60_000
    }
}
