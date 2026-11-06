// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

/**
 * **这条消息是「聊」还是「做」** —— 双流解耦的分诊口（编排 P2 规格 1）。
 *
 * ## 为什么默认是「聊」
 *
 * 规格把安全侧放在默认上：**未携带显式派单指令 ⇒ 只读讨论**。反过来（默认派单）意味着
 * 一句「这个接口怎么设计」也会拉起一个能写文件的 CLI，而用户以为自己只是在问问题 ——
 * 那是不可撤销的那类错（写进别人的仓库）。所以判定必须「宁可判成聊，不可判成做」的
 * 方向**反过来**：这里是**只有认出派单词才算做**，其余全归聊。
 *
 * 代价是漏判（本该开工的被当成讨论）。漏判的后果是「agent 只给了方案、没动手」，
 * 用户再补一句「开工」即可；误判的后果是有副作用。取前者。
 *
 * ## 两条判据的优先序（决定表）
 *
 * | 文本 | 判 |
 * |---|---|
 * | `组队: …` / `team: …` / 阶段语法（`开发: … 审核: …`）/ 快捷指令 | **做**（显式指令，不被问句覆盖） |
 * | 「怎么实现 X？」这类**问句 + 派单动词** | **聊**（问句赢：人在问，不是在下令） |
 * | 其余（含纯 @ 打招呼、纯提问） | **聊** |
 *
 * 「问句赢」只对**软动词**（实现/修复/重构…）生效，对**硬指令**（`组队:`、阶段语法、
 * `/test`）不生效：`组队: 帮我分析下` 依然是派单 —— 人已经按了「开工」那个动作。
 *
 * ## 为什么词表是 unicode 转义而不是中文字面量
 *
 * 这些是**解析词**不是 UI 文案，进不了 `strings.xml`；而 `cjkLiteralGate` 只允许生产源码
 * 里出现已入账的中文字面量。转义写法（`"\u5b9e\u73b0"`）与 `StageChain.parseStages` 里
 * `\uff1a` 的处理同一口径 —— 可读性靠本段注释兜住。
 */
object CognitiveFlow {

    /** 分诊结果。`Discuss` = 只读讨论（零 worktree、零写工具）；`Deliver` = 工单交付流。 */
    enum class Intent { Discuss, Deliver }

    /**
     * 每人只发言 **1 轮**（规格 1.2）。
     *
     * 「受控回合」不是礼貌问题：两个 agent 互相客套没有终止条件，Token 会一直烧到超时。
     * 一轮之后由宿主收口（写提议开工卡），而不是让它们自己决定还要不要再说一句。
     */
    const val MAX_AGENT_TURNS: Int = 1

    /**
     * 只读讨论的系统前置。**ASCII**：它进的是外部 CLI 的提示词，不是产品文案。
     *
     * 三条都必须写死在这里（而不是散在调用点）：不许写文件、不许动 git、不许跑会改
     * 工作区的命令。少写一条就等于给「调研」留了一个写盘的后门。
     */
    const val READ_ONLY_DIRECTIVE: String =
        "[READ-ONLY DISCUSSION] This turn is analysis only. " +
            "Do NOT create, edit, delete, move or rename any file. " +
            "Do NOT run git commands that write (add/commit/checkout/branch/merge/rebase/stash/apply). " +
            "Do NOT run builds, installs, formatters or tests that change the workspace. " +
            "Read, search and reason only. " +
            "If the user asks for a plan, end with a concrete proposal: the goal in one line " +
            "plus the agent you would pick to build it and the agent you would pick to review it."

    /**
     * 硬指令：出现即 `Deliver`，**问句也盖不住**（人已经按了开工那个动作）。
     *
     * `team:` 与 `\u7ec4\u961f:` 是同一个挡位的两个写法（见 `DelegationParser.AUTO_PREFIX`）；
     * 阶段语法与快捷指令同理由解析器/快捷指令层定义，这里只做**分诊**，不重复解析。
     */
    private val HARD_DISPATCH: List<String> = listOf(
        "\u7ec4\u961f", // 组队
        "team:",
        "\u5f00\u53d1:", // 开发:
        "\u5f00\u53d1\uff1a",
        "\u5ba1\u6838:", // 审核:
        "\u5ba1\u6838\uff1a",
        "/status",
        "/diff",
        "/test",
        "/stop",
    )

