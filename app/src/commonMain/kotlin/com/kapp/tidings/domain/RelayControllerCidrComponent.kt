// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.domain.model.ScanProgressUi
import com.kapp.tidings.core.nowMillis
import com.kapp.tidings.core.protocol.CidrUtils
import com.kapp.tidings.core.protocol.Codec
import com.kapp.tidings.core.protocol.Envelope
import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.SCAN_RANGES_PER_PACKET
import com.kapp.tidings.core.protocol.ScanRangeSummary
import com.kapp.tidings.core.protocol.makeEnvelope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * CIDR 手动添加节点：把一段网段展开成可点的一批节点，并把扫描结果按段发给对端。
 *
 * 切出来的理由：这一族的演化动力与其它 7 个分片互不相干。过去它们都是
 * `internal fun RelayController.` 的顶层扩展，凑在一起约 3000 行；改其中一族要在
 * 另外六族里找它依赖了什么。
 *
 * @param host 提供本族用到的控制器成员与同包顶层扩展。逐个 `host.` 前缀而不是
 *   持有整个控制器，是为了让**依赖面完全显式**：读方法体就知道这一族碰了哪些状态。
 *   只对本族真正用到的名字加前缀 —— 同名的函数形参会遮蔽控制器成员（`state` 就是
 *   一例：控制器有 `state: StateFlow<RelayUiState>`，而 agent 帧形参叫 `state: String?`），
 *   给它加前缀会直接产出语法垃圾。
 */
