// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.DelegationTeam
import com.kapp.tenon.agent.drivers.AgentProfiles
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
 * 委派团队模板的宿主侧持久化（编排规范 §16.3）。
 *
 * 文件即接口（入口 B）：`delegation-teams.json` 落在 `controller.dataDirectory`，
 * 与 `agent-grants.json` / `session-exempts.json` 同生命周期 —— 由 `DesktopExtension.attach`
 * 装配、`detach` 释放。**禁止**写 `~/.tenon/`（破坏 `:orchestration` 零 UI / `:cli`
 * 的可移植性）或工作区仓库内部（`.tidings/`）。
 *
 * ## 三态失败语义（§15 第 8 条裁决：fail-closed 不静默换人）
 *
 * | 情形 | 处置 |
 * |---|---|
 * | 文件损坏 / `schemaVersion` 未知 / 结构非法 | **整份视为不可信**：忽略模板（回落保底两棒），`RelayLog` 留诊断 —— 配置不可信时派单不该被卡死，但必须留痕 |
 * | 结构合法但**同厂商** | **保存时拒绝**（`save()` 返回 `false` + 诊断）：门对同厂商一律 `Reject`，早失败早暴露，不让用户存一个注定派不出去的配置 |
 * | 模板合法但成员**不在位** | 不是本类的事：解析器照常产出计划，`StageChainGate` `Reject` 出 blocked 卡 |
 *
 * ## 并发纪律（照 `AgentGrantStore` 的审计结论）
 *
 * 「改内存 + 拼 json + 写文件」整段在同一把**写锁**里 —— 拆开的话两个并发 save
 * 各自拿快照，谁最后落盘谁说了算，先保存的配置会被静默吞掉。原子写 temp + rename，
 * rename 不继承源权限所以 **0600 两个文件都要设**。内存快照按文件 `mtime` 失效：
 * 入口 B（手改文件）与入口 A（对话配置）共用同一份事实。
 */
