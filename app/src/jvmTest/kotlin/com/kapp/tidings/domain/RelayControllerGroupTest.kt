// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.core.protocol.Codec
import com.kapp.tidings.core.protocol.ConvId
import com.kapp.tidings.core.protocol.Envelope
import com.kapp.tidings.core.protocol.GroupMeta
import com.kapp.tidings.core.protocol.MsgKinds
import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.Profile
import com.kapp.tidings.core.protocol.newId
import com.kapp.tidings.core.session.NodeRuntime
import com.kapp.tidings.core.nowMillis
import com.kapp.tidings.core.transport.IncomingFilePlan
import com.kapp.tidings.core.transport.IncomingOffer
import com.kapp.tidings.core.transport.LocalFile
import com.kapp.tidings.core.transport.TransferEvent
import com.kapp.tidings.platform.InMemoryAppPlatform
import com.kapp.tidings.platform.SimpleHttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.kapp.tidings.domain.model.MessageUi
import com.kapp.tidings.domain.model.NoticeKind
import com.kapp.tidings.domain.model.TransferState

/**
 * 群这条路上"消息该落在哪个会话、谁的成员表说了算"的判定。
 *
 * 这些用例存在的理由：群在界面上和私聊长得一样，出错的形态也长得一样 ——
 * 一条群消息落进了某个人的私聊、一份被拒的成员表悄悄生效、被移出群之后还能继续发。
 * 三种都不会崩、不会报错，只会让用户看到错的归属，而截图门禁和真机手测都**看不见**
 * 归属错了（画面里有一条消息，位置也对，只是它不该在那里）。
 *
 * 放在 `jvmTest` 而不是 `commonTest`：要真的跑 `scope.launch`（建群广播、发群消息都在
 * 协程里），`runBlocking` 才等得到。
 *
 * 装置里的控制器**没有起节点**（`runtime == null`）。这是刻意的：起节点要绑真实
 * UDP/TCP 端口，而这里要验的是路由与合并，不是网络。没有节点反而把每条"需要节点"
 * 的分支变成了一条可断言的中文横幅（[BannerMessage.NodeNotStarted]），
 * 比一个内部标志位更难作假。
 */
class RelayControllerGroupTest {

    private val self = "abc12345-0000-4000-8000-000000000000"
    private val lin = "11111111-1111-4111-8111-111111111111"
    private val zhou = "22222222-2222-4222-8222-222222222222"

    // ---- 建群 ----

    @Test
    fun creatingAGroupPutsItInTheConversationList() = runBlocking {
        val controller = controller()

        val groupId = controller.createGroup("上线小组", listOf(lin, zhou))

        assertNotNull(groupId, "建群成功必须回一个 groupId，界面靠它跳进新会话")
        val row = controller.state.value.conversations.single { it.isGroup }
        assertEquals("上线小组", row.title)
        assertEquals(ConvId.group(groupId), row.convId)
        // 分母含自己（3），分子不含（此刻谁都不在线）。
        assertEquals(3, row.memberCount)
        assertEquals(0, row.memberOnlineCount)
        // 群行不能借 `online` 那个字段说话：它对群恒 false，画成绿点会显示"离线"。
        assertFalse(row.online)
    }

    @Test
    fun theCreatorIsAlwaysInTheMemberList() = runBlocking {
        // 这条不是格式讲究：`GroupMeta.create` 把 ownerId 设成自己，而协议要求
        // ownerId 必须是 members 之一 —— 少了自己，对方**整帧静默拒收**，
        // 症状是"建了群，成员那边什么都没发生"，没有任何错误提示。
        val controller = controller()

        val groupId = controller.createGroup("上线小组", listOf(lin))
        val meta = controller.state.value.settings.groups.single { it.groupId == groupId }

        assertEquals(self, meta.members.first(), "自己必须在成员表里，且是第一位")
        assertEquals(self, meta.ownerId)
        assertTrue(meta.hasMember(lin))
    }

    @Test
    fun aGroupWithoutANameIsRefused() = runBlocking {
        val controller = controller()

        assertNull(controller.createGroup("   ", listOf(lin)))
        assertEquals(BannerMessage.GroupNameRequired, controller.state.value.banner?.message)
        assertTrue(controller.state.value.settings.groups.isEmpty())
    }

    @Test
    fun anOverlongGroupNameIsRefusedHereNotSilentlyThere() = runBlocking {
        // 超长名字在对方那边会让整帧 `group.info` 被拒收，而且拒收是静默的。
        // 在这里拦下来，用户至少知道该改什么。
        val controller = controller()

        assertNull(controller.createGroup("群".repeat(33), listOf(lin)))
        assertEquals(BannerMessage.GroupNameTooLong(32), controller.state.value.banner?.message)
        assertTrue(controller.state.value.settings.groups.isEmpty())
    }

    @Test
    fun aGroupOfOneIsRefused() = runBlocking {
        // 只有自己的群在协议上完全合法，但发出去没有任何人会收到 —— 那不是群聊。
        val controller = controller()

        assertNull(controller.createGroup("自言自语", listOf(self, "  ")))
        assertEquals(BannerMessage.GroupNeedsMember, controller.state.value.banner?.message)
        assertTrue(controller.state.value.settings.groups.isEmpty())
    }

    // ---- 工作区建群（真机反馈：「无法快速建群，一直提示要至少一个人」）----

