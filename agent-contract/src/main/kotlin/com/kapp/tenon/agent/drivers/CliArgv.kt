// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

/**
 * argv 模板渲染器：把 [CliDriverSpecs.ArgvSpec] 的 token 骨架里的 `${占位符}` 换成动态 token。
 *
 * 为什么是纯函数、零 IO：真源在 JSON，投影出模板骨架；动态部分（模型旗标、dsh patch 路径、
 * codex 沙箱模式、续跑 session id）由驱动库在调用前算好放进 [tokens]。渲染只是字符串替换——
 * 它不认识 CLI、不读环境、不写文件，所以能住在零 IO 的 `:agent-contract` 里，也能被构建期校验
 * 用同一套占位符口径检查。
 *
 * **缺失占位符一律响亮失败**：静默展开成空集会重演「配了模型却跑默认」——用户看得见的是
 * 「模型没生效」，没人会想到是模板占位符拼错了。宁可当场抛错，也不给一个看着正常的 argv。
 */
object CliArgv {
    private val PLACEHOLDER = Regex("^\\$\\{([a-zA-Z]+)\\}$")

    /**
     * 渲染一条模板。未识别的 `${x}` 或缺省的 token 都会抛 [IllegalStateException]。
     * [tokens] 的值可以是 0 个（如没配模型时 `${model}` 展开成空）、1 个或多个 token
     * （如 dsh 的 `${patch}` 展开成 `--patch` + 路径两个 token）。
     */
    fun render(template: List<String>, tokens: Map<String, List<String>>): List<String> =
        template.flatMap { token ->
            val match = PLACEHOLDER.matchEntire(token)
            if (match == null) {
                listOf(token)
            } else {
                val name = match.groupValues[1]
                tokens[name]
                    ?: throw IllegalStateException(
                        "argv template references \${$name} but no token was supplied " +
                            "(template=$template, supplied=${tokens.keys})"
                    )
            }
        }
}
