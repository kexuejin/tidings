// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guard tests verifying DesktopWorkerOrchestrator wiring and DSH core alignment.
 */
class DesktopWorkerOrchestratorTest {

    @Test
    fun extensionWiresWorkerOrchestratorToOnAgentPrompt() {
        val source = listOf(
            File("src/main/kotlin/com/kapp/tidings/desktop/DesktopExtension.kt"),
            File("desktopApp/src/main/kotlin/com/kapp/tidings/desktop/DesktopExtension.kt"),
        ).firstOrNull { it.isFile }
        assertTrue(source != null, "DesktopExtension.kt not found")
        val text = source.readText()

        assertTrue(
            text.contains("DesktopWorkerOrchestrator("),
            "DesktopWorkerOrchestrator not instantiated in DesktopExtension",
        )
        assertTrue(
            text.contains("workerOrchestrator.handlePrompt("),
            "onAgentPrompt not wired to workerOrchestrator.handlePrompt",
        )
    }

    @Test
    fun abortSignalsRecognizedAccurately() {
        // Source scan test for signal coverage and direct method check
        val source = listOf(
            File("src/main/kotlin/com/kapp/tidings/desktop/DesktopWorkerOrchestrator.kt"),
            File("desktopApp/src/main/kotlin/com/kapp/tidings/desktop/DesktopWorkerOrchestrator.kt"),
        ).firstOrNull { it.isFile }
        assertTrue(source != null, "DesktopWorkerOrchestrator.kt not found")
        val text = source.readText()

        assertTrue(text.contains("\"stop\""), "Missing stop signal keyword")
        assertTrue(text.contains("\"abort\""), "Missing abort signal keyword")
        assertTrue(text.contains("\"cancel\""), "Missing cancel signal keyword")
        assertTrue(text.contains("active.job.cancel"), "Must cancel active job upon abort signal")
        assertTrue(text.contains("Resumed: processing follow-up"), "Must resume blocked ticket upon follow-up")
    }

    @Test
    fun orchestratorUsesExistingBroadcastChainCard() {
        val source = listOf(
            File("src/main/kotlin/com/kapp/tidings/desktop/DesktopWorkerOrchestrator.kt"),
            File("desktopApp/src/main/kotlin/com/kapp/tidings/desktop/DesktopWorkerOrchestrator.kt"),
        ).firstOrNull { it.isFile }
        assertTrue(source != null, "DesktopWorkerOrchestrator.kt not found")
        val text = source.readText()

        assertTrue(
            text.contains("controller.broadcastChainCard("),
            "Worker card must be broadcast via existing broadcastChainCard channel",
        )
        assertTrue(
            text.contains("StageChain.parseStages(prompt)"),
            "Orchestrator must differentiate single-worker and multi-role chain via parseStages",
        )
    }

    @Test
    fun prototypeAndHealCapabilitiesRecognized() {
        val source = listOf(
            File("src/main/kotlin/com/kapp/tidings/desktop/DesktopWorkerOrchestrator.kt"),
            File("desktopApp/src/main/kotlin/com/kapp/tidings/desktop/DesktopWorkerOrchestrator.kt"),
        ).firstOrNull { it.isFile }
        assertTrue(source != null, "DesktopWorkerOrchestrator.kt not found")
        val text = source.readText()

        assertTrue(text.contains("isPrototypeIntent"), "Must support prototype intent detection")
        assertTrue(text.contains("followUpIfActive"), "Must support follow-up activation")
        assertTrue(text.contains("approvePrototype"), "Must support prototype approval")
        assertTrue(text.contains("maxHealRounds"), "Must support maxHealRounds self-healing loop")
    }

    /**
     * 派单落点**只能**来自群绑定的工作区。
     *
     * 真机事故（2026-11-01）：同一个群连发两条派单，一条落进 `~/e2e-weather-app`、
     * 一条落进 `~/weather-relay`。当时 `resolveWorkspacePath` 的第二段兜底是
     * 「本机第一个工作区」，而 `agentWorkspaces` 是 Map —— 顺序随哈希，于是同一份
     * 配置给出两个答案，且本端卡片、进度、补丁卡全都正常（错的是"改哪个仓"）。
     * 这条守卫锚的正是那个兜底：它不在，只剩"绑定 / 退单"两种结局。
     */
    @Test
    fun dispatchTargetComesOnlyFromGroupBinding() {
        val source = listOf(
            File("src/main/kotlin/com/kapp/tidings/desktop/DesktopWorkerOrchestrator.kt"),
            File("desktopApp/src/main/kotlin/com/kapp/tidings/desktop/DesktopWorkerOrchestrator.kt"),
        ).firstOrNull { it.isFile }
        assertTrue(source != null, "DesktopWorkerOrchestrator.kt not found")
        val text = source.readText()

        assertTrue(
            text.contains("workspaceOfGroup("),
            "派单落点必须由群绑定的工作区解析（RelayUiState.workspaceOfGroup）",
        )
        assertFalse(
            text.contains("firstOrNull { it.peerId == self }"),
            "不许再用「本机第一个工作区」兜底：Map 顺序不定，同一群会落到不同仓",
        )
    }
}
