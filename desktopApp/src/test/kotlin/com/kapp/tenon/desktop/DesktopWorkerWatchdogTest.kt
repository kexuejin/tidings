// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.desktop

// 编排面（:orchestration）：从桌面壳下沉的工单/责任链/台账/卡片补丁。
import com.kapp.tenon.orchestration.CrashReport
import com.kapp.tenon.orchestration.HeartbeatEmitter
import com.kapp.tenon.orchestration.OrchestratorCards
import com.kapp.tenon.orchestration.RevisionFeedback
import com.kapp.tenon.orchestration.SandboxIdleWatchdog
import com.kapp.tenon.orchestration.WorkerHeartbeat
import com.kapp.tenon.orchestration.WorkerPrompt

import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 4 宿主编排层接线的全组用例：看门狗死人开关、心跳探针节流、Fail-Loud
 * 崩溃抽屉、打回反馈解码、提示词硬约束前置、卡片预算闸门。
 *
 * 时序类判定全部走**注入时钟**（拨毫秒推进 3 分钟空闲），不用固定 delay 赌 ——
 * AGENTS 测试纪律与 envDependentTestGate 的共同要求。
 */
class DesktopWorkerWatchdogTest {

    // ------------------------------------------------------------------ 死人开关

    /**
     * 租约内推进 → OBSERVE；租约过后第一次 poll → TERMINATE（SIGTERM）；
     * 宽限期内再次有输出（feed）→ 租约复位，重新 OBSERVE —— 进程缓过来不算事故。
     */
    @Test
    fun idleLeaseEscalatesOnlyAfterGraceAndFeedResetsIt() {
        var now = 1_000_000L
        val wd = SandboxIdleWatchdog(
            clock = { now },
            idleTimeoutMs = 180_000L,
            graceMs = 5_000L,
        )
        assertEquals(SandboxIdleWatchdog.Verdict.OBSERVE, wd.poll(), "刚启动就是推进中")
        now += 100_000
        wd.feed()
        assertEquals(SandboxIdleWatchdog.Verdict.OBSERVE, wd.poll(), "有输出就续租")
        now += SandboxIdleWatchdog.DEFAULT_IDLE_TIMEOUT_MS + 1
        assertEquals(SandboxIdleWatchdog.Verdict.TERMINATE, wd.poll(), "空闲超租约先发 SIGTERM")
        now += 3_000
        assertEquals(SandboxIdleWatchdog.Verdict.OBSERVE, wd.poll(), "宽限期未满不升级")
        // SIGTERM 之后进程又吐了一行：租约复位，回到 OBSERVE。
        wd.feed()
        assertEquals(SandboxIdleWatchdog.Verdict.OBSERVE, wd.poll(), "宽限期内有输出 = 活了，重新续租")
    }

    /** 宽限期满仍无推进 → FORCE_KILL（熔断）。 */
    @Test
    fun graceExpiryWithoutProgressMeansForceKill() {
        var now = 0L
        val wd = SandboxIdleWatchdog(clock = { now }, idleTimeoutMs = 180_000L, graceMs = 5_000L)
        now += 180_001
        assertEquals(SandboxIdleWatchdog.Verdict.TERMINATE, wd.poll())
        now += 5_000
        assertEquals(
            SandboxIdleWatchdog.Verdict.FORCE_KILL,
            wd.poll(),
            "SIGTERM 后宽限到期仍无推进必须升级熔断",
        )
    }

    /** env 配置口径：坏值回落默认，不许把配置笔误变成「立刻熔断」。 */
    @Test
    fun junkIdleEnvFallsBackToDefault() {
        assertEquals(
            SandboxIdleWatchdog.DEFAULT_IDLE_TIMEOUT_MS,
            SandboxIdleWatchdog.idleTimeoutMsOf(emptyMap()),
        )
        assertEquals(
            60_000L,
            SandboxIdleWatchdog.idleTimeoutMsOf(mapOf(SandboxIdleWatchdog.ENV_IDLE_TIMEOUT_MS to "60000")),
        )
        for (junk in listOf("0", "-5", "abc", "  ")) {
            assertEquals(
                SandboxIdleWatchdog.DEFAULT_IDLE_TIMEOUT_MS,
                SandboxIdleWatchdog.idleTimeoutMsOf(mapOf(SandboxIdleWatchdog.ENV_IDLE_TIMEOUT_MS to junk)),
                "junk 空闲租约必须回落默认，不许变成立刻熔断",
            )
        }
    }

    // ------------------------------------------------------------------ 心跳探针

    /** 无变化不发（刷新节流）；变了才发，且发的载荷带 lastBeatTs 毫秒与观察。 */
    @Test
    fun heartbeatOnlyEmitsOnChange() {
        var now = 10_000L
        val emitter = HeartbeatEmitter(clock = { now })
        val first = emitter.note("gradle", "test Foo.bar")
        assertNotNull(first, "第一次事实必须发")
        assertEquals("gradle", first.currentTool)
        assertEquals(10_000L, first.lastBeatTs)
        assertNull(emitter.note("gradle", "test Foo.bar"), "同内容不发：刷新省的是帧不是渲染")
        now += HeartbeatEmitter.MIN_INTERVAL_MS
        val second = emitter.note("git", null)
        assertNotNull(second, "子动作变了必须发")
        assertEquals("git", second.currentTool)
        assertEquals(now, second.lastBeatTs, "lastBeatTs 必须是毫秒墙钟")
    }

