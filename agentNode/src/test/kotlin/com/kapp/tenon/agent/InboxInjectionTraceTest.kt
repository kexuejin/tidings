// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

import com.kapp.tenon.core.protocol.InboxUnresolved
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [InboxInjectionTrace] 的判据。
 *
 * 这一份台账是「接入与通道」页唯一的数据源，而页面最怕的两件事都在这里钉住：
 *  1. **空集不能说成 0/0 的统计**（「桥没在跑」与「没有 @ 消息」必须分得开）；
 *  2. **未解析必须带成因**（只给计数，用户除了重装什么都做不了）。
 */
class InboxInjectionTraceTest {

    private fun hint(
        resolved: Boolean = true,
        mayExecute: Boolean = true,
        boundWsId: String? = "ws-1",
        reject: String? = null,
    ) = AgentInboxHint(
        addressedToAgent = true,
        boundWsId = boundWsId,
        targetSessionId = if (resolved) "sess-1" else null,
        senderMayExecute = mayExecute,
        requestRejectReason = reject,
    )

    @Test
    fun freshTraceIsEmptyNotZeroed() {
        val snap = InboxInjectionTrace().snapshot()
        assertTrue(snap.isEmpty, "一条判定都没做过时 isEmpty 必须为真 —— 页面据此画空态")
        assertEquals(0L, snap.lastPollAt)
        assertEquals(0L, snap.lastInjectableAt)
        assertTrue(snap.causes.isEmpty())
    }

    @Test
    fun pollIsRecordedEvenWithoutAnyMessage() {
        val trace = InboxInjectionTrace()
        trace.recordPoll(at = 1_000)
        val snap = trace.snapshot()
        assertEquals(1_000L, snap.lastPollAt, "桥来取过件这件事必须留下读数，否则空态说不出「桥没在跑」")
        assertTrue(snap.isEmpty, "取过件但没有任何判定 ⇒ 仍然是空窗口")
    }

    @Test
    fun resolvedAndUnresolvedAreCountedSeparately() {
        val trace = InboxInjectionTrace()
        trace.record("group:a", 1, hint(resolved = true), at = 10)
        trace.record("group:a", 2, hint(resolved = false, boundWsId = null), at = 20)
        val snap = trace.snapshot()
        assertEquals(2, snap.addressedCount)
        assertEquals(1, snap.resolvedCount)
        assertEquals(1, snap.unresolvedCount)
        assertEquals(20L, snap.lastAddressedAt, "最近一条判定是后到的那条（含投不出去的）")
        assertEquals(10L, snap.lastInjectableAt, "只有 resolved 的那条才是「可注入」，后一条不能顶替它")
        assertEquals(listOf(InboxUnresolved.NO_UNBOUND_SESSION to 1), snap.causes)
    }

    @Test
    fun unresolvedAlwaysCarriesACause() {
        val cases = listOf(
            hint(resolved = false, mayExecute = false) to InboxUnresolved.SENDER_NOT_PERMITTED,
            hint(resolved = false, reject = InboxUnresolved.REQUESTED_SESSION_GONE) to
                InboxUnresolved.REQUESTED_SESSION_GONE,
            hint(resolved = false, reject = InboxUnresolved.REQUESTED_SESSION_OUTSIDE_WORKSPACE) to
                InboxUnresolved.REQUESTED_SESSION_OUTSIDE_WORKSPACE,
            hint(resolved = false, boundWsId = null) to InboxUnresolved.NO_UNBOUND_SESSION,
            hint(resolved = false) to InboxUnresolved.NO_SESSION_IN_WORKSPACE,
        )
        for ((sample, expected) in cases) {
            assertEquals(expected, causeOf(sample), "成因归因必须落在 InboxUnresolved 的稳定 token 上")
        }
    }

