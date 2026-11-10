// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * 形态 C 的 MCP Server 冒烟（`scripts/tenon-mcp-server.py`）：MCP stdio 协议的
 * initialize / tools/list / tools/call 三段握手 + 宿主不可达时的 fail-closed 行为。
 *
 * python3 是本仓门禁链自身的硬前置（tools 目录的 py 门禁脚本），真子进程冒烟与 envDependentTestGate
 * 的口径一致 —— 依赖的是构建工具链，不是某台机器的某个文件。
 */
class TenonMcpServerSmokeTest {

    /** 测试 JVM 的 cwd 是模块目录 —— 从这里向上找仓根的 scripts/。 */
    private val script: File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .map { File(it, "scripts/tenon-mcp-server.py") }
        .firstOrNull { it.isFile }
        ?: error("tenon-mcp-server.py 找不到（从仓根或模块目录跑测试）")

    private fun runServer(lines: List<String>): List<String> {
        val proc = ProcessBuilder(listOf("python3", script.absolutePath))
            .redirectErrorStream(false)
            .start()
        proc.outputWriter(Charsets.UTF_8).use { w ->
            lines.forEach { w.write(it + "\n") }
        }
        val out = proc.inputReader(Charsets.UTF_8).readText()
        proc.waitFor()
        return out.lines().filter { it.isNotBlank() }
    }

    @Test
    fun mcpHandshakeListsTheThreeToolsAndCallsFailClosedWithoutAHost() = runBlocking {
        withContext(Dispatchers.IO) {
            withTimeout(60_000) {
                assertTrue(script.isFile, "MCP 脚本在 scripts/ 下（从仓根跑测试）")
                val replies = runServer(
                    listOf(
                        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05"}}""",
                        """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
                        """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""",
                        """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"tenon_request_human_approval","arguments":{"tool":"git push","summary":"冒烟"}}}""",
                    ),
                )
                assertEquals(3, replies.size, "notification 不应答，三个请求三个应答：$replies")
                val init = replies[0]
                assertTrue(init.contains("\"serverInfo\"") && init.contains("tenon"), "initialize 应答带 serverInfo：$init")
                val tools = replies[1]
                for (expected in listOf(
                    "tenon_fetch_node_capabilities",
                    "tenon_request_human_approval",
                    "tenon_send_group_card",
                    "tenon_ask_question",
                )) {
                    assertTrue(tools.contains(expected), "工具清单缺 $expected")
                }
                // 宿主不可达 → 审批工具 fail-closed（deny），不是错误也不是 allow。
                // content 里的 JSON 被再编码一层（转义引号）—— 精确匹配脆弱，
                // 语义断言（deny 与 fail-closed 同现）才是这个冒烟真正要钉的。
                assertTrue(
                    replies[2].contains("deny") && replies[2].contains("fail-closed"),
                    "宿主不可达 = deny（fail-closed）：${replies[2]}",
                )
            }
        }
    }

    @Test
    fun unknownToolAnswersJsonRpcError() = runBlocking {
        withContext(Dispatchers.IO) {
            withTimeout(60_000) {
                val replies = runServer(
                    listOf("""{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"nope","arguments":{}}}"""),
                )
                assertEquals(1, replies.size)
                assertTrue(replies.single().contains("-32602"), "未知工具按 JSON-RPC 参数错误回答")
            }
        }
    }
}
