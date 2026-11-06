// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 整表派生只做一次（轮 15 / WP2 回潮守卫）。
 *
 * 以前 `ChatMessageList` 对同一份 `messages` 挂了五个独立的 `remember(messages)`：
 * 时间线、会话线、id 索引、序号索引、可见卡片 rev。加一种「渲染前要扫全表」的
 * 判据时，很容易顺手再挂第六个 —— 而每个缓存各自失效、各自重算，长会话里
 * 就是每帧多扫一遍。注册表化之后这些判据统一由 `ChatListLayout` 提供。
 *
 * 这里数的是**调用形状**而不是行数：别名赋值不算扫描，只有 `remember(messages) {`
 * 才是。反向验证：在 ChatMessageList 里再加一个 `remember(messages) { … }`，本用例变红。
 */
class ChatListLayoutGuardTest {

    private val list = "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/chat/ChatMessageList.kt"

    private fun live(): String {
        val file = listOf(
            java.io.File(list),
            java.io.File("src/commonMain/kotlin/com/kapp/tenon/feature/chat/ChatMessageList.kt"),
        ).firstOrNull { it.exists() }
        checkNotNull(file) { "找不到 ChatMessageList.kt（cwd=" + java.io.File(".").absolutePath + "）" }
        // 去块注释与行注释，免得 KDoc 里抄的形状被当成真调用。
        return file.readText()
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .lineSequence()
            .map { it.substringBefore("//") }
            .joinToString("\n")
    }

    @Test
    fun wholeTableIsDerivedOnce() {
        val hits = Regex("""remember\(messages\)\s*\{""").findAll(live()).count()
        assertEquals(1, hits, "整表派生只能有一个 remember(messages)，多的那个请并进 chatListLayoutOf")
    }

    @Test
    fun derivationHasASingleNamedEntryPoint() {
        val source = live()
        assertTrue(source.contains("chatListLayoutOf(messages, ::localDayKey)"), "唯一派生入口不见了")
        assertTrue(source.contains("val layout = "), "派生结果要落成 layout 供行级注册表消费")
    }
}
