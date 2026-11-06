// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 卡片**动作标签**的文案守卫：动作 JSON 会随群帧广播给所有端，`label` 一旦写成
 * 中文字面量，接收端的语言就再也翻不回去。动作标签必须给 `labelKey`（端上按
 * `CardActionLabelKeys` 翻译，旧端回退 label），或者干脆只留 actionId 让端上兜底。
 *
 * 边界说明：本守卫只收动作标签。卡片里的 `title`/`text`/`summary` 由各 builder
 * 自己的措辞来源负责 —— 旧驱动（`SandboxedCliDriver` 的进度/结论卡）仍有四处
 * 中文标题，那需要一个 titleKey 级的契约（不在本轮 `labelKey` 的范围内），
 * 留着它们会让这条守卫永远红；把范围钉在动作标签上，守卫才能真的判红。
 *
 * 判据先剥注释（AGENTS §4：守卫别被自己的注释绊倒），并断言扫到的 `put("label"`
 * 行数下限 —— 守卫自己空转的话，永远不会红。
 */
class CardPayloadCopyGuardTest {

    private val builders = listOf(
        "agentNode/src/main/kotlin/com/kapp/tenon/agent/StageChain.kt",
        "agentNode/src/main/kotlin/com/kapp/tenon/agent/drivers/SandboxedCliDriver.kt",
        "orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopWorkerOrchestrator.kt",
        "orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopWorkerWatchdog.kt",
        "orchestration/src/main/kotlin/com/kapp/tenon/orchestration/BoardCardFlip.kt",
        "orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopStageChain.kt",
        "app/src/commonMain/kotlin/com/kapp/tenon/domain/RelayControllerAgentInvokeComponent.kt",
    )

    /** 与 cjk-literal 门禁同一族区段：汉字、全角标点、假名/谚文都算「文案」。 */
    private val cjk = Regex("[\u3000-\u303F\u3040-\u30FF\u3400-\u4DBF\u4E00-\u9FFF\uAC00-\uD7AF\uFF00-\uFFEF]")

    @Test
    fun cardPayloadBuildersCarryNoHardcodedCopy() {
        var inspectedPutLines = 0
        val violations = mutableListOf<String>()
        builders.forEach { path ->
            val code = GuardSourceScan.stripComments(GuardSourceScan.read(path))
            code.lineSequence().forEachIndexed { index, line ->
                if (!line.contains("put(\"label\"")) return@forEachIndexed
                inspectedPutLines++
                if (cjk.containsMatchIn(line)) violations += path + ":" + (index + 1) + ": " + line.trim()
            }
        }
        assertTrue(inspectedPutLines >= 15, "一条动作标签 put(...) 都没扫到 —— 守卫在空转")
        assertTrue(
            violations.isEmpty(),
            "卡片动作标签里出现硬编码文案（改用 labelKey）：\n" +
                violations.joinToString("\n"),
        )
    }
}
