// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.cabinet

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 守卫 UX 规范 V1 §2.5③「产物闭环」的**只做视图**那一档（文件柜 Tab 2 的「智能体产物」段）。
 *
 * ## 规范原文与这一档的边界
 *
 * 原文是「所有生成的非代码成果物，底层自动归档到底栏 Tab 2【文件柜】」。真正的「归档」
 * （把文件搬进共享目录）要一次网络写 + 一条防重记账，而且是**未经询问就把用户的文件放进
 * 共享目录** —— 那是产品决策，不是 UI 优化。这一档只做前半句：把散在几十条消息里的产物
 * 在一个地方列出来，按项目目录分组，点一行回到产出它的那条会话。
 * **零迁移、零协议键、不动任何人的磁盘。**
 *
 * ## 为什么「点开」是回会话而不是打开文件
 *
 * `AgentArtifactEntity` 只有 `file_id`，**没有本地路径**。所以这一行点开的是产出它的
 * 那条会话（文件气泡在那儿，由既有文件动作去开）。把一个 `fileId` 当可打开路径是编造；
 * 给这一行挂个"打开"按钮更糟 —— 按下去没反应。
 *
 * ## 反向验证（这一档的特殊之处，如实写）
 *
 * 行为测试 [com.kapp.tenon.domain.CabinetArtifactsTest] **写在实现之后** —— 它测的投影
 * 函数在实现前不存在，先写只能编译不过，那不算"先红后绿"。所以本守卫的红不是靠"实现前
 * 跑一遍"，而是靠**对真实源文件的变异**，每一种都实测变红、随后逐字还原：
 *
 * | 变异 | 变红的用例 |
 * | --- | --- |
 * | 段里把 `if (onOpenSession == null) return` 换成默认空回调 | `deliverablesAre…` / `bothShells…` / `everyCriticalFragment…` |
 * | 手机壳把 `onOpenSession = onOpenArtifactSession` 换成 `null` | 同上三条 |
 * | 投影里删掉 `visibleInDsh()` 过滤 | `deliverablesAre…` / `visibilityAndDirectoryIdentity…` / `everyCriticalFragment…` |
 * | 投影里把 `sortedByDescending` 改回 `sortedBy` | `deliverablesAre…` / `theCapIsAppliedBeforeGrouping…` / `everyCriticalFragment…` |
 * | 投影里在循环**之前**插一次 `rows.groupBy { it.wsId }`（模拟"先分组后截断"） | 只有 `theCapIsAppliedBeforeGrouping…` 红 |
 *
 * 另外记一笔：**直接删掉**那行提前返回是编译期红 —— 后面把 `onOpenSession` 传给非空形参
 * 就编不过。也就是说 fail-closed 这一条有两道锁：类型系统一道，这条守卫一道。
 *
 * 变异自检（[everyCriticalFragmentIsRemovalSensitive]）把"逐片段摘掉判据必须变红"固化
 * 下来，防止哪天判据退化成"文件里出现过这个词"。
 */
class CabinetArtifactsSectionGuardTest {

    private val projectionPath = "app/src/commonMain/kotlin/com/kapp/tenon/domain/CabinetArtifacts.kt"
    private val sectionPath =
        "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/cabinet/AgentArtifactsCabinetSection.kt"
    private val phoneShellPath =
        "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/cabinet/CabinetScreen.kt"
    private val desktopShellPath = "composeApp/src/commonMain/kotlin/com/kapp/tenon/ui/desktop/CabinetPane.kt"
    private val phoneRootPath = "composeApp/src/commonMain/kotlin/com/kapp/tenon/App.kt"
    private val desktopRootPath = "composeApp/src/commonMain/kotlin/com/kapp/tenon/ui/desktop/DesktopShell.kt"
    private val zhPath = "composeApp/src/commonMain/composeResources/values/strings.xml"
    private val enPath = "composeApp/src/commonMain/composeResources/values-en/strings.xml"

    private fun live(path: String): String = GuardSourceScan.stripComments(GuardSourceScan.read(path))

    /** 判据要同时看六份源码 + 两份资源，用一只袋子装着，变异时才好逐份替换。 */
    private data class Sources(
        val projection: String,
        val section: String,
        val phoneShell: String,
        val desktopShell: String,
        val phoneRoot: String,
        val desktopRoot: String,
        val zh: String,
        val en: String,
    )