    /**
     * 软动词：出现时**还要看是不是问句**。`\u600e\u4e48\u5b9e\u73b0` 是提问，
     * `\u5b9e\u73b0\u8fd9\u4e2a` 是下令 —— 两者的差别不在动词上，在句子的语气上。
     */
    private val SOFT_DISPATCH: List<String> = listOf(
        "\u5b9e\u73b0", // 实现
        "\u4fee\u590d", // 修复
        "\u91cd\u6784", // 重构
        "\u6539\u9020", // 改造
        "\u65b0\u589e", // 新增
        "\u5220\u6389", // 删掉
        "\u6539\u6210", // 改成
        "\u5199\u4e00\u4e2a", // 写一个
        "\u63d0\u4ea4", // 提交
        "\u5408\u5e76", // 合并
        "\u4e0a\u7ebf", // 上线
        "\u5f00\u5de5", // 开工
        "implement",
        "refactor",
        "fix ",
        "commit",
        "merge ",
    )

    /** 问句标记：与软动词同时出现时，判「聊」。 */
    private val INQUIRY: List<String> = listOf(
        "\u600e\u4e48", // 怎么
        "\u600e\u6837", // 怎样
        "\u5982\u4f55", // 如何
        "\u4e3a\u4ec0\u4e48", // 为什么
        "\u662f\u5426", // 是否
        "\u80fd\u4e0d\u80fd", // 能不能
        "\u8981\u4e0d\u8981", // 要不要
        "\u8c03\u7814", // 调研
        "\u5206\u6790", // 分析
        "\u8bc4\u4f30", // 评估
        "\u5bf9\u6bd4", // 对比
        "\u98ce\u9669", // 风险
        "?",
        "\uff1f",
        "how ",
        "why ",
        "should ",
    )

    /**
     * 只读档允许的工具名（归一后）。**白名单而非黑名单**：外部 CLI 的工具集由它自己
     * 演进，黑名单迟早漏一个 `apply_patch_v2`；白名单漏了只会让一次读被拦下（可见、
     * 可修），不会让一次写溜过去。
     *
     * 名字按「去掉命名空间前缀后的末段」比对（`mcp__fs__read_file` → `read_file`），
     * 因为同一个读能力在不同驱动里前缀不同。
     */
    private val READ_ONLY_TOOLS: Set<String> = setOf(
        "read", "read_file", "readfile", "read_many_files",
        "view", "cat", "open_file",
        "grep", "search", "search_files", "codebase_search", "semantic_search",
        "glob", "list", "ls", "list_dir", "list_files", "find",
        "webfetch", "web_fetch", "websearch", "web_search",
        "todowrite", "todo_read", "notebook_read", "think",
    )

    /** 派单词大小写不敏感的比对基数（英文动词全小写；中文没有大小写，lowercase 无害）。 */
    private fun containsAny(text: String, tokens: List<String>): Boolean {
        val lower = text.lowercase()
        return tokens.any { lower.contains(it) }
    }

    /**
     * 分诊。**纯函数**：同一段文本永远同一个答案（分诊表是接线守卫的判据来源）。
     */
    fun classify(text: String): Intent {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Intent.Discuss
        if (containsAny(trimmed, HARD_DISPATCH)) return Intent.Deliver
        if (containsAny(trimmed, SOFT_DISPATCH) && !containsAny(trimmed, INQUIRY)) return Intent.Deliver
        return Intent.Discuss
    }

    /** 只读档的提示词：前置 + 原文（原文一字不动，人说的话不该被改写）。 */
    fun readOnlyPrompt(text: String): String = READ_ONLY_DIRECTIVE + "\n\n" + text

    /**
     * 这个工具在只读档里放不放行。`null` / 空名 ⇒ **不放行**：没有名字的调用无法
     * 证明它是读，fail-closed。
     */
    fun allowsTool(tool: String?): Boolean {
        val name = tool?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
        val leaf = name.substringAfterLast("__").substringAfterLast('.')
        if (leaf in READ_ONLY_TOOLS) return true
        // 前缀式命名（`mcp__fs__read_file`）已被上一行归一；剩下的整名再比一次，
        // 兼容不带分隔符的驱动（`readFile` 归一成 `readfile` 后落在白名单里）。
        return name in READ_ONLY_TOOLS
    }
}
