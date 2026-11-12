// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.guards

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 五个首页 tab 的顶栏必须共用同一套 chrome：`LargeTitleBar`（整幅宽的 `surface` 面）
 * + `TitleBarDivider`（一条 hairline），下面的内容区才透出 `background`。
 *
 * ## 为什么这条要写成源码守卫
 *
 * 2026-11-06 真机像素复测：消息 / 节点 / 设置的白色顶栏色带一直到 y=290（1080×2400），
 * 设置到 y=279，而**文件柜与工作区只到 y=127** —— 那 127px 是根 Surface 给系统栏
 * inset 铺的底，两页其实一条顶栏都没画：标题是 `LazyColumn` / 滚动 `Column` 里的一个
 * `Row`，直接铺在页面底色上。浅色只差 #FFFFFF→#FAFAF8（2/255，看着"发灰"），
 * 深色差 #232625→#121413（17/255，一整条带）。原型 m08-nodes / m11-agents /
 * m11b-cabinet / m12-settings 四张参考帧一律是白顶栏 + hairline + 米白内容区。
 *
 * 这不是"改个色值"能补的：只要某一页还在内容列里手画标题，它就没有自己的
 * `surface` 面、也不会有分隔线，并且会跟着内容滚走。类型系统完全看不出来，
 * 截图门禁也只看原型 HTML，所以只能在源码层问「这一页的顶栏从哪来」。
 *
 * 守卫刻意按**调用块**取（[GuardSourceScan.bracedBlockAfter]）而不是全文 contains：
 * 注释里正当地写着这些组件名，全文匹配会被自己的注释喂饱。
 */
class TabChromeConsistencyGuardTest {

    private val src = "composeApp/src/commonMain/kotlin/com/kapp/tenon/"

    /** 一屏 = 「这个文件的这一段活代码里必须 / 不得出现某个形状」。 */
    private data class Rule(
        val file: String,
        val marker: String?,
        val mustContain: List<String> = emptyList(),
        val mustNotContain: List<String> = emptyList(),
        val why: String,
    )

    private val rules: List<Rule> = listOf(
        Rule(
            "ui/components/TopBars.kt", "fun LargeTitleBar(",
            mustContain = listOf(
                ".background(MaterialTheme.colorScheme.surface)",
                "heightIn(min = sp.xxxl)",
            ),
            why = "共享顶栏必须自己铺 surface（不许透出 background），且高度有 48dp 下限",
        ),
        Rule(
            "feature/conversations/ConversationsScreen.kt", "fun ConversationsScreen(",
            mustContain = listOf("LargeTitleBar(", "TitleBarDivider()"),
            why = "消息页：标准顶栏 + hairline",
        ),
        Rule(
            "feature/nodes/NodesScreen.kt", "fun NodesScreen(",
            mustContain = listOf("LargeTitleBar(", "TitleBarDivider()"),
            why = "节点页：列表与详情两条分支都走标准顶栏",
        ),
        Rule(
            "feature/settings/SettingsHomeScreen.kt", "fun SettingsHomeScreen(",
            mustContain = listOf("LargeTitleBar(", "TitleBarDivider()"),
            why = "设置首页：标准顶栏 + hairline",
        ),
        Rule(
            "feature/cabinet/CabinetScreen.kt", "fun MineCabinetView(",
            mustContain = listOf("LargeTitleBar(", "TitleBarDivider()"),
            mustNotContain = listOf("headlineSmall"),
            why = "文件柜：标题曾画在 LazyColumn 的 item 里、铺在 background 上",
        ),
        Rule(
            "feature/agents/AgentWorkspaceBoard.kt", "fun WorkspaceBoardTitleBar(",
            mustContain = listOf("LargeTitleBar(", "TitleBarDivider()"),
            why = "工作区：顶栏提到滚动列之外，整幅宽 surface + hairline",
        ),
        Rule(
            "feature/agents/AgentWorkspaceBoard.kt", "fun WorkspaceBoardView(",
            mustNotContain = listOf("headlineSmall", "showHeader"),
            why = "看板正文里不许再出现第二份页头标题，否则又是两套 chrome",
        ),
        Rule(
            "feature/agents/AgentConsoleScreen.kt", "fun AgentConsoleScreen(",
            mustContain = listOf("WorkspaceBoardTitleBar("),
            why = "工作区屏必须挂上那个顶栏组件（只定义不调用等于没修）",
        ),
        Rule(
            "feature/search/GlobalSearchScreen.kt", "fun SearchTopBar(",
            mustContain = listOf(".background(MaterialTheme.colorScheme.surface)"),
            why = "搜索态顶栏对应原型 .topbar.gs，继承 .topbar 的 --surface",
        ),
    )

