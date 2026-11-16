// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.drivers.AgentProfiles
import com.kapp.tenon.domain.DriverAuthStateEntryUi
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
    /** agy 登录态探针（DesktopExtension 的同一实例，结果记忆在探针侧）；null = 不接。 */
    private val agyLoginStateProbe: (suspend () -> Boolean?)? = null,
    /**
     * 逐底座登录态快照（新链，与 [agyLoginStateProbe] 并行、不替换 —— 后者还被 quest 快败用着）。
     * 入参是在位驱动 id 集合，返回值按 driverKey 建键；null = 本机没接探针 ⇒ 端口答 null（UI 不画）。
     * 映射为 domain 形状的逻辑住在 DesktopExtension 的装配处，这里只透传。
     */
    private val authStatesProbe: (suspend (Set<String>) -> Map<String, DriverAuthStateEntryUi>?)? = null,
) : TeamExecutionPort {

    override fun driverCatalog(): Set<String> =
        (availableDrivers() ?: emptySet()).toSet()

    override suspend fun agyLoginState(): Boolean? {
        // 本机没有 agy（不在在位驱动目录）→ null：对话框整行不画，
        // 不给一台没装 agy 的机器画「未登录」（那是在报告不存在的东西）。
        if (com.kapp.tenon.agent.drivers.DriverKeys.cliDriverId("agy") !in driverCatalog()) return null
        return agyLoginStateProbe?.invoke()
    }

    override suspend fun driverAuthStates(): Map<String, DriverAuthStateEntryUi>? {
        // 探针没接（手机壳 / 老装配）⇒ null，与 agy 单探针的降级口径一致：
        // 读不到就说读不到，不用空 map 冒充「全都判过了」。空 map 只在探针明确
        // 「在位驱动里没有需要登录态的」时由装配处给出，UI 据此画「不适用」。
        val probe = authStatesProbe ?: return null
        return probe.invoke(driverCatalog())
    }

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
