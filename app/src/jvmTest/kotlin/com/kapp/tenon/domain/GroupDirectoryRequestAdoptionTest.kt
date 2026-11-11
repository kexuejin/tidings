// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.core.protocol.VirtualAgentMember
import com.kapp.tenon.platform.InMemoryAppPlatform
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * 宿主兑现「别处（手机）建的群」对它提的目录请求（UX 规范 §2.6 手机侧）。
 *
 * 请求**不是新帧**：手机建绑定群时，`group.info` 的席位声明里 `hostNodeId` 就是本机、
 * `defaultWsId` 就是用户点选的那个目录（`virtualAgentMemberFor` 的既定行为）。宿主此前
 * 收到这份声明什么都不做 ⇒ 群「本项目目录」永远空着 ⇒ 派单 `no git workspace bound to group`。
 *
 * 这组用例钉住五条 fail-closed（指针已设不覆盖 / 本机席位多义不猜 / 只认本机工作区行 /
 * 没路径不动 / 手机无端口不动），以及「幂等：已登记的目录不重复登记」。
 *
 * 反向验证：把 `snap.groupPointers.containsKey` 那一行删掉 ⇒ 覆盖宿主机主选择那条红；
 * 把 `requested.size != 1` 换成 `requested.isEmpty()` ⇒ 多义那条红；
 * 把 `hostNodeId == self` 的过滤去掉 ⇒ 别人的席位那条红。
 */
class GroupDirectoryRequestAdoptionTest {

    private val scope = CoroutineScope(Job())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private val self = "abc12345-0000-4000-8000-000000000000"
    private val peer = "11111111-1111-4111-8111-111111111111"

    /** 目录端口假件：与 DesktopHostDirectoryPort 同语义（解析以指针为前提）。 */
    private class FakeDirectoryPort : HostDirectoryPort {
        var directories: List<HostDirectoryUi> = emptyList()
        val pointers = mutableMapOf<String, String>()
        var resolved: String? = null
        var registrations = 0

        override fun snapshot(): HostDirectorySnapshotUi = HostDirectorySnapshotUi(
            directories = directories,
            groupPointers = pointers.toMap(),
            whitelist = directories.map { WhitelistUi(it.dirId, readOnly = false) },
        )

        override fun register(path: String, displayName: String?): HostDirectoryUi? {
            if (path.isBlank()) return null
            directories.firstOrNull { it.path == path }?.let { return it }
            registrations++
            val dir = HostDirectoryUi(
                dirId = "dir-" + (directories.size + 1),
                path = path,
                displayName = displayName ?: path.substringAfterLast('/'),
                gitRemote = null,
            )
            directories = directories + dir
            return dir
        }

        override fun setGroupPointer(groupId: String, dirId: String?): Boolean {
            if (dirId != null && directories.none { it.dirId == dirId }) return false
            if (dirId == null) pointers.remove(groupId) else pointers[groupId] = dirId
            return true
        }

        override fun setWhitelist(dirId: String, readOnly: Boolean, allowed: Boolean): Boolean = true

        override fun resolveForGroup(groupId: String, agentKey: String): String? =
            if (pointers.containsKey(groupId)) resolved else null
    }

    private class MemorySettingsStore(private val nodeId: String) : SettingsStore {
        private var current: RelaySettings? = RelaySettings(
            nodeId = nodeId,
            nick = "dir-request-test",
            udpPort = 0,
            tcpPort = 0,
        )

        override fun load(): RelaySettings? = current

        override fun save(settings: RelaySettings) {
            current = settings
        }
    }

    private fun controller(): RelayController = RelayController(
        platform = InMemoryAppPlatform(),
        store = MemorySettingsStore(self),
        scope = scope,
        version = "relay-test",
    )

    /** 手机建群那一刻的群元数据：席位挂在**本机**（agent 在这台机器上跑），目录是手机选的。 */
    private fun phoneSideGroup(
        groupId: String,
        wsIds: List<String>,
        hostedBy: String = self,
    ): GroupMeta = GroupMeta(
        groupId = groupId,
        name = "手机建的项目群",
        members = listOf(self, peer),
        rev = 1,
        updatedBy = peer,
        updatedTs = 1_000L,
        virtualAgents = wsIds.mapIndexed { i, wsId ->
            VirtualAgentMember(
                agentId = "dsh",
                hostNodeId = hostedBy,
                name = if (i == 0) "dsh-assistant" else "dsh-assistant-$i",
                defaultWsId = wsId,
            )
        },
    )

