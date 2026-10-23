// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.domain.model.PeerUi
import com.kapp.tidings.core.protocol.GroupMeta
import com.kapp.tidings.core.protocol.VirtualAgentMember
import com.kapp.tidings.core.protocol.MsgTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「一个身份一个来源」的钉子（问题 1 / 3 / 4 的共同根因）。
 *
 * 背景：同一台桌面端在手机上有三种身份 —— 气泡署名「DSH Agent」、头像当成未知人、
 * Markdown 按「peer 是不是 agent」判成否；群成员列表里它又退化成宿主机器名
 * （`demo-mac-97`），@ 候选里干脆没有它。根因不是某个 UI 写错了，而是
 * `agent:<kind>` 从来没有被当成**成员身份**解析。这份测试钉住解析器的三件事：
 *
 * 1. agent 的名字来自 agentKind / alias，**不是**宿主昵称；
 * 2. 名字有优先级（昵称 → 备注 → 群昵称 → agent kind → 短 id + 「资料未到」）；
 * 3. @ 候选的 token 与触发判据**同源**（`@DSH` 既能插入也能叫醒）。
 */
class SenderIdentityTest {

    private val self = "self-node-0001"
    private val host = "demo-mac-97-node" // 宿主机器（桌面端）
    private val redmi = "redmi-k80-node"
    private val ghost = "a7b8c9da-aaaa-bbbb-cccc-dddddddddddd" // 只有 id、没有资料的那台

    private fun peer(
        nodeId: String,
        nick: String = "",
        remark: String = "",
        online: Boolean = true,
        isAgent: Boolean = false,
        avatar: Int = -1,
    ): PeerUi = PeerUi(
        nodeId = nodeId,
        nick = nick,
        remark = remark,
        online = online,
        avatar = avatar,
        caps = if (isAgent) listOf(MsgTypes.CAP_AGENT) else emptyList(),
    )

    /** 本群入驻了 host 上的 dsh —— 用户点过「邀请新智能体入驻」。 */
    private fun declaredDsh() = VirtualAgentMember(
        agentId = "dsh",
        hostNodeId = host,
        name = "dsh-teahouse-mobile",
        wsId = "ws-teahouse",
    )

    private fun group(
        members: List<String> = listOf(self, host, redmi, ghost),
        nicks: Map<String, String>? = null,
        /**
         * 群里入驻的 agent。**默认带一条** —— 这是真实形态：
         *
         * 群里的 agent 唯一来源是 `virtualAgents` 声明（用户点「邀请新智能体入驻」时
         * 生成的 `agent-workspace` 名字）。原先测试的默认 ctx 没有它，
         * agent 全靠「宿主 × kind」推断 —— 那正是被删掉的错误模型。
         */
        /**
         * 群里入驻的 agent。**默认 null（没有入驻）** —— 这是多数测试的真实前提。
         *
         * 群里的 agent 唯一来源是 `virtualAgents` 声明：没入驻就没有 agent、也不存在
         * 「没入驻怎么 @」这个问题。需要测 agent 的用例显式传一条进来。
         */
        virtualAgents: List<VirtualAgentMember>? = null,
    ): GroupMeta = GroupMeta(
        groupId = "g-1",
        name = "协同群",
        members = members,
        rev = 1,
        updatedBy = self,
        updatedTs = 0L,
        memberNicks = nicks,
        virtualAgents = virtualAgents,
    )

    /** 桌面端那台机器的标准上下文：它是 agent 宿主，kind = dsh，alias = DSH。 */
    private fun ctx(
        peers: List<PeerUi> = listOf(
            peer(host, nick = "demo-mac-97", isAgent = true),
            peer(redmi, nick = "REDMI K80"),
        ),
        group: GroupMeta? = group(),
        // 一台设备可以装多个 agent ⇒ 宿主 → kind **集合**。
        agentKindsByHost: Map<String, Set<String>> = mapOf(host to setOf("dsh")),
        agentAliasesByHost: Map<String, String> = mapOf(host to "DSH"),
        agentSenderIds: Set<String> = setOf("agent:dsh"),
    ): SenderIdentityContext = SenderIdentityContext(
        selfNodeId = self,
        // 自己不在 peers 里 —— 昵称必须单独给，否则群主显示成 nodeId 前 8 位。
        selfNick = "我自己",
        peers = peers,
        group = group,
        agentKindsByHost = agentKindsByHost,
        agentAliasesByHost = agentAliasesByHost,
        agentSenderIds = agentSenderIds,
    )

