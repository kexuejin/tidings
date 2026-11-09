// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 追问桥的**作用域守卫**（真机现场）。
 *
 * 1:1 的「两路不双派」口径（App.kt 装配处）：药丸 invoke 帧执行、文本帧留档。
 * 追问桥（`onAgentFollowUp` → `followUpIfActive`）若在 1:1 也生效，同一句追问
 * 会被消费两次：文本路出方向确认卡、invoke 路走 `handlePromptAwait` —— 后者
 * 补齐 prototype 分支后两路都出卡、都存 `pendingPrototypeInputs`。
 *
 * 所以入站文本上的追问桥必须**群作用域**：`groupId != null` 前置。
 */
class AgentFollowUpScopeGuardTest {

    private val source: String = GuardSourceScan.stripComments(
        GuardSourceScan.read(
            "app/src/commonMain/kotlin/com/kapp/tenon/domain/RelayControllerMessagesComponent.kt",
        ),
    )

    @Test
    fun theFollowUpBridgeIsGroupScopedOnInboundText() {
        val callAt = source.indexOf("host.onAgentFollowUp?.invoke")
        assertTrue(callAt >= 0, "入站文本必须保留追问桥调用（群路径的追问/微调靠它）")
        // 调用点所在行必须带群前置：1:1 文本只是留档，追问归 invoke 通路。
        val lineStart = source.lastIndexOf('\n', callAt).let { if (it < 0) 0 else it + 1 }
        val line = source.substring(lineStart, source.indexOf('\n', callAt).let { if (it < 0) source.length else it })
        assertTrue(
            line.contains("groupId != null"),
            "onAgentFollowUp 必须群作用域（groupId != null 前置）——1:1 文本帧再触发追问就是双派",
        )
    }
}
