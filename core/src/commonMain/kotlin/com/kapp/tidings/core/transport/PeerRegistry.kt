// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import com.kapp.tidings.core.nowMillis
import com.kapp.tidings.core.createCoreLock
import com.kapp.tidings.core.withLock
import com.kapp.tidings.core.protocol.Profile
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

    /**
     * 用户把这条节点**从本机节点表里删掉了**（[PeerRegistry.forget]）。
     *
     * 为什么单独一个事件而不是复用 [Updated]：落盘那边只在 Updated/Online 上标脏并整份
     * 重写 known-peers；删除如果不触发它，文件里那条会在**下次重启时原样回来** ——
     * 用户会认为「删除没生效」，而内存里其实早就没了（最典型的两端不一致）。
     */
    data class Removed(val nodeId: String) : PeerEvent
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

    /** nodeId → 配对码里存下来的候选端点（不含当前地址，当前地址在 [PeerRecord] 里）。 */
    private val candidateEndpoints = HashMap<String, List<PeerEndpoint>>()

    /**
     * nodeId → 对端的 TLS pin（wire 形态 `sha256:<64 hex>`）。
     *
     * 只有**配对码带过 pin** 的节点才会在这里；没有 = 不知道对方身份，K2 的公网长连接
     * 因此**不建**（fail-closed：有 pin 必须验，没 pin 不许连）。
     */
    private val peerPins = HashMap<String, String>()

    /** nodeId → pin 的来源（`pairing` / `tofu`）—— 见 [setPin] 的 KDoc。 */
    private val pinSources = HashMap<String, String>()

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

        val sameAddress = (existing.ip == ip || com.kapp.tidings.core.protocol.IpAddressUtils.isSameHost(existing.ip, ip)) && existing.udpPort == udpPort
        if (!sameAddress) {
            // 已在线节点不接受源地址漂移；离线节点带完整 profile、或定向握手（confirmed = true 且带完整 profile）允许换地址。
            if (existing.online && !confirmed) return null
            if (profile == null) return null
        }

        if (profile != null) {
            val sameRevision = profile.profileRev == existing.profile.profileRev
            val newer = profile.profileRev > existing.profile.profileRev
            val lastTs = profileTimes[nodeId]
            val ordered = profileTs == null || lastTs == null || profileTs > lastTs
            // **合成名必须能被真名顶替**（真机反馈：扫码后节点表一直显示 `node-a1b2c3d4`）。
            //
            // 成因：扫码那一刻只有 nodeId（二维码曾经不带昵称），`rememberPairedPeer` 于是
            // 造了一个 `node-<前8位>` 的**合成名**并记为 `profileRev = 0`。而对端真实的
            // profile 也常常就是 `profileRev = 0`（老端不累加），且 `profileTs` 未必更大 ⇒
            // `newer=false`、`ordered=false`、`confirmed=false` ⇒ **永远替换不掉**。
            //
            // 判据刻意写得很窄：只有"当前名字是合成名"且"新名字不是合成名"时才越过版本
            // 检查。这不放宽任何真实资料的新旧纪律 —— 它只是承认一件事：**我们从来没拿到过
            // 这个节点的真名**，所以没有"更新的资料"要保护。
            val replacesSyntheticName = isSyntheticNick(existing.profile.nick) &&
                !isSyntheticNick(profile.nick)
            val accept = newer || replacesSyntheticName ||
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

    /**
     * 把一个**已落盘**的对端恢复成**离线**记录（task-80 ①）。
     *
     * ## 为什么单独一个入口，而不是走 [touch]
     *
     * [touch] 的语义是"**现在**收到了一帧"：它会更新 lastSeen = now、置 online = true、
     * 发 Online 事件。用它在启动时恢复历史记录，等于对全世界宣布"这些设备现在都在线"——
     * 而蜂窝下它们可能根本不可达，消息会进一条通不了的队列。
     *
     * 这里只做"想起来它是谁"：profile、地址、上次露面时间照原样放回，**online = false**、
     * 不刷新 lastSeen、不发任何事件（启动阶段没有订阅者，发了也没人听）。
     *
     * 已存在的记录**不覆盖**：启动顺序上 discovery 可能先跑起来并真的收到了某台设备的帧，
     * 那条实时记录比盘上的旧记录新，不能被历史盖掉。
     */
    fun restoreOffline(
        profile: Profile,
        ip: String,
        udpPort: Int,
        lastSeen: Long,
    ): PeerRecord? = lock.withLock {
        if (profile.nodeId == selfNodeId) return null
        if (peers.containsKey(profile.nodeId)) return null
        val record = PeerRecord(
            profile = profile,
            ip = ip,
            udpPort = udpPort,
            lastSeen = lastSeen,
            online = false,
        )
        peers[profile.nodeId] = record
        profileTimes[profile.nodeId] = lastSeen
        record
    }

    fun get(nodeId: String): PeerRecord? = lock.withLock { peers[nodeId] }

    /**
     * 修剪**久未露面的离线身份**：`now - lastSeen > maxAgeMs` 且 `online == false` 且
     * [keep] 不认领的条目。返回实际修剪掉的 nodeId 列表。
     *
     * 为什么必须有：registry 只进不出 —— 重装换过身份的手机、联调留下的旧实例、
     * 夹具的一次性节点，全都永久留在节点表与建群成员选择器里（真机现场：一台桌面在
     * 手机上是 5 个同名设备条目）。修剪是**数据卫生**不是拉黑：设备再出现时发现流程
     * 会原样把它学回来（profile 带 caps/pin，真名跟着回来）。
     *
     * [keep] 认领的绝不剪：群成员（离线队列还要为它留着）、用户手动加的地址、
     * 自己。正在线的不在本函数语义里（只看 offline 条目）。
     *
     * ## 只剪"记忆"，**不动信任根**
     *
     * 这条走 [remove] 的 `revokeTrust = false` 支路：节点行、候选池、档案新鲜度判据都摘掉，
     * 但 `peerPins` / `pinSources` **留着**。区别在于两种删除的语义不同：
     * - 用户显式删节点（[forget]）= **撤销信任**，pin 必须一起消失；
     * - 自动过期 = **数据卫生**，只是"不再显示"。pin 是对端的公钥指纹，把它一起剪掉意味着
     *   那台设备下次出现时落到 TOFU 首次固定（[setPin] 的 `tofu` 支路，本身带首次使用
     *   竞态）—— 一次纯展示层的清理**静默**把信任锚降级了，与「公网必须 TLS + 双向 pin、
     *   验不过就拒」的 fail-closed 立场相反。留着的 pin 是惰性的：`pinOf` 只在
     *   [PeerRecord] 还在表里时被 [LinkClient.linkTargets] / 握手校验读到，没有节点行时
     *   谁也拿不到它；等那台设备再出现，它反而成了"还是原来那台"的证据（换了密钥就验不过，
     *   正是 pin 该有的行为）。
     *
     * 逐条走 [remove] 而不是自抄一份字段清单：那条路已经把候选池 / 档案新鲜度判据都摘干净
     * 并发 Removed 事件（known-peers 的落盘合并窗口靠它标脏）—— 两处各写一遍迟早一处漏字段。
     */
    fun pruneOfflineOlderThan(
        maxAgeMs: Long,
        keep: (nodeId: String) -> Boolean,
        now: Long = nowMillis(),
    ): List<String> {
        val stale = lock.withLock {
            peers.values.filter { record ->
                !record.online && (now - record.lastSeen) > maxAgeMs && !keep(record.profile.nodeId)
            }.map { it.profile.nodeId }
        }
        return stale.filter { remove(it, revokeTrust = false) }
    }

    /**
     * 按 (ip, port) 反查对端 —— luciola 模式下，主机通过入站配对 (TLPAIR1) 拿到的是
     * "nodeId + 公钥"，没有端点映射；要反向拨号时只能靠 registry 把 nodeId 找回来，
     * 再从 TrustStore 取它的 pin。
     */
    fun findByEndpoint(ip: String, port: Int): PeerRecord? = lock.withLock {
        // 同一端点可能对应多条历史记录(重装过的手机会留下旧 nodeId) ——
        // 必须挑**在线且最近见过**的那条,否则会把 pin 钉到旧身份上,握手对不上,
        // 症状是"永远 no-ack、最后退回 legacy TCP"(真机实测)。
        peers.values
            .filter {
                (it.ip == ip || com.kapp.tidings.core.protocol.IpAddressUtils.isSameHost(it.ip, ip)) &&
                    it.udpPort == port
            }
            .maxWithOrNull(compareBy<PeerRecord>({ it.online }, { it.lastSeen }))
    }

    companion object {
        /** pin 来源：配对流程带来（可信路径）。 */
        const val PIN_SOURCE_PAIRING = "pairing"

        /** pin 来源：TOFU 首次固定（**降级路径**，有首次使用竞态）。 */
        const val PIN_SOURCE_TOFU = "tofu"

        /** pin 来源：没记录（旧库文件，或调用方没传）。 */
        const val PIN_SOURCE_UNKNOWN = "unknown"

        /** 候选池里最多留几条「见到过」的源地址（配对码给的那几条排在前面、优先保留）。 */
        const val MAX_REMEMBERED_SOURCES = 8
    }

    /**
     * 记下一个节点的**候选端点**（配对码里那一池地址）。
     *
     * 与 [PeerRecord] 里的 `ip`/`udpPort` 分开：那是"当前正在用的那一个"，这是"还有哪些
     * 可以试"。发送失败时按序兜底（见 `Messenger.sendAwaitAck`），心跳也按它们全发一份
     * （见 `Discovery.broadcastTargets`）—— 手机只留移动网时，它给桌面发 presence 必须能
     * 到达桌面的**公网**地址，否则桌面永远学不到手机的运营商地址。
     */
    fun setCandidates(nodeId: String, endpoints: List<PeerEndpoint>) {
        lock.withLock {
            if (endpoints.isEmpty()) {
                candidateEndpoints.remove(nodeId)
            } else {
                candidateEndpoints[nodeId] = endpoints
            }
        }
    }

    /**
     * 记下「刚刚从这个地址收到过它的报文」——收进**候选池**，不动当前地址。
     *
     * 为什么必须记（task-28 真机）：[touch] 的漂移规则不允许用一条不带资料的 presence 改写
     * 当前地址（这是对的，TS 同款），所以对端换网之后本机可能长时间停在旧地址上；如果反向的
     * entry/alive 那一跳（UDP 单播）被网络过滤，本机就**永远**学不到新地址 —— 表现为
     * 「ip 停在运营商地址、online=false、队列只涨不降」。
     *
     * 把数据报源地址收进候选池之后，出站重试（Messenger 的「离线但有候选也试」）就能打到新
     * 地址；对端的 ACK 再经 [setActiveEndpoint] 把当前地址翻正。**不依赖任何反向 UDP 路径**，
     * 也不需要放宽漂移规则。
     *
     * 上限 [MAX_REMEMBERED_SOURCES]：保留配对码给的那几条（更可靠），只在其后追加最近看到的。
     */
    fun rememberSource(nodeId: String, host: String, port: Int) {
        if (nodeId.isBlank() || host.isBlank() || port !in 1..65535) return
        lock.withLock {
            val existing = candidateEndpoints[nodeId].orEmpty()
            if (existing.any { it.host == host && it.port == port }) return
            candidateEndpoints[nodeId] =
                (existing.take(MAX_REMEMBERED_SOURCES - 1) + PeerEndpoint(host, port))
        }
    }

    /**
     * 记下对端的 TLS pin（配对码里那一枚，或 TOFU 首次固定学到的）。传 null/空串 = 删除。
     *
     * [source] 是**可诊断性**的关键（task-38）：`pairing` = 配对流程带来的（可信路径，无竞态）；
     * `tofu` = 首次固定学到的（**降级路径**，有首次使用竞态）。让"其实还在 TOFU"看得见，
     * 而不是又一次静默 —— 今天已经在 `lnk1`/`att1` 上栽过两次同类。
     */
    fun setPin(nodeId: String, pin: String?, source: String = PIN_SOURCE_UNKNOWN) {
        lock.withLock {
            val clean = pin?.trim().orEmpty()
            if (clean.isEmpty()) {
                peerPins.remove(nodeId)
                pinSources.remove(nodeId)
            } else {
                peerPins[nodeId] = clean
                pinSources[nodeId] = source
            }
        }
    }

    /** 这枚 pin 是哪来的：`pairing` / `tofu` / `unknown`（旧库、或没记录来源）。 */
    fun pinSourceOf(nodeId: String): String? = lock.withLock { pinSources[nodeId] }

    /** 全部 pin 来源（落盘用）。 */
    fun allPinSources(): Map<String, String> = lock.withLock { pinSources.toMap() }

    /** 对端的 TLS pin；没配对过就是 null（公网长连接会因此拒绝建立）。 */
    fun pinOf(nodeId: String): String? = lock.withLock { peerPins[nodeId] }

    /** 全部 pin（落盘用）。 */
    fun allPins(): Map<String, String> = lock.withLock { peerPins.toMap() }

    /** 该节点的候选端点（按配对码给的顺序）；没存过就是空表。 */
    fun candidatesOf(nodeId: String): List<PeerEndpoint> = lock.withLock { candidateEndpoints[nodeId].orEmpty() }

    /** 全部节点的候选端点 —— 心跳要把它们都发一份。 */
    fun allCandidates(): Map<String, List<PeerEndpoint>> = lock.withLock { candidateEndpoints.toMap() }

    /**
     * 一次**成功的发送**证明了这个端点可达：把它提升为当前地址。
     *
     * 与 [touch] 的区别：touch 的地址来自入站数据报（可能被"拒绝漂移"挡掉），这里来自
     * "我们刚从这个地址收到了 ACK" —— 那是比任何入站帧都硬的事实，所以直接改。
     */
    /**
     * 长连接建立后调用：**只更新地址**，保留 [PeerRecord.udpPort]。
     *
     * 为什么不能直接用 [setActiveEndpoint]：那条连接的远端端口是一个**临时 TCP 端口**，
     * 把它写进 udpPort 会让之后所有 UDP 发送都发到一个没人听的端口。长连接证明的是
     * "这台设备现在从这个 IP 可达"，UDP 端口仍取它自己声明的那个。
     */
    fun setLinkedAddress(nodeId: String, host: String) {
        val record = lock.withLock {
            val r = peers[nodeId] ?: return
            if (r.ip == host && r.online) return
            r.ip = host
            r.lastSeen = nowMillis()
            r.online = true
            r
        }
        emit(PeerEvent.Updated(record))
    }

    fun setActiveEndpoint(nodeId: String, host: String, port: Int) {
        val record = lock.withLock {
            val r = peers[nodeId] ?: return
            if (r.ip == host && r.udpPort == port && r.online) return
            r.ip = host
            r.udpPort = port
            r.lastSeen = nowMillis()
            r.online = true
            r
        }
        emit(PeerEvent.Updated(record))
    }

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

    /**
     * 把一条节点**从本机节点表里删掉**（用户显式动作，不可撤销）。
     *
     * ## 删的是「本机对它的记忆」，不是那条设备
     *
     * 一起清掉的四类东西，少一样就会表现为「删不干净」：
     * - `peers`：节点行本身；
     * - `peerPins` / `pinSources`：pin 留在表里时，重启后 [pinOf] 还能把它当作「我认识
     *   这台设备」，而节点表里已经没有那一行 —— 一个没有主体的信任；
     * - `candidateEndpoints`：配对码带下来的地址池，不清就会继续往旧地址发心跳；
     * - `profileTimes` / `profileConfirmationOnly`：旧档案的新鲜度判据，留着会让它下次
     *   被重新发现时被判成「旧档案」而拒收。
     *
     * **它下次被发现在网上时会重新出现** —— 这是设计，不是缺陷：局域网里出现一台设备，
     * 本机就会重新学到它。界面必须把这一点说清（见确认框文案）。
     *
     * @return 这条节点此前是否存在过。
     */
    fun forget(nodeId: String): Boolean = remove(nodeId, revokeTrust = true)

    /**
     * [forget] 的实现本体，多一个 [revokeTrust] 开关供**自动过期**（[pruneOfflineOlderThan]）
     * 复用：过期只剪记忆，不撤销信任（理由见后者的 KDoc）。
     *
     * 两条支路共享同一份字段清单 —— 分头各写一遍，漏一个字段就会变成"删不干净"，
     * 而那类缺陷在真机上只表现为"它又回来了"。
     */
    private fun remove(nodeId: String, revokeTrust: Boolean): Boolean {
        val existed = lock.withLock { peers.remove(nodeId) != null }
        lock.withLock {
            profileTimes.remove(nodeId)
            profileConfirmationOnly.remove(nodeId)
            candidateEndpoints.remove(nodeId)
            if (revokeTrust) {
                peerPins.remove(nodeId)
                pinSources.remove(nodeId)
            }
        }
        if (existed) emit(PeerEvent.Removed(nodeId))
        return existed
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

/**
 * 这个名字是不是**我们自己合成的**（`node-<nodeId 前 8 位>`）？
 *
 * 扫码配对时二维码可能只带 nodeId（老载荷不带昵称），那时只能先造一个占位名。
 * 合成名**不是对端的资料**，所以它不该挡住真名进来（见 `touch` 里的
 * `replacesSyntheticName`）。判据收在这里，是因为生成点（`NodeRuntime.rememberPairedPeer`、
 * `RelayController.handleQrCode`）有两处，而"什么算合成名"必须只有一个定义 ——
 * 两处各写一遍，迟早出现一处认不出、于是那条 nodeId 永远卡在占位名上。
 */
fun isSyntheticNick(nick: String?): Boolean {
    val text = nick?.trim().orEmpty()
    if (text.length != SYNTHETIC_NICK_PREFIX.length + 8) return false
    if (!text.startsWith(SYNTHETIC_NICK_PREFIX)) return false
    return text.substringAfter(SYNTHETIC_NICK_PREFIX).all { it.isDigit() || it in 'a'..'f' }
}

/** 合成名字前缀。生成与识别**共用**这一个常量（两处各写一遍就会漂移）。 */
const val SYNTHETIC_NICK_PREFIX: String = "node-"