    // ---------------------------------------------------------------- agent 身份

    /**
     * 真机现象：群里显示「DSH Agent」但头像/Markdown 不认，成员列表显示宿主机器名。
     * 解析器必须把 `agent:dsh` 解析成 **DSH**，而不是"未知成员"或宿主的昵称。
     */
    @Test
    fun anAgentSenderIsNamedByItsKindNotByTheHostNickname() {
        val identity = assertNotNull(resolveSenderIdentity("agent:dsh", ctx()))
        assertEquals("dsh", identity.displayName)
        assertTrue(identity.isAgent)
        assertEquals("dsh", identity.agentKind)
        assertEquals("@dsh", identity.mentionToken)
        assertTrue(identity.isMember, "在群里说过话的 agent 就是群成员")
        assertEquals(true, identity.online, "agent 的在线态来自它的宿主")
    }

    /** 宿主昵称 / 群昵称都**不**参与 agent 那一档：用户要的是 DSH，不是 mac mini。 */
    @Test
    fun anAgentMemberHidesTheHostNickname() {
        val identity = resolveNodeIdentity(host, ctx())
        assertTrue(identity.isAgent)
        assertEquals("dsh", identity.displayName)
        assertEquals(DisplayNameSource.AgentKind, identity.nameSource)
        assertEquals("@dsh", identity.mentionToken)
    }

    /**
     * **alias 不得盖过 kind。**
     *
     * 真机教训：alias 是宿主给**会话**起的别名，随会话变 —— 群里出现过
     * `#协同优化测试` 这种把会话标题当 agent 人名的形态。用户问的是「这是哪个 agent」，
     * `OPENCODE` 比会话别名稳定；alias 只在 kind 缺失时兜底。
     */
    @Test
    fun anAliasNeverOverridesTheKind() {
        val identity = assertNotNull(
            resolveSenderIdentity(
                "agent:opencode",
                ctx(agentKindsByHost = mapOf(host to setOf("opencode")), agentAliasesByHost = mapOf(host to "#协同优化测试")),
            ),
        )
        assertEquals("opencode", identity.displayName)
        // @ 的 token 走 AgentMemberName.token：归一化成小写 ASCII（与触发正则同一套）。
        assertEquals("@opencode", identity.mentionToken, "@ 的 token 必须与触发判据同源") 
    }

    /** kind 缺失时才用 alias 兜底（老宿主可能只报别名）。 */
    @Test
    fun anAliasIsUsedOnlyWhenTheKindIsMissing() {
        val identity = assertNotNull(
            resolveSenderIdentity(
                "agent:dsh",
                ctx(agentAliasesByHost = mapOf(host to "DSH")),
            ),
        )
        assertEquals("dsh", identity.displayName)
    }

    /** 虚拟 agent 形态 `agent:<host>:<agentId>`：显示 agentId，不显示宿主节点昵称。 */
    @Test
    fun aVirtualAgentShowsItsAgentId() {
        val g = group().copy(
            virtualAgents = listOf(
                com.kapp.tidings.core.protocol.VirtualAgentMember(agentId = "coder-01", hostNodeId = host, name = ""),
            ),
        )
        val identity = assertNotNull(resolveSenderIdentity("agent:$host:coder-01", ctx(group = g)))
        // 三段式里的 agentId 是**宿主给这个 agent 起的名字**，原样显示、不改大小写
        // （老形态 agent:<kind> 仍然全大写，那是驱动类型当产品名）。
        assertEquals("coder-01", identity.displayName)
        assertTrue(identity.isAgent)
    }