internal class RelayControllerCidrComponent(
    private val host: RelayController,
) {

/**
 * [RelayController] 的 CIDR 网段探测与 Gossip 同步域扩展。
 *
 * 对齐茶话间 `src/main/net/range-sync.ts` & `src/main/net/discovery.ts`：
 * 1. CIDR 配置管理：校验标准化、持久化至 [RelaySettings.scanRanges]；
 * 2. 入站 `scan-ranges` 处理：自动吸收对端分享的合法网段记录（去重、不覆盖本地条目、不自动全量扫描）；
 * 3. 出站 Gossip 分享：向对端单播分享本机已知网段（上限 10 条/包）；
 * 4. 扫描调度：分批对网段内主机逐个单播 entry 探活，节流防风暴并实时更新 [ScanProgressUi]。
 */

fun handleAddScanRange(rawCidr: String) {
    val normalized = CidrUtils.normalizeCidr(rawCidr)
    if (normalized == null) {
        host.warn(BannerMessage.ScanRangeInvalid)
        return
    }
    // 检查是否可展开（/22 及更小，<= 1024 主机）
    val hosts = CidrUtils.parseCidrHosts(normalized)
    if (hosts == null) {
        host.warn(BannerMessage.ScanRangeInvalid)
        return
    }

    val currentSettings = host._state.value.settings
    if (currentSettings.scanRanges.any { it.cidr == normalized }) {
        host.info(BannerMessage.ScanRangeAdded(normalized))
        return
    }

    val newEntry = ScanRangeEntry(
        cidr = normalized,
        addedAt = nowMillis(),
        source = "self",
    )
    val nextSettings = currentSettings.copy(
        scanRanges = currentSettings.scanRanges + newEntry,
    )
    host.handlePersist(nextSettings)
    host.info(BannerMessage.ScanRangeAdded(normalized))
    host.publish()

    // 抖动后向所有在线对端分享新网段
    handleScheduleShareScanRangesSoon()
}


fun handleRemoveScanRange(cidr: String) {
    val currentSettings = host._state.value.settings
    val nextSettings = currentSettings.copy(
        scanRanges = currentSettings.scanRanges.filterNot { it.cidr == cidr },
    )
    if (nextSettings != currentSettings) {
        host.handlePersist(nextSettings)
        host.publish()
    }
}

/**
 * 收到远端节点发送的 `scan-ranges` 报文：解析、校验并合并到已知网段列表中。
 */


/**
 * 收到远端节点发送的 `scan-ranges` 报文：解析、校验并合并到已知网段列表中。
 */
fun handleOnScanRangesReceived(envelope: Envelope) {
    val decodedRanges = Codec.decodeScanRanges(envelope.payload) ?: return
    val fromNodeId = envelope.from
    val peer = host._state.value.peers.firstOrNull { it.nodeId == fromNodeId }
    val peerNick = peer?.nick ?: fromNodeId.take(8)

    val currentSettings = host._state.value.settings
    val existingCidrs = currentSettings.scanRanges.map { it.cidr }.toSet()
    val additions = mutableListOf<ScanRangeEntry>()

    for (summary in decodedRanges) {
        val cidr = CidrUtils.normalizeCidr(summary.cidr) ?: continue
        if (cidr in existingCidrs || additions.any { it.cidr == cidr }) continue
        additions += ScanRangeEntry(
            cidr = cidr,
            addedAt = if (summary.addedAt > 0) summary.addedAt else nowMillis(),
            source = "peer",
            sourceNodeId = fromNodeId,
            sourceNick = peerNick,
        )
    }

    if (additions.isNotEmpty()) {
        val nextSettings = currentSettings.copy(
            scanRanges = currentSettings.scanRanges + additions,
        )
        host.handlePersist(nextSettings)
        host.publish()
    }
}

/**
 * 节点上线或手动触发时，向对端发送本机已知的所有网段。
 */


/**
 * 节点上线或手动触发时，向对端发送本机已知的所有网段。
 */
fun handleShareScanRangesToPeer(targetNodeId: String) {
    val rt = host.runtime ?: return
    val ranges = host._state.value.settings.scanRanges
    if (ranges.isEmpty()) return

    val summaries = ranges.map {
        ScanRangeSummary(cidr = it.cidr, addedAt = it.addedAt)
    }

    host.scope.launch {
        for (chunk in summaries.chunked(SCAN_RANGES_PER_PACKET)) {
            val payload = Codec.scanRangesPayload(chunk)
            val envelope = makeEnvelope(
                type = MsgTypes.SCAN_RANGES,
                from = host._state.value.selfNodeId,
                payload = payload,
            )
            runCatching {
                rt.sendReliableNoQueue(targetNodeId, envelope)
            }
        }
    }
}

private var shareScanRangesJob: Job? = null


fun handleScheduleShareScanRangesSoon() {
    shareScanRangesJob?.cancel()
    shareScanRangesJob = host.scope.launch {
        // 抖动 500ms - 2000ms 避免网络峰值
        delay((500..2000).random().toLong())
        val onlinePeers = host._state.value.peers.filter { it.online }
        for (p in onlinePeers) {
            handleShareScanRangesToPeer(p.nodeId)
        }
    }
}

private var activeScanJob: Job? = null

/**
 * 触发网段探测：对指定 CIDR（或已配置的所有 CIDR）展开主机列表并逐个发送探活包。
 */


/**
 * 触发网段探测：对指定 CIDR（或已配置的所有 CIDR）展开主机列表并逐个发送探活包。
 */
fun handleTriggerCidrScan(targetCidr: String?) {
    val rt = host.runtime ?: run {
        host.warn(BannerMessage.NodeNotStarted)
        return
    }

    val cidrList = if (targetCidr != null) {
        listOf(targetCidr)
    } else {
        host._state.value.settings.scanRanges.map { it.cidr }
    }

    if (cidrList.isEmpty()) return

    val plan = CidrUtils.buildCidrHostPlan(cidrList)
    if (plan.isEmpty()) return

    activeScanJob?.cancel()
    host.info(BannerMessage.ScanRangeTriggered(plan.size))

    activeScanJob = host.scope.launch {
        var scanned = 0
        val total = plan.size
        val currentCidrDisplay = targetCidr ?: (if (cidrList.size == 1) cidrList.first() else "${cidrList.size} ranges")

        host._state.update {
            it.copy(
                scanProgress = ScanProgressUi(
                    currentCidr = currentCidrDisplay,
                    scannedHosts = 0,
                    totalHosts = total,
                    discoveredCount = it.peers.count { p -> p.online },
                )
            )
        }

        val targetPort = host._state.value.settings.udpPort
        // 循环变量不能叫 host：那会遮蔽构造参数 host，让本函数后半段的
        // host._state 全变成对一个 String 的成员访问。
        for (target in plan) {
            runCatching {
                rt.announceTo(target, targetPort)
            }
            scanned++
            if (scanned % 4 == 0 || scanned == total) {
                host._state.update {
                    it.copy(
                        scanProgress = it.scanProgress?.copy(
                            scannedHosts = scanned,
                            discoveredCount = it.peers.count { p -> p.online },
                        )
                    )
                }
            }
            // 每台主机间隔 15ms，避免路由器丢包与网络拥塞
            delay(15)
        }

        // 扫描完成稍等 2 秒后清理进度条
        delay(2000)
        host._state.update { it.copy(scanProgress = null) }
    }
}
}
