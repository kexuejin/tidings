// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.resources

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 守卫 UX 规范 V1 §3「对外用户界面去『工作区』工程词汇」的**最后一段残留**。
 *
 * §3 的七行里，底栏 Tab / 桌面侧栏与菜单 / 建群弹窗标题 / 建群列表分类 /
 * 路径占位 / 群信息设置行都已由 Phase B 与 `6834c8e6`（判据词汇随项目目录模型同步）
 * 落地；本轮只余三条：
 * - `conv_filter_agents` 消息页筛选芯片仍是 `Agent`；
 * - `tool_approvals_row_ws` 工具审批归属行仍是「工作区 / Workspace」；
 * - `agent_detail_missing_body` 智能体详情页空态仍说「席位或工作区」。
 *
 * **边界（照 `6834c8e6`「逐处判定、协议语境与历史读数保留」的口径）**：
 * 只扫 `<string>` 的**值**，先剥 XML 注释。注释里写「工作区看板」「某个工作区点打开聊天」
 * 是内部工程词汇与历史读数，不是用户看到的字，改它们只会制造无意义 diff。
 * 同理，领域实体 / 数据库列 / 协议键里的 `Workspace` 一律不碰 —— 那是命名与所有权，
 * 不属文案收口（AGENTS §6.6 的 B 类）。
 *
 * **另一条明确边界**：`androidApp/src/screenshotTest/` 里还有 3 处写死「工作区」的
 * **mock 预览数据**（`PreviewState` 一条假工单标题 + `ScreenshotPreviewsM3Shared` 两条
 * info 行）。它们是截图帧的假数据，不是产品 UI；改它们会与已 gitignore 且陈旧的参考图集
 * 进一步脱节，属「截图参考集换代」那笔 B 类欠债，不在本笔，也不许据此说「全仓零残留」。
 *
 * 反向验证：本守卫先于实现落地，实现前 4 红 2 绿（两条恒绿的是「解析器完整性」与
 * 「逐条回灌的变异自检」，它们不依赖线上文案）；实现后 6 绿。
 */
class ProjectDirectoryWordingGuardTest {

    private val zhPath = "composeApp/src/commonMain/composeResources/values/strings.xml"
    private val enPath = "composeApp/src/commonMain/composeResources/values-en/strings.xml"

    private val xmlComment = Regex("""<!--[\s\S]*?-->""")
    private val declaredEntry = Regex("""<string\s+name=""")
    private val entry = Regex("""<string\s+name="([^"]+)"\s*>([\s\S]*?)</string>""")
    private val workspaceWord = Regex("""\b[Ww]orkspaces?\b""")

    /** 剥掉 XML 注释后逐条取值；解析数与声明数对不上就返回空表（宁可报红，不许恒真）。 */
    private fun userFacingValues(xml: String): Map<String, String> {
        val live = xmlComment.replace(xml, "")
        val declared = declaredEntry.findAll(live).count()
        val parsed = entry.findAll(live).associate { it.groupValues[1] to it.groupValues[2].trim() }
        return if (declared == parsed.size) parsed else emptyMap()
    }

    private fun zh() = userFacingValues(GuardSourceScan.read(zhPath))
    private fun en() = userFacingValues(GuardSourceScan.read(enPath))

    /* ===== 两条「零残留」棘轮 ===== */

    @Test
    fun chineseUiStringsCarryNoWorkspaceWording() {
        val values = zh()
        assertTrue(values.isNotEmpty(), "解析器没读出任何 <string> —— 空集合会让本守卫恒真")
        val offenders = values.filter { (_, text) -> "工作区" in text }
        assertTrue(
            offenders.isEmpty(),
            "用户可见中文文案里还有「工作区」（§3 要求统一为「项目目录 / 智能体」）：" +
                offenders.entries.joinToString { "${it.key}=${it.value}" },
        )
    }

    @Test
    fun englishUiStringsCarryNoWorkspaceWording() {
        val values = en()
        assertTrue(values.isNotEmpty(), "解析器没读出任何 <string> —— 空集合会让本守卫恒真")
        val offenders = values.filter { (_, text) -> workspaceWord.containsMatchIn(text) }
        assertTrue(
            offenders.isEmpty(),
            "用户可见英文文案里还有 Workspace/workspaces（§3 要求 Project directory）：" +
                offenders.entries.joinToString { "${it.key}=${it.value}" },
        )
    }

    /* ===== §3 的三条具体落点 ===== */

    @Test
    fun messageFilterChipUsesAgentsWording() {
        assertEquals("智能体", zh()["conv_filter_agents"], "消息页筛选芯片的中文标签")
        assertEquals("Agents", en()["conv_filter_agents"], "消息页筛选芯片的英文标签")
    }