    /**
     * **声明名优先于标识符**：`virtualAgents` 里有名字时用它，不是显示 agentId。
     *
     * 真身：这个字段以前只有读、没有写 —— 于是这条分支永远取不到值，agent 只能显示
     * 成标识符（用户看到「一群机器人名字都是驱动类型」）。写入点已补在发送侧
     * （`groupMetaWithNicks` 里，与成员昵称同一处）。
     */
    @Test
    fun aDeclaredAgentNameWinsOverItsId() {
        val g = group().copy(
            virtualAgents = listOf(
                com.kapp.tidings.core.protocol.VirtualAgentMember(
                    agentId = "coder-01",
                    hostNodeId = host,
                    name = "代码助手",
                ),
            ),
        )
        val identity = assertNotNull(resolveSenderIdentity("agent:$host:coder-01", ctx(group = g)))
        assertEquals("代码助手", identity.displayName, "声明名必须优先于 agentId")
    }

    /**
     * **一台设备上的多个 agent 是两个可被 @ 的对象。**
     *
     * 真身：`agentKindsByHost` 曾是 `Map<String, String>` + `if (!out.containsKey(host))`，
     * 每个宿主只留**第一个** kind ⇒ 装了 codex 之后 `@opencode` 永远匹配不上，
     * 表现为「@ 了却没反应」。
     */
    @Test
    fun everyAgentOnOneHostIsSeparatelyMentionable() {
        // **一台设备上的两个 agent 各自入驻，各有自己的名字。**
        //
        // 这正是「按 agent 配、一个 agent 一个名字」要保证的：codex 与 opencode
        // 在同一台机器、同一个群里，是两个独立可 @ 的对象。
        val rows = groupParticipantRows(
            ctx(
                group = group(
                    virtualAgents = listOf(
                        VirtualAgentMember(agentId = "codex", hostNodeId = host, name = "codex-a", wsId = "ws-a"),
                        VirtualAgentMember(agentId = "opencode", hostNodeId = host, name = "opencode-b", wsId = "ws-b"),
                    ),
                ),
                agentKindsByHost = mapOf(host to setOf("codex", "opencode")),
                // 夹具默认还带一条 `agent:dsh` 的发送者（模拟"只在消息里出现过"），
                // 这里要测的是"同一宿主上的两个 kind"，清掉它避免混入第三行。
                agentSenderIds = emptySet(),
            ),
        )
        assertEquals(setOf("codex", "opencode"), rows.agentKinds, "两个 agent 的 kind 都要在触发集里")
        assertEquals(2, rows.agents.count { it.agentKind != null }, "面板里也必须是两行")
        val tokens = rows.agents.mapNotNull { it.agentKind }.map { "@" + it }.toSet()
        assertEquals(setOf("@codex", "@opencode"), tokens)
    }

    @Test
    fun aMissingSenderIdHasNoIdentity() {
        assertNull(resolveSenderIdentity(null, ctx()), "缺 id 就没有身份，界面不许编一个人")
        assertNull(resolveSenderIdentity("   ", ctx()))
    }

    // ---------------------------------------------------------------- 名字优先级

    /** 档案昵称 > 本机备注 > 群昵称 > 短 id。 */
    @Test
    fun theNamePrioritiesAreInOrder() {
        val allSources = ctx(
            peers = listOf(peer(redmi, nick = "REDMI K80", remark = "红米")),
            group = group(nicks = mapOf(redmi to "群里的红米")),
        )
        assertEquals("REDMI K80", resolveNodeIdentity(redmi, allSources).displayName, "档案昵称优先")

        val remarkOnly = ctx(
            peers = listOf(peer(redmi, nick = "", remark = "红米")),
            group = group(nicks = mapOf(redmi to "群里的红米")),
        )
        assertEquals("红米", resolveNodeIdentity(redmi, remarkOnly).displayName, "没有昵称才用备注")

        val groupNickOnly = ctx(peers = emptyList(), group = group(nicks = mapOf(redmi to "群里的红米")))
        val fromGroup = resolveNodeIdentity(redmi, groupNickOnly)
        assertEquals("群里的红米", fromGroup.displayName, "本机不认识时用群元数据里的昵称")
        assertEquals(DisplayNameSource.GroupNick, fromGroup.nameSource)
    }