    /**
     * 取函数体，而不是「marker 之后的第一个花括号」。
     *
     * `LargeTitleBar` 的签名里有 `actions: @Composable () -> Unit = {}`，那个 `{}`
     * 比函数体早出现；直接 `bracedBlockAfter` 会把「缺少 background」误报成真缺陷。
     * 先用平衡圆括号吃掉整个形参表，再在其后找第一对花括号。
     */
    private fun bodyOf(live: String, marker: String): String {
        val at = live.indexOf(marker)
        assertTrue(at >= 0, "源码里找不到函数：$marker")
        val params = GuardSourceScan.parenBlockAfter(live, marker, at)
        val afterSignature = live.substring(at + marker.length + params.length + 1)
        return GuardSourceScan.bracedBlockAfter(afterSignature, "")
    }

    /**
     * 整屏容器绑 `surface` 的**逐条登记表**：键是「相对路径:所在函数名」，值必须有理由。
     *
     * 白名单不是垃圾桶：新写一个整屏 `Surface(color = surface)` 而不在这里表态即红。
     * 用函数名而不是行号，上游插注释不会让门禁失效。
     */
    private val fullScreenSurfaceAllowed = mapOf(
        "com/kapp/tenon/App.kt:RelayApp" to
            "外层整窗 Surface，只负责状态栏/导航栏 inset 底色；原型 .topbar/.nav 用 --surface",
        "com/kapp/tenon/feature/chat/ArtifactPreview.kt:ArtifactPreviewDialog" to
            "Dialog 内的全屏预览，是抬起的对话框面而不是页内容区",
        "com/kapp/tenon/feature/chat/HistorySearchOverlay.kt:HistorySearchOverlay" to
            "整屏浮层，原型 .hsbd{background:var(--surface)} 明确如此",
    )

    /**
     * 第二种「整屏绑 surface」形态：`Column(modifier = modifier.fillMaxSize()
     * .background(colorScheme.surface))`。上面那条守卫只认 `Surface(color = ...)`，
     * 照不到直接铺 modifier 的写法。桌面搜索独立窗口是原型 `.win{background:var(--surface)}`
     * 明确要求的例外；外层 `BoxWithConstraints` 与最外层 Surface 同一语义。
     */
    private val fullScreenBackgroundAllowed = mapOf(
        "com/kapp/tenon/App.kt:RelayApp" to
            "BoxWithConstraints 是外层整窗 inset 容器，与最外层 Surface 同一语义",
        "com/kapp/tenon/ui/desktop/DesktopGlobalSearch.kt:DesktopGlobalSearchPage" to
            "桌面搜索是独立窗口内容，原型 .win{background:var(--surface)} 明确如此",
    )