    /** 六份源码取**原文**，剥注释交给 [wired] 统一做 —— 负样本（只剩注释）才有意义。 */
    private fun sources(): Sources = Sources(
        projection = GuardSourceScan.read(projectionPath),
        section = GuardSourceScan.read(sectionPath),
        phoneShell = GuardSourceScan.read(phoneShellPath),
        desktopShell = GuardSourceScan.read(desktopShellPath),
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
        "cabinet_agent_artifacts_title",
        "cabinet_agent_artifacts_hint",
        "cabinet_agent_artifacts_no_ws",
    )

    /** 投影层：上限常量、全局倒序、先截断后分组、可见性、合成键、唯一群绑定尺子、线上事实。 */
    private val projectionFragments = listOf(
        "public const val CABINET_ARTIFACT_LIMIT: Int = 20",
        "sortedByDescending { it.at }",
        "if (rows.size >= limit) break",
        "visibleInDsh()",
        "isSyntheticWsId(it)",
        "rows.groupBy { it.wsId }",
        "groupBoundToWorkspace(wsId, settingsSlice.settings.groups)",
        "hasFile = artifact.fileId != null",
    )

    /** UI 段：能力缺失不画、空不画、点击落到会话、文案全走资源键。 */
    private val sectionFragments = listOf(
        "if (onOpenSession == null) return",
        "if (groups.isEmpty()) return",
        "onOpenSession(row.peerId, row.wsId)",
        "Res.string.cabinet_agent_artifacts_title",
        "Res.string.cabinet_agent_artifacts_hint",
        "Res.string.cabinet_agent_artifacts_no_ws",
        "Res.string.conv_inbox_ws_row_unnamed",
        "Res.string.agent_artifact_no_file",
    )

    /** 手机壳：段被真的挂进 Tab 2，且回调是**转发**（不是就地编一个 lambda）。 */
    private val phoneShellFragments = listOf(
        "AgentArtifactsCabinetSection(",
        "onOpenSession = onOpenArtifactSession",
    )

    /** 桌面壳：同一个 `MineCabinetView`，同一个转发口。 */
    private val desktopShellFragments = listOf(
        "onOpenArtifactSession = onOpenArtifactSession",
    )

    /** 两个组合根：落点与智能体页的 `onOpenChat` 同一条 —— 绑群走群会话。 */
    private val rootFragments = listOf(
        "onOpenArtifactSession = { peerId, wsId ->",
        "groupBoundToWorkspace(wsId, settingsSlice.settings.groups)",
    )

    private fun wired(s: Sources): Boolean {
        val zhValues = values(s.zh)
        val enValues = values(s.en)
        if (zhValues.isEmpty() || enValues.isEmpty()) return false
        if (keys.any { zhValues[it].isNullOrBlank() || enValues[it].isNullOrBlank() }) return false
        // 剥注释在**这里**做，而不是在 sources() 里：负样本就是"片段只出现在注释里"，
        // 若判据直接吃原文，那段注释会把判据骗成绿的 —— 守卫会被自己的注释绊倒。
        val projection = GuardSourceScan.stripComments(s.projection)
        val section = GuardSourceScan.stripComments(s.section)
        val phoneShell = GuardSourceScan.stripComments(s.phoneShell)
        val desktopShell = GuardSourceScan.stripComments(s.desktopShell)
        val phoneRoot = GuardSourceScan.stripComments(s.phoneRoot)
        val desktopRoot = GuardSourceScan.stripComments(s.desktopRoot)
        return projectionFragments.all { projection.contains(it) } &&
            sectionFragments.all { section.contains(it) } &&
            phoneShellFragments.all { phoneShell.contains(it) } &&
            desktopShellFragments.all { desktopShell.contains(it) } &&
            rootFragments.all { phoneRoot.contains(it) } &&
            rootFragments.all { desktopRoot.contains(it) }
    }

    @Test
    fun deliverablesAreListedGroupedAndWireBackToTheirSession() {
        assertTrue(
            wired(sources()),
            "§2.5③（只做视图档）没有全链路接线：投影 → 段 → 手机壳 / 桌面壳 → 两个组合根 → 双语资源键",
        )
    }

