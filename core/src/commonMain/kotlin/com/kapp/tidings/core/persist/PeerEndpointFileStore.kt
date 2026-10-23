// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.persist

import com.kapp.tidings.core.fs.fileExists
import com.kapp.tidings.core.fs.readFileBytes
import com.kapp.tidings.core.nowMillis
import com.kapp.tidings.core.transport.PeerEndpoint
import com.kapp.tidings.core.transport.PeerEndpointStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 候选端点的落盘实现：`<dataDir>/stores/peer-endpoints.json`。
 *
 * 与另外几个 store 同一条纪律（见 [PersistStores] 的 KDoc）：构造时读一次、变更时整份写回；
 * 读不出来/写不进去一律降级为「这份当空表」，绝不拦路 —— 丢的是兜底能力，不是消息。
 *
 * ## 为什么要 TTL 清理（task-24）
 *
 * 这份表按 nodeId 存「配对码里那一池地址」，而**删掉设备这条路上没有任何东西会回来清它**：
 * `PeerRegistry` 只有 `setCandidates`（接口 [PeerEndpointStore] 也只有 load/save），registry 与
 * 这份文件都只增不减。真机后果：设备删掉很久之后，它的候选端点还躺在
 * `stores/peer-endpoints.json` 里，每次启动都被 `NodeRuntime.start` 装回 registry，
 * 心跳还会往那些早就没有主人的地址发一份。
 *
 * ## 策略：按「这一条最后一次**变化**的时刻」算 TTL（默认 30 天）
 *
 * 为什么是「变化」而不是「出现过」：这份文件**没有「见过它」的信号** —— 唯一写入点是配对
 * （`NodeRuntime.setPeerEndpoints`），而 registry 自己从不删除候选。也就是说，只要 registry
 * 还留着那条记录，任何「按出现刷新」的规则都会把它一起刷新，永远清不掉。反过来，值本身
 * 不再变化才是「这条兜底信息已经过期」的可用信号。
 *
 * 为什么不会误删刚配对的设备：刚写入 / 刚变化的条目，时间戳就是当下，离 30 天的窗口差得远。
 *
 * 代价（明确写出来）：一个**长期不再配对、地址也一直没变**的对端，其候选池会在 30 天后从这份
 * 兜底表里消失 —— 它当前的地址仍在 peer 记录里，重新配对就会立刻回来。这个取舍是刻意的：
 * 候选池是「换网兜底」，不是身份；过期数据留在盘上只会让心跳发向幽灵地址。
 *
 * ## 版本
 *
 * 快照升到 v2：多一张 `updatedAt`（nodeId → 毫秒）。v1 文件没有这张表 ⇒ 读到之后按「现在」
 * 盖章（升级不丢数据），之后照常按 TTL 老化。
 */
class FilePeerEndpointStore(
    private val path: String,
    /** 多久没变化就丢弃。默认 [DEFAULT_TTL_MS]；测试注入更短的值。 */
    private val ttlMillis: Long = DEFAULT_TTL_MS,
    /** 时钟，测试注入以便确定性地跨天。 */
    private val clock: () -> Long = { nowMillis() },
) : PeerEndpointStore {

    private var current: Map<String, List<PeerEndpoint>> = emptyMap()
    private var stamps: Map<String, Long> = emptyMap()

    init {
        val snapshot = readSnapshot()
        val at = clock()
        val kept = snapshot.endpoints.filter { (nodeId, _) ->
            // 没有时间戳（v1 文件）⇒ 按「现在」盖章，不当作过期。
            at - (snapshot.updatedAt[nodeId] ?: at) <= ttlMillis
        }
        current = kept
        stamps = kept.mapValues { (nodeId, _) -> snapshot.updatedAt[nodeId] ?: at }
        // 两种情况都要回写：
        // · 真裁掉了东西 ⇒ 让**文件本身**也变干净，而不是「读的时候看不见、盘上还在」；
        // · 有条目没有时间戳（v1 文件）⇒ 把「现在」盖上盘，否则每次启动都会重新给它盖章，
        //   这些条目就永远老不掉（TTL 对升级前的文件失效）。
        val missingStamp = kept.any { (nodeId, _) -> snapshot.updatedAt[nodeId] == null }
        if (kept.size != snapshot.endpoints.size || missingStamp) persist()
    }

    override fun load(): Map<String, List<PeerEndpoint>> = current

    override fun save(endpoints: Map<String, List<PeerEndpoint>>) {
        val at = clock()
        val nextStamps = endpoints.mapValues { (nodeId, list) ->
            // 值没变 ⇒ 保留原来的时间戳（不刷新）；变了或新来的 ⇒ 盖当下。
            if (current[nodeId] == list) stamps[nodeId] ?: at else at
        }
        // 写的时候也按同一把尺子裁一次：调用方（registry）可能仍把它当成「还认识」而重复送来。
        val kept = endpoints.filter { (nodeId, _) -> at - (nextStamps[nodeId] ?: at) <= ttlMillis }
        current = kept
        stamps = kept.mapValues { (nodeId, _) -> nextStamps[nodeId] ?: at }
        persist()
    }

    private fun persist() {
        runCatching {
            val text = json.encodeToString(
                PeerEndpointsSnapshot.serializer(),
                PeerEndpointsSnapshot(endpoints = current, updatedAt = stamps),
            )
            rewriteLog(null, path, StringBuilder(text))
        }
    }

    private fun readSnapshot(): PeerEndpointsSnapshot {
        if (!fileExists(path)) return PeerEndpointsSnapshot()
        val bytes = readFileBytes(path) ?: return PeerEndpointsSnapshot()
        return runCatching {
            json.decodeFromString(PeerEndpointsSnapshot.serializer(), bytes.decodeToString())
        }.getOrDefault(PeerEndpointsSnapshot())
    }

    companion object {
        /** 默认 30 天：长到不会因为「这个月没重新配对」误删一个活着的对端。 */
        const val DEFAULT_TTL_MS: Long = 30L * 24 * 60 * 60 * 1000

        private val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * 写进文件的那个形状。
 *
 * 外面裹一层 version，与 MessageSnapshot 同一条理由：这份数据跨版本留存，
 * 字段只会增不会删，老记录里出现新键时 ignoreUnknownKeys 让它仍能读回来。
 *
 * [updatedAt] 是 v2 加的（task-24）：nodeId → 这一条**最后一次变化**的毫秒时刻。
 * 缺失 = 未知（v1 文件或手写文件），读的时候按「现在」盖章，不当作过期。
 */
@Serializable
internal data class PeerEndpointsSnapshot(
    val version: Int = SNAPSHOT_VERSION,
    val endpoints: Map<String, List<PeerEndpoint>> = emptyMap(),
    val updatedAt: Map<String, Long> = emptyMap(),
) {
    companion object {
        const val SNAPSHOT_VERSION = 2
    }
}
