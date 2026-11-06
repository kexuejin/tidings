// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.cli

import com.kapp.tenon.agent.ChainProgress
import com.kapp.tenon.agent.ChainStage
import com.kapp.tenon.agent.DelegationPlan
import com.kapp.tenon.agent.StageChain
import com.kapp.tenon.agent.StageChainRunner
import com.kapp.tenon.agent.StageRun
import com.kapp.tenon.agent.drivers.AgentDriver
import com.kapp.tenon.agent.drivers.DriverCapability
import com.kapp.tenon.agent.drivers.DriverEvent
import com.kapp.tenon.agent.drivers.SessionHandle
import com.kapp.tenon.agent.drivers.TaskProgressSnapshot
import com.kapp.tenon.orchestration.BoardCardFlip
import com.kapp.tenon.orchestration.StageChainGate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 阶段 1 的**可执行验收物**：在没有任何桌面壳的 classpath 上，用假 driver 建一条链跑通。
 *
 * ## 三条判据（对应「编排下沉」到底成了没有）
 *
 * 1. 假 driver 实现的是 :agent-contract 的会话制契约，不是桌面壳里的某个内部类；
 * 2. 代码门（StageChainGate）、执行体（StageChainRunner）、卡面翻面（BoardCardFlip）
 *    全部来自 :orchestration —— 而 :cli 的**主源集**仍然只链接 :app，本测试用的是
 *    testImplementation（见 CliHasNoDesktopShellTest 的守卫）；
 * 3. 全程不出现任何 desktopApp 类型：一旦有人把编排逻辑挪回壳里，这个测试会编译不过。
 *
 * 刻意**不碰** ControlPlane / git / worktree：那是桌面壳的真环境（D 类），留给既有桌面测试；
 * 这里只验「编排面能被无 UI 的宿主驱动」。
 */
class FakeDriverChainAcceptanceTest {

    /** 只按脚本产出事件的假驱动：openSession 给句柄，executeTask 发两段增量后收尾。 */
    private class ScriptedDriver(override val driverId: String) : AgentDriver {

        override val displayName: String = "Scripted " + driverId
        var opened: Int = 0
        var executed: Int = 0
        var finished: Int = 0

        override suspend fun listCapabilities(): List<DriverCapability> = emptyList()

        override suspend fun openSession(wsPath: String, resumeSessionId: String?): SessionHandle {
            opened++
            return SessionHandle(sessionId = "s-" + driverId + "-" + opened, wsPath = wsPath, createdAt = 0L)
        }

        override suspend fun executeTask(
            sessionId: String,
            prompt: String,
            onEvent: suspend (DriverEvent) -> Unit,
        ) {
            executed++
            onEvent(DriverEvent.TokenDelta("处理：" + prompt.take(16)))
            onEvent(DriverEvent.Finished(finishReason = "stop", totalTokens = 1))
            finished++
        }

        override suspend fun handleAction(sessionId: String, actionId: String, decidedBy: String) = Unit

        override suspend fun queryProgress(sessionId: String): TaskProgressSnapshot? = null
    }

    private fun text(card: JsonObject, key: String): String? =
        (card[key] as? JsonPrimitive)?.content

    @Test
    fun fakeDriverChainRunsToReviewReadyOnOneCardSlot() = runBlocking {
        val worker = ScriptedDriver("codex-node")
        val reviewer = ScriptedDriver("dsh-review")
        val reports = mutableListOf<ChainProgress>()
        val prompts = mutableListOf<String>()
        var head = "c0ffee00"

        suspend fun runStage(stage: ChainStage, prompt: String, repoPath: String): StageRun {
            prompts += prompt
            val driver = if (stage.agentKey == "codex") worker else reviewer
            val handle = driver.openSession(repoPath, null)
            var said: String? = null
            driver.executeTask(handle.sessionId, prompt) { event ->
                if (event is DriverEvent.TokenDelta) said = event.delta
            }
            return StageRun(
                ok = true,
                sessionId = handle.sessionId,
                patchPath = "/tmp/" + stage.role + ".diff",
                summary = said ?: stage.role,
            )
        }

        val stages = listOf(
            ChainStage(role = "开发", agentKey = "codex", instruction = "实现搜索"),
            ChainStage(role = "审核", agentKey = "dsh", instruction = "审阅改动"),
        )

        // 代码门：先让委派计划过一遍在位/异厂商裁决（被搬的 StageChainGate）。
        val decision = StageChainGate.enforce(
            DelegationPlan(goal = "实现搜索", stages = stages, source = DelegationPlan.Source.EXPLICIT),
            availableKeys = setOf("codex", "dsh"),
        )
        assertTrue(decision is StageChainGate.Decision.Pass, "异厂商两棒应放行，实际：" + decision)
        val passed = decision as StageChainGate.Decision.Pass

        val runner = StageChainRunner(
            headSha = { head },
            runStage = { stage, prompt, repoPath -> runStage(stage, prompt, repoPath) },
            land = { _, run, _ ->
                head = "sha-" + run.sessionId
                head
            },
            report = { reports += it },
        )
        val runs = runner.run("c-cli", "/tmp/repo", "实现搜索", passed.stages)

        // 1) 链真的跑完了：两棒都执行、都落了提交、终态 review_ready。
        assertEquals(2, runs.size, "两棒都要跑完")
        assertEquals(1, worker.executed, "开发棒要真的驱动了 worker")
        assertEquals(1, reviewer.executed, "审核棒要真的驱动了 reviewer")
        assertEquals(2, worker.finished + reviewer.finished, "每根驱动各收尾一次")
        assertEquals("review_ready", reports.last().state)

        // 2) 交接靠提交：第二棒的起点是第一棒落下的那个提交（不是日志）。
        assertEquals(2, prompts.size, "两棒各拼一次 prompt")
        assertTrue(
            prompts[1].contains("sha-s-codex-node-1"),
            "第二棒的起点必须是第一棒落下的提交，实际：\n" + prompts[1],
        )

        // 3) 单卡单槽：所有播报同一 cardId、rev 严格递增；翻面后仍在同一槽位。
        val cards = reports.map { StageChain.cardOf(it) }
        assertEquals(
            listOf("chain-c-cli"),
            cards.map { text(it, "cardId") }.distinct(),
            "一链一卡：所有播报必须落在同一个 cardId 上",
        )
        val revs = reports.map { it.rev }
        assertEquals(revs.sorted(), revs, "rev 必须单调递增：" + revs)
        assertEquals(revs.distinct().size, revs.size, "同一卡的 rev 不许重复：" + revs)

        val merged = BoardCardFlip.merged("chain-c-cli", cards.last())
        assertEquals("chain-c-cli", text(merged, "cardId"), "翻面不许换卡槽")
        assertEquals("merged", text(merged, "state"))
        val mergedRev = (merged["rev"] as? JsonPrimitive)?.content?.toIntOrNull()
        assertTrue(mergedRev != null && mergedRev >= 1, "翻面必须带一个 rev，交给宿主按台账抬高")
    }
}
