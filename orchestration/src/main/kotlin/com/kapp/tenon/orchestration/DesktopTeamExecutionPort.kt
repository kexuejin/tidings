// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.drivers.AgentProfiles
import com.kapp.tenon.domain.TeamExecutionKindsUi
import com.kapp.tenon.domain.TeamExecutionMemberUi
import com.kapp.tenon.domain.TeamExecutionPort
import com.kapp.tenon.domain.TeamExecutionProfileUi
import com.kapp.tenon.domain.TeamExecutionSaveResult

/**
 * [TeamExecutionPort] 的桌面实现（团队执行方案 §6.3）：把 UI 侧的编辑形状映射到
 * [TeamExecutionStore]（宿主本地执行档）与驱动名册（在位 = `AgentProfiles` ∩ 控制面）。
 *
 * 校验**单源**：[TeamExecutionValidation]（保存与派单读同一份规则）——
 * UI 侧的 `validateExecutionProfileUi` 只是结构前置（就地提示），这里的校验是权威。
 */
class DesktopTeamExecutionPort(
    private val store: TeamExecutionStore,
    /** 在位驱动键（编排器的 availableAgentKeys 同一份名册；null = 控制面不可用 ⇒ 空名册）。 */
    private val availableDrivers: () -> Set<String>?,
    /** 群 → 项目目录 dirId（D16 主键的一半）；与 HostDirectoryStore 同一实例。 */
    private val pointerDirId: (groupId: String) -> String?,
) : TeamExecutionPort {

    override fun driverCatalog(): Set<String> =
        (availableDrivers() ?: emptySet()).toSet()

    override fun profileOf(
        dirId: String,
        leaderAgentId: String,
    ): TeamExecutionProfileUi? =
        store.profileFor(dirId, leaderAgentId)?.let { p ->
            TeamExecutionProfileUi(
                dirId = p.dirId,
                leaderAgentId = p.leaderAgentId,
                members = p.members.map {
                    TeamExecutionMemberUi(it.agentId, it.kind, it.driverKey, it.models, it.customPrompt)
                },
            )
        }

    /** 团队解散收尾：把这份档从宿主磁盘上真的删掉（[TeamExecutionStore.remove]）。 */
    override fun forget(dirId: String, leaderAgentId: String) {
        store.remove(dirId, leaderAgentId)
    }

    override fun validate(
        profile: TeamExecutionProfileUi,
        declaredMemberIds: Set<String>,
    ): List<String> = TeamExecutionValidation.validate(
        toSpec(profile),
        declaredMemberIds,
    )

    override fun save(
        profile: TeamExecutionProfileUi,
        declaredMemberIds: Set<String>,
    ): TeamExecutionSaveResult = when (val verdict = store.save(toSpec(profile), declaredMemberIds)) {
        is TeamExecutionSaveVerdict.Saved -> TeamExecutionSaveResult.Saved
        is TeamExecutionSaveVerdict.Rejected -> TeamExecutionSaveResult.Rejected(verdict.reasons)
    }

    override fun groupPointerDirId(groupId: String): String? = pointerDirId(groupId)

    private fun toSpec(profile: TeamExecutionProfileUi) = TeamExecutionProfile(
        dirId = profile.dirId,
        leaderAgentId = profile.leaderAgentId,
        updatedAt = 0,
        members = profile.members.map {
            TeamExecutionMemberSpec(it.agentId, it.kind, it.driverKey, it.models, it.customPrompt)
        },
    )
}

/** UI 侧 kind 常量与编排层取值域的同源校验（防漂移的编译期对账，测试也钉）。 */
private val kindParity: Boolean =
    TeamExecutionKindsUi.WORKER == TeamExecutionKinds.WORKER &&
        TeamExecutionKindsUi.REVIEWER == TeamExecutionKinds.REVIEWER
