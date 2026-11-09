// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **卡片家族规格扫描**（第八轮验收的用户提问：测试只见到了工单卡一种，原型稿里的
 * 卡片族谱没有逐个过）。这里把发射侧（orchestration / agentNode）**实际会发出的
 * 每一种卡片形态**，按其发射时的 JSON 形状（逐字对照 `StageChain.cardOf`、
 * `HealSteering.cardWithSteeringActions`、`DesktopWorkerWatchdog.withFailureCheck`、
 * `ArtifactPipe.upgradeArtifactsSelfDescribing`）喂进手机端解析器
 * [parseAgentCard]，钉住每一种形态在 UI 模型上必须可见的事实。
 *
 * ## 家族矩阵（发射方 → 断言）
 *
 * | 形态 | 发射处 | 本文件钉住 |
 * |---|---|---|
 * | 受理卡 proposed | `publishTicketCard(state="proposed")` + cardOf 尾部 proposed 分支 | actions 立即执行/取消（labelKey）、banner、bot.bdot=run |
 * | 原型待选 prototype | 同上 | banner + diff 预览可解析 |
 * | 执行中 working | 同上 | **elapsedSec 耗时段**、chips 第 N/M 棒、abort-task 动作、items detail |
 * | 终审合并 review_ready | 同上 + 自描述产物升级 | diff hunks、artifacts intent/delivered、门禁 chip、bdot=done |
 * | 受阻 blocked | 同上 | chips 受阻、bdot=fail |
 * | 放弃 aborted | 同上 | banner、bdot=fail |
 * | worker 崩溃检查项 | `withFailureCheck` | checks 里 failed + observation + drawer |
 * | 熔断卡（人工纠偏） | `HealSteering.cardWithSteeringActions` | 两个纠偏动作（重试输入槽 + 放弃保留现场） |
 * | 翻面知情行 | 合并/判定后 rev+1 | decidedBy + decidedActionId |
 * | 已合并 merged | 终态 | bdot=done（bdot 词表与原型一致） |
 *
 * ## 为什么在这一层钉
 *
 * 判据横跨「发射侧 JSON 形状 ↔ 手机端解析」两模块；真机逐形态驱动编排器成本是
 * 半天一个形态，而解析漏一个字段（如 elapsedSec 没接、drawer 没接）在真机上表现为
 * 「卡上少一块」，肉眼比对不可持续。这里把全部形态一次扫齐；发射侧形状若有漂移，
 * 本文件的 builder（逐字对照发射函数）会在评审时暴露。
 *
 * ## 反向验证
 *
 * 临时注释 `AgentCardParse` 的 `elapsedSec` 接线 ⇒ working 那条红（唯一失败）；
 * 复原后绿。其余形态同理可各自打红。
 */
class AgentCardFamilySpecTest {

    // ---------------------------------------------------------------- 发射形状

    /** `StageChain.cardOf` 的镜像：按发射侧的字段与形状构造（type=task-board）。 */
    private fun ticketCard(
        state: String,
        title: String = "implement create probe9-20261108.txt PROBE-OK9",
        banner: Pair<String, String>? = "团队已就位：DSH 主理，8 秒后自动开工" to "info",
        percent: Int = 40,
        elapsedSec: Long? = 45,
        items: Boolean = true,
        chips: Boolean = true,
        withActions: Boolean? = null, // null = 按发射规则：仅 working 给 abort-task
        bot: Boolean = true,
    ): JsonObject = buildJsonObject {
        put("cardId", "chain-tmw7spec01")
        put("rev", 6)
        put("type", "task-board")
        put("state", state)
        put("title", title)
        put("progress", percent)
        elapsedSec?.let { put("elapsedSec", it) }
        putJsonArray("items") {
            if (items) {
                add(buildJsonObject {
                    put("id", "编码实现")
                    put("label", "编码实现")
                    put("status", if (state == "review_ready" || state == "aborted") "done" else "running")
                    put("assignee", "DSH")
                    put("detail", "sandbox harvest +1 -0 in 1 file(s)")
                })
            }
        }
        if (bot) putJsonObject("bot") {
            put("name", "DSH")
            put("branch", "chain/tmw7spec01")
            put("bdot", when (state) {
                "review_ready", "merged", "done" -> "done"
                "blocked", "aborted" -> "fail"
                else -> "run"
            })
        }
        if (chips) putJsonArray("chips") {
            when (state) {
                "executing", "working", "prototype" ->
                    add(buildJsonObject { put("text", "第 1/1 棒 · $percent%"); put("tone", "pri") })
                "review_ready" ->
                    add(buildJsonObject { put("text", "门禁 2 项全绿"); put("tone", "ok") })
                "blocked" ->
                    add(buildJsonObject { put("text", "受阻 · 等你给方向"); put("tone", "warn") })
            }
        }
        if (withActions ?: (state == "working")) putJsonArray("actions") {
            add(buildJsonObject { put("actionId", "abort-task"); put("label", "abort-task") })
        }
        banner?.let { (text, tone) -> putJsonObject("banner") { put("text", text); put("tone", tone) } }
    }

