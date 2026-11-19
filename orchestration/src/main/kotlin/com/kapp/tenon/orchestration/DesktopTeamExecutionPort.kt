// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.drivers.AgentProfiles
import com.kapp.tenon.agent.drivers.CliDriverSpecs
import com.kapp.tenon.core.nowMillis
import com.kapp.tenon.domain.DriverAuthCheckUi
import com.kapp.tenon.domain.DriverAuthStateEntryUi
import com.kapp.tenon.domain.DriverModelCatalogEntryUi
import com.kapp.tenon.domain.DriverRosterEntryUi
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
    /**
     * 模型目录探针（按需调用，结果按驱动缓存）：入参是在位驱动键集合。
     * null = 本机没接目录 ⇒ 端口答 null（UI 不画选择项、只留手填）。
     */
    private val modelCatalogProbe: (suspend (Set<String>) -> Map<String, DriverModelCatalogEntryUi>?)? = null,
) : TeamExecutionPort {

    /**
     * 最近一次目录探测里**确定已知**的模型 id 表：保存校验的输入。
     *
     * null = 从没探过（放行）。只有 UI 真的调过 [driverModelCatalog] 才会有值 ——
     * 这正是「未知不拦」的落点：没打开过编制的地方，保存不该因为目录缺失被拒。
     */
    @Volatile
    private var knownModelsSnapshot: Map<String, Set<String>>? = null

    override fun driverCatalog(): Set<String> =
        (availableDrivers() ?: emptySet()).toSet()

    /**
     * 盘面全集名册（方案 §3.2/§3.9）：从 `CliDriverSpecs`（身份卡，唯一真相）投影；
     * 在位标记与 [driverCatalog] 同源；安装指引住**这张表**（独立于 Spec —— 它是
     * 引导文案不是身份），缺项 = null（UI 回落「复制安装命令」，不画死链）。
     */
    override fun driverRoster(): List<DriverRosterEntryUi>? {
        val inService = availableDrivers() ?: return null
        return CliDriverSpecs.builtins.map { spec ->
            DriverRosterEntryUi(
                key = spec.key,
                displayName = spec.displayName,
                shortName = spec.shortName ?: spec.displayName.substringBefore(' '),
                vendor = spec.vendor,
                installed = spec.key in inService,
                installUrl = INSTALL_GUIDES[spec.key],
                supportsPerTaskModel = spec.supportsPerTaskModel,
            )
        }
    }

    override suspend fun driverAuthChecks(): List<DriverAuthCheckUi>? {
        val probe = authStatesProbe ?: return null
        // 探针答 null = 这一轮**没得到结论**（子进程没起来 / 控制面不在位）。透传 null，
        // 不要把它折成空表 —— 空表在界面上是「检过了，一台都没有」，那是在报告没发生的事。
        val map = probe.invoke(driverCatalog()) ?: return null
        val now = nowMillis()
        return map.map { (key, entry) ->
            DriverAuthCheckUi(
                driverKey = key,
                state = entry.state,
                evidence = entry.evidence,
                remediation = entry.remediation,
                remediationDetail = entry.remediationDetail,
                checkedAt = now,
            )
        }
    }

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

    override suspend fun driverModelCatalog(): Map<String, DriverModelCatalogEntryUi>? {
        val probe = modelCatalogProbe ?: return null
        // 与登录态同一口径：探针答 null = 这一轮没得到结论，透传 null 而不是折成空表。
        val entries = probe.invoke(driverCatalog()) ?: return null
        knownModelsSnapshot = DriverModelCatalogMapping.knownModelIds(entries)
        return entries
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
        knownModelsSnapshot,
    )

    override fun save(
        profile: TeamExecutionProfileUi,
        declaredMemberIds: Set<String>,
    ): TeamExecutionSaveResult = when (
        val verdict = store.save(toSpec(profile), declaredMemberIds, knownModelsSnapshot)
    ) {
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

/**
 * 底座**安装指引**（方案 §3.2 的 installUrl 裁定）：它不住 `CliDriverSpecs.Spec` ——
 * 安装是引导文案不是身份。缺项 = UI 回落「复制安装命令」。
 * 既有安装形态优先（本机实测过的公开约定路径），否则给官方主页。
 */
private val INSTALL_GUIDES: Map<String, String> = mapOf(
    "codex" to "https://developers.openai.com/codex/cli/",
    "claude" to "https://claude.com/product/claude-code",
    "agy" to "https://antigravity.google/",
    "opencode" to "https://opencode.ai/docs/",
    "dsh" to "https://docs.deepseek.com/",
    "codebuddy" to "https://www.codebuddy.ai/",
    "zcode" to "https://zcode.ai/",
)
