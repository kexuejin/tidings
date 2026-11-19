// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.guards

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 「@所有人」的**剥离集与词条同源**（真机 UX 轮 2026-11-19 的修复）。
 *
 * 住在 `:composeApp` 的 guards 包而不是 `:app`：本守卫同时读领域侧的
 * `RelayController` 与 UI 侧的 strings.xml，只有 `:composeApp` 能同时读两边；
 * 写在 `:app` 里只能靠相对路径侥幸成立，换工作目录就静默失效。
 * `GuardsLiveInTheUpperLayerTest` 钉住这条边界。
 *
 * 剥离常量 `MENTION_ALL_TOKENS` 住在 `RelayController`（领域层）；词条
 * `chat_mention_all` 住在 composeApp 的各 locale strings.xml（UI 层）。两边各写
 * 一份就会各自腐坏：词条加了第三种语言、剥离集没跟上 —— 那种语言的「@所有人」
 * 又会漏进工单标题（本修复要拔的正是这根刺）。
 *
 * 判据：**每个 locale 的词条值（ASCII 归一小写）都必须在剥离集里**；剥离集里
 * 不存在任何 locale 都没有的孤儿 token。comparing 用源码文本，不引资源编译 ——
 * 与本仓其它源码级守卫同一取舍。
 */
class GroupMentionAllTokenParityTest {

    private val stringsZho = GuardSourceScan.read(
        "composeApp/src/commonMain/composeResources/values/strings.xml",
    )
    private val stringsEn = GuardSourceScan.read(
        "composeApp/src/commonMain/composeResources/values-en/strings.xml",
    )
    private val controller = GuardSourceScan.stripComments(
        GuardSourceScan.read(
            "app/src/commonMain/kotlin/com/kapp/tenon/domain/RelayController.kt",
        ),
    )

    private val constantTokens: Set<String> by lazy {
        val block = controller
            .substringAfter("MENTION_ALL_TOKENS = setOf(")
            .substringBefore(")")
        Regex("\"([^\"]+)\"").findAll(block).map { it.groupValues[1] }.toSet()
    }

    private fun valueOf(xml: String): String {
        val marker = "<string name=\"chat_mention_all\">"
        val at = xml.indexOf(marker)
        assertTrue(at >= 0, "strings.xml 里没有 chat_mention_all 词条（键改名了？同步守卫与本修复）")
        return xml.substring(at + marker.length, xml.indexOf("</string>", at))
    }

    @Test
    fun everyLocaleValueIsStripped() {
        val zh = valueOf(stringsZho)
        val en = valueOf(stringsEn).lowercase()
        assertTrue(
            zh in constantTokens,
            "zh 词条「$zh」不在剥离集 $constantTokens —— 它会漏进工单标题",
        )
        assertTrue(
            en in constantTokens,
            "en 词条「$en」不在剥离集 $constantTokens —— 它会漏进工单标题",
        )
    }

    @Test
    fun noOrphanTokenInTheStripSet() {
        assertTrue(
            constantTokens.isNotEmpty(),
            "剥离集为空 = @所有人 根本不被剥（修复被整个拔掉了）",
        )
        val locales = setOf(valueOf(stringsZho), valueOf(stringsEn).lowercase())
        val orphans = constantTokens - locales
        assertTrue(
            orphans.isEmpty(),
            "剥离集里的孤儿 token $orphans 没有任何 locale 词条对应 —— 删掉或补词条",
        )
    }
}
