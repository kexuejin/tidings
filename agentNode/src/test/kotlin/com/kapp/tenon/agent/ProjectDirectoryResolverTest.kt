// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 项目目录 → agent 工作区的四条解析规则（决策稿 §3.1，写死不许猜）。
 *
 * 每条规则一条钉子，外加两个「匹配前先归一」的判据（尾斜杠、agentKey 大小写）。
 * 反向验证：把 [ProjectDirectoryResolver.resolve] 里 agentKey 的大小写折叠
 * （ignoreCase）去掉，[agentKeyIsCaseInsensitive] 变红；把尾斜杠归一去掉，
 * [trailingSlashNormalizesThePath] 变红。
 */
class ProjectDirectoryResolverTest {

    private val selfNodeId = "host-1"

    private fun resolver(vararg records: AgentWorkspaceRegistry.WorkspaceRecord) =
        ProjectDirectoryResolver(selfNodeId) { records.toList() }

    private fun record(
        nodeId: String = selfNodeId,
        agentKey: String?,
        wsId: String,
        wsPath: String?,
        wsTitle: String? = null,
    ) = AgentWorkspaceRegistry.WorkspaceRecord(
        nodeId = nodeId,
        agentKey = agentKey,
        wsId = wsId,
        wsTitle = wsTitle,
        wsPath = wsPath,
        agentKind = null,
        online = null,
        sessionCount = null,
        order = null,
        updatedAt = 0L,
    )

    private fun dirOf(vararg dirs: HostDirectory): (String) -> HostDirectory? {
        val byId = dirs.associateBy { it.dirId }
        return { id -> byId[id] }
    }

    private val projectDir = HostDirectory("dir-abc123def456", "/work/proj", "proj")

    @Test
    fun blankAgentKeyIsNotExposed() {
        // 没有 agent 身份，无从解析「它暴露了什么」—— 结论是不暴露，而不是猜。
        assertEquals(
            DirectoryResolution.NotExposed,
            resolver(record(agentKey = "codex", wsId = "ws-1", wsPath = "/work/proj"))
                .resolve("", projectDir.dirId, dirOf(projectDir)),
        )
        assertEquals(
            DirectoryResolution.NotExposed,
            resolver(record(agentKey = "codex", wsId = "ws-1", wsPath = "/work/proj"))
                .resolve("   ", projectDir.dirId, dirOf(projectDir)),
        )
    }

    @Test
    fun missingOrDanglingDirectoryMeansNoPathDeclared() {
        val r = resolver(record(agentKey = "codex", wsId = "ws-1", wsPath = "/work/proj"))
        // 群还没有项目目录（指针为空）。
        assertEquals(DirectoryResolution.NoPathDeclared, r.resolve("codex", null, dirOf(projectDir)))
        assertEquals(DirectoryResolution.NoPathDeclared, r.resolve("codex", "   ", dirOf(projectDir)))
        // 指针悬空：dirId 查不到档案（档案被删）。
        assertEquals(DirectoryResolution.NoPathDeclared, r.resolve("codex", "dir-gone", dirOf(projectDir)))
        // 档案在但 path 为空：无法映射。
        val empty = HostDirectory("dir-abc123def456", "  ", "empty")
        assertEquals(DirectoryResolution.NoPathDeclared, r.resolve("codex", empty.dirId, dirOf(empty)))
    }

    @Test
    fun unexposedWhenNothingMatches() {
        val r = resolver(record(agentKey = "codex", wsId = "ws-1", wsPath = "/work/other"))
        assertEquals(
            DirectoryResolution.NotExposed,
            r.resolve("codex", projectDir.dirId, dirOf(projectDir)),
            "宣告的是别的路径：目录在这个 agent 眼里不存在",
        )
    }

    @Test
    fun uniqueMatchResolvesToThatWorkspace() {
        val r = resolver(record(agentKey = "codex", wsId = "ws-1", wsPath = "/work/proj", wsTitle = "Proj"))
        assertEquals(
            DirectoryResolution.Resolved("ws-1", "Proj"),
            r.resolve("codex", projectDir.dirId, dirOf(projectDir)),
        )
    }

    @Test
    fun twoWorkspacesOnTheSamePathAreAmbiguous() {
        val r = resolver(
            record(agentKey = "codex", wsId = "ws-1", wsPath = "/work/proj"),
            record(agentKey = "codex", wsId = "ws-2", wsPath = "/work/proj"),
        )
        assertEquals(
            DirectoryResolution.Ambiguous(listOf("ws-1", "ws-2")),
            r.resolve("codex", projectDir.dirId, dirOf(projectDir)),
            "同一个 agent 在两个工作区挂了同一路径：不执行（选错上下文比不干更糟）",
        )
    }

    @Test
    fun trailingSlashNormalizesThePath() {
        val r = resolver(record(agentKey = "codex", wsId = "ws-1", wsPath = "/work/proj/"))
        assertEquals(
            DirectoryResolution.Resolved("ws-1", null),
            r.resolve("codex", projectDir.dirId, dirOf(projectDir)),
            "宣告带尾斜杠、档案不带：同一路径",
        )
    }

    @Test
    fun agentKeyIsCaseInsensitive() {
        val r = resolver(record(agentKey = "Codex-CLI", wsId = "ws-1", wsPath = "/work/proj"))
        assertEquals(
            DirectoryResolution.Resolved("ws-1", null),
            r.resolve("codex-cli", projectDir.dirId, dirOf(projectDir)),
            "agent 身份键忽略大小写（对端拼写漂移不许变成『未暴露』）",
        )
    }

    @Test
    fun otherNodeAndMissingAgentKeyNeverMatch() {
        val r = resolver(
            record(nodeId = "host-2", agentKey = "codex", wsId = "ws-other", wsPath = "/work/proj"),
            record(agentKey = null, wsId = "ws-null", wsPath = "/work/proj"),
        )
        assertEquals(
            DirectoryResolution.NotExposed,
            r.resolve("codex", projectDir.dirId, dirOf(projectDir)),
            "别的宿主的宣告不算本机的；没报 agent 身份的记录不猜归属",
        )
    }
}
