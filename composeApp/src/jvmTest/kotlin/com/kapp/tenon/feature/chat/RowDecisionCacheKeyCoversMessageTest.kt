// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tenon.feature.chat

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **源码级守卫**：行判定缓存的键必须覆盖判定函数的**全部输入**。
 *
 * ## 为什么这条必须是源码级（真机缺陷 A）
 *
 * `ChatMessageList` 每一行都缓存 `resolveRow(message, …)` 的结果。缓存键曾经是
 * `(message.id, message.renderCard, message.text, visibleCardIds, unreadableText)` ——
 * 看着"把会变的东西都列上了"，但传输推进时变的恰恰是**没列上的那个字段**：
 * `message.file`（`state` / `done` / `speedBps`）。
 *
 * 后果是文件卡永久停在首帧：字节已经落盘、`accepted=true`、领域层发出去的状态就是
 * `Completed`，卡面却仍写「对方发来的文件，等你接收」，还挂着「接收/拒绝」两个按钮。
 * 退出会话再进来才对 —— 因为那时 `remember` 随组合一起重建。
 *
 * 同一条坑此前已经踩过一次（T-2d 单卡槽：rev 推进时缓存停在首帧，判定横幅与步骤推进
 * 都不上屏），当时的补法是往键里再加两个字段。**加字段治不了这一类**：只要判定函数
 * 读整条消息，键就必须是整条消息。
 *
 * 这条判据住在 Composable 里，单测进不去（要真跑 Compose 得拉起整个宿主）；能钉住它
 * 的最低成本事实是**源码**。与 `SessionDividerUsesTheSameSourceTest` 同一纪律。
 */
class RowDecisionCacheKeyCoversMessageTest {

    private fun source(): String {
        val candidates = listOf(
            File("src/commonMain/kotlin/com/kapp/tenon/feature/chat/ChatMessageList.kt"),
            File("composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/chat/ChatMessageList.kt"),
        )
        val hit = candidates.firstOrNull { it.isFile }
            ?: error("找不到 ChatMessageList.kt，candidates=" + candidates.map { it.absolutePath })
        return hit.readText()
    }

    @Test
    fun theRowDecisionIsCachedOnTheWholeMessage() {
        assertTrue(
            source().contains("remember(message, visibleCardIds, unreadableText)"),
            "行判定的缓存键必须是整条 message —— 少一个字段就会让文件卡/卡片停在首帧",
        )
    }

    @Test
    fun theCacheKeyDoesNotDriftBackToAFieldList() {
        val src = source()
        // 逐字钉住那条回归：按字段列键的写法（`message.id,` 打头的键列表）不许回来。
        assertFalse(
            src.contains("message.id, message.renderCard, message.text"),
            "缓存键不许退回「列几个字段」：判定函数读整条消息，键就得是整条消息",
        )
    }
}