    @Test
    fun approvalAttributionAndMissingAgentBodyUseProjectDirectoryWording() {
        val zhValues = zh()
        val enValues = en()
        assertEquals("项目目录 %1\$s", zhValues["tool_approvals_row_ws"], "工具审批归属行中文")
        assertEquals("Project directory %1\$s", enValues["tool_approvals_row_ws"], "工具审批归属行英文")

        val zhBody = zhValues["agent_detail_missing_body"].orEmpty()
        val enBody = enValues["agent_detail_missing_body"].orEmpty()
        assertTrue("项目目录" in zhBody && "工作区" !in zhBody, "详情页空态中文仍说工作区：$zhBody")
        assertTrue(
            "project directory" in enBody.lowercase() && !workspaceWord.containsMatchIn(enBody),
            "详情页空态英文仍说 workspace：$enBody",
        )
    }

    /* ===== 守卫自己的两条牙 ===== */

    @Test
    fun parserSeesEveryStringEntry() {
        for (path in listOf(zhPath, enPath)) {
            val xml = GuardSourceScan.read(path)
            val live = xmlComment.replace(xml, "")
            val declared = declaredEntry.findAll(live).count()
            assertEquals(
                declared,
                userFacingValues(xml).size,
                "$path 里有 <string> 没被解析到 —— 守卫会在那批文案上静默放行",
            )
        }
    }

    /**
     * 逐条把旧词回灌进一份**合成干净夹具**，每一条都必须让判据变红。
     * 用合成夹具而不是线上文件：线上文件实现前本来就是脏的，「再弄脏一点」证明不了守卫有牙。
     */
    @Test
    fun guardDetectsEachReintroducedPiece() {
        val cleanZh = """
            <resources>
              <string name="conv_filter_agents">智能体</string>
              <string name="tool_approvals_row_ws">项目目录 %1${'$'}s</string>
              <string name="agent_detail_missing_body">它的席位或项目目录可能已经撤销。</string>
            </resources>
        """.trimIndent()
        val cleanEn = """
            <resources>
              <string name="conv_filter_agents">Agents</string>
              <string name="tool_approvals_row_ws">Project directory %1${'$'}s</string>
              <string name="agent_detail_missing_body">Its seat or project directory may have been revoked.</string>
            </resources>
        """.trimIndent()

        assertTrue(cleanFixtureIsClean(cleanZh, cleanEn), "合成夹具本身必须是干净的")

        val mutations: List<Triple<String, String, String>> = listOf(
            Triple("芯片中文退回 Agent", cleanZh.replace("<string name=\"conv_filter_agents\">智能体", "<string name=\"conv_filter_agents\">Agent"), cleanEn),
            Triple("芯片英文退回 Agent", cleanZh, cleanEn.replace("<string name=\"conv_filter_agents\">Agents", "<string name=\"conv_filter_agents\">Agent")),
            Triple("归属行中文退回工作区", cleanZh.replace("项目目录 %1${'$'}s", "工作区 %1${'$'}s"), cleanEn),
            Triple("归属行英文退回 Workspace", cleanZh, cleanEn.replace("Project directory %1${'$'}s", "Workspace %1${'$'}s")),
            Triple("空态中文退回工作区", cleanZh.replace("席位或项目目录", "席位或工作区"), cleanEn),
            Triple("空态英文退回 workspaces", cleanZh, cleanEn.replace("seat or project directory", "seat or workspaces")),
            Triple("任意一条新文案夹带工作区", cleanZh.replace("</resources>", "<string name=\"future\">工作区</string></resources>"), cleanEn),
            Triple("任意一条新英文文案夹带 Workspace", cleanZh, cleanEn.replace("</resources>", "<string name=\"future\">Workspace</string></resources>")),
            Triple("整份资源解析不出来", "", cleanEn),
        )
        mutations.forEach { (why, mutatedZh, mutatedEn) ->
            assertFalse(
                cleanFixtureIsClean(mutatedZh, mutatedEn),
                "回灌「$why」后判据没报红 —— 这一条守卫是假的",
            )
        }
    }

    /** 与线上判据同源，只是把「至少读到 1000 条」换成「三条目标键都在」。 */
    private fun cleanFixtureIsClean(zhXml: String, enXml: String): Boolean {
        val zhValues = userFacingValues(zhXml)
        val enValues = userFacingValues(enXml)
        if (zhValues.isEmpty() || enValues.isEmpty()) return false
        if (zhValues.values.any { "工作区" in it }) return false
        if (enValues.values.any { workspaceWord.containsMatchIn(it) }) return false
        if (zhValues["conv_filter_agents"] != "智能体" || enValues["conv_filter_agents"] != "Agents") return false
        if (zhValues["tool_approvals_row_ws"] != "项目目录 %1\$s") return false
        if (enValues["tool_approvals_row_ws"] != "Project directory %1\$s") return false
        val zhBody = zhValues["agent_detail_missing_body"] ?: return false
        val enBody = enValues["agent_detail_missing_body"] ?: return false
        if ("项目目录" !in zhBody || "工作区" in zhBody) return false
        if ("project directory" !in enBody.lowercase() || workspaceWord.containsMatchIn(enBody)) return false
        return true
    }
}
