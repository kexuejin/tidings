// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [AgentSessionRegistry] 的台账口径回归测试。
 *
 * 这两个用例各自钉住一个曾经的缺陷，都是纯内存的、不需要起网络：
 *
 * 1. **淘汰方向**（F1）：`updatedAt` 以前只有 `recordSession` 会推进，于是只流事件、
 *    不重发宣告的活跃会话在 `maxSessions` 淘汰时被当成「最旧」优先删掉，连它的事件一起丢。
 *    修法：`ensureSession` 对**已存在**的会话也刷新 `updatedAt`（六类帧都经过它）。
 *
 *    ⚠️ 时间线是**判别力的来源**，别随手改：活跃会话（`long-running`）的事件必须发生在
 *    **竞争会话（`done-1`）之后、触发淘汰的那一帧（`done-2`）之前**。只有这样，
 *    「按真实活动时间排」与「按最后一次宣告排」才会给出不同的淘汰结果：
 *    修前 `done-2` 到达时 `long-running` 的 `updatedAt` 仍停在 1000（比 `done-1` 的 9000 旧）
 *    ⇒ 它被淘汰；修后 `updatedAt` 已推进到 10010 ⇒ 被淘汰的是最闲的 `done-1`。
 *    （早先一版探针把事件时间放在竞争会话**之前**，那种顺序下修前修后都会淘汰同一个会话，
 *    结果是**不具判别力**的假红 —— 这条注释就是为了防止再写回那种顺序。）
 *
 * 2. **合成会话不该宣称「收到过宣告」**（F6）：会话从未宣告、但先来了事件时，`ensureSession`
 *    会补一条占位会话。旧写法把它当 `outbound = false` 的宣告走 `recordSession`，于是
 *    `announcedIn` 被置真 —— 面板会以为对方主动宣告过。修后两个标记双双为假，
 *    这正是 [AgentSessionRegistry.SessionRecord.synthesized] 信号。
 */
class AgentSessionRegistryProbeTest {

    @Test
    fun mostActiveSessionMustSurviveEviction() {
        val reg = AgentSessionRegistry(maxSessions = 2, maxEventsPerSession = 50)

        // 长跑会话：只宣告一次（busy），之后一直流事件，不再重发宣告。
        reg.recordSession(
            nodeId = "peer",
            sessionId = "long-running",
            state = "busy",
            outbound = true,
            at = 1_000,
        )

        // 一个更闲的会话：它的活动时间夹在 long-running 的宣告与它的事件之间。
        reg.recordSession(nodeId = "peer", sessionId = "done-1", state = "done", outbound = true, at = 9_000)

        // long-running 现在还在干活 —— 事件时间**晚于** done-1 的 updatedAt。
        for (i in 1L..10L) {
            reg.recordEvent(
                nodeId = "peer",
                sessionId = "long-running",
                cursor = i,
                eventType = "tool.started",
                at = 10_000 + i,
            )
        }

        // 再来一个会话把 maxSessions 顶破：该被淘汰的是最闲的 done-1，不是还在干活的 long-running。
        reg.recordSession(nodeId = "peer", sessionId = "done-2", state = "done", outbound = true, at = 20_000)

        val ids = reg.sessions().map { it.sessionId }
        assertTrue("long-running" in ids, "最活跃的会话被淘汰了，实际留下：$ids")
        assertFalse("done-1" in ids, "该淘汰的是最闲的 done-1，实际留下：$ids")
        assertEquals(10, reg.events("peer", "long-running").size, "活跃会话的事件被连带删光")
    }

    @Test
    fun sessionInferredFromAFrameDoesNotClaimAnAnnouncement() {
        val reg = AgentSessionRegistry()

        // 只有事件、没有任何 agent-session 宣告。
        reg.recordEvent(
            nodeId = "peer",
            sessionId = "never-announced",
            cursor = 1,
            eventType = "tool.started",
        )

        val row = reg.sessions().single()
        assertEquals("never-announced", row.sessionId)
        assertFalse(row.announcedIn, "占位会话不该宣称「收到过对方宣告」")
        assertFalse(row.announcedOut, "占位会话不该宣称「本端发出过宣告」")
        assertTrue(row.synthesized, "两侧都没宣告过 ⇒ 必须标成合成会话")
        assertEquals(null, row.state, "没宣告过就没有 state，面板据此降级")

        // 真宣告到达后，它不再是合成会话。
        reg.recordSession(nodeId = "peer", sessionId = "never-announced", state = "busy", outbound = false)
        val announced = reg.sessions().single()
        assertTrue(announced.announcedIn)
        assertFalse(announced.synthesized, "收到真宣告后不该再是合成会话")
    }
}
