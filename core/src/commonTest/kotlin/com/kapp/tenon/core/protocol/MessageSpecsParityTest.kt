// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 四张集合派生化后的 parity 钉子：把改造前四段手写 `setOf` 的名单以字符串常量
 * 形式逐项写死在这里，与「由 MessageSpecs 派生」的结果逐项比对。
 *
 * 钉子用帧类型串字面量，不引用 [MsgTypes] 的派生 `val`，以免自证。
 */
class MessageSpecsParityTest {

    /** 改造前 RELIABLE 的 26 项（与 scripts/e2e/relay_e2e.py 的 RELIABLE_TYPES 逐项相等）。 */
    private val preRefactorReliable = setOf(
        "msg", "file-ctl", "group", "avatar", "update", "share", "screen",
        "agent-session", "agent-event",
        "agent-queue", "agent-run", "agent-approval", "agent-cancel", "agent-ack",
        "agent-artifact",
        "agent-workspace",
        "agent-grant-request", "agent-grant", "agent-grant-list",
        "recovery-req", "recovery-data",
        "agent-session-page",
        "agent-invoke", "agent-invoke-result",
        "agent-channel", "agent-channel-result",
    )

    /** 改造前 HANDLED 的 32 项。 */
    private val preRefactorHandled = setOf(
        "entry", "alive", "exit", "presence", "profile",
        "msg", "ack", "file-ctl",
        "agent-session", "agent-event",
        "agent-queue", "agent-run", "agent-approval", "agent-cancel", "agent-ack",
        "agent-artifact",
        "agent-workspace", "agent-grant-request", "agent-grant", "agent-grant-list", "agent-session-page",
        "group", "share", "scan-ranges", "screen", "avatar",
        "recovery-req", "recovery-data",
        "agent-invoke", "agent-invoke-result",
        "agent-channel", "agent-channel-result",
    )

    /** 改造前 UNHANDLED 的 2 项。 */
    private val preRefactorUnhandled = setOf("update", "peers")

    /** 改造前 KNOWN 的 34 项（atr1 两帧是后到的本端增补，不在这份清单里 —— 见下方差集断言）。 */
    private val preRefactorKnown = setOf(
        "entry", "alive", "exit", "presence", "profile", "peers", "scan-ranges", "msg", "ack",
        "file-ctl", "group", "avatar", "update", "share", "screen",
        "agent-session", "agent-event",
        "agent-queue", "agent-run", "agent-approval", "agent-cancel", "agent-ack",
        "agent-artifact",
        "agent-workspace", "agent-grant-request", "agent-grant", "agent-grant-list", "agent-session-page",
        "recovery-req", "recovery-data",
        "agent-invoke", "agent-invoke-result",
        "agent-channel", "agent-channel-result",
    )

    @Test
    fun theFourDerivedSetsKeepTheirLegacySizes() {
        assertEquals(36, MsgTypes.KNOWN.size)
        assertEquals(28, MsgTypes.RELIABLE.size)
        assertEquals(34, MsgTypes.HANDLED.size)
        assertEquals(2, MsgTypes.UNHANDLED.size)
    }

    @Test
    fun knownIsExactlyHandledPlusUnhandled() {
        assertEquals(emptySet<String>(), MsgTypes.HANDLED intersect MsgTypes.UNHANDLED)
        assertEquals(MsgTypes.KNOWN, MsgTypes.HANDLED + MsgTypes.UNHANDLED)
    }

    @Test
    fun reliableIsASubsetOfKnown() {
        assertTrue(MsgTypes.KNOWN.containsAll(MsgTypes.RELIABLE))
    }

    @Test
    fun everySpecTypeIsUniqueAndCoversKnown() {
        assertEquals(36, MessageSpecs.all.size)
        assertEquals(MessageSpecs.all.size, MessageSpecs.all.map { it.type }.toSet().size)
        assertEquals(MessageSpecs.all.size, MessageSpecs.byType.size)
        assertEquals(MsgTypes.KNOWN, MessageSpecs.all.map { it.type }.toSet())
    }

    @Test
    fun theFourSetsAndSpecFlagsMatchThePreRefactorLists() {
        assertEquals(34, preRefactorKnown.size)
        assertEquals(26, preRefactorReliable.size)
        assertEquals(32, preRefactorHandled.size)
        assertEquals(2, preRefactorUnhandled.size)

        // 免批规则读撤的两帧（atr1）是改造**之后**的本端增补 —— 改造前清单保持原样，
        // 集合对比改为「改造前清单 ⊆ 现清单，差集恰好是这两帧」：既有类型一个不许动、
        // 新增类型必须成对申报，两个方向都钉住。
        val extensionFrames = setOf("agent-tool-rules-request", "agent-tool-rules-list")
        assertTrue(MsgTypes.KNOWN.containsAll(preRefactorKnown))
        assertEquals(extensionFrames, MsgTypes.KNOWN - preRefactorKnown)
        assertTrue(MsgTypes.RELIABLE.containsAll(preRefactorReliable))
        assertEquals(extensionFrames, MsgTypes.RELIABLE - preRefactorReliable)
        assertTrue(MsgTypes.HANDLED.containsAll(preRefactorHandled))
        assertEquals(extensionFrames, MsgTypes.HANDLED - preRefactorHandled)
        assertEquals(preRefactorUnhandled, MsgTypes.UNHANDLED)

        for (spec in MessageSpecs.all) {
            assertEquals(true, spec.known, "${spec.type} known flag")
            // 改造前的类型逐字沿用旧 flag；增补的两帧（atr1）自己申报 reliable/handled
            // —— 它们的申报受上面的差集断言约束（新增必须进 RELIABLE 与 HANDLED）。
            if (spec.type in preRefactorKnown) {
                assertEquals(
                    spec.type in preRefactorReliable, spec.reliable,
                    "${spec.type} reliable flag mismatch",
                )
                assertEquals(
                    spec.type in preRefactorHandled, spec.handled,
                    "${spec.type} handled flag mismatch",
                )
            } else {
                assertTrue(spec.reliable, "${spec.type}（增补帧）必须可靠投递")
                assertTrue(spec.handled, "${spec.type}（增补帧）必须有人处理")
            }
        }
    }
}
