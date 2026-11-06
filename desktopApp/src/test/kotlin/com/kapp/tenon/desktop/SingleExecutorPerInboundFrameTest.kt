// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 一条群帧只许有一个执行者。
 *
 * 真机实测（2026-11-01 15:55）：手机发一句话，宿主起了**两个** worktree（`t-7`、`t-8`）。
 * 成因是入站层两条真执行的路径同时在跑 ——
 * 1. `agentIntent` → `handleInboundAgentIntent`（M4 派单执行，管授权/算力标签/跨端委派）；
 * 2. 文本路由 `route == Local` → `onAgentPrompt` → DSH 编排器。
 * 第 2 条是后接上的（编排器原先是个空 lambda），接上之后同一句话就双跑：双倍 token，
 * 而且编排器按 convId 只记最后一张工单 ⇒ 前一个任务从此刻起**停不掉**（真机上只能人工 kill）。
 *
 * 守卫锚的是"两段都执行"这个形状：文本路由那一段必须带 `intentOwnsDispatch` 的闸。
 */
class SingleExecutorPerInboundFrameTest {

    @Test
    fun textRouteIsGatedWhenIntentPathOwnsTheDispatch() {
        val source = listOf(
            File("../app/src/commonMain/kotlin/com/kapp/tenon/domain/RelayControllerMessagesComponent.kt"),
            File("app/src/commonMain/kotlin/com/kapp/tenon/domain/RelayControllerMessagesComponent.kt"),
        ).firstOrNull { it.isFile }
        assertTrue(source != null, "RelayControllerMessagesComponent.kt not found")
        val text = source.readText()

        assertTrue(
            text.contains("intentOwnsDispatch"),
            "入站层必须算一次「这条帧归谁执行」，否则 intent 与文本路由会各派一次单",
        )
        assertTrue(
            text.contains("route == AgentPromptRoute.Local && !intentOwnsDispatch"),
            "文本路由的派单必须被 intentOwnsDispatch 挡住（原来是无条件 dispatch）",
        )
        assertTrue(
            text.contains("host.agent.intentExecutorPresent()"),
            "判据必须取「本机有没有 invoke 执行器」，不是「有没有 intent」" +
                "（Phase 3 把 invoke 分片收进会话域的 ConversationAgentPort：调用点写作 host.agent.intentExecutorPresent()，" +
                "判据本身不变）",
        )
    }
}
