// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.domain.RelayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * **产物与卡片投递**：本机产出的文件、以及要推到群里的卡片，都从这里出去。
 *
 * 三个方法的共同点：**失败只影响「看不看得见」，不影响活干没干** —— 所以它们一律
 * `runCatching` + 留痕，绝不把异常抛回执行路径。正因为失败是静默的（用户只说
 * 「卡没出现」「文件没收到」），这三条才值得从装配代码里单独拎出来看。
 *
 * 抽出来的第二笔理由：它们都读「当前工单编排器」（滞留卡的判据是「编排器认不认得这张卡」），
 * 而那是随挂载变化的 --- 所以拿取值器，不拿副本（同 [AgentEventForwarder] 的理由）。
 */
internal class ArtifactDelivery(
    private val controller: RelayController,
    private val scope: CoroutineScope,
    /** 当前工单编排器；未挂为 null。 */
    private val tickets: () -> DesktopWorkerOrchestrator?,
) {

    /** 产物投递（T-G1/E1 共用）：本机文件 → 派单来源的群（扇出在域层）。 */
    internal fun deliverCliArtifacts(
        artifacts: List<Triple<String, String, Long>>,
        deliverGroupId: String?,
    ) {
        if (deliverGroupId == null) return
        artifacts.forEach { (path, name, bytes) ->
            runCatching {
                // purpose=artifact：对端免确认收下。任务卡才是决策面，「接收/拒绝」
                // 对随附的补丁/报告是一个多余的决策点（产物双路问题的另一半）。
                controller.sendAgentArtifacts(
                    com.kapp.tenon.core.protocol.ConvId.group(deliverGroupId),
                    listOf(com.kapp.tenon.core.transport.LocalFile(path = path, name = name, size = bytes)),
                )
            }.onFailure { t ->
                RelayLog.info("RELAY-INVOKE", "artifact delivery failed for " + name + ": " + (t.message ?: (t::class.simpleName ?: "unknown")))
            }
        }
    }

    /**
     * 把驱动刚发的一张卡**立刻**推进群，不占用 invoke 的返回值通道。
     *
     * 为什么不是等 `done(summary, card)`：那条路要等任务完全结束。执行中的状态
     * 只有在执行中发出去才有意义。域层的 `broadcastBoardCard` 负责「同 cardId
     * 本机落库重写 + 远端入站对称替换」，所以受理卡与终态卡会合并成一张。
     *
     * 投不出去不吞：广播失败只影响「看不看得见」，任务本身照跑，但必须留痕 ——
     * 否则下次又会以「卡片没出现」的形式重新查一遍。
     */
    internal fun publishCardNow(groupId: String?, agentId: String, cardJson: String?) {
        if (groupId.isNullOrBlank() || cardJson.isNullOrBlank()) return
        scope.launch {
            runCatching { controller.broadcastChainCard(groupId, agentId, cardJson) }
                .onFailure { t ->
                    RelayLog.info("RELAY-INVOKE", "card broadcast failed: " + (t.message ?: t::class.simpleName))
                }
        }
    }

    /**
     * 滞留工单卡的如实翻面（D-NEW-1）。
     *
     * 动作帧到达时，工单可能已经不在编排器登记簿里 —— 宿主**重启**丢了内存态，
     * 或工单**早已收尾**被移出。这时宿主不可能再为这个按钮做任何事，但手机上
     * 那张卡还挂着按钮和进行中的状态：点了毫无反应，却显示「已由 <设备> 处理」，
     * 比无反应更误导（真机实锤：旧点将卡的「取消」点着，宿主日志里只有一行
     * nothing-to-cancel，卡面纹丝不动）。
     *
     * 处理：按 cardId 反查本机消息行拿到会话（与合并冲突卡同一条权威路径），
     * 用 [BoardCardFlip.stale] 在**原卡**上翻成「已停止」并摘掉按钮，横幅说明原因。
     * **只在编排器确实不认得这张卡时才翻** —— 各动作分支自己的 false 可能只是
     * 时序竞争（倒计时抢先开工），真实状态卡马上就到，翻「失效」反而是说谎。
     */
    internal fun flipStaleChainCard(cardId: String, agentId: String) {
        if (!cardId.startsWith("chain-")) return
        if (tickets()?.hasTicketForCard(cardId) == true) return
        val row = controller.state.value.messages.values
            .asSequence()
            .flatten()
            .lastOrNull { (it.renderCard?.get("cardId") as? JsonPrimitive)?.contentOrNull == cardId }
        val groupId = row?.peerId
            ?.let(com.kapp.tenon.core.protocol.ConvId::groupIdOf)
        if (groupId == null) {
            RelayLog.info("RELAY-INVOKE", "stale card action has no conversation to flip on (card " + cardId + ")")
            return
        }
        val staleCard = BoardCardFlip.stale(
            cardId = cardId,
            base = row.renderCard,
            hint = "该工单已结束或宿主已重启，此操作未生效",
        ).toString()
        publishCardNow(groupId, agentId, staleCard)
        RelayLog.info("RELAY-INVOKE", "stale card flipped to aborted (card " + cardId + ")")
    }
}
