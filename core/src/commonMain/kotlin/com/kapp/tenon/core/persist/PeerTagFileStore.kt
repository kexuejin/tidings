// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.persist

import com.kapp.tenon.core.fs.fileExists
import com.kapp.tenon.core.fs.readFileBytes
import com.kapp.tenon.core.nowMillis
import com.kapp.tenon.core.protocol.ChannelTags
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 一条标签便条：本机给某个节点贴的**本机视角**标注（M7-T4，决策包 §3.2 方案 B）。
 *
 * 与 `ManualPeerEntry.name` 同一性质：不进 wire、不是对端声明的身份 ——
 * 路由判定发生在发起方（`agent-channel` 的 `targetTag`）。
 */
@Serializable
data class PeerTagEntry(
    val nodeId: String,
    /** 标签集合（集合语义：同节点重复打同一个标签 = 幂等）。形状由 [ChannelTags.isWellFormed] 单源。 */
    val tags: List<String>,
    val updatedAt: Long,
)

@Serializable
data class PeerTagSnapshot(
    val version: Int = 1,
    val entries: List<PeerTagEntry> = emptyList(),
)

/**
 * 节点能力标签落盘：`<dataDir>/stores/peer-tags.json`（M7-T4，决策包 §3.2 推荐方案 B）。
 *
 * ## 为什么是独立 JSON store 而不是 KnownPeerSnapshot 加字段
 *
 * 决策包 §3.2 的 A 案两个坑（静默丢失的形状）：① `NodeRuntime.knownPeersSnapshot()`
 * 从 `PeerRecord` 整份重建后 `save(...)`，`PeerRecord` 没有 tags ⇒ **下一次 save
 * 静默抹掉用户标签**；② known-peers 的 90 天 TTL 会把标签连同对端一起删掉。
 * 标签是**身份标注**（同 pin 的立场），不随可达性缓存过期 —— 与 registry 完全解耦，
 * save 互不覆盖（本仓有解耦判据测试钉住，见 PeerTagFileStoreTest）。
 *
 * ## 与其它 store 同一条纪律（三同）
 *
 * 构造时读一次、变更时整份写回；读不出来/写不进去一律降级为「这份当空表」，绝不拦路
 * （启动路径不能被一个坏便条文件卡死）。**零协议键零迁移**（决策包 §3.4）：标签永不
 * 广播 —— 用户手写的自由文本进 wire 等于把本机命名习惯泄露给所有对端。
 *
 * ## 过期立场
 *
 * **不过期**（与 pins 同一立场）：标签是用户亲手打的，90 天不通信不代表他不想再路由。
 */
class PeerTagFileStore(
    private val path: String,
    private val clock: () -> Long = { nowMillis() },
) {

    private var current: List<PeerTagEntry> = emptyList()

    init {
        current = readSnapshot().entries
    }

    /** 已落盘的标签便条（编排器路由查询与节点详情 UI 都从这里读）。 */
    fun load(): List<PeerTagEntry> = current

    /** 查「哪些节点挂着 [tag]」（编排器 `targetTag` 路由的直接消费口，调用方再自行选优）。 */
    fun nodeIdsWithTag(tag: String): List<String> = current
        .filter { it.tags.contains(tag) }
        .map { it.nodeId }

    /**
     * 给一个节点打标签（集合语义：重复标签幂等；形状由 [ChannelTags.isWellFormed]
     * 单源判 —— **写侧与 wire 校验引用同一个谓词**，决策包 §3.3「又一条 #7 防线」）。
     *
     * @return 打完后的标签集；形状不合法的标签被拒（原集合原样返回，不静默改写）。
     */
    fun tag(nodeId: String, tags: List<String>): List<String> {
        if (tags.any { !ChannelTags.isWellFormed(it) }) return tagsOf(nodeId)
        val merged = (tagsOf(nodeId) + tags).distinct()
        if (merged.isNotEmpty()) {
            val rest = current.filter { it.nodeId != nodeId }
            current = (rest + PeerTagEntry(nodeId, merged, clock())).sortedBy { it.nodeId }
            persist()
        }
        return merged
    }

    /** 摘掉一个节点的指定标签（用户在节点详情里删标签的出口）。 */
    fun untag(nodeId: String, tags: List<String>) {
        val left = tagsOf(nodeId) - tags.toSet()
        current = if (left.isEmpty()) {
            current.filter { it.nodeId != nodeId }
        } else {
            val rest = current.filter { it.nodeId != nodeId }
            (rest + PeerTagEntry(nodeId, left, clock())).sortedBy { it.nodeId }
        }
        persist()
    }

    private fun tagsOf(nodeId: String): List<String> =
        current.firstOrNull { it.nodeId == nodeId }?.tags ?: emptyList()

    private fun persist() {
        runCatching {
            val text = json.encodeToString(
                PeerTagSnapshot.serializer(),
                PeerTagSnapshot(entries = current),
            )
            rewriteLog(null, path, StringBuilder(text))
        }.onFailure { t -> reportPersistFailure(path, t) }
    }

    private fun readSnapshot(): PeerTagSnapshot {
        if (!fileExists(path)) return PeerTagSnapshot()
        val bytes = readFileBytes(path) ?: return PeerTagSnapshot()
        return runCatching {
            json.decodeFromString(PeerTagSnapshot.serializer(), bytes.decodeToString())
        }.getOrDefault(PeerTagSnapshot())
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }
}
