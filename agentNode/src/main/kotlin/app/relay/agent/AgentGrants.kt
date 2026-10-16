// SPDX-License-Identifier: GPL-3.0-only

package app.relay.agent

import app.relay.core.protocol.AgentGrantPolicies
import app.relay.core.protocol.AgentGrantScopes
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
 * PPE-1 增补 D7（父稿 §3.3）：agent 授权的**策略与台账**，宿主侧持久化。
 *
 * ## 分工（照父稿的表，一处都不含糊）
 *
 * - **决定在桌面端**（面板 / 人按按钮，经控制面 `PUT /v1/agent/grants` 写进来）；
 * - **执行在这里**：投递前 `allows()` 过滤 —— 只有在宿主进程过滤，"没授权"才等于"没数据"。
 *   `dsh-tidings` 插件只是转发方，不持有台账，也不做决定。
 *
 * ## 三档策略
 *
 * | 档 | 语义 |
 * |---|---|
 * | `auto`（默认） | 任何已添加的节点可见全部 agent —— 今天的零门控行为，零迁移 |
 * | `byDevice` | per-nodeId 白名单，缺省拒绝 |
 * | `request` | 未授权设备可申请 → 桌面端批准/拒绝；申请进 [requests] 队列 |
 *
 * 旧端（不声明 `agr1`）在 `byDevice`/`request` 下收不到 agent 数据：**沉默 = 未授权**，
 * 不猜、不默认放行（父稿 §3.3）。
 *
 * ## 两条诚实边界（写进这里，UI 文案必须带上）
 *
 * 1. **撤销不回收历史**：授权只拦未来的投递；已经投到对方设备上的数据就在对方本地库里。
 * 2. **默认 `auto` = 默认开放**：第一次收到某设备的申请/宣告时在 [requests] 留一条记录
 *    （谁、何时、要什么），别让"默认开放"变成"无声开放"。
 *
 * ## 落盘
 *
 * 一个 JSON 文件（`<dataDir>/agent-grants.json`），原子写（temp + rename）。
 * 为什么必须落盘：授权是**跨重启的决定** —— 用户在面板上批准了一台手机，重启节点后
 * 不能又退回"未授权"，那等于把人的按钮抹掉；而 `auto` 档同样要记住（有人显式改回
 * `auto` 也是决定）。
 *
 * **读不回来时 fail-closed**：文件不存在 = 首次运行（出厂 `auto`）；文件在但读不出 /
 * 解析不了 / 档位不合法 = 退回最严档 `request` 并把原因记进 [Snapshot.loadError]。
 * 绝不能"静默退回 `auto`" —— 那等于把文件写坏就能给自己发全量授权（见 [load]）。
 *
 * 线程安全：控制面 4 线程池 + 入站协程都会读写，全部走 [rwLock]；
 * 变更与落盘必须在**同一把写锁**里（见 [persistLocked]），否则并发 grant/revoke 会互相覆盖。
 */
class AgentGrantStore(private val file: File) {

    /** 一条生效授权。scope=all 时 [agentKey] 为 null（整台设备）。 */
    data class Grant(
        val nodeId: String,
        val scope: String,
        val agentKey: String?,
        val grantedAt: Long,
        val grantedBy: String?,
    )

    /** 一条设备发来的申请（宿主侧待办队列）。 */
    data class Request(
        val nodeId: String,
        val agentKey: String?,
        val reason: String?,
        val at: Long,
    )

    data class Snapshot(
        val policy: String,
        val grants: List<Grant>,
        val requests: List<Request>,
        /**
         * 启动时没能把台账读回来的一句话原因（读不出 / 解析不了 / 档位不合法）；正常为 null。
         *
         * **进程内粘住不清**：它描述的是「这次启动丢掉了什么」，不是「现在文件好不好」——
         * 之后任何一次 [setPolicy] 都会写出健康文件，但那次丢掉的授权不会自己回来，
         * 面板与 403 文案必须还能说出这件事。
         */
        val loadError: String? = null,
    )