    /** 最小间隔限流：变化落在间隔内就吞掉，防止高频工具把通道刷成瀑布。 */
    @Test
    fun heartbeatThrottlesWithinMinInterval() {
        var now = 0L
        val emitter = HeartbeatEmitter(clock = { now })
        assertNotNull(emitter.note("a"))
        now += 1_000
        assertNull(emitter.note("b"), "最小间隔内的变化被节流丢弃")
        now += HeartbeatEmitter.MIN_INTERVAL_MS
        assertNotNull(emitter.note("c"), "间隔过了就恢复发射")
    }

    /** 空白工具名不进载荷：lastBeatTs 与 observation 是契约字段，宁缺毋滥。 */
    @Test
    fun heartbeatBlankToolYieldsNullField() {
        val emitter = HeartbeatEmitter(clock = { 5L })
        val beat = assertNotNull(emitter.note("  "))
        assertNull(beat.currentTool, "空白工具名如实缺席，不发空串")
    }

    // ------------------------------------------------------------------ 崩溃抽屉

    /** Fail-Loud：抽屉取**尾部** 20 行 —— 结论在最后，头部是框架噪声。 */
    @Test
    fun drawerKeepsTheLastTwentyLines() {
        val lines = (1..50).map { "line-$it" }
        val drawer = CrashReport.drawerOf(lines.joinToString("\n"))
        assertNotNull(drawer)
        assertFalse(drawer.contains("line-30"), "头部 30 行该被截掉")
        assertTrue(drawer.startsWith("line-31"), "抽屉必须从倒数第 20 行开始")
        assertTrue(drawer.endsWith("line-50"), "最后一句（结论）必须保留")
        assertEquals(20, drawer.split("\n").size, "抽屉行数 = 20")
    }

    @Test
    fun drawerTruncatesToBudgetAndHandlesBlank() {
        val big = (1..50).joinToString("\n") { "x".repeat(100) }
        assertTrue(CrashReport.drawerOf(big)!!.length <= CrashReport.DRAWER_MAX_CHARS, "抽屉按字符预算截断")
        assertNull(CrashReport.drawerOf(null))
        assertNull(CrashReport.drawerOf("   "))
        assertNull(CrashReport.drawerOf("\n\n"))
        val throwableDrawer = CrashReport.drawerOf(IllegalStateException("boom"))
        assertTrue(throwableDrawer.isNotBlank(), "异常路径也能给出抽屉")
        assertTrue(
            throwableDrawer.length <= CrashReport.DRAWER_MAX_CHARS,
            "异常抽屉同样受字符预算约束",
        )
    }

    // ------------------------------------------------------------------ 打回注水

    /** 解码侧与 composeApp 编码侧共用同一前缀契约（ui-renderer 对齐决定）。 */
    @Test
    fun revisionFeedbackDecodesThePrefixContract() {
        assertEquals(
            "补上单元测试",
            RevisionFeedback.feedbackOf("reject-revision|补上单元测试"),
            "前缀后的全文就是用户反馈",
        )
        assertEquals("trim me", RevisionFeedback.feedbackOf("reject-revision|  trim me  "), "反馈取修正后的文本")
        assertNull(RevisionFeedback.feedbackOf("retry"), "非打回动作维持既有路由，不误吞")
        assertNull(RevisionFeedback.feedbackOf("reject-revision|"), "空反馈 = 没有反馈")
        assertNull(RevisionFeedback.feedbackOf("reject-revision|   "))
        assertNull(RevisionFeedback.feedbackOf("reject-revision"), "只有前缀没有分隔体也不算")
    }

    /** 硬约束**前置**：约束块出现在 containment 与任务正文之前。 */
    @Test
    fun workerPromptPutsConstraintsBeforeEverythingElse() {
        val prompt = WorkerPrompt.build(devPrompt = "do the thing", constraints = "1) all tests green")
        val constraintIdx = prompt.indexOf("1) all tests green")
        val bodyIdx = prompt.indexOf("do the thing")
        assertTrue(constraintIdx in 0 until bodyIdx, "约束必须先于任务正文出现（硬约束前置）")
        assertTrue(
            prompt.indexOf("HARD CONSTRAINTS") < constraintIdx,
            "约束块要有可认的标头，让模型把它当验收标准",
        )
        assertTrue(
            prompt.substringAfter("do the thing").contains("workspace repository directory").not() ||
                prompt.indexOf("workspace repository directory") < bodyIdx,
            "containment 前导仍然在位",
        )
        // 无约束 = 原形状（既有行为不回归）。
        val plain = WorkerPrompt.build("just do it")
        assertFalse(plain.contains("HARD CONSTRAINTS"))
        assertTrue(plain.contains("just do it"))
        val blank = WorkerPrompt.build("just do it", constraints = "   ")
        assertFalse(blank.contains("HARD CONSTRAINTS"), "空白约束不当约束")
    }

