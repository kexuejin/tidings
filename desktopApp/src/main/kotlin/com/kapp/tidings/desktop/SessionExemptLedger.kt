// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * L1 会话免审的**宿主侧台账**（落盘）—— §4.2「分级会话免审」的桌面落点。
 *
 * ## 它回答的问题，与既有两张表各管一段
 *
 * | 表 | 粒度 | 语义 |
 * |---|---|---|
 * | `AgentToolApprovalStore` | (设备, agent, tool 名) | 「这个工具以后都别问」（跨会话） |
 * | 本类 | (会话键, agentKey) | 「这个会话里的低危动作别再问」（会话粒度，带过期） |
 *
 * ## 过期语义（关键）
 *
 * 会话免审**不是无限期**：会话结束 / 台账淘汰都可能让键失效，落表必须带 `expiresAt`。
 * 缺省租约 [`DEFAULT_TTL_MS`] 与审批 TTL（Limits.APPROVAL_TTL_MS，5 分钟）同量级——
 * 手机上勾选的是「本次会话」而不是「从此以后」，租约到期免审自动收口，方向 fail-closed。
 * 查表时 `now >= expiresAt` 判不命中并顺手清掉过期行（丢的是免审，方向收紧）。
 *
 * ## 读写纪律（与 AgentToolApprovalStore 同一条）
 *
 * - 文件读不回来 ⇒ 空表 ⇒ 照常问（丢免审只多问一次，反方向才是替人批准）。
 * - 改内存与落盘在**同一把写锁**里；改完在锁外回调 [`onChange`]（面板据此刷新）。
 * - 0600 落盘：这份文件是安全策略。
 *
 * 会话键：优先用 agent 会话键（控制面台账的 sessionId）；动作帧带不出会话时由调用方
 * 按 agent 解析最近会话兜底 —— 键的**形状**由调用方决定，本表只保证逐字匹配。
 */
internal class SessionExemptLedger(private val file: File) {

    /** 一条免审：会话键 + agent 键 + 过期时刻。缺一不落表（没有范围的放行无法表达「只放行哪个会话」）。 */
    data class Grant(
        val sessionKey: String,
        val agentKey: String,
        val expiresAt: Long,
        val grantedAt: Long,
        val grantedBy: String?,
    )

    /** 查表判定的输出（区分「没表」与「有过期表」，调用方的日志措辞不同）。 */
    enum class Verdict { HIT, EXPIRED, MISS }

    /** 缺省租约：与审批 TTL（Limits.APPROVAL_TTL_MS）同量级 —— 会话粒度不是无限期。 */
    internal val DEFAULT_TTL_MS: Long = 300_000L

    /** 「表变了」回调（面板刷新用）；锁外触发。 */
    var onChange: (() -> Unit)? = null

    private val rwLock = ReentrantReadWriteLock()
    private val grants = LinkedHashSet<Grant>()

    init {
        load()
    }

    /** 全部有效（未过期）免审，按授予时刻降序 —— 设置页后续展示用。 */
    fun snapshot(now: Long): List<Grant> = rwLock.read {
        grants.filter { now < it.expiresAt }.sortedByDescending { it.grantedAt }
    }

    /**
     * 落一条免审（幂等：同 (sessionKey, agentKey) 重落只续期）。
     * @return false = 参数不成立（空会话键 / 空 agentKey），**什么都没改** ——
     *   不静默写成通配，那会把无名规则变成全放行。
     */
    fun grant(
        sessionKey: String,
        agentKey: String,
        grantedBy: String? = null,
        at: Long,
        ttlMs: Long = DEFAULT_TTL_MS,
    ): Boolean {
        val key = sessionKey.trim().takeIf { it.isNotEmpty() } ?: return false
        val agent = agentKey.trim().takeIf { it.isNotEmpty() } ?: return false
        val ttl = ttlMs.coerceAtLeast(1L)
        rwLock.write {
            grants.removeAll { it.sessionKey == key && it.agentKey == agent }
            grants += Grant(
                sessionKey = key,
                agentKey = agent,
                expiresAt = at + ttl,
                grantedAt = at,
                grantedBy = grantedBy?.trim()?.takeIf { it.isNotEmpty() },
            )
            persistLocked()
        }
        onChange?.invoke()
        return true
    }

