// SPDX-License-Identifier: GPL-3.0-only

package app.relay.agent

import app.relay.core.protocol.AgentExecPolicies
import app.relay.core.protocol.GroupMeta
import app.relay.core.protocol.VirtualAgentMember
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `/v1/inbox` 的注入判定。
 *
 * 用户的规则：agent 进群时**要么绑定了工作区，要么没绑定**；两种都要能被 @。
 * 授权：**只有群主 / 管理员 / agent 自己的节点用户 / 显式授权者**可以让它执行。
 */
class AgentInboxEnricherTest {

    private val owner = "node-owner"
    private val admin = "node-admin"
    private val host = "node-host"
    private val member = "node-member"

    private fun meta(vararg agents: VirtualAgentMember) = GroupMeta(
        groupId = "g-1", name = "群",
        members = listOf(owner, admin, host, member),
        rev = 1, updatedBy = owner, updatedTs = 0L,
        ownerId = owner,
        adminIds = listOf(admin),
        virtualAgents = agents.toList(),
    )

    private fun agent(name: String, wsId: String? = null) =
        VirtualAgentMember(agentId = name, hostNodeId = host, name = name, wsId = wsId)

    /** 绑了工作区的 agent：hint 必须带出 wsId。 */
    @Test
    fun aBoundAgentHintCarriesTheWorkspace() {
        val hint = resolveAgentInboxHint(meta(agent("dsh-t", wsId = "ws-9")), owner, "@dsh-t 跑测试")
        assertEquals(true, hint?.addressedToAgent)
        assertEquals("ws-9", hint?.boundWsId)
        assertEquals("dsh-t", hint?.agentName)
    }

    /** 没绑工作区的 agent 同样给出 hint，只是 boundWsId 为 null。 */
    @Test
    fun anUnboundAgentHintHasNoWorkspace() {
        val hint = resolveAgentInboxHint(meta(agent("dsh")), owner, "@dsh 你好")
        assertNull(hint?.boundWsId, "没绑定就是 null，不许编一个工作区")
    }

    /** 没 @ 到任何 agent ⇒ null。 */
    @Test
    fun noMentionYieldsNoHint() {
        assertNull(resolveAgentInboxHint(meta(agent("dsh")), owner, "随便聊聊"))
    }

    /** 群没有虚拟 agent ⇒ null。 */
    @Test
    fun aGroupWithoutVirtualAgentsYieldsNoHint() {
        assertNull(resolveAgentInboxHint(null, owner, "@dsh 你好"))
        assertNull(resolveAgentInboxHint(meta(), owner, "@dsh 你好"))
    }

    // ---------------------------------------------------------------- 授权

    /**
     * **群主在默认档（`list`）下不能执行** —— 他建了这个群，不等于他能命令我的机器。
     *
     * 这条原先写的是 `theOwnerMayExecute`，断言 `senderMayExecute == true`。它能绿是因为
     * 当年的判据是**黑名单**式（`verdict != MemberOnly && verdict != NotAMember`）：
     * `OwnerNeedsGrant` 不在排除列表里，于是被静默放行 —— 与 `AgentExecAuthorizer` 的
     * KDoc（引的是产品口径原话「群主 / 管理员的确不能默认批准」）直接相反。
     *
     * 正确的语义：他有**资格申请**，但仍需本机用户在可信设备面板里点一下。
     * `denyReason` 因此是 `OwnerNeedsGrant` —— 界面上说得清「你差的是进名单」。
     */
    @Test
    fun theOwnerNeedsAGrant() {
        val h = resolveAgentInboxHint(meta(agent("dsh")), owner, "@dsh 跑")
        assertEquals(false, h?.senderMayExecute, "群主不默认获得执行权")
        assertEquals("OwnerNeedsGrant", h?.denyReason)
    }

    /** 管理员同理：群角色换不来执行权，要进可信设备名单。 */
    @Test
    fun anAdminNeedsAGrant() {
        val h = resolveAgentInboxHint(meta(agent("dsh")), admin, "@dsh 跑")
        assertEquals(false, h?.senderMayExecute, "管理员不默认获得执行权")
        assertEquals("AdminNeedsGrant", h?.denyReason)
    }

