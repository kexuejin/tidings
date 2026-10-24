// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 捎话 Tidings contributors

package com.kapp.tidings.feature.agents

import com.kapp.tidings.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 工作区看板**容器语言**的守卫：设备组头 / 工作区行必须是和会话行同一套「白卡 + 圆角 + 投影」，
 * 而分组层次靠**排版**，不靠再套一层盒子。
 *
 * 为什么只能源码级守：这两种偏差都只在渲染帧里看得见，而渲染帧只进截图基线（改基线是另一件事）。
 * 这里把"用的是哪套容器"钉在源码上：`LocalCardShape.current` / `relayCardShadow()` / 内边距 token
 * 一缺就红；设备组头出现尺寸字面量也红。
 *
 * 反向验证：`guardDetectsEachRemovedPiece` 在**内存里**逐条删掉对应那段再跑同一个判据，
 * 必须各报一次 —— 不为了验证守卫去改生产文件。
 */
class AgentBoardContainerGuardTest {

    private val board =
        "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/agents/AgentWorkspaceBoard.kt"

    /** 取 `marker` 起到下一个 `@Composable`（或文件尾）之间的活代码。 */
    private fun slice(text: String, marker: String): String {
        val start = text.indexOf(marker)
        if (start < 0) return ""
        val next = text.indexOf("@Composable", start + marker.length)
        return if (next < 0) text.substring(start) else text.substring(start, next)
    }

    /** 容器语言的判据（对"设备组头"与"Agent 层"两段分别成立）。 */
    private fun containerHoles(text: String): List<String> {
        val groupHead = slice(text, "private fun BoardGroupHead(")
        val agentHead = slice(text, "private fun AgentSectionHead(")
        val holes = mutableListOf<String>()
        if (groupHead.isEmpty()) holes += "锚点：BoardGroupHead"
        if (agentHead.isEmpty()) holes += "锚点：AgentSectionHead"
        // 设备组头 = 与会话行同一张卡
        listOf(
            "LocalCardShape.current" to "设备组头要用全局卡片圆角",
            "relayCardShadow()" to "设备组头要用全仓自绘投影（与会话行同一口径）",
            "padding(horizontal = sp.lg, vertical = sp.md)" to "内边距走 spacing token",
            "MaterialTheme.colorScheme.surface" to "卡片底色用 surface，不是裸页面底色",
        ).forEach { (needle, why) -> if (!groupHead.contains(needle)) holes += why + "（缺 " + needle + "）" }
        if (Regex("\\.dp\\b").containsMatchIn(groupHead)) {
            holes += "设备组头不许写尺寸字面量（.dp），一律走 token"
        }
        // Agent 层 = 排版层级，不是容器层级
        listOf("LocalCardShape.current", "relayCardShadow()").forEach { needle ->
            if (agentHead.contains(needle)) holes += "Agent 层再套卡就变成三层并列盒子（不该有 " + needle + "）"
        }
        if (!agentHead.contains("sp.lg")) holes += "Agent 层缩进要对齐卡内边距（sp.lg）"
        if (!agentHead.contains("MaterialTheme.typography.labelMedium")) holes += "Agent 身份键要留在 labelMedium 排版层级"
        // 工作区行 = 共享卡片组件，不许在板里重画
        if (!text.contains("InboxUnitRow(")) holes += "工作区行必须复用共享卡片组件"
        if (Regex("private fun BoardWorkspaceRow\\(").containsMatchIn(text)) holes += "板里不许另造工作区行组件"
        return holes
    }

    @Test
    fun boardUsesTheSameContainerLanguageAsTheRestOfTheApp() {
        val text = GuardSourceScan.stripComments(GuardSourceScan.read(board))
        val holes = containerHoles(text)
        assertTrue(holes.isEmpty(), holes.joinToString("；"))
    }

    /**
     * 判据必须能区分对错：逐条把对应的字删掉，`containerHoles` 必须各报一次。
     */
    @Test
    fun guardDetectsEachRemovedPiece() {
        val base = GuardSourceScan.stripComments(GuardSourceScan.read(board))
        val cases = listOf(
            "private fun BoardGroupHead(" to "LocalCardShape.current",
            "private fun BoardGroupHead(" to "relayCardShadow()",
            "private fun BoardGroupHead(" to "padding(horizontal = sp.lg, vertical = sp.md)",
            "private fun BoardGroupHead(" to "MaterialTheme.colorScheme.surface",
            "private fun AgentSectionHead(" to "sp.lg",
            "private fun AgentSectionHead(" to "MaterialTheme.typography.labelMedium",
        )
        cases.forEach { (marker, needle) ->
            val target = slice(base, marker)
            assertTrue(target.contains(needle), "夹具前提：这一段的源码里本来有 " + needle)
            val broken = base.replace(target, target.replace(needle, ""))
            assertFalse(
                containerHoles(broken).isEmpty(),
                "把 " + marker + " 里的 " + needle + " 删掉后判据没报 —— 它守不住这一条",
            )
        }
        // 反向：Agent 层加上卡片形状必须被报出来（这一条守卫的是"别套盒子"）。
        val agentHead = slice(base, "private fun AgentSectionHead(")
        val nested = base.replace(agentHead, agentHead.replace("Row(", "Row(modifier = Modifier.background(MaterialTheme.colorScheme.surface, LocalCardShape.current))"))
        assertTrue(
            containerHoles(nested).any { it.contains("Agent 层再套卡") },
            "Agent 层套上卡片形状时必须变红",
        )
    }
}
