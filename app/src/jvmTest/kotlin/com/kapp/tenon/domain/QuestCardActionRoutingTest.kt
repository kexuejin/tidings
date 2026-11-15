// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.ConvId
import com.kapp.tenon.platform.InMemoryAppPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 支线卡动作的宿主侧分流（真机 P1 现场的一个独立面）。
 *
 * ## 现场
 *
 * quest 卡的「一键合入主分支」与链/工单卡共用同一 actionId（`merge-patch`）。
 * `handleHostCardAction` 此前没有 quest 分支 —— 支线的批准合流一路 fall-through
 * 进通用 `DesktopPatchMerge.apply`，对支线产物只会 `already exists in working
 * directory`（quest 分支上已提交），questRunner.confirmMerge 永远不可达：
 * WAITING_INPUT → 机主确认 → COMPLETED 的循环整个是死的。真机 2026-11-15 实证
 * （quest q88026da8f64f 点合并，宿主日志只有 patch merge FAILED，rebase+ff 未执行）。
 *
 * ## 判据
 *
 * - quest 门（onQuestIntervention）吃下动作 ⇒ 通用插件路径一次都不许被调；
 * - quest 门返回 false（本机没有这条支线 = 链/工单卡复用同一 actionId）
 *   ⇒ 落回通用路径，行为与从前一字不差。
 *
 * **反向验证**：把 handleHostCardAction 里的 quest 分流摘掉，第一条用例立刻红
 * （intervention 不被调、插件路径反而被调）。
 */
class QuestCardActionRoutingTest {

    private val scope = CoroutineScope(Job())
    private val phoneNode = "22222222-2222-4222-8222-222222222222"

    private fun controller(): RelayController = RelayController(
        platform = InMemoryAppPlatform(),
        store = InMemorySettingsStore(
            RelaySettings.firstRun().copy(
                udpPort = 0, tcpPort = 0,
                persistMessages = false, persistMessagesDecided = true,
            ),
        ),
        messageStore = InMemoryMessageStore(),
        agentStore = InMemoryAgentStore(),
        scope = scope,
    )

    /** 一张落到本机库里的支线卡（宿主署名）。 */
    private fun seedQuestCard(host: RelayController, groupId: String, cardId: String) = runBlocking {
        host.handleAppendMessage(
            com.kapp.tenon.domain.model.MessageUi(
                id = "quest-card-" + cardId,
                peerId = ConvId.group(groupId),
                mine = false,
                text = "quest card",
                at = com.kapp.tenon.core.nowMillis(),
                senderId = com.kapp.tenon.core.protocol.AgentSenderId.of(host.state.value.selfNodeId, "agy"),
                card = buildJsonObject {
                    put("cardId", cardId)
                    put("rev", 1)
                    put("title", "quest title")
                },
            ),
            incoming = true,
        )
    }

    @Test
    fun questMergeApprovalReachesTheQuestGateNotTheGenericMerge() = runBlocking {
        val host = controller()
        val groupId = host.createGroup("mesh", listOf(phoneNode))!!
        val questId = "q88026da8f64f"
        seedQuestCard(host, groupId, questId)
        val interventions = mutableListOf<Pair<String, String>>()
        host.onQuestIntervention = { qId, actionId, _ ->
            interventions.add(qId to actionId)
            qId == questId
        }
        var genericCalls = 0
        host.invokeCtl.onHostCardAction = { _, _, _, _, _ -> genericCalls++ }

        host.invokeCtl.handleHostCardAction(
            groupId, "agy", questId,
            com.kapp.tenon.core.protocol.Limits.SYSTEM_ACTION_MERGE_PATCH,
            host.state.value.selfNodeId,
        )

        assertEquals(
            listOf(questId to com.kapp.tenon.core.protocol.Limits.SYSTEM_ACTION_MERGE_PATCH),
            interventions,
            "quest 卡的 merge-patch 必须进 quest 门（runner 的真合流），不是通用 git apply",
        )
        assertEquals(0, genericCalls, "quest 门吃下的动作不许再落到通用插件路径")
        host.stop()
    }

    @Test
    fun questRetryAndCancelAlsoRouteThroughTheQuestGate() = runBlocking {
        val host = controller()
        val groupId = host.createGroup("mesh", listOf(phoneNode))!!
        val questId = "qaaaaaaaaaaaa"
        seedQuestCard(host, groupId, questId)
        val seen = mutableListOf<String>()
        host.onQuestIntervention = { qId, actionId, _ ->
            seen.add(actionId); qId == questId
        }
        host.invokeCtl.onHostCardAction = { _, _, _, _, _ -> }

        host.invokeCtl.handleHostCardAction(groupId, "agy", questId, "retry-quest", host.state.value.selfNodeId)
        host.invokeCtl.handleHostCardAction(groupId, "agy", questId, "cancel-quest", host.state.value.selfNodeId)

        assertEquals(listOf("retry-quest", "cancel-quest"), seen, "retry/cancel 同走 quest 门")
        host.stop()
    }

    @Test
    fun nonQuestCardsWithTheSameActionIdKeepTheGenericPath() = runBlocking {
        val host = controller()
        val groupId = host.createGroup("mesh", listOf(phoneNode))!!
        val chainCardId = "chain-tmw79h7vw"
        seedQuestCard(host, groupId, chainCardId)
        var interventionCalls = 0
        host.onQuestIntervention = { _, _, _ ->
            interventionCalls++
            false // 本机没有这条支线（链卡不是 quest）
        }
        var genericCalls = 0
        host.invokeCtl.onHostCardAction = { _, _, _, _, _ -> genericCalls++ }

        host.invokeCtl.handleHostCardAction(
            groupId, "tenon-chain", chainCardId,
            com.kapp.tenon.core.protocol.Limits.SYSTEM_ACTION_MERGE_PATCH,
            host.state.value.selfNodeId,
        )

        assertEquals(1, interventionCalls, "quest 门被问到（返回 false 放行）")
        assertEquals(1, genericCalls, "quest 门放行后必须落回通用路径 —— 链卡行为与从前一字不差")
        host.stop()
    }

    @Test
    fun unwiredQuestGatePreservesTheLegacyBehavior() = runBlocking {
        // :cli 等无编排宿主：onQuestIntervention 恒 null —— 不得把 merge-patch 吞掉。
        val host = controller()
        val groupId = host.createGroup("mesh", listOf(phoneNode))!!
        seedQuestCard(host, groupId, "qbbbbbbbbbbbb")
        var genericCalls = 0
        host.invokeCtl.onHostCardAction = { _, _, _, _, _ -> genericCalls++ }

        host.invokeCtl.handleHostCardAction(
            groupId, "agy", "qbbbbbbbbbbbb",
            com.kapp.tenon.core.protocol.Limits.SYSTEM_ACTION_MERGE_PATCH,
            host.state.value.selfNodeId,
        )

        assertEquals(1, genericCalls, "quest 门未接线时 merge-patch 必须走原通用路径")
        host.stop()
    }
}