    // ------------------------------------------------------------------ 卡片构建

    /** 三张卡的字段必须与已提交的解析契约对齐（cardId/rev/checks/lastBeatTs/state/bot）。 */
    @Test
    fun cardsCarryTheContractedFields() {
        val beat = WorkerHeartbeat(currentTool = "gradle", observation = "test Foo.bar", lastBeatTs = 42L)
        val heartbeat = Json.parseToJsonElement(
            OrchestratorCards.heartbeatCardJson("t1", rev = 7, title = "title", roleName = "Codex", beat = beat),
        ) as JsonObject
        assertEquals("chain-t1", (heartbeat["cardId"] as JsonPrimitive).content)
        assertEquals(7, (heartbeat["rev"] as JsonPrimitive).content.toInt())
        assertEquals("working", (heartbeat["state"] as JsonPrimitive).content)
        assertEquals(42L, (heartbeat["lastBeatTs"] as JsonPrimitive).content.toLong(), "lastBeatTs 毫秒进卡")
        val bot = heartbeat["bot"] as JsonObject
        assertEquals("Codex", (bot["name"] as JsonPrimitive).content)
        assertEquals("run", (bot["bdot"] as JsonPrimitive).content)
        val check = ((heartbeat["checks"] as JsonArray)[0]) as JsonObject
        assertEquals("test Foo.bar", (check["observation"] as JsonPrimitive).content)
        assertEquals("running", (check["status"] as JsonPrimitive).content)

        val failed = Json.parseToJsonElement(
            OrchestratorCards.failedCardJson(
                ticketId = "t2", rev = 3, title = "t", banner = "Worker crashed: exit 1",
                observation = "cli exited with code 1", drawer = "line-A\nline-B", roleName = "Claude",
            ),
        ) as JsonObject
        assertEquals("failed", (failed["state"] as JsonPrimitive).content)
        val failCheck = ((failed["checks"] as JsonArray)[0]) as JsonObject
        assertEquals("failed", (failCheck["status"] as JsonPrimitive).content)
        assertEquals("line-A\nline-B", (failCheck["drawer"] as JsonPrimitive).content, "崩溃抽屉进 checks")
        assertEquals("cli exited with code 1", (failCheck["observation"] as JsonPrimitive).content)

        val timeout = Json.parseToJsonElement(
            OrchestratorCards.timeoutCardJson("t3", rev = 5, title = "t", banner = "watchdog", roleName = "Codex"),
        ) as JsonObject
        assertEquals("timeout", (timeout["state"] as JsonPrimitive).content, "熔断卡的 state=timeout")
    }

    /** 预算闸门：24 KiB 是发送侧不变量，超了在宿主这里响，不靠对端拒收兜底。 */
    @Test
    fun hugeObservationIsClampedAndCardsStayInBudget() {
        val beat = WorkerHeartbeat(
            currentTool = null,
            observation = "爆".repeat(5_000),
            lastBeatTs = 1L,
        )
        val json = OrchestratorCards.heartbeatCardJson("t1", 1, "t", "Codex", beat)
        assertTrue(
            json.encodeToByteArray().size <= com.kapp.tenon.core.protocol.Limits.CARD_BYTES,
            "探针卡必须卡在 24 KiB 预算内（观测字段已被截断）",
        )
        assertFalse(json.contains("爆".repeat(5_000)), "全量观测不该原样进卡")

        val drawer = "y".repeat(10_000)
        val failed = OrchestratorCards.failedCardJson("t2", 1, "t", "b", "o", drawer, "Codex")
        assertTrue(failed.encodeToByteArray().size <= com.kapp.tenon.core.protocol.Limits.CARD_BYTES, "失败卡同预算")
    }

    /** withFailureCheck 是**追加**：阶段项等既有事实不被丢掉。 */
    @Test
    fun withFailureCheckAppendsWithoutDroppingExistingFacts() {
        val base = Json.parseToJsonElement(
            """{"cardId":"chain-t1","rev":9,"state":"blocked","title":"keep","items":[{"id":"Codex","label":"Codex","status":"failed"}]}""",
        ) as JsonObject
        val merged = OrchestratorCards.withFailureCheck(base, "cli exited with code 1", "tail\nlines")
        assertEquals("keep", (merged["title"] as JsonPrimitive).content, "既有标题保留")
        assertNotNull(merged["items"], "阶段项等既有事实保留")
        val checks = merged["checks"] as JsonArray
        assertEquals(1, checks.size, "原卡没有 checks 就建一条")
        val check = checks[0] as JsonObject
        assertEquals("failed", (check["status"] as JsonPrimitive).content)
        assertEquals("tail\nlines", (check["drawer"] as JsonPrimitive).content)
    }
}
