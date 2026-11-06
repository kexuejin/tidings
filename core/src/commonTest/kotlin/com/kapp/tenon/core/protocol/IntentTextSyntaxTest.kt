// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.decodeFromJsonElement

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


/** T-G6b：`tools` 键（D-034）—— 白名单 + 载荷往返 + 缺省缺席（AGENTS §4 测试走解码）。 */
class ChannelToolsKeyTest {

    private fun wire(withTools: Boolean): JsonObject {
        val payload = Codec.channelPayload(
            channelId = "ch-1", op = ChannelOps.ASSIGN, fromAgent = "a", task = "t",
            targetTag = "gpu", deadlineMs = 60_000,
            tools = if (withTools) listOf("read_file", "run_command") else null,
        )
        return payload.jsonObject
    }

    @Test
    fun toolsKeyWhitelistedAndRoundTrips() {
        val o = wire(withTools = true)
        assertTrue("tools" in o, "载荷带 tools 键")
        assertEquals(true, CodecWire.validatePayload(MsgTypes.AGENT_CHANNEL, o, Int.MAX_VALUE), "白名单放行")
        val back = Codec.wireJson.decodeFromJsonElement(AgentChannelPayload.serializer(), o)
        assertEquals(listOf("read_file", "run_command"), back.tools)
    }

    @Test
    fun absentToolsStaysOffTheWireAndValidates() {
        val o = wire(withTools = false)
        assertTrue("tools" !in o, "null 键不上线（encodeDefaults=false 同一哲学）")
        assertEquals(true, CodecWire.validatePayload(MsgTypes.AGENT_CHANNEL, o, Int.MAX_VALUE))
    }

    @Test
    fun malformedToolShapeIsRejectedAtBuildTime() {
        try {
            Codec.channelPayload(
                channelId = "ch-1", op = ChannelOps.ASSIGN, fromAgent = "a", task = "t",
                deadlineMs = 60_000, tools = listOf("Bad Tool"),
            )
            error("应该抛")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("tools"))
        }
    }
}