    /**
     * 真机现场：茶话间 0.33.2 那台没有 profile 昵称、也不在 known-peers 里，
     * 成员列表显示「未知成员」。现在显示 8 位短 id，但**必须**带「资料未到」语义 ——
     * 让裸 id 冒充真名和显示「未知成员」一样糟。
     */
    @Test
    fun anUnknownMemberFallsBackToTheShortIdButSaysProfileIsMissing() {
        val identity = resolveNodeIdentity(ghost, ctx())
        assertEquals("a7b8c9da", identity.displayName)
        assertFalse(identity.hasProfile)
        assertTrue(identity.needsProfileNote)
        assertEquals(DisplayNameSource.FallbackId, identity.nameSource)
    }

    @Test
    fun selfIsIdentifiedAndCannotBeMentioned() {
        val me = resolveNodeIdentity(self, ctx())
        assertTrue(me.isSelf)
        assertFalse(me.canMention)
    }

    // ---------------------------------------------------------------- 成员面板 / @ 候选

    /**
     * 成员面板的 agent 那一段：宿主成员与 `agent:dsh` 消息**合并成一行 DSH**，
     * 人类那一段里不再出现那台宿主机器（它现在被识别为 agent）。
     */
    @Test
    fun agentRowsMergeHostAndSenderAndLeaveHumansAlone() {
        val rows = groupParticipantRows(ctx(group = group(virtualAgents = listOf(declaredDsh()))))
        assertEquals(listOf("dsh-teahouse-mobile"), rows.agents.map { it.displayName })
        assertTrue(rows.hasAgent)
        assertEquals(setOf("dsh"), rows.agentKinds)
        // **成员恒为人类**：`group.members` 存的是节点的 nodeId，也就是"人的设备"。
        // 一台机器**同时**可以托管 agent —— 那不会让它不再是人的设备：
        // 它照旧出现在 humans 里，它托管的 agent 在 agents 里单独一行。
        //
        // 这条原来是 `assertFalse(humans.any { it.id == host })`，理由是"宿主被识别为
        // agent 后不再出现在人类成员里"。那个口径和顶栏对不上，而且真机上很难看：
        // 顶栏按 `group.members.size` 写「4 位成员」，而人类成员只画 3 行；
        // 手机端看桌面那台（CAP_AGENT，因为桌面同进程跑 :agentNode）时，
        // 群主会从成员列表里消失、名字变成机器名 demo-mac-97 挂在"入驻智能体"下。
        assertEquals(4, rows.humans.size, "4 个成员都必须在人类成员里出现")
        assertTrue(rows.humans.any { it.id == host }, "托管 agent 的宿主仍然是人类成员")
        assertTrue(rows.humans.any { it.id == ghost && !it.hasProfile })
    }

    /**
     * **三段式身份 `agent:<host>:<id>` 也必须进成员面板。**
     *
     * 真身：这里原来用 `agentKindOfSenderId(senderId) == null` 当过滤判据，而它对三段式
     * 返回 null（三段式的 kind 不在那一层）⇒ 新形态的 agent 被直接跳过、面板里看不到它。
     * 用 `AgentSenderId.parse` 判之后两种形态都算。
     */
    @Test
    fun aHostQualifiedAgentAlsoShowsUpInThePanel() {
        val rows = groupParticipantRows(
            ctx(
                peers = listOf(peer(redmi, nick = "REDMI K80")),
                agentKindsByHost = emptyMap(),
                agentAliasesByHost = emptyMap(),
                agentSenderIds = setOf("agent:redmi-k80-node:coder-01"),
            ),
        )
        // **模型变了：群里的 agent 只有一个来源 —— `virtualAgents` 声明（入驻）。**
        //
        // 原先断言「消息里出现过 `agent:<host>:<id>` ⇒ 它进成员面板」。那是错的：
        // 说过话 ≠ 入驻。任何一条历史 agent 消息都会让那个 agent 永久留在
        // 「本群的 agent」里，而它没有名字（只能现编），于是同一个 agent
        // 在群里一个名、在别处另一个名 —— 正是要消掉的分叉。
        assertTrue(
            rows.agents.isEmpty(),
            "没入驻的 agent 不该出现在群成员面板，实际=" + rows.agents.map { it.displayName },
        )
    }

