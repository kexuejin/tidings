// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `agent-approval` 的 `riskLevel` / `diff` 白名单判据（**走真解码**，AGENTS §4：
 * 判据必须过线，不许 `recordInbound` 直喂台账绕过编解码）。
 *
 * 缺陷现场：§14.3 声明交付了 `riskLevel`（L1/L2/L3）与 `diff`，builder 一直在发
 * （`ExternalPluginDriver` 默认就带 L2，仓外脚本发 L3），而 `AGENT_APPROVAL_EXPECTED_KEYS`
 * 没跟上 ⇒ 对端 `hasOnlyKeys` 整帧 extra-field 拒收 ⇒ 无 ACK ⇒ 重试入队 ⇒ 队列重放
 * 用 `decodeTcp` 仍拒 ⇒ 行被删。发送侧一切正常、插件审批永远到不了手机 —— 与
 * `senderId`（AGENTS §3-#1）、`agent-ack.kind` 漏值（Round 5）同族。
 */
class AgentApprovalWhitelistTest {

    private companion object {
        const val ID = "33333333-3333-4333-8333-333333333333"
        const val PEER = "11111111-1111-4111-8111-111111111111"
        const val TS = 1_700_000_000_000L

        fun accept(json: String): DecodeResult.Ok =
            Codec.decodeTcp(json) as? DecodeResult.Ok ?: fail("期望解码通过，实际被拒：$json")
    }

    private fun approvalJson(riskLevel: String?, diff: String?): String {
        val payload = Codec.agentApprovalPayload(
            approvalId = "a-1", sessionId = "s-1",
            tool = "bash", summary = "execute rm",
            expiresAt = TS + 60_000, choices = "allow,deny",
            riskLevel = riskLevel, diff = diff,
        ).jsonObject
        val fields = buildList {
            add("\"approvalId\":\"a-1\"")
            add("\"sessionId\":\"s-1\"")
            add("\"tool\":\"bash\"")
            add("\"summary\":\"execute rm\"")
            add("\"expiresAt\":${TS + 60_000}")
            add("\"choices\":\"allow,deny\"")
            if (riskLevel != null) add("\"riskLevel\":\"$riskLevel\"")
            if (diff != null) add("\"diff\":\"$diff\"")
        }
        return """{"v":1,"type":"${MsgTypes.AGENT_APPROVAL}","id":"$ID","from":"$PEER","ts":$TS,"payload":{${fields.joinToString(",")}}}"""
    }

    @Test
    fun `approval with riskLevel and diff decodes - the shipped plugin shape`() {
        val ok = accept(approvalJson("L2", "--- a/f.kt\n+++ b/f.kt"))
        val payload = ok.env.payload as? kotlinx.serialization.json.JsonObject ?: fail("payload 应为对象")
        assertEquals("L2", (payload["riskLevel"] as? JsonPrimitive)?.content)
        assertTrue(((payload["diff"] as? JsonPrimitive)?.content ?: "").startsWith("---"), "diff 必须原样到对端")
    }

    @Test
    fun `riskLevel outside the domain rejects the whole frame`() {
        val r = Codec.decodeTcp(approvalJson("L4", null))
        assertTrue(r is DecodeResult.Rejected, "L4 不在 [AgentRiskLevels] 域内 ⇒ 整帧拒（键对、值不在白名单=§3-#7）")
    }

    @Test
    fun `oversized diff rejects instead of being truncated`() {
        val big = "x".repeat(Limits.AGENT_DIFF + 1)
        val r = Codec.decodeTcp(approvalJson("L2", big))
        assertTrue(r is DecodeResult.Rejected, "diff 超预算 ⇒ 拒绝不截断（截断的 diff 比没有更危险）")
    }

    @Test
    fun `unknown extra key still rejects - whitelist stays strict`() {
        val extra = approvalJson("L2", null).replace(
            """"riskLevel":"L2"""",
            """"riskLevel":"L2","sneaky":1""",
        )
        val r = Codec.decodeTcp(extra)
        assertTrue(r is DecodeResult.Rejected, "白名单修复不许放松成容忍：未知键仍整帧拒")
    }

    @Test
    fun `driver default references the single value-domain source`() {
        // 消费端共同引用同一份取值域（AGENTS §3-#7）：驱动默认档与 UI 确认判定
        // 都不许各自写字面量。
        assertTrue(AgentRiskLevels.isKnown(AgentRiskLevels.MODIFYING))
        assertTrue(AgentRiskLevels.isKnown(AgentRiskLevels.HIGH))
        assertTrue(!AgentRiskLevels.isKnown("high"), "生态里出现过的旁写（high）必须显式挡在线外")
    }
}
