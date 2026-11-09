// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 「发 `agent-session` 之前先问对端 caps」守卫（阶段 0 / C2 门控）。
 *
 * `NodeOutbound.sendAgentSession` 的 KDoc 写得很清楚：**它自己不查 caps**，调用方必须先问
 * `peerDeclares`，否则帧会被对端静默丢弃、本端按 ACK/重传策略一直重传到入队，表现为
 * 「对方离线」这个查不出来的假故障。
 *
 * 现状（HEAD 基线）：
 * - `RelayControllerAgentComponent`（app 域）—— 有 `peerDeclares(` 前置 ✓
 * - `ControlPlaneAgentRoutes`（agentNode 控制面）—— 每个路由先走 `resolveAgentTarget(`，
 *   内部查 `CAP_AGENT_SESSION` + `requiredCap` 并做 `grants.allows` 过滤 ✓
 * - `ControlPlaneHookRoutes` —— **未门控**（显式 `to` 直接返回；缺省只按 `agq1` 筛）
 * - `DesktopExtension` —— **未门控**（`agent-channel` transfer 的可见性宣告）
 *
 * 后两处是既有技术债，本守卫不要求立即修，只要求**不许再多**：数量超过基线即判红。
 * 本轮新增的状态发送必须落在已门控入口上。
 *
 * 反向验证：在 `DesktopExtension` 里再写一个 `sendAgentSession(`，本用例变红。
 */
class AgentSessionSendGateTest {

    private data class Gated(val path: String, val gate: String)

    /** 已门控入口：文件里必须真的出现门控符号，否则说明守卫路径过期或门控被删。 */
    private val gated = listOf(
        Gated(
            "app/src/commonMain/kotlin/com/kapp/tenon/domain/RelayControllerAgentComponent.kt",
            "peerDeclares(",
        ),
        Gated(
            "agentNode/src/main/kotlin/com/kapp/tenon/agent/ControlPlaneAgentRoutes.kt",
            "resolveAgentTarget(",
        ),
    )

    /** 既有未门控发送点：数量基线，只许降不许升。 */
    private val knownUngated = mapOf(
        "agentNode/src/main/kotlin/com/kapp/tenon/agent/ControlPlaneHookRoutes.kt" to 1,
        "orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopExtension.kt" to 1,
    )

    private val sendCall = Regex("""sendAgentSession\s*\(""")

    private fun live(path: String): String =
        GuardSourceScan.stripComments(GuardSourceScan.read(path))

    @Test
    fun gatedSendersKeepTheirGate() {
        for (entry in gated) {
            val source = live(entry.path)
            assertTrue(
                source.contains("sendAgentSession("),
                entry.path + " 已经不再发 agent-session —— 守卫路径过期了，请更新本用例而不是删掉它",
            )
            assertTrue(
                source.contains(entry.gate),
                entry.path + " 有 sendAgentSession 却缺少 " + entry.gate +
                    "：对端未声明 agn1 时帧会被静默丢弃并重传到入队，表现为假离线",
            )
        }
    }

    @Test
    fun ungatedSenderCountDoesNotGrow() {
        for ((path, baseline) in knownUngated) {
            val count = sendCall.findAll(live(path)).count()
            assertTrue(
                count <= baseline,
                path + " 的未门控 sendAgentSession 从 " + baseline + " 处增至 " + count +
                    " 处：新增发送点前必须先补 caps 前置（或改造既有入口），不许扩大技术债",
            )
        }
    }
}
