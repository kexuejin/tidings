// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * M4 跨端派单网道的三个**纯件**：租约台账、审批防重台账、看板卡构造。
 *
 * 这里只测纯判定；两节点全回路（派单到达 / 零刷屏 / 断连超时）在 Layer 4 仿真里
 * （真 UDP 回环 + 真传输层）。拆开的理由：纯件的判据可以毫秒级验证，而回路是
 * 秒级观测预算 —— 把两者混在一个文件里，纯件的回归要等整个回路的启动握手。
 */
class AgentInvokeMeshPartsTest {

    // ---- AgentInvokeLeaseTable ----

    @Test
    fun leaseIsRunningUntilDeadline() {
        var now = 1_000L
        val table = AgentInvokeLeaseTable({ now })
        table.register(
            invokeId = "inv-1", groupId = "g-1", agentId = "gpu-runner", agentName = "gpu-runner",
            targetNode = "node-b", prompt = "run tests", wsId = null, deadlineMs = 5_000L,
        )
        assertEquals(InvokeLeaseStates.RUNNING, table.snapshot().single().state)

        now = 6_500L
        val flipped = table.markTimeoutDue()
        assertEquals(listOf("inv-1"), flipped.map { it.invokeId }, "过了期限的租约必须翻 Timeout")
        assertEquals(InvokeLeaseStates.TIMEOUT, table.snapshot().single().state)
    }

    @Test
    fun completedLeaseIsNeverFlippedToTimeout() {
        var now = 1_000L
        val table = AgentInvokeLeaseTable({ now })
        table.register("inv-1", "g-1", "a", "a", "node-b", "p", null, 5_000L)

        assertTrue(table.markDone("inv-1", summary = "ok", error = null) != null)
        now = 9_999L
        assertTrue(table.markTimeoutDue().isEmpty(), "已完成的租约不许被超时改写（结果比期限先到）")
        assertEquals(InvokeLeaseStates.DONE, table.snapshot().single().state)
    }

    @Test
    fun timeoutWinsExactlyOnce() {
        var now = 1_000L
        val table = AgentInvokeLeaseTable({ now })
        table.register("inv-1", "g-1", "a", "a", "node-b", "p", null, 5_000L)
        now = 6_000L
        assertEquals(1, table.markTimeoutDue().size, "第一次到期翻状态")
        assertEquals(0, table.markTimeoutDue().size, "第二次同一租约不再翻（超时卡只发一张）")
    }

    @Test
    fun resultAfterTimeoutIsDroppedIdempotently() {
        var now = 1_000L
        val table = AgentInvokeLeaseTable({ now })
        table.register("inv-1", "g-1", "a", "a", "node-b", "p", null, 5_000L)
        now = 6_000L
        table.markTimeoutDue()
        assertNull(table.markDone("inv-1", "late", null), "迟到的结果不许把 Timeout 改写成 Done")
    }

    @Test
    fun unknownInvokeIdIsNotCompletable() {
        val table = AgentInvokeLeaseTable({ 1_000L })
        assertNull(table.markDone("nope", "s", null))
    }

    @Test
    fun pruneDropsOnlyOldTerminalLeases() {
        var now = 1_000L
        val table = AgentInvokeLeaseTable({ now })
        table.register("inv-1", "g-1", "a", "a", "node-b", "p", null, 1_000L)
        table.register("inv-2", "g-1", "a", "a", "node-b", "p", null, 10_000L)
        table.markDone("inv-1", "ok", null)
        now = 20_000L
        table.pruneTerminalOlderThan(maxAgeMs = 5_000L)
        val left = table.snapshot().map { it.invokeId }
        assertFalse("inv-1" in left, "终态且过老的租约该清掉")
        assertTrue("inv-2" in left, "还在跑的租约不许被清（清了就没有超时判定）")
    }

    // ---- AgentCardActionLedger ----

    @Test
    fun firstAckWinsAndRestAreDropped() {
        val ledger = AgentCardActionLedger()
        assertTrue(ledger.firstWin("card-1", "approve", "alice"), "第一个 ack 生效")
        assertFalse(ledger.firstWin("card-1", "approve", "bob"), "晚到的并发点击幂等丢弃")
        assertEquals("alice", ledger.decidedBy("card-1", "approve"))
        assertNull(ledger.decidedBy("card-1", "other-action"), "同卡片的其它按钮不连坐")
    }

