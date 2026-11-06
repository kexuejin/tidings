// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.desktop

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * L1 会话免审回路的**桌面装配守卫**：动作分支（编码复用）、台账落表、低危自动验讫
 * 三段必须同在 —— 摘掉任何一段，编译照绿，但「勾选免审」就退回逐次弹审批的假通路。
 * 剥掉注释再匹配（[GuardSourceScan] 的纪律），避免被解释这件事的注释绊倒。
 */
class SessionExemptWiringGuardTest {

    private val extension = "orchestration/src/main/kotlin/com/kapp/tenon/orchestration"
    private val chain = "orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopStageChain.kt"

    // 免审动作分支住在 HostCardActions.kt（ARP-5 拆出）⇒ 判据读整包。
    private fun live(): String = GuardSourceScan.livePackageOf(extension)

    @Test
    fun exemptActionBranchLandsLedgerGrant() {
        val source = live()
        assertTrue(
            source.contains("com.kapp.tenon.domain.isSessionExemptAction(actionId)") &&
                source.contains("com.kapp.tenon.domain.sessionExemptCardIdOf(actionId)"),
            "免审动作分支必须复用 :app 的单槽编码（isSessionExemptAction / sessionExemptCardIdOf）—— 两侧各写一份迟早漂",
        )
        // 接收者不钉写法：台账随挂载态走取值器（ARP-5 之后是 `exempts()?.grant(`），
        // 但「落表」这个事实必须还在 —— 且必须带命名的 sessionKey（空键等于全放行）。
        assertTrue(
            Regex("""\.grant\(\s*sessionKey\s*=""").containsMatchIn(source),
            "免审分支必须把 (会话键, agentKey, 过期) 落进 SessionExemptLedger —— 不落表就是假通路",
        )
        assertTrue(
            source.contains("sessionExemptLedger = null"),
            "台账必须随 detach 清空 —— 与授权三桥同生命周期，旧运行时的表不该被新运行时读到",
        )
    }

    @Test
    fun lowRiskReviewerGateIsInPlace() {
        // 免审查表器装在 DesktopExtension（链派发入参），低危判据消费在 DesktopStageChain
        // （review_ready 自动验讫）：两段分居两个文件，摘掉任何一段类型系统不报 —— 源码钉。
        assertTrue(
            live().contains("sessionExemptOf = { sid -> sessionExempts.isExempt("),
            "链派发必须把免审台账查表器接进 DesktopStageChain —— 不接就是假免审",
        )
        val chainSource = GuardSourceScan.stripComments(GuardSourceScan.read(chain))
        assertTrue(
            chainSource.contains("LowRiskReviewer.isLowRisk(") &&
                chainSource.contains("sessionExemptOf?.invoke("),
            "自动验讫必须同时过两道门：低危判据（LowRiskReviewer）与会话免审查表 —— 缺一道就等于全免审",
        )
    }
}