// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.guards

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Team Folder 接线的单一来源守卫（阶段 1 / C4）。
 *
 * 事实前提：桌面 `ConversationSidebar` **复用了** `ConversationListPanel`（把
 * `sessions` 映射成 feed 传进去），所以折叠只有一处实现。这比"两端各写一份"更省，
 * 但也更脆 —— 一旦有人在桌面侧另写一段折叠，手机与桌面就会给出不同分组。
 *
 * 判据：
 * 1. `ConversationListPanel` 必须调 `foldTeamRows(`，并且 `itemsIndexed` 吃的是投影后的
 *    `rows`（不是原始 `feed`）；
 * 2. 渲染组件 `TeamFolderRows` 不许自己分组（不含 `foldTeamRows(`）；
 * 3. `ConversationSidebar` 只透传参数（含 `teamFoldersByConv = teamFoldersByConv`），
 *    不许自己折叠；
 * 4. `ConversationRow` 的首参仍是 `row: ConversationUi` —— 成员行不许把假会话塞进来。
 *
 * 反向验证：把 `ConversationListPanel` 的 `itemsIndexed(rows` 改回 `itemsIndexed(feed`，
 * 本用例变红。
 */
class TeamFolderWiringGuardTest {

    private val listPanel =
        "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/conversations/ConversationListPanel.kt"
    private val rowsFile =
        "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/conversations/TeamFolderRows.kt"
    private val sidebar =
        "composeApp/src/commonMain/kotlin/com/kapp/tenon/ui/desktop/DesktopConversationWidgets.kt"
    private val conversationRow =
        "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/conversations/ConversationRow.kt"
    private val phoneScreen =
        "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/conversations/ConversationsScreen.kt"
    private val desktopPane =
        "composeApp/src/commonMain/kotlin/com/kapp/tenon/ui/desktop/DesktopConversationsPane.kt"
    private val chatFrames =
        "androidApp/src/screenshotTest/kotlin/com/kapp/tenon/domain/screenshot/ScreenshotPreviewsChat.kt"

    private fun live(path: String): String =
        GuardSourceScan.stripComments(GuardSourceScan.read(path))

    @Test
    fun listPanelProjectsThroughFoldTeamRows() {
        val source = live(listPanel)
        assertTrue(
            source.contains("foldTeamRows("),
            "ConversationListPanel 必须调 foldTeamRows —— 折叠是纯函数投影，不许在渲染循环里现算",
        )
        assertTrue(
            source.contains("itemsIndexed(rows"),
            "ConversationListPanel 的列表必须消费投影后的 rows；直接吃 feed 等于跳过折叠",
        )
    }

    @Test
    fun rendererDoesNotGroupOnItsOwn() {
        val source = live(rowsFile)
        assertTrue(
            !source.contains("foldTeamRows("),
            "TeamFolderRows 只负责画：分组逻辑一旦进渲染组件，手机与桌面就会各有一套",
        )
    }

    @Test
    fun desktopSidebarOnlyPassesParametersThrough() {
        val source = live(sidebar)
        assertTrue(
            source.contains("teamFoldersByConv = teamFoldersByConv"),
            "ConversationSidebar 必须把 teamFoldersByConv 透传给共享容器",
        )
        assertTrue(
            !source.contains("foldTeamRows("),
            "ConversationSidebar 不许自己折叠 —— 它复用的是 ConversationListPanel，再写一份就是第二条数据流",
        )
    }

    @Test
    fun conversationRowStillTakesARealConversation() {
        val source = live(conversationRow)
        assertTrue(
            source.contains("internal fun ConversationRow("),
            "ConversationRow 的可见性/名字变了，更新本守卫而不是删掉它",
        )
        assertTrue(
            source.contains("row: ConversationUi"),
            "ConversationRow 的首参必须是真实 ConversationUi —— 成员行不许造假会话塞进来",
        )
    }

    @Test
    fun bothShellsComputeAndPassTheTeamFolders() {
        for (path in listOf(phoneScreen, desktopPane)) {
            val source = live(path)
            assertTrue(
                source.contains("teamFoldersByConv("),
                path + " 必须从域层算团队清单 —— 只在容器里留默认空参数等于功能没通电",
            )
        }
        // 桌面四档都要透传；漏一档就会在某个窗口宽度下"组头消失"。
        val pane = live(desktopPane)
        assertEquals(
            4,
            Regex("""teamFoldersByConv = teamFolders,""").findAll(pane).count(),
            "桌面 ConversationsPane 的四档 ConversationSidebar 都要透传 teamFolders",
        )
    }

    @Test
    fun headerKeepsBothEntryAndToggle() {
        val rows = live(rowsFile)
        assertTrue(
            rows.contains("onOpenChat: ((String) -> Unit)?"),
            "组头必须有进会话入口 —— 折叠态下组头替代了会话行，没有它这个群就点不进去",
        )
        assertTrue(
            rows.contains("onToggle: ((String) -> Unit)?"),
            "组头必须有折叠入口（右侧符号的独立热区）",
        )
        val panel = live(listPanel)
        assertTrue(panel.contains("onOpenChat = onOpenChat,"), "容器必须把进会话回调接到组头")
        assertTrue(panel.contains("onToggle = onToggleTeam,"), "容器必须把折叠回调接到组头")
    }

    @Test
    fun memberRowWiringIsComplete() {
        assertTrue(
            live(rowsFile).contains("onClick: (() -> Unit)?"),
            "成员行要能点（契约：打开所属群并预填 @leader）",
        )
        assertTrue(
            live(listPanel).contains("onMentionMember?.let"),
            "容器必须把预填回调接到成员行；只在参数上留着等于点了没反应",
        )
        assertTrue(
            live(phoneScreen).contains("onDraftChange"),
            "手机屏要提供草稿写入口，否则成员行在手机上永远不可点",
        )
        assertTrue(
            live(desktopPane).contains("onDraftChange?.let"),
            "桌面包要从 onDraftChange 派生预填（它本来就有这个槽）",
        )
    }

    @Test
    fun collapsedAndExpandedStatesStayReachableFromFrames() {
        // 折叠态是屏内 `remember` 状态，静态帧点不了组头右侧那颗符号。没有
        // `initialExpandedTeams` 这个接缝，展开态（成员行 + 角色 + 会话行补回）
        // 就是零视觉覆盖 —— 而它正是"组内不画分隔线、展开只补一次会话行"的验处。
        assertTrue(
            live(phoneScreen).contains("mutableStateOf(initialExpandedTeams)"),
            "expandedTeams 的初值必须来自 initialExpandedTeams，否则展开态永远录不到",
        )
        val frames = live(chatFrames)
        for (name in listOf("chat-home-team-folder", "chat-home-team-folder-expanded")) {
            assertTrue(
                frames.contains("name = \"" + name + "\""),
                "参考帧 " + name + " 不见了：折叠态与展开态各要一帧，删帧等于删掉这条证据",
            )
        }
    }
}
