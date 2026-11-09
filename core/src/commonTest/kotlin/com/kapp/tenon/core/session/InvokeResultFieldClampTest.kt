// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.session

import com.kapp.tenon.core.protocol.Codec
import com.kapp.tenon.core.protocol.Limits
import com.kapp.tenon.core.protocol.MsgTypes
import com.kapp.tenon.core.protocol.Protocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.put

/**
 * `agent-invoke-result` 的 summary / error 发送侧钳制（真机现场）。
 *
 * 现场：模拟器 1:1 直派被自愈退单，宿主回 `status=error` 的结果帧 —— error 字段
 * 装的是**完整退单横幅**（首因+末因+HEAL_EXHAUSTED 标签+人助提示），超过
 * `AGENT_NOTE`（200 字节）⇒ 发送方自己在 flush 队列重放时被自家的
 * `validateInvokeResult` 整帧拒收（`flush drop … bad-payload:agent-invoke-result,
 * 1712 B`），发起方租约永远悬空。完整横幅不丢：它住在卡片 banner 里。
 *
 * 三条判据：短句原样（不画蛇添足）、超限截到字节上限内（截点在码点边界）、
 * 钳制后的 payload 必须过得了对端校验 —— 加一条**阴性对照**证明校验真的咬人。
 */
class InvokeResultFieldClampTest {

    /** 编排器退单横幅的真实形状（DesktopWorkerOrchestrator blocked 终局）。 */
    private val handedBackBanner =
        "Handed back: exceeded max heal rounds (3) — round 1: cli exited with code 1 " +
            "(stderr tail: CUDA out of memory); round 3: No patch produced by worker" +
            " | HEAL_EXHAUSTED(rounds=3)" +
            " | one optional human-steering round remains (worktree kept)"

    // ---- 阴性对照：校验真的会拒 ----

    @Test
    fun anOverLimitErrorIsRejectedByTheValidatorWithoutTheClamp() {
        assertTrue(
            Codec.utf8ByteLength(handedBackBanner) > Limits.AGENT_NOTE,
            "前置：退单横幅确实超过 AGENT_NOTE（现场 1712 B 帧的前提）",
        )
        val payload = Codec.agentInvokeResultPayload(
            invokeId = "inv-hb-1", ok = false, status = "error",
            summary = null, error = handedBackBanner, card = null, at = null,
        )
        assertFalse(
            Codec.validatePayload(MsgTypes.AGENT_INVOKE_RESULT, payload, Protocol.TEXT_TCP_LIMIT),
            "超限 error 必须被对端整帧拒收 —— 这是本缺陷的成因，也是钳制的存在理由",
        )
    }

    // ---- 钳制行为 ----

    @Test
    fun shortFieldsPassThroughUntouched() {
        val clamped = clampInvokeResultFields("done in 3 steps", "cli exited with code 1")
        assertEquals("done in 3 steps", clamped.summary)
        assertEquals("cli exited with code 1", clamped.error)
    }

    @Test
    fun nullStaysNull() {
        val clamped = clampInvokeResultFields(null, null)
        assertEquals(null, clamped.summary)
        assertEquals(null, clamped.error)
    }

    @Test
    fun exactlyAtLimitIsUntouched() {
        val s = "s".repeat(Limits.AGENT_INVOKE_SUMMARY - 1) // 省略号预算 3 字节
        val e = "x".repeat(Limits.AGENT_NOTE - 3)
        val clamped = clampInvokeResultFields(s, e)
        assertEquals(s, clamped.summary)
        assertEquals(e, clamped.error)
    }

    @Test
    fun overLimitFieldsAreCutInsideTheByteBudget() {
        val clamped = clampInvokeResultFields(handedBackBanner, handedBackBanner)
        assertTrue(
            Codec.utf8ByteLength(clamped.summary ?: "") <= Limits.AGENT_INVOKE_SUMMARY,
            "summary 截后必须落在 AGENT_INVOKE_SUMMARY 字节内",
        )
        assertTrue(
            Codec.utf8ByteLength(clamped.error ?: "") <= Limits.AGENT_NOTE,
            "error 截后必须落在 AGENT_NOTE 字节内（对端按字节判）",
        )
        assertTrue((clamped.error ?: "").endsWith("…"), "截断要留省略号，读的人知道有下文")
    }

    @Test
    fun cjkTextIsCutByBytesNotByCodeUnits() {
        // 中文一字 3 字节：按码元切会得到一个比字节上限还大的字符串（clampUtf8 的 KDoc 例子）。
        val cjk = "受阻".repeat(200) // 400 字 = 1200 字节 ≫ 200
        val clamped = clampInvokeResultFields(null, cjk)
        assertTrue(
            Codec.utf8ByteLength(clamped.error ?: "") <= Limits.AGENT_NOTE,
            "中文 error 截后仍必须在字节上限内",
        )
        assertFalse((clamped.error ?: "").contains("\uD83D"), "切点不许落在代理对中间")
    }

    // ---- 端到端：钳制后的 payload 必须过得了对端校验 ----

    @Test
    fun clampedPayloadPassesTheReceiveSideValidation() {
        val clamped = clampInvokeResultFields(handedBackBanner, handedBackBanner)
        val payload = Codec.agentInvokeResultPayload(
            invokeId = "inv-hb-2", ok = false, status = "error",
            summary = clamped.summary, error = clamped.error,
            card = kotlinx.serialization.json.buildJsonObject {
                put("cardId", "chain-tmw8z5229")
                put("rev", 1)
            },
            at = null,
        )
        assertTrue(
            Codec.validatePayload(MsgTypes.AGENT_INVOKE_RESULT, payload, Protocol.TEXT_TCP_LIMIT),
            "发送侧钳制后的结果帧必须能被对端完整接收（修复现场：flush drop bad-payload）",
        )
    }
}
