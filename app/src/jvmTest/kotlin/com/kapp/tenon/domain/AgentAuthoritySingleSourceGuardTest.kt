// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 「审批权威判据只有一份」守卫（Phase 4）。
 *
 * 客户端曾经有四份手抄的审批判据，其中看板那份写错成「只挡 RO」，于是同一个人在看板能点批准、
 * 在会话详情却是只读。现在判据本体只有 `:core` 的 `mayDecideAgentApproval` /
 * `canApproveAgentRequest` 一份；切片投影只允许**取输入并转交**，不许就地展开真值表。
 *
 * 判据：投影文件必须真的调用这两个核心判据；投影与旧入口文件里都不许出现手写的权限比较。
 * 反向验证：在 AgentProjections.kt 里写 `val allow = grant?.access != "ro"`，本用例变红。
 */
class AgentAuthoritySingleSourceGuardTest {

    private val projections = "app/src/commonMain/kotlin/com/kapp/tenon/domain/AgentProjections.kt"
    private val legacy = "app/src/commonMain/kotlin/com/kapp/tenon/domain/AgentApprovalAuthority.kt"

    private fun live(path: String): String = GuardSourceScan.stripComments(GuardSourceScan.read(path))

    @Test
    fun projectionsDelegateToTheSingleCoreJudge() {
        val source = live(projections)
        assertTrue(source.contains("mayDecideAgentApproval("), "mayDecideApproval 投影必须转交 :core 的同一个判据")
        assertTrue(source.contains("canApproveAgentRequest("), "canApproveAgentWork 投影必须转交 :core 的同一个判据")
    }

    @Test
    fun noInlinedAccessTableAnywhere() {
        val forbidden = listOf("AgentGrantAccess.RO", "AgentGrantAccess.RW", "AgentGrantAccess.ADMIN", "access !=", "access ==", "delegatedApproval &&")
        for (path in listOf(projections, legacy)) {
            val source = live(path)
            for (token in forbidden) {
                assertTrue(
                    !source.contains(token),
                    path + " 出现 \"" + token + "\"：审批判据必须只有 :core 一份，就地展开真值表会让四处 UI 再次各说各话",
                )
            }
        }
    }
}
