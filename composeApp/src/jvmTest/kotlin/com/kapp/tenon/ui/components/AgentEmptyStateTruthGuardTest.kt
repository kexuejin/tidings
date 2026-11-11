// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.ui.components

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 守卫智能体页空态的**三句话必须自洽**。
 *
 * ## 现场（真机截图）
 *
 * Pixel 6 的「智能体」页同时出现：
 * - 页头 `1 台设备 · 0 Agent · 0 项目目录`；
 * - 警示条 `已收到 Agent` / `这台设备上的 Agent 还没有上报任何项目目录或会话`；
 * - 段头 `Agent（1）`；
 * - 卡内说明 `还没有设备上报 Agent 与项目目录`。
 *
 * 同一块内容里「已收到 1 个」与「没有任何设备上报」互相反驳。
 *
 * ## 根因是两把尺子
 *
 * - 页头数 `agentSections()`（只含**带目录**的 agent）⇒ 0；
 * - 空态数 `agents.size`，而 `agents` 来自 `agentPeers` —— 它会把**本机宿主**追加进去
 *   （只要 `agentWorkspaces` 里有本机的键，哪怕是一条没宣告目录的空壳）⇒ 1；
 * - 卡内说明是写死的「没有设备上报」那一句，不跟着判据翻档。
 *
 * ## 修法
 *
 * 页头与空态共用**同一把尺子**：`hubAgents(...)` 投影（只收有证据的实体，没证据的
 * 本机节点不进）。卡内说明随同一个布尔翻档。本守卫钉住这四件事，并保留一条
 * 「修法前的形态必须判红」的反向用例。
 *
 * 反向验证：先写守卫、再用修法前的源码跑一次判红，改完转绿。
 */
class AgentEmptyStateTruthGuardTest {

    private val boardPath =
        "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/agents/AgentWorkspaceBoard.kt"
    private val zhPath = "composeApp/src/commonMain/composeResources/values/strings.xml"
    private val enPath = "composeApp/src/commonMain/composeResources/values-en/strings.xml"

    private fun live(path: String): String =
        GuardSourceScan.stripComments(GuardSourceScan.read(path))

    private val xmlComment = Regex("""<!--[\s\S]*?-->""")
    private val declaredEntry = Regex("""<string\s+name=""")
    private val entry = Regex("""<string\s+name="([^"]+)"\s*>([\s\S]*?)</string>""")

    private fun values(xml: String): Map<String, String> {
        val stripped = xmlComment.replace(xml, "")
        val declared = declaredEntry.findAll(stripped).count()
        val parsed = entry.findAll(stripped).associate { it.groupValues[1] to it.groupValues[2].trim() }
        return if (declared == parsed.size) parsed else emptyMap()
    }

    /** 页头与空态共用一把尺子 + 卡内说明跟着同一个布尔翻档。 */
    private fun consistent(board: String, zh: String, en: String): Boolean {
        val zhValues = values(zh)
        val enValues = values(en)
        if (zhValues.isEmpty() || enValues.isEmpty()) return false
        if (zhValues["agent_empty_agents_only_card"].isNullOrBlank()) return false
        if (enValues["agent_empty_agents_only_card"].isNullOrBlank()) return false
        // 页头与空态两处都从同一个投影取数（出现次数 ≥ 2，而不是只改了一处）。
        if (board.split("hubAgents(agentSlice, nodeSlice, settingsSlice, now)").size - 1 < 2) return false
        return listOf(
            "val agentTotal = hubAgents(agentSlice, nodeSlice, settingsSlice, now).size",
            "val hubAgentCount = remember(agentSlice, nodeSlice, settingsSlice, now)",
            "val anyAgentReported = hubAgentCount > 0",
            "stringResource(Res.string.agent_empty_section_n, hubAgentCount)",
            "Res.string.agent_empty_agents_only_card",
            "Res.string.agent_empty_card",
        ).all { board.contains(it) }
    }

