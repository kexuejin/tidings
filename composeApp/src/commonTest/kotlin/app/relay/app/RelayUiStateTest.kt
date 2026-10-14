// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import app.relay.core.protocol.ConvId
import app.relay.core.protocol.GroupMeta
import app.relay.core.protocol.MsgTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import app.relay.app.model.DeliveryState
import app.relay.app.model.FileCardUi
import app.relay.app.model.MessageUi
import app.relay.app.model.AgentSessionKey
import app.relay.app.model.AgentSessionUi
import app.relay.app.model.PeerUi

/**
 * 会话列表是六个屏幕里唯一"从原始数据聚合出来"的东西，其余屏幕都只是把 state 摆出来。
 * 聚合错了，用户看到的是错的排序、错的未读数、错的名字 —— 所以这里逐条钉住它的规则。
 */
class RelayUiStateTest {

    private fun peer(
        nodeId: String,
        nick: String,
        online: Boolean = false,
        lastSeenAt: Long = 0L,
        caps: List<String> = emptyList(),
    ) = PeerUi(
        nodeId = nodeId,
        nick = nick,
        online = online,
        lastSeenAt = lastSeenAt,
        caps = caps,
    )

    private fun message(id: String, peerId: String, text: String, at: Long, mine: Boolean = false) =
        MessageUi(id = id, peerId = peerId, mine = mine, text = text, at = at)

    @Test
    fun newerActivitySortsBeforeOlderActivity() {
        val state = RelayUiState(
            peers = listOf(
                peer("off", "离线的人", online = false, lastSeenAt = 9_000L),
                peer("on", "在线的人", online = true, lastSeenAt = 1_000L),
            ),
        )
        // 纯时间倒序：更近活跃的排在前面
        assertEquals(listOf("off", "on"), state.conversations.map { it.convId })
    }

    @Test
    fun unreadPrioritySortsBeforeRead() {
        val state = RelayUiState(
            peers = listOf(
                peer("a", "甲", online = false, lastSeenAt = 9_000L),
                peer("b", "乙", online = true, lastSeenAt = 1_000L),
            ),
            unreadByPeer = mapOf("b" to 1),
        )
        // 未读优先级压过时间倒序
        assertEquals(listOf("b", "a"), state.conversations.map { it.convId })
    }

    @Test
    fun withinSamePresenceNewerActivityComesFirst() {
        val state = RelayUiState(
            peers = listOf(peer("a", "甲", online = true), peer("b", "乙", online = true)),
            messages = mapOf(
                "a" to listOf(message("m1", "a", "早", at = 1_000L)),
                "b" to listOf(message("m2", "b", "晚", at = 5_000L)),
            ),
        )
        assertEquals(listOf("b", "a"), state.conversations.map { it.convId })
    }

    @Test
    fun peerWithoutMessagesStillAppearsWithNullPreview() {
        // 刚配对完、还没说过话的电脑必须立刻出现在列表里，否则用户以为配对失败。
        val state = RelayUiState(
            peers = listOf(peer("fresh", "刚配对的电脑", online = true, lastSeenAt = 4_200L)),
        )
        val row = state.conversations.single()
        assertEquals("fresh", row.convId)
        assertEquals("刚配对的电脑", row.title)
        assertNull(row.preview, "还没聊过时 preview 必须是 null，文案交给 UI 翻译")
        assertEquals(4_200L, row.at, "没有消息时时间戳退回 lastSeenAt")
    }

    @Test
    fun previewAndTimeComeFromTheLastMessage() {
        val state = RelayUiState(
            peers = listOf(peer("p", "电脑", online = true, lastSeenAt = 99_000L)),
            messages = mapOf(
                "p" to listOf(
                    message("m1", "p", "第一条", at = 1_000L),
                    message("m2", "p", "最后一条", at = 7_000L, mine = true),
                ),
            ),
        )
        val row = state.conversations.single()
        assertEquals("最后一条", row.preview)
        assertEquals(7_000L, row.at, "有消息时用最后一条消息的时间，而不是 lastSeenAt")
    }

