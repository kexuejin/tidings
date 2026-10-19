// SPDX-License-Identifier: GPL-3.0-only
//
// P2-21：禁止硬编码 CJK 用户可见文案的门禁（lint gate）。
//
// 由根 build.gradle.kts 通过 `apply(from = "tools/cjk-literal-gate.gradle.kts")`
// 引入，注册三个任务：
//
//   :cjkLiteralGate            扫描生产 Kotlin 源码，与基线比对（默认入口）
//   :cjkLiteralGateBaseline    重新生成基线（有意为之的棘轮调整）
//   :cjkLiteralGateSelfTest    用内存样本自检词法器（证明门禁不是空转）
//
// 为什么是一个词法器而不是 grep：本仓库中文注释/KDoc 极多，裸 grep 会把注释、
// 文档里的示例代码全算成违规。这里用一个小型状态机区分
//   CODE / 行注释 / 块注释(含 KDoc) / 普通字符串 / 原始字符串 / 字符字面量，
// 只有后三者的**字面量正文**里出现 CJK 才算违规；`${...}` 插值会切回 CODE 并按
// 花括号配平（插值里再嵌一个字符串也能正确归位）。
//
// 基线语义（棘轮，只增不改）：
//   基线文件记录**每个文件允许的 CJK 字面量条数**。门禁在
//   「某文件当前条数 > 该文件基线条数」或「出现基线里没有的文件」时失败，
//   失败时逐条打印 `file:line: "片段"`。
//   条数下调（有人做了资源化）不失败，只提示可以下调基线。
//   用条数而不是 file:line 做基线，是因为并行开发中行号漂移非常频繁，
//   file:line 基线会把「同一处代码上移 10 行」误报成新增违规。

import org.gradle.api.GradleException
import java.io.File

// ---------------------------------------------------------------------------
// 词法器
// ---------------------------------------------------------------------------

/** 一条命中：字面量里出现 CJK 的首行，以及该字面量的片段。 */
class CjkLiteralHit(val line: Int, val snippet: String)

