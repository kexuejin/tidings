// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.protocol

import com.kapp.tidings.core.protocol.AgentExecAuthorizer.permitsExecution
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **执行授权只有一个权威判据：`permitsExecution()`。**
 *
 * ## 真身
 *
 * `AgentExecAuthorizer` 用白名单表达「哪些档位可以真的执行」，它的 KDoc 明写：
 * 「集中一处，防止将来顺手再多放行一档」。而消费点（`AgentInboxEnricher`）一度自己写：
 *
 * ```
 * val allowed = verdict != Verdict.MemberOnly && verdict != Verdict.NotAMember
 * ```
 *
 * 那是**黑名单**：除了明确列出的两档，将来新加的任何档位都会被当成「可以执行」。
 * 两者当前答案相同（`Verdict` 只有六个值），语义却相反 —— 执行授权这条路上
 * 没有「碰巧对了」这种安全。
 *
 * 这条测试钉两件事：白名单的具体成员，以及「每一个档位的答案都被显式声明过」
 * （新增档位时忘记分类，这里会红）。
 */
class AgentExecAuthorizerVerdictsTest {

    /** 只有这三档能真的执行。多一档、少一档都要在这里体现。 */
    @Test
    fun onlyTheThreePermittingVerdictsAllowExecution() {
        assertTrue(AgentExecAuthorizer.Verdict.AgentHost.permitsExecution())
        assertTrue(AgentExecAuthorizer.Verdict.GrantedDevice.permitsExecution())
        assertTrue(AgentExecAuthorizer.Verdict.AnyMemberGranted.permitsExecution())
    }

    /** 这三档都不能执行 —— 尤其是群主/管理员，它们**没有结构性特权**。 */
    @Test
    fun theRemainingVerdictsAllDeny() {
        assertFalse(AgentExecAuthorizer.Verdict.MemberOnly.permitsExecution())
        assertFalse(AgentExecAuthorizer.Verdict.NotAMember.permitsExecution())
        assertFalse(
            AgentExecAuthorizer.Verdict.OwnerNeedsGrant.permitsExecution(),
            "群主不默认放行 —— 他建了群不等于他能命令我的机器",
        )
        assertFalse(
            AgentExecAuthorizer.Verdict.AdminNeedsGrant.permitsExecution(),
            "管理员同理，要进可信设备名单才放行",
        )
    }

    /**
     * **每一个枚举档位都必须被显式分类。**
     *
     * 新增一档却忘了想「它该不该放行」时，这条会红 —— 那正是「顺手多放行一档」的
     * 发生时刻。集合大小变化本身就是需要人看一眼的信号。
     */
    @Test
    fun everyVerdictIsClassified() {
        val all = AgentExecAuthorizer.Verdict.entries
        val permitting = all.filter { it.permitsExecution() }.toSet()
        val denying = all.filterNot { it.permitsExecution() }.toSet()
        assertTrue(
            permitting.size == 3,
            "放行的档位数量变了（当前 " + permitting + "）—— 确认这是有意的，再同步这条测试",
        )
        assertTrue(
            denying.size == 4,
            "拒绝的档位数量变了（当前 " + denying + "）—— 新增档位必须显式想一遍它该不该放行",
        )
        assertTrue(permitting.intersect(denying).isEmpty(), "一档不能既放行又拒绝")
    }

    /** 非群聊一律 fail-closed：用错地方时不会静默放行。 */
    @Test
    fun aNonGroupContextDeniesByDefault() {
        val verdict = AgentExecAuthorizer.authorize(
            senderId = "someone",
            group = null,
            grantAllows = { true }, // 即使台账说可以，非群上下文也不放行
            policy = AgentExecPolicies.ANY_MEMBER,
            selfNodeId = "self",
        )
        assertFalse(verdict.permitsExecution(), "非群聊不在本判据范围内，必须拒而不是放行")
    }
}
