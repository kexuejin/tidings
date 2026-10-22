// SPDX-License-Identifier: GPL-3.0-only

package app.relay.agent

import app.relay.core.protocol.AgentGrantAccess
import app.relay.core.protocol.AgentGrantScopes
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「能看」与「能执行」必须是两个判定。
 *
 * ## 守的是什么
 *
 * 批准一台设备为可信（`scope=ALL`），意味着它**能看**本机的 agent 列表、会话流、产物 ——
 * 那是对的。但「能让我的机器跑命令」风险完全不同：
 *
 * 批准那台设备的初心可能是「让他用 dsh 做项目协作」，而三个月后装了 codex 并给它
 * 更宽的权限 —— 如果 `scope=ALL` 继续自动覆盖，**同事在无人察觉的情况下拿到了新
 * agent 的执行权**。
 *
 * 所以执行面（`forExecution = true`）要求**逐 agent 显式授权**；唯一的例外是本机用户。
 */
class AgentGrantExecutionScopeTest {

    private fun store(): AgentGrantStore =
        AgentGrantStore(File.createTempFile("grants", ".json").also { it.delete() })

    private val peer = "node-peer"
    private val agentKey = "dsh"

    /** **可信设备（scope=ALL）能看，但不能凭它执行**。 */
    @Test
    fun aTrustedDeviceCanReadButNotExecute() {
        val s = store()
        s.setPolicy("byDevice")
        s.grant(peer, AgentGrantScopes.ALL, agentKey = null, access = AgentGrantAccess.RW, grantedBy = "me", at = 1L)

        assertTrue(
            s.allows(peer, agentKey = agentKey, requireRw = true, forExecution = false),
            "可信设备应当能看",
        )
        assertFalse(
            s.allows(peer, agentKey = agentKey, requireRw = true, forExecution = true),
            "可信设备不该凭 scope=ALL 就获得执行权 —— 那会让新装的 agent 自动继承",
        )
    }

    /** 逐 agent 授权之后，执行面才放行。 */
    @Test
    fun anExplicitPerAgentGrantAllowsExecution() {
        val s = store()
        s.setPolicy("byDevice")
        s.grant(peer, AgentGrantScopes.ALL, agentKey = null, access = AgentGrantAccess.RW, grantedBy = "me", at = 1L)
        s.grant(peer, AgentGrantScopes.AGENT, agentKey = agentKey, access = AgentGrantAccess.RW, grantedBy = "me", at = 2L)

        assertTrue(s.allows(peer, agentKey = agentKey, requireRw = true, forExecution = true))
    }

    /** **逐 agent 授权只对那一个 agent 生效**（装了新的不会自动继承）。 */
    @Test
    fun aPerAgentGrantDoesNotLeakToOtherAgents() {
        val s = store()
        s.setPolicy("byDevice")
        s.grant(peer, AgentGrantScopes.AGENT, agentKey = "dsh", access = AgentGrantAccess.RW, grantedBy = "me", at = 1L)

        assertTrue(s.allows(peer, agentKey = "dsh", requireRw = true, forExecution = true))
        assertFalse(
            s.allows(peer, agentKey = "codex", requireRw = true, forExecution = true),
            "授权了 dsh 不等于授权了 codex",
        )
    }

    /** **本机用户是唯一例外**：`isMyDevice` 在执行面仍然放行（他本来就在自己机器上）。 */
    @Test
    fun myOwnDeviceIsTheOnlyScopeAllException() {
        val s = store()
        s.setPolicy("byDevice")
        s.grant(
            peer, AgentGrantScopes.ALL, agentKey = null,
            access = AgentGrantAccess.OWNER, isMyDevice = true, grantedBy = "me", at = 1L,
        )
        assertTrue(s.allows(peer, agentKey = "anyAgent", requireRw = true, forExecution = true))
    }
}