class DelegationTeamStore(
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val lock = ReentrantReadWriteLock()

    @Volatile
    private var snapshot: Map<String, DelegationTeam> = emptyMap()

    @Volatile
    private var snapshotMtime: Long = INITIAL_MTIME

    /** 与 mtime 组成新鲜度对：同毫秒内的连续写入（保存后立刻手改）只靠 mtime 分不出来。 */
    @Volatile
    private var snapshotLength: Long = INITIAL_LENGTH

    /** 按群取模板；没有 → null（解析器自然回落启发式保底两棒）。 */
    fun teamFor(groupId: String): DelegationTeam? = refreshIfStale()[groupId]

    /**
     * 保存（同 groupId 覆盖）。返回 `false` = 拒绝写入（同厂商 / 键非法）或写盘失败，
     * **绝不吞掉**：「看起来在记、其实没记」是本仓最贵的缺陷形态，失败必须可见
     * （调用方据此给用户报错）。
     */
    fun save(team: DelegationTeam): Boolean {
        // 归一化校验：模板里的键必须**已是**档案键（入口 A 在拦截桥里归一，入口 B 直改文件
        // 后由 load 校验）；这里再验一次是防错前移 —— 带着未归一键存进去，派单面就要面对它。
        val worker = AgentProfiles.normalizeKey(team.workerKey)
        val reviewer = AgentProfiles.normalizeKey(team.reviewerKey)
        if (worker == null || reviewer == null) {
            RelayLog.info(TAG, "save rejected: unknown key(s) " + team.workerKey + "/" + team.reviewerKey)
            return false
        }
        if (worker == reviewer) {
            RelayLog.info(TAG, "save rejected: worker == reviewer (" + worker + ")")
            return false
        }
        val workerVendor = AgentProfiles.vendorOf(worker)
        val reviewerVendor = AgentProfiles.vendorOf(reviewer)
        if (workerVendor == null || reviewerVendor == null || workerVendor == reviewerVendor) {
            RelayLog.info(
                TAG,
                "save rejected: same vendor (" + workerVendor + ") for " + worker + "/" + reviewer,
            )
            return false
        }
        val normalized = team.copy(
            groupId = team.groupId,
            workerKey = worker,
            reviewerKey = reviewer,
            updatedAt = now(),
        )
        lock.write {
            val teams = refreshIfStale().toMutableMap()
            teams[normalized.groupId] = normalized
            if (!persistLocked(teams)) return false
            snapshot = teams
            val stamp = freshnessStamp()
            snapshotMtime = stamp.first
            snapshotLength = stamp.second
        }
        return true
    }

    /** mtime+长度变了（或从未读过）⇒ 在写锁里整份重读。手改文件与对话配置共用同一份事实。 */
    private fun refreshIfStale(): Map<String, DelegationTeam> {
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

    /** (mtime, length)；文件不存在 = (0, 0)。同毫秒连写只靠 mtime 分不出来，长度补一刀。 */
    private fun freshnessStamp(): Pair<Long, Long> =
        if (file.isFile) file.lastModified() to file.length() else MISSING_STAMP

    private fun freshness(): Pair<Long, Long> = snapshotMtime to snapshotLength

    /**
     * 整份读入。三态语义的第一态：文件缺失 = 没配过（正常，静默返回空）；
     * 损坏 / 版本未知 / 结构非法（含档案表里没有的键）= **整份不可信**，记诊断后按空处理。
     */
    private fun loadLocked(): Map<String, DelegationTeam> {
        val text = runCatching { file.readText() }.getOrElse {
            RelayLog.info(TAG, "load failed (unreadable): " + it.message)
            return emptyMap()
        }
        val parsed = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrElse {
            RelayLog.info(TAG, "load failed (corrupt json): ignored, falling back to heuristic roster")
            return emptyMap()
        }
        val version = parsed["schemaVersion"]?.jsonPrimitive?.longOrNull
        if (version != SCHEMA_VERSION) {
            RelayLog.info(TAG, "load failed (schemaVersion=" + version + "): ignored, falling back to heuristic roster")
            return emptyMap()
        }
        val teams = runCatching {
            parsed["teams"]?.jsonArray?.map { entry -> teamOf(entry.jsonObject) }
        }.getOrElse {
            RelayLog.info(TAG, "load failed (illegal structure): ignored, falling back to heuristic roster")
            return emptyMap()
        }
        if (teams == null || teams.any { it == null }) {
            RelayLog.info(TAG, "load failed (illegal entry): ignored, falling back to heuristic roster")
            return emptyMap()
        }
        return teams.filterNotNull().associateBy { it.groupId }
    }

    /** 单条目解析：任何字段缺 / 类型不对 / 键不在档案表 ⇒ null（调用方据此判整份不可信）。 */
    private fun teamOf(entry: JsonObject): DelegationTeam? {
        val groupId = entry["groupId"]?.jsonPrimitive?.contentOrNull ?: return null
        val worker = entry["workerKey"]?.jsonPrimitive?.contentOrNull ?: return null
        val reviewer = entry["reviewerKey"]?.jsonPrimitive?.contentOrNull ?: return null
        val updatedAt = entry["updatedAt"]?.jsonPrimitive?.longOrNull ?: return null
        if (groupId.isBlank()) return null
        // 键必须在档案表里（归一后）—— 名字写错 = 非法结构，不是「先存着以后再说」。
        val w = AgentProfiles.normalizeKey(worker) ?: return null
        val r = AgentProfiles.normalizeKey(reviewer) ?: return null
        return DelegationTeam(groupId, w, r, updatedAt)
    }

    /** 原子写（temp + rename + 0600，照 `AgentGrantStore.persistLocked` 的既有纪律）。 */
    private fun persistLocked(teams: Map<String, DelegationTeam>): Boolean {
        val json = buildJsonObject {
            put("schemaVersion", SCHEMA_VERSION)
            put("teams", buildJsonArray {
                teams.values.sortedBy { it.groupId }.forEach { t ->
                    add(buildJsonObject {
                        put("groupId", t.groupId)
                        put("workerKey", t.workerKey)
                        put("reviewerKey", t.reviewerKey)
                        put("updatedAt", t.updatedAt)
                    })
                }
            })
        }
        return runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.absolutePath + ".tmp")
            tmp.writeText(json.toString())
            // 0600：这是宿主侧的派单策略，不是偏好。rename 不继承源权限，两个文件都要设。
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
        const val TAG = "TEAM"
        const val SCHEMA_VERSION: Long = 1
        const val INITIAL_MTIME = -1L
        const val INITIAL_LENGTH = -1L
        val MISSING_STAMP: Pair<Long, Long> = 0L to 0L
    }
}
