// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 桌面侧透传守卫：Reviewer 审计结论（checks[]）必须真的能从链执行体流进广播帧。
 *
 * 与 DesktopStageChainTest 同款源码守卫手法 —— 这一层不碰真 git / 真 CLI，
 * 接线缺失的失效形态（判据在、透传不在）只能靠源码级断言抓。
 */
class DesktopStageChainAuditTest {

    private fun stageChainSource(): String {
        val f = listOf(
            File("../orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopStageChain.kt"),
            File("orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopStageChain.kt"),
        ).firstOrNull { it.isFile }
        assertTrue(f != null, "找不到 DesktopStageChain.kt")
        return f!!.readText()
    }

    @Test
    fun thePublishSlotCarriesAuditChecksOntoTheCard() {
        val text = stageChainSource()
        assertTrue(text.contains("pendingChecks"), "缺少审计结论暂存位（checks 透传的载体）")
        assertTrue(text.contains("checks = pendingChecks"), "publish 的 ChainProgress 必须带 checks")
        // 一卡一事实：非合流卡不得携带上一张卡的审计项。
        assertTrue(text.contains("pendingChecks = emptyList()"), "发出后必须清空，防止审计项污染后续卡")
    }

    @Test
    fun theReportCallbackStoresProgressChecksBeforePublishing() {
        val text = stageChainSource()
        assertTrue(text.contains("pendingChecks = progress.checks"),
            "report 回调要在 publish 前把 progress.checks 存进透传位")
    }
}
