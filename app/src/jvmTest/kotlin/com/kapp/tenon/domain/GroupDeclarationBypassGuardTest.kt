// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.testsupport.GuardSourceScan
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 编制写路径**不许有旁路**的源码级守卫。
 *
 * ## 为什么只能在源码层守
 *
 * 「所有编制变更都走 [applyDeclaration]」是三条纪律的合集，类型系统一条都表达不了：
 *
 * 1. **落盘只许一个出口**：`commitGroupMetaUpdate` 之外任何地方只要再写一次
 *    `settings.copy(groups = …)`，那条路就绕过了 rev 推进、token 池校验与审计 ——
 *    症状是「两个入口改同一字段，一个 rev+1、一个不动」，而且编译不报错。
 * 2. **入口只翻译，不拼 patch**：handler 里再出现 `rev = …` / `commitGroupMetaUpdate`
 *    就是把校验又抄了一遍，第三份抄本迟早与另两份不一致（这轮之前就分叉过：
 *    入驻查名字唯一、团队编制查 token 池）。
 * 3. **审计行数可数**：入口等价判据靠「一次成功命令 = 一行审计」，旁路写不产生审计行，
 *    于是判据会静默失真。
 *
 * 守卫剥掉注释再匹配：本仓踩过「守卫被自己的注释绊倒」（注释里写一遍 `copy(groups = …)`
 * 就让白名单多一个文件）。
 */
class GroupDeclarationBypassGuardTest {

    private fun root(): File = GuardSourceScan.scaffoldRoot()

    /** 生产源码根：按模块 × 源集枚举，不靠工作目录猜测。 */
    private fun productionFiles(): List<File> {
        val modules = listOf(
            "core", "app", "composeApp", "agentNode", "orchestration",
            "cli", "desktopApp", "androidApp", "iosApp", "testsupport",
        )
        val sourceSets = listOf(
            "commonMain", "jvmSharedMain", "jvmMain", "androidMain",
            "iosMain", "appleMain", "nativeMain", "main",
        )
        return modules.flatMap { module ->
            sourceSets.mapNotNull { set -> File(root(), "$module/src/$set/kotlin").takeIf { it.isDirectory } }
        }.flatMap { dir ->
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }
    }

    private fun relative(file: File): String = file.absolutePath.removePrefix(root().absolutePath + "/")

    /** 剥注释后含 [needle] 的生产文件（相对路径集合）。 */
    private fun filesContaining(needle: String): Set<String> = productionFiles()
        .filter { GuardSourceScan.stripComments(it.readText()).contains(needle) }
        .map { relative(it) }
        .toSet()

    /**
     * 取出某个顶层扩展函数的函数体（剥注释后按大括号配平）。
     *
     * @return 函数体文本（不含签名）；找不到函数返回 null。
     */
    private fun functionBody(relativePath: String, signature: String): String? {
        val text = GuardSourceScan.stripComments(File(root(), relativePath).readText())
        val start = text.indexOf(signature)
        if (start < 0) return null
        val open = text.indexOf('{', start)
        if (open < 0) return null
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
        return null
    }

    @Test
    fun groupMetaPersistHasASingleWritableEntry() {
        assertEquals(
            setOf(
                "app/src/commonMain/kotlin/com/kapp/tenon/domain/RelayControllerGroupsComponent.kt",
                "app/src/commonMain/kotlin/com/kapp/tenon/domain/GroupDeclarationWritePath.kt",
                "app/src/commonMain/kotlin/com/kapp/tenon/domain/RelayControllerProjectDirectoryComponent.kt",
            ),
            filesContaining("commitGroupMetaUpdate("),
            "群元数据落盘只许三处：定义处（GroupsComponent）、编制命令（GroupDeclarationWritePath）、" +
                "指针投影重建（ProjectDirectory，派生投影不是编制变更）。" +
                "出现第四处 = 又一条绕过 rev/校验/审计的旁路。",
        )
    }

    @Test
    fun settingsGroupsIsNeverRewrittenOutsideTheGroupsComponent() {
        assertEquals(
            setOf("app/src/commonMain/kotlin/com/kapp/tenon/domain/RelayControllerGroupsComponent.kt"),
            filesContaining("copy(groups ="),
            "`settings.groups` 的可写引用只允许建群/改资料这类群元数据操作（GroupsComponent）。" +
                "编制变更一律走 applyDeclaration —— 它经 commitGroupMetaUpdate 落盘。",
        )
    }

    @Test
    fun theThreeEntriesOnlyTranslateTheyDoNotBuildPatches() {
        data class Entry(val file: String, val signature: String)

        val entries = listOf(
            Entry(
                "app/src/commonMain/kotlin/com/kapp/tenon/domain/RelayControllerProjectDirectoryComponent.kt",
                "fun RelayController.handleAddGroupAgent(",
            ),
            Entry(
                "app/src/commonMain/kotlin/com/kapp/tenon/domain/RelayControllerProjectDirectoryComponent.kt",
                "fun RelayController.handleRemoveGroupAgent(",
            ),
            Entry(
                "app/src/commonMain/kotlin/com/kapp/tenon/domain/RelayControllerGroupsComponent.kt",
                "fun RelayController.handleSetGroupTeamDeclaration(",
            ),
        )
        for (entry in entries) {
            val body = functionBody(entry.file, entry.signature)
            assertTrue(body != null, "找不到 ${entry.signature} 的实现 —— 守卫已失效，先修守卫")
            assertTrue(
                body!!.contains("applyDeclaration("),
                "${entry.signature} 必须把意图交给 applyDeclaration，不许自己落盘",
            )
            for (forbidden in listOf("commitGroupMetaUpdate(", "virtualAgents =", "rev = ")) {
                assertFalse(
                    body.contains(forbidden),
                    "${entry.signature} 里出现 `$forbidden` = 又把校验/patch 抄了一遍。" +
                        "入口只负责把界面输入翻译成命令，校验与顺序只许有一份。",
                )
            }
        }
    }
}
