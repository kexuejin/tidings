// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

/**
 * 「群选中的项目目录」→「这台宿主上哪个 agent 工作区能兑现它」的解析结果（项目目录
 * 决策稿 §3.1）。四种结论**写死，不许猜**：
 *
 * - [Resolved]：恰好一条工作区宣告的路径与目录档案相等 —— 唯一可执行的情形；
 * - [Ambiguous]：不止一条 —— 同一个 agent 在多个工作区里挂了同一路径，执行点**不执行**
 *   （选错工作区等于把活干在别人的上下文里，宁可不干也不猜）；
 * - [NotExposed]：这个 agent 根本没宣告过这条路径（或没报 agent 身份）；
 * - [NoPathDeclared]：群里还没有项目目录、或指针悬空（档案被删）、或档案路径为空 ——
 *   问题出在**目录这一层**，与 agent 暴露与否无关。
 *
 * 消费方对四种结论的处置各不相同（可执行 / 提示歧义 / 提示未暴露 / 提示先选目录），
 * 所以是 sealed 而不是布尔加原因字符串 —— 多一个能说谎的编码就多一个静默。
 */
sealed interface DirectoryResolution {
    /** 唯一匹配的工作区（wsId 即协议 `agent-session`/`agent-workspace` 里的工作区身份）。 */
    data class Resolved(val wsId: String, val wsTitle: String?) : DirectoryResolution

    /** 匹配到多条；[matches] 是候选 wsId（去重后）。 */
    data class Ambiguous(val matches: List<String>) : DirectoryResolution

    /** 该 agent 没有宣告这条路径（agent 身份缺失也归这里：无从解析 = 没暴露）。 */
    data object NotExposed : DirectoryResolution

    /** 目录这一层就断了：没有指针 / 指针悬空 / 档案路径为空。 */
    data object NoPathDeclared : DirectoryResolution
}

/**
 * 项目目录 → agent 工作区的解析器（决策稿 §3.1）。
 *
 * ## 为什么是纯函数 + lambda 注入
 *
 * 判据的输入只有三样：agent 身份、目录档案、工作区宣告记录。前两者由调用方给，
 * 第三样（[AgentWorkspaceRegistry.WorkspaceRecord]）经 [records] 取值器注入 ——
 * 工作区台账是**当前态**视图（随重挂重建），解析器持有台账实例就会在重挂后读到
 * 旧表；取一条 lambda，每次解析都拿当下的事实。副作用为零也让四条规则可以逐条钉测试。
 *
 * ## 判据（逐条对应 [resolve] 的实现顺序，改这里先改决策稿）
 *
 * 1. agentKey 空白 → NotExposed：没有 agent 身份，无从谈起「它暴露了什么」；
 * 2. dirId 空白 → NoPathDeclared：群还没有项目目录；
 * 3. directoryOf(dirId) 为 null → NoPathDeclared：指针悬空（档案被删）；
 * 4. 档案 path 归一（去尾斜杠）后，在本机（nodeId 相等）该 agent（agentKey 忽略大小写
 *    相等；null 不算 —— 对端没报身份不猜）的宣告记录里找 wsPath 归一后相等：
 *    恰好 1 → Resolved；>1 → Ambiguous；0 → NotExposed。档案 path 归一后为空同归
 *    NoPathDeclared（无法映射）。
 */
class ProjectDirectoryResolver(
    private val selfNodeId: String,
    private val records: () -> List<AgentWorkspaceRegistry.WorkspaceRecord>,
) {

    fun resolve(agentKey: String, dirId: String?, directoryOf: (String) -> HostDirectory?): DirectoryResolution {
        if (agentKey.isBlank()) return DirectoryResolution.NotExposed
        val id = dirId?.trim()?.takeIf { it.isNotEmpty() } ?: return DirectoryResolution.NoPathDeclared
        val dir = directoryOf(id) ?: return DirectoryResolution.NoPathDeclared
        val wanted = normalizePath(dir.path)
        if (wanted.isEmpty()) return DirectoryResolution.NoPathDeclared
        val key = agentKey.trim()
        val matched = records().filter { rec ->
            rec.nodeId == selfNodeId &&
                rec.agentKey?.equals(key, ignoreCase = true) == true &&
                normalizePath(rec.wsPath) == wanted
        }
        return when (matched.size) {
            1 -> DirectoryResolution.Resolved(matched.single().wsId, matched.single().wsTitle)
            0 -> DirectoryResolution.NotExposed
            else -> DirectoryResolution.Ambiguous(matched.map { it.wsId }.distinct())
        }
    }

    /** 路径归一：去首尾空白与尾部 `/`，使 `/a/b/` 与 `/a/b` 相等（其余形态不动，不猜盘符大小写）。 */
    private fun normalizePath(path: String?): String =
        path?.trim()?.trimEnd('/') ?: ""
}