    /** 群元数据里没有宿主、只在消息里出现过 agent：也要进 @ 候选，否则用户 @ 不到它。 */
    @Test
    fun anAgentThatOnlySpokeInTheGroupStillShowsUp() {
        // **说过话 ≠ 入驻。** 群里没有 `virtualAgents` 声明时，agent 不在列表里 ——
        // 那正是「没有入驻怎么 @」的答案：不能 @，也不该出现。
        val rows = groupParticipantRows(
            ctx(
                peers = listOf(peer(redmi, nick = "REDMI K80")),
                group = group(virtualAgents = null),
                agentKindsByHost = emptyMap(),
                agentAliasesByHost = emptyMap(),
                agentSenderIds = setOf("agent:dsh"),
            ),
        )
        assertTrue(
            rows.agents.isEmpty(),
            "只说过话、没入驻的 agent 不该出现在群列表，实际=" + rows.agents.map { it.mentionToken },
        )
    }

    /**
     * @ 候选的 token 与触发判据**同源**：`@DSH` 既能插入、也能叫醒；
     * 而宿主昵称（以前候选里插的就是它）插进去叫不醒 —— 这条就是"@ 了却没反应"的钉子。
     */
    @Test
    fun theMentionTokenIsTheSamePredicateTheTriggerUses() {
        val rows = groupParticipantRows(ctx(group = group(virtualAgents = listOf(declaredDsh()))))
        val agent = rows.agents.single()
        // **token 就是群里的暴露名**（入驻时生成），不是 kind。
        //
        // 这条原先断言 `@dsh` —— 那是「显示 `DSH`、插入 `@dsh`」两名字时代的产物。
        // 现在显示名、@ token、气泡署名是同一个串：用户看到什么就打什么。
        assertEquals("@dsh-teahouse-mobile", agent.mentionToken)
        assertEquals(
            agent.mentionToken.removePrefix("@"),
            agent.displayName,
            "显示名与 @ token 必须是同一个字符串（一份事实一个说法）",
        )

        assertTrue(
            RelayController.shouldTriggerAgent(
                text = "${agent.mentionToken} 跑一下测试",
                groupId = "g-1",
                isWorkspaceGroup = false,
                repliedToIsAgent = false,
                agentKinds = rows.agentKinds,
            ),
            "@DSH 必须能触发（插入与判据同源）",
        )
        assertFalse(
            RelayController.shouldTriggerAgent(
                text = "@demo-mac-97 跑一下测试",
                groupId = "g-1",
                isWorkspaceGroup = false,
                repliedToIsAgent = false,
                agentKinds = rows.agentKinds,
            ),
            "宿主昵称不是 agent 名，@ 它不该叫醒任何人",
        )
    }

    /** 群里没有 agent 时 agentInvolved 必须是 false（不许为了一条普通群消息编状态线）。 */
    @Test
    fun aHumanOnlyGroupHasNoAgentInvolved() {
        val rows = groupParticipantRows(
            ctx(
                peers = listOf(peer(redmi, nick = "REDMI K80")),
                group = group(members = listOf(self, redmi)),
                agentKindsByHost = emptyMap(),
                agentAliasesByHost = emptyMap(),
                agentSenderIds = emptySet(),
            ),
        )
        assertFalse(rows.hasAgent)
        assertTrue(rows.agentKinds.isEmpty())
        assertEquals(2, rows.humans.size, "自己和 REDMI 都在这段")
    }

    /**
     * **本机自己也要有名字。**
     *
     * 真身：`peers` 是「发现到的对端」，**不含自己**；于是群主（= 本机）在成员面板里
     * 只能显示 nodeId 前 8 位。昵称本该来自 `settings.nick`，但那条路没接。
     */
    @Test
    fun theOwnerShowsItsNicknameNotAnId() {
        val identity = resolveNodeIdentity(self, ctx())
        assertEquals("我自己", identity.displayName, "本机必须显示昵称，不是 nodeId 前 8 位")
        assertTrue(identity.isSelf)
    }

    // ------------------------------------------------------ 单聊的 @ 候选

