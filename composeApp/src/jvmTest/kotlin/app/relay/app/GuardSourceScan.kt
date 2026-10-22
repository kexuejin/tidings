// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import java.io.File

/**
 * 源码级守卫的公共工具：读源码文本、剥注释、按平衡括号/花括号取一段调用。
 *
 * ## 为什么这几条纪律只能在源码层守
 *
 * 本仓没有 Compose UI 测试基建（没有 `ui-test` 依赖、没有 `createComposeRule`），
 * 而这几条纪律全在 Composable 内部："这颗按钮画不画"、"破坏性动作有没有前置确认"。
 * 单测够不到它们，只能在源码文本上钉住 —— 与 [PairingEntryPointsGuardTest] /
 * [SystemBarsInsetGuardTest] 同一取舍。
 *
 * ## 两条纪律（本仓各踩过一次）
 *
 * 1. **先剥注释再匹配**。注释里正当提到那个函数名或那段旧代码，守卫会被自己的注释
 *    绊倒 —— 例如「清空全部」那条纪律的旧写法就出现在 `App.kt` 的注释里。
 * 2. **按平衡括号取调用块，而不是全文 contains**。"文件里出现过某个标识符"区分不出
 *    对错：那个回调可能挂在别处。要问的是"**这颗按钮**的 onClick 里有没有它"。
 */
internal object GuardSourceScan {

    /** 从 Gradle 的测试工作目录向上找 settings.gradle.kts，避免写死相对层级。 */
    fun scaffoldRoot(): File {
        var dir: File? = File("").absoluteFile.normalize()
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile && File(dir, "composeApp").isDirectory) return dir
            dir = dir.parentFile
        }
        throw AssertionError("找不到 scaffold 根目录（工作目录 " + File("").absolutePath + "）")
    }

    /** 读一个相对 scaffold 根的源码文件。 */
    fun read(relativePath: String): String = File(scaffoldRoot(), relativePath).readText()

    /**
     * 剥掉 `//` 行注释与 `/* */` 块注释，**保留换行**（不保留的话后续的偏移量都对不上，
     * 报错时也没法定位）。字符串字面量整段照抄 —— 否则串里的 `https://…` 会被当成
     * 行注释，把那一行后面的活代码一起抹掉。
     */
    fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val next = text.getOrNull(i + 1)
            when {
                c == '/' && next == '/' -> {
                    while (i < text.length && text[i] != '\n') {
                        out.append(' ')
                        i++
                    }
                }
                c == '/' && next == '*' -> {
                    out.append("  ")
                    i += 2
                    while (i < text.length && !(text[i] == '*' && text.getOrNull(i + 1) == '/')) {
                        out.append(if (text[i] == '\n') '\n' else ' ')
                        i++
                    }
                    if (i < text.length) {
                        out.append("  ")
                        i += 2
                    }
                }
                // 字符串字面量整段照抄：不这样做的话，串里的 `https://…` 会被当成行注释，
                // 那一行后面的**活代码**会被抹掉 —— 守卫就会漏掉那一行上的空 onClick。
                c == '"' -> {
                    val triple = text.getOrNull(i + 1) == '"' && text.getOrNull(i + 2) == '"'
                    val quotes = if (triple) 3 else 1
                    repeat(quotes) { out.append('"') }
                    i += quotes
                    while (i < text.length) {
                        if (text[i] == '\\' && !triple && i + 1 < text.length) {
                            out.append(text[i]).append(text[i + 1])
                            i += 2
                            continue
                        }
                        if (text[i] == '"' && (!triple || (text.getOrNull(i + 1) == '"' && text.getOrNull(i + 2) == '"'))) {
                            repeat(quotes) { out.append('"') }
                            i += quotes
                            break
                        }
                        out.append(text[i])
                        i++
                    }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /** 取 `marker` 之后第一对平衡圆括号里的内容（`marker` 必须以 `(` 结尾）。 */
    fun parenBlockAfter(text: String, marker: String, fromIndex: Int = 0): String {
        val at = text.indexOf(marker, fromIndex)
        if (at < 0) throw AssertionError("源码里找不到调用：$marker")
        val open = at + marker.length - 1
        if (text.getOrNull(open) != '(') throw AssertionError("marker 必须以 '(' 结尾：$marker")
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return text.substring(open + 1, i)
                }
            }
        }
        throw AssertionError("圆括号没有闭合：$marker")
    }

    /** 取 `marker` 之后第一对平衡花括号里的内容（`marker` 自身不含花括号）。 */
    fun bracedBlockAfter(text: String, marker: String): String {
        val at = text.indexOf(marker)
        if (at < 0) throw AssertionError("源码里找不到标记：$marker")
        val open = text.indexOf('{', at)
        if (open < 0) throw AssertionError("标记后面没有花括号：$marker")
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open + 1, i)
                }
            }
        }
        throw AssertionError("花括号没有闭合：$marker")
    }

    /** 列出若干源码根下的全部 `.kt`（跳过 `build/` 产物目录），返回相对 scaffold 根的路径。 */
    fun kotlinFiles(vararg sourceRoots: String): List<String> {
        val root = scaffoldRoot()
        return sourceRoots.flatMap { relativeRoot ->
            val dir = File(root, relativeRoot)
            if (!dir.isDirectory) emptyList()
            else dir.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".kt") && it.path.contains("${File.separator}build${File.separator}").not() }
                .map { it.relativeTo(root).path.replace(File.separatorChar, '/') }
                .toList()
        }.sorted()
    }
}