    private fun RelayController.arrive(meta: GroupMeta) = groupsCtl.handleApplyGroupMeta(null, meta, peer)

    // ---------------------------------------------------------------- 兑现

    /** 手机建的群到宿主：登记本机那条目录、设指针、席位投影按解析重写。 */
    @Test
    fun theHostHonoursTheDirectoryAPhonePicked() = runBlocking {
        val controller = controller()
        val port = FakeDirectoryPort().also { it.resolved = "ws-dsh" }
        controller.hostDirectoryPort = port
        controller.recordLocalWorkspace(
            wsId = "ws-dsh",
            wsTitle = "teahouse-mobile",
            wsPath = "/srv/teahouse-mobile",
            agentKey = "dsh",
            agentKind = "DSH",
        )

        controller.arrive(phoneSideGroup("g-1", listOf("ws-dsh")))

        val dir = assertNotNull(port.directories.singleOrNull(), "宿主要把那条目录登记进台账")
        assertEquals("/srv/teahouse-mobile", dir.path, "路径从本机工作区行取，不从线上取")
        assertEquals(
            dir.dirId,
            port.pointers["g-1"],
            "指针必须设上 —— 否则派单按 NotConfigured 退单（no git workspace bound to group）",
        )
        val seat = controller.state.value.settings.groups.single { it.groupId == "g-1" }
            .virtualAgents.orEmpty().single { it.hostNodeId == self }
        assertEquals("ws-dsh", seat.defaultWsId, "席位投影按解析结果重写")
        assertEquals(1, port.registrations, "只登记一次")
    }

    /** 宿主机主已经选过目录 ⇒ 自动逻辑一格都不许动（显式选择优先）。 */
    @Test
    fun aPointerTheHostAlreadySetIsNeverOverridden() = runBlocking {
        val controller = controller()
        val port = FakeDirectoryPort().also { it.resolved = "ws-dsh" }
        controller.hostDirectoryPort = port
        val chosen = assertNotNull(port.register("/srv/host-choice", "host-choice"), "前提：机主选过")
        port.pointers["g-1"] = chosen.dirId
        controller.recordLocalWorkspace(
            wsId = "ws-dsh",
            wsTitle = "teahouse-mobile",
            wsPath = "/srv/teahouse-mobile",
            agentKey = "dsh",
            agentKind = "DSH",
        )

        controller.arrive(phoneSideGroup("g-1", listOf("ws-dsh")))

        assertEquals(chosen.dirId, port.pointers["g-1"], "指针仍是机主选的那一个")
        assertEquals(
            listOf("/srv/host-choice"),
            port.directories.map { it.path },
            "不许为自动逻辑另登记一条目录",
        )
    }

    /** 本机两个席位指向不同目录 ⇒ 多义不猜（D14：写入目标只有一个）。 */
    @Test
    fun ambiguousSelfSeatsAreNotAdopted() = runBlocking {
        val controller = controller()
        val port = FakeDirectoryPort().also { it.resolved = "ws-a" }
        controller.hostDirectoryPort = port
        controller.recordLocalWorkspace(
            wsId = "ws-a", wsTitle = "a", wsPath = "/srv/a", agentKey = "dsh", agentKind = "DSH",
        )
        controller.recordLocalWorkspace(
            wsId = "ws-b", wsTitle = "b", wsPath = "/srv/b", agentKey = "dsh-2", agentKind = "DSH",
        )

        controller.arrive(phoneSideGroup("g-1", listOf("ws-a", "ws-b")))

        assertNull(port.pointers["g-1"], "多义不设指针")
        assertTrue(port.directories.isEmpty(), "也不登记任何一条")
    }

