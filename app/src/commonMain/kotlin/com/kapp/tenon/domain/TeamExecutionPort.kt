// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.core.protocol.Limits
import com.kapp.tenon.core.protocol.TeamMemberInfo
import com.kapp.tenon.core.protocol.VirtualAgentMember
import com.kapp.tenon.core.protocol.resolveTeamMemberToken

/**
 * 团队执行档的**壳层端口**（团队执行方案 §6.3/§7.1）：UI 读/写宿主本地执行配置的唯一通路。
 *
 * ## 为什么是端口而不是直调
 *
 * `:app`（域层）不许 import 驱动/编排类型（`:domainComposeFreeGate` 同一条边界纪律）：
 * 驱动名册、执行档落盘、保存校验的实现都在 `:orchestration`/`desktopApp`，经壳注入。
 * 与 `onExecDevices` / `invokeExecutor` 同一形状 —— **手机端端口为 null ⇒ UI 只读**
 * （执行配置由席位宿主管理，手机只看声明投影）。
 */
interface TeamExecutionPort {

    /**
     * 宿主**在位**的驱动键集合（`AgentProfiles.knownKeys() ∩ resolveCliDriver`）。
     * 驱动下拉只画这里面的键 —— "装了没"是宿主当下的事实，不是保存时的事实。
     */
    fun driverCatalog(): Set<String>

    /**
     * agy CLI 的登录态（预检探针）：true = 已登录，false = 未登录，null = 本机没有
     * agy / 探针不可用。suspend：首次探测要起一次子进程（≈6s），结果由探针侧记忆。
     */
    suspend fun agyLoginState(): Boolean? = null

    /**
     * 逐底座的登录态快照：`driverKey → 快照`。
     *
     * **设计用途 = 配置页「检测」按钮的按需调用**（用户点一次才探，结果落在对应底座卡上并
     * 给出解法：命令原文 / 打开应用 / 安装说明），让「没登录」在派单之前就暴露，而不是等
     * 一轮白跑之后。三种形态必须分清：
     *
     * - 自动预检（启动即探 7 家）**不做** —— 冷启动多背十几秒，多数人当天用不上；
     * - 按需检测（一个显式按钮调用本方法）**做** —— 这正是本方法存在的理由；
     * - 常驻登录表（一页永远挂着的「未判定 × N」）**不做** —— 既吵，又没有下一个动作。
     *
     * 登录本身仍由各家 CLI 管：应用不给「一键登录」，只给命令 + 复制。卡上的解法与派单失败
     * 时的内联提示取自同一份词表（编排层 `DriverAuthStateMapping.remediationDetailOf`），
     * UI 不抄第二份。
     *
     * 口径仍守三态 + 证据强度：**null = 读不到**（这台机器没有执行面 / 探针没接），
     * 空 map = 读到了但无需登录的底座；二者都不许冒充「全部已登录」。弱证据（配置文件/
     * 环境在位）只表示"有凭证物、未验真"。任何"两集合都空即通过"的写法都是空集假绿。
     */
    suspend fun driverAuthStates(): Map<String, DriverAuthStateEntryUi>? = null

    /**
     * 读该支队的执行档；null = 没配过（正常）。主键 = `(目录, 领队)`，**不含群** ——
     * 一支队进几个群都是同一份档（见 `TeamExecutionProfile` 的 KDoc）。
     */
    fun profileOf(dirId: String, leaderAgentId: String): TeamExecutionProfileUi?

    /**
     * 丢掉该支队的执行档（团队解散时收尾）。默认空实现：没有执行面的壳压根没有档可丢。
     *
     * 与 [save] 分开而不是 save(null)：删除是个**要能被统计**的动作（解散的横幅要如实说
     * 有没有清掉），`save` 的返回值语义是「校验/落盘」。
     */
    fun forget(dirId: String, leaderAgentId: String) = Unit

    /** 校验（保存前预检 + UI 就地指出原因）；与 save 共用同一份规则（§3.4 单源）。 */
    fun validate(profile: TeamExecutionProfileUi, declaredMemberIds: Set<String>): List<String>

    /** 保存（同主键覆盖）。Rejected 携带结构化原因，UI 直接展示 —— 不允许"先存着"。 */
    fun save(profile: TeamExecutionProfileUi, declaredMemberIds: Set<String>): TeamExecutionSaveResult