    /** `withFailureCheck` 的镜像：worker 崩溃检查项。 */
    private fun withFailureCheck(base: JsonObject): JsonObject {
        val check = buildJsonObject {
            put("id", "crash")
            put("label", "worker exit")
            put("status", "failed")
            put("observation", "dsh exited 402 QUOTA after 87 steps")
            put("drawer", "dsh --profile headless --json …（原始 stderr 尾巴）")
        }
        val existing = base["checks"]
        val checks = buildJsonArray { (existing as? kotlinx.serialization.json.JsonArray)?.forEach { add(it) }; add(check) }
        return JsonObject(base.toMap() + ("checks" to checks))
    }

    /** `HealSteering.cardWithSteeringActions` 的镜像：熔断卡纠偏动作。 */
    private fun withSteering(base: JsonObject): JsonObject {
        val actions = buildJsonArray {
            add(buildJsonObject { put("actionId", "heal-steer"); put("label", "Type one instruction & retry") })
            add(buildJsonObject { put("actionId", "heal-abandon"); put("label", "Give up, keep worktree") })
        }
        return JsonObject(base.toMap() + ("actions" to actions))
    }

    /** `ArtifactPipe.upgradeArtifactsSelfDescribing` 的镜像：自描述产物条目。 */
    private fun withSelfDescribing(base: JsonObject): JsonObject {
        val artifacts = buildJsonArray {
            add(buildJsonObject {
                put("name", "patch.diff")
                put("bytes", 166)
                put("mime", "text/x-diff")
                put("intent", "review & merge")
                put("delivered", true)
            })
            add(buildJsonObject {
                put("name", "report.json")
                put("bytes", 12288)
                put("mime", "application/json")
                put("intent", "read-only report")
                put("delivered", true)
            })
        }
        return JsonObject(base.toMap() + ("artifacts" to artifacts))
    }

    // ---------------------------------------------------------------- 各形态

    @Test
    fun proposedCardCarriesStartAndCancelActionsWithLocalizedKeys() {
        val base = ticketCard("proposed")
        val withActions = JsonObject(base.toMap() + ("actions" to buildJsonArray {
            add(buildJsonObject {
                put("actionId", "start-task")
                put("label", "Start now")
                put("labelKey", "card.action.start_task")
            })
            add(buildJsonObject {
                put("actionId", "cancel-task")
                put("label", "Cancel")
                put("labelKey", "card.action.cancel_task")
            })
        }))
        val card = assertNotNull(parseAgentCard(withActions))
        assertEquals("proposed", card.state)
        assertEquals(listOf("start-task", "cancel-task"), card.actions.map { it.actionId },
            "受理卡的动作 = 立即执行/取消（宿主分支与端上词条早已就位；" +
                "publishTicketCard 的 options 参数是无人使用的死参数，另记清理）")
        assertEquals("run", card.bot?.bdot)
        assertEquals(AgentCardBannerTone.Info, card.banner?.tone)
    }

    @Test
    fun prototypeCardKeepsItsAdjustOrApproveBannerAndDiff() {
        val base = ticketCard(
            "prototype",
            banner = "Refined prototype ready. Reply to adjust it further, or approve to start building." to "info",
        )
        val withDiff = JsonObject(base.toMap() + ("diff" to buildJsonObject {
            put("path", "prototype/relay.html")
            put("summary", "原型卡片段初稿")
            put("additions", 109)
            put("deletions", 12)
            putJsonArray("hunk") { add(JsonPrimitive("@@ -202,6 +202,12 @@")) }
        }))
        val card = assertNotNull(parseAgentCard(withDiff))
        assertEquals("prototype", card.state)
        assertEquals(1, card.diff?.hunk?.size, "diff hunk 原文必须可解析（文档预览 .df 块的数据源）")
        assertEquals(109, card.diff?.additions)
    }

