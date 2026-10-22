// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **会话标题必须能跟着群消息走，且不破坏帧的成对纪律。**
 *
 * ## 真身
 *
 * 分段线的标题来自 `state.agentSessions` —— 那是**本机私有**数据（只由本机 agent 宿主
 * `saveSession` 写入），从来没有同步过对端。真机读数：手机侧那张表是**空的**，
 * 于是它那边每条分段线都只能显示「新会话」，而桌面端显示得出真标题。
 *
 * 消法：让标题跟着消息走（单帧自包含），而不是另开一条会话元数据同步通道
 * （那要维护「哪个对端知道哪些会话」的状态，还会出现「元数据到了、消息没到」的时序缺口）。
 */
class GroupTextSessionTitleTest {

    private fun payload(title: String?, sessionId: String? = "session-aaaa", cursor: Long? = 7L) =
        CodecMsg.groupTextPayload(
            text = "正文",
            groupId = "grp-1",
            groupRev = 7,
            agentSessionId = sessionId,
            agentCursor = cursor,
            agentSessionTitle = title,
        )

    /** 标题能编出去、也能解回来。 */
    @Test
    fun theTitleSurvivesTheWireAndValidates() {
        val p = payload("会话页体验问题")
        assertTrue(Codec.validatePayload(MsgTypes.MSG, p, Protocol.TEXT_TCP_LIMIT), "帧该合法")
        val obj = p as kotlinx.serialization.json.JsonObject
        assertEquals(
            "会话页体验问题",
            (obj["agentSessionTitle"] as? kotlinx.serialization.json.JsonPrimitive)?.content,
            "标题必须真的在载荷里（发送侧不产出它 = 对端永远只能显示「新会话」）",
        )
    }

    /** 没有标题时**不出现这个键**（`explicitNulls=false`）+ 帧仍合法。 */
    @Test
    fun anAbsentTitleDoesNotAddTheKey() {
        val p = payload(null)
        val obj = p as kotlinx.serialization.json.JsonObject
        assertFalse(obj.containsKey("agentSessionTitle"), "缺省标题不该在帧里留一个 null 键")
        assertTrue(Codec.validatePayload(MsgTypes.MSG, p, Protocol.TEXT_TCP_LIMIT))
    }

    /**
     * **没有 `agentSessionId` 的帧不许带标题。**
     *
     * 那句话没有主语：接收端只能忽略它（静默丢弃一段事实，比直接拒更糟）。
     */
    @Test
    fun aTitleWithoutASessionIsRejected() {
        val orphan = CodecMsg.groupTextPayload(
            text = "正文",
            groupId = "grp-1",
            groupRev = 7,
            agentSessionTitle = "孤儿标题",
        )
        assertFalse(
            Codec.validatePayload(MsgTypes.MSG, orphan, Protocol.TEXT_TCP_LIMIT),
            "标题必须在有会话归属的帧里才有意义",
        )
    }

    /** 标题过长整帧被拒 —— 所以发送侧必须截断（`clampGroupTitle`）。 */
    @Test
    fun anOverlongTitleIsRejectedByTheReceiver() {
        val tooLong = "字".repeat(Limits.GROUP_NAME + 1)
        assertFalse(
            Codec.validatePayload(MsgTypes.MSG, payload(tooLong), Protocol.TEXT_TCP_LIMIT),
            "超限标题整帧拒收，发送侧无感知 —— 所以要有 clampGroupTitle",
        )
    }

    /** 空白标题等同没有（界面「有标题/没标题」是两档渲染，空串两档都不落）。 */
    @Test
    fun blankTitlesCollapseToNull() {
        assertNull(app.relay.core.session.clampGroupTitle(null))
        assertNull(app.relay.core.session.clampGroupTitle(""))
        assertNull(app.relay.core.session.clampGroupTitle("   "))
        assertEquals("标题", app.relay.core.session.clampGroupTitle("  标题  "))
    }

    /** 超长标题被截到上限内 —— 「不产出注定被拒的帧」是发送侧的不变量。 */
    @Test
    fun anOverlongTitleIsClampedWithinBudget() {
        val long = "字".repeat(Limits.GROUP_NAME * 2)
        val clamped = assertNotNull(app.relay.core.session.clampGroupTitle(long))
        assertTrue(
            Codec.utf8ByteLength(clamped) <= Limits.GROUP_NAME,
            "截断后必须放得进 LIMITS.GROUP_NAME 字节，否则整帧被拒",
        )
    }
}
