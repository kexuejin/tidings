// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.guards

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Team Folder 接线的守卫。
 *
 * 事实前提有两条：
 * 1. 桌面 `ConversationSidebar` **复用了** `ConversationListPanel`，所以折叠只有一处实现；
 * 2. 折叠**不替代会话行** —— 会话行永远是 `ConversationRow`（唯一实现），团队只是行内
 *    一枚标记 + 行下追加的标签/成员行。
 *
 * 第 2 条是本守卫的重点：上一版有一个 `TeamFolderHeader` 替代会话行，结果折叠态丢掉了
 * 最后一条消息与时间，展开态又把同一条会话画了第二遍。那类退化在单测里不会红
 * （结构测试只数行数），只能靠源码守卫挡住"再长出一个替代行型"。
 *
 * 判据：
 * 1. `ConversationListPanel` 必须调 `foldTeamRows(`，且 `itemsIndexed` 吃投影后的 `rows`；
 * 2. 会话行由 `ConversationRow(row = row.item.conv, …)` 画 —— 即**真实会话**，不是造出来的行；
 * 3. 团队渲染文件里**不许有** `TeamFolderHeader`（替代行型已废除）与 `foldTeamRows(`（不许自己分组）；
 * 4. 折叠符号只有一个入口：[TeamBadge] 收 `convId` + `onToggle`，且容器把 `onToggleTeam` 传进去；
 * 5. 两端外壳都从域层算团队清单；桌面四档都透传；
 * 6. 成员行点击链路完整（行可点 → 容器接 → 两端有草稿入口）；
 * 7. 折叠态初值接缝与两帧参考图都在。
 */
class TeamFolderWiringGuardTest {

    private val listPanel =
        "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/conversations/ConversationListPanel.kt"
    private val rowsFile =
        "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/conversations/TeamFolderRows.kt"
    private val projection =
        "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/conversations/TeamFolderProjection.kt"
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
            "列表必须消费投影后的 rows；直接吃 feed 等于跳过折叠",
        )
    }

    @Test
    fun sessionRowIsStillTheRealConversation() {
        val panel = live(listPanel)
        assertTrue(
            panel.contains("ConversationRow(") && panel.contains("row.item.conv"),
            "会话行必须由 ConversationRow 画真实会话（row.item.conv）—— 造一个假行就是上一版的退化",
        )
        // 替代行型已废除：谁把它加回来，这里先红。
        assertTrue(
            !live(rowsFile).contains("TeamFolderHeader"),
            "不许再引入替代会话行的组头 —— 它会丢预览与时间，展开态还会重复同一会话",
        )
        assertTrue(
            !live(projection).contains("sessionRowEmitted"),
            "展开态不再需要'补回会话行'：会话行一直都在",
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
            source.contains("expandedTeamConvs = expandedTeamConvs"),
            "展开态也要透传（会话键集合）",
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
        assertTrue(
            source.contains("teamSlot: (@Composable RowScope.() -> Unit)?"),
            "团队标记只能作为会话行的尾部插槽，不能反过来让会话行判断'我是不是团队'",
        )
    }

    @Test
    fun oneTogglePerRow() {
        val rows = live(rowsFile)
        assertTrue(
            rows.contains("convId: String"),
            "折叠标记收的是**会话键**：一行只有一个开关（多个团队整块展开）",
        )
        assertTrue(
            rows.contains("onToggle: ((String) -> Unit)?"),
            "折叠入口必须存在；没有它这一行就只能看不能展",
        )
        assertTrue(
            live(listPanel).contains("onToggle = onToggleTeam,"),
            "容器必须把折叠回调接到标记上",
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
        // 桌面四档都要透传；漏一档就会在某个窗口宽度下"标记消失"。
        val pane = live(desktopPane)
        assertEquals(
            4,
            Regex("""teamFoldersByConv = teamFolders,""").findAll(pane).count(),
            "桌面 ConversationsPane 的四档 ConversationSidebar 都要透传 teamFolders",
        )
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
        // 展开态是屏内 `remember` 状态，静态帧点不了行内那颗符号。没有
        // `initialExpandedTeamConvs` 这个接缝，展开态（团队标签 + 成员行）就是零视觉覆盖。
        assertTrue(
            live(phoneScreen).contains("mutableStateOf(initialExpandedTeamConvs)"),
            "expandedTeamConvs 的初值必须来自 initialExpandedTeamConvs，否则展开态永远录不到",
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