    @Test
    fun headerAndEmptyStateShareOneRuler() {
        assertTrue(
            consistent(live(boardPath), GuardSourceScan.read(zhPath), GuardSourceScan.read(enPath)),
            "智能体页的页头 / 段头 / 卡内说明没有共用同一把尺子",
        )
    }

    @Test
    fun theOldFalsePositiveRulerIsGone() {
        val board = live(boardPath)
        assertFalse(
            board.contains("val anyAgentReported = agents.isNotEmpty()"),
            "空态又回到了 agentPeers.size —— 它恒含本机宿主，会把「0 Agent」翻成「Agent（1）」",
        )
        assertFalse(
            board.contains("stringResource(Res.string.agent_empty_section_n, agents.size)"),
            "段头又按 agentPeers.size 数了",
        )
    }

    @Test
    fun everyCriticalFragmentIsRemovalSensitive() {
        val board = live(boardPath)
        val zh = GuardSourceScan.read(zhPath)
        val en = GuardSourceScan.read(enPath)
        assertTrue(consistent(board, zh, en), "变异自检的起点必须是绿")

        listOf(
            "val agentTotal = hubAgents(agentSlice, nodeSlice, settingsSlice, now).size",
            "val hubAgentCount = remember(agentSlice, nodeSlice, settingsSlice, now)",
            "val anyAgentReported = hubAgentCount > 0",
            "stringResource(Res.string.agent_empty_section_n, hubAgentCount)",
            "Res.string.agent_empty_agents_only_card",
            "Res.string.agent_empty_card",
        ).forEach { fragment ->
            assertFalse(
                consistent(board.replace(fragment, ""), zh, en),
                "摘掉「$fragment」后判据仍为真 ⇒ 这段是摆设",
            )
        }
        // 只改一处、另一处仍用旧尺子也必须判红：两处取数缺一不可。
        val oneRuler = board.replaceFirst(
            "hubAgents(agentSlice, nodeSlice, settingsSlice, now).size",
            "0",
        )
        assertFalse(consistent(oneRuler, zh, en), "只改页头、空态没跟着改时判据必须判红")
    }

    @Test
    fun thePreFixShapeIsRed() {
        val board = live(boardPath)
        val zh = GuardSourceScan.read(zhPath)
        val en = GuardSourceScan.read(enPath)
        // 把修法前的三处形态回灌进去（与真机截图同一形态），守卫必须判红。
        val preFix = board
            .replace(
                "val agentTotal = hubAgents(agentSlice, nodeSlice, settingsSlice, now).size",
                "val agentTotal = units.groups.sumOf { group -> group.agentSections().size }",
            )
            .replace("val anyAgentReported = hubAgentCount > 0", "val anyAgentReported = agents.isNotEmpty()")
            .replace(
                "stringResource(Res.string.agent_empty_section_n, hubAgentCount)",
                "stringResource(Res.string.agent_empty_section_n, agents.size)",
            )
        assertFalse(consistent(preFix, zh, en), "修法前的形态（0 Agent / Agent（1）/ 没有设备上报）必须判红")
    }

    @Test
    fun bothLocalesCarryTheCardCopyAndKeepTheVocabularyOut() {
        val zhValues = values(GuardSourceScan.read(zhPath))
        val enValues = values(GuardSourceScan.read(enPath))
        assertTrue(zhValues.isNotEmpty() && enValues.isNotEmpty(), "资源解析完整性")
        assertTrue(zhValues.containsKey("agent_empty_agents_only_card"), "中文缺 agent_empty_agents_only_card")
        assertTrue(enValues.containsKey("agent_empty_agents_only_card"), "英文缺 agent_empty_agents_only_card")
        assertFalse(
            zhValues["agent_empty_agents_only_card"].orEmpty().contains("工作区"),
            "新文案带回了刚收口掉的工程词汇",
        )
        assertFalse(
            enValues["agent_empty_agents_only_card"].orEmpty().contains("Workspace", ignoreCase = true),
            "新文案带回了刚收口掉的工程词汇",
        )
        assertEquals(zhValues.size, enValues.size, "双语逐键对账")
    }
}
