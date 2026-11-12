// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.agents

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 守卫 UX 规范 V1 §2.6「新手冷启动：把冷冰冰的空态变成 10 秒可玩的引导」。
 *
 * ## 规范原文与这一档的偏差（如实记）
 *
 * 原文是「**未绑定任何项目群时**展示引导卡片 → 点击呼出精简版建群弹窗」。照字面做成
 * "空态替代品"会得到一颗**死按钮**：看板的空态判据是 `hasVisibleContent`，而一条已宣告的
 * 项目目录本身就让它成立 —— 也就是说**空态成立时一个目录都没有**，「立即关联第一个项目」
 * 无目录可绑。反过来，有目录的时候空态根本不出现。
 *
 * 所以这一档把引导卡画在**已经有目录、但一个都没绑群**的那一刻（那正是"新用户首次配对
 * 设备后进入第四页"的真实画面：屏幕上列着目录名，用户不知道下一步是建群），并且：
 * - 只认**别人的、在线的**设备（文案说的是「发现局域网可用设备」）；
 * - 壳没有建群入口 ⇒ **整张不画**（与 `onOpenShareRoot` 同一条规矩）。
 *
 * ## 反向验证
 *
 * 投影的三条 fail-closed 由**真实源文件变异**证明（每条都实测变红、随后逐字还原）：
 * 去掉「已有目录绑群」短路 → `anAlreadyBoundDirectoryEndsTheOnboarding` 红；
 * 去掉「本机不算发现」过滤 → `theOwnHostIsNotADiscoveredDevice` 红；
 * 去掉「必须在线」→ 两条在线相关用例红。本守卫管的是**接线**那一段。
 */
class FirstProjectCardGuardTest {

    private val boardPath = "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/agents/AgentWorkspaceBoard.kt"
    private val cardPath = "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/agents/FirstProjectCard.kt"
    private val consolePath = "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/agents/AgentConsoleScreen.kt"
    private val phoneRootPath = "composeApp/src/commonMain/kotlin/com/kapp/tenon/App.kt"
    private val desktopRootPath = "composeApp/src/commonMain/kotlin/com/kapp/tenon/ui/desktop/DesktopShell.kt"
    private val zhPath = "composeApp/src/commonMain/composeResources/values/strings.xml"
    private val enPath = "composeApp/src/commonMain/composeResources/values-en/strings.xml"

    private fun live(path: String): String = GuardSourceScan.stripComments(GuardSourceScan.read(path))

    private data class Sources(
        val board: String,
        val card: String,
        val console: String,
        val phoneRoot: String,
        val desktopRoot: String,
        val zh: String,
        val en: String,
    )

    private fun sources(): Sources = Sources(
        board = GuardSourceScan.read(boardPath),
        card = GuardSourceScan.read(cardPath),
        console = GuardSourceScan.read(consolePath),
        phoneRoot = GuardSourceScan.read(phoneRootPath),
        desktopRoot = GuardSourceScan.read(desktopRootPath),
        zh = GuardSourceScan.read(zhPath),
        en = GuardSourceScan.read(enPath),
    )

    private val xmlComment = Regex("""<!--[\s\S]*?-->""")
    private val declaredEntry = Regex("""<string\s+name=""")
    private val entry = Regex("""<string\s+name="([^"]+)"\s*>([\s\S]*?)</string>""")

    private fun values(xml: String): Map<String, String> {
        val stripped = xmlComment.replace(xml, "")
        val declared = declaredEntry.findAll(stripped).count()
        val parsed = entry.findAll(stripped).associate { it.groupValues[1] to it.groupValues[2].trim() }
        return if (declared == parsed.size) parsed else emptyMap()
    }

    private val keys = listOf(
        "agent_onboarding_device_title",
        "agent_onboarding_device_body",
        "agent_onboarding_bind_target",
        "agent_onboarding_bind_cta",
    )

    /** 看板：投影 → fail-closed 判据 → 卡片；回调原样转发，不在壳里编 lambda。 */
    private val boardFragments = listOf(
        "firstProjectCandidate(agentSlice, nodeSlice, settingsSlice)",
        "if (firstProject != null && bindFirstProject != null)",
        "FirstProjectCard(",
        "onBind = bindFirstProject,",
    )

    /** 卡片：四条文案全走资源键，点 CTA 交回壳。 */
    private val cardFragments = listOf(
        "Res.string.agent_onboarding_device_title",
        "Res.string.agent_onboarding_device_body",
        "Res.string.agent_onboarding_bind_target",
        "Res.string.agent_onboarding_bind_cta",
        "onBind(first.workspace)",
        "Res.string.conv_inbox_ws_row_unnamed",
    )

