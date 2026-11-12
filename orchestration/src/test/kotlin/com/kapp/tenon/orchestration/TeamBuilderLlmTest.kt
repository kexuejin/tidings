// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [TeamBuilderLlm] 的判据：null 语义（无 key / 空词汇表 / HTTP 失败 / 响应畸形）与
 * 原样透传（门裁决在 TeamScaffoldParser，这里只负责把 content 拿回来）。
 */
class TeamBuilderLlmTest {

    private val leaders = setOf("dsh", "codex")
    private val drivers = setOf("dsh", "codex", "claude")

    private fun openAiResponse(content: String): String = """
        {"choices":[{"message":{"role":"assistant","content":"${content.replace("\"", "\\\"")}"}}]}
    """.trimIndent()

    @Test
    fun blankNeedOrEmptyVocabRefusesBeforeAnyHttpCall() {
        var called = false
        val http: (String, String, String, Long) -> String? = { _, _, _, _ ->
            called = true; null
        }
        assertNull(TeamBuilderLlm.draft("", leaders, drivers, apiKey = "k", http = http))
        assertNull(TeamBuilderLlm.draft("need", emptySet(), drivers, apiKey = "k", http = http))
        assertNull(TeamBuilderLlm.draft("need", leaders, emptySet(), apiKey = "k", http = http))
        assertTrue(!called, "结构性拒绝必须发生在任何网络调用之前")
    }

    @Test
    fun missingApiKeyRefusesWithoutHttpCall() {
        var called = false
        val http: (String, String, String, Long) -> String? = { _, _, _, _ ->
            called = true; null
        }
        assertNull(TeamBuilderLlm.draft("need", leaders, drivers, apiKey = "  ", http = http))
        assertNull(TeamBuilderLlm.draft("need", leaders, drivers, apiKey = null, http = http))
        assertTrue(!called, "无 key 是零成本判断，不许打一次网才失败")
    }

    @Test
    fun happyPathReturnsRawContentForTheParser() {
        val payload = "{\"teamName\":\"T\",\"leader\":\"dsh\",\"members\":[]}"
        var seenEndpoint: String? = null
        var seenKey: String? = null
        var seenBody: String? = null
        val http: (String, String, String, Long) -> String? = { endpoint, key, body, _ ->
            seenEndpoint = endpoint
            seenKey = key
            seenBody = body
            openAiResponse(payload)
        }
        assertEquals(payload, TeamBuilderLlm.draft("need", leaders, drivers, apiKey = "k", http = http))
        assertEquals("https://api.deepseek.com/chat/completions", seenEndpoint)
        assertEquals("k", seenKey)
        assertTrue(seenBody!!.contains("\"json_object\""), "响应格式钉 json_object")
        assertTrue(seenBody!!.contains("worker|reviewer"), "system prompt 带产出 schema")
    }

    @Test
    fun httpFailureAndMalformedResponseDegradeToNull() {
        assertNull(
            TeamBuilderLlm.draft("need", leaders, drivers, apiKey = "k") { _, _, _, _ -> null },
            "非 2xx = null（调用方回落模板）",
        )
        assertNull(
            TeamBuilderLlm.draft("need", leaders, drivers, apiKey = "k") { _, _, _, _ ->
                throw java.net.SocketTimeoutException("t")
            },
            "超时/异常 = null，不外抛",
        )
        assertNull(
            TeamBuilderLlm.draft("need", leaders, drivers, apiKey = "k") { _, _, _, _ -> "not json" },
            "响应畸形 = null",
        )
        assertNull(
            TeamBuilderLlm.draft("need", leaders, drivers, apiKey = "k") { _, _, _, _ ->
                openAiResponse("")
            },
            "空 content = null",
        )
    }
}
