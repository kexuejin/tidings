// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.core.protocol.TeamMemberInfo
import com.kapp.tenon.core.protocol.MsgKinds
import com.kapp.tenon.core.protocol.MsgTypes
import com.kapp.tenon.core.protocol.makeEnvelope
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.kapp.tenon.platform.InMemoryAppPlatform
import com.kapp.tenon.testutil.awaitUntil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §4-8b 的**唤醒接线**集成钉子：队还没进这个群（本机有档案、群无席位）时，
 * 手打 `@队名` 也要把派单路由唤醒 —— 编排层接住后出「把队加进这个群」的
 * blocked 卡。修复前路由层无席位就不唤醒（候选里没有、手打也是「点了没反应」），
 * 编排层的 TEAM_NOT_IN_GROUP 分支永远够不着。
 *
 * 编排层自己的两段（resolveTeamTarget 的 NotInGroup 判据、blocked 卡带
 * teamIdForAttach）各有测试钉着；这里钉的是**三段之间的接线**。
 */
class UnattachedTeamWakeTest {

    private val self = "11111111-1111-4111-8111-111111111111"
    private val phone = "22222222-2222-4222-8222-222222222222"
    private val groupId = "g-unattached"

    private fun settings(): RelaySettings = RelaySettings.firstRun().copy(
        udpPort = 0, tcpPort = 0,
        persistMessages = false, persistMessagesDecided = true,
        groups = listOf(
            GroupMeta(
                groupId = groupId,
                name = "协同群",
                members = listOf(self, phone),
                rev = 3,
                updatedBy = self,
                updatedTs = 1,
                // **没有团队席位**：队还没进这个群（或已被移出）。
                virtualAgents = emptyList(),
            ),
        ),
        teamProfiles = listOf(
            TeamProfile(
                teamId = "team-e2e",
                name = "e2e-team",
                leaderAgentId = "e2e-team-lead",
                members = listOf(TeamMemberInfo("m-e2e-coder", "coder", "编码")),
                rev = 1,
            ),
        ),
    )

    private fun groupText(id: String, text: String) = makeEnvelope(
        type = MsgTypes.MSG,
        id = id,
        from = phone,
        payload = buildJsonObject {
            put("kind", MsgKinds.GROUP_TEXT)
            put("text", text)
            put("groupId", groupId)
            put("groupRev", 3)
        },
    )

    @Test
    fun anUnattachedTeamMentionStillWakesTheDispatcher() = runBlocking {
        val scope = CoroutineScope(Job())
        val host = RelayController(
            platform = InMemoryAppPlatform(),
            store = InMemorySettingsStore(settings()),
            scope = scope,
        )
        val prompts = mutableListOf<String>()
        val keys = mutableListOf<String?>()
        host.onAgentPrompt = { _, _, prompt, _, key ->
            prompts.add(prompt)
            keys.add(key)
        }
        host.messagesCtl.handleOnInbound(
            groupText("env-8b-1", "@e2e-team 干个活"),
        )
        awaitUntil("显式点名派单") { prompts.isNotEmpty() }
        assertTrue(
            prompts.isNotEmpty(),
            "未入驻团队的手打 mention 必须唤醒派单路由（编排层接住后出 blocked 卡）",
        )
        assertEquals("e2e-team", keys.single(), "点名键必须是整队 token（编排层据此判 NotInGroup）")
        assertEquals("干个活", prompts.single(), "交给执行器的仍是剥净 @ 的正文")
    }
}
