// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.drivers.AgentProfiles
import com.kapp.tenon.agent.drivers.DriverModelFlags
import com.kapp.tenon.core.protocol.Limits
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 团队执行档的一条成员：编制里的组员 → **宿主本地的执行配置**（团队执行方案 §3.2）。
 *
 * `agentId` 对应线格式里组员的稳定 slug（**编辑保全契约**：声明改名不重算）；
 * `driverKey` 是 [AgentProfiles] 的档案键；`models` 首位生效、空表 = 继承驱动默认。
 * 只消费首位是**有意的**（见 [TeamExecutionValidation]）：没有轮换消费路径就把多值存下来，
 * 等于让「配了三个模型」变成一句空话。
 */
data class TeamExecutionMemberSpec(
    val agentId: String,
    val kind: String,
    val driverKey: String,
    val models: List<String> = emptyList(),
    val customPrompt: String? = null,
) {
    val isWorker: Boolean get() = kind == TeamExecutionKinds.WORKER
}

/**
 * 一支团队的执行档。主键 = `(dirId, leaderAgentId)`（D16：dirId 是宿主本机目录档案的
 * 稳定 id，**不随 agent 重新宣告 wsId 变化**；`leaderAgentId` 是声明里的稳定 id，
 * 不是显示 token）。
 *
 * ## 主键里为什么没有 groupId
 *
 * 一支团队进多个群是**一等场景**（一份名册、每群各一条席位）。键里带 groupId 的后果是
 * 「同一支队在同一目录下要配 N 份」：`TeamRosterDialog` 的执行配置段按群打开，用户在 A 群
 * 配完，B 群的 `@团队` 读的是另一条键 ⇒ `team execution is not configured`
 * （blocked 卡，不是静默失败，但用户完全不知道为什么「刚配过」）。执行配置属于**团队**，
 * 不属于「团队在某群的那条席位」，所以键只留这两个真的会变的东西。
 *
 * 已知边界（fail-closed 方向）：同一个 agent 在两个群里各当**不同两支团队**的领队、
 * 且两群指向**同一个目录** ⇒ 两队共用一条档。这种名册不符不会被静默吃掉 ——
 * `TeamExecutionResolver.profileForSeat` 的悬空检查会以
 * `execution profile is stale: … no longer in the roster` 拒掉。
 *
 * **执行配置只存在宿主本地** —— 不进 group 帧、不广播给全群；群声明仍然只带身份与角色。
 */
data class TeamExecutionProfile(
    val dirId: String,
    val leaderAgentId: String,
    val updatedAt: Long,
    val members: List<TeamExecutionMemberSpec>,
) {
    /**
     * **全员可干活的人**（`kind = worker`）。1 人 = 唯一干活人；N 人 = 候选池，本单谁干
     * 由主理人按任务点将（见 `PlannerChainGate`）。顺序即编制顺序，`first()` 是**默认**
     * 锚点 —— `@团队名` / `@主理人` 仍落到它，主理人不改就还是旧行为。
     */
    val workers: List<TeamExecutionMemberSpec> get() = members.filter { it.isWorker }

    /** 默认干活人（编制首位）。点将未生效时的一切回落都读这一格。 */
    val worker: TeamExecutionMemberSpec? get() = workers.firstOrNull()
    val reviewers: List<TeamExecutionMemberSpec> get() = members.filter { !it.isWorker }
}

/** 成员 kind 的取值域（单一定义；保存与读取都过它）。 */
object TeamExecutionKinds {
    const val WORKER: String = "worker"
    const val REVIEWER: String = "reviewer"
    val ALL: Set<String> = setOf(WORKER, REVIEWER)
}

/** 保存结果：失败必须给出结构化原因（哪个成员、哪条规则），UI 直接展示 —— 不允许"先存着"。 */
sealed interface TeamExecutionSaveVerdict {
    data object Saved : TeamExecutionSaveVerdict
    data class Rejected(val reasons: List<String>) : TeamExecutionSaveVerdict
}