    private val consoleFragments = listOf(
        "onBindFirstProject: ((com.kapp.tenon.domain.model.WorkspaceUi) -> Unit)? = null",
        "onBindFirstProject = onBindFirstProject,",
    )

    private fun wired(s: Sources): Boolean {
        val zhValues = values(s.zh)
        val enValues = values(s.en)
        if (zhValues.isEmpty() || enValues.isEmpty()) return false
        if (keys.any { zhValues[it].isNullOrBlank() || enValues[it].isNullOrBlank() }) return false
        val board = GuardSourceScan.stripComments(s.board)
        val card = GuardSourceScan.stripComments(s.card)
        val console = GuardSourceScan.stripComments(s.console)
        val phoneRoot = GuardSourceScan.stripComments(s.phoneRoot)
        val desktopRoot = GuardSourceScan.stripComments(s.desktopRoot)
        return boardFragments.all { board.contains(it) } &&
            cardFragments.all { card.contains(it) } &&
            consoleFragments.all { console.contains(it) } &&
            phoneRoot.contains("onBindFirstProject = { ws -> groupPromptFor = ws }") &&
            desktopRoot.contains("onBindFirstProject = { ws -> groupCreatePromptFor = ws }")
    }

    @Test
    fun theOnboardingCardIsProjectedRenderedAndWiredOnBothShells() {
        assertTrue(
            wired(sources()),
            "§2.6 引导卡没有全链路接线：投影 → 看板 → 卡片 → 两个壳 → 组合根 → 双语资源键",
        )
    }

    /**
     * 引导卡**不是空态的替代品** —— 它必须画在空态那条 `return` 之后。
     *
     * 这是这一档最关键的结构事实：空态成立时一个目录都没有，那颗按钮必然点不动。
     * 光看"文件里有没有 `firstProjectCandidate`"看不出位置，所以这里比下标。
     */
    @Test
    fun theCardIsDrawnAfterTheEmptyStateNeverInsteadOfIt() {
        val board = live(boardPath)
        val emptyAt = board.indexOf("if (!units.hasVisibleContent)")
        val cardAt = board.indexOf("firstProjectCandidate(")
        assertTrue(emptyAt >= 0, "看板里没有空态判据")
        assertTrue(cardAt >= 0, "看板里没有引导卡的投影")
        assertTrue(
            emptyAt < cardAt,
            "引导卡被挪进了空态分支（或跑到它前面）—— 空态成立时一个目录都没有，" +
                "「立即关联第一个项目」会变成一颗点不动的按钮",
        )
    }

    @Test
    fun theShellsForwardTheCallbackInsteadOfInventingOne() {
        assertTrue(
            live(consolePath).contains("onBindFirstProject = onBindFirstProject,"),
            "智能体页没有把回调透传给看板 —— 壳传进来的 null 到不了卡片那一层",
        )
        assertTrue(
            live(phoneRootPath).contains("onBindFirstProject = { ws -> groupPromptFor = ws }"),
            "手机组合根没有接上「精简建群弹窗」—— 卡片上那颗按钮点下去什么都不会发生",
        )
        assertTrue(
            live(desktopRootPath).contains("onBindFirstProject = { ws -> groupCreatePromptFor = ws }"),
            "桌面组合根没有接上同一支对话框 —— 同一个 MineCabinetView 式的共享件，两个壳必须一样",
        )
    }

    @Test
    fun theCardNamesTheDirectoryWithoutUsingTheWireKey() {
        val card = live(cardPath)
        assertFalse(
            card.contains("first.workspace.wsId"),
            "把 wsId 印成了目录名 —— 身份键不是名字（缺名字时写「未命名项目目录」）",
        )
        assertTrue(
            card.contains("first.workspace.wsTitle"),
            "卡片没有读目录名：要绑的是**哪一个**目录必须写出来，否则用户按下去只能靠猜",
        )
    }

    @Test
    fun bothLocalesStayInParity() {
        val zhValues = values(GuardSourceScan.read(zhPath))
        val enValues = values(GuardSourceScan.read(enPath))
        assertTrue(zhValues.isNotEmpty() && enValues.isNotEmpty(), "资源解析完整性")
        keys.forEach { key ->
            assertTrue(zhValues.containsKey(key), "中文资源缺 $key")
            assertTrue(enValues.containsKey(key), "英文资源缺 $key")
        }
        assertEquals(zhValues.size, enValues.size, "双语逐键对账")
    }

