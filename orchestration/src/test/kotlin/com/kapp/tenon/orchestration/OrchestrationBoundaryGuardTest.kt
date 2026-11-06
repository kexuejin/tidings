// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 编排层的**边界守卫**（下沉第二步的验收）：它必须零 UI，壳只负责装配。
 *
 * 这些判据都只能写在源码上：
 * - 构建脚本里一旦出现 composeApp / Compose 依赖，类型系统不会报错，直到手机上
 *   被迫拖进一整套 UI 栈；
 * - 「诊断走注入端口」「文案走 RelayUiTexts」是接线纪律，摘掉之后业务照样跑，
 *   只是实时缓冲 / 兜底文案静默消失。
 *
 * 判据先剥注释再匹配（AGENTS §4：守卫别被自己的注释绊倒）。
 */
class OrchestrationBoundaryGuardTest {

    private val build = "orchestration/build.gradle.kts"

    /**
     * 编排层的主源码包。
     *
     * **判据读整包，不读某一个文件**：这一族守的是「这个模块零 UI / 文案只走桥」，
     * 而代码会按职责拆文件（轮 8 / ARP-5 把 `DesktopExtension.kt` 拆成装配 + 转发 + 文案
     * 三半）。只盯一个文件的话，搬走一块就等于**把守卫挪出了一个缺口**：读的还是老文件、
     * 断言全在剩下的代码上通过。
     */
    private val packageDir = "orchestration/src/main/kotlin/com/kapp/tenon/orchestration"

    private fun live(path: String): String = GuardSourceScan.stripComments(GuardSourceScan.read(path))

    /** 包内全部 `.kt` 的活代码（剥注释）拼接；目录读不到就报错，不许在空集合上恒真。 */
    private fun livePackage(): String {
        val files = GuardSourceScan.kotlinFiles(packageDir)
        assertTrue(files.size >= 3, "编排包只解析出 " + files.size + " 个 .kt —— 路径过期了，守卫会空转")
        return files.joinToString("\n") { live(it) }
    }

    @Test
    fun moduleDoesNotDependOnUiOrDesktopShell() {
        val depLines = GuardSourceScan.read(build).lineSequence()
            .map { it.trim() }
            .filter {
                it.startsWith("implementation(") || it.startsWith("api(") || it.startsWith("testImplementation(")
            }
            .toList()
        assertTrue(depLines.isNotEmpty(), "构建脚本里一条依赖都没解析出来 —— 这条守卫在空转")
        depLines.forEach { line ->
            listOf(":composeApp", ":desktopApp", "compose", "ktor").forEach { banned ->
                assertFalse(
                    line.contains(banned),
                    "编排层依赖里出现了 UI / 桌面壳 / 网络栈（" + line + "）—— 它必须零 UI，壳只装配",
                )
            }
        }
    }

    @Test
    fun extensionDoesNotReachIntoTheUiLayer() {
        val code = livePackage()
        listOf(
            "org.jetbrains.compose",
            "com.kapp.tenon.feature.",
            "com.kapp.tenon.ui.",
            "com.kapp.tenon.generated.resources",
        ).forEach { banned ->
            assertFalse(
                code.contains(banned),
                "编排层仍在直接引用 UI 层（" + banned + "）—— 应改为端口注入 / RelayUiTexts 桥",
            )
        }
    }

    @Test
    fun diagnosticsGoThroughTheInjectedPort() {
        val code = livePackage()
        assertTrue(code.contains("liveEventSink?.invoke("), "实时诊断必须走注入端口，而不是直连 UI 的环形缓冲")
        assertFalse(code.contains("AgentLiveLog"), "编排层不许直接持有 UI 侧的诊断缓冲")
        // 端口语义要写在 KDoc 里：null 是正常形态（测试 / 无壳装配），不是失败。
        assertTrue(
            GuardSourceScan.kotlinFiles(packageDir).joinToString("\n") { GuardSourceScan.read(it) }
                .contains("null = 静默无诊断"),
            "liveEventSink 的 KDoc 必须写明 null 语义（测试 / 无壳装配时静默，不影响业务）",
        )
    }

    @Test
    fun grantDecisionLivesHereNotInTheShell() {
        val decision = "orchestration/src/main/kotlin/com/kapp/tenon/orchestration/GrantDecision.kt"
        assertTrue(
            GuardSourceScan.read(decision).contains("data class GrantDecision("),
            "授权决定模型必须住在编排层（它是编排与壳之间的交接模型）",
        )
        val panel = "desktopApp/src/main/kotlin/com/kapp/tenon/desktop/DesktopTrustedDevicesPanel.kt"
        val panelCode = live(panel)
        assertFalse(
            panelCode.contains("data class GrantDecision("),
            "壳里不许再定义 GrantDecision —— 两份定义迟早分叉",
        )
        assertTrue(
            panelCode.contains("import com.kapp.tenon.orchestration.GrantDecision"),
            "壳应从编排层 import 这个模型",
        )
    }

    @Test
    fun fallbackCopyComesFromRelayUiTexts() {
        val code = livePackage()
        assertTrue(
            code.contains("controller.uiTexts.agentEventThinkingOnlyFallback()"),
            "「只有思考没有正文」的兜底文案必须经 RelayUiTexts 桥取 —— 编排层不许碰 composeResources",
        )
        assertFalse(code.contains("getString("), "编排层不许直接调用 Compose 资源读取")
    }
}