    /**
     * 单聊里对端若是 agent 宿主，必须能 @ 到它的 agent。
     *
     * 真身：`@` 候选只从**群成员**派生（`ChatScreen` 的 `else -> emptyList()`），
     * 于是单聊里输 `@` 什么都不弹 —— 而单聊正是跟 agent 说话的主场景。
     */
    @Test
    fun aDirectChatWithAnAgentHostYieldsItsMentionCandidates() {
        val targets = directChatMentionTargets(host, ctx(group = null))
        assertEquals(1, targets.size, "一个宿主上一个 kind ⇒ 一个候选")
        assertEquals("@dsh", targets.single().mentionToken)
        assertTrue(targets.single().isAgent)
    }

    /**
     * 一台设备装两个 agent ⇒ 两个候选。
     *
     * 与群里同一条展开规则：**一个宿主上的每个 kind 各算一个 agent**。
     * 只取第一个会让 `@opencode` 永远匹配不上（真机：@ 了却没反应）。
     */
    @Test
    fun aDirectChatWithTwoAgentKindsYieldsBothCandidates() {
        val targets = directChatMentionTargets(
            host,
            ctx(group = null, agentKindsByHost = mapOf(host to setOf("dsh", "opencode"))),
        )
        assertEquals(listOf("@dsh", "@opencode"), targets.map { it.mentionToken })
    }

    /**
     * 对端**不是** agent ⇒ 空列表。
     *
     * 空就是空，不编一个候选：`CAP_AGENT` 只说明「这台机器能跑 agent」，
     * 不说明「它托管的 agent 是哪一个」（见 `agentHostsInGroup` 的同一条纪律）。
     */
    @Test
    fun aDirectChatWithAPlainPeerYieldsNoCandidates() {
        val targets = directChatMentionTargets(redmi, ctx(group = null))
        assertTrue(targets.isEmpty(), "普通对端没有可 @ 的 agent")
    }
    // ---------------------------------------------------------------- 名字只有一份

    /**
     * **显示名必须等于 @ token 去掉 `@` 之后的那串 —— 一份事实一个说法。**
     *
     * 真机现场：群列表显示 `DSH`、@ 候选也显示 `DSH`，但点进去插入的是
     * `@dsh-teahouse-mobile`。用户按看到的名字打 `@DSH`，判据认的是长 token，
     * 症状是「@ 了没反应」。
     *
     * 现在统一用群元数据里登记的 `VirtualAgentMember.name`（暴露名）。
     */
    @Test
    fun theAgentDisplayNameIsTheDeclaredExposeName() {
        val declared = GroupMeta(
            groupId = "g-1",
            name = "群",
            members = listOf(self, host),
            rev = 1,
            updatedBy = self,
            updatedTs = 0L,
            virtualAgents = listOf(
                // 真机形态：agentId 是大写 DSH（入驻时按 agentKind 兜底写入），
                // 而宿主上报的 kind 是小写 dsh —— 两者必须能对上。
                VirtualAgentMember(
                    agentId = "DSH",
                    hostNodeId = host,
                    name = "dsh-teahouse-mobile",
                    wsId = "ws-1",
                ),
            ),
        )
        val identity = groupParticipantRows(ctx(group = declared)).agents
            .first { it.isAgent }
        assertEquals(
            "dsh-teahouse-mobile",
            identity.displayName,
            "显示名必须用登记的暴露名，不能是 agentKind 大写",
        )
        assertEquals(
            "@dsh-teahouse-mobile",
            identity.mentionToken,
            "@ 要打的必须是同一个字符串",
        )
    }

    /** 没登记的 agent（没入驻这个群）才退回 kind 大写 —— 那是「不知道它在群里的名字」。 */
    @Test
    fun anUndeclaredAgentFallsBackToItsKind() {
        // **这个群没有 agent 入驻 ⇒ agent 列表是空的**（不是退回 kind 编一个名字）。
        //
        // 原先断言「没入驻时退回 kind 显示 DSH」—— 那等于给一个不在群里的 agent
        // 现编名字，用户看到 `DSH` 却没有对应的声明，@ 它也没有目标。
        val empty = GroupMeta(
            groupId = "g-1",
            name = "群",
            members = listOf(self, host),
            rev = 1,
            updatedBy = self,
            updatedTs = 0L,
        )
        val rows = groupParticipantRows(ctx(group = empty))
        assertTrue(
            rows.agents.isEmpty(),
            "没入驻的群不该凭空出现 agent，实际=" + rows.agents.map { it.displayName },
        )
    }