    /**
     * 群项目目录指针（D16：执行档主键的一半）。null = 群没设目录 / 本机不是宿主 ——
     * 编制对话框据此置灰保存（执行档挂在一个解析不出来的目录上没有意义）。
     */
    fun groupPointerDirId(groupId: String): String? = null
}

/** 底座登录态的三态（与探针侧同口径：只报确定未登录，其余放行）。 */
enum class DriverAuthStateUi { AUTHENTICATED, NOT_AUTHENTICATED, UNKNOWN }

/** 证据强度：真跑子命令 = STRONG；只看凭证文件/环境在位 = HINT（未验真）。 */
enum class DriverAuthEvidenceUi { STRONG, HINT }

/**
 * 补凭证的引导类别（不是句子）：编排层按 driverKey 定这档，UI 按它选一条本地化文案。
 * 两边各只认这份枚举，不在 UI 里按驱动名再写一遍 if 链（词表抄两处必错）。
 */
enum class DriverAuthRemediationUi {
    /** 交互式 CLI：宿主终端跑一次登录命令（agy / codebuddy / claude / codex / opencode）。 */
    TERMINAL_LOGIN,
    /** 只认环境变量 key：配到宿主环境（dsh 出厂路由 / 通用兜底）。 */
    TOKEN_ENV,
    /** 登录态在桌面应用里：先在应用里选好默认模型（zcode）。 */
    APP_MODEL,
    /** 这条底座不需要单独登录（native / 无凭证面的驱动）。 */
    NONE,
}

/**
 * 一条底座登录态。字段全是 `String`/枚举，不携带任何驱动或平台类型 —— `:app` 零依赖边界。
 */
data class DriverAuthStateEntryUi(
    val driverKey: String,
    val state: DriverAuthStateUi,
    val evidence: DriverAuthEvidenceUi,
    val remediation: DriverAuthRemediationUi,
    /**
     * 这家驱动**确切**的补凭证动作：登录子命令 / 环境变量名（如 `codex login`、
     * `DEEPSEEK_API_KEY`）。由编排层按 driverKey 唯一派生，UI 只把它填进
     * [remediation] 对应的文案模板 —— 不许在 UI 里再写一遍驱动名 if 链。
     *
     * 空串 = 这家不需要单独动作（native / 无凭证面）。**不是**「没判定」的替代：
     * 状态仍由 [state] 与 [evidence] 如实表达。
     */
    val remediationDetail: String = "",
)

/** 端口侧的执行档成员（UI 编辑形状；与编排层 TeamExecutionMemberSpec 逐字段对齐）。 */
data class TeamExecutionMemberUi(
    val agentId: String,
    val kind: String,
    val driverKey: String,
    val models: List<String> = emptyList(),
    val customPrompt: String? = null,
) {
    val isWorker: Boolean get() = kind == TeamExecutionKindsUi.WORKER
}

/** 成员 kind 的取值域（UI 侧单一定义；编排层 TeamExecutionKinds 与它逐字同源）。 */
object TeamExecutionKindsUi {
    const val WORKER: String = "worker"
    const val REVIEWER: String = "reviewer"
}

/**
 * 执行档的编辑形状。主键 = `(dirId, leaderAgentId)` —— **没有 groupId**：
 * 执行配置属于团队，一支队进几个群都是同一份档（写进键里就得配 N 份）。
 */
data class TeamExecutionProfileUi(
    val dirId: String,
    val leaderAgentId: String,
    val members: List<TeamExecutionMemberUi>,
)

sealed interface TeamExecutionSaveResult {
    data object Saved : TeamExecutionSaveResult
    data class Rejected(val reasons: List<String>) : TeamExecutionSaveResult
}

/**
 * 声明层席位 → 执行档**初值**（团队编制对话框打开时预填）：声明里的每个组员一条，
 * 既有执行档的同 agentId 条目**沿用其执行配置**（编辑保全 —— 改名/改角色不重算执行配置）；
 * 没配过的新组员默认第一名为 worker、其余 reviewer，驱动取目录第一个（UI 可改）。
 */