    /**
     * 只在自己这一台设备上（或者对端全离线）也必须能建出工作区群。
     *
     * 旧行为：`handleCreateGroup` 要求「除自己外至少一个成员」，而工作区建群对话框的成员
     * 来源只有**当前在线设备**、它自己又不是成员选择器 —— 于是单机用户永远建不出来，
     * 只得到一句「至少再选一个人」，对话框却已经关掉、里面根本没有可勾的人。
     *
     * 反向验证：把 `handleCreateWorkspaceGroup` 的 `allowEmptyMembers` 改回 false，
     * 这条用例在 `assertNotNull(groupId)` 处变红。
     */
    @Test
    fun aWorkspaceGroupIsCreatedEvenWithNoOtherDeviceOnline() = runBlocking {
        val controller = controller()
        controller.recordLocalWorkspace(
            wsId = "ws-alpha",
            wsTitle = "teahouse-mobile",
            wsPath = "/srv/teahouse-mobile",
            agentKey = "dsh",
            agentKind = "DSH",
        )

        val groupId = controller.createWorkspaceGroup(
            name = "teahouse-mobile 协同群",
            wsId = "ws-alpha",
            memberIds = emptyList(),
            description = "/srv/teahouse-mobile",
        )

        assertNotNull(groupId, "单机（没有任何在线设备）也要能把工作区群建出来")
        val meta = controller.state.value.settings.groups.single { it.groupId == groupId }
        assertEquals(listOf(self), meta.members, "人类成员只有自己 —— 但群里还有那个 agent")
        val seat = meta.virtualAgents?.single()
        assertNotNull(seat, "工作区群必须把工作区上的 agent 声明进群（否则两个人都没有，@ 也没人可叫）")
        // 工作区没上报 agentId（真机主形态）时退到 agentKind —— 与「邀请新智能体入驻」同一条兜底。
        assertEquals("DSH", seat.agentId)
        assertEquals(self, seat.hostNodeId)
        assertEquals("ws-alpha", seat.wsId)
        assertEquals(
            BannerMessage.GroupCreatedWithoutMembers("teahouse-mobile 协同群"),
            controller.state.value.banner?.message,
            "0 人时要给「先建群、再加设备」的文案，而不是「正在通知 0 位成员」",
        )
    }

    @Test
    fun aWorkspaceGroupStillTakesTheOtherDevicesItHas() = runBlocking {
        // 有人在线时行为不变：人类成员照旧，agent 声明与它们并存（不是二选一）。
        val controller = controller()
        controller.recordLocalWorkspace(
            wsId = "ws-alpha",
            wsTitle = "teahouse-mobile",
            wsPath = "/srv/teahouse-mobile",
            agentKey = "dsh",
            agentKind = "DSH",
        )

        val groupId = controller.createWorkspaceGroup(
            name = "teahouse-mobile 协同群",
            wsId = "ws-alpha",
            memberIds = listOf(lin),
            description = "/srv/teahouse-mobile",
        )

        assertNotNull(groupId)
        val meta = controller.state.value.settings.groups.single()
        assertEquals(listOf(self, lin), meta.members)
        assertEquals(1, meta.virtualAgents?.size)
        assertEquals(BannerMessage.GroupCreated("teahouse-mobile 协同群", 1), controller.state.value.banner?.message)
    }

    @Test
    fun aWorkspaceGroupForANewDirectoryIsStillCreated() = runBlocking {
        // 「关联本地全新目录」分支：目录还没被任何 agent 宣告成工作区（wsId = null），
        // 因此没有可入驻的 agent。群照样要建出来 —— 它是这个目录的协同通道，
        // 设备与工作区随后再接上；这里钉住的是「没有工作区行时不会退化成旧判据」。
        val controller = controller()

        val groupId = controller.createWorkspaceGroup(
            name = "new-feature 协同群",
            wsId = null,
            memberIds = emptyList(),
            description = "/srv/new-feature",
        )

        assertNotNull(groupId, "新目录分支同样不该被「至少再选一个人」挡住")
        val meta = controller.state.value.settings.groups.single { it.groupId == groupId }
        assertEquals(listOf(self), meta.members)
        assertNull(meta.virtualAgents, "还没有工作区行 ⇒ 没有可声明的 agent（不许替他编一个）")
    }

    // ---- 解散群 ----

    /**
     * 群主解散 = 一次性的全员移出：成员表裁到只剩自己、rev+1、本机删掉群。
     *
     * 为什么值得钉住：这条路径**不新增任何帧**，靠的是对端 GroupMerge 判 Removed。
     * 一旦成员表裁错（比如还留着别人），对端收到后会当成一次普通的「有人退群」，
     * 群在别人那里继续存在 —— 而用户以为已经解散了。
     */
    @Test
    fun theOwnerCanDisbandAGroup() = runBlocking {
        val controller = controller()
        val groupId = controller.createGroup("要解散的群", listOf(lin, zhou))!!

        controller.dissolveGroup(groupId)

        assertNull(
            controller.state.value.settings.groups.firstOrNull { it.groupId == groupId },
            "解散后本机不再持有这个群",
        )
        assertEquals(
            BannerMessage.GroupDisbanded("要解散的群"),
            controller.state.value.banner?.message,
            "回执要说清是「解散」，不是「我被移出」",
        )
    }

    @Test
    fun aNonOwnerCannotDisband() = runBlocking {
        // 非群主解散必须被拒：它会让别人的群凭空消失。这里用「群里只有自己」的一档 ——
        // 自己建群必然是群主，所以改成先由 lin 建群不现实（协议上没有代建），
        // 于是直接构造一个群主是别人的元数据。
        val controller = controller()
        val groupId = "group-owned-by-other"
        val meta = GroupMeta(
            groupId = groupId,
            name = "别人的群",
            members = listOf(self, lin),
            rev = 1,
            updatedBy = lin,
            updatedTs = 1L,
            creatorId = lin,
            ownerId = lin,
            adminIds = emptyList(),
        )
        controller.onInbound(groupInfo(meta))

        controller.dissolveGroup(groupId)

        assertNotNull(
            controller.state.value.settings.groups.firstOrNull { it.groupId == groupId },
            "非群主解散必须被拒，群还在",
        )
        assertEquals(BannerMessage.GroupNoPermission, controller.state.value.banner?.message)
    }
    @Test
    fun aPlainGroupDoesNotSeatAnyAgent() = runBlocking {
        // 决策边界：只有**工作区群**才有「agent 是第二个参与者」这个前提。
        // 普通建群（会话列表那颗按钮）就算简介写成了某个工作区的路径，也不替用户入驻任何
        // agent —— 群里的 agent 只有「显式入驻」一个来源。
        val controller = controller()
        controller.recordLocalWorkspace(
            wsId = "ws-alpha",
            wsTitle = "teahouse-mobile",
            wsPath = "/srv/teahouse-mobile",
            agentKey = "dsh",
            agentKind = "DSH",
        )

        val groupId = controller.createGroup("随手拉的群", listOf(lin), "/srv/teahouse-mobile")

        assertNotNull(groupId)
        assertNull(controller.state.value.settings.groups.single().virtualAgents)
    }