    @Test
    fun theCopyDoesNotBringBackTheRetiredVocabulary() {
        val zh = values(GuardSourceScan.read(zhPath))
        val en = values(GuardSourceScan.read(enPath))
        keys.forEach { key ->
            val zhValue = zh[key].orEmpty()
            val enValue = en[key].orEmpty()
            // 规范 §3：对外界面不再出现「工作区」这个工程词汇。
            assertFalse(zhValue.contains("工作区"), "$key 带回了已退役的「工作区」：$zhValue")
            assertFalse(enValue.contains("workspace", ignoreCase = true), "$key 带回了 workspace：$enValue")
        }
    }

    @Test
    fun everyCriticalFragmentIsRemovalSensitive() {
        val s = sources()
        assertTrue(wired(s), "变异自检的起点必须是绿")

        boardFragments.forEach { fragment ->
            assertFalse(
                wired(s.copy(board = s.board.replace(fragment, ""))),
                "看板里摘掉「$fragment」后判据仍为真 ⇒ 这段是摆设",
            )
        }
        cardFragments.forEach { fragment ->
            assertFalse(
                wired(s.copy(card = s.card.replace(fragment, ""))),
                "卡片里摘掉「$fragment」后判据仍为真 ⇒ 这段是摆设",
            )
        }
        consoleFragments.forEach { fragment ->
            assertFalse(
                wired(s.copy(console = s.console.replace(fragment, ""))),
                "智能体页摘掉「$fragment」后判据仍为真 ⇒ 这处接线是摆设",
            )
        }
        keys.forEach { key ->
            val pattern = Regex("""<string name="$key"[\s\S]*?</string>""")
            assertFalse(wired(s.copy(zh = s.zh.replace(pattern, ""))), "$key 从中文资源摘掉后判据仍为真")
            assertFalse(wired(s.copy(en = s.en.replace(pattern, ""))), "$key 从英文资源摘掉后判据仍为真")
        }
    }

    /**
     * 点「聊天」**直接进 1:1**，不许在路径上拦一张建群弹窗。
     *
     * 规范不变量 2：建群是另一件事，它的入口是顶栏「+」与新手卡那两条独立触发
     * （`onCreateWorkspaceGroup` / `onBindFirstProject`），与「我要跟这个 Agent 说话」无关。
     *
     * 正向证据与负向断言同批：先证明**取到了** `onOpenChat` 那段（含跳到会话的调用），
     * 否则「里面没有 groupPromptFor」在任何空源码上都成立 —— 空集假绿。
     */
    @Test
    fun openingAChatNeverBlocksOnTheGroupPrompt() {
        val cases = listOf(
            "手机" to live(phoneRootPath),
            "桌面" to live(desktopRootPath),
        )
        for ((label, src) in cases) {
            val block = openChatBlock(src)
            assertTrue(
                block.contains("openConversation(") || block.contains("openWorkspaceChat("),
                label + "：没取到 onOpenChat 里进会话那一段（空集假绿的前置证据）",
            )
            assertFalse(
                block.contains("groupPromptFor"),
                label + "：onOpenChat 又把建群弹窗当前置拦截了 —— 点聊天应该直接说话",
            )
            assertFalse(
                block.contains("groupCreatePromptFor"),
                label + "：onOpenChat 又把建群弹窗当前置拦截了 —— 点聊天应该直接说话",
            )
        }
    }

    /** `onOpenChat` 到下一个回调参数之间的源码（两个壳的下一参都是 `onLoadWorkspaceSessions`）。 */
    private fun openChatBlock(src: String): String {
        val start = src.indexOf("onOpenChat = {")
        if (start < 0) return ""
        val end = src.indexOf("onLoadWorkspaceSessions", start)
        return if (end > start) src.substring(start, end) else src.substring(start)
    }

    @Test
    fun guardIsNotFooledByCommentsOrEmptySources() {
        val zh = GuardSourceScan.read(zhPath)
        val en = GuardSourceScan.read(enPath)
        assertFalse(
            wired(Sources("", "", "", "", "", zh, en)),
            "空源码必须判红，不能恒真",
        )
        val commented = Sources(
            board = """
                // firstProjectCandidate(agentSlice, nodeSlice, settingsSlice)
                /* if (firstProject != null && bindFirstProject != null) */
                // FirstProjectCard( onBind = bindFirstProject,
            """.trimIndent(),
            card = """
                // Res.string.agent_onboarding_device_title
                /* Res.string.agent_onboarding_bind_cta */
                // onBind(first.workspace)
            """.trimIndent(),
            console = "// onBindFirstProject = onBindFirstProject,",
            phoneRoot = "// onBindFirstProject = { ws -> groupPromptFor = ws }",
            desktopRoot = "/* onBindFirstProject = { ws -> groupCreatePromptFor = ws } */",
            zh = zh,
            en = en,
        )
        assertFalse(wired(commented), "只出现在注释里的片段不能算接线")
    }
}
