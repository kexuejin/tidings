// SPDX-License-Identifier: GPL-3.0-only

package app.relay.desktop

import app.relay.agent.AgentSessionRegistry
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「给群补历史」的两块判据（task-46）：水位台账 + 补发候选。
 *
 * 真机缺陷：手机离线期间错过的**用户消息**永远补不回来 —— 因为补发那一趟用的判据是
 * 「本地会话里有没有这句」，而用户自己打的字无论如何都会先落本地 ⇒ 本地有 ≠ 群里发过。
 *
 * 水位粒度是**（会话 × 群）**（lead 裁决）：每个群是独立的投递目标，对一个群失败不该
 * 压制另一个群。所以这里除了幂等，还专门钉住「A 群成功、B 群失败 ⇒ B 群仍会补发」。
 */
class GroupForwardLedgerTest {

    private fun rec(sessionId: String, cursor: Long, type: String = "user.message", body: String? = "hi") =
        AgentSessionRegistry.EventRecord(
            nodeId = "peer-1",
            sessionId = sessionId,
            cursor = cursor,
            eventType = type,
            summary = null,
            body = body,
            ref = null,
            at = 1_700_000_000_000L,
        )

    private fun tempFile() = File.createTempFile("group-forward", ".txt").also { it.delete() }

    @Test
    fun unmarkedSessionAndGroupHaveNoWatermark() {
        val ledger = GroupForwardLedger(tempFile())
        assertEquals(-1L, ledger.cursorOf("s-1", "g-A"))
    }

    @Test
    fun watermarkIsPerSessionAndGroupAndSurvivesRestart() {
        val file = tempFile()
        GroupForwardLedger(file).apply {
            mark("s-1", "g-A", 10)
            mark("s-1", "g-A", 4)
            mark("s-1", "g-B", 7)
        }
        val reopened = GroupForwardLedger(file)
        assertEquals(10L, reopened.cursorOf("s-1", "g-A"), "只前进，且重启后仍知道发到哪了")
        assertEquals(7L, reopened.cursorOf("s-1", "g-B"), "另一个群各自独立")
        assertEquals(-1L, reopened.cursorOf("s-2", "g-A"))
    }

    @Test
    fun corruptLedgerDegradesToEmpty() {
        val file = tempFile()
        file.writeText("not a ledger\n\nbroken\tlines\tnot-a-number\nonly\ttwo")
        assertEquals(-1L, GroupForwardLedger(file).cursorOf("s-1", "g-A"), "坏文件 = 空台账（宁可多试一次）")
    }

    @Test
    fun onlyUnforwardedUserMessagesAreCandidates() {
        val events = listOf(
            rec("s-1", 1),
            rec("s-1", 2),
            rec("s-1", 3, type = "assistant.text"),
            rec("s-2", 1),
        )
        val candidates = groupForwardCandidates(
            events = events,
            forwardedCursorOf = { r -> if (r.sessionId == "s-1") 1L else -1L },
            isPeerEcho = { false },
        )
        assertEquals(
            listOf("s-1@2", "s-2@1"),
            candidates.map { it.sessionId + "@" + it.cursor },
            "只补没发过的用户消息，agent 正文不重发",
        )
    }

    @Test
    fun peerEchoIsNeverBackfilled() {
        val events = listOf(rec("s-1", 5, body = "手机发的那句"))
        val candidates = groupForwardCandidates(
            events = events,
            forwardedCursorOf = { -1L },
            isPeerEcho = { it.body == "手机发的那句" },
        )
        assertTrue(candidates.isEmpty(), "手机自己发的回声不许补发（否则用户自己的话在群里出现两遍）")
    }

    @Test
    fun everythingAlreadyForwardedMeansNoWork() {
        val events = listOf(rec("s-1", 1), rec("s-1", 2))
        val candidates = groupForwardCandidates(
            events = events,
            forwardedCursorOf = { 2L },
            isPeerEcho = { false },
        )
        assertTrue(candidates.isEmpty(), "幂等：发过的绝不再发")
    }

    @Test
    fun missedWhileOfflineIsBackfilledExactlyOnce() {
        val file = tempFile()
        val ledger = GroupForwardLedger(file)
        val events = listOf(rec("s-1", 1), rec("s-1", 2))
        val group = "g-A"

        fun pass(): List<Long> {
            val picked = groupForwardCandidates(
                events = events,
                forwardedCursorOf = { r -> ledger.cursorOf(r.sessionId, group) },
                isPeerEcho = { false },
            )
            // 发送成功之后才记水位（与 DesktopExtension 里的顺序一致）。
            picked.forEach { ledger.mark(it.sessionId, group, it.cursor) }
            return picked.map { it.cursor }
        }

        assertEquals(listOf(1L, 2L), pass(), "离线期间错过的两条：恢复后补齐")
        assertEquals(emptyList(), pass(), "第二趟：发过的绝不再发（幂等）")
        assertEquals(2L, GroupForwardLedger(file).cursorOf("s-1", group), "水位落盘 ⇒ 重启后仍不会重发")
    }

    @Test
    fun oneGroupsFailureDoesNotSuppressAnotherGroup() {
        val ledger = GroupForwardLedger(tempFile())
        val events = listOf(rec("s-1", 1))
        val groupA = "g-A"
        val groupB = "g-B"

        // 对 A 群发送成功 ⇒ 记 A 群的水位；对 B 群那次抛错 ⇒ **不记**。
        ledger.mark("s-1", groupA, 1)

        val forA = groupForwardCandidates(events, { r -> ledger.cursorOf(r.sessionId, groupA) }, { false })
        val forB = groupForwardCandidates(events, { r -> ledger.cursorOf(r.sessionId, groupB) }, { false })
        assertTrue(forA.isEmpty(), "A 群发过了 ⇒ 不再发")
        assertEquals(listOf(1L), forB.map { it.cursor }, "B 群失败过 ⇒ 仍会补发（每个群是独立投递目标）")
    }
}