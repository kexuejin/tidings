// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.core.protocol.ConvId
import com.kapp.tenon.core.protocol.GroupMeta

/**
 * 跨节点产物/源码补丁拉取与投递权限看门狗 (ArtifactTransfer)。
 *
 * 安全防御（两道门，都要过）：
 * 1. **谁**：当对端发起拉取或订阅指定支线产物（patch.diff、完整源码）时，
 *    必须校验对端 peerId 是否具备目标群/任务的合法成员资格，
 *    绝不允许跨群或来自非群成员的未授权下载请求（[authorizePull]）；
 * 2. **拿什么**：请求携带的产物相对路径必须钉在产物目录内 —— 绝对路径、`..` 上钻段、
 *    反斜杠、盘符列分隔与控制字符一律拒绝（[validateArtifactRelativePath]）。
 *    两者任一拒绝即整请求拒绝（fail-closed）。
 */
object ArtifactTransfer {

    sealed interface AuthorizationResult {
        object Allowed : AuthorizationResult
        data class Denied(val reason: String) : AuthorizationResult
    }

    /** 产物相对路径的长度上限：超长路径没有正当业务，只可能是探测。 */
    private const val MAX_ARTIFACT_PATH_LENGTH: Int = 512

    /**
     * 产物**相对路径**校验（路径穿越防线）。
     *
     * 判据（全部 fail-closed，拒绝时带原因）：
     * - 非空、长度 ≤ [MAX_ARTIFACT_PATH_LENGTH]；
     * - 不以 `/`、`\` 开头（绝对路径），不含 `:`（Windows 盘符 / 流分隔）；
     * - 按分隔符切段后**任何一段都不是 `..`**（上钻到产物目录之外），
     *   也没有空段（`a//b`、`a/` 这类畸形）；
     * - 不含控制字符。
     *
     * 纯字符串判定，不做文件系统访问 —— 校验发生在「要不要受理这个请求」的层面，
     * 真正读盘的落点（ArtifactDelivery）只允许拿到通过本判据的相对路径再自行拼接。
     */
    fun validateArtifactRelativePath(relativePath: String): AuthorizationResult {
        if (relativePath.isBlank()) {
            return AuthorizationResult.Denied("Artifact path cannot be blank")
        }
        if (relativePath.length > MAX_ARTIFACT_PATH_LENGTH) {
            return AuthorizationResult.Denied("Artifact path too long (>" + MAX_ARTIFACT_PATH_LENGTH + ")")
        }
        if (relativePath.startsWith("/") || relativePath.startsWith("\\")) {
            return AuthorizationResult.Denied("Artifact path must be relative: " + relativePath)
        }
        if (relativePath.contains(':')) {
            return AuthorizationResult.Denied("Artifact path must not contain ':' (drive/stream separator): " + relativePath)
        }
        val segments = relativePath.replace('\\', '/').split('/')
        if (segments.any { it.isEmpty() }) {
            return AuthorizationResult.Denied("Artifact path has empty segment: " + relativePath)
        }
        if (segments.any { it == ".." }) {
            return AuthorizationResult.Denied("Artifact path must not traverse upward: " + relativePath)
        }
        if (segments.any { segment -> segment.any { it.code < 0x20 } }) {
            return AuthorizationResult.Denied("Artifact path contains control characters: " + relativePath)
        }
        return AuthorizationResult.Allowed
    }

    /**
     * 校验请求者 [requesterPeerId] 是否有权拉取属于 [targetGroupId] 或会话 [convId] 的产物；
     * 请求携带产物相对路径 [artifactPath] 时先过 [validateArtifactRelativePath]。
     */
    fun authorizePull(
        requesterPeerId: String,
        targetGroupId: String?,
        convId: String?,
        groups: List<GroupMeta>,
        artifactPath: String? = null,
    ): AuthorizationResult {
        if (requesterPeerId.isBlank()) {
            return AuthorizationResult.Denied("Requester peer ID cannot be blank")
        }
        if (artifactPath != null) {
            val pathVerdict = validateArtifactRelativePath(artifactPath)
            if (pathVerdict is AuthorizationResult.Denied) {
                return pathVerdict
            }
        }

        // 如果绑定了群，请求者必须是该群的现有成员
        if (!targetGroupId.isNullOrBlank()) {
            val bareGroupId = ConvId.groupIdOf(targetGroupId) ?: targetGroupId
            val group = groups.firstOrNull { it.groupId == bareGroupId }
                ?: return AuthorizationResult.Denied("Target group not found: " + bareGroupId)

            val isMember = group.members.contains(requesterPeerId)
            if (!isMember) {
                return AuthorizationResult.Denied("Requester " + requesterPeerId + " is not a member of group " + bareGroupId)
            }
            return AuthorizationResult.Allowed
        }

        // 如果是 1:1 或私人助理单聊
        if (convId != null) {
            if (ConvId.isAssistant(convId)) {
                // 私人助理会话只允许本机主访问，拒绝外部直接拉取
                return AuthorizationResult.Denied("Artifacts in assistant session cannot be pulled by external peer: " + requesterPeerId)
            }
            if (convId == requesterPeerId) {
                return AuthorizationResult.Allowed
            }
            if (ConvId.isExternalAgent(convId)) {
                val peerNode = convId.removePrefix(ConvId.AGENT_PREFIX).substringBefore('/')
                if (peerNode == requesterPeerId) {
                    return AuthorizationResult.Allowed
                }
            }
        }

        return AuthorizationResult.Denied("No matching authorization policy for requester " + requesterPeerId)
    }
}