    @Test
    fun aGroupIsSavedEvenWhenTheNodeIsNotRunning() = runBlocking {
        // 落盘必须发生在广播**之前**：反过来的话，广播途中进程被杀，
        // 成员那边有群、我们这边没有，之后他们发的每条群消息都触发一次 op:need 而我们回不了。
        val controller = controller()

        val groupId = controller.createGroup("上线小组", listOf(lin, zhou))

        val meta = controller.state.value.settings.groups.single()
        assertEquals(groupId, meta.groupId)
        assertEquals(1, meta.rev)
        assertEquals(
            BannerMessage.GroupCreated("上线小组", 2),
            controller.state.value.banner?.message,
        )
    }

    @Test
    fun creatingAGroupPrimesTheNeedTracker() = runBlocking {
        // 建群之后我们**手上就有**这个群的元数据了，所以同 rev 的群消息不该再让我们回头
        // 去要一份（`GroupNeedTracker` 的存在理由就是别白跑这一趟）。
        // 加人/移人/收到 info 三条写元数据的路径都登记了游标，建群这条**原来漏了**：
        // 症状是建完群收到的第一条同 rev 群消息会回一发 `op:need`，对端若还不回 info，
        // 之后每一条群消息都再问一次。
        val controller = controller()

        val groupId = controller.createGroup("上线小组", listOf(lin, zhou))!!

        val meta = controller.state.value.settings.groups.single { it.groupId == groupId }
        assertFalse(
            controller.core.groupNeeds.shouldRequest(groupId, meta.rev),
            "刚建完的群（rev=${meta.rev}）不该为同一个 rev 再发 need",
        )
        assertTrue(
            controller.core.groupNeeds.shouldRequest(groupId, meta.rev + 1),
            "真的有更新的 rev，还是要问",
        )
    }

    @Test
    fun incomingGroupTextWithANewerNumericRevAsksForMeta() = runBlocking {
        // `groupRev` 在协议里是**数字**：`Codec.intOf` 对字符串一律返回 null（见 `CodecWire.intOf`），
        // 群文本消息的载荷校验（`CodecMsg.validateMsg`）就是用它判合法性的，发送侧也写数字。
        // `syncGroupMetaIfNeeded` 原来把 rev 读成 `takeIf { it.isString }` ⇒ 真机上的数字 rev
        // 一律变成 0 ⇒ `beginRequest(groupId, 0)` 在游标已经登记过本地 rev 之后恒为假 ——
        // **need 索取这条路径在实机上从来没跑过**。2026-10-10 的 group 夹具
        // `group-need-on-newer-rev` 逮到它：手机 ACK 了 rev=2 的群消息（本地 rev=1）却不回 need。
        val controller = controller()
        val rt = NodeRuntime(
            profile = Profile.agent(
                nodeId = self, nick = "我的手机", host = "127.0.0.1",
                ver = "relay-test", platform = "linux",
            ),
            udpPort = 0,
            bindHost = "127.0.0.1",
        )
        val groupId = "500ecc5d-5882-413b-8693-80e044efb8f1"
        controller.core.groupNeeds.noteKnown(groupId, 1)  // 本地 rev=1（建群 / 收到 info 之后就是它）

        controller.groupsCtl.handleSyncGroupMetaIfNeeded(
            rt,
            envelope(
                type = MsgTypes.MSG,
                from = lin,
                payload = buildJsonObject {
                    put("kind", MsgKinds.GROUP_TEXT)
                    put("text", "hi")
                    put("groupId", groupId)
                    put("groupRev", 2)
                },
            ),
        )

        assertFalse(
            controller.core.groupNeeds.shouldRequest(groupId, 2),
            "收到 groupRev=2（本地 1）应当已经问过 ⇒ 游标推到 2",
        )
        assertTrue(
            controller.core.groupNeeds.shouldRequest(groupId, 3),
            "更高的 rev 仍然要问",
        )
    }

    // ---- 收到 group{op:"info"} ----

    @Test
    fun anIncomingGroupWithMeInItIsAccepted() = runBlocking {
        val controller = controller()
        val meta = remoteGroup("g-1", "上线小组", listOf(self, lin), rev = 1, updatedBy = lin)

        controller.onInbound(groupInfo(meta))

        assertEquals(meta.groupId, controller.state.value.settings.groups.single().groupId)
        assertTrue(controller.state.value.conversations.any { it.isGroup && it.title == "上线小组" })
    }

    @Test
    fun anIncomingGroupWithoutMeIsNotSaved() = runBlocking {
        // 只保存自己所在的群。存下一份"我不在里面"的成员表，会让一个我从没参与过的群
        // 出现在会话列表里，而且点进去发不出任何东西。
        val controller = controller()
        val meta = remoteGroup("g-1", "别人的群", listOf(lin, zhou), rev = 1, updatedBy = lin)

        controller.onInbound(groupInfo(meta))

        assertTrue(controller.state.value.settings.groups.isEmpty())
    }

    @Test
    fun aStaleGroupInfoDoesNotRollBackTheLocalCopy() = runBlocking {
        val controller = controller()
        val current = remoteGroup("g-1", "上线小组", listOf(self, lin), rev = 5, updatedBy = lin)
        controller.onInbound(groupInfo(current))

        val stale = remoteGroup("g-1", "旧名字", listOf(self, lin), rev = 4, updatedBy = lin)
        controller.onInbound(groupInfo(stale))

        assertEquals("上线小组", controller.state.value.settings.groups.single().name)
    }

    @Test
    fun renamingAGroupLeavesANoticeInTheTimeline() = runBlocking {
        val controller = controller()
        controller.onInbound(
            groupInfo(remoteGroup("g-1", "上线小组", listOf(self, lin), rev = 1, updatedBy = lin)),
        )

        controller.onInbound(
            groupInfo(remoteGroup("g-1", "上线小组（临时）", listOf(self, lin), rev = 2, updatedBy = lin)),
        )

        val notices = controller.state.value.messagesWith(ConvId.group("g-1"))
            .mapNotNull { it.notice }
        assertTrue(
            notices.contains(NoticeKind.GroupRenamed),
            "群名在用户眼前变了，时间线里必须有一行解释，否则像是界面自己在改字",
        )
        assertEquals("上线小组（临时）", controller.state.value.settings.groups.single().name)
    }

