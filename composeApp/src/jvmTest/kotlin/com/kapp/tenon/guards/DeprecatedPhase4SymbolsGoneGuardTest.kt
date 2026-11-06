// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.guards

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 4.5 收尾守卫 A：旧聚合符号**彻底退场**。
 *
 * 17 个 @Deprecated 入口与 helpers 已删除；编译器挡得住直接调用，但挡不住有人重新
 * 写同名扩展或再按成员访问。本守卫只看活代码里的成员访问形态。
 *
 * 反向验证：在任一生产文件里写 `state.conversations`，本用例变红。
 */
class DeprecatedPhase4SymbolsGoneGuardTest {

    private val roots = listOf(
        "app/src/commonMain/kotlin",
        "composeApp/src/commonMain/kotlin",
        "desktopApp/src/main/kotlin",
    )

    private val forbidden = listOf(

        ".inboxWorkspaceUnits(",


        ".messagesWith(",
        ".visibleAgentSessionsOf(",
        ".agentEventsOf(",
        ".agentRunsOf(",
        ".agentArtifactsOf(",
        ".grantStatusOf(",
        ".mayDecideApproval(",
        ".canApproveAgentWork(",

    )

    @Test
    fun legacyAggregateMembersAreGone() {
        val offenders = mutableListOf<String>()
        for (path in GuardSourceScan.kotlinFiles(*roots.toTypedArray())) {
            val live = GuardSourceScan.stripComments(GuardSourceScan.read(path))
            for (token in forbidden) {
                if (live.contains(token)) offenders += path + " -> " + token
            }
        }
        assertTrue(offenders.isEmpty(), "旧聚合入口已删除，生产代码不许再按成员访问： " + offenders.joinToString(" | "))
    }

    @Test
    fun noPhase4CleanupDeprecationRemains() {
        val offenders = GuardSourceScan.kotlinFiles(*roots.toTypedArray()).filter {
            GuardSourceScan.read(it).contains("Phase 4 cleanup")
        }
        assertTrue(offenders.isEmpty(), "仍有 Phase 4 cleanup 的 @Deprecated 声明：" + offenders.joinToString(" | "))
    }
}
