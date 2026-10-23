// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * **广播 `group.info` 前补齐本机内容时，不能把已有的抹掉。**
 *
 * ## 真机现场（2026-10-22）
 *
 * 桌面端 `settings.json` 里明明有 `virtualAgents = [dsh-teahouse-mobile]`，
 * 但发出去的 `group.info` 帧（rev=7）**没有这个字段**。手机端收到后：
 * 群里 0 个 agent、`@` 候选空、气泡署名退回 `dsh`。
 *
 * 用户问「是不是我清除数据就正确了」—— **不是**：帧本身就是坏的，
 * 清完重收还是同样缺字段的帧。
 */
class GroupMetaLocalExtrasTest {

    private val self = "self-node-1"
    private val other = "other-node-2"

    private fun declared() = VirtualAgentMember(
        agentId = "DSH",
        hostNodeId = self,
        name = "dsh-teahouse-mobile",
        wsId = "ws-1",
    )

    private fun meta(
        agents: List<VirtualAgentMember>? = null,
        nicks: Map<String, String>? = null,
    ) = GroupMeta(
        groupId = "g-1",
        name = "群",
        members = listOf(self, other),
        rev = 7,
        updatedBy = self,
        updatedTs = 0L,
        virtualAgents = agents,
        memberNicks = nicks,
    )

    /**
     * **核心判据：本机没有 agent（null）时，别人声明进来的必须原样保留。**
     *
     * `null` 与「空列表」是两种事实：前者是「本机没有要声明的」，
     * 后者是「这个群没有 agent」。抹成 null 就是让手机端看到后一种。
     */
    @Test
    fun aLocalWithNoAgentsKeepsTheExistingDeclarations() {
        val original = meta(agents = listOf(declared()))
        val after = original.withLocalExtras(nicks = emptyMap(), localAgents = null)
        assertNotNull(after.virtualAgents, "入驻声明不能被一次广播抹掉")
        assertEquals(1, after.virtualAgents!!.size)
        assertEquals("dsh-teahouse-mobile", after.virtualAgents!!.single().name)
    }

    /** 本机确实有声明时要覆盖（那是新的真事）。 */
    @Test
    fun aLocalDeclarationOverridesTheIncomingOne() {
        val mine = VirtualAgentMember(agentId = "codex", hostNodeId = self, name = "codex-a", wsId = "ws-2")
        val after = meta(agents = listOf(declared())).withLocalExtras(emptyMap(), listOf(mine))
        assertEquals(listOf(mine), after.virtualAgents)
    }

    /** 昵称为空同样不覆盖已有的（拿不到 ≠ 该抹掉）。 */
    @Test
    fun emptyNicksDoNotDropTheExistingOnes() {
        val original = meta(nicks = mapOf(other to "Pixel 6"))
        val after = original.withLocalExtras(nicks = emptyMap(), localAgents = null)
        assertEquals(mapOf(other to "Pixel 6"), after.memberNicks)
    }

    /** 没有可补的东西时原样返回（不产生一次无意义的 copy）。 */
    @Test
    fun nothingToAddReturnsTheSameInstance() {
        val original = meta(agents = listOf(declared()), nicks = mapOf(other to "Pixel 6"))
        assertEquals(original, original.withLocalExtras(emptyMap(), null))
    }

    /** 其它字段一个都不能被顺手改掉。 */
    @Test
    fun theOtherFieldsSurvive() {
        val original = meta(agents = listOf(declared()))
        val after = original.withLocalExtras(mapOf(other to "Pixel 6"), null)
        assertEquals(original.groupId, after.groupId)
        assertEquals(original.rev, after.rev)
        assertEquals(original.members, after.members)
    }
}
