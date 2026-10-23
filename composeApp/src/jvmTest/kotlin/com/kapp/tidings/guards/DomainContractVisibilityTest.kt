// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.guards

import com.kapp.tidings.testsupport.GuardSourceScan

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 「领域层的对外契约不许退回 internal」。
 *
 * 现在 app/ 与 ui/、feature/ 还在同一个模块里，所以 internal 声明被跨包使用
 * 完全合法 —— 这正是最危险的时刻：任何一次重构都可以把某个符号改回 internal，
 * 编译器不会报警，而等到按模块拆开时，断裂会在每个调用点分别报错。
 *
 * 判据：① 声明处没有 internal；② 调用侧真的 import 了它；
 * ③ 反过来，没有任何 UI 调用点的符号必须保持 internal。
 */
class DomainContractVisibilityTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile.normalize()
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("找不到仓库根（工作目录 " + File("").absolutePath + "）")
    }

    /**
     * 读源码。走 [GuardSourceScan.resolve] 的**跨模块回退** —— 领域层搬进 :app 之后，
     * 这些声明所在的文件不再住 `composeApp/` 前缀下。
     * 自己拼路径会让本守卫在搬模块后读不到文件（而读不到的表现是"找不到源码"，
     * 不是"断言失败" —— 前者容易被人当成路径写错而改路径）。
     */
    private fun source(relativePath: String): String = GuardSourceScan.read(relativePath)

    /** 剥掉注释，只在活代码上判断（否则 KDoc 里提到符号名会被自己绊倒）。 */
    private fun live(relativePath: String): String = stripComments(source(relativePath))

    private fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val next = text.getOrNull(i + 1)
            if (c == '/' && next == '/') {
                while (i < text.length && text[i] != '\n') i++
            } else if (c == '/' && next == '*') {
                i += 2
                while (i < text.length && !(text[i] == '*' && text.getOrNull(i + 1) == '/')) i++
                if (i < text.length) i += 2
            } else if (c == '"') {
                out.append(c)
                i++
                while (i < text.length) {
                    if (text[i] == '\\' && i + 1 < text.length) {
                        out.append(text[i]).append(text[i + 1])
                        i += 2
                        continue
                    }
                    out.append(text[i])
                    if (text[i] == '"') { i++; break }
                    i++
                }
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    // 领域层住在 :app。这条曾经写成 `composeApp/...`，靠 GuardSourceScan 里那段
    // 跨模块回退才读到 —— 回退已删除（它静默修好了 6 处同类过期路径）。
    private val appRoot = "app/src/commonMain/kotlin/com/kapp/tidings/domain"

    private val contracts: Map<String, String> = mapOf(
        "MediaKind" to (appRoot + "/MediaKind.kt"),
        "mediaKindOf" to (appRoot + "/MediaKind.kt"),
        "canRecall" to (appRoot + "/ChatInteractions.kt"),
        "recallBlockedReason" to (appRoot + "/ChatInteractions.kt"),
        "recallRemainingMs" to (appRoot + "/ChatInteractions.kt"),
        "formatRecallRemaining" to (appRoot + "/ChatInteractions.kt"),
        "isRecallUrgent" to (appRoot + "/ChatInteractions.kt"),
        "sessionPageKeyOf" to (appRoot + "/RelayControllerAgentComponent.kt"),
        "pkEmojiPreview" to (appRoot + "/RelayControllerPkComponent.kt"),
        "pairingPayload" to (appRoot + "/QrPairing.kt"),
        "manualPeerStatuses" to (appRoot + "/ManualPeerStatus.kt"),
        "agentAttachmentIsImage" to (appRoot + "/model/AgentUi.kt"),
        "agentAttachmentCaption" to (appRoot + "/model/AgentUi.kt"),
    )

    @Test
    fun `对外契约的声明处没有 internal`() {
        val offenders = contracts.entries.mapNotNull { (symbol, path) ->
            val code = live(path)
            val pattern = Regex("internal\\s+((?:[a-z]+\\s+)*)" + Regex.escape(symbol))
            if (pattern.containsMatchIn(code)) symbol else null
        }
        assertTrue(
            offenders.isEmpty(),
            "这些符号已是对外契约，声明处不许再写 internal：" + offenders +
                " —— 改回 internal 在同模块里仍然编译通过，但按模块拆开后会在每个调用点断裂",
        )
    }

    @Test
    fun `对外契约真的被 UI 侧引用`() {
        val roots = listOf(
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui",
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature",
        )
        val uiCode = roots.flatMap { root -> kotlinFiles(root) }
            .joinToString(" ") { live(it) }
        val unused = contracts.keys.filter { symbol ->
            val pattern = Regex("\\b" + Regex.escape(symbol) + "\\b")
            !pattern.containsMatchIn(uiCode)
        }
        assertTrue(
            unused.isEmpty(),
            "这些符号被声明为对外契约，但 ui/feature 里一处都没有用：" + unused +
                " —— 要么调用点已经搬走/删掉（那就不该提权），要么守卫的扫描根漏了",
        )
    }

    @Test
    fun `public 函数返回的类型也必须 public`() {
        // 编译器会强制这一条（'public' function exposes its 'internal' return type），
        // 所以这条守卫守的是「别用别的手段绕过去」（例如把返回值改成 Any? 再强转）。
        // RecallBlock 自己没有 UI 直接 import —— 它只作为 recallBlockedReason 的返回类型存在。
        val code = live(appRoot + "/ChatInteractions.kt")
        assertTrue(
            Regex("enum\\s+class\\s+RecallBlock").containsMatchIn(code),
            "RecallBlock 是 public 函数 recallBlockedReason 的返回类型，必须是 public；" +
                "改回 internal 时编译器会报 'public function exposes its internal return type'",
        )
        assertTrue(
            Regex("fun\\s+recallBlockedReason\\s*\\([^)]*\\)\\s*:\\s*RecallBlock\\?").containsMatchIn(code),
            "recallBlockedReason 的返回类型必须仍是 RecallBlock? —— 换成 Any?/Int 之类会把枚举语义丢给调用方去猜",
        )
    }

    @Test
    fun `没有 UI 调用点的符号必须保持 internal`() {
        // 提权不是越多越好：没有 UI 调用点却提成 public 的符号，等于把内部细节
        // 永久摆到对外接口上。这三条都实测过在 ui/feature 里零引用。
        val code = live(appRoot + "/ChatInteractions.kt")
        val search = live(appRoot + "/RelaySearch.kt")
        val searchPattern = Regex("internal\\s+fun\\s+GlobalSearchScope\\.includes")
        assertTrue(
            searchPattern.containsMatchIn(search),
            "GlobalSearchScope.includes 在 ui/feature 里零引用，必须保持 internal",
        )
        listOf("RECALL_WINDOW_MS", "RECALL_URGENT_MS").forEach { constant ->
            val pattern = Regex("internal\\s+const\\s+val\\s+" + Regex.escape(constant))
            assertTrue(
                pattern.containsMatchIn(code),
                constant + " 必须保持 internal —— 没有 UI 调用点直接读它，提权等于给 UI 留一个自定窗口的口子",
            )
        }
    }

    private fun kotlinFiles(relativeRoot: String): List<String> {
        val root = File(repoRoot(), relativeRoot)
        if (!root.isDirectory) return emptyList()
        val base = repoRoot()
        return root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") && !it.path.contains("/build/") }
            .map { it.relativeTo(base).path.replace(File.separatorChar, '/') }
            .toList()
            .sorted()
    }
}