    @Test
    fun unreadAndPendingAreWiredPerPeer() {
        val state = RelayUiState(
            peers = listOf(peer("a", "甲", online = true), peer("b", "乙", online = true)),
            unreadByPeer = mapOf("a" to 2, "b" to 5),
            pendingByPeer = mapOf("a" to 3),
        )
        val byId = state.conversations.associateBy { it.convId }
        assertEquals(2, byId.getValue("a").unread)
        assertEquals(3, byId.getValue("a").pendingCount)
        assertEquals(5, byId.getValue("b").unread)
        assertEquals(0, byId.getValue("b").pendingCount, "没有积压的对端必须是 0，不是 null 也不是别人的数")
        assertEquals(3, state.totalPending)
    }

    @Test
    fun agentCapSplitsAgentsFromHumans() {
        val state = RelayUiState(
            peers = listOf(
                peer("h1", "小林"),
                peer("a1", "codex·终端", caps = listOf(MsgTypes.CAP_AGENT, MsgTypes.CAP_MOBILE_TEXT)),
                peer("h2", "书房的电脑", caps = listOf("fd1", "mrec1")),
            ),
        )
        // 判据只有 ag1 这一个能力位 —— 不靠昵称、不靠 host。
        assertEquals(listOf("a1"), state.agentPeers.map { it.nodeId })
        assertEquals(listOf("h1", "h2"), state.humanPeers.map { it.nodeId })
        assertTrue(state.conversations.single { it.convId == "a1" }.isAgent)
        assertTrue(!state.conversations.single { it.convId == "h2" }.isAgent)
    }

    @Test
    fun unknownOrMissingPeerLookupsAreEmptyNotCrashes() {
        val state = RelayUiState(peers = listOf(peer("a", "甲")))
        assertNull(state.peer(null))
        assertNull(state.peer("nobody"))
        assertEquals("甲", state.peer("a")?.nick)
        assertEquals(emptyList(), state.messagesWith(null))
        assertEquals(emptyList(), state.messagesWith("nobody"))
        assertEquals(emptyList(), RelayUiState().conversations)
        assertEquals(0, RelayUiState().onlineCount)
    }

    @Test
    fun displayNameFallsBackHostThenIpThenNodeIdPrefix() {
        assertEquals("小林", peer("n", "小林").displayName)
        assertEquals("mac.local", PeerUi(nodeId = "n", nick = "", host = "mac.local").displayName)
        assertEquals("10.0.0.9", PeerUi(nodeId = "n", nick = "", host = "", ip = "10.0.0.9").displayName)
        assertEquals("12345678", PeerUi(nodeId = "12345678-rest", nick = "").displayName)
    }

    /**
     * 冷启动回归：`MessageStore` 恢复出来的历史早于网络发现，所以恢复完的那一刻
     * `peers` 里**一定还没有**对端记录。如果会话列表只遍历 `peers`，磁盘上有消息、
     * 重启后列表却是空的 —— 用户看到的就是"重启即丢消息"。
     */
    @Test
    fun restoredThreadWithoutPeerRecordStillShowsUp() {
        val state = RelayUiState(
            peers = emptyList(),
            messages = mapOf(
                "2819d147-rest" to listOf(
                    message("m1", "2819d147-rest", "在吗", at = 100L),
                    message("m2", "2819d147-rest", "刚发的那条", at = 200L, mine = true),
                ),
            ),
            unreadByPeer = mapOf("2819d147-rest" to 2),
            pendingByPeer = mapOf("2819d147-rest" to 1),
        )
        val row = state.conversations.single()
        assertEquals("2819d147-rest", row.convId)
        // 对端还没被重新发现：离线、非 Agent，标题退回 nodeId 前缀（和 displayName 末级兜底一致）。
        assertTrue(!row.online)
        assertTrue(!row.isAgent)
        assertEquals("2819d147", row.title)
        // 预览/时间取恢复出来的最后一条消息，未读与排队数照常挂在这个 peerId 上。
        assertEquals("刚发的那条", row.preview)
        assertEquals(200L, row.at)
        assertEquals(2, row.unread)
        assertEquals(1, row.pendingCount)
    }

