// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.chat

import com.kapp.tidings.domain.model.MessageUi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 声明式卡片渲染引擎的**判据**（方案 §4.3 / §5.3 / §7.3，M3 交付 1）。
 *
 * 三条不变量，各对应一次真机级别的代价：
 * 1. **解析是宽容的、身份是严格的**：未知字段忽略（卡片体是插件定义的，协议只管字节预算），
 *    但没有 `cardId` / `rev` 的对象不是一张卡 —— 没有身份键就无法折叠，渲染它等于放弃原位刷新。
 * 2. **折叠只认 (cardId, rev)**：卡片更新以新消息进流（追加式新行，方案 §5.3 定死），
 *    「不刷屏」是 UI 语义 —— 按 cardId 只渲染最新 rev，旧 rev 收起（不渲染）。
 * 3. **审批锁的三态**：可点 → 本机乐观置灰（等待宿主确认）→ 宿主判定后全员显示批准人。
 *    第二态是 M3 的全部：跨端 ack 广播 M4 才接，这里把 disabled 语义钉死。
 */
class AgentCardRenderTest {

    private fun card(
        cardId: String = "task-89",
        rev: Int = 3,
        title: String? = "重构网络传输层",
        progress: Int? = 70,
        withSteps: Boolean = true,
        withActions: Boolean = true,
        decidedBy: String? = null,
    ): JsonObject = buildJsonObject {
        put("type", "team-task-board")
        put("version", 1)
        put("cardId", cardId)
        put("rev", rev)
        if (title != null) put("title", title)
        if (progress != null) put("progress", progress)
        if (withSteps) {
            put(
                "items",
                Json.parseToJsonElement(
                    """[
                      {"id":"1","label":"协议定义更新","status":"done","assignee":"@architect"},
                      {"id":"2","label":"核心通道编写","status":"running","assignee":"@coder"},
                      {"id":"3","label":"回归测试校验","status":"pending","assignee":"@tester"}
                    ]""",
                ),
            )
        }
        if (withActions) {
            put(
                "actions",
                Json.parseToJsonElement(
                    """[
                      {"actionId":"pause-task","label":"暂停任务","style":"secondary"},
                      {"actionId":"approve","label":"批准","style":"primary","approval":true}
                    ]""",
                ),
            )
        }
        if (decidedBy != null) {
            put("decidedBy", decidedBy)
            put("decidedActionId", "approve")
        }
    }

    private fun message(id: String, cardJson: JsonObject?, at: Long = 1_000L) = MessageUi(
        id = id,
        peerId = "group:g-1",
        mine = false,
        text = "",
        at = at,
        card = cardJson,
    )

    // ── 解析 ─────────────────────────────────────────────────────────────

    @Test
    fun parsesTheSpecShape() {
        val card = assertNotNull(parseAgentCard(card()), "§4.3 的样例形状必须解析出一张卡")
        assertEquals("task-89", card.cardId)
        assertEquals(3, card.rev)
        assertEquals("重构网络传输层", card.title)
        assertEquals(70, card.progress)
        assertEquals(3, card.steps.size)
        assertEquals(AgentCardStepStatus.Done, card.steps[0].status)
        assertEquals(AgentCardStepStatus.Running, card.steps[1].status)
        assertEquals(AgentCardStepStatus.Pending, card.steps[2].status)
        assertEquals("@coder", card.steps[1].assignee)
        assertEquals(2, card.actions.size)
        assertTrue(card.actions[1].approval, "审批按钮必须带 approval 标记（§5.3 的高危审批）")
    }

    @Test
    fun unknownFieldsAreIgnoredButIdentityIsStrict() {
        // 未知字段忽略：卡片体是插件定义的，渲染引擎不因多了一个键而整卡拒收。
        val extra = Json.parseToJsonElement(
            """{"cardId":"c1","rev":1,"title":"t","customFutureField":{"x":1}}""",
        ) as JsonObject
        val card = assertNotNull(parseAgentCard(extra))
        assertEquals("t", card.title)

        // 没有 cardId：无法折叠 ⇒ 不是一张卡。
        assertNull(parseAgentCard(Json.parseToJsonElement("""{"rev":1}""") as JsonObject))
        // 没有 rev：原位刷新的序断了 ⇒ 不是一张卡（宁可少一张卡，不渲染一张永远不更新的）。
        assertNull(parseAgentCard(Json.parseToJsonElement("""{"cardId":"c1"}""") as JsonObject))
        // 空对象与 null 同档。
        assertNull(parseAgentCard(Json.parseToJsonElement("""{}""") as JsonObject))
        assertNull(parseAgentCard(null))
    }

