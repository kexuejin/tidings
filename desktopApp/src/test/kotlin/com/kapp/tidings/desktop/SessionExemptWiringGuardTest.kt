// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import com.kapp.tidings.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * L1 会话免审回路的**桌面装配守卫**：动作分支（编码复用）、台账落表、低危自动验讫
 * 三段必须同在 —— 摘掉任何一段，编译照绿，但「勾选免审」就退回逐次弹审批的假通路。
 * 剥掉注释再匹配（[GuardSourceScan] 的纪律），避免被解释这件事的注释绊倒。
 */
class SessionExemptWiringGuardTest {

    private val extension = "desktopApp/src/main/kotlin/com/kapp/tidings/desktop/DesktopExtension.kt"
    private val chain = "desktopApp/src/main/kotlin/com/kapp/tidings/desktop/DesktopStageChain.kt"

    private fun live(): String = GuardSourceScan.stripComments(GuardSourceScan.read(extension))

    @Test
    fun exemptActionBranchLandsLedgerGrant() {
        val source = live()
        assertTrue(
            source.contains("com.kapp.tidings.feature.chat.isSessionExemptAction(actionId)") &&
                source.contains("com.kapp.tidings.feature.chat.sessionExemptCardIdOf(actionId)"),
            "免审动作分支必须复用 composeApp 的单槽编码（isSessionExemptAction / sessionExemptCardIdOf）—— 两侧各写一份迟早漂",
        )
        assertTrue(
            source.contains("sessionExemptLedger?.grant("),
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