    // ------------------------------------------------ 裸 nodeId：人 vs agent

    /**
     * **消息署名：一个「能跑 agent 的宿主」的裸 nodeId 是「人」，显示人的昵称。**
     *
     * 真身：用户在自己的 DSH 里打字，桌面端把那条 prompt 转发进群，`senderId` 是
     * **用户的 nodeId**（说这句话的确实是人）。手机上那个 nodeId 在 `agentKindsByHost`
     * 里挂着 `dsh`（它确实装了 agent），于是被渲染成 `dsh` ——
     * **用户看到自己说的话署名成了 agent**。
     *
     * 判据错在方向：「这台机器装了 agent」≠「这条消息是 agent 说的」。
     * 后者由 `senderId` 形态决定：`agent:<host>:<id>` 是 agent，裸 `<nodeId>` 是人。
     */
    @Test
    fun aBareHostNodeIdIsHumanInTheBubbleContext() {
        val host = "a1b2c3d4-6e74-4a7c-aab1-20a1d1284cd6"
        val peer = PeerUi(nodeId = host, nick = "demo-mac-97", remark = "", online = true, avatar = -1, caps = emptyList())
        val group = GroupMeta(
            groupId = "g-1", name = "群", members = listOf(self, host),
            rev = 7, updatedBy = host, updatedTs = 0L,
            virtualAgents = listOf(
                VirtualAgentMember(agentId = "DSH", hostNodeId = host, name = "dsh-teahouse-mobile", wsId = "ws-1"),
            ),
        )
        // 成员列表的 ctx（默认：宿主按 agent 渲染）
        val memberCtx = ctx(
            peers = listOf(peer),
            group = group,
            agentKindsByHost = mapOf(host to setOf("dsh")),
        )
        // 消息署名的 ctx（裸 nodeId 按人渲染）
        val bubbleCtx = memberCtx.copy(bareNodeIdIsHuman = true)

        val asMember = resolveNodeIdentity(host, memberCtx)
        assertTrue(
            asMember.isAgent,
            "成员列表里那个裸 nodeId 代表「这台机器上的 agent」，判成 agent 是对的；实得 " + asMember.displayName,
        )

        val asHuman = resolveNodeIdentity(host, bubbleCtx)
        assertEquals(
            "demo-mac-97",
            asHuman.displayName,
            "消息署名里那是人说的一句话 ⇒ 显示人的昵称，不该显示成 agent 名",
        )
        assertFalse(asHuman.isAgent, "裸 nodeId 不是 agent 身份")
    }

    /** 带 `agent:` 前缀的发送者，两种 ctx 下都仍是 agent。 */
    @Test
    fun anAgentPrefixedSenderStaysAnAgentInBothContexts() {
        val host = "a1b2c3d4-6e74-4a7c-aab1-20a1d1284cd6"
        val peer = PeerUi(nodeId = host, nick = "demo-mac-97", remark = "", online = true, avatar = -1, caps = emptyList())
        val group = GroupMeta(
            groupId = "g-1", name = "群", members = listOf(self, host),
            rev = 7, updatedBy = host, updatedTs = 0L,
            virtualAgents = listOf(
                VirtualAgentMember(agentId = "DSH", hostNodeId = host, name = "dsh-teahouse-mobile", wsId = "ws-1"),
            ),
        )
        val base = ctx(
            peers = listOf(peer),
            group = group,
            agentKindsByHost = mapOf(host to setOf("dsh")),
        )
        for (c in listOf(base, base.copy(bareNodeIdIsHuman = true))) {
            val id = resolveSenderIdentity("agent:$host:dsh", c)
            assertEquals("dsh-teahouse-mobile", id?.displayName, "agent 说的就显示 agent 名（两种 ctx 一致）")
            assertTrue(id?.isAgent == true)
        }
    }
}