    private val rwLock = ReentrantReadWriteLock()
    private var policy: String = AgentGrantPolicies.AUTO
    private var grants: MutableList<Grant> = mutableListOf()
    private var requests: MutableList<Request> = mutableListOf()
    private var loadError: String? = null

    init {
        load()
    }

    fun snapshot(): Snapshot = rwLock.read {
        Snapshot(policy, grants.toList(), requests.toList(), loadError)
    }

    /**
     * 投递前的门控（执行点）。
     *
     * `auto` 恒真；`byDevice`/`request` 查白名单：`scope=all` 命中任何 agentKey，
     * `scope=agent` 只在 [agentKey] **非空且逐字相等**时命中 —— 帧没带 agentKey 时
     * 不许拿 scope=agent 的授权放行（那是"猜"，父稿的降级铁律：缺哪一层就退化到哪一层，
     * 但**不退着放行**）。
     */
    fun allows(nodeId: String, agentKey: String?): Boolean = rwLock.read {
        if (policy == AgentGrantPolicies.AUTO) return@read true
        grants.any { g ->
            g.nodeId == nodeId && (
                g.scope == AgentGrantScopes.ALL ||
                    (g.scope == AgentGrantScopes.AGENT && agentKey != null && g.agentKey == agentKey)
                )
        }
    }

    fun setPolicy(next: String): Boolean {
        if (next !in AgentGrantPolicies.VALUES) return false
        rwLock.write {
            policy = next
            persistLocked()
        }
        return true
    }

    /** 授权（幂等：同 (nodeId, scope, agentKey) 重入只刷新时间戳）。 */
    fun grant(nodeId: String, scope: String, agentKey: String?, grantedBy: String?, at: Long): Grant? {
        if (scope !in AgentGrantScopes.VALUES) return null
        val g = Grant(nodeId, scope, if (scope == AgentGrantScopes.AGENT) agentKey else null, at, grantedBy)
        rwLock.write {
            grants.removeAll { it.nodeId == g.nodeId && it.scope == g.scope && it.agentKey == g.agentKey }
            grants += g
            // 批完即从待办里摘掉这条申请（同 (nodeId, agentKey)）。
            requests.removeAll { it.nodeId == nodeId && it.agentKey == g.agentKey }
            trimLocked()
            persistLocked()
        }
        return g
    }

    /**
     * 撤销。**只拦未来**：已经投出去的数据在对方设备本地库里，这里不回收（边界 1）。
     */
    fun revoke(nodeId: String, scope: String, agentKey: String?): Int {
        var removed = 0
        rwLock.write {
            val doomed = grants.filter {
                it.nodeId == nodeId && it.scope == scope &&
                    (scope != AgentGrantScopes.AGENT || it.agentKey == agentKey)
            }
            removed = doomed.size
            grants.removeAll(doomed.toSet())
            persistLocked()
        }
        return removed
    }

    /** 记下一条入站申请（宿主侧）。同 (nodeId, agentKey) 重投就地替换（新 reason/at 覆盖）。 */
    fun recordRequest(nodeId: String, agentKey: String?, reason: String?, at: Long) {
        rwLock.write {
            requests.removeAll { it.nodeId == nodeId && it.agentKey == agentKey }
            requests += Request(nodeId, agentKey, reason, at)
            trimLocked()
            persistLocked()
        }
    }

    // ------------------------------------------------------------------ 持久化

