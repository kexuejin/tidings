// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 契约形状与两条编排不变量的**源码级守卫**。
 *
 * ## 为什么这些判据只能写在源码上
 *
 * 契约是一组声明，它的「对」不是运行出来的：
 *
 * - 会话制五个成员在不在接口上 —— 这是编译级的，但**删掉一个成员**在实现侧可以靠
 *   「恰好没人调用」而全绿，所以这里钉住成员清单；
 * - 能力 ACL 不许回到接口上（它是宿主状态，透传给插件进程就会绕过宿主）——
 *   这一条永远是「某个成员不存在」，只能用源码判据；
 * - KDoc 里的两条不变量（交接靠提交、单卡单槽翻面刷新）是给下一个人读的契约，
 *   删掉不会让任何测试变红 —— 所以连 KDoc 一起钉住。
 *
 * 判据全部先剥注释再匹配（AGENTS §4 的「守卫别被自己的注释绊倒」）。
 */
class AgentDriverContractShapeTest {

    private val contractSource = "agent-contract/src/main/kotlin/com/kapp/tenon/agent/drivers/AgentDriver.kt"

    private fun live(): String = GuardSourceScan.stripComments(GuardSourceScan.read(contractSource))

    @Test
    fun sessionLifecycleMembersStayOnTheInterface() {
        val code = live()
        listOf(
            "val driverId: String",
            "val displayName: String",
            "suspend fun listCapabilities(): List<DriverCapability>",
            "suspend fun openSession(wsPath: String, resumeSessionId: String?): SessionHandle",
            "suspend fun executeTask(",
            "onEvent: suspend (DriverEvent) -> Unit",
            "suspend fun handleAction(sessionId: String, actionId: String, decidedBy: String)",
            "suspend fun queryProgress(sessionId: String): TaskProgressSnapshot?",
        ).forEach { member ->
            assertTrue(code.contains(member), "契约少了成员/形状变了：$member —— 驱动与宿主都按它寻址")
        }
    }

    @Test
    fun capabilityAclMustStayHostSide() {
        val code = live()
        listOf("fun updateCapabilityAcl", "fun isCapabilityAllowed", "fun capabilityAcl").forEach { forbidden ->
            assertFalse(
                code.contains(forbidden),
                "能力 ACL 不许回到驱动接口上（$forbidden）：它是宿主状态，进程外插件驱动一次都不许碰到",
            )
        }
    }

    @Test
    fun eventVocabularyIsExactlyTheSevenKnownShapes() {
        // 七个事件型是驱动面：少一个 = 某类增量静默丢失；多一个 = 宿主映射与客户端解析都不认识。
        // 其中 Artifact 是**进程内**事件（本机路径只走内存，不进协议帧），其余六型才会随帧下发。
        val variants = Regex("""data\s+class\s+\w+\s*\([^)]*\)\s*:\s*DriverEvent""", RegexOption.DOT_MATCHES_ALL)
            .findAll(live()).map { it.value.substringAfter("data class ").substringBefore("(") }.toList()
        assertEquals(
            listOf("ThoughtDelta", "TokenDelta", "ToolCall", "Card", "Artifact", "Finished", "Error").sorted(),
            variants.sorted(),
            "DriverEvent 的取值域变了：新增/删除事件都必须同时改宿主映射与客户端解析",
        )
    }

    @Test
    fun kdocCarriesTheTwoOrchestrationInvariants() {
        // 读未剥注释的原文：这两条恰恰是 KDoc 里的契约，剥了就什么都不剩。
        val kdoc = GuardSourceScan.read(contractSource)
        assertTrue(kdoc.contains("交接靠提交"), "契约 KDoc 丢了「交接靠提交，不靠原始日志」这条不变量")
        assertTrue(kdoc.contains("StageSeed"), "契约 KDoc 必须点名 promptOf 的唯一入参形状（StageSeed）")
        assertTrue(kdoc.contains("单卡单槽"), "契约 KDoc 丢了「单卡单槽翻面刷新」这条不变量")
        assertTrue(kdoc.contains("cardId") && kdoc.contains("rev"), "契约 KDoc 必须写明 cardId 固定、rev 严格递增")
    }

    @Test
    fun contractModuleHasNoUpstreamDependency() {
        // 依赖方向单向：契约 ← agentNode ← orchestration/desktopApp。
        // 反向依赖会让「只依赖契约」的验收物（假驱动链）重新拖进整个控制面。
        val dependencyLines = GuardSourceScan.read("agent-contract/build.gradle.kts")
            .lineSequence()
            .filter { line ->
                val t = line.trimStart()
                t.startsWith("implementation(") || t.startsWith("api(") || t.startsWith("testImplementation(")
            }
            .toList()
        assertTrue(dependencyLines.isNotEmpty(), "构建脚本里一条依赖都没解析出来 —— 这条守卫在空转")
        dependencyLines.forEach { line ->
            assertFalse(
                line.contains("project(\":agentNode\")") || line.contains("project(\":orchestration\")") ||
                    line.contains("project(\":desktopApp\")") || line.contains("project(\":app\")") ||
                    line.contains("project(\":core\")") || line.contains("compose") || line.contains("ktor"),
                "agent-contract 的依赖里出现了上游模块或 UI/网络栈（$line）—— 契约必须保持纯接口",
            )
        }
    }
}
