// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * agent 的群消息身份 = **(设备, agent)**。
 *
 * 真机现场（2026-10-20 桌面 relay.db）：同一个 agent 在库里是 `agent:DSH` 860 条 /
 * `agent:dsh` 794 条 —— 只差大小写，一个 agent 显示成两个人。而更根本的是：
 * 只写 `agent:<kind>` 时，**两台设备上同名的 agent 在 senderId 里完全一样**，
 * 会被合并成同一个人（用户：「某个设备支持的工具？因为可能存在不同设备有自己的不同的 agent」）。
 */
class AgentSenderIdTest {

    private val host = "a1b2c3d4-6e74-4a7c-aab1-20a1d1284cd6"

    @Test
    fun theIdentityCarriesTheHostDevice() {
        assertEquals("agent:$host:dsh", AgentSenderId.of(host, "dsh"))
    }

    /** 两台设备上同名的 agent 必须是**两个**发送者。 */
    @Test
    fun theSameAgentNameOnTwoDevicesIsTwoIdentities() {
        val a = AgentSenderId.of(host, "dsh")
        val b = AgentSenderId.of("bd2d73c5-0ee0-4709-9600-02f650312115", "dsh")
        assertTrue(a != b, "两台设备上的 dsh 不能被当成同一个人：$a vs $b")
    }

    /** agentId 缺失时退回 kind；两者都空时退回 `dsh`，绝不产出 `agent::` 这种怪东西。 */
    @Test
    fun aMissingAgentIdFallsBackToTheKind() {
        assertEquals("agent:$host:native", AgentSenderId.of(host, null, "native"))
        assertEquals("agent:$host:dsh", AgentSenderId.of(host, "  ", null))
        assertEquals("agent:dsh", AgentSenderId.of("", "dsh"), "宿主缺失时退回老形态，不产出 agent::dsh")
    }

    /**
     * **长度预算**：`Limits.ID = 64`。超了会被接收侧 `isStr(senderId, Limits.ID)` 整帧拒收，
     * 而发送侧拿不到任何错误 —— 表现就是"对方没收到"。所以必须主动截断。
     */
    @Test
    fun theIdNeverExceedsTheProtocolLimit() {
        val long = "x".repeat(80)
        val id = AgentSenderId.of(host, long)
        assertTrue(id.length <= Limits.ID, "senderId 超长会被整帧拒收：len=${id.length} > ${Limits.ID}")
        assertEquals(AgentSenderId.MAX_AGENT_ID, id.removePrefix("agent:$host:").length)
    }

    /** 读侧要同时认新老两种形态（老版本对端还在写 `agent:<kind>`）。 */
    @Test
    fun bothShapesAreParseable() {
        assertEquals(host to "dsh", AgentSenderId.parse("agent:$host:dsh"))
        assertEquals(null to "dsh", AgentSenderId.parse("agent:dsh"), "老形态没有设备")
        assertNull(AgentSenderId.parse("a1b2c3d4-6e74-4a7c-aab1-20a1d1284cd6"), "人的 nodeId 不是 agent")
        assertNull(AgentSenderId.parse("agent:"))
    }
}
