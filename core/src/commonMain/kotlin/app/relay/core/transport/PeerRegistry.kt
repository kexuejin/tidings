// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

import app.relay.core.nowMillis
import app.relay.core.createCoreLock
import app.relay.core.withLock
import app.relay.core.protocol.Profile
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * 在线节点表。逐条对齐茶话间 TS 侧 `src/main/net/peer-registry.ts`。
 *
 * 三条规则是从源码读出来的，别凭直觉改：
 *
 * 1. **未知节点必须有完整 profile 才进表**（`peer-registry.ts:51`）：没有 profile 的
 *    `msg`/`presence` 一律丢弃。这是手机端"必须先握手"的根据。
 * 2. **已在线节点拒绝源地址漂移**（`peer-registry.ts:63`）：同一 nodeId 从新地址发来
 *    普通帧会被拒；只有"离线 + 带完整 profile"才允许换地址 —— 也就是手机从 WiFi 切到
 *    蜂窝后，必须用完整 profile 重新握手，而不是继续发消息。
 * 3. **事件顺序：先 Updated 再 Online**（`peer-registry.ts:94`）。TS 里 emit 是同步的，
 *    messenger 的补发监听器需要先拿到最新地址；Kotlin 侧用同步监听器列表保证同样的顺序，
 *    另外挂一条 SharedFlow 给 UI。
 */
data class PeerRecord(
    var profile: Profile,
    var ip: String,
    var udpPort: Int,
    var lastSeen: Long,
    var online: Boolean,
)

sealed interface PeerEvent {
    /** 记录有任何变化（地址、lastSeen、profile、online）。 */
    data class Updated(val record: PeerRecord) : PeerEvent

    /** 由离线变在线 —— 补发队列的触发点。 */
    data class Online(val nodeId: String) : PeerEvent
}

class PeerRegistry(private val selfNodeId: String) {

    private val lock = createCoreLock()
    private val peers = LinkedHashMap<String, PeerRecord>()
    private val profileTimes = HashMap<String, Long>()

    /**
     * 同一 profileRev 但 profileTs 没有前进的节点。TS 用它防止时钟回拨时把旧 profile
     * 当成新 profile 覆盖（`peer-registry.ts:84`）。
     */
    private val profileConfirmationOnly = HashSet<String>()

    private val listeners = mutableListOf<(PeerEvent) -> Unit>()
    private val _events = MutableSharedFlow<PeerEvent>(extraBufferCapacity = 64)

    /** 给 UI / 其它订阅者的事件流（尽力投递，不阻塞）。 */
    val events: SharedFlow<PeerEvent> = _events

    /** 同步监听器 —— 补发队列依赖它的顺序与同步性。 */
    fun addListener(listener: (PeerEvent) -> Unit) {
        lock.withLock { listeners += listener }
    }

    fun removeListener(listener: (PeerEvent) -> Unit) {
        lock.withLock { listeners -= listener }
    }

    private fun emit(event: PeerEvent) {
        val targets = lock.withLock { listeners.toList() }
        for (listener in targets) listener(event)
        _events.tryEmit(event)
    }

