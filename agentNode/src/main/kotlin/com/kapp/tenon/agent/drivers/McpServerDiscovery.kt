// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import java.io.File

/**
 * MCP Server 脚本定位与驱动配置渲染。
 *
 * 为沙箱内的 CLI 驱动（如 OpenCode、Claude、Codex）注入宿主提供的 MCP 协同端点。
 */
object McpServerDiscovery {

    const val ENV_MCP_SERVER_SCRIPT = "TENON_MCP_SERVER_SCRIPT"
    const val SCRIPT_NAME = "tenon-mcp-server.py"

    /**
     * 解析 tenon-mcp-server.py 脚本的物理路径。
     * 依次从环境变量、起始目录及其祖先目录、当前工作目录进行探测。
     */
    fun resolveScript(base: File? = null): File? {
        System.getenv(ENV_MCP_SERVER_SCRIPT)?.takeIf { it.isNotBlank() }?.let { explicit ->
            val file = File(explicit)
            if (file.isFile) return runCatching { file.canonicalFile }.getOrDefault(file)
        }

        var curr = base?.let { runCatching { it.canonicalFile }.getOrDefault(it) }
        while (curr != null && curr.exists()) {
            val scriptInScripts = File(curr, "scripts/$SCRIPT_NAME")
            if (scriptInScripts.isFile) {
                return runCatching { scriptInScripts.canonicalFile }.getOrDefault(scriptInScripts)
            }
            val scriptDirect = File(curr, SCRIPT_NAME)
            if (scriptDirect.isFile) {
                return runCatching { scriptDirect.canonicalFile }.getOrDefault(scriptDirect)
            }
            curr = curr.parentFile
        }

        val userDir = File(System.getProperty("user.dir") ?: ".")
        val scriptInUserDir = File(userDir, "scripts/$SCRIPT_NAME")
        if (scriptInUserDir.isFile) {
            return runCatching { scriptInUserDir.canonicalFile }.getOrDefault(scriptInUserDir)
        }

        val direct = File("scripts/$SCRIPT_NAME")
        if (direct.isFile) {
            return runCatching { direct.canonicalFile }.getOrDefault(direct)
        }

        return null
    }

    /**
     * 生成 Claude / Gemini 兼容的 mcp_config 配置 JSON。
     */
    fun renderClaudeMcpConfig(script: File, hostUrl: String = "http://127.0.0.1:8788"): String = buildString {
        appendLine("{")
        appendLine("  \"mcpServers\": {")
        appendLine("    \"tenon\": {")
        appendLine("      \"command\": \"python3\",")
        appendLine("      \"args\": [\"" + quote(script.absolutePath) + "\"],")
        appendLine("      \"env\": {")
        appendLine("        \"TENON_HOST_URL\": \"" + quote(hostUrl) + "\"")
        appendLine("      }")
        appendLine("    }")
        appendLine("  }")
        append("}")
    }

    private fun quote(raw: String): String =
        raw.replace("\\", "\\\\").replace("\"", "\\\"")
}