/**
 * 保存/读取共用的**纯校验**（§3.4）：把"注定派不出去"的配置在保存时拒掉。
 * 在位检查（"装了没"）不在这里 —— 那是派单时 [StageChainGate] 的事实；这里只验结构。
 *
 * @param declaredMemberIds 该席位线格式 `teamMembers` 的 agentId 全集；null = 暂无声明
 *   可比对（跳过 ⊆ 校验，读取侧还有悬空兜底）。
 * @param knownModels 各驱动**当下真的列出**的模型 id（`driverKey → ids`），来自宿主侧的
 *   模型目录探测。null / 缺键 = 没探到（放行）；已知但不含该模型 ⇒ 拒。判据必须是
 *   「已知且不含」而不是「不在表里」—— 后者会把离线、未登录、解析失败时的合法模型全拒掉。
 */
object TeamExecutionValidation {

    fun validate(
        profile: TeamExecutionProfile,
        declaredMemberIds: Set<String>? = null,
        knownModels: Map<String, Set<String>>? = null,
    ): List<String> {
        val reasons = mutableListOf<String>()
        val members = profile.members
        val workers = profile.workers
        // **0 人不行，多人合法**：`kind` 声明的是「这个人能不能干活」，不是「谁是唯一干活人」。
        // 多人都标 worker 时，本单由谁下场是主理人的点将（`PlannerChainGate`），编制不该
        // 替它锁死；但一个人都不能干活就没得派 —— 那一档仍在保存期拒。
        if (workers.isEmpty()) {
            reasons.add("at least one worker required (found 0)")
        }
        if (members.size > Limits.TEAM_MEMBERS_MAX) {
            reasons.add("team size exceeds limit (" + members.size + " > " + Limits.TEAM_MEMBERS_MAX + ")")
        }
        val ids = members.map { it.agentId.trim().lowercase() }
        if (ids.any { it.isEmpty() }) reasons.add("member agentId must not be blank")
        if (ids.toSet().size != ids.size) reasons.add("duplicate member agentId in execution profile")
        for (m in members) {
            if (m.kind !in TeamExecutionKinds.ALL) {
                reasons.add("member " + m.agentId + ": unknown kind '" + m.kind + "'")
            }
            if (AgentProfiles.normalizeKey(m.driverKey) == null) {
                reasons.add("member " + m.agentId + ": unknown driverKey '" + m.driverKey + "'")
            }
            if (m.models.size > Limits.AGENT_MODELS_MAX) {
                reasons.add("member " + m.agentId + ": too many models (" + m.models.size + ")")
            }
            // **存下来就必须生效**：本期只消费 `models.first`（见 `TeamPlanCompiler`
            // → `ChainStage.model` → `executeCliTask(model=)`）。多值没有轮换/回退的
            // 消费路径，允许它就等于再造一批「配置里有、跑起来不用」的死数据。
            if (m.models.size > 1) {
                reasons.add(
                    "member " + m.agentId + ": only one model per member is supported " +
                        "(got " + m.models.size + ")",
                )
            }
            // 驱动能力事实：不支持按次指定模型的驱动（zcode 只能在应用内选）填了模型
            // 必须当场拒 —— 否则用户以为选了模型，实际跑默认。
            // 注意 dsh **不在此列**：它没有旗标但支持按次指定（走 --patch 配置覆盖）。
            if (m.models.any { it.isNotBlank() } && !DriverModelFlags.supports(m.driverKey)) {
                reasons.add(
                    "member " + m.agentId + ": driver '" + m.driverKey +
                        "' cannot take a model per task (" +
                        (DriverModelFlags.unsupportedReason(m.driverKey, m.models.first()) ?: "no model flag") + ")",
                )
            }
            m.models.forEach { model ->
                if (model.isBlank() || model.length > Limits.AGENT_MODEL_ID) {
                    reasons.add("member " + m.agentId + ": illegal model id '" + model.take(70) + "'")
                }
            }
            // 目录已知但不含该模型 ⇒ 保存期拒（拦住「填了个不存在的值，跑到 CLI 才失败」）。
            // 目录缺失 / 该驱动 UNKNOWN / 从没探过（knownModels == null）一律放行：
            // 「没验到」不是「验坏了」，离线时拒掉全部合法模型是更贵的缺陷。
            val catalogIds = knownModels?.let { ids ->
                AgentProfiles.normalizeKey(m.driverKey)?.let(ids::get)
            }
            if (catalogIds != null) {
                m.models.filter { it.isNotBlank() && it !in catalogIds }.forEach { bad ->
                    reasons.add(
                        "member " + m.agentId + ": model '" + bad.take(70) +
                            "' is not in driver '" + m.driverKey + "' catalog",
                    )
                }
            }
        }
        // **异厂商审核对「每一位」可干活的人成立**（不是只对第一位）：点将可以把任何
        // worker 推到链首，只跟默认那位比厂商，等于给「换个人干」留了一条同厂商自审的
        // 后门。判据放宽一格的代价是保存期偶尔多拒一次配置，换来的是「存下来的组合
        // 一定派得出去」—— 与 `StageChainGate` 的运行期判据同向。
        members.filter { !it.isWorker }.forEach { reviewer ->
            val reviewerVendor = AgentProfiles.vendorOf(reviewer.driverKey)
            workers.forEach { candidate ->
                val workerVendor = AgentProfiles.vendorOf(candidate.driverKey)
                if (workerVendor != null && reviewerVendor == workerVendor) {
                    reasons.add(
                        "reviewer " + reviewer.agentId + " must be a different vendor than worker " +
                            candidate.agentId + " (" + workerVendor + ")",
                    )
                }
            }
        }
        if (declaredMemberIds != null) {
            val declared = declaredMemberIds.map { it.trim().lowercase() }.toSet()
            members.forEach { m ->
                if (m.agentId.trim().lowercase() !in declared) {
                    reasons.add("member " + m.agentId + " is not in the declared roster")
                }
            }
        }
        return reasons
    }
}

