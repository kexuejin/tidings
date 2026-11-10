// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

/**
 * 席位 `@token` 的**职责取值域**（D13）：名字表达分工，不表达物理目录。
 *
 * 单一定义、禁止散落 —— 生成入口（[AgentMemberName.base]）与 UI 选择器共同引用这一份；
 * 各写一份清单就是在制造「键对、值不在白名单」那类分叉。
 *
 * 规则（决策稿 D13）：token = `kind + 职责`（`dsh-coder` / `gemini-lead`）；
 * 职责留空 = 只用 kind（`dsh`）；群内冲突追加序号（`dsh-coder-2`，见 [AgentMemberName.unique]）。
 * **换目录不改名** —— 目录一变名字就变，历史消息署名的读法就跟着断。
 */
object AgentDuties {
    const val LEAD = "lead"
    const val CODER = "coder"
    const val REVIEWER = "reviewer"
    const val TESTER = "tester"
    const val ANALYST = "analyst"
    const val WRITER = "writer"
    const val OPS = "ops"
    const val ASSISTANT = "assistant"

    /** 全部合法取值；UI 选择器只准从这里取。 */
    val ALL = listOf(LEAD, CODER, REVIEWER, TESTER, ANALYST, WRITER, OPS, ASSISTANT)

    /** 单兵席位默认职责。 */
    const val DEFAULT_SINGLE = ASSISTANT

    /** 团队领队席位默认职责。 */
    const val DEFAULT_LEADER = LEAD

    /**
     * 归一化：取值域外的值（含 null/空白）一律回落**单兵默认**。
     * 不发明第四种拼写 —— 外来数据宁可归一，也不让 `Coder`/`CODER` 变成第二个事实。
     */
    fun normalize(raw: String?): String = raw?.trim()?.takeIf { it in ALL } ?: DEFAULT_SINGLE
}

/**
 * 群里一个可被 @ 的智能体席位名（D13：`kind + 职责`）。
 *
 * ## 名字表达分工，不表达目录
 *
 * 旧规则 `<agentId>-<wsTitle>` 把物理目录写进了名字：目录一改名/换目录，`@` 习惯与
 * 历史署名的读法就跟着断（消息的 `senderId` 是稳定的，**显示名是从群声明实时解析的**）。
 * 新规则 [AgentDuties] 只进名字一次 —— 建席位那一刻；之后换目录、换设备都不改名。
 *
 * ## 名字：默认生成，进群后不许冲突
 *
 * **@ 语法永远只有一级**（`@dsh-coder`）—— 消歧在**添加那一刻**做完（`unique` 追加序号），
 * 不在 @ 的时候才暴露给用户。既有席位**沿用现有名字**（`@` token 不能漂）；
 * 新席位才走本规则。
 */
object AgentMemberName {

    /**
     * 默认席位名：`<kind>-<职责>`，都归一化。
     *
     * - kind 取 **agentKind**（`dsh` / `gemini`），不再用逐机实例 id（`dsh-029bbbf3`）——
     *   那是「在哪台机器的第几份拷贝」，不是「这个数字员工是谁」；
     * - 职责为空（用户选「通用」）时只用 kind；
     * - 归一化规则见 [slug]。
     */
    fun base(agentKind: String?, agentId: String?, duty: String?): String {
        val agent = slug(agentKind?.takeIf { it.isNotBlank() } ?: agentId).ifBlank { "agent" }
        val d = slug(duty)
        return if (d.isBlank()) agent else "$agent-$d"
    }

    /**
     * 在 [taken] 里挑一个不冲突的名字：`base` 被占了就依次试 `base-2`、`base-3`…
     *
     * 这就是"默认生成一个唯一名字，后面再加的不允许冲突"的全部实现 ——
     * **不提供手改入口**（用户原话：「默认生成一个唯一名字，后面再加的不允许冲突」）。
     */
    fun unique(base: String, taken: Set<String>): String {
        val clean = base.ifBlank { "agent" }
        if (clean !in taken) return clean
        var n = 2
        while ("$clean-$n" in taken) n++
        return "$clean-$n"
    }

    /**
     * 归一化成一个能直接打出来的 @ token。
     *
     * 只保留小写字母、数字、`-`；其它字符（空格、下划线、中文、斜杠…）转成 `-`，
     * 连续 `-` 合并，首尾 `-` 去掉。
     *
     * 为什么把中文也转掉：`@` 之后是**输入法打出来的字**，中文名在 @ 补全里会出现
     * "打了一半对不上"的问题（组合输入期间每次按键都在过滤）。用 ASCII 短名最稳，
     * 而显示用的中文名仍然由 `displayName` 提供 —— 两者不必是同一个字符串。
     */
    fun slug(raw: String?): String {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return ""
        val sb = StringBuilder(s.length)
        var lastDash = false
        for (ch in s.lowercase()) {
            val ok = ch in 'a'..'z' || ch in '0'..'9'
            if (ok) {
                sb.append(ch)
                lastDash = false
            } else if (!lastDash && sb.isNotEmpty()) {
                sb.append('-')
                lastDash = true
            }
        }
        return sb.toString().trimEnd('-')
    }

    /**
     * **触发判据要认的 token 名**：与 [base] 产出的暴露名同一套字符集（小写 ASCII）。
     *
     * 为什么单列一个函数而不是复用 [base]：`base` 拼的是 `agent-ws` 两个成分，
     * 而触发侧可能只有一个 kind（老形态、或没有 workspace 的 agent）。
     * 两者必须**同一套归一化**（都走 [slug]），否则 `@` 插入的名字与正则认的名字会分叉 ——
     * 那就是本仓反复出现过的「@ 了却叫不醒」。
     */
    fun token(vararg parts: String?): String {
        val joined = parts.filterNotNull().map { slug(it) }.filter { it.isNotBlank() }
        return if (joined.isEmpty()) "agent" else joined.joinToString("-")
    }
}

