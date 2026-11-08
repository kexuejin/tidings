// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.chat

import com.kapp.tenon.domain.model.MessageUi
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
        assertNull(card.elapsedSec, "样例卡没有耗时段，解析不得编造")
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

    // ── 结构化 Acceptance 验收契约解析（Phase 1）─────────────────────────

    @Test
    fun parsesChecksAndFallsBackToItems() {
        val withChecksJson = Json.parseToJsonElement(
            """{
                "cardId":"task-check",
                "rev":1,
                "checks":[
                    {"id":"c1","label":"Frame budget check","status":"done","time":"0.6s","observation":"under 64 KiB"},
                    {"id":"c2","label":"Unit tests","status":"running","drawer":"Stacktrace at line 42","open":true}
                ]
            }""",
        ) as JsonObject
        val cardWithChecks = assertNotNull(parseAgentCard(withChecksJson))
        assertEquals(2, cardWithChecks.checks.size)
        assertEquals("0.6s", cardWithChecks.checks[0].time)
        assertEquals("under 64 KiB", cardWithChecks.checks[0].observation)
        assertEquals(AgentCardStepStatus.Done, cardWithChecks.checks[0].status)
        assertEquals("Stacktrace at line 42", cardWithChecks.checks[1].drawer)
        assertTrue(cardWithChecks.checks[1].open)
        assertEquals(AgentCardStepStatus.Running, cardWithChecks.checks[1].status)

        // items 回退到 checks
        val withItemsJson = Json.parseToJsonElement(
            """{
                "cardId":"task-legacy",
                "rev":1,
                "items":[
                    {"id":"1","label":"Legacy step","status":"done","assignee":"@worker"}
                ]
            }""",
        ) as JsonObject
        val cardWithItems = assertNotNull(parseAgentCard(withItemsJson))
        assertEquals(1, cardWithItems.checks.size)
        assertEquals("Legacy step", cardWithItems.checks[0].label)
        assertEquals("@worker", cardWithItems.checks[0].observation)
        assertEquals(AgentCardStepStatus.Done, cardWithItems.checks[0].status)
    }

    @Test
    fun parsesBotChipsContextCmdDiffHunkAndSubActions() {
        val json = Json.parseToJsonElement(
            """{
                "cardId":"task-full",
                "rev":2,
                "bot":{"name":"Claude Code","avatar":"bolt","branch":"worktree: #feat-1","bdot":"run"},
                "chips":[
                    {"text":"+42 / −7 lines","tone":"pri"},
                    {"text":"zero breaking","tone":"ok"}
                ],
                "context":"Optimize frame budget and guard against packet overflow",
                "cmd":"rm -rf build/",
                "diff":{
                    "path":"core/transport/NodeOutbound.kt",
                    "additions":42,
                    "deletions":7,
                    "note":"Under 32 KiB budget",
                    "hunk":["@@ -45,7 +45,8 @@","-old line","+new line"],
                    "open":true
                },
                "subActions":[
                    {"actionId":"session-exempt","label":"Session Exempt"}
                ]
            }""",
        ) as JsonObject
        val card = assertNotNull(parseAgentCard(json))
        assertNotNull(card.bot)
        assertEquals("Claude Code", card.bot?.name)
        assertEquals("bolt", card.bot?.avatar)
        assertEquals("worktree: #feat-1", card.bot?.branch)
        assertEquals("run", card.bot?.bdot)

        assertEquals(2, card.chips.size)
        assertEquals("+42 / −7 lines", card.chips[0].text)
        assertEquals("pri", card.chips[0].tone)
        assertEquals("zero breaking", card.chips[1].text)
        assertEquals("ok", card.chips[1].tone)

        assertEquals("Optimize frame budget and guard against packet overflow", card.context)
        assertEquals("rm -rf build/", card.cmd)

        assertNotNull(card.diff)
        assertEquals("core/transport/NodeOutbound.kt", card.diff?.path)
        assertEquals(42, card.diff?.additions)
        assertEquals(7, card.diff?.deletions)
        assertEquals("Under 32 KiB budget", card.diff?.note)
        assertEquals(listOf("@@ -45,7 +45,8 @@", "-old line", "+new line"), card.diff?.hunk)
        assertTrue(card.diff?.open == true)

        assertEquals(1, card.subActions.size)
        assertEquals("session-exempt", card.subActions[0].actionId)
        assertEquals("Session Exempt", card.subActions[0].label)
    }

    @Test
    fun malformedNestedStructuresNeverThrow() {
        // 计划 §三.1：卡片体是插件定义的，任何嵌套结构畸形都必须安全回退，
        // 不许抛异常把消息流渲染炸掉 —— 未知形状的卡宁可显示成最朴素的卡。
        fun raw(s: String): JsonObject = Json.parseToJsonElement(s) as JsonObject

        // bot 畸形：name 缺失 → 整个 bot 丢弃（有 avatar 没 name 不是工牌）
        val badBot = parseAgentCard(raw("""{"cardId":"b1","rev":1,"bot":{"avatar":"x"}}"""))
        assertEquals(null, badBot?.bot)

        // bot 畸形：整块是数组而不是对象 → 忽略
        val botArray = parseAgentCard(raw("""{"cardId":"b2","rev":1,"bot":["x"]}"""))
        assertEquals(null, botArray?.bot)

        // chips 畸形：元素缺 text → 单条丢弃；整块是标量 → 空清单
        val badChips = parseAgentCard(raw("""{"cardId":"c1","rev":1,"chips":[{"tone":"pri"}]}"""))
        assertEquals(0, badChips?.chips?.size)
        val scalarChips = parseAgentCard(raw("""{"cardId":"c2","rev":1,"chips":"x"}"""))
        assertEquals(0, scalarChips?.chips?.size)

        // diff 畸形：hunk 元素混入对象 → 只留原始行；整块是数组 → diff 丢弃
        val mixedHunk = parseAgentCard(
            raw("""{"cardId":"d1","rev":1,"diff":{"path":"a.kt","hunk":["-a",{"x":1},"+b"]}}"""),
        )
        assertEquals(listOf("-a", "+b"), mixedHunk?.diff?.hunk)
        val diffArray = parseAgentCard(raw("""{"cardId":"d2","rev":1,"diff":["x"]}"""))
        assertEquals(null, diffArray?.diff)

        // checks 畸形：元素缺 label → 单条丢弃；items 元素不是对象 → 忽略
        val badChecks = parseAgentCard(raw("""{"cardId":"e1","rev":1,"checks":[{"id":"c1"}]}"""))
        assertEquals(0, badChecks?.checks?.size)
        val badItems = parseAgentCard(raw("""{"cardId":"e2","rev":1,"items":["x",{}]}"""))
        assertEquals(0, badItems?.steps?.size)
        assertEquals(0, badItems?.checks?.size)

        // 顶层是数组 / 数字 / 字符串：不是对象 → 不是一张卡
        for (s in listOf("""["x"]""", """42""", """"card"""")) {
            val el = Json.parseToJsonElement(s)
            val r = parseAgentCard(el as? JsonObject)
            assertEquals(null, r)
        }

        // lastBeatTs 畸形：负值如实保留（前端自己判断过期），非数字 → null
        val beat = parseAgentCard(raw("""{"cardId":"f1","rev":1,"lastBeatTs":1700000000000}"""))
        assertEquals(1700000000000L, beat?.lastBeatTs)
        val badBeat = parseAgentCard(raw("""{"cardId":"f2","rev":1,"lastBeatTs":"soon"}"""))
        assertEquals(null, badBeat?.lastBeatTs)
    }

    @Test
    fun formatsContextAnnotatedStringWithBoldAndCodeTags() {
        val raw = "<b>执行意图</b>：修复问题。<br/>运行 <code>./gradlew check</code>。"
        val annotated = formatContextAnnotated(raw)
        assertEquals("执行意图：修复问题。\n运行 ./gradlew check。", annotated.text)
        assertTrue(annotated.spanStyles.isNotEmpty(), "应包含 SpanStyle 格式化")
    }
}
