// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

import com.kapp.tenon.core.protocol.AgentGrantAccess
import com.kapp.tenon.core.protocol.AgentGrantScopes
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

    /**
     * **线上身份的大小写不能决定授权结果**。
     *
     * 台账存的是注册时的原样（面板 `@` 到 `DSH` 就存 `DSH`），而卡片动作帧里的
     * `agentId` 是 `AgentSenderId.parse` 从 `senderId` 反解出来的 —— 写进去时被
     * `AgentSenderId.of` 小写化成 `agent:<host>:dsh`。按字面比 ⇒ 同一个 agent
     * 每次动作都被判无权限。
     *
     * 真机 A22 现场：手机点「合并此补丁」，帧到了宿主（dedup 有、ACK 已回），
     * `allows(node, agentKey="dsh", forExecution=true)` 撞上台账的 `DSH` 判假，
     * 于是走「被拒」分支；而 1:1 的拒绝横幅需要群 ⇒ 也发不出去 ⇒ 点了彻底没反应。
     */
    @Test
    fun aGrantMatchesTheLowercasedWireIdentity() {
        val s = store()
        s.setPolicy("byDevice")
        s.grant(peer, AgentGrantScopes.AGENT, agentKey = "DSH", access = AgentGrantAccess.RW, grantedBy = "me", at = 1L)

        assertTrue(
            s.allows(peer, agentKey = "dsh", requireRw = true, forExecution = true),
            "动作帧带回的是小写化后的 agentId，台账里是大写 —— 必须认成同一个身份",
        )
        assertTrue(
            s.allows(peer, agentKey = "DSH", requireRw = true, forExecution = true),
            "原样大小写当然也要放行",
        )
    }

    /** 归一大小写**不等于**放宽作用域：别的 agent 照旧不能借这条授权执行。 */
    @Test
    fun caseFoldingDoesNotLeakTheGrantToAnotherAgent() {
        val s = store()
        s.setPolicy("byDevice")
        s.grant(peer, AgentGrantScopes.AGENT, agentKey = "DSH", access = AgentGrantAccess.RW, grantedBy = "me", at = 1L)

        assertFalse(
            s.allows(peer, agentKey = "codex", requireRw = true, forExecution = true),
            "授权了 DSH 不等于授权了 codex",
        )
        assertFalse(
            s.allows(peer, agentKey = "dsh-cli", requireRw = true, forExecution = true),
            "前缀相同但名字不同，不许被当成同一个 agent",
        )
    }
}
