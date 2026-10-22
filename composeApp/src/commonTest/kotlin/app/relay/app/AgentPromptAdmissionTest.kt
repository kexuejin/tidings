// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import app.relay.app.AgentPromptAdmission.Verdict
import app.relay.app.AgentPromptAdmission.permitsInjection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * 注入准入判据的测试。
 *
 * 这些断言的共同目的：**接通执行通道之后，不该被执行的话一句都进不去**。
 * 每一条都对应一种真实的越权路径，不是为了凑覆盖率。
 */
class AgentPromptAdmissionTest {

    private fun admit(
        addressed: Boolean = true,
        session: String? = "s-1",
        access: String? = "rw",
    ) = AgentPromptAdmission.admit(addressed, session, access)

    /** 正常路径：给 agent 的话 + 有会话 + 有读写权限 ⇒ 放行。 */
    @Test
    fun aProperlyAddressedPromptWithRwGrantIsAllowed() {
        assertEquals(Verdict.Allow, admit())
    }

    /**
     * **只读授权必须被拒。**
     *
     * `AgentGrantAccess.RO` 的 KDoc 逐字写着「不可下发任务或审批」。
     * 如果这里放行，等于「只给我看」的授权同时能让我在他机器上执行命令。
     */
    @Test
    fun aReadOnlyGrantCannotInject() {
        assertEquals(Verdict.ReadOnlyGrant, admit(access = "ro"))
        assertFalse(admit(access = "ro").permitsInjection())
    }

    /** 其余三档都含「下发任务」：rw / admin / owner。 */
    @Test
    fun rwAdminAndOwnerCanInject() {
        for (a in listOf("rw", "admin", "owner")) {
            assertEquals(Verdict.Allow, admit(access = a))
        }
    }

    /**
     * **没有任何授权记录 ⇒ 拒绝（fail-closed）。**
     *
     * 缺省放行是最常见的越权形态：新设备第一次连上时还没建授权记录，
     * 如果那时默认允许，攻击者只要连上就能执行。
     */
    @Test
    fun noGrantAtAllIsRejected() {
        assertEquals(Verdict.NoGrant, admit(access = null))
        assertEquals(Verdict.NoGrant, admit(access = "   "))
    }

    /**
     * **没带会话归属 ⇒ 拒投，不猜。**
     *
     * 猜一个「最近的会话」会让 agent 在无关上下文里执行 —— 比不执行更糟，
     * 因为用户看到的是「它动了」，却不知道动的是哪个项目的目录。
     */
    @Test
    fun aPromptWithoutSessionIsRejectedInsteadOfGuessed() {
        assertEquals(Verdict.NoSession, admit(session = null))
        assertEquals(Verdict.NoSession, admit(session = ""))
        assertEquals(Verdict.NoSession, admit(session = "  "))
    }

    /**
     * **消息本来不该叫醒 agent ⇒ 直接拒。**
     *
     * 判据来自 Tidings 侧 `shouldTriggerAgent`，这里只消费不重算。
     * 真机事故：一条不带 @ 的群消息拉起过 native 驱动。
     */
    @Test
    fun aMessageNotAddressedToTheAgentIsRejected() {
        assertEquals(Verdict.NotAddressedToAgent, admit(addressed = false))
    }

    /**
     * **判定顺序**：没 @ 且没权限时，报的是「没 @」而不是「没权限」。
     *
     * 顺序不是风格问题：报错原因指向错误的维度，排查时就会往错的方向挖。
     */
    @Test
    fun theFirstFailingDimensionIsTheOneReported() {
        assertEquals(
            Verdict.NotAddressedToAgent,
            admit(addressed = false, session = null, access = null),
        )
        assertEquals(Verdict.NoSession, admit(addressed = true, session = null, access = null))
    }

    /** 大小写与空白不该影响判定（线上取值可能带空格或大写）。 */
    @Test
    fun accessComparisonIsCaseAndWhitespaceInsensitive() {
        assertEquals(Verdict.Allow, admit(access = " RW "))
        assertEquals(Verdict.ReadOnlyGrant, admit(access = "RO"))
    }
    // ------------------------------------------------- 执行面：授权档必须显式

    /**
     * **`auto`（默认档）下不开放执行。**
     *
     * `auto` 的语义是「已添加的节点都**可见**」，不是「都可在本机执行」。
     * 配对门槛只是扫过码；把读取的默认档套到执行上，等于「扫过码就能在我机器上跑命令」。
     */
    @Test
    fun theDefaultAutoPolicyDoesNotPermitExecution() {
        assertEquals(
            Verdict.DefaultPolicyTooOpen,
            AgentPromptAdmission.admitForExecution("auto", Verdict.Allow),
        )
    }

    /** 显式档（byDevice / request）才放行执行。 */
    @Test
    fun explicitPoliciesPermitExecution() {
        for (policy in listOf("byDevice", "request")) {
            assertEquals(
                Verdict.Allow,
                AgentPromptAdmission.admitForExecution(policy, Verdict.Allow),
                "显式授权档 $policy 应放行",
            )
        }
    }

    /** 未知 / 缺省档一律不放行 —— **未知不等于宽松**。 */
    @Test
    fun unknownPolicyIsTreatedAsTooOpen() {
        for (policy in listOf(null, "", "   ", "someFuturePolicy")) {
            assertEquals(
                Verdict.DefaultPolicyTooOpen,
                AgentPromptAdmission.admitForExecution(policy, Verdict.Allow),
            )
        }
    }

    /** 第一层已经拒的，第二层原样透传（不要被"档位够"洗白）。 */
    @Test
    fun aRejectedVerdictIsNeverWashedByAPermissivePolicy() {
        for (v in listOf(
            Verdict.ReadOnlyGrant, Verdict.NoGrant,
            Verdict.NoSession, Verdict.NotAddressedToAgent,
        )) {
            assertEquals(v, AgentPromptAdmission.admitForExecution("byDevice", v))
        }
    }
}
