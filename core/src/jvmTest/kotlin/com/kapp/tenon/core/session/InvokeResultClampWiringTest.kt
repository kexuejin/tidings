// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.session

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 接线钉子：`sendAgentInvokeResult` **真的**钳制 summary / error（真机现场）。
 *
 * 为什么单测不够：[InvokeResultFieldClampTest] 测的是 `clampInvokeResultFields`
 * 这个纯函数 —— 它绿只证明「有个函数能算对」。发送路径要是没调它，退单横幅
 * 照样以裸值进 payload，被自家校验整帧拒收（`flush drop … bad-payload:
 * agent-invoke-result`，发起方租约悬空）。与 [AgentEventFrameBudgetWiringTest]
 * 同一条纪律：这一族「发送侧忘了钳」的缺陷在本仓已两度活到真机。
 *
 * 读源码问两个问题：
 * 1. 函数体里有没有 `clampInvokeResultFields(`；
 * 2. payload builder 收到的是不是钳制后的字段（裸 `summary, error` 位置实参 = 被绕过）。
 */
class InvokeResultClampWiringTest {

    private val source: String = GuardSourceScan.stripComments(
        GuardSourceScan.read("core/src/commonMain/kotlin/com/kapp/tenon/core/session/NodeOutbound.kt"),
    )

    private fun sendAgentInvokeResultBody(): String =
        GuardSourceScan.bracedBlockAfter(source, "suspend fun sendAgentInvokeResult(")

    @Test
    fun theSendPathClampsTheTextFieldsBeforeBuildingThePayload() {
        val body = sendAgentInvokeResultBody()
        assertTrue(
            body.contains("clampInvokeResultFields("),
            "sendAgentInvokeResult 必须调用 clampInvokeResultFields —— error 装的是编排器退单横幅，" +
                "超 AGENT_NOTE 会被对端整帧拒收，发起方租约悬空。",
        )
    }

    @Test
    fun thePayloadIsBuiltFromTheClampedFieldsOnly() {
        val body = sendAgentInvokeResultBody()
        assertFalse(
            Regex("""agentInvokeResultPayload\([^)]*summary,\s*error""").containsMatchIn(body),
            "agentInvokeResultPayload 收到了裸 summary, error —— 钳制被绕过，毒帧仍会进队列。",
        )
        assertTrue(
            body.contains("clamped.summary") && body.contains("clamped.error"),
            "payload 必须由钳制后的字段构建。",
        )
    }
}