    /**
     * 记一次"见到该节点"。
     *
     * @param profile 完整 profile；只有 entry/alive 才带。普通 msg/presence 传 null。
     * @param profileTs 该 profile 的发出时间（信封 ts），用于判断新旧。
     * @param confirmed 定向握手（entry）为 true —— 允许在 profileRev 相同时接受 profile。
     * @return 更新后的记录；被拒绝时返回 null（自己、profile.nodeId 不一致、未知节点无
     *         profile、已在线节点换地址）。
     */
    fun touch(
        nodeId: String,
        ip: String,
        udpPort: Int,
        profile: Profile? = null,
        profileTs: Long? = null,
        confirmed: Boolean = false,
    ): PeerRecord? = lock.withLock {
        if (nodeId == selfNodeId) return null
        if (profile != null && profile.nodeId != nodeId) return null

        val now = nowMillis()
        val existing = peers[nodeId]
        if (existing == null) {
            if (profile == null) return null
            val record = PeerRecord(profile, ip, udpPort, now, online = true)
            peers[nodeId] = record
            profileTimes[nodeId] = profileTs ?: now
            emit(PeerEvent.Updated(record))
            emit(PeerEvent.Online(nodeId))
            return record
        }

        val sameAddress = existing.ip == ip && existing.udpPort == udpPort
        if (!sameAddress) {
            // 已在线节点不接受源地址漂移；离线节点带完整 profile 可以重新握手（切网）。
            if (existing.online) return null
            if (profile == null) return null
        }

        if (profile != null) {
            val sameRevision = profile.profileRev == existing.profile.profileRev
            val newer = profile.profileRev > existing.profile.profileRev
            val lastTs = profileTimes[nodeId]
            val ordered = profileTs == null || lastTs == null || profileTs > lastTs
            val accept = newer ||
                (sameRevision && ((ordered && !profileConfirmationOnly.contains(nodeId)) || confirmed))
            if (accept) {
                // 真正的新版本要**解除**这个节点之前的"只认确认"封印（对齐 TS
                // `peer-registry.ts:83` 的 `if (newer) this.profileConfirmationOnly.delete(nodeId)`）。
                // 漏掉这一句，封印就是永久的：一个节点只要曾经因为时间戳乱序被记过一次，
                // 之后无论它把 profileRev 提到多少，我们都不会再采纳它的资料 —— 于是它改了
                // 端口、改了能力，我们永远停在旧的那份上，并按旧端口去连（实测）。
                if (newer) profileConfirmationOnly.remove(nodeId)
                if (confirmed && sameRevision && profileTs != null && lastTs != null && profileTs <= lastTs) {
                    profileConfirmationOnly.add(nodeId)
                }
                existing.profile = profile
                profileTimes[nodeId] = profileTs ?: now
            }
        }

        existing.ip = ip
        existing.udpPort = udpPort
        existing.lastSeen = now
        val wasOffline = !existing.online
        existing.online = true
        emit(PeerEvent.Updated(existing))
        if (wasOffline) emit(PeerEvent.Online(nodeId))
        existing
    }

    fun get(nodeId: String): PeerRecord? = lock.withLock { peers[nodeId] }

    /** 未知节点返回 -1（与 TS `profileRevOf` 一致）。 */
    fun profileRevOf(nodeId: String): Int = lock.withLock { peers[nodeId]?.profile?.profileRev ?: -1 }

    fun markOffline(nodeId: String) {
        val record = lock.withLock {
            val r = peers[nodeId] ?: return
            if (!r.online) return
            r.online = false
            r
        }
        emit(PeerEvent.Updated(record))
    }

    /** 心跳超时（protocol §6.2）。返回本次被判定离线的节点。 */
    fun sweep(offlineAfterMs: Long): List<String> {
        val now = nowMillis()
        val offlineRecords = mutableListOf<Pair<String, PeerRecord>>()
        lock.withLock {
            for ((nodeId, record) in peers) {
                if (record.online && now - record.lastSeen > offlineAfterMs) {
                    record.online = false
                    offlineRecords += (nodeId to record)
                }
            }
        }
        for ((_, record) in offlineRecords) {
            emit(PeerEvent.Updated(record))
        }
        return offlineRecords.map { it.first }
    }

    fun onlineCount(): Int = lock.withLock { peers.values.count { it.online } }

    /**
     * 展示用排序：在线优先，其次按昵称。
     * TS 用 `Intl.Collator('zh-Hans-CN')`；Kotlin 侧用自然序近似（中文排序会有差异，
     * 只影响列表展示顺序，不影响协议）。
     */
    fun list(): List<PeerRecord> = lock.withLock {
        peers.values.sortedWith(
            compareByDescending<PeerRecord> { it.online }.thenBy { it.profile.nick },
        )
    }

    /** 热路径用：保持插入顺序，不做排序。 */
    fun values(): List<PeerRecord> = lock.withLock { peers.values.toList() }
}