    @Test
    fun concurrentAcksProduceExactlyOneWinner() {
        val ledger = AgentCardActionLedger()
        val winners = java.util.concurrent.atomic.AtomicInteger(0)
        val threads = (1..16).map { i ->
            Thread {
                if (ledger.firstWin("card-9", "approve", "node-$i")) winners.incrementAndGet()
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(1, winners.get(), "16 个并发 ack 必须只有 1 个生效（原子 Action Lock）")
    }

    @Test
    fun rebuildRestoresDecisionsFromCardRows() {
        val ledger = AgentCardActionLedger()
        val decidedCard = buildJsonObject {
            put("cardId", "card-2")
            put("rev", 3)
            put("decidedBy", "carol")
            put("decidedActionId", "approve")
        }
        val plainCard = buildJsonObject {
            put("cardId", "card-3")
            put("rev", 1)
        }
        ledger.rebuildFrom(listOf(decidedCard, plainCard))
        assertEquals("carol", ledger.decidedBy("card-2", "approve"), "重启后从落库卡片行回填判定")
        assertNull(ledger.decidedBy("card-3", "approve"), "没有 decidedBy 的卡片不许凭空多出判定")
    }

    // ---- 看板卡构造（Leader 是唯一对外发言人，§6.1）----

    @Test
    fun timeoutCardCarriesRetryAndFallbackActions() {
        val card = buildInvokeBoardCard(
            invokeId = "inv-7",
            prompt = "run the regression suite",
            agentName = "gpu-runner",
            state = InvokeLeaseStates.TIMEOUT,
            timeoutTitle = "子任务超时中断",
            retryLabel = "重试",
            fallbackLabel = "降级为本地执行",
            summary = null,
            error = null,
            workerCard = null,
        )
        assertEquals("invoke-inv-7", card.strOf("cardId"), "看板卡的身份域归 Leader（invoke- 前缀）")
        assertEquals(1, card.intOf("rev"))
        val actions = card.arrayOf("actions")
        assertEquals(
            listOf(InvokeBoardActions.RETRY, InvokeBoardActions.FALLBACK_LOCAL),
            actions.map { it.strOf("actionId") },
            "超时卡必须带两个动作（§7.3-1）",
        )
        assertTrue(card.textOf("title")!!.contains("run the regression suite"))
    }

    @Test
    fun doneCardWrapsWorkerCardUnderLeaderNamespace() {
        val worker = buildJsonObject {
            put("cardId", "plugin-card-1")
            put("rev", 4)
            put("title", "回归完成")
            put("progress", 100)
        }
        val card = buildInvokeBoardCard(
            invokeId = "inv-8", prompt = "p", agentName = "gpu-runner",
            state = InvokeLeaseStates.DONE, timeoutTitle = "", retryLabel = "", fallbackLabel = "",
            summary = "all green", error = null, workerCard = worker,
        )
        assertEquals("invoke-inv-8", card.strOf("cardId"), "组员的 cardId 域不能混进 Leader 的群流（原位折叠按 cardId）")
        assertEquals(1, card.intOf("rev"))
        assertEquals("回归完成", card.strOf("title"), "组员卡片的标题进看板")
    }

    @Test
    fun failedStatesCarryNoActionButtons() {
        val card = buildInvokeBoardCard(
            invokeId = "inv-9", prompt = "p", agentName = "a",
            state = InvokeLeaseStates.DENIED, timeoutTitle = "超时", retryLabel = "重试", fallbackLabel = "降级",
            summary = null, error = "owner has not approved", workerCard = null,
        )
        assertTrue(card.arrayOf("actions").isEmpty(), "被拒的派单不给重试/降级按钮 —— 重试还是被拒")
        assertTrue(card.strOf("error")!!.contains("owner has not approved"), "拒绝原因必须如实进卡")
    }

    @Test
    fun doneCardIsAnHonestCompletion() {
        val card = buildInvokeBoardCard(
            invokeId = "inv-10", prompt = "p", agentName = "a",
            state = InvokeLeaseStates.DONE, timeoutTitle = "", retryLabel = "", fallbackLabel = "",
            summary = "3 steps finished", error = null, workerCard = null,
        )
        assertTrue(card.arrayOf("actions").isEmpty())
        assertEquals(100, card.intOf("progress"))
        val banner = card["banner"] as JsonObject
        assertTrue(banner.strOf("text")!!.contains("3 steps finished"), "摘要是用户可见的事实，必须在 banner 里")
        assertEquals("success", banner.strOf("tone"))
    }

    // ---- 小工具 ----

    private fun JsonObject.strOf(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content

    private fun JsonObject.intOf(key: String): Int? = strOf(key)?.toIntOrNull()

    private fun JsonObject.textOf(key: String): String? = strOf(key)

    private fun JsonObject.arrayOf(key: String): List<JsonObject> =
        (this[key] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
}
