// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Reviewer 低危判据（纯函数）：白名单 + 黑名单，黑名单永远赢。
 * 每条正向判据都有反向验证位 —— 摘掉对应实现行必须变红（AGENTS §4）。
 */
class LowRiskReviewerTest {

    // ── 白名单：单测 / 文档 / 日志类 ──────────────────────────────────────

    @Test
    fun unitTestWorkIsLowRisk() {
        assertTrue(LowRiskReviewer.isLowRisk(null, "./gradlew :composeApp:jvmTest"))
        assertTrue(LowRiskReviewer.isLowRisk(null, "run unit tests for the parser module"))
        assertTrue(LowRiskReviewer.isLowRisk(null, "kotlintest 边界用例补两条"))
        // 风险等级自报 L1 与词表一致。
        assertTrue(LowRiskReviewer.isLowRisk("L1", "gradle test --tests AgentCardPolicyTest"))
    }

    @Test
    fun documentationWorkIsLowRisk() {
        assertTrue(LowRiskReviewer.isLowRisk(null, "update README to document the new flag"))
        assertTrue(LowRiskReviewer.isLowRisk(null, "修正 KDoc 里的参数说明"))
        assertTrue(LowRiskReviewer.isLowRisk(null, "docs: refresh changelog"))
    }

    @Test
    fun logOnlyWorkIsLowRisk() {
        assertTrue(LowRiskReviewer.isLowRisk(null, "add a debug log line in the retry loop"))
        assertTrue(LowRiskReviewer.isLowRisk(null, "打印一行诊断日志到 stderr"))
    }

    // ── 黑名单：高危词一律不走免审 ────────────────────────────────────────

    @Test
    fun destructiveCommandIsNeverLowRiskEvenWithDocWords() {
        // 摘要里同时出现低危词与高危词：黑名单赢 —— "update the docs then rm -rf build" 不是低危。
        assertFalse(LowRiskReviewer.isLowRisk(null, "update the docs then rm -rf build"))
        assertFalse(LowRiskReviewer.isLowRisk(null, "单测前先 sudo chmod 777 输出目录"))
    }

    @Test
    fun credentialAndNetworkWordsAreNeverLowRisk() {
        assertFalse(LowRiskReviewer.isLowRisk(null, "read the keystore token"))
        assertFalse(LowRiskReviewer.isLowRisk(null, "curl https://example.com/upload"))
        assertFalse(LowRiskReviewer.isLowRisk(null, "set up port-forward tunnel for debugging"))
        assertFalse(LowRiskReviewer.isLowRisk(null, "把密钥写进配置"))
    }

    @Test
    fun unknownToolOrEmptyDescriptionIsNotLowRisk() {
        // fail-closed：「不知道危不危」和「不危」不是一回事 —— 白名单不命中就走审批。
        assertFalse(LowRiskReviewer.isLowRisk(null, "deploy-to-store"))
        assertFalse(LowRiskReviewer.isLowRisk(null, ""))
        assertFalse(LowRiskReviewer.isLowRisk(null, null))
        assertFalse(LowRiskReviewer.isLowRisk(null, "   "))
    }

    @Test
    fun declaredRiskLevelOverridesWordList() {
        // 插件自报 L2/L3：摘要再温和也走高危门。
        assertFalse(LowRiskReviewer.isLowRisk("L2", "update docs"))
        assertFalse(LowRiskReviewer.isLowRisk("L3", "add a unit test"))
        assertFalse(LowRiskReviewer.isLowRisk("high", "log a line"))
        // 自报低危但内容高危：黑名单仍然赢。
        assertFalse(LowRiskReviewer.isLowRisk("L1", "rm -rf /"))
    }
}