    @Test
    fun theFirstTimeAGroupIsLearnedThereIsNoNotice() = runBlocking {
        // 第一次认识它时不插系统提示：群里会立刻出现对方发来的那条群消息，
        // 再插一行"群成员有变化"是纯噪音。
        val controller = controller()

        controller.onInbound(
            groupInfo(remoteGroup("g-1", "上线小组", listOf(self, lin), rev = 1, updatedBy = lin)),
        )

        assertTrue(
            controller.state.value.messagesWith(ConvId.group("g-1")).isEmpty(),
            "刚认识的群不该凭空多出一行系统提示",
        )
    }

    @Test
    fun beingRemovedIsItsOwnNoticeAndKeepsTheHistory() = runBlocking {
        // 「群里的人变了」和「我不在里面了」是两件事：用户要做的事完全不同。
        // 而且成员表必须**存下来**：不存的话会话键会退化成标题里的 `group:g-1`，
        // 历史还在但群名没了。
        val controller = controller()
        val owner = remoteGroup("g-1", "上线小组", listOf(self, lin), rev = 1, updatedBy = lin)
            .copy(ownerId = lin, adminIds = emptyList())
        controller.onInbound(groupInfo(owner))

        controller.onInbound(
            groupInfo(owner.copy(rev = 2, members = listOf(lin), updatedBy = lin)),
        )

        val meta = controller.state.value.settings.groups.single()
        assertFalse(meta.hasMember(self))
        assertTrue(
            controller.state.value.messagesWith(ConvId.group("g-1"))
                .any { it.notice == NoticeKind.RemovedFromGroup },
        )
    }

    @Test
    fun aRemovedMemberCanStillDeleteTheLocalGroup() = runBlocking {
        // 被移出后 `GroupMerge.Removed` 会**保留**元数据（成员表里没有自己 = 它回答
        // 「我为什么发不出话」的唯一依据）。但保留不等于删不掉：那条群里的
        // 「退出群聊」此前在 `!hasMember(self)` 处直接 return —— 按下去什么都不发生、
        // 连提示都没有，用户被卡在一个既删不掉又发不出话的群上。
        val controller = controller()
        val owner = remoteGroup("g-1", "上线小组", listOf(self, lin), rev = 1, updatedBy = lin)
            .copy(ownerId = lin, adminIds = emptyList())
        controller.onInbound(groupInfo(owner))
        controller.onInbound(groupInfo(owner.copy(rev = 2, members = listOf(lin), updatedBy = lin)))
        assertFalse(controller.state.value.settings.groups.single().hasMember(self))

        controller.leaveGroup("g-1")

        assertTrue(
            controller.state.value.settings.groups.none { it.groupId == "g-1" },
            "已经被移出的人也必须能把本地这条群删掉，否则它永远留在会话列表里",
        )
        assertEquals(
            BannerMessage.GroupLeft,
            controller.state.value.banner?.message,
            "回执必须如实说「你已被移出」，不能说成「已退出」",
        )
    }

    @Test
    fun theRealWorldRemovalFrameIsApplied() = runBlocking {
        // **真机帧原文**（2026-10-26 15:54:08，手机端把本机移出「协同群」）：
        // 桌面收到了它（messages.log 里能从解码出来），却没有把成员表更新过来。
        // 这条用例把这帧逐字节喂进同一条入站路径，让接收侧自己给出结论。
        val controller = controller()
        val groupId = "81b6078b-3084-4fff-9de1-1966783bb6b6"
        val phone = "00000000-0000-4000-8000-0000000000a1"
        // 先让它成为成员（rev 1），
        controller.onInbound(
            groupInfo(
                GroupMeta(
                    groupId = groupId, name = "协同群", members = listOf(phone, self),
                    rev = 1, updatedBy = phone, updatedTs = 1792992719409L,
                    creatorIp = "", creatorId = phone, ownerId = phone, adminIds = emptyList(),
                ),
            ),
        )
        assertTrue(controller.state.value.settings.groups.single().hasMember(self))

        // 再喂那一帧原文（走**真实解码**，不是构造 GroupMeta）。
        val payload = Json.parseToJsonElement(
            """
            {
        "op": "info",
        "group": {
                "groupId": "81b6078b-3084-4fff-9de1-1966783bb6b6",
                "name": "协同群",
                "members": [
                        "00000000-0000-4000-8000-0000000000a1"
                ],
                "rev": 2,
                "updatedBy": "00000000-0000-4000-8000-0000000000a1",
                "updatedTs": 1793001249934,
                "creatorIp": "",
                "creatorId": "00000000-0000-4000-8000-0000000000a1",
                "ownerId": "00000000-0000-4000-8000-0000000000a1",
                "adminIds": [],
                "description": "/sdcard/verify-wsu",
                "memberNicks": {
                        "00000000-0000-4000-8000-0000000000a1": "Pixel 6"
                }
        }
}
            """.trimIndent(),
        ) as JsonObject
        controller.onInbound(
            Envelope(
                v = 1, type = MsgTypes.GROUP,
                id = "8fe96c00-f3ba-4f59-83bf-e3115e4ef9af",
                from = phone, ts = 1793001249938L, payload = payload,
            ),
        )

        val meta = controller.state.value.settings.groups.single()
        assertEquals(2, meta.rev, "真机那一帧是 rev 2，本机必须跟上")
        assertFalse(meta.hasMember(self), "群主把本机移出后，成员表里不该再有自己")
    }

    @Test
    fun aMemberCannotChangeRoles() = runBlocking {
        // 普通成员唯一能做的是"只增不减地邀请新人"。角色矩阵不能自授。
        val controller = controller()
        val owner = remoteGroup("g-1", "上线小组", listOf(self, lin, zhou), rev = 1, updatedBy = lin)
            .copy(ownerId = lin, adminIds = emptyList())
        controller.onInbound(groupInfo(owner))

        // 周（普通成员）把自己提成管理员。
        controller.onInbound(
            groupInfo(
                owner.copy(rev = 2, updatedBy = zhou, adminIds = listOf(zhou)),
            ),
        )

        val meta = controller.state.value.settings.groups.single()
        assertEquals(1, meta.rev, "被拒的帧不能推进本地 rev")
        assertNull(meta.adminIds?.firstOrNull())
    }

    // ---- 收到群文本 ----

