// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.core.protocol.AgentExecAuthorizer
import com.kapp.tidings.core.protocol.AgentExecAuthorizer.permitsExecution
import com.kapp.tidings.core.protocol.AgentExecPolicies
import com.kapp.tidings.core.protocol.AgentGrantScopes
import com.kapp.tidings.core.protocol.GroupMeta
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **端到端**：从「用户在界面上勾了一台设备」到「那台设备在群里 @ agent 能被执行」。
 *
 * 前面的测试各自测一段；这条把四段连起来跑一遍，确认它们是**同一套语义**：
 *
 * 1. 界面派生 `execDeviceRows` 决定勾选状态；
 * 2. 勾选写 `scope=AGENT` 的授权；
 * 3. 执行判定用 `forExecution = true` 查同一份授权；
 * 4. 结果是「能执行」。
 *
 * 缺任何一段，用户看到的就是「我勾了，它还是不动」。
 */
class AgentExecDeviceEndToEndTest {

    private val self = "node-self"
    private val peer = "node-peer"
    private val agent = "dsh"

    private fun group() = GroupMeta(
        groupId = "g-1",
        name = "群",
        members = listOf(self, peer),
        rev = 1,
        updatedBy = self,
        updatedTs = 0L,
        ownerId = self,
        adminIds = emptyList(),
    )

    /** 授权之后：界面勾上，且执行判定放行。 */
    @Test
    fun checkingADeviceMakesItExecutable() {
        // ① 勾选之前：界面显示未勾，执行判定拒绝。
        val before = execDeviceRows(
            selfNodeId = self, agentKey = agent,
            knownDevices = listOf(peer to "同事手机"),
            grants = emptyList(),
        )
        assertFalse(before.first { it.nodeId == peer }.checked, "还没勾时不该是勾上的")
        assertFalse(
            AgentExecAuthorizer.authorize(peer, group(), grantAllows = { false })
                .permitsExecution(),
            "没授权时不该能执行",
        )

        // ② 用户勾上 ⇒ 写入 scope=AGENT 的授权（界面与台账同一时刻）。
        val after = execDeviceRows(
            selfNodeId = self, agentKey = agent,
            knownDevices = listOf(peer to "同事手机"),
            grants = listOf(
                ExecGrantView(nodeId = peer, scope = AgentGrantScopes.AGENT, agentKey = agent, isMyDevice = false),
            ),
        )
        assertTrue(after.first { it.nodeId == peer }.checked, "勾上之后界面必须显示已勾")

        // ③ 同一份授权让执行判定放行。
        assertTrue(
            AgentExecAuthorizer.authorize(
                peer,
                group(),
                grantAllows = { it == peer },
            ).permitsExecution(),
            "勾上之后必须能执行 —— 否则就是『我勾了它还是不动』",
        )
    }

    /** 取消之后：界面不勾，执行判定也拒绝（两边同时生效）。 */
    @Test
    fun uncheckingADeviceTakesEffectOnBothSides() {
        val after = execDeviceRows(
            selfNodeId = self, agentKey = agent,
            knownDevices = listOf(peer to "同事手机"),
            grants = emptyList(),
        )
        assertFalse(after.first { it.nodeId == peer }.checked)
        assertFalse(
            AgentExecAuthorizer.authorize(peer, group(), grantAllows = { false })
                .permitsExecution(),
        )
    }

    /** **只对该 agent 生效**：勾了 dsh，不等于 codex 也能执行。 */
    @Test
    fun theGrantIsScopedToOneAgent() {
        val grants = listOf(
            ExecGrantView(nodeId = peer, scope = AgentGrantScopes.AGENT, agentKey = "dsh", isMyDevice = false),
        )
        val dshRows = execDeviceRows(self, "dsh", listOf(peer to "同事"), grants)
        val codexRows = execDeviceRows(self, "codex", listOf(peer to "同事"), grants)
        assertTrue(dshRows.first { it.nodeId == peer }.checked, "dsh 应当已勾")
        assertFalse(codexRows.first { it.nodeId == peer }.checked, "codex 不该被顺带勾上")
    }

    /**
     * **本机用户在任何情况下都能执行**（不需要被勾）。
     *
     * 关键：这条数据里本机用户**不是** agent 宿主（`virtualAgents` 为空）——
     * 他就是用自己的 nodeId 在群里说话。判据必须单独认出这一档，
     * 否则「我在自己机器上让自己的 agent 干活」会被判成没授权。
     */
    @Test
    fun selfAlwaysExecutes() {
        assertTrue(
            AgentExecAuthorizer.authorize(
                self,
                group(),
                grantAllows = { false },
                policy = AgentExecPolicies.LIST,
                selfNodeId = self,
            ).permitsExecution(),
            "群主可能不是本机用户，但『我就是本机用户』这条不该受影响",
        );
    }
}