    @Test
    fun stepStatusesCoverTheFiveKnownValuesAndFallBackToPending() {
        val json = Json.parseToJsonElement(
            """{"cardId":"c","rev":1,"items":[
                {"id":"1","label":"a","status":"done"},
                {"id":"2","label":"b","status":"running"},
                {"id":"3","label":"c","status":"pending"},
                {"id":"4","label":"d","status":"failed"},
                {"id":"5","label":"e","status":"blocked"},
                {"id":"6","label":"f","status":"some-future-status"}
            ]}""",
        ) as JsonObject
        val card = assertNotNull(parseAgentCard(json))
        assertEquals(
            listOf(
                AgentCardStepStatus.Done,
                AgentCardStepStatus.Running,
                AgentCardStepStatus.Pending,
                AgentCardStepStatus.Failed,
                AgentCardStepStatus.Blocked,
                // 未知的未来状态退到待开始：显示成"还没轮到它"比显示成空洞诚实。
                AgentCardStepStatus.Pending,
            ),
            card.steps.map { it.status },
        )
    }

    // ── 折叠（原位刷新 = 按 cardId 只渲染最新 rev）────────────────────────

    @Test
    fun onlyTheNewestRevOfEachCardIdIsRendered() {
        val messages = listOf(
            message("m1", card(rev = 1), at = 1_000L),
            message("m2", card(rev = 2), at = 2_000L),
            message("m3", card(rev = 3), at = 3_000L),
            message("m4", card(cardId = "task-77", rev = 1), at = 4_000L),
            message("m5", null, at = 5_000L),
        )
        val visible = latestRevCardMessageIds(messages)
        assertEquals(setOf("m3", "m4"), visible, "同 cardId 只留最新 rev；别的消息不受影响")
    }

    @Test
    fun equalRevsBreakTheTieByArrivalOrderNotByTimestamp() {
        // 落库时刻（at）与信封 ts 是两件事（AGENTS §3 的教训）：同 rev 撞车时按**到达序**
        // 取最后一条 —— 库里追加式的最后一行就是最新事实，不赌时间戳。
        val messages = listOf(
            message("early", card(rev = 3), at = 9_000L),
            message("late", card(rev = 3), at = 1_000L),
        )
        assertEquals(setOf("late"), latestRevCardMessageIds(messages))
    }

    @Test
    fun foldSurvivesUnsortedInput() {
        // 消息流按会话分页读回，顺序不保证全局有序；折叠必须自己找最新 rev，
        // 不能依赖入参已排序（那是把判据建立在调用方的好习惯上）。
        val messages = listOf(
            message("m3", card(rev = 3), at = 3_000L),
            message("m1", card(rev = 1), at = 1_000L),
            message("m2", card(rev = 2), at = 2_000L),
        )
        assertEquals(setOf("m3"), latestRevCardMessageIds(messages))
    }

    // ── 审批锁三态 ───────────────────────────────────────────────────────

    @Test
    fun editableByDefault() {
        val card = assertNotNull(parseAgentCard(card()))
        assertEquals(
            CardActionUi(CardActionLock.Editable),
            cardActionUiOf(card, pendingActions = emptySet(), actionId = "approve"),
        )
    }

    @Test
    fun localClickGreysTheButtonBeforeTheHostConfirms() {
        val card = assertNotNull(parseAgentCard(card()))
        val ui = cardActionUiOf(card, pendingActions = setOf("task-89|approve"), actionId = "approve")
        assertEquals(CardActionLock.PendingConfirm, ui.lock, "本机点击乐观置灰（§5.3）")
        assertTrue(ui.disabled)
        // 同卡片的**另一个**按钮不受影响：乐观锁按 (cardId, actionId) 记账，不整卡锁死。
        assertEquals(
            CardActionUi(CardActionLock.Editable),
            cardActionUiOf(card, pendingActions = setOf("task-89|approve"), actionId = "pause-task"),
        )
    }

    @Test
    fun hostVerdictOverridesEverything() {
        val decided = assertNotNull(parseAgentCard(card(decidedBy = "Alice")))
        // 宿主判定已到：无论本机有没有 pending，按钮都进终态并显示批准人。
        val ui = cardActionUiOf(decided, pendingActions = setOf("task-89|approve"), actionId = "approve")
        assertEquals(CardActionLock.Decided, ui.lock)
        assertTrue(ui.disabled)
        assertEquals("Alice", ui.decidedBy)
        // 宿主判定的是 approve，别的按钮不背这口锅（M4 的防重语义在 UI 上的投影）。
        assertEquals(
            CardActionLock.Editable,
            cardActionUiOf(decided, pendingActions = emptySet(), actionId = "pause-task").lock,
        )
    }

    @Test
    fun decidedBeatsPendingWhenBothPresent() {
        // 两端同时点击的现场：本机自己的 pending 还没清，宿主判定先到 ——
        // 判定优先（它携带批准人，是更强的证据）。
        val decided = assertNotNull(parseAgentCard(card(decidedBy = "Alice")))
        assertEquals(
            CardActionLock.Decided,
            cardActionUiOf(decided, pendingActions = setOf("task-89|approve"), actionId = "approve").lock,
        )
    }

    @Test
    fun nonApprovalActionsRenderAsPlainEntries() {
        val card = assertNotNull(parseAgentCard(card()))
        assertFalse(card.actions[0].approval, "style=secondary 的暂停任务不是审批")
        assertTrue(card.actions[1].approval)
    }
}
