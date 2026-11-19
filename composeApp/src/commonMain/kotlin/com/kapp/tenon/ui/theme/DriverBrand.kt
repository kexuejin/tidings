// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 底座**厂商品牌色 + 回落名**（方案 §3.2/§3.9）。
 *
 * ## 这里是什么，不是什么
 *
 * **权威表在 `CliDriverSpecs`**（`:agent-contract`，UI 不许 import）。桌面壳把它投影成
 * 名册经 `LocalDriverRoster` 注入，UI 一律先读名册。本文件只负责两件名册给不了的事：
 *
 * 1. 品牌色（名册只有 vendor 字符串）；
 * 2. **名册缺失时的回落名**（手机壳 / 预览没有名册事实）。回落表**只有这一份** ——
 *    从前 `formatDriverName` 里还抄过一份 `when`，两份表必然漂（`Codex CLI` vs `Codex`）。
 *    回落也认不出的键**原样显示键本身**：键是事实，编一个名字不是。
 *
 * 品牌色只写在本文件，不进 feature 层；只用于小面积 tint（色块首字 / 12dp 圆点），
 * 底一律用 `accents.surface3`（随主题反色），所以深色模式不需要第二套品牌色。
 */
object DriverBrand {

    /** 品牌色表（§3.2 逐条）。语义色两档在 [tintOf] 里按主题解析。 */
    private val ANTHROPIC = Color(0xFFD97757)
    private val GOOGLE = Color(0xFF4285F4)
    private val DEEPSEEK = Color(0xFF4D6BFE)
    private val TENCENT = Color(0xFF0052D9)
    private val ZHIPU = Color(0xFF3859FF)

    /** 按 **driverKey** 反解 vendor。 */
    fun vendorOf(driverKey: String): String = when (driverKey.trim().lowercase()) {
        "codex" -> "openai"
        "claude" -> "anthropic"
        "agy" -> "google"
        "dsh" -> "deepseek"
        "codebuddy" -> "tencent"
        "zcode" -> "zhipu"
        "opencode" -> "opencode"
        else -> driverKey.trim().lowercase()
    }

    /**
     * 名册读不到时的**回落显示名**（唯一一份手写表，见类 KDoc）。
     * 认不出的键原样返回 —— 不 titlecase、不拼 `CLI` 后缀：那些都是编出来的。
     */
    fun displayNameOf(driverKey: String): String = when (driverKey.trim().lowercase()) {
        "codex" -> "Codex"
        "claude" -> "Claude Code"
        "agy" -> "Antigravity"
        "dsh" -> "DeepSeek Harness"
        "codebuddy" -> "CodeBuddy"
        "zcode" -> "ZCode"
        "opencode" -> "OpenCode"
        else -> driverKey.trim()
    }

    /** 行内副行用的**短名**（比 [displayNameOf] 短一档，如 `DeepSeek Harness` → `DeepSeek`）。 */
    fun shortNameOf(driverKey: String): String =
        displayNameOf(driverKey).substringBefore(' ')

    /** 按 **vendor** 取 tint（不按 driverKey —— 同厂商新增驱动自动复用同一档）。 */
    @Composable
    fun tintOf(vendor: String): Color = when (vendor.trim().lowercase()) {
        "openai" -> MaterialTheme.colorScheme.onSurface
        "anthropic" -> ANTHROPIC
        "google" -> GOOGLE
        "deepseek" -> DEEPSEEK
        "tencent" -> TENCENT
        "zhipu" -> ZHIPU
        "opencode" -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}