    /**
     * 对端后来真的又被发现了：同一个 nodeId 只能有一条会话，而且要用真实记录
     * 顶替掉兜底的 nodeId 前缀（昵称、在线状态都跟着换过来）。
     */
    @Test
    fun realPeerRecordReplacesTheFallbackWithoutDuplicating() {
        val thread = mapOf(
            "n1" to listOf(message("m1", "n1", "早安", at = 200L)),
        )
        val before = RelayUiState(peers = emptyList(), messages = thread).conversations
        val after = RelayUiState(
            peers = listOf(peer("n1", "书房的电脑", online = true, lastSeenAt = 300L)),
            messages = thread,
        ).conversations
        assertEquals(listOf("n1"), before.map { it.convId })
        assertEquals(listOf("n1"), after.map { it.convId })
        assertEquals("书房的电脑", after.single().title)
        assertTrue(after.single().online)
        assertEquals(200L, after.single().at)
    }

    /**
     * 聊天页的同一个坑：列表修好了还不够，点进"只剩历史、对端还没被发现"的会话时，
     * `state.peer()` 返回 null，聊天页过去会直接画"选一个会话"，等于历史仍然读不到。
     * `chatPeer` 要在这种时候给出占位对端，让历史能打开。
     */
    @Test
    fun chatPeerFallsBackToAPlaceholderWhenOnlyHistoryRemains() {
        val restored = RelayUiState(
            peers = emptyList(),
            messages = mapOf("2819d147-rest" to listOf(message("m1", "2819d147-rest", "在吗", at = 200L))),
        )
        // `assertNotNull` 带 contract，返回的就是非空值 —— 后面不必再写 `?.`。
        val placeholder = assertNotNull(restored.chatPeer("2819d147-rest"))
        assertEquals("2819d147-rest", placeholder.nodeId)
        // 标题与列表里的兜底一致（nodeId 前缀），状态如实显示离线、非 Agent。
        assertEquals("2819d147", placeholder.displayName)
        assertFalse(placeholder.online)
        assertFalse(placeholder.isAgent)

        // 对端真被发现了就必须用真实记录，不能拿占位的把昵称和在线状态盖掉。
        val discovered = restored.copy(
            peers = listOf(peer("2819d147-rest", "书房的电脑", online = true)),
        )
        assertEquals("书房的电脑", discovered.chatPeer("2819d147-rest")?.displayName)

        // 没有任何历史的未知 id 不给占位：没有东西可看的会话仍然该画"选一个会话"。
        assertNull(restored.chatPeer("从未说过话"))
        assertNull(restored.chatPeer(null))
    }

    /**
     * 落盘那一层只做一件事：把重启后一定作废的东西扔掉（文件卡片路径）。
     * 排队这件事**发生过**，它是历史事实，不是运行时缓存 —— 所以 `Queued` 必须如实进文件。
     * 以前在这里把 `Queued` 抹成 null，等于顺手替重启后的行为做了决定。
     */
    @Test
    fun storableKeepsQueuedAndOnlyDropsTheFileCard() {
        val queued = MessageUi(
            id = "m1",
            peerId = "peer-1",
            mine = true,
            text = "对方离线，排着队",
            at = 1_000L,
            delivery = DeliveryState.Queued,
            file = FileCardUi(
                transferId = "t1",
                name = "报告.pdf",
                size = 2048L,
                savedPaths = listOf("/tmp/a.pdf"),
            ),
        )

        val storable = queued.toStorable()

        assertEquals(DeliveryState.Queued, storable.delivery, "排队是发生过的事，落盘不该抹平它")
        assertNull(storable.file, "路径与传输单据重启后一律作废")
    }