    @Test
    fun aGroupTextLandsInTheGroupConversationNotInTheSenders() = runBlocking {
        // 这是 ⑤ 的核心缺陷：以前群文本归到 `envelope.from` 的 1:1 会话，
        // 于是"群里说的话"在界面上变成了那个人私下跟我说的话。
        val controller = controller()
        controller.onInbound(
            groupInfo(remoteGroup("g-1", "上线小组", listOf(self, lin), rev = 1, updatedBy = lin)),
        )

        controller.onInbound(groupText("g-1", rev = 1, text = "今晚八点上线"))

        val inGroup = controller.state.value.messagesWith(ConvId.group("g-1"))
        assertEquals(1, inGroup.size)
        assertEquals("今晚八点上线", inGroup.single().text)
        // 会话键是群，所以必须另存发送者 —— 否则群里分不出是谁说的。
        assertEquals(lin, inGroup.single().senderId)
        assertTrue(
            controller.state.value.messagesWith(lin).isEmpty(),
            "群消息不该同时出现在发送者的私聊里",
        )
    }

    @Test
    fun aGroupTextFromAnUnknownGroupStillShowsUp() = runBlocking {
        // 成员表还在路上（对方刚建群就发言）时不能把消息丢掉：先让它出现在
        // `group:<id>` 会话里，标题退回 groupId 前缀，等元数据到了再换成群名。
        val controller = controller()

        controller.onInbound(groupText("g-1", rev = 1, text = "刚建的群"))

        assertEquals(1, controller.state.value.messagesWith(ConvId.group("g-1")).size)
        assertTrue(controller.state.value.settings.groups.isEmpty(), "没有成员表，不能凭空造一个群")
    }

    @Test
    fun aGroupMessageCanBeRecalledByItsSender() = runBlocking {
        // 群消息落地后，"会话匹配"不再等于"在 `from` 那个线程里找"：群文本按 groupId
        // 归到 `group:<id>`，发送者存在 `senderId` 里。撤回如果还只查 1:1 线程，
        // 就会**静默失效** —— ACK 早回过了，对端以为撤回成功，而这边那条消息还在。
        val controller = controller()
        controller.onInbound(
            groupInfo(remoteGroup("g-1", "上线小组", listOf(self, lin), rev = 1, updatedBy = lin)),
        )
        controller.onInbound(groupText("g-1", rev = 1, text = "我带水"))

        val convId = ConvId.group("g-1")
        val sent = controller.state.value.messagesWith(convId).single()
        assertFalse(sent.mine)

        controller.onInbound(recall(from = lin, targetId = sent.id, groupId = "g-1", groupRev = 1))

        val after = controller.state.value.messagesWith(convId).single()
        assertEquals("", after.text, "撤回要连正文一起隐藏")
        assertEquals(NoticeKind.RecalledByPeer, after.notice)
    }

    @Test
    fun aRecallFromSomeoneElseDoesNotTouchAGroupMessage() = runBlocking {
        // 撤回者必须是原消息发送者。周没说过那句话，它发的撤回不能把林的话删掉。
        val controller = controller()
        controller.onInbound(
            groupInfo(
                remoteGroup("g-1", "上线小组", listOf(self, lin, zhou), rev = 1, updatedBy = lin),
            ),
        )
        controller.onInbound(groupText("g-1", rev = 1, text = "我带水"))
        val sent = controller.state.value.messagesWith(ConvId.group("g-1")).single()

        controller.onInbound(recall(from = zhou, targetId = sent.id, groupId = "g-1", groupRev = 1))

        val after = controller.state.value.messagesWith(ConvId.group("g-1")).single()
        assertEquals("我带水", after.text)
        assertNull(after.notice)
    }

    @Test
    fun anIncomingGroupTextIsUnreadForTheGroupConversation() = runBlocking {
        val controller = controller()

        controller.onInbound(groupText("g-1", rev = 1, text = "在吗"))

        assertEquals(1, controller.state.value.unreadByPeer[ConvId.group("g-1")])
    }

    @Test
    fun selectingAGroupConversationClearsItsUnread() = runBlocking {
        // `selectPeer` 清的是节点 id；群会话的键是 `group:<id>`，用错那个的结果是
        // "看着消息却还挂着红点"。
        val controller = controller()
        controller.onInbound(groupText("g-1", rev = 1, text = "在吗"))

        controller.selectConversation(ConvId.group("g-1"))

        assertEquals(0, controller.state.value.unreadByPeer[ConvId.group("g-1")])
    }

    @Test
    fun aMessageArrivingWhileYouAreLookingAtTheConversationRaisesNoUnread() = runBlocking {
        // 与上一条同一个毛病，只是发生在**不切换**的时候：停在会话里收消息，
        // 以前照样 +1，于是标签条上挂着一个永远不消的红点（消息就在眼前）。
        // 真机验收群聊时在 Pixel 6 上拍到的就是这个 1。
        val controller = controller()
        val convId = ConvId.group("g-1")
        controller.setVisibleConversation(convId)

        controller.onInbound(groupText("g-1", rev = 1, text = "在吗"))

        assertEquals(1, controller.state.value.messagesWith(convId).size, "消息本身要落进会话")
        assertEquals(null, controller.state.value.unreadByPeer[convId], "看着的消息不算未读")
    }

    @Test
    fun leavingTheConversationPutsUnreadBackToCounting() = runBlocking {
        // 反向的那半：离开之后同一条消息必须重新算未读，否则这个字段就成了
        // "一旦看过就永远不提醒"的开关。`setVisibleConversation(null)` 就是
        // 壳在返回列表 / 切走页签时推过来的那一帧。
        val controller = controller()
        val convId = ConvId.group("g-1")
        controller.setVisibleConversation(convId)
        controller.onInbound(groupText("g-1", rev = 1, text = "第一条"))
        controller.setVisibleConversation(null)

        controller.onInbound(groupText("g-1", rev = 1, text = "第二条"))

        assertEquals(1, controller.state.value.unreadByPeer[convId])
    }

    @Test
    fun enteringConversationClearsExistingUnreadCount() = runBlocking {
        val controller = controller()
        val convId = ConvId.group("g-1")
        controller.onInbound(groupText("g-1", rev = 1, text = "未读消息"))
        assertEquals(1, controller.state.value.unreadByPeer[convId], "未查看时应有1条未读")

        controller.setVisibleConversation(convId)
        assertEquals(0, controller.state.value.unreadByPeer[convId], "进入会话后未读应立刻被清零")
    }

    // ---- 发送 ----