/**
 * 小型 Kotlin 词法器：只回答一个问题 —— 生产源码里哪些**字面量正文**含 CJK。
 *
 * 覆盖：`//` 行注释、`/* */` 块注释（KDoc 是块注释的一种，因此天然排除）、
 * `"..."` 普通字符串（含 `\"` 转义）、`"""..."""` 原始字符串、`'x'` 字符字面量、
 * 以及字符串/原始字符串里的 `${...}` 插值。
 */
object KotlinCjkLexer {

    // CJK 相关区段（含全角标点、假名、谚文）。刻意不含 U+2018/2019/201C/201D
    // 这类弯引号：它们在非中文文案里也合法，收进来只会制造假阳性。
    private val CJK_RANGES: List<IntRange> = listOf(
        0x2E80..0x2EFF,     // CJK Radicals Supplement
        0x3000..0x303F,     // CJK Symbols and Punctuation
        0x3040..0x30FF,     // Hiragana + Katakana
        0x3100..0x312F,     // Bopomofo
        0x3400..0x4DBF,     // CJK Ext A
        0x4E00..0x9FFF,     // CJK Unified Ideographs
        0xAC00..0xD7AF,     // Hangul syllables
        0xF900..0xFAFF,     // CJK Compatibility Ideographs
        0xFE30..0xFE4F,     // CJK Compatibility Forms
        0xFF00..0xFFEF,     // Halfwidth / Fullwidth Forms
        0x20000..0x2FA1F,   // CJK Ext B..F（代理对）
    )

    fun isCjkCodePoint(codePoint: Int): Boolean = CJK_RANGES.any { codePoint in it }

    /** 第 i 个位置（可能是代理对的高位）是否 CJK。 */
    private fun cjkAt(s: CharSequence, i: Int): Boolean {
        val ch = s[i]
        if (isCjkCodePoint(ch.code)) return true
        if (ch.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
            return isCjkCodePoint(Character.toCodePoint(ch, s[i + 1]))
        }
        return false
    }

    fun scan(src: String): List<CjkLiteralHit> {
        val hits = ArrayList<CjkLiteralHit>()
        val n = src.length
        var i = 0
        var line = 1
        var state = "CODE"
        var interp = 0            // 处于 ${...} 内时的花括号深度
        var returnState = "CODE"  // 插值结束时回到哪个字面量状态
        val buf = StringBuilder()
        var cjkLine = 0

        fun flush() {
            if (cjkLine != 0) {
                hits.add(CjkLiteralHit(cjkLine, buf.toString().replace('\n', ' ').take(120)))
            }
            buf.setLength(0)
            cjkLine = 0
        }

        fun resetLiteral() {
            buf.setLength(0)
            cjkLine = 0
        }

        fun append(ch: Char, at: Int) {
            if (cjkLine == 0 && cjkAt(src, at)) cjkLine = line
            buf.append(ch)
        }

        while (i < n) {
            val ch = src[i]
            val next = if (i + 1 < n) src[i + 1] else '\u0000'

            if (state == "CODE") {
                if (ch == '/' && next == '/') { state = "LINE"; i += 2; continue }
                if (ch == '/' && next == '*') { state = "BLOCK"; i += 2; continue }
                if (interp > 0) {
                    if (ch == '{') {
                        interp++
                    } else if (ch == '}') {
                        interp--
                        if (interp == 0) {
                            state = returnState
                            returnState = "CODE"
                            i++
                            continue
                        }
                    }
                }
                if (ch == '"' && i + 2 < n && src[i + 1] == '"' && src[i + 2] == '"') {
                    state = "RAW"; resetLiteral(); i += 3; continue
                }
                if (ch == '"') { state = "STRING"; resetLiteral(); i++; continue }
                if (ch == '\'') { state = "CHAR"; resetLiteral(); i++; continue }
                if (ch == '\n') line++
                i++
                continue
            }

            if (state == "LINE") {
                if (ch == '\n') { line++; state = "CODE" }
                i++
                continue
            }

            if (state == "BLOCK") {
                if (ch == '*' && next == '/') { state = "CODE"; i += 2; continue }
                if (ch == '\n') line++
                i++
                continue
            }

            // ---- 字面量内部 ----
            if (ch == '$' && next == '{' && (state == "STRING" || state == "RAW")) {
                flush()
                returnState = state
                state = "CODE"
                interp = 1
                i += 2
                continue
            }
            if (ch == '\\' && (state == "STRING" || state == "CHAR")) {
                // 转义：`\"` 不是字符串结束，`\n` 也不是换行
                if (i + 1 < n) {
                    if (cjkLine == 0 && cjkAt(src, i + 1)) cjkLine = line
                    buf.append(src[i + 1])
                }
                i += 2
                continue
            }
            if (state == "STRING" && ch == '"') { flush(); state = "CODE"; i++; continue }
            if (state == "RAW" && ch == '"' && i + 2 < n && src[i + 1] == '"' && src[i + 2] == '"') {
                flush(); state = "CODE"; i += 3; continue
            }
            if (state == "CHAR" && ch == '\'') { flush(); state = "CODE"; i++; continue }
            if (ch == '\n') line++
            append(ch, i)
            i++
        }
        flush()
        return hits
    }
}

// ---------------------------------------------------------------------------
// 扫描范围与基线
// ---------------------------------------------------------------------------

class CjkGateConfig(
    val sourceRoot: File,
    /** 相对**仓库根**的生产源码根（变量名曾是 scaffoldDir —— 代码早期住在 scaffold/ 下，
     *  目录重组后已搬到仓库根；值就是 projectDir）。刻意不含 core/ 与 agentNode/：它们的中文是异常/日志/CLI 文本， */
    /** 且本轮由其他通道在改；纳入会得到一个噪声基线而不是一条文案门禁。 */
    val scanRoots: List<String>,
    val baselineFile: File,
)

object CjkGate {
    const val HEADER = "# P2-21 CJK 字面量基线（棘轮：只允许减少，不允许增加）"

    /** 生产源码？排除测试、截图测试与预览假数据。 */
    fun isProductionSource(relativePath: String): Boolean {
        val p = "/$relativePath"
        val excludedDirs = listOf(
            "/build/",
            "/screenshotTest/", "/screenshotTestDebug/", "/screenshotTestRelease/",
            "/commonTest/", "/jvmTest/", "/androidUnitTest/", "/androidInstrumentedTest/",
            "/androidTest/", "/test/",
        )
        if (excludedDirs.any { p.contains(it) }) return false
        val name = relativePath.substringAfterLast('/')
        if (name.endsWith("Test.kt")) return false
        // PreviewState.kt 是 @Preview 专用的假数据，不是生产路径（与截图测试同类）。
        if (name == "PreviewState.kt") return false
        return true
    }

    /** 返回 (相对路径 → 命中行列表)，只含真正有 CJK 字面量的文件。 */
    fun scan(config: CjkGateConfig): Map<String, List<CjkLiteralHit>> {
        val result = LinkedHashMap<String, List<CjkLiteralHit>>()
        config.scanRoots.forEach { rootName ->
            val root = File(config.sourceRoot, rootName)
            if (!root.isDirectory) return@forEach
            root.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".kt") }
                .sortedBy { it.absolutePath }
                .forEach { file ->
                    val relative = file.relativeTo(config.sourceRoot).invariantSeparatorsPath
                    if (!isProductionSource(relative)) return@forEach
                    val hits = KotlinCjkLexer.scan(file.readText())
                    if (hits.isNotEmpty()) result[relative] = hits
                }
        }
        return result
    }

    /** 基线：相对路径 → 允许条数。 */
    fun readBaseline(file: File): Map<String, Int> {
        if (!file.isFile) {
            throw GradleException(
                "缺少基线文件 ${file.path}。先跑 `./gradlew :cjkLiteralGateBaseline` 生成。",
            )
        }
        val map = LinkedHashMap<String, Int>()
        file.readLines().forEach { raw ->
            val text = raw.trim()
            if (text.isEmpty() || text.startsWith("#")) return@forEach
            val parts = text.split('\t')
            if (parts.size != 2) {
                throw GradleException("基线文件格式错误（应为 `<count>\\t<path>`）：$raw")
            }
            val count = parts[0].trim().toIntOrNull()
                ?: throw GradleException("基线文件条数不是整数：$raw")
            map[parts[1].trim()] = count
        }
        return map
    }

    fun writeBaseline(file: File, current: Map<String, List<CjkLiteralHit>>, note: String) {
        val total = current.values.sumOf { it.size }
        val body = buildString {
            appendLine(HEADER)
            appendLine("#")
            appendLine("# 格式: <允许条数>\\t<相对仓库根的路径>")
            appendLine("# 重新生成: ./gradlew :cjkLiteralGateBaseline")
            appendLine("# 生成时机: $note")
            appendLine("# 命中总数: $total（文件数 ${current.size}）")
            appendLine("#")
            current.entries.sortedBy { it.key }.forEach { (path, hits) ->
                appendLine("${hits.size}\t$path")
            }
        }
        file.parentFile.mkdirs()
        file.writeText(body)
    }
}

// ---------------------------------------------------------------------------
// 任务注册
// ---------------------------------------------------------------------------

val cjkBaselineFile = File(project.projectDir, "tools/cjk-literal-baseline.txt")
val cjkConfig = CjkGateConfig(
    sourceRoot = project.projectDir,
    scanRoots = listOf(
        "composeApp/src/commonMain",
        "composeApp/src/androidMain",
        "composeApp/src/iosMain",
        "composeApp/src/jvmMain",
        "desktopApp/src/main",
    ),
    baselineFile = cjkBaselineFile,
)

fun currentWorkspaceNote(): String {
    val head = try {
        val proc = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
            .directory(project.projectDir).redirectErrorStream(true).start()
        proc.inputStream.bufferedReader().readText().trim().ifEmpty { "unknown" }
    } catch (_: Exception) {
        "unknown"
    }
    val dirty = try {
        val proc = ProcessBuilder("git", "status", "--porcelain")
            .directory(project.projectDir).redirectErrorStream(true).start()
        if (proc.inputStream.bufferedReader().readText().isBlank()) "clean" else "dirty"
    } catch (_: Exception) {
        "unknown"
    }
    return "HEAD=$head ($dirty), scope=${cjkConfig.scanRoots.joinToString(",")}"
}

tasks.register("cjkLiteralGate") {
    group = "verification"
    description = "P2-21：生产 Kotlin 源码里不允许新增硬编码 CJK 字面量（与基线比对）"
    doLast {
        // -PcjkGateBaseline=<path> 可换一份基线跑（用于演示/复现失败路径，不动仓库里的基线）。
        val baselineFile = (project.findProperty("cjkGateBaseline") as? String)
            ?.let { File(it) } ?: cjkConfig.baselineFile
        val current = CjkGate.scan(cjkConfig)
        val baseline = CjkGate.readBaseline(baselineFile)
        val currentTotal = current.values.sumOf { it.size }
        val baselineTotal = baseline.values.sum()

        logger.lifecycle("CJK 字面量门禁 (P2-21)")
        logger.lifecycle("  工作树: ${currentWorkspaceNote()}")
        logger.lifecycle("  扫描根: ${cjkConfig.scanRoots.joinToString(", ")}")
        logger.lifecycle("  基线文件: ${baselineFile.path}")
        logger.lifecycle("  当前 $currentTotal 处 / 基线 $baselineTotal 处")
        logger.lifecycle("  命中文件清单（当前 / 基线 / 差）:")

        val rows = sortedSetOf<String>().apply {
            addAll(current.keys)
            addAll(baseline.keys)
        }
        val newViolations = ArrayList<String>()
        val improvements = ArrayList<String>()

        rows.forEach { path ->
            val now = current[path]?.size ?: 0
            val was = baseline[path] ?: 0
            val delta = now - was
            val mark = when {
                delta > 0 -> "新增"
                delta < 0 -> "减少"
                now == 0 -> "已清零"
                else -> "持平"
            }
            logger.lifecycle("    %4d  %4d  %+4d  %-6s %s".format(now, was, delta, mark, path))
            if (delta > 0) {
                val extra = current.getValue(path)
                extra.forEachIndexed { index, hit ->
                    if (index >= was) newViolations.add("$path:${hit.line}: \"${hit.snippet}\"")
                }
            } else if (delta < 0) {
                improvements.add("$path: $now（基线 $was，可下调）")
            }
        }

        if (improvements.isNotEmpty()) {
            logger.lifecycle("  可下调基线（资源化已经做在前面的文件）:")
            improvements.forEach { logger.lifecycle("    - $it") }
        }

        val stale = baseline.keys - current.keys
        if (stale.isNotEmpty()) {
            logger.lifecycle("  基线里已不存在的文件（可清理）:")
            stale.forEach { logger.lifecycle("    - $it") }
        }

        if (newViolations.isNotEmpty()) {
            if (newViolations.size > 200) {
                logger.lifecycle("  ... 省略 ${newViolations.size - 200} 条")
            }
            val shown = newViolations.take(200)
            throw GradleException(
                buildString {
                    appendLine("CJK 字面量门禁失败：新增 ${newViolations.size} 处硬编码中文文案。")
                    appendLine("用户可见文案应放进 composeResources/values*/strings.xml（见 AdaptiveScaffold.kt 的 StringResource-in-enum 写法）。")
                    appendLine("确实不是用户可见文案（日志/异常）时，才用 `./gradlew :cjkLiteralGateBaseline` 有意下调/更新基线。")
                    appendLine()
                    shown.forEach { appendLine("  $it") }
                },
            )
        }
        logger.lifecycle("  结果: 通过（当前 $currentTotal = 基线 $baselineTotal；存量清单见上，只降不升 —— 新增一处即失败）")
    }
}

tasks.register("cjkLiteralGateBaseline") {
    group = "verification"
    description = "重新生成 P2-21 基线文件（棘轮调整，须在 PR 里有理由）"
    doLast {
        val current = CjkGate.scan(cjkConfig)
        val old = if (cjkConfig.baselineFile.isFile) CjkGate.readBaseline(cjkConfig.baselineFile) else emptyMap()
        val newTotal = current.values.sumOf { it.size }
        val oldTotal = old.values.sum()
        CjkGate.writeBaseline(cjkConfig.baselineFile, current, currentWorkspaceNote())
        logger.lifecycle("已写入 ${cjkConfig.baselineFile.path}")
        logger.lifecycle("  基线总数: $oldTotal → $newTotal（文件数 ${current.size}）")
        if (newTotal > oldTotal) {
            logger.warn("警告：基线总数上升了 ${newTotal - oldTotal} 处。棘轮只应在确有新增且经评审时上调。")
        }
    }
}

tasks.register("cjkLiteralGateSelfTest") {
    group = "verification"
    description = "P2-21：用内存样本自检 CJK 词法器（证明门禁不是空转）"
    doLast {
        // 每例: (名字, 源码, 期望命中数)
        val cases = listOf(
            Triple("行注释里的中文不算", "val a = 1 // 这是注释\n", 0),
            Triple("块注释里的中文不算", "/* 中文块注释 */\nval a = 1\n", 0),
            Triple("KDoc 里的中文不算", "/**\n * 中文文档\n */\nval a = 1\n", 0),
            Triple("KDoc 代码样例里的字符串不算", "/**\n * 例：`val s = \"中文\"`\n */\nval a = 1\n", 0),
            Triple("注释与代码同行：只有字面量算", "val a = \"中文\" // 中文注释\n", 1),
            Triple("普通字符串里的中文算", "val a = \"确定\"\n", 1),
            Triple("原始字符串里的中文算", "val a = \"\"\"多行\n中文\"\"\"\n", 1),
            Triple("字符字面量里的中文算", "val a = '中'\n", 1),
            Triple("转义引号不结束字符串", "val a = \"他说：\\\"中文\\\"\"\n", 1),
            Triple("插值里嵌套字符串算", "val a = \"x\${listOf(\"中文\")[0]}\"\n", 1),
            Triple("插值外的中文算", "val a = \"中文 \${1 + 1}\"\n", 1),
            Triple("全角标点算", "val a = \"（已完成）\"\n", 1),
            Triple("纯 ASCII 字符串不算", "val a = \"save\"\n", 0),
            Triple("非 CJK 的下标/路径不算", "val a = \"relay/downloads\"\n", 0),
            Triple("注释掉的字符串不算", "// val a = \"中文\"\nval b = 2\n", 0),
            Triple("原始字符串里的 // 不是注释", "val a = \"\"\"http://中文\"\"\"\n", 1),
            Triple("多行原始字符串行号正确", "val a = \"\"\"\n行\n中文\"\"\"\n", 1),
            Triple("字符串里的花括号不干扰插值配平", "val a = \"{中文}\"\n", 1),
        )
        var failures = 0
        cases.forEach { (name, src, expected) ->
            val actual = KotlinCjkLexer.scan(src).size
            val ok = actual == expected
            if (!ok) failures++
            logger.lifecycle("  ${if (ok) "PASS" else "FAIL"}  $name（期望 $expected，实际 $actual）")
        }

        // 行号断言：多行原始字符串里，命中的行应是 CJK 所在行（第 3 行）
        val lineCase = KotlinCjkLexer.scan("val a = \"\"\"\nline one\nline two 中文\n\"\"\"\n")
        val lineOk = lineCase.size == 1 && lineCase[0].line == 3
        if (!lineOk) failures++
        logger.lifecycle("  ${if (lineOk) "PASS" else "FAIL"}  命中行号指向 CJK 所在行（期望 3，实际 ${lineCase.firstOrNull()?.line}）")

        // 排除规则断言
        val exclusionCases = listOf(
            "composeApp/src/commonMain/kotlin/app/relay/ui/A.kt" to true,
            "composeApp/src/commonMain/kotlin/app/relay/ui/AFeatureTest.kt" to false,
            "composeApp/src/commonTest/kotlin/B.kt" to false,
            "composeApp/src/jvmTest/kotlin/B.kt" to false,
            "androidApp/src/screenshotTest/kotlin/Preview.kt" to false,
            "composeApp/src/commonMain/kotlin/app/relay/ui/desktop/PreviewState.kt" to false,
            "desktopApp/src/test/kotlin/C.kt" to false,
        )
        exclusionCases.forEach { (path, expected) ->
            val actual = CjkGate.isProductionSource(path)
            val ok = actual == expected
            if (!ok) failures++
            logger.lifecycle("  ${if (ok) "PASS" else "FAIL"}  生产文件判定 $path → $actual（期望 $expected）")
        }

        if (failures > 0) throw GradleException("CJK 门禁自检失败：$failures 例不符")
        logger.lifecycle("自检通过：${cases.size + 1 + exclusionCases.size} 例全绿")
    }
}