    /** 取 `openAt` 处圆括号里的活代码块（含括号），未闭合返回 null。 */
    private fun parenBlock(text: String, openAt: Int): String? {
        var depth = 0
        for (i in openAt until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return text.substring(openAt, i + 1)
                }
            }
        }
        return null
    }

    @Test
    fun `full screen containers never bind surface`() {
        val sourceRoot = "composeApp/src/commonMain/kotlin"
        val files = GuardSourceScan.kotlinFiles(sourceRoot)
        assertTrue(files.isNotEmpty(), "源码目录扫不到 .kt —— 空集合会让这条门禁恒真")
        val offenders = mutableListOf<String>()
        val registered = mutableListOf<String>()
        files.forEach { path ->
            val live = GuardSourceScan.stripComments(GuardSourceScan.read(path))
            var at = 0
            while (true) {
                val call = live.indexOf("Surface(", at)
                if (call < 0) break
                at = call + 1
                val block = parenBlock(live, call + "Surface".length) ?: continue
                if ("fillMaxSize()" !in block) continue
                if (!Regex("color\\s*=\\s*[^,\\n]*\\bsurface(Variant|Container\\w*)?\\b").containsMatchIn(block)) continue
                val before = live.substring(0, call)
                val fname = Regex("""\bfun\s+(\w+)\s*\(""").findAll(before).lastOrNull()?.groupValues?.get(1) ?: "?"
                val relative = path.removePrefix(sourceRoot + "/")
                val key = "$relative:$fname"
                if (key in fullScreenSurfaceAllowed) registered.add(key) else offenders.add(key)
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "未登记的整屏 surface（浅色 #FAFAF8→#FFFFFF，深色 #121413→#232625 的跳色源头）：\n" +
                offenders.joinToString("\n"),
        )
        // 「白名单里已无命中」也要报：没数过的 0 比难看的正数危险。
        val neverSeen = fullScreenSurfaceAllowed.keys - registered.toSet()
        assertTrue(
            neverSeen.isEmpty(),
            "登记表里有从未命中的条目（门禁分母失效，或是代码搬走了没改守卫）：" +
                neverSeen.joinToString(", "),
        )
    }

    /** 从 `call` 往前找最近的未配对 `(`，返回它到 `call` 之间的实参文本。 */
    private fun argsSinceOpen(live: String, call: Int): String? {
        var depth = 0
        var i = call - 1
        val floor = maxOf(0, call - 600)
        while (i >= floor) {
            when (live[i]) {
                ')' -> depth++
                '(' -> {
                    if (depth == 0) return live.substring(i + 1, call)
                    depth--
                }
            }
            i--
        }
        return null
    }

    @Test
    fun `full screen modifier chains never bind surface`() {
        val sourceRoot = "composeApp/src/commonMain/kotlin"
        val files = GuardSourceScan.kotlinFiles(sourceRoot)
        assertTrue(files.isNotEmpty(), "源码目录扫不到 .kt —— 空集合会让这条门禁恒真")
        val offenders = mutableListOf<String>()
        val registered = mutableListOf<String>()
        files.forEach { path ->
            val live = GuardSourceScan.stripComments(GuardSourceScan.read(path))
            var at = 0
            while (true) {
                val call = live.indexOf(".background(", at)
                if (call < 0) break
                at = call + 1
                val args = parenBlock(live, call + ".background".length) ?: continue
                if (!Regex("""colorScheme\.surface\b""").containsMatchIn(args)) continue
                // 只看**所在调用本身**的参数链：从最近的未配对 `(` 起，到 .background
                // 之前必须含 fillMaxSize()。顶栏的 fillMaxWidth() 不算整屏；
                // 也不能「往前找最近的 Modifier」——那会跨到上一条语句的
                // fillMaxSize()，把桌面侧栏 `Column(modifier.background(surface))`
                // 误判成整屏（实测假阳性）。
                val chain = argsSinceOpen(live, call) ?: continue
                if ("fillMaxSize()" !in chain) continue
                val before = live.substring(0, call)
                val fname = Regex("""\bfun\s+(\w+)\s*\(""").findAll(before).lastOrNull()?.groupValues?.get(1) ?: "?"
                val key = path.removePrefix(sourceRoot + "/") + ":" + fname
                if (key in fullScreenBackgroundAllowed) registered.add(key) else offenders.add(key)
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "未登记的整屏 `.background(surface)`（整屏内容区必须用 background）：\n" +
                offenders.joinToString("\n"),
        )
        val neverSeen = fullScreenBackgroundAllowed.keys - registered.toSet()
        assertTrue(
            neverSeen.isEmpty(),
            "登记表里有从未命中的条目（门禁分母失效，或代码搬走了没改守卫）：" +
                neverSeen.joinToString(", "),
        )
    }

    /**
     * 手机五个 tab 共用一条页面级顶栏；桌面壳的内容区一律不画第二条。
     *
     * 桌面壳顶部已有窗口级 `DesktopTitleBar` + 工具栏，`AgentConsoleScreen` 若沿用默认
     * `showHeader = true`，就会在两栏内容区再叠一条按 `ContentWidths.Pane` 收列居中的
     * `WorkspaceBoardTitleBar`：大标题飘在左右两栏之间、刷新圆钮孤悬右侧，切 tab 时
     * 与另外四页的 chrome 形态不一致。节点页 / 聊天页在桌面壳都传 `showHeader = false`，
     * 智能体页必须跟着走同一条。
     *
     * 取**调用实参块**而不是全文 contains：注释里正当写着这个参数名，全文匹配会被自己
     * 的注释喂饱；而且参数可能挂到别的调用上。手机壳反向断言不许显式关页头 —— 那边
     * 五个 tab 的一致性仍由上面的规则守着。
     */
    @Test
    fun `desktop shell suppresses page title bar while phone keeps it`() {
        val desktop = GuardSourceScan.stripComments(
            GuardSourceScan.read(src + "ui/desktop/DesktopShell.kt"),
        )
        val desktopArgs = GuardSourceScan.parenBlockAfter(desktop, "AgentConsoleScreen(")
        assertTrue(
            "showHeader = false" in desktopArgs,
            "桌面壳的 AgentConsoleScreen 调用必须传 showHeader = false：" +
                "窗口标题栏 + 工具栏已是页头，再画一条就是双层 chrome",
        )
        val phone = GuardSourceScan.stripComments(GuardSourceScan.read(src + "App.kt"))
        val phoneArgs = GuardSourceScan.parenBlockAfter(phone, "AgentConsoleScreen(")
        assertTrue(
            "showHeader" !in phoneArgs,
            "手机壳不许显式关页头：五个首页 tab 共用同一条 topbar + hairline",
        )
    }

    @Test
    fun `five home tabs share one title bar shape`() {
        val failures = mutableListOf<String>()
        rules.forEach { rule ->
            val path = src + rule.file
            val live = GuardSourceScan.stripComments(GuardSourceScan.read(path))
            val block = if (rule.marker != null) bodyOf(live, rule.marker) else live
            rule.mustContain.forEach { needle ->
                if (needle !in block) {
                    failures += path + " [" + rule.marker + "] 缺少 " + needle + " —— " + rule.why
                }
            }
            rule.mustNotContain.forEach { needle ->
                if (needle in block) {
                    failures += path + " [" + rule.marker + "] 仍含 " + needle + " —— " + rule.why
                }
            }
        }
        assertTrue(
            failures.isEmpty(),
            "首页 tab 顶栏不一致（" + failures.size + " 处）：\n" + failures.joinToString("\n"),
        )
    }
}
