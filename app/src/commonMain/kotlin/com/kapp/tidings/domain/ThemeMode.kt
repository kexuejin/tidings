// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

/**
 * 「跟随系统 / 浅色 / 深色」三选一，以及它在设置里的 wire 形态。
 *
 * ## 为什么这属于领域侧，不属于主题层
 *
 * 它看起来像主题代码，但它的**归属**判据是"谁把它当事实"：
 * - `RelaySettings.themeMode` 存的就是这三个 wire 串（持久化事实）；
 * - `RelayController.updateThemeMode` 收到的也是 wire 串（协议/命令的事实）；
 * - 主题层只是**读**它来决定画哪一档颜色。
 *
 * 所以 wire 串与解析规则必须住在能持久化它的那一侧，否则"把设置存对"这件事
 * 会依赖 UI 层存在。真出过这类问题的形态见 `fromWire` 的兜底注释。
 *
 * ## 为什么单独一个枚举而不是 `darkTheme: Boolean`
 *
 * 布尔量表达不了「不表态」：要么跟随系统、要么强制某一档，而两个设置页签
 * （外观、状态栏图标亮度）都要求第三档存在。
 */
enum class ThemeMode {
    System,
    Light,
    Dark;

    /** 把枚举压成设置屏用的字符串（wire 形态）。 */
    val wire: String
        get() = when (this) {
            System -> THEME_MODE_SYSTEM
            Light -> THEME_MODE_LIGHT
            Dark -> THEME_MODE_DARK
        }

    /** [systemDark] 是系统的深色开关；只有 [System] 会去读它。 */
    fun resolve(systemDark: Boolean): Boolean = when (this) {
        System -> systemDark
        Light -> false
        Dark -> true
    }

    companion object {
        /**
         * 从 wire 串解析。
         *
         * 真收到不认识的值一律按 [System] 走 —— 设置项被改坏（手改文件、旧版本写进来的
         * 已废弃值）时退回系统主题，比停在某个半截状态安全。
         */
        fun fromWire(value: String): ThemeMode = when (value) {
            THEME_MODE_LIGHT -> Light
            THEME_MODE_DARK -> Dark
            else -> System
        }
    }
}

/**
 * [ThemeMode] 的三个 wire 取值。
 *
 * 这是**持久化/命令的格式**，不是界面文案：写进 `settings.json`、走过控制面。
 * 所以它跟着 [ThemeMode] 一起住在领域侧，界面侧的 `THEME_MODE_*` 只是转发这两个名字
 * （见 `feature/settings/SettingsPageChrome`），改动只能发生在这里。
 */
const val THEME_MODE_SYSTEM: String = "system"
const val THEME_MODE_LIGHT: String = "light"
const val THEME_MODE_DARK: String = "dark"
