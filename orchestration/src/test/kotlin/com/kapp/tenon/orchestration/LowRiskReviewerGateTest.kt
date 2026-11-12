// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [LowRiskReviewer] 的**免审准入不变量**测试。
 *
 * 这里钉的是安全边界，不是词表内容（词表内容改了用例跟着改是预期的）：
 * 1. **黑名单恒赢** —— 摘要同时命中高危词与低危词时永远不算低危
 *    （演化规范 §8.2 的「负例免审诱导」：诱导模型把高危动作包装成低危摘要）；
 * 2. **fail-closed** —— 两层判据都不命中、摘要为空、riskLevel 不认识，一律不算低危：
 *    「不知道危不危」和「不危」不是一回事；
 * 3. **riskLevel 门在词表之前** —— 插件自报非低危等级时摘要再温和也不放；
 * 4. **大小写与中文同源** —— 摘要统一小写匹配；中文高危词以 \u 转义入库，
 *    这里用真中文明文钉住「转义数据真的能匹配中文摘要」。
 *
 * 纯函数 + 合成输入：不读时钟、不碰文件、不碰网络。
 */
class LowRiskReviewerGateTest {

    // ---------- 黑名单恒赢 ----------

    @Test
    fun highRiskMarkersWinOverLowRiskWords() {
        assertFalse(
            LowRiskReviewer.isLowRisk("L1", "update the docs then rm -rf build"),
            "同时命中低危词（docs）与高危词（rm -rf）必须按高危处理",
        )
        assertFalse(
            LowRiskReviewer.isLowRisk("L1", "log the rotation of api-key material"),
            "凭据词命中时低危词（log）不可放行",
        )
        assertFalse(
            LowRiskReviewer.isLowRisk("L1", "write a test that runs git push --force"),
            "高危词（git push）藏在测试语境里同样要拦",
        )
    }

    // ---------- 白名单语义 + fail-closed ----------

    @Test
    fun onlyWhitelistedWordsQualifyAsLowRisk() {
        assertTrue(LowRiskReviewer.isLowRisk("L1", "add a unit test for the login flow"), "单测词应放行")
        assertTrue(LowRiskReviewer.isLowRisk("L1", "update docs and changelog"), "文档词应放行")
        assertTrue(LowRiskReviewer.isLowRisk("L1", "print diagnostic output to stdout"), "日志词应放行")
    }

    @Test
    fun unlistedBenignSummariesStayClosed() {
        assertFalse(
            LowRiskReviewer.isLowRisk("L1", "reorganize the toolbar buttons"),
            "良性但不在白名单的动作必须保持 fail-closed —— 判不了就走正常审批",
        )
        assertFalse(
            LowRiskReviewer.isLowRisk(null, "mystery-tool --do-something"),
            "未知工具名（riskLevel 也未自报）不许免审",
        )
    }

    @Test
    fun emptyOrBlankSummariesNeverPass() {
        for (level in listOf<String?>(null, "L1", "INFO", "")) {
            assertFalse(LowRiskReviewer.isLowRisk(level, null), "null 摘要 fail-closed（level=" + level + "）")
            assertFalse(LowRiskReviewer.isLowRisk(level, "   "), "空白摘要 fail-closed（level=" + level + "）")
            assertFalse(LowRiskReviewer.isLowRisk(level, ""), "空摘要 fail-closed（level=" + level + "）")
        }
    }

    // ---------- riskLevel 门在词表之前 ----------

    @Test
    fun declaredLowRiskLevelsFallThroughToWordList() {
        val summary = "update docs"
        for (low in listOf("L1", "LOW", "INFO", "l1", " info ")) {
            assertTrue(
                LowRiskReviewer.isLowRisk(low, summary),
                "自报低危等级（" + low + "）应按词表判据放行（trim + 大小写归一）",
            )
        }
        // 低危等级 + 不在白名单的摘要：仍然不放
        assertFalse(
            LowRiskReviewer.isLowRisk("L1", "reboot the machine"),
            "等级说了不算 —— 摘要命中高危词照拦",
        )
    }

    @Test
    fun declaredNonLowRiskLevelsAreRejectedRegardlessOfSummary() {
        val summary = "update docs"
        for (high in listOf("L2", "L3", "high", "medium", "critical", "HIGH", "UNKNOWN-LX")) {
            assertFalse(
                LowRiskReviewer.isLowRisk(high, summary),
                "自报非低危等级（" + high + "）时摘要再温和也不许放行",
            )
        }
        // 空串等级 = 没有有效自报，落回词表判据（对照：docs 在白名单）
        assertTrue(
            LowRiskReviewer.isLowRisk("", summary),
            "空串 riskLevel 视为未自报，按词表判据处理",
        )
    }

    // ---------- 大小写归一与中文词同源 ----------

    @Test
    fun summariesAreLowercasedBeforeMatching() {
        assertTrue(LowRiskReviewer.isLowRisk("L1", "Update DOCS and README"), "摘要统一转小写后匹配白名单")
        assertFalse(
            LowRiskReviewer.isLowRisk("L1", "rotate the API-KEY"),
            "大写形态的高危词同样命中（词表匹配不区分大小写）",
        )
    }

    @Test
    fun chineseMarkersMatchPlainChineseSummaries() {
        // 主源里中文词以 \u 转义入库（判据数据不是文案）——这里用明文钉住匹配行为
        assertFalse(
            LowRiskReviewer.isLowRisk(null, "删除整个数据库目录"),
            "中文高危词（删除整个）必须拦下中文摘要",
        )
        assertFalse(LowRiskReviewer.isLowRisk(null, "格式化系统盘"), "中文高危词（格式化）必须拦下")
        assertFalse(LowRiskReviewer.isLowRisk(null, "重置配置为默认值"), "中文高危词（重置配置）必须拦下")
        assertTrue(LowRiskReviewer.isLowRisk(null, "补充登录模块的单测用例"), "中文低危词（单测/用例）应放行")
        assertTrue(LowRiskReviewer.isLowRisk(null, "更新说明文档"), "中文低危词（说明/文档）应放行")
    }
}
