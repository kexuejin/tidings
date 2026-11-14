// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.core.protocol.ConvId
import com.kapp.tenon.core.protocol.GroupMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactTransferSecurityTest {

    private val sampleGroups = listOf(
        GroupMeta(
            groupId = "group-dev-team",
            name = "Dev Team",
            members = listOf("peer-owner", "peer-member"),
            rev = 1,
            updatedBy = "peer-owner",
            updatedTs = 1000L,
        ),
    )

    @Test
    fun groupMemberCanPullArtifact() {
        val result = ArtifactTransfer.authorizePull(
            requesterPeerId = "peer-member",
            targetGroupId = "group-dev-team",
            convId = null,
            groups = sampleGroups,
        )
        assertTrue(result is ArtifactTransfer.AuthorizationResult.Allowed)
    }

    @Test
    fun nonGroupMemberDeniedPullingArtifact() {
        val result = ArtifactTransfer.authorizePull(
            requesterPeerId = "peer-stranger",
            targetGroupId = "group-dev-team",
            convId = null,
            groups = sampleGroups,
        )
        assertTrue(result is ArtifactTransfer.AuthorizationResult.Denied)
    }

    @Test
    fun externalPeerCannotPullAssistantSessionArtifacts() {
        val result = ArtifactTransfer.authorizePull(
            requesterPeerId = "peer-external",
            targetGroupId = null,
            convId = ConvId.ASSISTANT,
            groups = sampleGroups,
        )
        assertTrue("私人助理会话的产物绝对禁止外部节点拉取", result is ArtifactTransfer.AuthorizationResult.Denied)
    }

    @Test
    fun externalAgentSessionArtifactPullAuthorizedOnlyForSameNode() {
        val allowedResult = ArtifactTransfer.authorizePull(
            requesterPeerId = "node-alpha",
            targetGroupId = null,
            convId = "agent:node-alpha/coder",
            groups = sampleGroups,
        )
        assertTrue("同节点的外部 Agent 会话产物允许拉取", allowedResult is ArtifactTransfer.AuthorizationResult.Allowed)

        val deniedResult = ArtifactTransfer.authorizePull(
            requesterPeerId = "node-beta",
            targetGroupId = null,
            convId = "agent:node-alpha/coder",
            groups = sampleGroups,
        )
        assertTrue("跨节点的未授权外部 Agent 会话产物必须拒绝", deniedResult is ArtifactTransfer.AuthorizationResult.Denied)
    }

    @Test
    fun artifactRelativePathRejectsTraversalAndAbsoluteShapes() {
        // 路径穿越防线（fail-closed）：绝对路径 / 上钻段 / 反斜杠 / 盘符 / 空段 / 控制字符
        // / 空串 / 超长，全部拒绝；唯一放行的是钉在产物目录内的普通相对路径。
        val cases = mapOf(
            "" to false,
            "   " to false,
            "/etc/passwd" to false,
            "\\\\srv\\share\\patch.diff" to false,
            "C:\\tmp\\patch.diff" to false,
            "../outside.diff" to false,
            "sub/../../../outside.diff" to false,
            "sub//double-slash.diff" to false,
            "trailing/" to false,
            "bad\u0000null.diff" to false,
            "safe/patch.diff" to true,
            "deep/nested/dir/patch.diff" to true,
        )
        for ((path, expected) in cases) {
            val verdict = ArtifactTransfer.validateArtifactRelativePath(path)
            assertEquals(
                "artifact path '" + path + "' should be " + (if (expected) "allowed" else "denied"),
                expected,
                verdict is ArtifactTransfer.AuthorizationResult.Allowed,
            )
        }
    }

    @Test
    fun authorizePullRejectsTraversalPathBeforeMembershipCheck() {
        // 两道门的顺序：路径先于成员判定 —— 一个合法群成员想拉 `../secrets` 也必须被拒。
        val result = ArtifactTransfer.authorizePull(
            requesterPeerId = "peer-member",
            targetGroupId = "group-dev-team",
            convId = null,
            groups = sampleGroups,
            artifactPath = "../secrets/link-key.pk8",
        )
        assertTrue(
            "合法成员携带上钻路径同样必须被拒: " + result,
            result is ArtifactTransfer.AuthorizationResult.Denied,
        )

        val allowed = ArtifactTransfer.authorizePull(
            requesterPeerId = "peer-member",
            targetGroupId = "group-dev-team",
            convId = null,
            groups = sampleGroups,
            artifactPath = "artifacts/quest-1/patch.diff",
        )
        assertTrue(
            "合法成员 + 合法相对路径才放行: " + allowed,
            allowed is ArtifactTransfer.AuthorizationResult.Allowed,
        )
    }
}