    /** 席位是**别人的**（hostNodeId = 对端）：本机没有可兑现的东西。 */
    @Test
    fun aSeatHostedElsewhereIsIgnored() = runBlocking {
        val controller = controller()
        val port = FakeDirectoryPort().also { it.resolved = "ws-dsh" }
        controller.hostDirectoryPort = port
        controller.recordLocalWorkspace(
            wsId = "ws-dsh",
            wsTitle = "teahouse-mobile",
            wsPath = "/srv/teahouse-mobile",
            agentKey = "dsh",
            agentKind = "DSH",
        )

        controller.arrive(phoneSideGroup("g-1", listOf("ws-dsh"), hostedBy = peer))

        assertNull(port.pointers["g-1"], "别人的席位不由本机兑现")
        assertTrue(port.directories.isEmpty())
    }

    /** 工作区没报路径（老 agent / 未暴露目录）⇒ 不登记、不设指针，群照常存在。 */
    @Test
    fun aWorkspaceWithoutAPathIsNotAdopted() = runBlocking {
        val controller = controller()
        val port = FakeDirectoryPort().also { it.resolved = "ws-dsh" }
        controller.hostDirectoryPort = port
        controller.recordLocalWorkspace(
            wsId = "ws-dsh",
            wsTitle = "teahouse-mobile",
            wsPath = null,
            agentKey = "dsh",
            agentKind = "DSH",
        )

        controller.arrive(phoneSideGroup("g-1", listOf("ws-dsh")))

        assertNull(port.pointers["g-1"], "没有路径就没有可登记的目录")
        assertTrue(port.directories.isEmpty())
        assertTrue(
            controller.state.value.settings.groups.any { it.groupId == "g-1" },
            "群本身照常落地（能对话，只是不可执行）",
        )
    }

    /** 手机（没有目录端口）：入站群照样落地，兑现这一步整体不运行。 */
    @Test
    fun aPhoneWithNoDirectoryPortAdoptsNothing() = runBlocking {
        val controller = controller()
        controller.recordLocalWorkspace(
            wsId = "ws-dsh",
            wsTitle = "teahouse-mobile",
            wsPath = "/srv/teahouse-mobile",
            agentKey = "dsh",
            agentKind = "DSH",
        )

        controller.arrive(phoneSideGroup("g-1", listOf("ws-dsh")))

        assertEquals(0, controller.adoptRequestedGroupDirectories(), "手机端恒 0（指针是宿主本机事实）")
        assertTrue(controller.state.value.settings.groups.any { it.groupId == "g-1" })
    }

    /** 目录已经登记过（同一路径）⇒ 复用既有 dirId，不产生第二条账。 */
    @Test
    fun anAlreadyRegisteredPathIsReused() = runBlocking {
        val controller = controller()
        val port = FakeDirectoryPort().also { it.resolved = "ws-dsh" }
        controller.hostDirectoryPort = port
        val existing = assertNotNull(port.register("/srv/teahouse-mobile", "teahouse-mobile"))
        port.registrations = 0
        controller.recordLocalWorkspace(
            wsId = "ws-dsh",
            wsTitle = "teahouse-mobile",
            wsPath = "/srv/teahouse-mobile",
            agentKey = "dsh",
            agentKind = "DSH",
        )

        controller.arrive(phoneSideGroup("g-1", listOf("ws-dsh")))

        assertEquals(existing.dirId, port.pointers["g-1"], "同一路径复用既有条目")
        assertEquals(1, port.directories.size, "台账里只有一条")
        assertEquals(0, port.registrations, "没有重复登记")
    }

    /** 幂等：同一份群再来一遍（对端补发），不产生第二次登记或第二份指针。 */
    @Test
    fun adoptingTwiceIsANoOp() = runBlocking {
        val controller = controller()
        val port = FakeDirectoryPort().also { it.resolved = "ws-dsh" }
        controller.hostDirectoryPort = port
        controller.recordLocalWorkspace(
            wsId = "ws-dsh",
            wsTitle = "teahouse-mobile",
            wsPath = "/srv/teahouse-mobile",
            agentKey = "dsh",
            agentKind = "DSH",
        )
        controller.arrive(phoneSideGroup("g-1", listOf("ws-dsh")))
        val dirId = port.pointers["g-1"]

        controller.arrive(phoneSideGroup("g-1", listOf("ws-dsh")))

        assertEquals(dirId, port.pointers["g-1"])
        assertEquals(1, port.registrations, "重放不重复登记")
    }
}
