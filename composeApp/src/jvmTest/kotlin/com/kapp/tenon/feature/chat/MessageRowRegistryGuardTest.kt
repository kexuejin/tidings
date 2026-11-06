// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.chat

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 行级判定只住在注册表里（轮 16 / WP3 回潮守卫）。
 *
 * 改造前 `ChatMessageList` 的六支 if/else 就是类型表，优先级靠书写顺序：
 * 系统行 > 屏幕历史卡 > 声明式卡（画 / 旧 rev 收起 / 退成文本） > 状态横幅 > 普通气泡。
 * 新增一种消息要往这串链子里插一支，漏插不报编译错，只是静默落进气泡那一支。
 *
 * 这里不 import 注册表类型，只扫源码形状 —— 这样「注册表还没写」时用例是
 * **断言红**而不是编译红，先红后绿的判据才看得见。
 */
class MessageRowRegistryGuardTest {

    private val registry =
        "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/chat/MessageRowRegistry.kt"

    private fun source(path: String): String? {
        val file = listOf(
            java.io.File(path),
            java.io.File(path.removePrefix("composeApp/").let { "src/commonMain/kotlin/" + it.substringAfter("kotlin/") }),
        ).firstOrNull { it.exists() } ?: return null
        return file.readText()
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .lineSequence()
            .map { it.substringBefore("//") }
            .joinToString("\n")
    }

    @Test
    fun registryExistsAndStaysComposeFree() {
        val text = source(registry)
        assertTrue(text != null, "缺少 MessageRowRegistry.kt：行级判定不能散在 ChatMessageList 的 if/else 里")
        assertTrue(!text!!.contains("androidx.compose"), "注册表是纯 Kotlin 判定层，不许依赖 Compose")
        assertTrue(!text.contains("stringResource("), "词条由 Compose 侧取好再注入，注册表不碰资源")
    }

    @Test
    fun adaptersCoverEveryKindInDeclaredPriority() {
        val text = source(registry).orEmpty()
        assertTrue(text.contains("ChatRowKinds.ordered"), "注册顺序必须由 ChatRowKinds.ordered 派生，不能另写一份顺序")
        for (kind in listOf("Notice", "Screen", "AgentCard", "AgentState", "Bubble")) {
            assertTrue(text.contains("ChatRowKind.$kind"), "注册表少了 $kind 的适配分支")
        }
    }

    @Test
    fun decisionCoversAllThreeCardOutcomes() {
        val text = source(registry).orEmpty()
        assertTrue(text.contains("CardRowOutcome.Render"), "卡片渲染态没有映射")
        assertTrue(text.contains("CardRowOutcome.Hidden"), "旧 rev 收起态没有映射")
        assertTrue(text.contains("CardRowOutcome.FallbackToText"), "退档成文本态没有映射")
        assertTrue(text.contains("Delegate"), "退档必须委托给 Bubble 渲染块，不能自己画第二份气泡")
    }
}
