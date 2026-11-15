// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.session

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 接线钉子：`sendAgentEvent` **真的**按编码后帧长钳制（ARP-17）。
 *
 * 为什么单测不够：[AgentEventFrameBudgetTest] 测的是 `clampAgentEventBody` 这个纯函数 ——
 * 它绿只证明「有个函数能算对」，不能证明发送路径调了它、更不能证明用的是**真编码器**
 * 量出来的帧长（而不是随手传个 `{ it.length }`）。这一族缺陷在本仓有过先例：
 * `latestPatchRefForCard` 的守卫只锚了另一个函数名，缺陷因此活到真机（GOAL Round 51）。
 *
 * 所以这里读源码，问三个问题：
 * 1. 函数体里有没有 `clampAgentEventBody(`；
 * 2. 传进去的是不是 `frameBytes =`（真编码器那条路），而不是默认的字节截断；
 * 3. `agentEventPayload(...)` 里还有没有裸 `body`（有 = 钳制被绕过）。
 */
class AgentEventFrameBudgetWiringTest {

    private val source: String = GuardSourceScan.stripComments(
        GuardSourceScan.read("core/src/commonMain/kotlin/com/kapp/tenon/core/session/NodeOutbound.kt"),
    )

    private fun sendAgentEventBody(): String =
        GuardSourceScan.bracedBlockAfter(source, "suspend fun sendAgentEvent(")

    @Test
    fun theSendPathClampsTheBodyBeforeBuildingTheEnvelope() {
        val body = sendAgentEventBody()
        assertTrue(
            body.contains("clampAgentEventBody("),
            "sendAgentEvent 必须调用 clampAgentEventBody —— 否则 body 合法就默认信封合法，" +
                "而 AGENT_BODY_TCP_LIMIT 与整帧硬上限是同一个数。",
        )
    }

    @Test
    fun theClampIsMeasuredByTheRealEncoderNotByRawBytes() {
        val body = sendAgentEventBody()
        assertTrue(
            body.contains("frameBytes ="),
            "必须把「编码后帧长」的度量传进去（frameBytes =）—— 默认参数只是按原始字节截，" +
                "转义密集的 body（引号/反斜杠/换行）照样会产出两倍上限的帧。",
        )
        // 线框实测（code review #2）：msg 帧比信封多 26 字节包装
        // （{"type":"msg","envelope":… + }），量裸信封会在 (MAX-26, MAX] 窗口放行
        // 超帧——接收侧整链判死。msgFrameWireBytes = 真编码器 + 包装，比裸
        // Codec.encode 更强（把包装也算进度量）。
        assertTrue(
            body.contains("msgFrameWireBytes("),
            "帧长必须由线框实测给出（TcpFrames.msgFrameWireBytes = 真编码器 + msg 包装）——" +
                "量裸信封会在 (MAX-26, MAX] 窗口放行超帧。",
        )
    }

    @Test
    fun theEnvelopeIsBuiltFromTheClampedBodyOnly() {
        val body = sendAgentEventBody()
        // 只匹配传给 payload builder 的那个实参位置，不必解析整个表达式。
        val rawBodyPassed = Regex("""agentEventPayload\([^)]*\bbody\b""").containsMatchIn(body)
        assertFalse(
            rawBodyPassed,
            "agentEventPayload 里出现了裸 body —— 钳制结果被绕过，超帧仍会发出去。",
        )
        assertTrue(
            body.contains("envelopeOf(clamped)"),
            "发出去的信封必须由钳制后的 body 构建。",
        )
    }
}