    /**
     * 恢复那一层才做决定：core 的 `InMemoryQueueStore` 是进程内的，重启就是空表，
     * 排队的消息不会有人再替我们补发。此时既不该显示"等待中"（永远不会动了），
     * 也不该没徽标（看着像已送达），落到终态 `Failed` 才是实话。
     */
    @Test
    fun restoredTurnsMyQueuedIntoFailed() {
        val mine = MessageUi(
            id = "m1",
            peerId = "peer-1",
            mine = true,
            text = "对方离线，排着队",
            at = 1_000L,
            delivery = DeliveryState.Queued,
        )
        assertEquals(DeliveryState.Failed, mine.toRestored().delivery)

        // 入站的排队态不在我们手里，不能替对方改判。
        val theirs = mine.copy(mine = false)
        assertEquals(DeliveryState.Queued, theirs.toRestored().delivery)

        // 终态原样不动：Sent / Failed / Recalled / 无状态都不该被恢复逻辑碰。
        assertEquals(DeliveryState.Sent, mine.copy(delivery = DeliveryState.Sent).toRestored().delivery)
        assertEquals(DeliveryState.Failed, mine.copy(delivery = DeliveryState.Failed).toRestored().delivery)
        assertEquals(DeliveryState.Recalled, mine.copy(delivery = DeliveryState.Recalled).toRestored().delivery)
        assertNull(mine.copy(delivery = null).toRestored().delivery)
    }

    /**
     * 端到端钉住这条链：落盘如实记下"排过队"，恢复再如实改成"没发出去"。
     */
    @Test
    fun aQueuedMessageGoesOutAsFailedAndSentStaysSent() {
        val queued = MessageUi(
            id = "m1",
            peerId = "peer-1",
            mine = true,
            text = "排队的",
            at = 1_000L,
            delivery = DeliveryState.Queued,
        )
        val sent = queued.copy(id = "m2", text = "发出去的", at = 2_000L, delivery = DeliveryState.Sent)

        val restored = listOf(queued, sent).map { it.toStorable().toRestored() }

        assertEquals(DeliveryState.Failed, restored[0].delivery)
        assertEquals(DeliveryState.Sent, restored[1].delivery)
    }

    /**
     * 队列项被剪枝丢掉之后那张卡片该长什么样。
     *
     * 两条规则各自都有坏掉的后果：
     * - 排队的没变成失败 → 卡片永远停在「已排队」，而队列里已经没有它了（状态过期没自愈）；
     * - 已送达的被改回失败 → ACK 与剪枝擦肩而过时，一条真送到了的消息被说成没送出去。
     */
    @Test
    fun prunedQueuedMessagesBecomeFailedAndSentOnesDoNot() {
        val queued = MessageUi(
            id = "m1",
            peerId = "peer-1",
            mine = true,
            text = "排队中",
            at = 1_000L,
            delivery = DeliveryState.Queued,
        )

        val after = queued.afterPruned()
        assertEquals(DeliveryState.Failed, after.delivery)
        assertEquals(DeliveryState.Failed, after.delivery, "队列项被剪掉 ⇒ 这条永远不会发出去")
        assertEquals(queued.text, after.text, "剪枝只改投递态，正文一个字都不能动")

        // 非排队态一律原样返回，连拷贝都不做（`===` 而非 `==`）：剪枝只对"排队中"有权改判。
        // 已送达尤其重要 —— ACK 与剪枝是两条独立的路，擦肩而过时把「已送达」改回「失败」是撒谎。
        val sent = queued.copy(delivery = DeliveryState.Sent)
        assertTrue(sent.afterPruned() === sent)
        val recalled = queued.copy(delivery = DeliveryState.Recalled)
        assertTrue(recalled.afterPruned() === recalled)
        val noBadge = queued.copy(delivery = null)
        assertTrue(noBadge.afterPruned() === noBadge)
    }

