// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

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
 * 「这个 **tool** 以后不用再问我」—— 宿主侧的**按工具名**免批表（落盘）。
 *
 * ## 它回答的问题，与 [AgentGrantStore] 不是同一个
 *
 * | 表 | 回答 |
 * |---|---|
 * | [AgentGrantStore] | **哪台设备**能看/能执行我的 agent |
 * | [AgentExecPolicyStore] | 某个 agent 开放到**什么程度**（谁能触发它） |
 * | 本类 | 某个 agent 的**某个工具**，批准过一次之后还要不要再问 |
 *
 * 三层都是"更严"的方向：少了任何一层，答案都是"要问"/"不行"。
 *
 * ## 为什么粒度必须是 tool，不是 agent
 *
 * 「允许它跑 `git status`」与「允许它跑 `rm -rf`」是同一个 agent 的两个动作，
 * 风险差着量级。按 agent 免批等于：用户为了少点一次"同意看目录"，把整个 agent 的
 * 高危动作也一并放行了 —— 那不是省事，是把审批这道门拆了。所以键里必须有 tool 名，
 * 且 **tool 为空时永不命中**（没有名字的规则无法表达"只放行哪一个"）。
 *
 * ## 为什么键里还有 nodeId
 *
 * 「始终允许」是**某个人**在自己设备上按下的决定。只按 (agent, tool) 记，
 * 等于 A 按过一次，B 的设备从此也不用问了 —— 而 B 从没同意过。
 * 与 [AgentGrantStore] 的 `allows` 同一个立场：授权的单位是**设备**。
 *
 * ## 读不回来时是"要问"，不是"放行"
 *
 * 文件不存在 / 读不出 / 解析不了 ⇒ **空表**。空表的效果是"下次照常问"，
 * 这是唯一安全的方向：丢了一条免批只会让用户多点一次，反方向则是替用户批准。
 * 与 [AgentGrantStore] 的 fail-closed 同一条纪律，只是这份表的最严档就是"没有规则"。
 *
 * 线程安全：控制面线程池与入站协程都会读写，全部走 [rwLock]；改内存与落盘在**同一把
 * 写锁**里（理由与 [AgentGrantStore.persistLocked] 相同：并发写会互相覆盖）。
 */
class AgentToolApprovalStore(private val file: File) {

    /**
     * 一条免批规则。
     *
     * [agentKey] 为空 = 这台设备上**所有** agent 的该 tool 名（只在调用方本来就不知道
     * agent 时出现，例如会话台账里那条会话没有 `agentKind`）。判定时按"通配"处理，
     * 但仍然要求 nodeId 与 tool 精确相等。
     */
    data class Rule(
        val nodeId: String,
        val agentKey: String?,
        /**
         * 工作区维度（F3/C5，更严档裁决）：**null 只命中 null** —— 授予来源会话
         * 无 ws 时记 null，只对同样无 ws 的派单生效；不存在「null = 全部工作区」
         * 的分支。存量的三参旧规则（无 wsId 键）随开发期清库重配消失。
         */
        val wsId: String?,
        val tool: String,
        val grantedAt: Long,
        val grantedBy: String?,
    )

    private val rwLock = ReentrantReadWriteLock()
    private var rules: MutableList<Rule> = mutableListOf()

    /**
     * 「表变了」的回调（与 [AgentGrantStore.changeListeners] 同一套写法）。
     *
     * 为什么这份表也需要它：写它的人**不在这块 UI 上** —— 对端按「始终允许」发一条
     * `agent-ack.kind=always`，宿主在控制面线程里落表。没有通知的话，桌面设置页开着
     * 也看不到刚产生的规则，用户会以为那一下没生效（或以为表里只有他手动撤掉的几条）。
     * 回调在锁**外**触发，同一线程按注册顺序同步调用。
     */
    private val changeListeners = mutableListOf<() -> Unit>()

    init {
        load()
    }

    /** 注册一个「表变了」的回调；返回值用于注销。 */
    fun addChangeListener(listener: () -> Unit): () -> Unit {
        synchronized(changeListeners) { changeListeners += listener }
        return { synchronized(changeListeners) { changeListeners -= listener } }
    }

    private fun notifyChanged() {
        val snapshotOfListeners = synchronized(changeListeners) { changeListeners.toList() }
        snapshotOfListeners.forEach { runCatching { it() } }
    }

    /** 全部规则（只读快照，按 `grantedAt` 降序）。 */
    fun snapshot(): List<Rule> = rwLock.read { rules.sortedByDescending { it.grantedAt } }

    /**
     * 这一次审批还要不要问人。
     *
     * `true` = 这台设备、这个 agent、这个 tool 已经免批。三个参数任一为空/空串 ⇒ `false`：
     * 判定缺信息时**只能更严**（问一次比替人批准便宜得多）。
     */
    fun isAlwaysApproved(nodeId: String, agentKey: String?, tool: String?, wsId: String? = null): Boolean {
        val node = nodeId.trim().takeIf { it.isNotEmpty() } ?: return false
        val name = tool?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val agent = agentKey?.trim()?.takeIf { it.isNotEmpty() }
        val ws = wsId?.trim()?.takeIf { it.isNotEmpty() }
        return rwLock.read {
            rules.any { r ->
                r.nodeId == node && r.tool == name &&
                    (r.agentKey == null || r.agentKey == agent) &&
                    // **null 只命中 null**（F3 更严档）：ws 维度取自授予来源会话。
                    r.wsId == ws
            }
        }
    }

