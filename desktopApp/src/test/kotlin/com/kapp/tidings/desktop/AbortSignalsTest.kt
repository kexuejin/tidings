// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * §6.1 打断判据的回归。
 *
 * 真机上 FAIL 的那一条不是「关键词写错了」，而是「**带 @ 的打断**没人管」：
 * 裸 `停` 命中不了路由（不是 prompt）所以打断分支进不去；`@codex 停` 能进路由，
 * 但判据当时拿的是原文，`@codex 停` 不以 `停` 开头 ⇒ 也不命中。
 * 两头都漏，于是「怎么发都停不下来」。这两条现在都被钉住。
 */
class AbortSignalsTest {

    @Test
    fun bareKeywordsHit() {
        listOf("stop", "STOP", "abort", "cancel", "\u505c", "\u53d6\u6d88").forEach {
            assertTrue(AbortSignals.matches(it), "\"$it\" 必须命中")
        }
    }

    @Test
    fun keywordWithSuffixStillHits() {
        // 用户急着叫停时不会只打一个字；「停一下」「stop it」都算。
        listOf("\u505c\u4e00\u4e0b", "stop it", "cancel that", "\u53d6\u6d88\u4e86").forEach {
            assertTrue(AbortSignals.matches(it), "\"$it\" 必须命中")
        }
    }

    @Test
    fun mentionPrefixedAbortHits() {
        // 真机漏掉的那条：顺手 @ 了一下，就不该让打断失效。
        listOf("@codex-e2e \u505c", "@codex stop", "@a @b \u53d6\u6d88").forEach {
            assertTrue(AbortSignals.matches(it), "\"$it\" 必须命中")
        }
    }

    @Test
    fun normalInstructionsDoNotHit() {
        listOf(
            "fix city search: BEIJING returns NOT_FOUND",
            "\u5e2e\u6211\u4fee\u4e00\u4e0b\u57ce\u5e02\u641c\u7d22",
            "@codex-e2e fix city search",
            "",
            "   ",
        ).forEach {
            assertFalse(AbortSignals.matches(it), "\"$it\" 不该被当成打断")
        }
    }

    @Test
    fun stripMentionsLeavesOnlyUserText() {
        assertEquals("\u505c", AbortSignals.stripMentions("@codex-e2e \u505c"))
        assertEquals("\u505c", AbortSignals.stripMentions("  @a @b   \u505c  "))
        // 只有 @ 没有正文：返回空串（不是原文），否则它会被当成打断。
        assertEquals("", AbortSignals.stripMentions("@codex-e2e"))
    }

    @Test
    fun mentionOnlyMessageIsNotAbort() {
        assertFalse(AbortSignals.matches("@codex-e2e"), "只点了个名，不是打断")
    }

    /**
     * 「停」必须落到**进程**上，不能只翻卡片。
     *
     * 真机实测（2026-11-01 16:04）：卡片已经 `Aborted by user request`，而同一沙箱里的
     * `codex exec` 七分钟后仍在跑 —— 取消只发了协程取消，卡在阻塞读上的 CLI 未必响应。
     * 所以守卫锚的是"接线里真的有直杀那一步"，而不只是"关键词还在"。
     */
    @Test
    fun abortBridgeAlsoKillsSandboxProcesses() {
        val source = listOf(
            File("src/main/kotlin/com/kapp/tidings/desktop/DesktopExtension.kt"),
            File("desktopApp/src/main/kotlin/com/kapp/tidings/desktop/DesktopExtension.kt"),
        ).firstOrNull { it.isFile }
        assertTrue(source != null, "DesktopExtension.kt not found")
        val text = source.readText()

        assertTrue(
            text.contains("abortRunningSandboxes()"),
            "打断桥必须直接摘沙箱进程（abortRunningSandboxes），否则卡片翻了进程还活着",
        )
        assertTrue(
            text.contains("hasRunningSandboxes()"),
            "消费条件要看「本机有没有活在跑」，否则没派单时的「停」会被吞掉",
        )
    }
}