    @Test
    fun sendingToAGroupWithoutANodeSaysTheNodeIsNotStarted() = runBlocking {
        val controller = controller()
        val groupId = controller.createGroup("上线小组", listOf(lin))!!
        controller.dismissBanner()

        controller.sendToGroup(groupId, "在吗")

        assertEquals(BannerMessage.NodeNotStarted, controller.state.value.banner?.message)
    }

    @Test
    fun sendingToAGroupWeHaveNeverHeardOfSaysSoInsteadOfDroppingIt() = runBlocking {
        // 会话列表里可能先有了这个群（对方发过消息），而成员表还没同步到。
        // 这时候既不能猜成员、也不能把话丢掉不说。
        val controller = controller()

        controller.sendToGroup("g-unknown", "在吗")

        assertEquals(BannerMessage.GroupUnknown, controller.state.value.banner?.message)
    }

    @Test
    fun sendingToAGroupIWasRemovedFromIsRefused() = runBlocking {
        val controller = controller()
        val owner = remoteGroup("g-1", "上线小组", listOf(self, lin), rev = 1, updatedBy = lin)
            .copy(ownerId = lin, adminIds = emptyList())
        controller.onInbound(groupInfo(owner))
        // 群主把「我」移出去：成员表存下来（历史还在），但发不出话了。
        controller.onInbound(
            groupInfo(owner.copy(rev = 2, members = listOf(lin), updatedBy = lin)),
        )
        controller.dismissBanner()

        controller.sendToGroup("g-1", "我还在吗")

        assertEquals(BannerMessage.GroupLeft, controller.state.value.banner?.message)
    }

    @Test
    fun sendingToAnEmptyGroupTextDoesNothing() = runBlocking {
        val controller = controller()
        val groupId = controller.createGroup("上线小组", listOf(lin))!!
        controller.dismissBanner()

        controller.sendToGroup(groupId, "   ")

        assertNull(controller.state.value.banner, "空消息不是错误，不该弹横幅")
        assertTrue(controller.state.value.messagesWith(ConvId.group(groupId)).isEmpty())
    }

    @Test
    fun sendToConversationRoutesGroupsAndSingleChatsToDifferentPlaces() = runBlocking {
        // 聊天页只有一个输入框，它手里是**会话键**。让界面自己判断"这是群还是私聊"，
        // 就是把同一套 `ConvId.isGroup` 判断抄到每个调用点 —— 漏一处就是
        // "在群里说话却发给了某个人"。
        //
        // 没有节点时两条路的横幅**不同**，正好证明它真的分派了：
        // 群那条是"成员表还没有"，私聊那条是"节点还没起来"。
        val unknownGroup = controller()
        unknownGroup.sendToConversation(ConvId.group("g-1"), "在吗")
        assertEquals(BannerMessage.GroupUnknown, unknownGroup.state.value.banner?.message)

        val single = controller()
        single.sendToConversation(lin, "在吗")
        assertEquals(BannerMessage.NodeNotStarted, single.state.value.banner?.message)
    }

    @Test
    fun aSingleChatKeyIsNeverMistakenForAGroup() {
        // `ConvId` 的私聊键**不加前缀**（加了就要迁移已落盘的 messages.json 与全部调用点）。
        // 代价是"节点 id 长什么样"这件事必须一直成立，所以钉一条。
        assertNull(ConvId.groupIdOf(lin))
        assertEquals(lin, ConvId.single(lin))
        assertTrue(ConvId.isGroup(ConvId.group("g-1")))
        // 群 id 里可以有冒号，所以只能 removePrefix，不能 split(':')。
        assertEquals("a:b", ConvId.groupIdOf(ConvId.group("a:b")))
    }

    // ---- 群文件传输 ----

    @Test
    fun incomingGroupOfferRoutesToGroupConversation() = runBlocking {
        val controller = controller()
        val gMeta = remoteGroup(
            groupId = "g-file-1",
            name = "资料组",
            members = listOf(lin, self),
            rev = 1,
            updatedBy = lin,
        )
        controller.onInbound(groupInfo(gMeta))

        val offer = IncomingOffer(
            transferId = "t-100",
            peerId = lin,
            rootName = "架构图.pdf",
            files = listOf(IncomingFilePlan("f-1", "架构图.pdf", 2048L)),
            totalSize = 2048L,
            purpose = null,
            msgId = "m-file-1",
            expiresAt = null,
            groupId = "g-file-1",
            groupRev = 1,
        )

        // 收到群文件 Offer 事件
        controller.onTransfer(TransferEvent.Offered(offer))

        val groupConvKey = ConvId.group("g-file-1")
        val thread = controller.state.value.messagesWith(groupConvKey)
        assertEquals(1, thread.size, "群文件必须落在群会话群聊线程里")
        val msg = thread.first()
        val card = msg.file
        assertNotNull(card, "消息必须包含文件卡片")
        assertEquals("架构图.pdf", card.name)
        assertEquals(TransferState.Offered, card.state)
        assertEquals(lin, msg.senderId, "群文件必须署名为发送者的节点 ID")
        assertFalse(msg.mine)

        // 私聊线程不应有此文件
        val singleThread = controller.state.value.messagesWith(lin)
        assertTrue(singleThread.isEmpty(), "私聊线程绝不能混入群文件")
    }

    @Test
    fun groupFileProgressAndCompletionUpdatesCardInGroupConversation() = runBlocking {
        val controller = controller()
        val gMeta = remoteGroup(
            groupId = "g-file-2",
            name = "研发组",
            members = listOf(lin, self),
            rev = 1,
            updatedBy = lin,
        )
        controller.onInbound(groupInfo(gMeta))

        val offer = IncomingOffer(
            transferId = "t-200",
            peerId = lin,
            rootName = "原型设计.sketch",
            files = listOf(IncomingFilePlan("f-2", "原型设计.sketch", 10000L)),
            totalSize = 10000L,
            purpose = null,
            msgId = "m-file-2",
            expiresAt = null,
            groupId = "g-file-2",
            groupRev = 1,
        )
        controller.onTransfer(TransferEvent.Offered(offer))

        // 收到传输进度
        controller.onTransfer(
            TransferEvent.Progress(
                transferId = "t-200",
                peerId = lin,
                fileId = "原型设计.sketch",
                done = 5000L,
                total = 10000L,
                incoming = true,
            ),
        )

        val groupConvKey = ConvId.group("g-file-2")
        var card = controller.state.value.messagesWith(groupConvKey).first().file
        assertNotNull(card)
        assertEquals(TransferState.Active, card.state)
        assertEquals(5000L, card.done)

        // 收到传输完成
        controller.onTransfer(
            TransferEvent.Completed(
                transferId = "t-200",
                peerId = lin,
                incoming = true,
                savedPaths = listOf("/tmp/原型设计.sketch"),
            ),
        )

        card = controller.state.value.messagesWith(groupConvKey).first().file
        assertNotNull(card)
        assertEquals(TransferState.Completed, card.state)
        assertEquals(10000L, card.done)
        assertEquals(listOf("/tmp/原型设计.sketch"), card.savedPaths)
    }