/**
 * 团队执行档的宿主侧持久化（§3.2）。落盘纪律与 [DelegationTeamStore] **逐字同源**
 * （`controller.dataDirectory`、schemaVersion、写锁、mtime+长度失效、原子写、0600）；
 * 差异只在数据形状与校验规则（[TeamExecutionValidation]）。
 *
 * ## 三态失败语义（与 [DelegationTeamStore] 逐字一致）
 *
 * 文件缺失 = 没配过（正常）；损坏 / schemaVersion 未知 / 结构非法 = **整份不可信**
 * （按空处理 + `RelayLog` 留诊断）；成员悬空（声明里删了人）= 读时判过期（§3.5）。
 */
/** 自定义人设提示词上限（字符）。 */
private const val MAX_CUSTOM_PROMPT_CHARS = 2000

class TeamExecutionStore(
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val lock = ReentrantReadWriteLock()

    @Volatile
    private var snapshot: List<TeamExecutionProfile> = emptyList()

    @Volatile
    private var snapshotMtime: Long = INITIAL_MTIME

    @Volatile
    private var snapshotLength: Long = INITIAL_LENGTH

    /** 主键定位：`(目录, 领队)` —— 与群无关，一支队进几个群都是同一份档。 */
    fun profileFor(dirId: String, leaderAgentId: String): TeamExecutionProfile? =
        refreshIfStale().firstOrNull {
            it.dirId == dirId && it.leaderAgentId == leaderAgentId
        }

    /**
     * 保存（同主键覆盖）。[declaredMemberIds] 非空时额外校验档 ⊆ 声明（§3.4.6）。
     * 返回 [TeamExecutionSaveVerdict.Rejected] = 结构非法或写盘失败，**绝不吞掉**。
     */
    fun save(
        profile: TeamExecutionProfile,
        declaredMemberIds: Set<String>? = null,
        knownModels: Map<String, Set<String>>? = null,
    ): TeamExecutionSaveVerdict {
        val reasons = TeamExecutionValidation.validate(profile, declaredMemberIds, knownModels)
        if (reasons.isNotEmpty()) {
            RelayLog.info(TAG, "save rejected: " + reasons.joinToString("; "))
            return TeamExecutionSaveVerdict.Rejected(reasons)
        }
        // driverKey 归一后再落盘（与 DelegationTeamStore 同一条防错前移纪律）；
        // kind 已过 TeamExecutionKinds 校验，原样保留。
        val normalized = profile.copy(
            members = profile.members.map { m ->
                m.copy(driverKey = AgentProfiles.normalizeKey(m.driverKey) ?: m.driverKey)
            },
            updatedAt = now(),
        )
        lock.write {
            val teams = refreshIfStale().toMutableList()
            teams.removeAll {
                it.dirId == normalized.dirId && it.leaderAgentId == normalized.leaderAgentId
            }
            teams.add(normalized)
            if (!persistLocked(teams)) return TeamExecutionSaveVerdict.Rejected(listOf("failed to persist execution profile"))
            snapshot = teams
            val stamp = freshnessStamp()
            snapshotMtime = stamp.first
            snapshotLength = stamp.second
        }
        return TeamExecutionSaveVerdict.Saved
    }

    /**
     * 团队解散时同步移除该支队的执行档（§3.5）：防止存留无用幽灵记录。
     * 条目不存在是正常情形（返回 false 不代表失败）——例如同一目录下两个群都调过一次。
     */
    fun remove(dirId: String, leaderAgentId: String): Boolean {
        return lock.write {
            val teams = refreshIfStale().toMutableList()
            val removed = teams.removeAll {
                it.dirId == dirId && it.leaderAgentId == leaderAgentId
            }
            if (!removed) return@write false
            if (!persistLocked(teams)) return@write false
            snapshot = teams
            val stamp = freshnessStamp()
            snapshotMtime = stamp.first
            snapshotLength = stamp.second
            true
        }
    }

    private fun refreshIfStale(): List<TeamExecutionProfile> {
        val stamp = freshnessStamp()
        lock.read { if (stamp == freshness()) return snapshot }
        return lock.write {
            val current = freshnessStamp()
            if (current == freshness()) return@write snapshot
            val loaded = loadLocked()
            snapshot = loaded
            snapshotMtime = current.first
            snapshotLength = current.second
            loaded
        }
    }

    private fun freshnessStamp(): Pair<Long, Long> =
        if (file.isFile) file.lastModified() to file.length() else MISSING_STAMP

    private fun freshness(): Pair<Long, Long> = snapshotMtime to snapshotLength

    private fun loadLocked(): List<TeamExecutionProfile> {
        val text = runCatching { file.readText() }.getOrElse {
            RelayLog.info(TAG, "load failed (unreadable): " + it.message)
            return emptyList()
        }
        val parsed = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrElse {
            RelayLog.info(TAG, "load failed (corrupt json): ignored")
            return emptyList()
        }
        val version = parsed["schemaVersion"]?.jsonPrimitive?.longOrNull
        if (version != SCHEMA_VERSION && version != GROUP_SCOPED_SCHEMA_VERSION) {
            RelayLog.info(TAG, "load failed (schemaVersion=" + version + "): ignored")
            return emptyList()
        }
        val teams = runCatching {
            parsed["teams"]?.jsonArray?.map { entry -> profileOf(entry.jsonObject) }
        }.getOrElse {
            RelayLog.info(TAG, "load failed (illegal structure): ignored")
            return emptyList()
        }
        if (teams == null || teams.any { it == null }) {
            RelayLog.info(TAG, "load failed (illegal entry): ignored")
            return emptyList()
        }
        val loaded = teams.filterNotNull()
        // v2 的键比现在多一个 groupId ⇒ 同一支队在 N 个群里有 N 条。**读时就折叠**（保留
        // updatedAt 最新那条），而不是让调用方自己挑：三处读取点各挑一次必然分叉。
        // 不在这里立刻回写 —— 下一次 save 自然落成 v3，没再保存过的旧文件继续按折叠结果读。
        return if (version == GROUP_SCOPED_SCHEMA_VERSION) loaded.collapseGroupScoped() else loaded
    }

    private fun List<TeamExecutionProfile>.collapseGroupScoped(): List<TeamExecutionProfile> =
        groupBy { it.dirId to it.leaderAgentId }
            .map { (_, same) -> same.maxByOrNull { it.updatedAt } ?: same.first() }

    private fun profileOf(entry: JsonObject): TeamExecutionProfile? {
        val dirId = entry["dirId"]?.jsonPrimitive?.contentOrNull ?: return null
        val leaderAgentId = entry["leaderAgentId"]?.jsonPrimitive?.contentOrNull ?: return null
        val updatedAt = entry["updatedAt"]?.jsonPrimitive?.longOrNull ?: return null
        if (dirId.isBlank() || leaderAgentId.isBlank()) return null
        val members = runCatching {
            entry["members"]?.jsonArray?.map { m ->
                val o = m.jsonObject
                val agentId = o["agentId"]?.jsonPrimitive?.contentOrNull ?: return null
                val kind = o["kind"]?.jsonPrimitive?.contentOrNull ?: return null
                val driverKey = o["driverKey"]?.jsonPrimitive?.contentOrNull ?: return null
                if (AgentProfiles.normalizeKey(driverKey) == null) return null
                val models = o["models"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                // 人设是提示词前置，必须有硬上限：否则一次粘贴就能把任务书挤爆。
                val customPrompt = o["customPrompt"]?.jsonPrimitive?.contentOrNull
                    ?.trim()?.take(MAX_CUSTOM_PROMPT_CHARS)?.takeIf { it.isNotEmpty() }
                TeamExecutionMemberSpec(agentId, kind, driverKey, models, customPrompt)
            }
        }.getOrElse { return null }
        if (members == null || members.isEmpty()) return null
        return TeamExecutionProfile(dirId, leaderAgentId, updatedAt, members)
    }

    private fun persistLocked(teams: List<TeamExecutionProfile>): Boolean {
        val json = buildJsonObject {
            put("schemaVersion", SCHEMA_VERSION)
            put("teams", buildJsonArray {
                teams.sortedBy { it.dirId + "|" + it.leaderAgentId }.forEach { t ->
                    add(buildJsonObject {
                        put("dirId", t.dirId)
                        put("leaderAgentId", t.leaderAgentId)
                        put("updatedAt", t.updatedAt)
                        put("members", buildJsonArray {
                            t.members.forEach { m ->
                                add(buildJsonObject {
                                    put("agentId", m.agentId)
                                    put("kind", m.kind)
                                    put("driverKey", m.driverKey)
                                    put("models", buildJsonArray { m.models.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                                    m.customPrompt?.take(MAX_CUSTOM_PROMPT_CHARS)?.let { put("customPrompt", it) }
                                })
                            }
                        })
                    })
                }
            })
        }
        return runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.absolutePath + ".tmp")
            tmp.writeText(json.toString())
            // 0600：这是宿主侧的执行配置（含驱动/模型选择），不是偏好。
            tmp.setReadable(false, false)
            tmp.setReadable(true, true)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
            file.setReadable(false, false)
            file.setReadable(true, true)
            file.setWritable(false, false)
            file.setWritable(true, true)
            true
        }.getOrElse {
            RelayLog.info(TAG, "save failed (io): " + it.message)
            false
        }
    }

    private companion object {
        const val TAG = "TEAM-EXEC"
        // v3：主键去掉 groupId（`(dirId, leaderAgentId)`）—— 一支队进 N 个群只需要配一份。
        // v2 文件**读时折叠**（同键保留 updatedAt 最新那条），下一次保存落成 v3：
        // 这里不留旧键是没意义的（dirId 与 leaderAgentId 都在，折叠不丢信息）。
        // v1（键是 defaultWsId，不是 dirId）仍走「schemaVersion 不认识 ⇒ 整份按空」的既有
        // 清库语义 —— 那一版的 id 与现在的目录档案对不上，没有可迁移的东西。
        const val SCHEMA_VERSION: Long = 3
        const val GROUP_SCOPED_SCHEMA_VERSION: Long = 2
        const val INITIAL_MTIME = -1L
        const val INITIAL_LENGTH = -1L
        val MISSING_STAMP: Pair<Long, Long> = 0L to 0L
    }
}
