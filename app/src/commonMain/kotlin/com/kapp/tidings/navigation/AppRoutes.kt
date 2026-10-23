// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.navigation

/**
 * 顶级页签的路由 id。
 *
 * 用常量而不是散落的字面量：`App.kt` 里要按 id 比较很多次，拼错一个字母的后果是
 * 「点进去是空白页」而不是编译错误。
 *
 * 聊天**不在**这里 —— 它是详情，不是平级目的地，理由见 `AppNavItems`。
 *
 * ## 为什么单独一个文件（而不是和 `AppNavItem` 同处）
 *
 * 这些是**纯字符串**、零 Compose 依赖，而 `AppNavItem` 带 `StringResource` /
 * `DrawableResource`（Compose Resources 的类型）。两者混在一个文件里会让
 * 「路由 id」这个可复用的领域事实被资源类型拖住：读它的地方（导航状态机、
 * 控制面深链）没有理由依赖 Compose。按符号切开之后，这一半可以独立搬走。
 */
object AppRoutes {
    const val CONVERSATIONS = "conversations"
    const val CABINET = "cabinet"
    const val NODES = "nodes"
    const val AGENTS = "agents"
    const val SETTINGS = "settings"
}