    @Test
    fun unboundWorkspaceIsNotReportedAsTheReason() {
        // **反向探针**：把成因改回「未绑定工作区」（`no-bound-workspace`），这条必红。
        //
        // 「未绑定工作区」不是失败原因：未绑定的 agent 照样能在不带工作区的会话里干活。
        // 报成它，用户会去绑一个目录，然后发现仍然投不出去 —— 他的会话本来就不在那个目录里。
        val bound = hint(resolved = false, boundWsId = "ws-1")
        val unbound = hint(resolved = false, boundWsId = null)
        assertEquals(InboxUnresolved.NO_SESSION_IN_WORKSPACE, causeOf(bound))
        assertEquals(InboxUnresolved.NO_UNBOUND_SESSION, causeOf(unbound))
        assertNotEquals(
            causeOf(bound),
            causeOf(unbound),
            "「这个目录里没会话」与「连不带目录的会话都没有」是两种用户动作，不能合成一档",
        )
    }

    @Test
    fun blankBoundWorkspaceCountsAsUnbound() {
        // 空串按「未绑定」算 —— 与 `AgentSessionPick.pick` 的归一化（trim + isNotEmpty）同口径。
        // 两处判据不同源时，同一个 hint 会得到两个答案：pick 走「未绑定」分支、
        // 成因却说「这个项目目录里没会话」。
        assertEquals(InboxUnresolved.NO_UNBOUND_SESSION, causeOf(hint(resolved = false, boundWsId = "  ")))
    }

    @Test
    fun anUnknownRejectTokenIsReportedAsIsInsteadOfBeingReclassified() {
        // 判定层多一档点选被拒时，它给的 token 就是答案：这里原样上报，
        // 页面认不出会原样显示（`原因 <token>`）—— 那恰恰是「两端版本不一致」的证据。
        // 若这条 when 用「逐个 token 比对 + else 落到会话成因」，新 token 会被
        // 悄悄改写成「工作区里没会话」，线索就没了。
        val future = hint(resolved = false, reject = "requested-session-archived")
        assertEquals("requested-session-archived", causeOf(future))
    }

    @Test
    fun permissionDenialOutranksSessionProblems() {
        // 授权拒了的时候，有没有会话已经不重要 —— 用户要解的是权限，不是去开会话。
        val denied = hint(resolved = false, mayExecute = false, boundWsId = null)
        assertEquals(InboxUnresolved.SENDER_NOT_PERMITTED, causeOf(denied))
    }

    @Test
    fun targetSessionWithoutExecutionPermissionIsNotInjectable() {
        // **反向探针**：`record` 的谓词退回「只判 targetSessionId != null」，这条必红。
        //
        // 这个组合是真机上最常见的一种「没反应」：pick 与 authorize 是两条独立判定，
        // 会话照常算出来、发送者却没有执行权。只判前者的话，页面顶部会显示
        // 「最近一次可注入消息 刚刚」，而桥一条也不会投 —— 正是这一页要防的假绿。
        val trace = InboxInjectionTrace()
        trace.record("g", 1, hint(resolved = true, mayExecute = false), at = 10)
        val snap = trace.snapshot()
        assertEquals(1, snap.addressedCount, "它确实是「@ 到了本机 agent」的一条，要进窗口")
        assertEquals(0, snap.resolvedCount, "算得出会话但无权执行，不算「可注入」")
        assertEquals(1, snap.unresolvedCount)
        assertEquals(0L, snap.lastInjectableAt, "它不能顶新「最近一次可注入」")
        assertEquals(listOf(InboxUnresolved.SENDER_NOT_PERMITTED to 1), snap.causes)
    }

    @Test
    fun windowIsBoundedAndKeepsNewest() {
        val trace = InboxInjectionTrace(capacity = 3)
        repeat(5) { i ->
            trace.record("group:a", i.toLong() + 1, hint(resolved = i % 2 == 0), at = i.toLong())
        }
        val snap = trace.snapshot()
        assertEquals(3, snap.addressedCount, "窗口只留最近 capacity 条")
        assertEquals(3, snap.windowCapacity)
    }

    @Test
    fun replayingTheSameRowsDoesNotRefillTheWindow() {
        // 桥重启后从 since=0 重读一遍：同一批 seq 不能把窗口灌满，否则「近 20 条」
        // 会变成「近 20 次轮询」，统计从此不可信。
        val trace = InboxInjectionTrace()
        trace.record("group:a", 1, hint(), at = 10)
        trace.record("group:a", 2, hint(resolved = false), at = 20)
        repeat(5) { i ->
            trace.record("group:a", 1, hint(), at = 100L + i)
            trace.record("group:a", 2, hint(resolved = false), at = 100L + i)
        }
        val snap = trace.snapshot()
        assertEquals(2, snap.addressedCount)
        assertEquals(1, snap.resolvedCount)
    }

