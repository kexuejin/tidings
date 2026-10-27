// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.domain.model.FileCardUi
import com.kapp.tidings.domain.model.MessageUi
import com.kapp.tidings.domain.model.TransferRecordUi
import com.kapp.tidings.domain.model.TransferState
import com.kapp.tidings.core.nowMillis
import com.kapp.tidings.core.protocol.FileTransfer
import com.kapp.tidings.core.protocol.ConvId
import com.kapp.tidings.core.protocol.FilePurpose
import com.kapp.tidings.core.protocol.newId
import com.kapp.tidings.core.session.NodeRuntime
import com.kapp.tidings.core.transport.LocalFile
import com.kapp.tidings.core.transport.PartFile
import com.kapp.tidings.core.transport.TransferEvent
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 文件传输的入站编排与卡片同步：接受、拒绝、续传、取消，以及卡片入消息流。
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
internal class RelayControllerTransfersComponent(
    private val host: RelayController,
) {

/**
 * [RelayController] 的文件传输域扩展。
 *
 * 覆盖文件选择、Offer/Accept 协议交互、进度更新与残片管理。
 */

fun handlePickAndSendFiles(peerId: String) {
    val groupId = ConvId.groupIdOf(peerId)
    if (groupId != null) {
        val meta = host._state.value.settings.groups.firstOrNull { it.groupId == groupId }
        if (meta == null) {
            host.warn(BannerMessage.GroupUnknown)
            return
        }
        if (!meta.hasMember(host._state.value.selfNodeId)) {
            host.warn(BannerMessage.GroupLeft)
            return
        }
    }
    val rt = host.runtime ?: run { host.warn(BannerMessage.NodeNotStarted); return }
    host.scope.launch {
        val picked = runCatching { host.platform.filePicker.pick() }
            .onFailure {
                host.warn(
                    it.messageOrNull()?.let(BannerMessage::PickFailed) ?: BannerMessage.UnknownError,
                )
            }
            .getOrNull() ?: return@launch
        handleOfferFiles(rt, peerId, listOf(LocalFile(picked.path, picked.name, picked.sizeBytes)))
    }
}


fun handleSendFiles(
    peerId: String,
    files: List<LocalFile>,
    isFolder: Boolean = false,
    folderRootName: String? = null,
) {
    if (files.isEmpty()) return
    // **先判数量、给出具体数。** 传输层超限时只 `return null`，界面只会看到「这张传不出去」，
    // 说不出原因 —— 拖进一个几千文件的目录时，用户最需要知道的正是「有多少、上限多少」。
    if (files.size > FileTransfer.MAX_FILES_PER_TRANSFER) {
        host.warn(BannerMessage.TooManyFiles(files.size, FileTransfer.MAX_FILES_PER_TRANSFER))
        return
    }
    val groupId = ConvId.groupIdOf(peerId)
    if (groupId != null) {
        val meta = host._state.value.settings.groups.firstOrNull { it.groupId == groupId }
        if (meta == null) {
            host.warn(BannerMessage.GroupUnknown)
            return
        }
        if (!meta.hasMember(host._state.value.selfNodeId)) {
            host.warn(BannerMessage.GroupLeft)
            return
        }
    }
    val rt = host.runtime ?: run { host.warn(BannerMessage.NodeNotStarted); return }
    host.scope.launch { handleOfferFiles(rt, peerId, files, isFolder, folderRootName) }
}


suspend fun handleOfferFiles(
    rt: NodeRuntime,
    peerId: String,
    files: List<LocalFile>,
    isFolder: Boolean = false,
    folderRootName: String? = null,
) =
    handleOfferFilesWith(peerId, files) { member, msgId, groupId, groupRev ->
        runCatching {
            rt.offerFiles(
                peerId = member,
                files = files,
                msgId = msgId,
                // 文件夹传输：对端按 `name` 里的相对路径还原目录树，界面显示文件夹角标。
                isFolder = isFolder,
                folderRootName = folderRootName,
                groupId = groupId,
                groupRev = groupRev,
            )
        }.getOrNull()
    }

/**
 * 文件报价的乐观插入：**先把卡片放进消息流**，再等 [offer] 返回真实 transferId。
 *
 * 与私聊/群聊文本那条路同一条理由：报价要对每个成员跑一遍 ACK + TCP 兜底，
 * 那段时间界面原本什么都不显示（见 DeliveryState.Sending 的 KDoc）。这里先插一张
 * [TransferState.Offered] 的卡片，报价回来后把卡片就地换成带真实 transferId 的那张；
 * 报价失败时卡片转 [TransferState.Failed]，**不消失** —— 一张凭空消失的卡片读起来像
 * 「我根本没发过」。
 *
 * ## 临时键与登记的一致性
 *
 * 报价返回前不知道真实 transferId，所以卡片先挂在 [pendingTransferKey] 这个临时键上。
 * 报价返回后 [commitOptimisticTransferCard] 会把临时键换成真实 transferId，并同步
 * `transferConvs` / `transferMessages` 两张表 —— 三处必须指向同一条 [MessageUi]，
 * 否则后续 Progress / Completed 事件按真实 transferId 找不到卡片，会**另插一张**。
 *
 * @param offer 真正的报价动作（生产路径传 [NodeRuntime.offerFiles] 的调用）。抽成形参
 *   是为了让「先插卡片后等报价」这个**时序**能被闸门式测试钉住。
 */
internal suspend fun handleOfferFilesWith(
    peerId: String,
    files: List<LocalFile>,
    offer: suspend (member: String, msgId: String, groupId: String?, groupRev: Int?) -> String?,
) {
    if (files.isEmpty()) return
    val groupId = ConvId.groupIdOf(peerId)
    val name = files.first().name
    val msgId = newId()
    val pendingKey = pendingTransferKey(msgId)

    if (groupId != null) {
        val meta = host._state.value.settings.groups.firstOrNull { it.groupId == groupId }
        if (meta == null) {
            host.warn(BannerMessage.GroupUnknown)
            return
        }
        if (!meta.hasMember(host._state.value.selfNodeId)) {
            host.warn(BannerMessage.GroupLeft)
            return
        }
        val others = meta.members.filter { it != host._state.value.selfNodeId }
        if (others.isEmpty()) {
            host.warn(BannerMessage.GroupNeedsMember)
            return
        }
        val cardMessageId = insertOptimisticTransferCard(peerId, pendingKey, files, msgId)
        val spawnedTransfers = mutableListOf<String>()
        for (member in others) {
            val tId = offer(member, msgId, meta.groupId, meta.rev)
            if (tId != null) spawnedTransfers += tId
        }
        if (spawnedTransfers.isEmpty()) {
            failOptimisticTransferCard(peerId, pendingKey, cardMessageId)
            return
        }
        host.stateLock.withLock { host.core.groupTransferIds[msgId] = spawnedTransfers }
        commitOptimisticTransferCard(peerId, pendingKey, cardMessageId, spawnedTransfers, files, msgId)
        host._state.update {
            it.copy(banner = BannerUi(BannerMessage.FilesOffered(files.size, name), BannerTone.Info))
        }
        return
    }

    val cardMessageId = insertOptimisticTransferCard(peerId, pendingKey, files, msgId)
    val transferId = offer(peerId, msgId, null, null)
    if (transferId == null) {
        failOptimisticTransferCard(peerId, pendingKey, cardMessageId)
        return
    }
    commitOptimisticTransferCard(peerId, pendingKey, cardMessageId, listOf(transferId), files, msgId)
    host._state.update {
        it.copy(banner = BannerUi(BannerMessage.FilesOffered(files.size, name), BannerTone.Info))
    }
}

/**
 * 乐观卡片的临时 transferId 键。
 *
 * 以 `pending-` 开头：真实 transferId 是传输层生成的，不会以这个前缀开头，
 * 所以临时键不会和真实键撞上（撞上会让进度事件认到错误的卡片）。
 */
private fun pendingTransferKey(msgId: String): String = "pending-" + msgId

/**
 * 先把一张 [TransferState.Offered] 卡片插进 [convId]，返回它所在 [MessageUi] 的 id。
 *
 * 卡片先挂在 [pendingTransferKey] 上，报价返回后由 [commitOptimisticTransferCard] 换键。
 */
private fun insertOptimisticTransferCard(
    convId: String,
    pendingKey: String,
    files: List<LocalFile>,
    msgId: String,
): String {
    val messageId = host.localMessageId()
    val card = FileCardUi(
        transferId = pendingKey,
        name = files.first().name,
        size = files.sumOf { it.size },
        fileCount = files.size,
        mine = true,
        state = TransferState.Offered,
        msgId = msgId,
        localPaths = files.map { it.path },
    )
    host.stateLock.withLock {
        host.core.transferConvs[pendingKey] = convId
        host.core.transferMessages[pendingKey] = messageId
        host.handleAppendMessage(
            MessageUi(
                id = messageId,
                peerId = convId,
                mine = true,
                text = "",
                at = nowMillis(),
                file = card,
                senderId = host._state.value.selfNodeId,
            ),
        )
    }
    return messageId
}

/**
 * 报价返回后：临时键换成真实 transferId，并把那张卡片**就地改写**。
 *
 * 登记与卡片必须一致：`transferConvs[tId]` 给 Progress / Completed 事件定位会话，
 * `transferMessages[tId]` 给它们定位消息行，而卡片里的 transferId 是下一轮
 * [handleCurrentTransferCard] 的入口。三者指向同一条消息，才不会长出第二张卡片。
 */
private fun commitOptimisticTransferCard(
    convId: String,
    pendingKey: String,
    messageId: String,
    transferIds: List<String>,
    files: List<LocalFile>,
    msgId: String,
) {
    val card = FileCardUi(
        transferId = transferIds.first(),
        name = files.first().name,
        size = files.sumOf { it.size },
        fileCount = files.size,
        mine = true,
        state = TransferState.Offered,
        msgId = msgId,
        localPaths = files.map { it.path },
    )
    host.stateLock.withLock {
        host.core.transferConvs.remove(pendingKey)
        host.core.transferMessages.remove(pendingKey)
        for (tId in transferIds) {
            host.core.transferConvs[tId] = convId
            host.core.transferMessages[tId] = messageId
        }
        val existing = host.core.threads[convId]?.firstOrNull { it.id == messageId } ?: return@withLock
        host.handleReplaceMessage(convId, existing.copy(file = card))
    }
}

/**
 * 报价一个都没成功：把那张乐观卡片转成 [TransferState.Failed]，**不删掉它**。
 *
 * 旧行为是只弹一条横幅、卡片根本不出现 —— 用户看到的是「点了发送，什么都没有」。
 */
private fun failOptimisticTransferCard(convId: String, pendingKey: String, messageId: String) {
    host.stateLock.withLock {
        host.core.transferConvs.remove(pendingKey)
        host.core.transferMessages.remove(pendingKey)
        val existing = host.core.threads[convId]?.firstOrNull { it.id == messageId } ?: return@withLock
        val card = existing.file?.copy(state = TransferState.Failed, reason = "offer-failed") ?: return@withLock
        host.handleReplaceMessage(convId, existing.copy(file = card))
    }
    host.warn(BannerMessage.TransferFailed("offer-failed"))
}


fun handleAcceptTransfer(transferId: String) {
    host.runtime?.acceptTransfer(transferId)
}


fun handleDeclineTransfer(transferId: String) {
    host.runtime?.declineTransfer(transferId)
}


fun handleCancelTransfer(transferId: String) {
    val rt = host.runtime ?: run { host.warn(BannerMessage.NodeNotStarted); return }
    host.scope.launch {
        val related = host.stateLock.withLock {
            host.core.groupTransferIds.values.firstOrNull { transferId in it } ?: listOf(transferId)
        }
        for (tId in related) {
            runCatching { rt.cancelTransfer(tId) }
        }
    }
}


fun handleDiscardPartial(path: String) {
    host.runtime?.discardPartial(path)
}


fun handleLeftoverParts(): List<PartFile> = host.runtime?.leftoverParts().orEmpty()


fun handleClearLeftoverParts(): Int {
    val rt = host.runtime ?: return 0
    val parts = rt.leftoverParts()
    parts.forEach { rt.discardPartFile(it.path) }
    return parts.size
}


fun handleOnTransfer(event: TransferEvent) {
    when (event) {
        is TransferEvent.Offered -> {
            val offer = event.offer
            val offerGroupId = offer.groupId
            val convId = if (offerGroupId != null) ConvId.group(offerGroupId) else offer.peerId
            host.stateLock.withLock { host.core.transferConvs[offer.transferId] = convId }
            handleUpsertTransferCard(
                convId,
                FileCardUi(
                    transferId = offer.transferId,
                    name = offer.rootName,
                    size = offer.totalSize,
                    fileCount = offer.files.size,
                    mine = false,
                    state = TransferState.Offered,
                    msgId = offer.msgId,
                    purpose = offer.purpose,
                    autoAccepted = offer.autoAccepted,
                    tableText = offer.tableText,
                    tableTextTruncated = offer.tableTextTruncated == true,
                    isFolder = offer.isFolder,
                ),
                senderId = if (offer.groupId != null) offer.peerId else null,
            )
            if (offer.purpose == FilePurpose.SHARE_GET) {
                host.runtime?.acceptTransfer(offer.transferId)
            }
        }

        is TransferEvent.Progress -> {
            val convId = host.stateLock.withLock { host.core.transferConvs[event.transferId] } ?: event.peerId
            val card = handleCurrentTransferCard(convId, event.transferId) ?: FileCardUi(
                transferId = event.transferId,
                name = event.fileId,
                size = event.total,
                mine = !event.incoming,
                state = TransferState.Active,
            )
            handleUpsertTransferCard(
                convId,
                card.copy(
                    done = event.done,
                    // **分母只在 Offered 里定，这里不改。**
                    //
                    // 原来写 `size = maxOf(card.size, event.total)`：`card.size` 是
                    // `offer.totalSize`（**整次传输**总额），而 `event.total` 是**当前文件**
                    // 的大小。多文件传输时两者交替当分母，`fraction = done / size` 于是
                    // 一会儿按单文件算、一会儿按总额算 —— 真机现场：同一张图上的百分比
                    // 在 20% 与 60% 之间来回跳，看起来像两个下载同时在跑。
                    //
                    // 单文件时两者相等，看不出问题；所以这条只有在多文件传输上才会暴露。
                    size = card.size,
                    state = TransferState.Active,
                ),
            )
        }

        is TransferEvent.Completed -> {
            val convId = host.stateLock.withLock { host.core.transferConvs[event.transferId] } ?: event.peerId
            // **找不到卡片也要把「传完了」这条事实落下来，不能 `?: return`。**
            //
            // 原来这里写 `?: return`，而上一段 `Progress` 用的是 `?: FileCardUi(...)`
            // 自建卡片 —— 同一个 helper、两种处置。后果：卡片一旦查不到（会话键与
            // `transferConvs` 不一致、或 `transferMessages` 里还没有这一条），`Completed`
            // 被整条丢掉，那张卡永远停在 `Active` ⇒ 进度条卡在 100% 不动。
            //
            // `Progress` 能容忍「卡片还没建出来」，`Completed` 更不能丢：它带的是
            // `savedPaths`（文件真的落盘了），丢了等于用户不知道文件在哪。
            val card = handleCurrentTransferCard(convId, event.transferId) ?: FileCardUi(
                transferId = event.transferId,
                // 兜底时拿不到真名与总额（`Completed` 事件只带 savedPaths 与方向）——
                // 用路径末段当名字，比空字符串有用；大小未知就 0，卡片会显示「已完成」而不是百分比。
                name = event.savedPaths.firstOrNull()?.substringAfterLast('/') ?: event.transferId,
                size = 0L,
                mine = !event.incoming,
                state = TransferState.Completed,
            )
            handleUpsertTransferCard(
                convId,
                card.copy(
                    state = TransferState.Completed,
                    done = card.size,
                    savedPaths = event.savedPaths,
                ),
            )
            if (card.purpose == FilePurpose.SHARE_PUT && !card.mine) {
                val peer = host._state.value.peer(convId)
                val recent = CabinetRecentUpload(
                    transferId = event.transferId,
                    uploaderNodeId = convId,
                    uploaderNick = peer?.nick ?: convId.take(8),
                    fileCount = card.fileCount,
                    totalSize = card.size,
                    ts = nowMillis(),
                )
                val curUploads = host._state.value.settings.cabinetRecentUploads
                host.handlePersist(host._state.value.settings.copy(cabinetRecentUploads = (curUploads + recent).takeLast(20)))
                host.publish()
            }
            recordTransferLog(
                TransferRecordUi(
                    transferId = event.transferId,
                    name = card.name,
                    size = card.size,
                    isIncoming = event.incoming,
                    at = nowMillis(),
                    success = true,
                    path = event.savedPaths.firstOrNull(),
                    isFolder = card.isFolder,
                )
            )
        }

        is TransferEvent.Failed -> {
            val convId = host.stateLock.withLock { host.core.transferConvs[event.transferId] } ?: event.peerId
            val card = handleCurrentTransferCard(convId, event.transferId)
            if (card == null) {
                host.warn(BannerMessage.TransferFailed(event.reason))
            } else {
                handleUpsertTransferCard(
                    convId,
                    card.copy(state = TransferState.Failed, reason = event.reason),
                )
            }
            recordTransferLog(
                TransferRecordUi(
                    transferId = event.transferId,
                    name = card?.name ?: event.transferId,
                    size = card?.size ?: 0L,
                    isIncoming = event.incoming,
                    at = nowMillis(),
                    success = false,
                    isFolder = card?.isFolder ?: false,
                )
            )
        }

        is TransferEvent.Declined -> {
            val convId = host.stateLock.withLock { host.core.transferConvs[event.transferId] } ?: event.peerId
            val card = handleCurrentTransferCard(convId, event.transferId) ?: return
            handleUpsertTransferCard(convId, card.copy(state = TransferState.Declined))
        }
    }
}


fun handleResumeTransfer(transferId: String) {
    val rt = host.runtime ?: run { host.warn(BannerMessage.NodeNotStarted); return }
    val convId = host.stateLock.withLock { host.core.transferConvs[transferId] } ?: return
    val card = handleCurrentTransferCard(convId, transferId) ?: return
    if (card.mine) {
        // 出站失败只有「重发」没有「继续」（对齐 files.ts）
        return
    }
    handleUpsertTransferCard(convId, card.copy(state = TransferState.Active, reason = null))
    rt.resumeTransfer(transferId)
}


fun handleClearTransferLogs() {
    host._state.update { it.copy(transferLogs = emptyList()) }
}

private fun recordTransferLog(record: TransferRecordUi) {
    host._state.update {
        val existing = it.transferLogs.filterNot { log -> log.transferId == record.transferId }
        it.copy(transferLogs = (listOf(record) + existing).take(30))
    }
}


fun handleCurrentTransferCard(convId: String, transferId: String): FileCardUi? =
    host.stateLock.withLock {
        val messageId = host.core.transferMessages[transferId] ?: return@withLock null
        host.core.threads[convId]?.firstOrNull { it.id == messageId }?.file
    }


fun handleUpsertTransferCard(convId: String, card: FileCardUi, senderId: String? = null) {
    // host.stateLock 可重入：下面调到的 host.handleReplaceMessage / host.handleAppendMessage / host.refreshPeers / host.handlePersistThreads
    // 也会取这把锁，所以整段可以原子地跑完（host.core.transferMessages 与 host.core.threads 的一致读改写）。
    host.stateLock.withLock {
        val existingId = host.core.transferMessages[card.transferId]
        val existing = existingId?.let { id -> host.core.threads[convId]?.firstOrNull { it.id == id } }
        if (existing != null) {
            if (existing.notice != null) return@withLock
            host.handleReplaceMessage(convId, existing.copy(file = card))
            return@withLock
        }
        val id = existingId ?: host.localMessageId()
        host.core.transferMessages[card.transferId] = id
        host.handleAppendMessage(
            MessageUi(
                id = id,
                peerId = convId,
                mine = card.mine,
                text = "",
                at = nowMillis(),
                file = card,
                senderId = senderId ?: if (card.mine) host._state.value.selfNodeId else null,
            ),
            incoming = !card.mine,
        )
    }
}
}
