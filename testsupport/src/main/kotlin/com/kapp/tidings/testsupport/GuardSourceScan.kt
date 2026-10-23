// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.testsupport

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
object GuardSourceScan {

    /** 从 Gradle 的测试工作目录向上找 settings.gradle.kts，避免写死相对层级。 */
    fun scaffoldRoot(): File {
        var dir: File? = File("").absoluteFile.normalize()
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile && File(dir, "composeApp").isDirectory) return dir
            dir = dir.parentFile
        }
        throw AssertionError("找不到 scaffold 根目录（工作目录 " + File("").absolutePath + "）")
    }

    /**
     * 把「相对仓库根的路径」解析成真实文件，**跨模块回退**。
     *
     * ## 为什么需要回退
     *
     * 这些守卫要同时读**领域侧**与**UI 侧**的源码（纪律本身就是"两处必须一致"）。
     * 领域层搬进 `:app` 之后，同一条纪律的两端不再住在同一个前缀下 ——
     * 写成 `composeApp/src/commonMain/kotlin/com/kapp/tidings/app/X.kt` 的那一半会读不到。
     *
     * 回退规则只有一条、且方向唯一：`composeApp/src/<kind>/kotlin/com/kapp/tidings/app|db|platform|navigation/…`
     * ⇒ `app/src/<kind>/kotlin/com/kapp/tidings/…`。它正好是这次拆模块的映射，不含猜测。
     *
     * **读不到时把两个候选都报出来** —— 否则症状是「文件不存在」，而真正的问题是
     * "这个文件又搬去别的模块了"，下一个人得自己猜。
     */
    fun resolve(relativePath: String): File {
        val base = scaffoldRoot()
        val direct = File(base, relativePath)
        if (direct.isFile) return direct
        // 路径写错了要**说出来**，不要替他猜。
        //
        // 这里曾经有一段跨模块回退：`composeApp/.../app/X.kt` 找不到时自动改试
        // `app/.../app/X.kt`。它本意是"搬模块时少改几处"，实际后果更糟 ——
        // 有 6 处守卫的路径在拆模块后就已过期，而这个回退把它们**静默修好了**。
        // 于是"路径写错"这件事永远不会被发现，直到某天领域层再搬一次、回退链断掉，
        // 才会一次性炸出 6 个"源码读不到"。
        //
        // 更危险的是它掩盖了判断：一个本该写 `app/src/.../QrPairing.kt` 的守卫
        // 写着 `composeApp/src/...` 仍然绿着，读的人会以为那个文件还在 UI 侧。
        //
        // 现在改成：找不到就报错，并把"最可能的正确路径"写进消息里（只作提示）。
        val hint = if (relativePath.startsWith("composeApp/src/")) {
            relativePath.replaceFirst("composeApp/src/", "app/src/")
        } else {
            null
        }
        throw AssertionError(
            "源码读不到：" + relativePath +
                (if (hint != null) "。领域层已搬进 :app，你要找的多半是：" + hint else "") +
                "。请把守卫里的路径改对 —— 回退层已删除，因为它曾静默修好 6 处过期路径，" +
                "让「路径写错」永远不被发现。",
        )
    }

    /** 读一个相对仓库根的源码文件。 */
    fun read(relativePath: String): String = resolve(relativePath).readText()

    /**
     * 解析一个相对仓库根的**目录**（目录不是文件，所以不走 [resolve] 的 `isFile` 判断）。
     *
     * 不存在时直接报错：门禁/守卫遍历一个不存在的目录会得到空集合，
     * 「没有 offender」于是恒真 —— 这是本仓源码门禁最常见的假绿形态。
     */
    fun resolveDir(relativePath: String): File {
        val dir = File(scaffoldRoot(), relativePath)
        if (!dir.isDirectory) {
            throw AssertionError(
                "目录读不到：" + relativePath + "（仓库根 " + scaffoldRoot().absolutePath + "）。" +
                    "遍历不存在的目录会得到空集合，守卫会静默通过 —— 所以这里直接报错。",
            )
        }
        return dir
    }



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
