// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.EntityTypes
import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.core.protocol.VirtualAgentMember
import com.kapp.tenon.domain.model.PeerUi
import com.kapp.tenon.domain.model.WorkspaceKey
import com.kapp.tenon.domain.model.WorkspaceUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 新手冷启动引导（UX 规范 §2.6）的候选投影。
 *
 * 钉的是三条会**静默给出假承诺**的 fail-closed：
 *
 * 1. 已经有一个目录绑了群 ⇒ 不许再说「第一个项目」；
 * 2. 本机宿主不是「发现局域网可用设备」—— 文案说的是"发现"，自己的机器不是发现来的；
 * 3. 离线设备不许说「该设备已就绪」。
 *
 * 还有两条排序事实：目录按宣告到达时刻倒序挑（用户刚打开的那个最可能是他要的）、
 * 合成 wsId 不是真目录。
 */
class FirstProjectOnboardingTest {

    private val self = "self-node"

    private fun declared(
        peerId: String,
        wsId: String,
        wsTitle: String? = "项目甲",
        at: Long = 100L,
        agentKey: String? = "dsh",
    ) = WorkspaceUi(
        peerId = peerId,
        agentKey = agentKey,
        wsId = wsId,
        wsTitle = wsTitle,
        wsPath = null,
        agentKind = null,
        online = null,
        sessionCount = null,
        at = at,
    )

    private fun slice(workspaces: List<WorkspaceUi>) = AgentSlice(
        agentWorkspaces = workspaces.associate { WorkspaceKey(it.peerId, it.agentKey, it.wsId) to it },
    )

    private fun node(peers: List<PeerUi>) = NodeSlice(peers = peers)

    private fun host(nodeId: String = "host-1", name: String = "Mac Studio", online: Boolean = true) =
        PeerUi(nodeId = nodeId, nick = name, online = online)

    private fun settings(groups: List<GroupMeta> = emptyList()) =
        SettingsSlice(settings = RelaySettings.firstRun().copy(nodeId = self, groups = groups))

    private fun boundGroup(wsId: String): GroupMeta =
        GroupMeta.create("g-1", "项目甲协同群", members = emptyList(), selfId = self, updatedTs = 1L)
            .copy(
                virtualAgents = listOf(
                    VirtualAgentMember(
                        agentId = "ag-1",
                        hostNodeId = "host-1",
                        name = "阿甲",
                        entityType = EntityTypes.SINGLE,
                        defaultWsId = wsId,
                    ),
                ),
            )

    @Test
    fun aRemoteOnlineDeviceWithAnUnboundDirectoryIsTheFirstProject() {
        val first = firstProjectCandidate(
            slice(listOf(declared("host-1", "ws-a", "项目甲"))),
            node(listOf(host())),
            settings(),
        )
        assertEquals("ws-a", first?.workspace?.wsId)
        assertEquals("Mac Studio", first?.deviceName, "设备名走 PeerUi.displayName 那一把尺子")
    }

    @Test
    fun anAlreadyBoundDirectoryEndsTheOnboarding() {
        val first = firstProjectCandidate(
            slice(listOf(declared("host-1", "ws-a"))),
            node(listOf(host())),
            settings(groups = listOf(boundGroup("ws-a"))),
        )
        assertNull(first, "已经有目录绑了群 —— 「第一个项目」这句话此时是假的")
    }

    @Test
    fun theOwnHostIsNotADiscoveredDevice() {
        val first = firstProjectCandidate(
            slice(listOf(declared(self, "ws-self"))),
            node(listOf(host(nodeId = self, name = "我这台"))),
            settings(),
        )
        assertNull(first, "本机目录不是「发现局域网可用设备」，它已经直接列在看板上了")
    }

    @Test
    fun anOfflineDeviceIsNotReadySoNoCard() {
        val first = firstProjectCandidate(
            slice(listOf(declared("host-1", "ws-a"))),
            node(listOf(host(online = false))),
            settings(),
        )
        assertNull(first, "离线设备上「该设备已就绪」是假话 —— 宁可不引导，也不给假承诺")
    }

    @Test
    fun aHostMissingFromTheNodeTableIsNotADevice() {
        val first = firstProjectCandidate(
            slice(listOf(declared("host-unknown", "ws-a"))),
            node(emptyList()),
            settings(),
        )
        assertNull(first, "节点表里没有这台设备，就既不能说它在线、也拿不到它的名字")
    }

    @Test
    fun theMostRecentlyDeclaredDirectoryWins() {
        val first = firstProjectCandidate(
            slice(
                listOf(
                    declared("host-1", "ws-old", "旧目录", at = 100L),
                    declared("host-1", "ws-new", "新目录", at = 900L),
                ),
            ),
            node(listOf(host())),
            settings(),
        )
        assertEquals("ws-new", first?.workspace?.wsId, "用户刚打开的那个项目最可能是他要的")
    }

    @Test
    fun anOlderDirectoryOnAnOnlineDeviceBeatsANewerOneOnAnOfflineDevice() {
        val first = firstProjectCandidate(
            slice(
                listOf(
                    declared("host-online", "ws-online", at = 100L),
                    declared("host-offline", "ws-offline", at = 900L),
                ),
            ),
            node(
                listOf(
                    host(nodeId = "host-online", name = "在线的", online = true),
                    host(nodeId = "host-offline", name = "离线的", online = false),
                ),
            ),
            settings(),
        )
        assertEquals("ws-online", first?.workspace?.wsId, "在线优先于「更新」：离线的设备给不出协同")
        assertEquals("在线的", first?.deviceName)
    }

    @Test
    fun aSyntheticWsIdIsNotAProjectDirectory() {
        val first = firstProjectCandidate(
            slice(listOf(declared("host-1", "ws-probe-7"))),
            node(listOf(host())),
            settings(),
        )
        assertNull(first, "合成 wsId 绑上去只会得到一个假的群项目目录")
    }

    @Test
    fun noDeclaredDirectoryMeansNoCard() {
        val first = firstProjectCandidate(slice(emptyList()), node(listOf(host())), settings())
        assertNull(first, "一个目录都没有 —— 这正是空态，那里没有可绑的东西")
    }

    @Test
    fun theSameInstantFallsBackToWsIdSoThePickIsStable() {
        val first = firstProjectCandidate(
            slice(
                listOf(
                    declared("host-1", "ws-b", at = 500L),
                    declared("host-1", "ws-a", at = 500L),
                ),
            ),
            node(listOf(host())),
            settings(),
        )
        assertEquals("ws-a", first?.workspace?.wsId, "同一时刻要有确定的次序，否则每次重组都可能换一张卡")
        assertTrue(first != null)
    }
}
