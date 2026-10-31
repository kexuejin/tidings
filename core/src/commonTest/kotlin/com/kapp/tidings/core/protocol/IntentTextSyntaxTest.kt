// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

/** T-G2 ②：`require:` 文本语法解析（派单指令 vs 聊天内容的分界）。 */
class IntentTextSyntaxTest {

    @Test
    fun trailingRequireBlockIsExtractedAndStripped() {
        val (text, tags) = IntentTextSyntax.parse("@Architect 重构网络层 require:gpu,linux")
        assertEquals("@Architect 重构网络层", text, "指令本体从正文剥掉")
        assertEquals(listOf("gpu", "linux"), tags)
    }

    @Test
    fun modelColonTagSurvivesTheSyntax() {
        val (text, tags) = IntentTextSyntax.parse("跑回归 require:model:deepseek-v4.1,gpu")
        assertEquals("跑回归", text)
        assertEquals(listOf("model:deepseek-v4.1", "gpu"), tags)
    }

    @Test
    fun plainTextWithoutMarkerIsUntouched() {
        assertEquals("正常聊天 require" to emptyList(), IntentTextSyntax.parse("正常聊天 require"))
        assertEquals("提到 require: 但是普通文本" to emptyList(), IntentTextSyntax.parse("提到 require: 但是普通文本"))
    }

    @Test
    fun midSentenceRequireIsPlainText() {
        // 只认末尾段：正文中间的 require: 是普通文本。
        val original = "我们 require: 三个条件，然后开工"
        assertEquals(original to emptyList(), IntentTextSyntax.parse(original))
    }

    @Test
    fun trailingHostDirectiveIsExtractedAndStripped() {
        val (text, hostRef) = IntentTextSyntax.parseHost("@Codex 排查环境 +host:Alice-Mac")
        assertEquals("@Codex 排查环境", text, "宿主指令从正文剥掉")
        assertEquals("Alice-Mac", hostRef)
    }

    @Test
    fun hostDirectiveWithoutMarkerOrMalformedIsPlainText() {
        assertEquals("正常聊天" to null, IntentTextSyntax.parseHost("正常聊天"))
        val original = "提到 +host: 但是普通文本"
        assertEquals(original to null, IntentTextSyntax.parseHost(original))
        // 非法字符（空格）= 不认，整段保留。
        val bad = "派给 +host:Alice Mac 去做"
        assertEquals(bad to null, IntentTextSyntax.parseHost(bad))
    }

    @Test
    fun malformedTagsKeepTheWholeText() {
        // 形状不合法（大写）⇒ 宁可不路由也不猜：整段按普通文本保留。
        val original = "派活 require:GPU"
        assertEquals(original to emptyList(), IntentTextSyntax.parse(original))
    }
}