    /**
     * 上限必须作用在**全局件数**上：先按收到时刻倒序、再截断、最后分组。
     *
     * 反过来（先分组、每组各取几件）的症状是：一个产物很多的老目录会把新产物挤掉 ——
     * 用户刚交付的东西不在第一屏，而这一屏正是为了"刚交付的东西在哪"而存在的。
     * 光看"文件里有没有 `groupBy`"看不出顺序，所以这里比下标。
     */
    @Test
    fun theCapIsAppliedBeforeGroupingNotPerGroup() {
        val projection = live(projectionPath)
        val sortAt = projection.indexOf("sortedByDescending { it.at }")
        val breakAt = projection.indexOf("if (rows.size >= limit) break")
        val groupAt = projection.indexOf("rows.groupBy { it.wsId }")
        assertTrue(sortAt >= 0, "投影里没有全局按收到时刻倒序")
        assertTrue(breakAt >= 0, "投影里没有上限判断")
        assertTrue(groupAt >= 0, "投影里没有按项目目录分组")
        assertTrue(sortAt < breakAt, "必须先全局倒序，再逐件判断上限")
        assertTrue(
            breakAt < groupAt,
            "上限必须作用在**全局**件数上（截断后再分组）；反过来会让老目录里的旧产物挤掉新产物",
        )
    }

    /**
     * 可见性与身份沿用同一条尺子：`visibleInDsh()` 决定"这条会话在不在人眼前"，
     * `isSyntheticWsId` 决定"这个 wsId 算不算一个真目录"。
     *
     * 两处都不许在这里另起一套判据 —— 子代理静默会话里的产物在这一屏出现、或合成 wsId
     * 凭空造出一个假目录分组，都会让"按项目目录分组"这句话变成假的。
     */
    @Test
    fun visibilityAndDirectoryIdentityReuseTheExistingRulers() {
        val projection = live(projectionPath)
        assertTrue(
            projection.contains("visibleInDsh()"),
            "产物没有沿用 `visibleInDsh()` —— 子代理 / 空壳会话里的产物会在文件柜里冒出来，" +
                "而它们在卡片流和看板里本来就不存在",
        )
        assertTrue(
            projection.contains("isSyntheticWsId(it)"),
            "没有过滤合成 wsId —— 会凭空多出一个假的项目目录分组",
        )
        // 身份键不是名字：缺目录名时如实写「未命名项目目录」，绝不拿 wsId 顶替。
        assertFalse(
            live(sectionPath).contains("text = group.wsId"),
            "目录名缺失时把 wsId 印成了名字 —— 身份键不是名字",
        )
    }

    /**
     * 这一行**没有本地路径可给**，所以行模型不许长出 path 字段。
     *
     * `AgentArtifactEntity` 只有 `file_id`；产物本体要靠 `file_id` 指的那条 `file-msg` 落地。
     * 一旦行模型出现 `path`，就意味着某个调用点会把它当可打开路径 —— 那是编造。
     * 真要按 `fileId` 取文件，那是下载面的新能力，先改规范再改这一行。
     */
    @Test
    fun theRowModelNeverGrowsAPathField() {
        val projection = live(projectionPath)
        val block = Regex("""public data class CabinetArtifactRowUi\(([\s\S]*?)\n\)""")
            .find(projection)?.groupValues?.get(1)
        assertTrue(block != null, "找不到 `CabinetArtifactRowUi` 的字段列表")
        assertFalse(
            Regex("""val\s+\w*[Pp]ath\s*:""").containsMatchIn(block),
            "行模型长了 path 字段：产物只有 file_id、没有本地路径，出现 path 说明这里在假装能打开文件",
        )
        assertTrue(
            block.contains("val hasFile: Boolean"),
            "行模型丢了 `hasFile` —— 没有关联文件的产物会被印成一行只剩文件名的空副行",
        )
    }

    /**
     * 能力缺失 = 不画，不是"点了没反应"。
     *
     * 两个壳都必须**转发**这个回调（而不是就地编一个 lambda），否则 `null` 传不进来，
     * 段就会在"这个壳没有会话入口"时照样画出一列点不动的文件名。
     */
    @Test
    fun bothShellsLeaveTheSectionUndrawnWhenThereIsNoSessionEntry() {
        assertTrue(
            live(sectionPath).contains("if (onOpenSession == null) return"),
            "壳没有会话入口时这一段仍然会画 —— 能力缺失 = 不画，与「打开共享目录」那颗按钮同一条规矩",
        )
        assertTrue(
            live(phoneShellPath).contains("onOpenSession = onOpenArtifactSession"),
            "手机壳没有转发 `onOpenArtifactSession` —— null 传不进来，段会画出点不动的行",
        )
        assertTrue(
            live(desktopShellPath).contains("onOpenArtifactSession = onOpenArtifactSession"),
            "桌面壳没有转发 `onOpenArtifactSession` —— 同一个 `MineCabinetView`，两个壳必须一样",
        )
    }

