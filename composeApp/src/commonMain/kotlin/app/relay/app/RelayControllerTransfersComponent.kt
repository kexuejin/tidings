// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import app.relay.app.model.FileCardUi
import app.relay.app.model.MessageUi
import app.relay.app.model.TransferRecordUi
import app.relay.app.model.TransferState
import app.relay.core.nowMillis
import app.relay.core.protocol.ConvId
import app.relay.core.protocol.FilePurpose
import app.relay.core.protocol.newId
import app.relay.core.session.NodeRuntime
import app.relay.core.transport.LocalFile
import app.relay.core.transport.PartFile
import app.relay.core.transport.TransferEvent
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


fun handleSendFiles(peerId: String, files: List<LocalFile>) {
    if (files.isEmpty()) return
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
    host.scope.launch { handleOfferFiles(rt, peerId, files) }
}


suspend fun handleOfferFiles(rt: NodeRuntime, peerId: String, files: List<LocalFile>) {
    val groupId = ConvId.groupIdOf(peerId)
    val name = files.first().name
    val msgId = newId()

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
        val spawnedTransfers = mutableListOf<String>()
        for (member in others) {
            val tId = runCatching {
                rt.offerFiles(
                    peerId = member,
                    files = files,
                    msgId = msgId,
                    groupId = meta.groupId,
                    groupRev = meta.rev,
                )
            }.getOrNull()
            if (tId != null) {
                spawnedTransfers += tId
                host.stateLock.withLock { host.core.transferConvs[tId] = peerId }
            }
        }
        if (spawnedTransfers.isEmpty()) {
            host.warn(BannerMessage.TransferFailed("offer-failed"))
            return
        }
        val cardMessageId = host.localMessageId()
        host.stateLock.withLock {
            host.core.groupTransferIds[msgId] = spawnedTransfers
            for (tId in spawnedTransfers) {
                host.core.transferMessages[tId] = cardMessageId
            }
        }
        val primaryTransferId = spawnedTransfers.first()
        handleUpsertTransferCard(
            peerId,
            FileCardUi(
                transferId = primaryTransferId,
                name = name,
                size = files.sumOf { it.size },
                fileCount = files.size,
                mine = true,
                state = TransferState.Offered,
                msgId = msgId,
                localPaths = files.map { it.path },
            ),
        )
        host._state.update {
            it.copy(banner = BannerUi(BannerMessage.FilesOffered(files.size, name), BannerTone.Info))
        }
        return
    }

    val transferId = runCatching { rt.offerFiles(peerId, files, msgId = msgId) }.getOrNull()
    if (transferId == null) {
        host.warn(BannerMessage.TransferFailed("offer-failed"))
        return
    }
    host.stateLock.withLock { host.core.transferConvs[transferId] = peerId }
    handleUpsertTransferCard(
        peerId,
        FileCardUi(
            transferId = transferId,
            name = name,
            size = files.sumOf { it.size },
            fileCount = files.size,
            mine = true,
            state = TransferState.Offered,
            msgId = msgId,
            localPaths = files.map { it.path },
        ),
    )
    host._state.update {
        it.copy(banner = BannerUi(BannerMessage.FilesOffered(files.size, name), BannerTone.Info))
    }
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
                    size = maxOf(card.size, event.total),
                    state = TransferState.Active,
                ),
            )
        }

        is TransferEvent.Completed -> {
            val convId = host.stateLock.withLock { host.core.transferConvs[event.transferId] } ?: event.peerId
            val card = handleCurrentTransferCard(convId, event.transferId) ?: return
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
