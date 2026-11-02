// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Supervisor 三权分立最小闭环的判据（一）：拆解标记与并行分发。
 *
 * 并行不赌真实时长：重叠用「单调时戳区间相交」断言，退化用 cap=1 的区间相离对照 ——
 * 两者都是事件序事实，不 sleep 等同步、不断言峰值计数的精确值（恢复间隙非原子）。
 */
class StageChainIsolationTest {

    private fun isoStage(role: String, key: String = "codex", instruction: String = "做 " + role + " 的活") =
        ChainStage(role = role, agentKey = key, instruction = instruction, requiresIsolation = true)

    private fun plainStage(role: String, key: String = "codex") =
        ChainStage(role = role, agentKey = key, instruction = "做 " + role + " 的活")

    // ------------------------------------------------ 拆解标记：解析与老形状兼容

    @Test
    fun aStageDefaultsToSequentialSoOldCallSitesAreUntouched() {
        val legacy = ChainStage("原型", "agy", "画界面")
        assertFalse(legacy.requiresIsolation, "老调用（位置参数只到 instruction）缺省必须是非隔离")
    }

    @Test
    fun cardItemsWithoutTheKeyReadAsSequential() {
        val legacyItem = buildJsonObject {
            put("id", "s1")
            put("label", "原型")
            put("status", "done")
        }
        assertFalse(StageChain.requiresIsolationOf(legacyItem), "老卡没有这个键 ⇒ false")
        assertFalse(StageChain.requiresIsolationOf(buildJsonObject { put("requiresIsolation", "yes") }),
            "非布尔值按 false 兜底（宽松解析不许抛）")
        assertFalse(StageChain.requiresIsolationOf(null))
    }

    @Test
    fun cardItemsWithTheKeyReadAsIsolated() {
        assertTrue(StageChain.requiresIsolationOf(buildJsonObject { put("requiresIsolation", true) }))
        assertFalse(StageChain.requiresIsolationOf(buildJsonObject { put("requiresIsolation", false) }))
    }

    @Test
    fun dispatchTextMarksIsolationAndStripsTheMarkerFromTheInstruction() {
        val stages = StageChain.parseStages(
            "原型(agy): 画界面; 开发[iso](codex): 改协议实现; 验收[isolated]: 跑回归",
            defaultKey = "claude",
        )
        assertEquals(3, stages.size)
        assertFalse(stages[0].requiresIsolation, "不带标记的棒维持顺序")
        assertTrue(stages[1].requiresIsolation, "[iso] 标记的棒要求隔离")
        assertTrue(stages[2].requiresIsolation, "[isolated] 同样认")
        assertEquals("改协议实现", stages[1].instruction, "标记只归调度，不混进任务书")
        assertEquals("跑回归", stages[2].instruction)
    }

    @Test
    fun aMarkerOnTheRoleIsAlsoRecognized() {
        val stages = StageChain.parseStages("开发[iso]: 改协议; 验收: 跑回归", defaultKey = "claude")
        assertEquals(2, stages.size)
        assertTrue(stages[0].requiresIsolation, "标记贴在棒名上同样认")
        assertEquals("开发", stages[0].role, "标记要从棒名里摘掉")
        assertEquals("改协议", stages[0].instruction)
    }

    @Test
    fun dispatchTextWithoutMarkersKeepsLegacyShape() {
        val stages = StageChain.parseStages(
            "原型(agy): 设计界面草稿; 开发(codex): 实现功能逻辑; 验收: 跑回归测试",
            defaultKey = "claude",
        )
        assertEquals(listOf(false, false, false), stages.map { it.requiresIsolation })
        assertEquals("设计界面草稿", stages[0].instruction, "老文本的任务书一字不动")
    }

    @Test
    fun anIsolatedStagePromptCarriesTheSandboxDisciplineLine() {
        val prompt = StageChain.promptOf(isoStage("开发"), StageSeed("base", "/tmp/repo"))
        assertTrue(prompt.contains("worktree 沙箱"), "隔离棒的任务书要写明沙箱纪律:\n" + prompt)
        val plain = StageChain.promptOf(plainStage("开发"), StageSeed("base", "/tmp/repo"))
        assertFalse(plain.contains("worktree 沙箱"), "非隔离棒的任务书不掺隔离条款")
    }

    // ------------------------------------------------ 并行分发：事件序，不赌时长

