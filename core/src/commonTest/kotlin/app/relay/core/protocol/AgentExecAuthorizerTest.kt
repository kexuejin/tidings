// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.protocol

import app.relay.core.protocol.AgentExecAuthorizer.Verdict
import app.relay.core.protocol.AgentExecAuthorizer.permitsExecution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「谁能让本机 agent 真的执行」的判据。
 *
 * 用户定的口径：「群主 / 管理员的确不能默认批准。」
 *
 * 只有两类默认可以：**agent 自己的节点用户**、**名单里的设备**。
 * 群内任何人那一档是明确的逃生档，要用户显式选才生效。
 */
class AgentExecAuthorizerTest {

    private val owner = "node-owner"
    private val admin = "node-admin"
    private val agentHost = "node-agent-host"
    private val plainMember = "node-member"
    private val stranger = "node-stranger"

    private fun group(virtualAgents: List<VirtualAgentMember>? = null) = GroupMeta(
        groupId = "g-1",
        name = "测试群",
        members = listOf(owner, admin, agentHost, plainMember),
        rev = 1,
        updatedBy = owner,
        updatedTs = 0L,
        ownerId = owner,
        adminIds = listOf(admin),
        virtualAgents = virtualAgents,
    )

    private fun agent(host: String) =
        VirtualAgentMember(agentId = "dsh", hostNodeId = host, name = "dsh")

    /** agent 自己的节点用户免问（他本来就能在自己机器上执行）。 */
    @Test
    fun theAgentHostUserCanExecuteWithoutApproval() {
        val g = group(listOf(agent(agentHost)))
        val v = AgentExecAuthorizer.authorize(agentHost, g)
        assertEquals(Verdict.AgentHost, v)
        assertTrue(v.permitsExecution())
    }

    /** 名单里的设备可以执行（这是默认档下唯一的授权来源）。 */
    @Test
    fun aListedDeviceCanExecute() {
        val v = AgentExecAuthorizer.authorize(
            plainMember,
            group(),
            grantAllows = { it == plainMember },
        )
        assertEquals(Verdict.GrantedDevice, v)
        assertTrue(v.permitsExecution())
    }

    /**
     * **群主不默认放行。**
     *
     * 「群主可能不是这台机器的主人。他建了这个群，不等于他能命令我的机器。」
     */
    @Test
    fun theOwnerNeedsToBeListed() {
        val v = AgentExecAuthorizer.authorize(owner, group())
        assertEquals(Verdict.OwnerNeedsGrant, v)
        assertFalse(v.permitsExecution(), "群主不该仅凭群角色就能执行")
    }

    /** **管理员同样不默认放行。** */
    @Test
    fun anAdminNeedsToBeListed() {
        val v = AgentExecAuthorizer.authorize(admin, group())
        assertEquals(Verdict.AdminNeedsGrant, v)
        assertFalse(v.permitsExecution())
    }

    /** 群主进了名单之后就能执行（出口是有的）。 */
    @Test
    fun theOwnerCanExecuteOnceListed() {
        val v = AgentExecAuthorizer.authorize(owner, group(), grantAllows = { it == owner })
        assertEquals(Verdict.GrantedDevice, v)
        assertTrue(v.permitsExecution())
    }

    /** 只是群成员 ⇒ 不能执行。 */
    @Test
    fun aPlainMemberCannotExecute() {
        val v = AgentExecAuthorizer.authorize(plainMember, group())
        assertEquals(Verdict.MemberOnly, v)
        assertFalse(v.permitsExecution())
    }

    /** 非成员 ⇒ 不能执行（**任何档位**都不行）。 */
    @Test
    fun aNonMemberCannotExecute() {
        assertEquals(Verdict.NotAMember, AgentExecAuthorizer.authorize(stranger, group()))
        assertEquals(
            Verdict.NotAMember,
            AgentExecAuthorizer.authorize(stranger, group(), null, AgentExecPolicies.ANY_MEMBER),
        )
    }

    /** 没有发送者 id ⇒ fail-closed。 */
    @Test
    fun aMissingSenderCannotExecute() {
        for (s in listOf(null, "", "   ")) {
            assertEquals(Verdict.NotAMember, AgentExecAuthorizer.authorize(s, group()))
        }
    }

    /** 非群聊 ⇒ 本判据不适用，fail-closed。 */
    @Test
    fun aDirectChatIsOutOfScopeAndFailsClosed() {
        assertEquals(Verdict.NotAMember, AgentExecAuthorizer.authorize(owner, null))
    }

    /**
     * **默认档只认名单**：没有名单记录时，除了 agent 宿主谁都不行。
     */
    @Test
    fun theDefaultPolicyOnlyAllowsTheAgentHost() {
        val g = group(listOf(agent(agentHost)))
        for (s in listOf(owner, admin, plainMember)) {
            assertFalse(AgentExecAuthorizer.authorize(s, g, null).permitsExecution(), s)
        }
        assertTrue(AgentExecAuthorizer.authorize(agentHost, g, null).permitsExecution())
    }

    /**
     * **逃生档：群内任何成员。**
     *
     * 这是用户显式选择才生效的档 —— 选它意味着接受「进群即可执行」。
     */
    @Test
    fun theAnyMemberEscapeHatchAllowsEveryMember() {
        val g = group();
        for (s in listOf(owner, admin, plainMember)) {
            assertTrue(
                AgentExecAuthorizer.authorize(s, g, null, AgentExecPolicies.ANY_MEMBER).permitsExecution(),
                "$s 应当被放行",
            )
        }
    }

    /** **agent 宿主在任何档位下都免问**。 */
    @Test
    fun theAgentHostAlwaysExecutesRegardlessOfPolicy() {
        val g = group(listOf(agent(agentHost)))
        for (p in AgentExecPolicies.VALUES) {
            assertTrue(
                AgentExecAuthorizer.authorize(agentHost, g, null, p).permitsExecution(),
                "$p 档下 agent 宿主都应能执行",
            )
        }
    }

    /** 未知 / 缺省策略值收到最严一档（fail-closed）。 */
    @Test
    fun anUnknownPolicyFallsBackToTheListPolicy() {
        for (raw in listOf(null, "", "   ", "someFuturePolicy")) {
            assertEquals(AgentExecPolicies.LIST, AgentExecPolicies.normalize(raw))
        }
    }

    /** 只有这三档 permitsExecution —— 防止将来有人「顺手」多放行一档。 */
    @Test
    fun exactlyTheseVerdictsPermitExecution() {
        val permitting = Verdict.entries.filter { it.permitsExecution() }.toSet()
        assertEquals(
            setOf(Verdict.AgentHost, Verdict.GrantedDevice, Verdict.AnyMemberGranted),
            permitting,
        )
    }
}
