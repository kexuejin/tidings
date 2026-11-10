// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.ConvId
import com.kapp.tenon.domain.model.ScreenRecordUi
import com.kapp.tenon.platform.InMemoryAppPlatform
import com.kapp.tenon.testutil.awaitUntil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive

/**
 * **卡片动作的资格判定在宿主侧**（设计稿 v2 §4.8，S4-3）。
 *
 * ## 现场
 *
 * `/v1/plugin/action` 是「宿主 ↔ 本机插件」这一跳，按回环信任；而「**群里哪个人**
 * 有资格驱动我的插件」是另一跳，此前**没有判**：`handleHostCardAction` 只看
 * `decidedBy == envelope.from`（防冒名），于是任何群成员都能点别人卡片上的审批 /
 * 暂停按钮，宿主照单全收。
 *
 * §4.8 的口径：宿主机主 = 全部动作；过 `AgentExecAuthorizer` 的成员 = 可点；
 * 其余 = 只读。判定**必须在宿主侧** —— 资格台账在宿主手上，客户端不知道远端资格。
 *
 * ## 被拒时不许沉默
 *
 * 「静默丢弃」等于把「点了没反应」换个地方发生（M3 已裁决：没有通路的按钮比没有更糟）。
 * 所以宿主被拒时回一条 `agentState=failed` 的轻横幅，指名「谁被拒了、驱动的是谁」——
 * 复用 S4-4 的同一条通路，不新增协议键。
 *
 * ## 一个如实写下的边界
 *
 * 客户端**算不出**远端宿主的资格（台账不在它手上），所以按钮照画；真正的门在宿主这里。
 * 这不是妥协，是 §4.8「判定在宿主侧」的直接后果。
 */
class CardActionAuthorizationTest {

    private val scope = CoroutineScope(Job())

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

    private val phoneNode = "22222222-2222-4222-8222-222222222222"
    private val strangerNode = "33333333-3333-4333-8333-333333333333"

    private object Texts : RelayUiTexts {
        override suspend fun screenCardText(record: ScreenRecordUi): String = ""
        override suspend fun nudgeNotice(): String = ""
        override suspend fun workspaceGreeting(agentKind: String, workspaceTitle: String): String = ""
        override suspend fun workspaceSessionBrief(sessionTitle: String, busy: Boolean): String = ""
        override suspend fun invokeTimeoutTitle(agentName: String): String = ""
        override suspend fun invokeActionRetryLabel(): String = ""
        override suspend fun invokeActionFallbackLabel(): String = ""
        override suspend fun tagNoMatchTitle(tag: String): String = ""
        override suspend fun agentUnavailableNotice(agentName: String): String = ""
        override suspend fun agentNotCompletedNotice(agentName: String): String = ""
        override suspend fun agentActionDeniedNotice(actor: String, agentName: String): String =
            "$actor denied for $agentName"
        override suspend fun agentEventThinkingOnlyFallback(): String = ""
    }

    /** 宿主（本控制器）托管 agent 的群；[members] 是其他成员。 */
    private fun hostedGroup(host: RelayController, members: List<String>): String {
        val groupId = host.createGroup("mesh", members)!!
        host.recordLocalWorkspace(
            wsId = "ws-1", wsTitle = "WS", wsPath = null,
            agentKey = null, agentKind = "gpu-runner", online = true,
        )
        host.handleSetGroupAgentWorkspaces(groupId, listOf("ws-1"))
        return groupId
    }

