// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent.drivers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 钉住 [queryJson]：用户输入进 JSON 必须经过序列化器，不能字符串插值（task-50 审查修）。
 *
 * 修之前是拼字符串：prompt 里一个双引号就让整串变成非法 JSON，`\",\"x\":\"y` 这种还能注入额外字段。
 * 把实现改回插值，这条会立刻红。
 */
class DsBotDriverJsonTest {

    @Test
    fun aQuoteInThePromptDoesNotBreakTheJson() {
        val json = queryJson("a\"b")
        val parsed = Json.parseToJsonElement(json).jsonObject
        assertEquals("a\"b", parsed["query"]?.jsonPrimitive?.content, "引号必须被正确转义：$json")
    }

    @Test
    fun aBackslashInThePromptSurvivesARoundTrip() {
        val json = queryJson("C:\\work\\repo")
        val parsed = Json.parseToJsonElement(json).jsonObject
        assertEquals("C:\\work\\repo", parsed["query"]?.jsonPrimitive?.content)
    }

    @Test
    fun aPromptTryingToInjectFieldsStaysASingleValue() {
        val hostile = "\",\"admin\":true,\"q\":\""
        val parsed = Json.parseToJsonElement(queryJson(hostile)).jsonObject
        assertEquals(setOf("query"), parsed.keys, "只许有一个 query 键：注入不进去")
        assertEquals(hostile, parsed["query"]?.jsonPrimitive?.content)
    }
}