fun initialExecutionProfileUi(
    port: TeamExecutionPort,
    group: GroupMeta?,
    seat: VirtualAgentMember,
): TeamExecutionProfileUi? {
    if (group == null) return null
    // D16：键的一半换成群指针的 dirId —— 没设指针就取不到初值（对话框会置灰）。
    val dirId = port.groupPointerDirId(group.groupId) ?: return null
    val existing = port.profileOf(dirId, seat.agentId)
    val declared = seat.teamMembers
    // **单兵席位也要有执行层**（不变量 5）：没有 teamMembers 时从前直接 null ⇒ 单兵
    // 根本没有「底座/模型」这一层，换底座只能换席位本身（`agentId` 是身份）⇒ 编制与
    // 目录一起丢。这里给单兵生成一条 worker：`agentId` 仍是席位 slug（身份不随底座变），
    // `driverKey` 留空等机主显式指派 —— **不猜**（猜错比空着更贵，空着会被保存校验拦下）。
    if (declared.isEmpty()) {
        val prior = existing?.members?.firstOrNull {
            it.agentId.trim().lowercase() == seat.agentId.trim().lowercase()
        }
        return TeamExecutionProfileUi(
            dirId = dirId,
            leaderAgentId = seat.agentId,
            members = listOf(
                TeamExecutionMemberUi(
                    agentId = seat.agentId,
                    kind = TeamExecutionKindsUi.WORKER,
                    driverKey = prior?.driverKey.orEmpty(),
                    models = prior?.models ?: emptyList(),
                    customPrompt = prior?.customPrompt,
                ),
            ),
        )
    }
    val members = declared.mapIndexed { index, member ->
        val prior = existing?.members?.firstOrNull {
            it.agentId.trim().lowercase() == member.agentId.trim().lowercase()
        }
        TeamExecutionMemberUi(
            agentId = member.agentId,
            kind = prior?.kind ?: if (index == 0) TeamExecutionKindsUi.WORKER else TeamExecutionKindsUi.REVIEWER,
            driverKey = prior?.driverKey ?: "",
            models = prior?.models ?: emptyList(),
        )
    }
    return TeamExecutionProfileUi(dirId, seat.agentId, members)
}

/**
 * UI 侧保存校验（与编排层 save 共用同一份规则口径；端口的 validate 实现会再做一次
 * 完整校验 —— 这里只做**结构前置**，给出可就地显示的原因）。
 */
fun validateExecutionProfileUi(
    profile: TeamExecutionProfileUi,
    declaredMemberIds: Set<String>,
    driverCatalog: Set<String>,
): List<String> {
    val reasons = mutableListOf<String>()
    val workers = profile.members.filter { it.isWorker }
    if (workers.size != 1) reasons.add("exactly one worker required (found " + workers.size + ")")
    val ids = profile.members.map { it.agentId.trim().lowercase() }
    if (ids.toSet().size != ids.size) reasons.add("duplicate member agentId")
    if (profile.members.size > Limits.TEAM_MEMBERS_MAX) {
        reasons.add("team size exceeds limit (" + profile.members.size + ")")
    }
    profile.members.forEach { m ->
        if (m.driverKey !in driverCatalog) {
            reasons.add("member " + m.agentId + ": driver not installed (" + m.driverKey + ")")
        }
        // 结构前置：本期只消费 `models.first`（没有轮换的消费路径），多值存下来就是
        // 死数据。**驱动能力校验不在这里**——那份词表在 `DriverModelFlags`
        // （agent-contract），UI 层再抄一份必然两处漂移；宿主侧
        // `TeamExecutionValidation` 会在真正落盘前按它拒一次。
        if (m.models.size > 1) {
            reasons.add("member " + m.agentId + ": only one model per member is supported (" + m.models.size + ")")
        }
    }
    val declared = declaredMemberIds.map { it.trim().lowercase() }.toSet()
    profile.members.forEach { m ->
        if (m.agentId.trim().lowercase() !in declared) {
            reasons.add("member " + m.agentId + " is not in the declared roster")
        }
    }
    return reasons
}

/**
 * 本群全部组员的 `@` token → (席位, 组员) 的 UI 侧解析（@ 候选与插入用）；
 * 判据单源 = core 的 [resolveTeamMemberToken]。
 */
fun teamMentionCandidates(group: GroupMeta?): List<Pair<VirtualAgentMember, TeamMemberInfo>> {
    if (group == null) return emptyList()
    val out = mutableListOf<Pair<VirtualAgentMember, TeamMemberInfo>>()
    group.virtualAgents.orEmpty().filter { seat ->
        seat.entityType == com.kapp.tenon.core.protocol.EntityTypes.TEAM || seat.teamMembers.isNotEmpty()
    }.forEach { seat ->
        seat.teamMembers.forEach { member ->
            if (member.name.isNotBlank()) out.add(seat to member)
        }
    }
    return out
}