    /** agent 自己的节点用户可以执行。 */
    @Test
    fun theAgentHostMayExecute() {
        assertEquals(true, resolveAgentInboxHint(meta(agent("dsh")), host, "@dsh 跑")?.senderMayExecute)
    }

    /**
     * **只是群成员的人不能执行** —— 群成员身份是可达性，不是授权。
     */
    @Test
    fun aPlainMemberMayNotExecute() {
        val h = resolveAgentInboxHint(meta(agent("dsh")), member, "@dsh 跑")
        assertEquals(false, h?.senderMayExecute)
        assertEquals("MemberOnly", h?.denyReason)
    }

    /** 非成员不能执行。 */
    @Test
    fun aNonMemberMayNotExecute() {
        val h = resolveAgentInboxHint(meta(agent("dsh")), "node-stranger", "@dsh 跑")
        assertEquals(false, h?.senderMayExecute)
        assertEquals("NotAMember", h?.denyReason)
    }

    /** 显式授权可以补足普通成员（走宿主自己的授权表）。 */
    @Test
    fun anExplicitGrantLetsAPlainMemberExecute() {
        val h = resolveAgentInboxHint(
            meta = meta(agent("dsh")),
            senderId = member,
            text = "@dsh 跑",
            grantAllows = { it == member },
            // 默认档是最严的 hostOnly，要让可信设备生效必须显式放开。
            execPolicy = AgentExecPolicies.LIST,
        )
        assertEquals(true, h?.senderMayExecute)
    }
    // ---------------------------------------------------------------- 回声判据

    /** **本机用户 @ 自己的 agent：senderIsAgent 必须为 false。**
     *
     * 桥接侧用这个键判回声（而不是看 direction）：真机上 direction=out 被当成回声跳过，
     * 结果是「消息发出去了、对端也收到、但 agent 从不执行」。
     */
    @Test
    fun aHumanSenderIsNotMarkedAsAgent() {
        val h = resolveAgentInboxHint(
            meta = meta(agent("dsh")),
            senderId = member,
            text = "@dsh-teahouse-mobile 跑测试",
            grantAllows = { true },
        )
        assertEquals(false, h?.senderIsAgent, "人发的消息不该被标成 agent 说的")
    }

    /** **agent 自己发的：senderIsAgent 必须为 true**（那才是回声）。 */
    @Test
    fun anAgentSenderIsMarkedAsAgent() {
        val h = resolveAgentInboxHint(
            meta = meta(agent("dsh")),
            senderId = "agent:$host:dsh",
            text = "@dsh 继续",
            grantAllows = { true },
        )
        assertEquals(true, h?.senderIsAgent)
    }

    /** 老形态 `agent:<kind>` 也算 agent（真机库里有大量这种写法的历史消息）。 */
    @Test
    fun theLegacyAgentSenderShapeIsStillAnAgent() {
        val h = resolveAgentInboxHint(
            meta = meta(agent("dsh")),
            senderId = "agent:dsh",
            text = "@dsh 继续",
            grantAllows = { true },
        )
        assertEquals(true, h?.senderIsAgent)
    }
    // ---------------------------------------------------------------- agent 消息的回声

    /**
     * **agent 自己发的群消息必须被标成 agent**（否则会自激循环）。
     *
     * 真机事故：agent 的回复里提到了 `@dsh`，而 `ControlPlane` 取的是信封 `from`（本机
     * nodeId）而不是 `payload.senderId`（`agent:...:dsh`）⇒ `senderIsAgent=false` ⇒
     * 回声判据失效 ⇒ 它把自己的话当成新指令再投一遍 ⇒ 群里滚出一串「不是我发的」消息。
     */
    @Test
    fun anAgentAuthoredGroupMessageIsMarkedAsAgent() {
        val h = resolveAgentInboxHint(
            meta = meta(agent("dsh")),
            senderId = "agent:$host:dsh",
            text = "我刚说过 @dsh 的用法",
            grantAllows = { true },
        )
        assertEquals(true, h?.senderIsAgent, "agent 自己的话必须能被认出来，否则会自激")
    }
}
