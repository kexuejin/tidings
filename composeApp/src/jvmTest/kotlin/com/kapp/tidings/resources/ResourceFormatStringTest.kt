// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.resources

import com.kapp.tidings.testsupport.GuardSourceScan

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 守卫：`strings.xml` 里的百分号必须写成**一个** `%`，不能用 `%%` 转义。
 *
 * ## 为什么这条纪律反直觉
 *
 * 全世界所有走 `String.format` 的文案，字面百分号都该写 `%%`。**本仓不是**：
 * Compose Multiplatform 的 `replaceWithArgs` 是正则替换，不是 `String.format`。
 *
 * 口径来自字节码而非文档 —— 直接读 `components-resources-{android,desktop}:1.11.1`
 * 里 `org/jetbrains/compose/resources/StringResourcesUtilsKt.class` 的常量池：
 *
 * ```
 * SimpleStringFormatRegex = Regex("""%(\d+)\$[ds]""")
 * ```
 *
 * 两个平台的常量完全一致（common 源集编译产物）。推论有三条，每条都跟 `String.format`
 * 相反或不同：
 *
 * 1. `%%` **不会**被还原成 `%` —— 它不匹配正则，原样显示成两个百分号。
 * 2. `%1$d%` 是**正确**写法 —— 前半被替换、尾部裸 `%` 原样留下，显示 `45%`。
 * 3. `%s`（不带位置）也**不**匹配 —— 会原样显示 `%s`。
 *
 * ## 这条守卫补的是哪个洞
 *
 * 中英两处文案对百分号的理解曾经分叉：中文 `file_state_active_in` 写的是 `%1$d%`
 * （对），英文写的是 `Receiving %1$d%%`（会把 `Receiving 45%%` 甩给英文用户）。
 * 既有三道门禁全都没看见：
 * - 编译不报（串就是普通字符串）；
 * - `design-locale-parity` 比的是 **key** 集合与占位符**有无**，两边都"有占位符"即过关；
 * - `cjk-literal-gate` 只管中文有没有走到资源层。
 *
 * ## 为什么不复用 `String.format` 做判定
 *
 * 试过，方向是错的：`String.format("%1\$d%")` 抛 `UnknownFormatConversionException`，
 * 于是这条本该守 `%%` 的守卫会把**所有正确写法**判成 offender。运行时的口径只有
 * 那个正则，判定就必须照着那个正则来。
 */
class ResourceFormatStringTest {

    private val stringResourcesRoot = "composeApp/src/commonMain/composeResources"

    @Test
    fun noDoublePercentAnywhere() {
        val offenders = mutableListOf<String>()
        var scanned = 0

        forEachString { rel, name, template ->
            scanned++
            if (template.contains("%%")) {
                offenders += "  $rel  <string name=\"$name\">：$template\n" +
                    "      ↳ `%%` 不会被还原成 `%`，用户会看到两个百分号。改写成单个 `%`。"
            }
        }

        // 一条都没扫到说明解析坏了（目录结构变了 / 正则不匹配），
        // 那种情况下"没有 offender"是假绿。
        assertTrue(scanned > 1500, "只扫到 $scanned 条资源串，解析大概率坏了")

        assertEquals(
            emptyList(),
            offenders,
            "strings.xml 里有 `%%` —— Compose Multiplatform 的正则替换不认它：\n" +
                offenders.joinToString("\n"),
        )
    }

    /**
     * 占位符必须是 CMP 认识的那一种（`%n$d` / `%n$s`）。
     *
     * 不带位置的 `%s`、或者 `%n$f` 这类别的转换字符，正则都不匹配 ⇒ 原样显示给用户。
     * 这类 typo 编译期同样不报，只能在这里挡。
     */
    @Test
    fun placeholdersUseTheOnlyFormCmpRewrites() {
        val offenders = mutableListOf<String>()

        forEachString { rel, name, template ->
            // 先把合法占位符整段抹掉，剩下的 `%` 就是 CMP 不会处理的。
            val residue = template.replace(CMP_PLACEHOLDER, "")
            for (m in Regex("""%(.)""").findAll(residue)) {
                val next = m.groupValues[1]
                // 裸 `%`（后面不是字母/百分号）是字面百分号，正是本仓的正确写法，放行。
                if (next == "%" || !next[0].isLetter()) continue
                offenders += "  $rel  <string name=\"$name\">：$template\n" +
                    "      ↳ `%$next` 不是 `%n\$d`/`%n\$s`，CMP 不会替换它，会原样显示。"
            }
        }

        assertEquals(
            emptyList(),
            offenders,
            "strings.xml 里有 CMP 不认识的占位符写法：\n" + offenders.joinToString("\n"),
        )
    }

    /**
     * 反向验证：判定规则得真能分辨对错。
     *
     * 没有这条，`noDoublePercentAnywhere` 哪天被改成 `if (false)` 也照样绿。
     */
    @Test
    fun rulesMatchTheBytecodeSemantics() {
        // 中英曾经分叉的那一对：中文对、英文错。
        assertTrue("%1\$d%".let { !it.contains("%%") }, "`%1\$d%` 是正确写法，不得被判 offender")
        assertTrue("Receiving %1\$d%%".contains("%%"), "`%%` 必须被判 offender")

        // 占位符形态：`%1$d`/`%2$s` 合法，`%s` 与 `%1$f` 不合法。
        assertEquals("%", "%1\$d%".replace(CMP_PLACEHOLDER, ""))
        assertTrue(Regex("""%(.)""").containsMatchIn("%s".replace(CMP_PLACEHOLDER, "")))
        assertTrue(Regex("""%(.)""").containsMatchIn("%1\$f".replace(CMP_PLACEHOLDER, "")))
    }

    private fun forEachString(block: (rel: String, name: String, template: String) -> Unit) {
        val root = GuardSourceScan.scaffoldRoot()
        val dir = GuardSourceScan.resolveDir(stringResourcesRoot)
        val files = dir.listFiles().orEmpty()
            .filter { it.isDirectory }
            .map { File(it, "strings.xml") }
            .filter { it.isFile }
            .sortedBy { it.path }
        assertTrue(files.isNotEmpty(), "在 $stringResourcesRoot 下没找到任何 strings.xml")

        for (file in files) {
            val rel = file.relativeTo(root).path.replace(File.separatorChar, '/')
            for ((name, template) in parseStrings(file.readText())) block(rel, name, template)
        }
    }

    /**
     * 取 `<string name="…">内容</string>`；`<string-array>` / `<plurals>` 里的 `<item>`
     * 不走同一个 `replaceWithArgs` 路径，不取。
     */
    private fun parseStrings(xml: String): List<Pair<String, String>> =
        Regex(
            """<string\s+name="([^"]+)"[^>]*>(.*?)</string>""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        ).findAll(xml).map { it.groupValues[1] to unescape(it.groupValues[2]) }.toList()

    /** Android 资源里的反斜杠转义与 XML 实体，还原后再判定。 */
    private fun unescape(raw: String): String = raw
        .replace("\\'", "'")
        .replace("\\\"", "\"")
        .replace("\\n", "\n")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    private companion object {
        /** 与 CMP `SimpleStringFormatRegex` 逐字一致：`%(\d+)\$[ds]`。 */
        val CMP_PLACEHOLDER = Regex("""%(\d+)\$[ds]""")
    }
}
