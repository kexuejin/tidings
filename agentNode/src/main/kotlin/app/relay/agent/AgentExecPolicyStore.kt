// SPDX-License-Identifier: GPL-3.0-only

package app.relay.agent

import app.relay.core.protocol.AgentExecPolicies
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * **每个 agent 各自的执行范围**（谁能触发它执行），落盘保存。
 *
 * ## 为什么按 agent 而不是按宿主
 *
 * 一台机器可以同时装 dsh 与 codex（`RelayUiState.agentKindsByHost` 就是「宿主 → 多个 kind」）。
 * 宿主级一份配置做不到「允许群主用 dsh、但不允许用 codex」。
 *
 * ## 缺省 = 最严（这是本类唯一的安全不变量）
 *
 * 表里没有的 agent、读不出来的文件、被外部改坏的条目 —— **一律按 [AgentExecPolicies.LIST]**。
 * 理由：执行是不可撤销的动作。读不出来时「更严」只会让用户去配一次；
 * 「更松」会让人在他机器上跑命令。两者代价不对等，所以一律从严。
 *
 * ## 与 `AgentGrantStore` 的分工
 *
 * - 本类回答「这个 agent 开放到什么程度」——**策略**，一个 agent 一条；
 * - `AgentGrantStore` 回答「谁能用这个 agent」——**白名单**，一条授权一个节点。
 * 两层都是 agent 粒度，但一个问题只由一处回答。
 */
class AgentExecPolicyStore(private val file: File) {

    private val rwLock = ReentrantReadWriteLock()

    /** agentKey → 档位。只包含**显式配过**的 agent；缺的按最严一档处理。 */
    private var policies: Map<String, String> = emptyMap()

    init {
        load()
    }

    /** 全部已配置项（只读快照）。没配过的 agent 不在里面。 */
    fun snapshot(): Map<String, String> = rwLock.read { policies }

    /** 某个 agent 的执行范围；没配过 ⇒ [AgentExecPolicies.LIST]。 */
    fun policyOf(agentKey: String?): String {
        val key = agentKey?.trim()?.takeIf { it.isNotEmpty() } ?: return AgentExecPolicies.LIST
        return AgentExecPolicies.normalize(rwLock.read { policies[key] })
    }

    /**
     * 设置某个 agent 的执行范围。
     *
     * @return false = 档位取值非法（**不做任何修改**，不是「静默写成默认」）。
     */
    fun set(agentKey: String, policy: String): Boolean {
        val key = agentKey.trim()
        if (key.isEmpty()) return false
        if (policy !in AgentExecPolicies.VALUES) return false
        rwLock.write {
            policies = policies + (key to policy)
            persistLocked()
        }
        return true
    }

    /** 移除某个 agent 的配置（回到最严一档）。 */
    fun clear(agentKey: String): Boolean {
        val key = agentKey.trim()
        if (key.isEmpty() || key !in policies) return false
        rwLock.write {
            policies = policies - key
            persistLocked()
        }
        return true
    }

    /** 变更与落盘必须在同一把写锁里，否则并发 set 会互相覆盖。 */
    private fun persistLocked() {
        val json = buildJsonObject {
            policies.toSortedMap().forEach { (k, v) -> put(k, v) }
        }
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.toString());
        }
    }

    /**
     * 读盘。任何异常都**保持空表**（= 全部最严），不抛 —— 
     * 一个坏文件不该让整个插件起不来，但也不能因此放宽任何权限。
     */
    private fun load() {
        if (!file.isFile) return
        val text = runCatching { file.readText() }.getOrNull() ?: return
        val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return
        val out = LinkedHashMap<String, String>()
        root.forEach { (k, v) ->
            val raw = (v as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            // 非法取值就地丢掉：丢掉只会更严（回落到 HOST_ONLY），符合本类的不变量。
            //
            // **纵深防御**：`policyOf` 出口还会过一次 `AgentExecPolicies.normalize`，
            // 所以即使这里漏掉一条，读出来的仍是最严一档。两处各挡一次是刻意的 ——
            // 这类「信任了外部文件」的错误一旦发生，代价是别人在你机器上跑命令。
            if (raw != null && raw in AgentExecPolicies.VALUES) out[k] = raw
        }
        policies = out
    }
}