    @Test
    fun groupFileDeclinedUpdatesCardInGroupConversation() = runBlocking {
        val controller = controller()
        val gMeta = remoteGroup(
            groupId = "g-file-3",
            name = "测试组",
            members = listOf(lin, self),
            rev = 1,
            updatedBy = lin,
        )
        controller.onInbound(groupInfo(gMeta))

        val offer = IncomingOffer(
            transferId = "t-300",
            peerId = lin,
            rootName = "日志.log",
            files = listOf(IncomingFilePlan("f-3", "日志.log", 1024L)),
            totalSize = 1024L,
            purpose = null,
            msgId = "m-file-3",
            expiresAt = null,
            groupId = "g-file-3",
            groupRev = 1,
        )
        controller.onTransfer(TransferEvent.Offered(offer))
        controller.onTransfer(TransferEvent.Declined(transferId = "t-300", peerId = lin))

        val groupConvKey = ConvId.group("g-file-3")
        val card = controller.state.value.messagesWith(groupConvKey).first().file
        assertNotNull(card)
        assertEquals(TransferState.Declined, card.state)
    }

    @Test
    fun sendingFileToUnknownGroupEmitsGroupUnknownBanner() = runBlocking {
        val controller = controller()
        controller.sendFiles(ConvId.group("unknown-group"), listOf(LocalFile("/path/test.txt", "test.txt", 100L)))
        assertEquals(BannerMessage.GroupUnknown, controller.state.value.banner?.message)
    }

    @Test
    fun sendingFileToGroupWithoutSelfEmitsGroupLeftBanner() = runBlocking {
        val controller = controller()
        // 先以包含 self 的群初始化，再收到移除 self 的 rev 2 更新
        val owner = remoteGroup("g-left", "旧群", listOf(self, lin), rev = 1, updatedBy = lin)
            .copy(ownerId = lin, adminIds = emptyList())
        controller.onInbound(groupInfo(owner))
        controller.onInbound(
            groupInfo(owner.copy(rev = 2, members = listOf(lin), updatedBy = lin)),
        )

        controller.sendFiles(ConvId.group("g-left"), listOf(LocalFile("/path/test.txt", "test.txt", 100L)))
        assertEquals(BannerMessage.GroupLeft, controller.state.value.banner?.message)
    }

    // ---- 装置 ----

    private fun remoteGroup(
        groupId: String,
        name: String,
        members: List<String>,
        rev: Int,
        updatedBy: String,
    ): GroupMeta = GroupMeta(
        groupId = groupId,
        name = name,
        members = members,
        rev = rev,
        updatedBy = updatedBy,
        updatedTs = nowMillis(),
    )

    /** 一帧入站 `group{op:"info"}`，载荷走真实编解码（不是手搓 JSON）。 */
    private fun groupInfo(meta: GroupMeta): Envelope = envelope(
        type = MsgTypes.GROUP,
        from = meta.updatedBy,
        payload = Codec.groupInfoPayload(meta) as JsonObject,
    )

    /** 一帧入站群文本：type 是普通 `msg`，靠 kind 分开（协议就是这么定的）。 */
    private fun groupText(groupId: String, rev: Int, text: String, replyTo: String? = null): Envelope = envelope(
        type = MsgTypes.MSG,
        from = lin,
        payload = Codec.groupTextPayload(
            text = text,
            groupId = groupId,
            groupRev = rev,
            replyTo = replyTo,
        ) as JsonObject,
    )

    // ---- 群管理：邀请与移出成员 ----

    @Test
    fun addGroupMembersIncrementsRevAndUpdatesMemberList() = runBlocking {
        val controller = controller()
        val groupId = controller.createGroup("研发小组", listOf(lin))!!
        val beforeMeta = controller.state.value.settings.groups.single { it.groupId == groupId }
        assertEquals(1, beforeMeta.rev)
        assertEquals(listOf(self, lin), beforeMeta.members)

        controller.addGroupMembers(groupId, listOf(zhou))

        val afterMeta = controller.state.value.settings.groups.single { it.groupId == groupId }
        assertEquals(2, afterMeta.rev, "邀请新成员必须递增 rev")
        assertEquals(listOf(self, lin, zhou), afterMeta.members, "成员名单必须包含新成员")
        val banner = controller.state.value.banner?.message
        assertTrue(banner is BannerMessage.GroupMembersAdded && banner.count == 1)
    }

    @Test
    fun removeGroupMemberByOwnerRemovesMemberAndIncrementsRev() = runBlocking {
        val controller = controller()
        val groupId = controller.createGroup("研发小组", listOf(lin, zhou))!!
        val beforeMeta = controller.state.value.settings.groups.single { it.groupId == groupId }
        assertEquals(1, beforeMeta.rev)
        assertTrue(beforeMeta.hasMember(zhou))

        controller.removeGroupMember(groupId, zhou)

        val afterMeta = controller.state.value.settings.groups.single { it.groupId == groupId }
        assertEquals(2, afterMeta.rev, "群主移出成员必须递增 rev")
        assertFalse(afterMeta.hasMember(zhou), "移出的成员不能再存在于群中")
        assertTrue(afterMeta.hasMember(lin))
        val banner = controller.state.value.banner?.message
        assertTrue(banner is BannerMessage.GroupMemberRemoved)
    }

    @Test
    fun cannotRemoveOwnerFromGroup() = runBlocking {
        val controller = controller()
        val groupId = controller.createGroup("研发小组", listOf(lin))!!

        controller.removeGroupMember(groupId, self)

        val meta = controller.state.value.settings.groups.single { it.groupId == groupId }
        assertEquals(1, meta.rev, "试图移出群主被拒，rev 不变")
        assertTrue(meta.hasMember(self), "群主不可被移出")
        val banner = controller.state.value.banner?.message
        assertTrue(banner is BannerMessage.GroupNoPermission)
    }