    @Test
    fun aGroupConversationIsNamedAfterTheGroupNotAfterItsConversationKey() {
        // `convId` 是**会话键**而不是节点 id：群会话的键是 `group:<groupId>`，在节点表里
        // 永远查不到人。曾经有代码写成 `peer(convId)?.displayName ?: convId.take(8)`，
        // 于是群名显示成 `group:3a`（Pixel 6 与 iOS 模拟器上都拍到过）。
        // 这条守卫原来挂在聊天详情标签条的 `sessionTabsOf()` 上；标签条已按产品决策移除，
        // 但它盯的本来就是 convTitle 这个函数，所以搬到这里继续盯。
        val groupId = "3ae78fe9-b605-4979-80d0-c608877b3cd8"
        val convId = ConvId.group(groupId)
        val state = RelayUiState(
            settings = RelaySettings.firstRun().copy(
                groups = listOf(
                    GroupMeta(
                        groupId = groupId,
                        name = "周末爬山",
                        members = listOf("me", "you"),
                        rev = 1,
                        updatedBy = "me",
                        updatedTs = 1L,
                    ),
                ),
            ),
        )

        assertEquals("周末爬山", state.convTitle(convId))
    }

    @Test
    fun aGroupThatHasNotSyncedYetLosesTheConversationKeyPrefix() {
        // 群元数据还没到（或刚被删）时的兜底是 groupId 前缀，**不是** `group:` 那一段：
        // 会话键的前缀是实现细节，不该出现在用户眼前。
        val groupId = "3ae78fe9-b605-4979-80d0-c608877b3cd8"
        val title = RelayUiState().convTitle(ConvId.group(groupId))

        assertEquals("3ae78fe9", title)
        assertFalse(title.startsWith("group:"))
    }
    /**
     * 会话列表的顺序必须跟着**宿主的活动时刻**走，不是"本端收到帧的时刻"。
     *
     * 复现的是真机症状：历史分页一页 20 条走同一个 TCP tick 到达，`updatedAt` 全落在同一毫秒
     * ⇒ `sortedByDescending` 退化成 map 插入序 ⇒ 手机上显示成"最近创建的"甚至乱序，
     * 而用户在 DSH 里看到的是活动倒序（原话「跟 dsh 上显示的不一样，不是最新的」）。
     */
    /**
     * 可见性两键必须按 DSH 自己的 `sessionVisible()` 生效 —— 真机上这条判据没生效时，
     * 浮层里成批出现「Route this bounded MEMORY.md archive…」（子代理会话）与成对的「未命名」
     * （它们的空壳父会话），用户的原话是「怎么都是这些」。
     */
    @Test
    fun subagentAndBlankSessionsAreHiddenLikeDshDoes() {
        fun session(
            id: String,
            wsId: String? = "ws-1",
            origin: String? = null,
            blank: Boolean? = null,
            heard: Long = 1_000L,
        ) = AgentSessionUi(
            peerId = "dev", sessionId = id, agentKind = "DSH", updatedAt = heard,
            wsId = wsId, hostOrigin = origin, hostBlank = blank,
        )
        val state = RelayUiState(
            peers = listOf(peer("dev", "开发机", online = true, caps = listOf(MsgTypes.CAP_AGENT))),
            agentSessions = listOf(
                session("s-real", heard = 300),
                session("s-sub", origin = "subagent", heard = 200),
                session("s-shell", blank = true, heard = 100),
            ).associateBy { AgentSessionKey("dev", it.sessionId) },
        )

        assertEquals(
            listOf("s-real"),
            state.visibleAgentSessionsOf("dev").map { it.sessionId },
            "子代理会话与空壳会话都该藏起来（同工作区还有 s-real 这条可见会话）",
        )

        // 原始表必须原样保留 —— 分页游标（App.kt 的 onLoadMoreAgentSessions）与
        // ChatScreen.hasMoreSessionsLocal 都数它，两边同分母是 offset 正确的前提。
        assertEquals(3, state.agentSessionsOf("dev").size, "agentSessionsOf 不该被UI需求带成过滤版")

        // 一个工作区**全是空壳**时照旧显示：否则那一行会变成 0 个会话，而"看起来是空"
        // 比"多一行未命名"更糟（与 dsh-tidings 的 visibleOnPhone 同一取舍）。
        val onlyShells = RelayUiState(
            peers = listOf(peer("dev", "开发机", online = true, caps = listOf(MsgTypes.CAP_AGENT))),
            agentSessions = listOf(
                session("s-a", blank = true, heard = 200),
                session("s-b", blank = true, heard = 100),
            ).associateBy { AgentSessionKey("dev", it.sessionId) },
        )
        assertEquals(
            listOf("s-a", "s-b"),
            onlyShells.visibleAgentSessionsOf("dev").map { it.sessionId },
            "整组都是空壳时要放行，不能让工作区显示成 0 个会话",
        )

        // 老宿主一条都没报两键：原样放行 —— 「没听说」不等于「确认可见」，但更不能等于「藏起来」。
        val legacy = RelayUiState(
            peers = listOf(peer("dev", "开发机", online = true, caps = listOf(MsgTypes.CAP_AGENT))),
            agentSessions = listOf(
                session("s-old-1"),
                session("s-old-2", heard = 900),
            ).associateBy { AgentSessionKey("dev", it.sessionId) },
        )
        assertEquals(2, legacy.visibleAgentSessionsOf("dev").size, "老宿主没报两键时不该藏任何会话")

        // 归档会话默认隐藏（对齐 DSH 的 hide-archived 默认行为）。
        val withArchived = RelayUiState(
            peers = listOf(peer("dev", "开发机", online = true, caps = listOf(MsgTypes.CAP_AGENT))),
            agentSessions = listOf(
                session("s-active", heard = 300),
                AgentSessionUi(
                    peerId = "dev", sessionId = "s-archived", agentKind = "DSH", state = "archived",
                    updatedAt = 200L, wsId = "ws-1",
                ),
            ).associateBy { AgentSessionKey("dev", it.sessionId) },
        )
        assertEquals(
            listOf("s-active"),
            withArchived.visibleAgentSessionsOf("dev").map { it.sessionId },
            "归档会话默认在主列表中隐藏",
        )
    }

