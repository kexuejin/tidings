// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import kotlin.concurrent.Volatile
import kotlinx.serialization.Serializable

/**
 * 传输层的**数据传输类**：进程内几张表里每一项的形状。
 *
 * 切出来的理由：这些类型没有任何行为，也不碰 [TransferManager] 的锁 —— 它们是
 * 「数据所有权」的边界，不是「传输逻辑」的一部分。原来它们和 [TransferManager]
 * 挤在一个文件里，读协议形状要翻 1100 行。
 *
 * 全部是 `data class` / `sealed interface`，被 [TransferManager]、UI 层与
 * `NodeRuntime` 共同引用，因此可见性一律不变。
 */
/** 用户挑中的一个本地文件。[path] 是绝对路径，[name] 是发给对方看的相对名。 */
data class LocalFile(val path: String, val name: String, val size: Long)

/**
 * 显式作废待处理传输时用的原因串。
 *
 * 上层据此把"本地停机/重启导致的作废"与"对端拒收"区分开：两者都会让卡片消失，但只有
 * 前者值得在界面上留一句解释（"上次没传完，已作废"）。
 */
const val ABANDON_REASON_SHUTDOWN: String = "abandoned-shutdown"

/**
 * 磁盘上一个半截文件（`.part`）。
 *
 * 只在无法被任何在途传输认领时才可删 —— 见 [TransferManager.sweepStaleParts]。
 */
data class PartFile(val path: String, val size: Long)

/**
 * 启动清扫的结果。
 *
 * [scanned] 为假表示这个根目录被**故意跳过**了（见 [TransferManager.sweepStaleParts]），
 * 不是"扫过且没找到" —— 上层要能区分这两件事，否则会把"我没敢扫"读成"很干净"。
 */
data class PartSweepReport(
    val root: String,
    val scanned: Boolean,
    val deleted: List<String>,
    val freedBytes: Long,
    val failed: List<String>,
)

/** 待处理传输的一次快照：入站邀请 + 出站报价。 */
data class PendingTransfers(
    val offers: List<IncomingOffer>,
    val outgoing: List<OutgoingTransfer>,
)

/**
 * 显式作废的结果。
 *
 * 返回给上层而不是只发事件：事件是"发生了一件事"，而重启对账要的是**清单**——
 * 哪些 transferId 被作废了、对端是谁、原本传什么，界面据此把持久化的旧卡片标成已作废。
 */
data class PendingAbandonReport(
    val reason: String,
    val offers: List<IncomingOffer>,
    val outgoing: List<OutgoingTransfer>,
) {
    val isEmpty: Boolean get() = offers.isEmpty() && outgoing.isEmpty()

    /** 被作废的全部 transferId，`offers` 与 `outgoing` 的并集。 */
    val transferIds: Set<String> get() = offers.map { it.transferId }.toSet() + outgoing.map { it.transferId }
}

/**
 * 重启对账用的一次性快照。
 *
 * **为什么需要它**：待处理 offer / 出站报价都是进程内的内存表（[TransferManager] 的
 * `offers` / `outgoing`），重启后没有任何"恢复"可言；而磁盘上会留下上一进程的 `.part`。
 * 上层（UI 会话是持久化的，会画出一张永远不动的"待接收"卡片）唯一能做的正确动作是：
 * 拿 [pendingTransferIds] 跟自己的卡片对账，差集里的就是**上一次进程的孤儿**，显式作废；
 * 磁盘那一半由 [leftoverParts] 交代（启动时已被 [TransferManager.sweepStaleParts] 清掉）。
 */
data class TransferBacklog(
    val offers: List<IncomingOffer>,
    val outgoing: List<OutgoingTransfer>,
    val leftoverParts: List<PartFile>,
) {
    /** 本进程当前真正挂着的 transferId（重启后通常为空 —— 空就是"没有任何可恢复的"）。 */
    val pendingTransferIds: Set<String> get() = offers.map { it.transferId }.toSet() + outgoing.map { it.transferId }

    val isEmpty: Boolean get() = pendingTransferIds.isEmpty() && leftoverParts.isEmpty()
}

/** 一次出站传输（我们发文件，对方来拉）。 */
data class OutgoingTransfer(
    val transferId: String,
    val peerId: String,
    val rootName: String,
    val files: List<OutgoingFile>,
    val totalSize: Long,
    /**
     * 对方回了 `accept` 才置真。
     *
     * `TransferServer` 的 `resolve` 只认这个标志——拿着 transferId 不等于有资格拉文件，
     * 授权点只有这一处（对齐 TS `OutgoingLookup.resolve` 的语义）。
     */
    @Volatile var accepted: Boolean = false,
)

/** offer 分片重组完成后，交给 UI 决定收不收。 */
data class IncomingOffer(
    val transferId: String,
    val peerId: String,
    val rootName: String,
    val files: List<IncomingFilePlan>,
    val totalSize: Long,
    val purpose: String?,
    val msgId: String?,
    val expiresAt: Long?,
    /**
     * 群聊媒体的群 id（协议 §3）：非空表示这是群聊的一次点对点投递。
     *
     * 这是收端**唯一**能分辨群聊与私聊的地方，群图的内联上限只有私聊的一半。
     */
    val groupId: String? = null,
    val groupRev: Int? = null,
    /**
     * 表格图片消息的**文字视图**（≤4096B UTF-8）。
     *
     * 只有 `purpose:"image"` 的单图 offer 会带它；[tableTextTruncated] 为真表示发送端已按上限
     * 截断（图片本身仍完整）。收端拿它做同一气泡内的"图片 / 文字"切换。
     */
    val tableText: String? = null,
    val tableTextTruncated: Boolean? = null,
    val isFolder: Boolean = false,
)

/**
 * 对端在节点表里的可达地址。
 *
 * 同时也是配对码候选池（`addrs`）的纯值形态：`@Serializable` 是为了把那一池地址按 nodeId
 * 存进 `stores/peer-endpoints.json`（见 [PeerEndpointStore]）—— 换网之后要靠它们兜底。
 */
@Serializable
data class PeerEndpoint(val host: String, val port: Int)

/** 传输层的对外事件。UI 只消费这个，不碰 TCP。 */
sealed interface TransferEvent {
    /**
     * 收到一份完整的 offer。
     *
     * 自动接收的（小图片）也会发这个 —— UI 需要 `rootName`/`totalSize` 才画得出卡片；
     * 是否要用户点头看 [IncomingOffer.purpose]，不看有没有这条事件。
     */
    data class Offered(val offer: IncomingOffer) : TransferEvent

    /** [done] 是累计字节数（含续传起点），[incoming] 为真表示我们在收。 */
    data class Progress(
        val transferId: String,
        val peerId: String,
        val fileId: String,
        val done: Long,
        val total: Long,
        val incoming: Boolean,
    ) : TransferEvent

    data class Completed(
        val transferId: String,
        val peerId: String,
        val incoming: Boolean,
        val savedPaths: List<String>,
    ) : TransferEvent

    data class Failed(
        val transferId: String,
        val peerId: String,
        val incoming: Boolean,
        val reason: String,
    ) : TransferEvent

    /** 对方拒收或取消。 */
    data class Declined(val transferId: String, val peerId: String) : TransferEvent
}