    /**
     * 记下一条免批规则（幂等：同 (nodeId, agentKey, wsId, tool) 重入只刷新时间与授权人）。
     *
     * @return false = 参数不成立（空 tool / 空 nodeId），**什么都没改** ——
     *   不静默写成"通配"，那会把一条无名规则变成全放行。
     */
    fun remember(
        nodeId: String,
        agentKey: String?,
        tool: String?,
        grantedBy: String? = null,
        at: Long,
        wsId: String? = null,
    ): Boolean {
        val node = nodeId.trim().takeIf { it.isNotEmpty() } ?: return false
        val name = tool?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val agent = agentKey?.trim()?.takeIf { it.isNotEmpty() }
        val ws = wsId?.trim()?.takeIf { it.isNotEmpty() }
        rwLock.write {
            rules.removeAll { it.nodeId == node && it.agentKey == agent && it.tool == name && it.wsId == ws }
            rules += Rule(node, agent, ws, name, at, grantedBy)
            trimLocked()
            persistLocked()
        }
        notifyChanged()
        return true
    }

    /** 撤销一条（或这台设备上该 agent 的全部）免批。返回删掉的条数。 */
    fun revoke(nodeId: String, agentKey: String? = null, tool: String? = null): Int {
        var removed = 0
        rwLock.write {
            val doomed = rules.filter {
                it.nodeId == nodeId &&
                    (agentKey == null || it.agentKey == agentKey) &&
                    (tool == null || it.tool == tool)
            }
            removed = doomed.size
            rules.removeAll(doomed.toSet())
            persistLocked()
        }
        if (removed > 0) notifyChanged()
        return removed
    }

    /**
     * 撤销**界面上那一条**：三元逐字相等才算命中。
     *
     * ## 为什么不直接用 [revoke]
     *
     * [revoke] 的 `agentKey = null` 说的是「**不按 agent 过滤**」（那是它给
     * 「按设备整批撤」留的语义），而 [Rule.agentKey] 的 `null` 说的是另一件事：
     * 「这条规则覆盖这台设备上**所有** agent 的该 tool」。两个 `null` 含义相反 ——
     * 拿后者去调前者，用户点「撤销这一条」会把同设备同 tool 的**其它 agent 专属规则
     * 一起删掉**。方向是收紧（多撤销只会多问一次，不会多放行），但它让界面变成了
     * 假账：列表上还留着别的行，用户以为它们仍在免批，实际已经被顺手删了。
     *
     * 所以面板的逐条撤销走这里；[revoke] 保留原语义给「整批撤」用。
     */
    fun revokeExactly(nodeId: String, agentKey: String?, tool: String, wsId: String? = null): Int {
        var removed = 0
        rwLock.write {
            val doomed = rules.filter {
                it.nodeId == nodeId && it.agentKey == agentKey && it.wsId == wsId && it.tool == tool
            }
            removed = doomed.size
            rules.removeAll(doomed.toSet())
            if (removed > 0) persistLocked()
        }
        if (removed > 0) notifyChanged()
        return removed
    }

    // ------------------------------------------------------------------ 持久化

    private fun load() {
        if (!file.isFile) return
        val text = runCatching { file.readText() }.getOrNull() ?: return
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return
        val arr = root["rules"] as? JsonArray ?: return
        rwLock.write {
            rules = arr.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val nodeId = o.str("nodeId")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                // 没有 tool 名的条目**丢掉**：它无法表达"只放行哪一个"，留着等于全放行。
                val tool = o.str("tool")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Rule(
                    nodeId = nodeId,
                    agentKey = o.str("agentKey")?.takeIf { it.isNotBlank() },
                    wsId = o.str("wsId")?.takeIf { it.isNotBlank() },
                    tool = tool,
                    grantedAt = (o["grantedAt"] as? JsonPrimitive)?.longOrNull ?: 0L,
                    grantedBy = o.str("grantedBy"),
                )
            }.toMutableList()
            trimLocked()
        }
    }

    /** 落盘。**调用方必须已持有写锁**（与 [AgentGrantStore.persistLocked] 同一约定）。 */
    private fun persistLocked() {
        val json = buildJsonObject {
            put("rules", buildJsonArray {
                rules.forEach { r ->
                    add(buildJsonObject {
                        put("nodeId", JsonPrimitive(r.nodeId))
                        r.agentKey?.let { put("agentKey", JsonPrimitive(it)) }
                        r.wsId?.let { put("wsId", JsonPrimitive(it)) }
                        put("tool", JsonPrimitive(r.tool))
                        put("grantedAt", JsonPrimitive(r.grantedAt))
                        r.grantedBy?.let { put("grantedBy", JsonPrimitive(it)) }
                    })
                }
            })
        }
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.absolutePath + ".tmp")
            tmp.writeText(json.toString())
            // 0600：这份文件是**安全策略**（哪些动作不用再问），同机其它用户能写它
            // 就等于能给自己免批。rename 不继承源权限，所以两个文件都要设。
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

    /** 有界化：淘汰最旧的（丢的是免批，方向收紧，与 [AgentGrantStore.trimLocked] 同）。 */
    private fun trimLocked() {
        if (rules.size > MAX_RULES) {
            rules = rules.sortedByDescending { it.grantedAt }.take(MAX_RULES).toMutableList()
        }
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        /** 免批表上限：正常几十条，一辈子到不了；防的是坏文件 / 循环写入把控制面撑爆。 */
        const val MAX_RULES = 500
    }
}