    @Test
    fun workingCardShowsElapsedChipsAndAbortAction() {
        val card = assertNotNull(parseAgentCard(ticketCard("working")))
        assertEquals(45L, card.elapsedSec, "耗时段（规格二的 (45s)）必须活着 —— 发射方算好秒数")
        assertTrue(card.chips.any { it.text.contains("第 1/1 棒") }, "第 N/M 棒 chip（原型 .mchips）")
        assertEquals("abort-task", card.actions.singleOrNull()?.actionId, "干到一半的卡必须有「停」")
        assertEquals("sandbox harvest +1 -0 in 1 file(s)", card.steps.first().detail, "这棒干了什么必须上卡（P1）")
    }

    @Test
    fun reviewReadyCardCarriesSelfDescribedArtifactsAndGateChip() {
        val card = assertNotNull(parseAgentCard(withSelfDescribing(ticketCard("review_ready", banner = "改动已就绪：1 个文件（+1 −0）" to "success"))))
        assertEquals("done", card.bot?.bdot)
        val patch = card.artifacts.first { it.name == "patch.diff" }
        assertEquals("text/x-diff", patch.mime)
        assertNotNull(patch.intent, "自描述条目必须带 intent（审阅/合并的用途说明）")
        assertEquals(true, patch.delivered, "真发起过 P2P 直投的条目必须如实标 delivered")
        assertTrue(card.chips.any { it.text.contains("门禁 2 项全绿") }, "门禁 chip 只在 checks 非空时说全绿")
    }

    @Test
    fun blockedCardAsksForDirection() {
        val card = assertNotNull(parseAgentCard(ticketCard("blocked", banner = null)))
        assertTrue(card.chips.any { it.text.contains("受阻 · 等你给方向") })
        assertEquals("fail", card.bot?.bdot)
        assertNull(card.actions.singleOrNull(), "blocked 是等人说话，不该出现「停止」按钮")
    }

    @Test
    fun abortedCardKeepsTheWorktreeBanner() {
        val card = assertNotNull(
            parseAgentCard(ticketCard("aborted", banner = "Abandoned by user; worktree kept at chain/tmw7spec01" to "warn")),
        )
        assertEquals("fail", card.bot?.bdot)
        assertTrue(card.banner?.text?.contains("worktree kept") == true, "现场保留必须可见（不是一弃了之）")
    }

    @Test
    fun crashCheckCarriesObservationAndDrawer() {
        val card = assertNotNull(parseAgentCard(withFailureCheck(ticketCard("failed", banner = null))))
        val crash = card.checks.first { it.id == "crash" }
        assertEquals(AgentCardStepStatus.Failed, crash.status)
        assertEquals("dsh exited 402 QUOTA after 87 steps", crash.observation)
        assertEquals("dsh --profile headless --json …（原始 stderr 尾巴）", crash.drawer, "日志抽屉内容（点开看现场）")
    }

    @Test
    fun healSteeringCardOffersRetryInputAndAbandon() {
        val card = assertNotNull(
            parseAgentCard(withSteering(ticketCard("failed", banner = "Heal loop 3/3: fixing issues (执行中卡可见)" to "warn"))),
        )
        val ids = card.actions.map { it.actionId }
        assertTrue("heal-steer" in ids, "纠偏输入槽动作（自愈熔断后用户能就地给方向）")
        assertTrue("heal-abandon" in ids, "放弃但保留现场动作")
    }

    @Test
    fun decidedFooterSurvivesTheParse() {
        val base = ticketCard("merged", banner = null, elapsedSec = null, items = false, chips = false)
        val withDecision = JsonObject(
            base.toMap() + mapOf(
                "decidedBy" to JsonPrimitive("25222a45-4b84-4e9a-9870-d13f86b2a8de"),
                "decidedActionId" to JsonPrimitive("merge-patch"),
            ),
        )
        val card = assertNotNull(parseAgentCard(withDecision))
        assertEquals("25222a45-4b84-4e9a-9870-d13f86b2a8de", card.decidedBy, "知情行数据源（D7 渲染层再解析成名字）")
        assertEquals("merge-patch", card.decidedActionId)
        assertEquals("done", card.bot?.bdot, "已合并在 bdot 词表里是 done")
    }
}
