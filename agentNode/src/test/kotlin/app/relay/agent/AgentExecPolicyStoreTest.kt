// SPDX-License-Identifier: GPL-3.0-only

package app.relay.agent

import app.relay.core.protocol.AgentExecPolicies
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 每个 agent 的执行范围存储。
 *
 * 核心不变量：**任何读不出来的情况都回落到最严一档**。
 * 因为执行不可撤销，「更严」只是让用户去配一次，「更松」会让人在他机器上跑命令。
 */
class AgentExecPolicyStoreTest {

    private fun tempFile(): File =
        File.createTempFile("exec-policies", ".json").also { it.delete() }

    /** 没配过的 agent ⇒ 最严一档（新装的 agent 自动安全）。 */
    @Test
    fun anUnconfiguredAgentDefaultsToHostOnly() {
        val store = AgentExecPolicyStore(tempFile())
        assertEquals(AgentExecPolicies.LIST, store.policyOf("dsh"))
        assertEquals(AgentExecPolicies.LIST, store.policyOf(null))
        assertEquals(AgentExecPolicies.LIST, store.policyOf("  "))
    }

    /** 设置了就按设置来，且**落盘后重启仍在**。 */
    @Test
    fun aConfiguredPolicySurvivesARestart() {
        val file = tempFile()
        val store = AgentExecPolicyStore(file)
        assertTrue(store.set("dsh", AgentExecPolicies.ANY_MEMBER))
        assertEquals(AgentExecPolicies.ANY_MEMBER, store.policyOf("dsh"))

        val reloaded = AgentExecPolicyStore(file)
        assertEquals(AgentExecPolicies.ANY_MEMBER, reloaded.policyOf("dsh"))
    }

    /**
     * **两个 agent 互不影响** —— 这是「按 agent 配」的全部价值。
     */
    @Test
    fun twoAgentsAreIndependent() {
        val store = AgentExecPolicyStore(tempFile())
        store.set("dsh", AgentExecPolicies.ANY_MEMBER)
        store.set("codex", AgentExecPolicies.LIST)
        assertEquals(AgentExecPolicies.ANY_MEMBER, store.policyOf("dsh"))
        assertEquals(AgentExecPolicies.LIST, store.policyOf("codex"))
    }

    /** 非法档位被拒，且**不做任何修改**（不是静默写成默认）。 */
    @Test
    fun anInvalidPolicyIsRejectedWithoutSideEffects() {
        val store = AgentExecPolicyStore(tempFile())
        store.set("dsh", AgentExecPolicies.ANY_MEMBER)
        assertFalse(store.set("dsh", "someFuturePolicy"))
        assertEquals(AgentExecPolicies.ANY_MEMBER, store.policyOf("dsh"))
    }

    /** 空 agentKey 被拒（不能有一个叫空串的 agent）。 */
    @Test
    fun aBlankAgentKeyIsRejected() {
        val store = AgentExecPolicyStore(tempFile())
        assertFalse(store.set("", AgentExecPolicies.ANY_MEMBER))
        assertFalse(store.set("   ", AgentExecPolicies.ANY_MEMBER))
    }

    /**
     * **坏文件 ⇒ 全部最严**，不抛异常也不放宽。
     *
     * 三种坏法：不是 JSON、顶层不是对象、条目取值非法。
     */
    @Test
    fun aCorruptFileFallsBackToHostOnly() {
        val bad = tempFile()
        bad.writeText("这不是 JSON")
        assertEquals(AgentExecPolicies.LIST, AgentExecPolicyStore(bad).policyOf("dsh"))

        val notObject = tempFile()
        notObject.writeText("[1,2,3]")
        assertEquals(AgentExecPolicies.LIST, AgentExecPolicyStore(notObject).policyOf("dsh"))
    }

    /**
     * 条目取值非法 ⇒ **读出来是最严一档**。
     *
     * 注意这条断言测的是**最终结果**，不是某一层：存储层会丢掉非法条目，
     * `policyOf` 出口还会过一次 `normalize` —— 两处各挡一次（纵深防御）。
     * 所以把任一处的检查去掉，这条仍会绿；只有两处都破坏才变红。
     * 这是有意为之：这类「信任了外部文件」的错误一旦发生，代价是别人在你机器上跑命令。
     */
    @Test
    fun anIllegalEntryIsDroppedRatherThanTrusted() {
        val file = tempFile()
        file.writeText("""{"dsh":"anyMember","codex":"someFuturePolicy"}""")
        val store = AgentExecPolicyStore(file)
        assertEquals(AgentExecPolicies.ANY_MEMBER, store.policyOf("dsh"))
        assertEquals(AgentExecPolicies.LIST, store.policyOf("codex"))
    }

    /** clear 之后回到最严一档。 */
    @Test
    fun clearingAnAgentRestoresHostOnly() {
        val store = AgentExecPolicyStore(tempFile())
        store.set("dsh", AgentExecPolicies.ANY_MEMBER)
        assertTrue(store.clear("dsh"))
        assertEquals(AgentExecPolicies.LIST, store.policyOf("dsh"))
        assertFalse(store.clear("dsh"), "再清一次应当返回 false（本来就没有）")
    }
}