    @Test
    fun groupTextPreservesReplyTo() = runBlocking {
        val controller = controller()
        controller.onInbound(
            groupInfo(remoteGroup("g-1", "研发讨论", listOf(self, lin, zhou), rev = 1, updatedBy = lin)),
        )

        // 收到对端带 replyTo 的群消息
        controller.onInbound(
            groupText("g-1", rev = 1, text = "我赞同这个方案", replyTo = "origin-msg-123"),
        )

        val convId = ConvId.group("g-1")
        val msgs = controller.state.value.messagesWith(convId)
        val inbound = msgs.firstOrNull { it.text == "我赞同这个方案" }
        assertNotNull(inbound)
        assertEquals("origin-msg-123", inbound.replyTo)
    }

    @Test
    fun forwardMessageSendsTextToTargetConversation() = runBlocking {
        val platform = InMemoryAppPlatform()
        var sentUrl: String? = null
        platform.httpClient.handler = { method, url, _, _ ->
            if (method == "POST") sentUrl = url
            SimpleHttpResponse(200, """{"ok":true}""")
        }
        val controller = RelayController(
            platform = platform,
            store = GroupMemorySettingsStore(self),
            scope = this,
            version = "relay-test",
        )
        controller.setRemoteRelay(
            url = "https://relay.example.com",
            token = "tok",
            nodeId = lin,
            nick = "Lin",
        )

        val sourceMessage = MessageUi(
            id = "src-1",
            peerId = zhou,
            mine = false,
            text = "这是一条重要通知",
            at = nowMillis(),
        )

        controller.forwardMessage(sourceMessage, lin)
        kotlinx.coroutines.yield()

        val targetMsgs = controller.state.value.messagesWith(lin)
        val forwarded = targetMsgs.firstOrNull { it.text == "这是一条重要通知" }
        assertNotNull(forwarded)
        assertTrue(forwarded.mine)
        assertEquals(BannerMessage.ForwardSuccess, controller.state.value.banner?.message)
    }

    @Test
    fun updateGroupMetaUpdatesDescriptionAndAnnounce() = runBlocking {
        val controller = controller()
        val groupId = controller.createGroup("核心项目组", listOf(lin))!!

        controller.updateGroupMeta(groupId, description = "项目简介文本", announce = "重要公告内容")

        val group = controller.state.value.groupOfConv(ConvId.group(groupId))
        assertNotNull(group)
        assertEquals("项目简介文本", group.description)
        assertEquals("重要公告内容", group.announce)
        assertEquals(BannerMessage.GroupMetaUpdated, controller.state.value.banner?.message)
    }

    /**
     * **空串 = 清空，null = 不改**，两种语义必须都能表达。
     *
     * 现场：编辑框里删光文字点「保存」，界面曾把空串折成 null，控制器对 null 的解释是
     * 「保持原值」—— 于是简介/公告永远无法清空。协议面支持空串（`GroupMeta.description`
     * 的 KDoc「空串表示未设置」，`validateGroup` 用 `isStrAllowEmpty` 放行），
     * 所以控制器对 `""` 直接落库、随 `group.info` 全量快照同步给全群。
     *
     * 反向验证：把 UI 侧（`EditGroupMetaDialog`）的 `editDesc.trim()` 改回
     * `editDesc.trim().ifEmpty { null }`，[clearingTheDescriptionWithAnEmptyString] 不会红
     * （它测的是域层语义）—— 真正被这个语义钉住的是 UI 侧的接线，由调用点形状保证；
     * 域层这里钉住的是「null 绝不能被解释成清空」与「空串绝不能被解释成不改」。
     */
    @Test
    fun clearingTheDescriptionWithAnEmptyString() = runBlocking {
        val controller = controller()
        val groupId = controller.createGroup("核心项目组", listOf(lin))!!
        controller.updateGroupMeta(groupId, description = "项目简介文本", announce = "重要公告内容")

        controller.updateGroupMeta(groupId, description = "", announce = "")

        val group = controller.state.value.groupOfConv(ConvId.group(groupId))
        assertNotNull(group)
        assertEquals("", group.description, "空串必须把简介清空（协议：空串表示未设置）")
        assertEquals("", group.announce, "空串必须把公告清空")
        assertEquals(BannerMessage.GroupMetaUpdated, controller.state.value.banner?.message)
    }

    @Test
    fun nullFieldsMeanLeaveUnchangedNotClear() = runBlocking {
        val controller = controller()
        val groupId = controller.createGroup("核心项目组", listOf(lin))!!
        controller.updateGroupMeta(groupId, description = "项目简介文本", announce = "重要公告内容")

        // 只改公告、不动简介：传 null 的那一栏必须原样保留（旧语义，不许被"清空"语义吃掉）。
        controller.updateGroupMeta(groupId, description = null, announce = "新公告")

        val group = controller.state.value.groupOfConv(ConvId.group(groupId))
        assertNotNull(group)
        assertEquals("项目简介文本", group.description, "null = 不改；把 null 清成空串会把别人的简介抹掉")
        assertEquals("新公告", group.announce)
    }

    /** 一帧入站撤回：`groupId`/`groupRev` 按协议成对出现。 */
    private fun recall(from: String, targetId: String, groupId: String, groupRev: Int): Envelope =
        envelope(
            type = MsgTypes.MSG,
            from = from,
            payload = Codec.recallPayload(targetId, groupId = groupId, groupRev = groupRev) as JsonObject,
        )

    private fun envelope(type: String, from: String, payload: JsonObject): Envelope = Envelope(
        v = 1,
        type = type,
        id = newId(),
        from = from,
        ts = nowMillis(),
        payload = payload,
    )

    private fun CoroutineScope.controller(): RelayController = RelayController(
        platform = InMemoryAppPlatform(),
        store = GroupMemorySettingsStore(self),
        scope = this,
        version = "relay-test",
    )

    private class GroupMemorySettingsStore(private val nodeId: String) : SettingsStore {
        private var current: RelaySettings? = RelaySettings(
            nodeId = nodeId,
            nick = "我的手机",
            udpPort = 0,
            tcpPort = 0,
        )

        override fun load(): RelaySettings? = current

        override fun save(settings: RelaySettings) {
            current = settings
        }
    }
}