    @Test
    fun agentSessionsSortByHostActivityNotByWhenWeHeardThem() {
        fun session(id: String, heard: Long, host: Long?) = AgentSessionUi(
            peerId = "dev",
            sessionId = id,
            agentKind = "DSH",
            updatedAt = heard,
            hostUpdatedAt = host,
        )
        val state = RelayUiState(
            peers = listOf(peer("dev", "开发机", online = true, caps = listOf(MsgTypes.CAP_AGENT))),
            agentSessions = listOf(
                // 三条帧同时刻到达（heard 全相等）—— 只有宿主时刻能分开它们。
                session("s-oldest-work", heard = 5_000L, host = 1_000L),
                session("s-newest-work", heard = 5_000L, host = 9_000L),
                session("s-middle-work", heard = 5_000L, host = 4_000L),
            ).associateBy { AgentSessionKey("dev", it.sessionId) },
        )

        assertEquals(
            listOf("s-newest-work", "s-middle-work", "s-oldest-work"),
            state.agentSessionsOf("dev").map { it.sessionId },
            "同一毫秒到达时必须按宿主活动时刻排，否则就是到达序",
        )

        // 老宿主（不发尾键）：退回本端收帧时刻 —— 时间的下界，不是 0（0 会让它永远垫底）。
        val mixed = RelayUiState(
            peers = listOf(peer("dev", "开发机", online = true, caps = listOf(MsgTypes.CAP_AGENT))),
            agentSessions = listOf(
                session("s-with-host", heard = 1_000L, host = 2_000L),
                session("s-legacy", heard = 3_000L, host = null),
            ).associateBy { AgentSessionKey("dev", it.sessionId) },
        )
        assertEquals(
            listOf("s-legacy", "s-with-host"),
            mixed.agentSessionsOf("dev").map { it.sessionId },
            "没报宿主时刻的会话不该被当成最旧的一条",
        )
    }
}