    /** 查表：命中返回 HIT；有过期行返回 EXPIRED 并顺手清掉（丢的是免审，方向收紧）。 */
    fun verdictOf(sessionKey: String, agentKey: String?, now: Long): Verdict {
        val key = sessionKey.trim()
        if (key.isEmpty()) return Verdict.MISS
        return rwLock.write {
            val rows = grants.filter { it.sessionKey == key }
            if (rows.isEmpty()) return@write Verdict.MISS
            val live = rows.any { r ->
                now < r.expiresAt && (agentKey == null || agentKey == r.agentKey)
            }
            if (live) {
                Verdict.HIT
            } else {
                // 该会话的行全部过期（或 agentKey 不匹配）：过期行清掉，匹配不上**不清** ——
                // 那是别的 agent 的免审，删了它等于替别的会话收紧成假账。
                val doomed = rows.filter { now >= it.expiresAt }
                if (doomed.isNotEmpty()) {
                    grants.removeAll(doomed.toSet())
                    persistLocked()
                }
                Verdict.EXPIRED
            }
        }.also { if (it == Verdict.EXPIRED) onChange?.invoke() }
    }

    /** 该会话当前是否处于免审（[verdictOf] 的布尔快捷方式）。 */
    fun isExempt(sessionKey: String, agentKey: String?, now: Long): Boolean =
        verdictOf(sessionKey, agentKey, now) == Verdict.HIT

    /** 撤销一个会话的免审（设置页/撤销入口用）。返回删掉的条数。 */
    fun revoke(sessionKey: String, agentKey: String? = null): Int {
        var removed = 0
        rwLock.write {
            val doomed = grants.filter {
                it.sessionKey == sessionKey.trim() && (agentKey == null || it.agentKey == agentKey)
            }
            removed = doomed.size
            grants.removeAll(doomed.toSet())
            if (removed > 0) persistLocked()
        }
        if (removed > 0) onChange?.invoke()
        return removed
    }

    // ------------------------------------------------------------------ 持久化

    private fun load() {
        if (!file.isFile) return
        val text = runCatching { file.readText() }.getOrNull() ?: return
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return
        val arr = root["grants"] as? JsonArray ?: return
        rwLock.write {
            arr.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val sessionKey = o.str("sessionKey")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val agentKey = o.str("agentKey")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Grant(
                    sessionKey = sessionKey,
                    agentKey = agentKey,
                    expiresAt = (o["expiresAt"] as? JsonPrimitive)?.longOrNull ?: 0L,
                    grantedAt = (o["grantedAt"] as? JsonPrimitive)?.longOrNull ?: 0L,
                    grantedBy = o.str("grantedBy"),
                )
            }.forEach { grants += it }
        }
    }

    /** 落盘。**调用方必须已持有写锁**（与 AgentToolApprovalStore.persistLocked 同一约定）。 */
    private fun persistLocked() {
        val json = buildJsonObject {
            put("grants", buildJsonArray {
                grants.forEach { g ->
                    add(buildJsonObject {
                        put("sessionKey", JsonPrimitive(g.sessionKey))
                        put("agentKey", JsonPrimitive(g.agentKey))
                        put("expiresAt", JsonPrimitive(g.expiresAt))
                        put("grantedAt", JsonPrimitive(g.grantedAt))
                        g.grantedBy?.let { put("grantedBy", JsonPrimitive(it)) }
                    })
                }
            })
        }
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.absolutePath + ".tmp")
            tmp.writeText(json.toString())
            // 0600：安全策略文件（哪些会话不用再问），同机其它用户能写它就等于能给自己免审。
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
        }
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull
}
