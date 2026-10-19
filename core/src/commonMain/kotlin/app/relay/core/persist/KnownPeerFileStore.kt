// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.persist

import app.relay.core.fs.fileExists
import app.relay.core.fs.readFileBytes
import app.relay.core.nowMillis
import app.relay.core.protocol.Profile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 「已知对端」的落盘形态（task-80 ①）。
 *
 * 只存**身份与最近一次端点**，不存任何密钥类信息（pin 在 `peer-pins.json`，
 * 候选池在 `peer-endpoints.json`，各管各的）。
 */
@Serializable
data class KnownPeerSnapshot(
    val version: Int = 1,
    val peers: List<KnownPeer> = emptyList(),
)

@Serializable
data class KnownPeer(
    /** 完整 profile：昵称、平台、能力位、端口都在里面。丢了这个，重启后只剩一排 nodeId。 */
    val profile: Profile,
    val ip: String,
    val udpPort: Int,
    val lastSeen: Long,
)

/**
 * 已知对端落盘（task-80 ①）：`<dataDir>/stores/known-peers.json`。
 *
 * ## 为什么必须存（真机缺陷）
 *
 * 手机重启后 `PeerRegistry` 是**空的**。于是节点表里那台 Mac 只剩一行 `node-a1b2c3d4`
 * —— 那个名字来自配对时造的**合成名**，已经写进消息历史，重启后直接被读出来显示。
 * 更糟的是它对"这台机器是谁"一无所知：头像、平台、能力位全没了，用户会以为设备被删了。
 *
 * 而真相是：**身份是持久的，可达性才是易变的**。蜂窝下 Mac 确实连不上（用户看到
 * "没有回应"是对的），但"这台机器叫 demo-mac-97"这件事不该因为一次重启就消失。
 *
 * ## 恢复成 `online = false`，不撒谎
 *
 * 读回来的记录一律以**离线**喂进 registry。恢复成"在线"就是撒谎：蜂窝下它确实不可达，
 * 那会让消息进一条通不了的队列，用户看到的"已发送"是假的。
 *
 * ## 与其它 store 同一条纪律
 *
 * 构造时读一次、变更时整份写回；读不出来/写不进去一律降级为「这份当空表」，绝不拦路。
 */
class FileKnownPeerStore(
    private val path: String,
    /** 只留这么久没露过面的对端。默认 90 天。 */
    private val ttlMillis: Long = DEFAULT_TTL_MS,
    private val clock: () -> Long = { nowMillis() },
) : app.relay.core.transport.KnownPeerStore {

    private var current: List<KnownPeer> = emptyList()

    init {
        val snapshot = readSnapshot()
        val at = clock()
        // 过期条目在这里就裁掉：一份只增不减的"已知对端"最终会变成一份幽灵名录，
        // 每次启动都往那些早就不用的地址发心跳。
        val kept = snapshot.peers.filter { at - it.lastSeen <= ttlMillis }
        current = kept
        if (kept.size != snapshot.peers.size) persist()
    }

    /** 已落盘的对端（调用方据此在启动时以离线态喂进 registry）。 */
    override fun load(): List<KnownPeer> = current

    /**
     * 整份写回。
     *
     * 调用方负责**去抖**（`NodeRuntime` 在 `PeerEvent.Updated/Online` 上合并 1–2 秒再调），
     * 否则每来一帧都要写一次盘。
     */
    override fun save(peers: List<KnownPeer>) {
        val at = clock()
        val kept = peers.filter { at - it.lastSeen <= ttlMillis }
        current = kept
        persist()
    }

    private fun persist() {
        runCatching {
            val text = json.encodeToString(
                KnownPeerSnapshot.serializer(),
                KnownPeerSnapshot(peers = current),
            )
            rewriteLog(null, path, StringBuilder(text))
        }
    }

    private fun readSnapshot(): KnownPeerSnapshot {
        if (!fileExists(path)) return KnownPeerSnapshot()
        val bytes = readFileBytes(path) ?: return KnownPeerSnapshot()
        return runCatching {
            json.decodeFromString(KnownPeerSnapshot.serializer(), bytes.decodeToString())
        }.getOrDefault(KnownPeerSnapshot())
    }

    companion object {
        /**
         * 90 天。比候选端点（30 天）宽：那个是"换网兜底"，过期只会让心跳发向幽灵地址；
         * 这个是**身份**，删早了用户就再也认不出对面是谁（而那台设备可能一直在用，
         * 只是这三个月没跟本机通过信）。
         */
        const val DEFAULT_TTL_MS: Long = 90L * 24 * 60 * 60 * 1000

        private val json = Json { ignoreUnknownKeys = true }
    }
}