    @Test
    fun watermarkIsPerSession() {
        // `InMemoryMessageStore` 的 seq 是**按会话**各自从 1 数的（生产文件存储是全局单调）。
        // 用全局水位会让第二个会话的第一条消息被当成「已收过」而静默丢掉。
        val trace = InboxInjectionTrace()
        trace.record("group:a", 1, hint(resolved = false), at = 10)
        trace.record("group:b", 1, hint(resolved = true), at = 20)
        val snap = trace.snapshot()
        assertEquals(2, snap.addressedCount, "不同会话各自的第一条都要收进窗口")
        assertEquals(1, snap.unresolvedCount)
    }

    @Test
    fun causesAreSortedByCountThenByVocabularyOrder() {
        val trace = InboxInjectionTrace()
        trace.record("g", 1, hint(resolved = false, boundWsId = null), at = 1)
        trace.record("g", 2, hint(resolved = false), at = 2)
        trace.record("g", 3, hint(resolved = false), at = 3)
        trace.record("g", 4, hint(resolved = false, reject = InboxUnresolved.REQUESTED_SESSION_GONE), at = 4)
        val causes = trace.snapshot().causes
        assertEquals(InboxUnresolved.NO_SESSION_IN_WORKSPACE, causes.first().first, "多的排前面")
        assertEquals(2, causes.first().second)
        assertEquals(
            InboxUnresolved.REQUESTED_SESSION_GONE,
            causes[1].first,
            "同计数时按词表次序（点选被拒在未绑定工作区之前）",
        )
        assertEquals(InboxUnresolved.NO_UNBOUND_SESSION, causes.last().first)
    }

    @Test
    fun anActiveSessionKeepsItsWatermarkWhenTheTableIsFull() {
        // **反向探针**：把水位表改回插入序（去掉 accessOrder=true），这条必红。
        //
        // 插入序淘汰的是「最早第一次说话的会话」，而它很可能正是此刻最活跃的那条长开着的
        // 群会话。水位被挤掉之后，桥下一次重读会把它整段历史当成新消息重新收进窗口 ——
        // 页面上的判定统计突然虚高，而没有任何人改过什么。
        val trace = InboxInjectionTrace()
        val total = InboxInjectionTrace.MAX_TRACKED_PEERS
        // ① 灌满水位表：peer-0 最先说话 ⇒ 插入序下它正是最该被淘汰的那个。
        for (i in 0 until total) {
            trace.record("peer-" + i, 1, hint(), at = i.toLong() + 1)
        }
        // ② peer-0 又说了一句（同一条也算）：读一次就是「这个会话还活着」的证据，
        //    访问序下它要因此续命。
        trace.record("peer-0", 1, hint(), at = 1_000)
        // ③ 新会话把水位表顶到上限之上：访问序淘汰 peer-1，插入序淘汰 peer-0。
        trace.record("peer-new", 1, hint(), at = 2_000)
        // ④ 判据：peer-0 的历史 seq 仍被认作「已收过」。水位若被挤掉，这条会以
        //    「新消息」身份进窗口，窗口里就凭空多出一条未解析成因。
        trace.record("peer-0", 1, hint(resolved = false), at = 3_000)
        assertTrue(
            trace.snapshot().causes.isEmpty(),
            "活跃会话的水位被挤掉了 —— 它重读的历史被当成新消息再记一次，窗口统计从此虚高",
        )
    }

    @Test
    fun resolvedSampleHasNoCause() {
        val trace = InboxInjectionTrace()
        trace.record("g", 1, hint(resolved = true), at = 1)
        val snap = trace.snapshot()
        assertTrue(snap.causes.isEmpty())
        assertNull(trace.snapshot().causes.firstOrNull())
        assertFalse(snap.isEmpty)
    }
}