    /** 单调时戳区间相交 = 真并发；串行执行区间必然相离。 */
    @Test
    fun twoIsolatedStagesOverlapInTime() = runBlocking {
        val windows = ConcurrentHashMap<String, Pair<Long, Long>>()
        val runner = StageChainRunner(
            headSha = { "base" },
            runStage = { stage, _, _ ->
                val start = System.nanoTime()
                kotlinx.coroutines.yield()
                windows[stage.role] = start to System.nanoTime()
                StageRun(ok = true, summary = stage.role + " 完成")
            },
            land = { _, _, _ -> "sha" },
        )
        val out = runner.run("iso", "/tmp/repo", "并行链", listOf(isoStage("甲"), isoStage("乙"), plainStage("验收")))
        assertEquals(3, out.size)
        val (aStart, aEnd) = windows.getValue("甲")
        val (bStart, bEnd) = windows.getValue("乙")
        assertTrue(aStart < bEnd && bStart < aEnd,
            "两根隔离棒的时间窗必须重叠（串行必然相离）：甲=" + aStart + ".." + aEnd + " 乙=" + bStart + ".." + bEnd)
        val (_, cStart) = windows.getValue("验收")
        assertTrue(cStart >= maxOf(aEnd, bEnd), "非隔离棒要等隔离组全部完成才开跑")
    }

    /**
     * 上限是结构：许可数 2 ⇒ 四棒必有重叠（真并行）；对照 cap=1 ⇒ 区间相离（退化串行，
     * 单独用例钉住）。峰值计数的**精确值**依赖调度恢复间隙（withPermit 退出与计数递减
     * 之间没有原子性），不作断言 —— 那是 envDependentTestGate 反对的「赌时序」形态。
     */
    @Test
    fun theParallelCapIsStructuralNotACoincidence() = runBlocking {
        assertTrue(
            groupWindowsOverlap(listOf("甲", "乙", "丙", "丁"), limit = 2),
            "上限 2 时四棒必须存在时间窗重叠（真并行）",
        )
    }

    /** 上限 1 = 退化成串行：区间相离（这个反例证明重叠断言真能区分并行与串行）。 */
    @Test
    fun aCapOfOneDegradesToSequentialWindows() = runBlocking {
        assertFalse(
            groupWindowsOverlap(listOf("甲", "乙"), limit = 1),
            "上限 1 时两棒不得重叠（对照：证明重叠断言不是恒真）",
        )
    }

    /** 用独立 runner 以 [limit] 跑一组隔离棒，回答是否存在窗口相交的对。 */
    private suspend fun groupWindowsOverlap(roles: List<String>, limit: Int): Boolean {
        val windows = ConcurrentHashMap<String, Pair<Long, Long>>()
        val probe = StageChainRunner(
            headSha = { "base" },
            runStage = { stage, _, _ ->
                val start = System.nanoTime()
                kotlinx.coroutines.yield()
                windows[stage.role] = start to System.nanoTime()
                StageRun(ok = true, summary = "ok")
            },
            land = { _, _, _ -> "sha" },
            parallelLimit = limit,
        )
        probe.run("probe", "/tmp/repo", "t", roles.map { isoStage(it) })
        return roles.flatMap { a -> roles.map { b -> a to b } }.any { (a, b) ->
            a != b && windows.getValue(a).first < windows.getValue(b).second &&
                windows.getValue(b).first < windows.getValue(a).second
        }
    }

    /** 失败即停整组：组内任一棒失败 ⇒ 全组不落盘、后续棒不开跑。 */
    @Test
    fun anIsolatedGroupFailsAsAWholeWhenAnyMemberFails() = runBlocking {
        val ran = mutableListOf<String>()
        val seen = mutableListOf<ChainProgress>()
        val runner = StageChainRunner(
            headSha = { "base" },
            runStage = { stage, _, _ ->
                synchronized(ran) { ran += stage.role }
                if (stage.role == "乙") StageRun(ok = false, error = "cli exited 1")
                else StageRun(ok = true, summary = "ok")
            },
            land = { _, _, _ -> "sha" },
            report = { p -> synchronized(seen) { seen += p } },
        )
        val out = runner.run("iso-fail", "/tmp/repo", "t",
            listOf(isoStage("甲"), isoStage("乙"), plainStage("验收")))
        // 并行下组内到达顺序不定，判集合相等；关键是「验收」绝不能出现在里面。
        assertEquals(setOf("甲", "乙"), ran.toSet(), "失败组后面的棒绝不能开跑")
        assertEquals(2, out.size, "失败组整组不落盘")
        assertEquals("blocked", seen.last().state)
        assertTrue(seen.last().banner!!.contains("第 2 棒失败"), "卡面要如实说哪根棒、为什么停")
    }
}