    /** 一张落到本机库里的待决卡片（宿主署名），动作是审批类。[senderAgentId] 可注入假名（链卡形态）。 */
    private fun seedPendingCard(host: RelayController, groupId: String, senderAgentId: String = "gpu-runner"): String = runBlocking {
        val card = kotlinx.serialization.json.buildJsonObject {
            put("cardId", "approval-1")
            put("rev", 1)
            put("title", "deploy to prod")
            put("banner", kotlinx.serialization.json.buildJsonObject {
                put("text", "needs approval")
                put("tone", "warn")
            })
            put(
                "actions",
                kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.buildJsonObject { put("actionId", "allow"); put("label", "Allow"); put("approval", true) })
                    add(kotlinx.serialization.json.buildJsonObject { put("actionId", "deny"); put("label", "Deny"); put("approval", true) })
                },
            )
        }
        // 直接落库（`broadcastBoardCard` 需要 runtime；未 start 的宿主没有，但卡片事实
        // 属于本机用户，落库不该依赖运行时 —— 与 S4-4 的 broadcastAgentState 同一条纪律）。
        host.handleAppendMessage(
            com.kapp.tenon.domain.model.MessageUi(
                id = "card-msg-1",
                peerId = ConvId.group(groupId),
                mine = false,
                text = "deploy to prod",
                at = com.kapp.tenon.core.nowMillis(),
                senderId = com.kapp.tenon.core.protocol.AgentSenderId.of(host.state.value.selfNodeId, senderAgentId),
                card = card,
            ),
            incoming = true,
        )
        "approval-1"
    }

    private fun RelayController.rows(groupId: String) = state.value.messages[ConvId.group(groupId)].orEmpty()

    private fun JsonObject.strOf(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content

    @Test
    fun aMemberWithoutGrantCannotDriveTheCard() = runBlocking {
        val host = controller()
        val groupId = hostedGroup(host, listOf(phoneNode, strangerNode))
        host.uiTexts = Texts
        // 只有 phone 被批过执行权；stranger 在群里但没资格。
        host.invokeGrantAllows = { _, node -> node == phoneNode }
        var pluginCalls = 0
        host.invokeCtl.onHostCardAction = { _, _, _, _, _ -> pluginCalls++ }
        val cardId = seedPendingCard(host, groupId)
        val revBefore = host.rows(groupId).count()

        host.invokeCtl.handleHostCardAction(groupId, "gpu-runner", cardId, "allow", strangerNode)

        // 1) 插件一次都不许被调。
        assertEquals(0, pluginCalls, "无资格者的点击不许到达插件 —— 这是 §4.8 的门")
        // 2) 卡片不许出现「已决」的 rev+1（假决定比不决定更糟）。
        assertTrue(
            host.rows(groupId).none { it.card?.strOf("decidedBy") != null },
            "被拒的点击不许把卡片写成已决",
        )
        // 3) 但不许沉默：群里要出现一条指名道姓的拒绝反馈。
        awaitUntil("被拒时群流有反馈，不是静默") { host.rows(groupId).size > revBefore }
        val feedback = host.rows(groupId).last()
        assertEquals("failed", feedback.agentState, "拒绝反馈走 agentState=failed 轻横幅（S4-4 同一条通路）")
        assertTrue(feedback.text.contains(strangerNode) || feedback.text.contains("denied"), "反馈要点名：谁被拒了")
        host.stop()
    }

    @Test
    fun aGrantedMemberCanDriveTheCard() = runBlocking {
        val host = controller()
        val groupId = hostedGroup(host, listOf(phoneNode, strangerNode))
        host.uiTexts = Texts
        host.invokeGrantAllows = { _, node -> node == phoneNode }
        var pluginCalls = 0
        host.invokeCtl.onHostCardAction = { _, _, _, _, decidedBy ->
            assertEquals(phoneNode, decidedBy, "判定人必须是点击者本人")
            pluginCalls++
        }
        val cardId = seedPendingCard(host, groupId)

        host.invokeCtl.handleHostCardAction(groupId, "gpu-runner", cardId, "allow", phoneNode)

        assertEquals(1, pluginCalls, "有资格的成员必须真的驱动到插件")
        assertEquals(
            phoneNode, host.invokeCtl.actionLedger.decidedBy(cardId, "allow"),
            "判定人要记成点击者本人（不是宿主）—— 群里显示的是「谁批的」",
        )
        host.stop()
    }

    @Test
    fun theOwnerIsAlwaysAllowed() = runBlocking {
        val host = controller()
        val groupId = hostedGroup(host, listOf(phoneNode))
        host.uiTexts = Texts
        // 台账为空：机主不需要任何批准。
        host.invokeGrantAllows = { _, _ -> false }
        var pluginCalls = 0
        host.invokeCtl.onHostCardAction = { _, _, _, _, _ -> pluginCalls++ }
        val cardId = seedPendingCard(host, groupId)

        host.invokeCtl.handleHostCardAction(groupId, "gpu-runner", cardId, "allow", host.state.value.selfNodeId)

        assertEquals(1, pluginCalls, "宿主机主对本机卡片永远有资格")
        host.stop()
    }

    @Test
    fun repeatedDeniedClicksDoNotFloodTheGroup() = runBlocking {
        val host = controller()
        val groupId = hostedGroup(host, listOf(phoneNode, strangerNode))
        host.uiTexts = Texts
        host.invokeGrantAllows = { _, node -> node == phoneNode }
        val cardId = seedPendingCard(host, groupId)
        val before = host.rows(groupId).size

        repeat(5) {
            host.invokeCtl.handleHostCardAction(groupId, "gpu-runner", cardId, "allow", strangerNode)
        }

        awaitUntil("拒绝反馈出现") { host.rows(groupId).size > before }
        assertEquals(
            1, host.rows(groupId).size - before,
            "同一个人对同一动作重复点只回一条 —— 否则任何人都能拿它刷屏",
        )
        host.stop()
    }

    /**
     * **编排器代发的卡必须有出口**（真机 2026-11-03，多棒链复验时发现）：
     *
     * 链卡署名 `tenon-chain`（DesktopStageChain），不是任何真实席位。按假名查
     * 台账/策略双双落空 → 掉进最严档 → 有资格的成员点「合并此补丁」也被拒，
     * 群里回「没有权限驱动 tenon-chain」，链的产出永远合不进工作区。
     *
     * 口径：认不出的 agentId 按**本机托管给这个群**的 agent 判资格（卡是这个群
     * 工作区的产物）；真实席位的行为一字不变（由上面三条用例钉着）。
     */
    @Test
    fun anOrchestratorIssuedCardIsDrivableThroughTheHostedAgent() = runBlocking {
        val host = controller()
        val groupId = hostedGroup(host, listOf(phoneNode, strangerNode))
        host.uiTexts = Texts
        // phone 对本机 agent（gpu-runner）有执行权 —— 与真机「Pixel 6 能 @codex 派单」同态。
        host.invokeGrantAllows = { agentId, node -> agentId == "gpu-runner" && node == phoneNode }
        var pluginCalls = 0
        host.invokeCtl.onHostCardAction = { _, _, _, _, _ -> pluginCalls++ }
        val cardId = seedPendingCard(host, groupId, senderAgentId = "tenon-chain")

        host.invokeCtl.handleHostCardAction(groupId, "tenon-chain", cardId, "allow", phoneNode)

        assertEquals(
            1, pluginCalls,
            "链卡的动作要按本机托管的 agent 判资格：能驱动 gpu-runner 的人就能点链卡",
        )
        host.stop()
    }

    @Test
    fun anOrchestratorIssuedCardStillDeniesMembersWithoutGrant() = runBlocking {
        val host = controller()
        val groupId = hostedGroup(host, listOf(phoneNode, strangerNode))
        host.uiTexts = Texts
        host.invokeGrantAllows = { agentId, node -> agentId == "gpu-runner" && node == phoneNode }
        var pluginCalls = 0
        host.invokeCtl.onHostCardAction = { _, _, _, _, _ -> pluginCalls++ }
        val cardId = seedPendingCard(host, groupId, senderAgentId = "tenon-chain")
        val before = host.rows(groupId).size

        host.invokeCtl.handleHostCardAction(groupId, "tenon-chain", cardId, "allow", strangerNode)

        assertEquals(0, pluginCalls, "兜底不放松资格：没权的人照旧被拒")
        awaitUntil("被拒要有反馈") { host.rows(groupId).size > before }
        host.stop()
    }
}