    /**
     * 启动时把台账读回来。**读不回来就 fail-closed**（父稿 §3.3：不猜、不默认放行）。
     *
     * ## 为什么要分成三种结局，而不是一句 `?: return`
     *
     * 从前的写法是"读不出/解析不了就静默 return"，于是 [policy] 留在字段初值 `auto` ——
     * 而 `auto` 是"任何已添加的节点可见全部 agent"。等于：**把文件写坏 = 给自己发一份
     * 全量授权**，且没有任何界面会说这件事（这正是本类审计出的 H1）。
     *
     * 现在三种结局各自明确：
     *
     * 1. **文件不存在** → 首次运行，出厂档 `auto`（零迁移），[loadError] 保持 null；
     * 2. **文件在、但读不出 / 不是合法 JSON / 顶层不是对象 / 没有 policy 字段 / policy 不合法**
     *    → 退回**最严档 `request`**（缺省拒绝一切 agent 投递），并把原因记进 [loadError]
     *    供面板与 403 文案说明；
     * 3. **读得出来** → 正常装载，[loadError] = null。
     *
     * 为什么把"没有 policy 字段"也算失败：本类 [persistLocked] 每次落盘都写 policy，
     * 所以一个**由我们写出的**文件必有该字段；它缺失只可能是文件被截断/被外部改过，
     * 此时沿用 `auto` 与第 2 条同样危险。
     *
     * 单条 grant/request 解析不出来（缺 nodeId、scope 不合法）仍然是**就地丢掉那一条**，
     * 不升级为整档失败：丢掉一条授权只会更严，不会更松，而且这种条目无法与
     * "本来就没有这个字段"区分开。
     */
    private fun load() {
        if (!file.isFile) return

        val read = runCatching { file.readText() }
        val text = read.getOrNull()
        if (text == null) {
            failClosed("台账文件读不出来：" + (read.exceptionOrNull()?.let { it.message ?: it::class.simpleName } ?: "未知原因"))
            return
        }
        val parsed = runCatching { Json.parseToJsonElement(text) }
        val root = parsed.getOrNull() as? JsonObject
        if (root == null) {
            failClosed(
                "台账文件" + (
                    parsed.exceptionOrNull()?.let { "不是合法 JSON：" + (it.message ?: it::class.simpleName) }
                        ?: "顶层不是 JSON 对象"
                    ),
            )
            return
        }
        val declared = (root["policy"] as? JsonPrimitive)?.contentOrNull
        if (declared == null) {
            failClosed("台账文件没有 policy 字段（本类写出的文件必有）")
            return
        }
        if (declared !in AgentGrantPolicies.VALUES) {
            failClosed("台账里的策略「" + declared + "」不是 " + AgentGrantPolicies.VALUES.joinToString(" / "))
            return
        }

        rwLock.write {
            policy = declared
            grants = (root["grants"] as? JsonArray)?.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val nodeId = o.str("nodeId") ?: return@mapNotNull null
                val scope = o.str("scope")?.takeIf { it in AgentGrantScopes.VALUES } ?: return@mapNotNull null
                Grant(nodeId, scope, o.str("agentKey"), (o["grantedAt"] as? JsonPrimitive)?.longOrNull ?: 0L, o.str("grantedBy"))
            }?.toMutableList() ?: mutableListOf()
            requests = (root["requests"] as? JsonArray)?.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val nodeId = o.str("nodeId") ?: return@mapNotNull null
                Request(nodeId, o.str("agentKey"), o.str("reason"), (o["at"] as? JsonPrimitive)?.longOrNull ?: 0L)
            }?.toMutableList() ?: mutableListOf()
            loadError = null
            // 文件是磁盘上的字节，谁都可能往里塞一百万条 —— 装载后立刻有界化。
            trimLocked()
        }
    }

    /**
     * 读失败时的**唯一**降级路径：最严档 + 清空两份名单 + 记下原因。
     *
     * 用 [AgentGrantPolicies.REQUEST] 而不是 `byDevice`：三档里 `request` 与 `byDevice`
     * 在白名单为空时行为一样（都拒），但 `request` 的语义是"设备可以来申请"，恰好也是
     * 一个刚丢了台账的宿主最该处的状态 —— 对方能申请，人能重新批，而不是两边互相沉默。
     */
    private fun failClosed(why: String) {
        rwLock.write {
            policy = AgentGrantPolicies.REQUEST
            grants = mutableListOf()
            requests = mutableListOf()
            loadError = why
        }
    }

    /**
     * 落盘。**调用方必须已持有 [rwLock] 的写锁**（本函数名里的 Locked 就是这个意思）。
     *
     * ## 为什么序列化不能"在锁里取快照、到锁外写"
     *
     * 从前的写法是：`rwLock.read { 拼 json }` 拿到一份字符串，然后**出了锁**才写文件。
     * 控制面是 4 线程池，一次 `PUT /v1/agent/grants` 的 body 可以同时带 grant 与 revoke：
     * 两个线程各自拿到自己的快照，谁最后落盘谁说了算 —— 于是"先 grant 后 revoke"可能
     * 落成 grant 的旧快照，**撤销被静默吞掉**（这正是本类审计出的 M1）。
     *
     * 把"改内存 + 拼 json + 写文件"整段放进同一把写锁，顺序就与锁的获取顺序一致：
     * 后写的必然是后改的。代价是磁盘 I/O 期间持锁 —— 这份文件只有几百字节、
     * 写入频率是人点按钮的频率（控制面 4 个线程也不都在写授权），可以接受。
     *
     * 原子写：temp + rename。直接 writeText 半途崩会留下截断的 JSON，下次启动
     * [load] 会 fail-closed 到最严档 —— 不会放行，但用户点过的按钮还是丢了，
     * 所以 temp + rename 仍然必须。
     */
    private fun persistLocked() {
        val json = buildJsonObject {
            put("policy", JsonPrimitive(policy))
            put("grants", buildJsonArray {
                grants.forEach { g ->
                    add(buildJsonObject {
                        put("nodeId", JsonPrimitive(g.nodeId))
                        put("scope", JsonPrimitive(g.scope))
                        g.agentKey?.let { put("agentKey", JsonPrimitive(it)) }
                        put("grantedAt", JsonPrimitive(g.grantedAt))
                        g.grantedBy?.let { put("grantedBy", JsonPrimitive(it)) }
                    })
                }
            })
            put("requests", buildJsonArray {
                requests.forEach { r ->
                    add(buildJsonObject {
                        put("nodeId", JsonPrimitive(r.nodeId))
                        r.agentKey?.let { put("agentKey", JsonPrimitive(it)) }
                        r.reason?.let { put("reason", JsonPrimitive(it)) }
                        put("at", JsonPrimitive(r.at))
                    })
                }
            })
        }
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.absolutePath + ".tmp")
            tmp.writeText(json.toString())
            // 0600：这份文件是**安全策略**（谁能看这台宿主的 agent），不是偏好。
            // 同机其它用户能写它 = 能给自己发授权。本仓既有纪律（dsh-tidings 的
            // config-v1.json 同此）；rename 不继承源权限，所以**两个文件都要设**。
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

    /**
     * 两份名单的有界化（调用方必须已持写锁）。
     *
     * 写入路各自有上限，但 [load] 是唯一会**一次装进任意多条**的入口 —— 文件是磁盘上的
     * 字节，谁都可能往里塞一百万条。所以装载之后、以及每次 [grant] 之后都收一次。
     *
     * 授权按 [Grant.grantedAt] 降序保留最新的：淘汰方向收紧（丢旧留新），且与
     * 「最近批准的那台设备最可能是用户现在还在用的」一致。
     */
    private fun trimLocked() {
        if (grants.size > MAX_GRANTS) {
            grants = grants.sortedByDescending { it.grantedAt }.take(MAX_GRANTS).toMutableList()
        }
        while (requests.size > MAX_REQUESTS) requests.removeAt(0)
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        /** 待办申请上限：按到达顺序 FIFO 淘汰（长跑的节点不能被申请刷爆）。 */
        const val MAX_REQUESTS = 100

        /**
         * 白名单上限（按授权时刻淘汰最旧的）。
         *
         * 台账是**人的操作产物**：正常几十条，一辈子也到不了这个数。设上限防的是
         * 「一个（坏掉的或伪造的）台账文件 / 一条不停调 PUT /v1/agent/grants 的循环
         * 把控制面撑爆」—— [load] 会照单全收 JSON 数组，而 [ControlPlane.readGrants]
         * 还会把整份名单吐进 HTTP 响应体。
         *
         * 淘汰方向是**收紧**：丢的是最旧的授权，剩下的授权只会更少，不会更松。
         */
        const val MAX_GRANTS = 500
    }
}
