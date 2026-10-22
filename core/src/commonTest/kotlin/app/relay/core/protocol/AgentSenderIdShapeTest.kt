// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **`senderId` 必须能被无歧义地还原，且永不超 `Limits.ID` 字节。**
 *
 * 两个真实缺陷（都在这一版修掉，各有真机后果）：
 *
 * ## ① 截断按字符而不是字节
 *
 * `Limits.ID = 64` 校验的是 **UTF-8 字节长度**，而 `take(21)` 数的是字符。
 * 21 个中文 = 45 字节，`agent:`(6) + UUID(36) + `:`(1) + 45 = **88 > 64** ⇒
 * `isStr(o["senderId"], Limits.ID)` 为假 ⇒ **整帧拒收**。而发送侧拿不到任何错误 ——
 * 症状是「对方没收到」，与本类 KDoc 记的那条一模一样。
 *
 * ## ② 写侧不禁冒号、读侧按冒号切
 *
 * 写进 `agent:host:my:agent`、`parse` 用 `substringAfterLast(':')` 读回来是
 * `(host, "agent")` —— 身份被悄悄改写，两个不同的 agent 会合并成同一个人。
 */
class AgentSenderIdShapeTest {

    private val host = "a1b2c3d4-6e74-4a7c-aab1-20a1d1284cd6"

    /** **产出的 id 必须放得进 `Limits.ID` 字节。** 中文 agent 名也不许超。 */
    @Test
    fun theProducedIdNeverExceedsTheByteBudget() {
        val cases = listOf(
            "dsh",
            "智能体名字很长很长的二十一个字",
            "a".repeat(200),
            "codex-with-a-very-long-alias",
            "中文-混合-English-名字",
        )
        for (raw in cases) {
            val id = AgentSenderId.of(host, raw, "dsh")
            val bytes = id.encodeToByteArray().size
            assertTrue(
                bytes <= Limits.ID,
                "senderId 超字节预算会让整帧被拒收（发送侧无感知）：" + raw.take(12) +
                    " → " + bytes + " > " + Limits.ID,
            )
        }
    }

    /** **写进去什么，读回来就是什么** —— 含冒号的 agent 名不许被截断。 */
    @Test
    fun aColonInTheAgentNameDoesNotChangeTheParsedIdentity() {
        val id = AgentSenderId.of(host, "my:agent", "dsh")
        val parsed = AgentSenderId.parse(id)
        assertEquals(host, parsed?.first, "host 必须还原")
        assertTrue(
            parsed?.second?.isNotEmpty() == true,
            "agentId 不该被切成空：写侧要保证它不含分隔符",
        )
        // 归一化之后冒号变成 `-`，身份不会再被切开。
        assertEquals("my-agent", parsed?.second)
    }

    /** 往返恒等：任意输入产出的 id，parse 回来必须还是 (host, 规范化后的名字)。 */
    @Test
    fun everyProducedIdRoundTrips() {
        val names = listOf("dsh", "DSH", "my:agent", "中文名", "a b_c", "x".repeat(80))
        for (raw in names) {
            val id = AgentSenderId.of(host, raw, "dsh")
            val parsed = AgentSenderId.parse(id)
            assertEquals(host, parsed?.first, "host 往返失败：" + raw)
            assertTrue(
                parsed?.second?.isNotBlank() == true,
                "agentId 往返成了空：" + raw + " → " + id,
            )
            // 再跑一遍不该再变（幂等）
            assertEquals(id, AgentSenderId.of(host, parsed?.second, "dsh"), "归一化必须幂等：" + raw)
        }
    }

    /** 老形态 `agent:<kind>` 仍然能读（旧端发的）。 */
    @Test
    fun theLegacyFormStillParses() {
        val parsed = AgentSenderId.parse("agent:dsh")
        assertEquals(null, parsed?.first, "老形态没有 host")
        assertEquals("dsh", parsed?.second)
    }
}
