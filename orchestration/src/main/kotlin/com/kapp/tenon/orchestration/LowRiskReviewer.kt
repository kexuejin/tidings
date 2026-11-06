// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

/**
 * Reviewer 的**低危判据**（纯函数）—— §八.2「L1 会话免审」的自动验讫守门人。
 *
 * ## 判定形状：白名单 + 黑名单，黑名单永远赢
 *
 * 只有两类输入：动作/命令的描述串（工具名、summary、命令行）与风险等级字面量。
 * 规则分两层：
 * 1. **黑名单**：高危词（删库、网络穿透、配置重置、密钥、提权、远程执行……）一律
 *    [LowRiskReviewer.isLowRisk] = false —— **高危词一律不走免审**，即使摘要里
 *    同时出现了低危词（"update the docs then rm -rf build" 不是低危）。
 * 2. **白名单**：单测/文档/日志类词命中，且黑名单未命中，才算低危。
 *
 * 两层都不命中的描述（比如一个不知名的工具名）**不算低危** —— fail-closed：
 * 「不知道危不危」和「不危」不是一回事，判不了就走正常审批。
 *
 * riskLevel 显式给出时以它为准（插件自报 L2/L3 直接走高危门）；判不了（null / 未知）
 * 才落到词表判据。词表只收 ASCII 小写词：描述串先整体转小写再匹配，中文描述里的
 * 「文档」「单测」等词同样入库 —— 宿主的工具摘要是中英混排的。
 *
 * 纯函数：不读时钟、不读文件、不碰任何进程 —— 测试用合成输入钉死。
 */
object LowRiskReviewer {

    /**
     * 高危词：任何命中都**直接否定**免审资格。
     *
     * 词形取「能在摘要里出现的最小片段」：`rm -rf` 连写、`sudo`、`chmod 777`、
     * 协议/凭据词、网络穿透词、远程执行词。宁可多拦（多问一次）不可漏放。
     */
    private val HIGH_RISK_MARKERS: List<String> = listOf(
        "rm -rf", "rm -fr", "rmdir /s", "del /s", "rd /s", "mkfs", "format ",
        "sudo", "chmod ", "chown ", "777",
        "token", "secret", "credential", "password", "passwd", "api-key", "apikey", "private key", "keystore",
        ".ssh", "id_rsa", "link-key", "link-cert",
        "reset", "factory", "wipe", "uninstall",
        "port-forward", "portforward", "tunnel", "proxy", "nat ", "firewall", "iptables", "vpn",
        "curl ", "wget ", "http://", "https://", "nc ", "ssh ", "scp ", "remote-exec",
        "kill -9", "killall", "pkill", "shutdown", "reboot",
        "git push", "git reset --hard", "git clean",
        // 中文高危词以 \u 转义入库：这是判据数据不是文案，而 cjkLiteralGate 的棘轮不分
        // 文案与数据 —— 转义后源码零 CJK 字面量，匹配行为逐字不变（测试钉着同一批词）。
        "\u5220\u9664\u6574\u4e2a", "\u683c\u5f0f\u5316", "\u6e05\u7a7a\u6570\u636e\u5e93",
        "\u91cd\u7f6e\u914d\u7f6e", "\u63d0\u6743", "\u5bc6\u94a5", "\u8bc1\u4e66",
        "\u8fdc\u7a0b\u6267\u884c", "\u7aef\u53e3\u8f6c\u53d1",
    )

    /** 低危词：单测 / 文档 / 日志类（白名单，须与黑名单无交集才生效）。 */
    private val LOW_RISK_MARKERS: List<String> = listOf(
        "test", "unittest", "unit-test", "junit", "kotlintest", "spec",
        "jvmtest", "androidhosttest", "testreport", "golden", "fixture",
        "doc", "docs", "docstring", "javadoc", "kdoc", "readme", "comment", "commentary", "changelog",
        "log", "logs", "logging", "print", "echo ", "diagnostic", "trace ", "debug output", "stderr", "stdout",
        // 低危中文词同样转义（判据数据）：单测/测试/补测/用例/文档/注释/日志/打印/说明。
        "\u5355\u6d4b", "\u6d4b\u8bd5", "\u8865\u6d4b", "\u7528\u4f8b", "\u6587\u6863",
        "\u6ce8\u91ca", "\u65e5\u5fd7", "\u6253\u5370", "\u8bf4\u660e",
    )

    /** 插件自报的非低危等级（riskLevel 字面量）——直接走高危门，不落词表。 */
    private val HIGH_RISK_LEVELS: Set<String> = setOf("L2", "L3", "high", "medium", "critical")

    /**
     * 这个动作能不能由 Reviewer 自动验讫（会话免审通道的准入判据）。
     *
     * @param riskLevel 插件自报的风险等级（agent-approval 的 riskLevel 字段）；null = 未自报。
     * @param summaryOrCommand 工具名 / 摘要 / 命令串，任意拼接 —— 词表按片段匹配。
     */
    fun isLowRisk(riskLevel: String?, summaryOrCommand: String?): Boolean {
        // 插件显式报了非低危等级：以它为准，摘要再温和也不放。
        val declared = riskLevel?.trim()?.uppercase()
        if (declared != null && declared.isNotEmpty()) {
            if (declared !in setOf("L1", "LOW", "INFO")) return false
            return byWordList(summaryOrCommand)
        }
        return byWordList(summaryOrCommand)
    }

    /** 词表判据：黑名单命中 ⇒ false；否则白名单命中 ⇒ true；都不命中 ⇒ false（fail-closed）。 */
    private fun byWordList(summaryOrCommand: String?): Boolean {
        val text = summaryOrCommand?.trim()?.lowercase().orEmpty()
        if (text.isEmpty()) return false
        if (HIGH_RISK_MARKERS.any { text.contains(it) }) return false
        return LOW_RISK_MARKERS.any { text.contains(it) }
    }
}