    /**
     * 文案不许说"打开文件"。
     *
     * 这一档**不搬文件**，本地没有路径；说"打开"就是把用户带到一次静默失败上。
     * 中英两侧都要守住，英文尤其容易顺手写成 "open the file"。
     */
    @Test
    fun theCopyNeverPromisesToOpenTheFile() {
        val zh = values(GuardSourceScan.read(zhPath))
        val en = values(GuardSourceScan.read(enPath))
        val zhHint = zh["cabinet_agent_artifacts_hint"].orEmpty()
        val enHint = en["cabinet_agent_artifacts_hint"].orEmpty()
        assertTrue(zhHint.isNotBlank() && enHint.isNotBlank(), "提示文案缺失")
        assertFalse(zhHint.contains("打开"), "中文提示承诺了「打开」：$zhHint")
        assertFalse(enHint.contains("open", ignoreCase = true), "英文提示承诺了 open：$enHint")
        assertTrue(
            zhHint.contains("会话"),
            "提示必须说清落点是「会话」（否则用户以为点一下就能拿到文件）：$zhHint",
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
    fun everyCriticalFragmentIsRemovalSensitive() {
        val s = sources()
        assertTrue(wired(s), "变异自检的起点必须是绿")

        fun mutate(what: String, mutated: Sources, why: String) {
            assertFalse(wired(mutated), "$what 被摘掉后判据仍为真 ⇒ $why 是摆设")
        }

        projectionFragments.forEach { fragment ->
            mutate("投影里的「$fragment」", s.copy(projection = s.projection.replace(fragment, "")), "这段投影")
        }
        sectionFragments.forEach { fragment ->
            mutate("段里的「$fragment」", s.copy(section = s.section.replace(fragment, "")), "这段 UI")
        }
        phoneShellFragments.forEach { fragment ->
            mutate("手机壳里的「$fragment」", s.copy(phoneShell = s.phoneShell.replace(fragment, "")), "这处接线")
        }
        desktopShellFragments.forEach { fragment ->
            mutate("桌面壳里的「$fragment」", s.copy(desktopShell = s.desktopShell.replace(fragment, "")), "这处接线")
        }
        rootFragments.forEach { fragment ->
            mutate("手机组合根里的「$fragment」", s.copy(phoneRoot = s.phoneRoot.replace(fragment, "")), "这个落点")
            mutate(
                "桌面组合根里的「$fragment」",
                s.copy(desktopRoot = s.desktopRoot.replace(fragment, "")),
                "这个落点",
            )
        }
        keys.forEach { key ->
            val pattern = Regex("""<string name="$key"[\s\S]*?</string>""")
            mutate("中文资源里的 $key", s.copy(zh = s.zh.replace(pattern, "")), "这个键")
            mutate("英文资源里的 $key", s.copy(en = s.en.replace(pattern, "")), "这个键")
        }
    }

    @Test
    fun guardIsNotFooledByCommentsOrEmptySources() {
        val zh = GuardSourceScan.read(zhPath)
        val en = GuardSourceScan.read(enPath)
        val empty = Sources("", "", "", "", "", "", zh, en)
        assertFalse(wired(empty), "空源码必须判红，不能恒真")

        val commented = Sources(
            projection = """
                // public const val CABINET_ARTIFACT_LIMIT: Int = 20
                /* sortedByDescending { it.at }
                   if (rows.size >= limit) break
                   visibleInDsh() isSyntheticWsId(it) rows.groupBy { it.wsId }
                   groupBoundToWorkspace(wsId, settingsSlice.settings.groups)
                   hasFile = artifact.fileId != null */
            """.trimIndent(),
            section = """
                /* if (onOpenSession == null) return */
                // if (groups.isEmpty()) return
                // onOpenSession(row.peerId, row.wsId)
                // Res.string.cabinet_agent_artifacts_title
            """.trimIndent(),
            phoneShell = "// AgentArtifactsCabinetSection(\n/* onOpenSession = onOpenArtifactSession */",
            desktopShell = "// onOpenArtifactSession = onOpenArtifactSession",
            phoneRoot = "// onOpenArtifactSession = { peerId, wsId ->",
            desktopRoot = "/* groupBoundToWorkspace(wsId, settingsSlice.settings.groups) */",
            zh = zh,
            en = en,
        )
        assertFalse(wired(commented), "只出现在注释里的片段不能算接线")
    }
}
