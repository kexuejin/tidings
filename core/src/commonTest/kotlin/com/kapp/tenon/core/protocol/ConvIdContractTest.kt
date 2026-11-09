// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 会话键契约钉子（阶段 0 / C1）。
 *
 * 会话只有两种键：私聊 = 对方 nodeId（裸的），群聊 = `group:<groupId>`。判据是
 * [ConvId.isGroup]，**不是**"有没有前缀"。
 *
 * 这条钉子存在的理由：Team Folder 天然想给团队造一个 `team-<pluginId>` 键，而
 * `team-*` 不带 `group:` 前缀 ⇒ 被判成私聊 ⇒ 发送侧拿它当一个不存在的 nodeId 找对端
 * ⇒ 无目标 ⇒ **静默无帧**。所以这里把"team-* / plugin-* 不是群键"写成可执行断言：
 * 想引入新键的人会先撞上这个用例，而不是在真机上发现"手机端怎么没收到"。
 */
class ConvIdContractTest {

    @Test
    fun privateKeyIsTheBareNodeId() {
        assertEquals("node-a1b2", ConvId.single("node-a1b2"))
        assertFalse(ConvId.isGroup("node-a1b2"))
        assertNull(ConvId.groupIdOf("node-a1b2"))
    }

    @Test
    fun groupKeyUsesTheSinglePrefix() {
        assertEquals("group:", ConvId.GROUP_PREFIX)
        assertEquals("group:g1", ConvId.group("g1"))
        assertTrue(ConvId.isGroup("group:g1"))
        assertEquals("g1", ConvId.groupIdOf("group:g1"))
    }

    @Test
    fun teamAndPluginKeysAreNeverGroupKeys() {
        // 这些形态一旦被当成会话键，会走 resolveNodeIdentity 当对端解析 → 无目标 → 静默无帧。
        for (key in listOf("team-devops-swarm", "plugin-devops-swarm", "team:devops-swarm", "team_group:g1")) {
            assertFalse(
                ConvId.isGroup(key),
                key + " 被判成群键了：Team Folder 只能用 group:<groupId> 作会话键，成员行不持有 convId",
            )
            assertNull(ConvId.groupIdOf(key), key + " 不应解析出 groupId")
        }
    }

    @Test
    fun groupIdMayContainColon() {
        // groupId 由对端给，可以自带冒号 —— 所以解析必须是 removePrefix，而不是 split(':')。
        assertEquals("g:1", ConvId.groupIdOf(ConvId.group("g:1")))
        assertEquals("g:1", ConvId.groupIdOf("group:g:1"))
    }
}
